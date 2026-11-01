package com.scribe.app.ai

import com.scribe.app.cloud.GenResult
import com.scribe.app.cloud.Part
import com.scribe.app.cloud.Schema
import com.scribe.app.cloud.Turn
import com.scribe.app.cloud.Vertex
import com.scribe.app.data.ActionItem
import com.scribe.app.data.Chapter
import com.scribe.app.data.MindNode
import com.scribe.app.data.Notes
import com.scribe.app.data.Recording
import com.scribe.app.data.Segment
import com.scribe.app.data.SettingsStore
import com.scribe.app.data.Template
import com.scribe.app.data.Transcript
import com.scribe.app.util.AppJson
import com.scribe.app.util.formatClock
import com.scribe.app.util.formatDateTime
import kotlinx.serialization.Serializable
import kotlin.math.ceil

class AiService(private val vertex: Vertex, private val settings: SettingsStore) {

    // ---------- Transcription ----------

    @Serializable
    private data class TranscriptOut(val language: String = "", val segments: List<Segment> = emptyList())

    private val transcriptSchema = Schema.obj(
        "language" to Schema.str("BCP-47 code of the main spoken language"),
        "segments" to Schema.arr(
            Schema.obj(
                "speaker" to Schema.str("Speaker label, e.g. 'Speaker 1', or the person's name if clearly stated"),
                "start" to Schema.num("Start time in seconds from the beginning of the WHOLE recording"),
                "end" to Schema.num("End time in seconds"),
                "text" to Schema.str("Verbatim text of this utterance"),
            )
        ),
    )

    /**
     * Transcribes audio already uploaded to Cloud Storage. Long recordings are processed in windows so the
     * output never exceeds the model's output token limit; speaker labels are kept consistent between windows.
     */
    suspend fun transcribe(
        gsUri: String,
        mimeType: String,
        durationMs: Long,
        onProgress: (String) -> Unit = {},
    ): Transcript {
        val windowSec = 20 * 60
        val totalSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val windows = if (totalSec <= 25 * 60) 1 else ceil(totalSec / windowSec).toInt()
        val lang = settings.value.language
        val all = mutableListOf<Segment>()
        var language = ""
        for (w in 0 until windows) {
            val from = w * windowSec
            val to = if (windows == 1) totalSec.toInt() + 1 else minOf((w + 1) * windowSec, totalSec.toInt() + 1)
            if (windows > 1) onProgress("Transcribing part ${w + 1} of $windows")
            val knownSpeakers = all.map { it.speaker }.distinct()
            val prompt = buildString {
                appendLine("Transcribe this audio recording accurately and completely.")
                if (windows > 1) {
                    appendLine("ONLY transcribe the portion from ${formatClock(from * 1000L)} to ${formatClock(to * 1000L)} of the recording.")
                    appendLine("Timestamps must be absolute (seconds from the very start of the file).")
                }
                appendLine("- Perform speaker diarization. Label speakers 'Speaker 1', 'Speaker 2', ... in order of first appearance.")
                appendLine("- If someone's name is clearly stated (e.g. introductions), use that name as the label instead.")
                if (knownSpeakers.isNotEmpty()) appendLine("- Speakers already identified earlier in this recording: ${knownSpeakers.joinToString()}. Reuse these labels for the same voices.")
                appendLine("- Split into segments at every change of speaker and at least every ~30 seconds.")
                appendLine("- Keep the original language${if (lang.isNotBlank()) " (expected: $lang)" else ""}; do not translate.")
                appendLine("- Remove pure filler sounds (um, uh) but otherwise keep wording verbatim. Mark inaudible parts as [inaudible].")
                appendLine("- If there is no speech, return an empty segments array.")
            }
            val res = vertex.generate(
                turns = listOf(Turn("user", listOf(Part.GcsFile(gsUri, normaliseMime(mimeType)), Part.Text(prompt)))),
                schema = transcriptSchema,
                temperature = 0.1,
                maxOutputTokens = 65535,
            )
            val out = parse<TranscriptOut>(res)
            if (language.isBlank()) language = out.language
            all += out.segments.filter { it.text.isNotBlank() }
        }
        return Transcript(language = language, segments = all.sortedBy { it.start })
    }

    private fun normaliseMime(m: String): String = when {
        m.contains("m4a") || m == "audio/x-m4a" || m == "audio/aac-adts" -> "audio/mp4"
        m == "audio/x-wav" -> "audio/wav"
        m.startsWith("audio/") || m.startsWith("video/") -> m
        else -> "audio/mp4"
    }

    // ---------- Notes / summary / mind map ----------

    @Serializable
    private data class ActionOut(val task: String = "", val owner: String = "", val due: String = "", val priority: String = "")

    @Serializable
    private data class NotesOut(
        val title: String = "",
        val summary: String = "",
        val keyPoints: List<String> = emptyList(),
        val actionItems: List<ActionOut> = emptyList(),
        val decisions: List<String> = emptyList(),
        val questions: List<String> = emptyList(),
        val topics: List<String> = emptyList(),
        val chapters: List<Chapter> = emptyList(),
        val mindMap: MindNode? = null,
    )

    private val notesSchema = Schema.obj(
        "title" to Schema.str("Short, specific title (max 8 words)"),
        "summary" to Schema.str("The main write-up in GitHub-flavoured markdown, following the template instructions"),
        "keyPoints" to Schema.strArr("5-10 most important takeaways, one sentence each"),
        "actionItems" to Schema.arr(
            Schema.obj(
                "task" to Schema.str("Concrete, actionable task starting with a verb"),
                "owner" to Schema.str("Person responsible, or empty"),
                "due" to Schema.str("Due date/time as stated (ISO yyyy-mm-dd if a date can be inferred), or empty"),
                "priority" to Schema.str("high, medium or low"),
                required = listOf("task"),
            )
        ),
        "decisions" to Schema.strArr("Decisions that were made"),
        "questions" to Schema.strArr("Open questions / unresolved issues"),
        "topics" to Schema.strArr("2-5 short topic tags (1-2 words each)"),
        "chapters" to Schema.arr(
            Schema.obj(
                "start" to Schema.num("Start time in seconds"),
                "title" to Schema.str(),
                "summary" to Schema.str("One sentence"),
            ),
            "Chronological chapters covering the whole recording",
        ),
        "mindMap" to Schema.mindNode(4),
    )

    suspend fun generateNotes(rec: Recording, transcript: Transcript, template: Template): Notes {
        val system = """You are an expert note-taker and analyst. You turn recorded conversations into accurate,
well-structured, genuinely useful notes. Never invent facts that are not in the transcript. Use the speakers'
names where known. The current date context is the recording date: ${formatDateTime(rec.createdAt)}.
Resolve relative dates ("next Friday") into absolute dates based on that date when possible."""
        val prompt = buildString {
            appendLine("TEMPLATE INSTRUCTIONS for the 'summary' field:")
            appendLine(template.instructions)
            appendLine()
            appendLine("MIND MAP: build 'mindMap' as a hierarchy: root label = the central subject; 3-7 main branches;")
            appendLine("each with 2-5 children; go deeper only where useful. Labels must be short (max ~6 words).")
            appendLine()
            if (rec.bookmarks.isNotEmpty()) {
                appendLine("The user flagged these moments as important while recording — give them extra weight:")
                rec.bookmarks.forEach { b -> appendLine("- ${formatClock(b.timeMs)}${if (b.note.isNotBlank()) " — ${b.note}" else ""}") }
                appendLine()
            }
            appendLine("Recording title: ${rec.title}")
            appendLine("Duration: ${formatClock(rec.durationMs)}")
            appendLine()
            appendLine("TRANSCRIPT:")
            append(transcript.asPlainText())
        }
        val res = vertex.generate(
            turns = listOf(Turn.user(prompt)),
            system = system,
            model = settings.value.reasoningModel,
            schema = notesSchema,
            temperature = 0.3,
            maxOutputTokens = 32768,
        )
        val out = parse<NotesOut>(res)
        return Notes(
            title = out.title,
            summary = out.summary,
            keyPoints = out.keyPoints,
            actionItems = out.actionItems.filter { it.task.isNotBlank() }.map { ActionItem(task = it.task, owner = it.owner, due = it.due, priority = it.priority) },
            decisions = out.decisions,
            questions = out.questions,
            topics = out.topics,
            chapters = out.chapters,
            mindMap = out.mindMap,
            templateId = template.id,
            model = settings.value.reasoningModel,
            generatedAt = System.currentTimeMillis(),
        )
    }

    // ---------- Chat ----------

    suspend fun askRecording(rec: Recording, history: List<Turn>, question: String): String {
        val t = rec.transcript
        val system = buildString {
            appendLine("You are a helpful assistant answering questions about ONE recording. Use only the recording content")
            appendLine("below unless the user explicitly asks for general knowledge. Cite timestamps like [12:34] when relevant.")
            appendLine("Answer in concise markdown.")
            appendLine()
            appendLine("Title: ${rec.title}")
            appendLine("Recorded: ${formatDateTime(rec.createdAt)}  Duration: ${formatClock(rec.durationMs)}")
            rec.notes?.let { appendLine("Summary:\n${it.summary}") }
            appendLine()
            appendLine("TRANSCRIPT:")
            append(t?.asPlainText() ?: "(no transcript yet)")
        }
        return vertex.generate(history + Turn.user(question), system = system, model = settings.value.reasoningModel, temperature = 0.4).text.trim()
    }

    /** "Ask anything" across the whole library. Uses Gemini's long context instead of a vector DB. */
    suspend fun askLibrary(recordings: List<Recording>, history: List<Turn>, question: String): String {
        val budget = 600_000 // characters of context
        val terms = question.lowercase().split(Regex("\\W+")).filter { it.length > 3 }
        val ranked = recordings.sortedWith(
            compareByDescending<Recording> { r -> terms.count { r.matches(it) } }.thenByDescending { it.createdAt }
        )
        val ctx = StringBuilder()
        for (r in ranked) {
            val header = "\n=== RECORDING: \"${r.title}\" — ${formatDateTime(r.createdAt)} (${formatClock(r.durationMs)}) ===\n"
            val notes = r.notes?.let { n ->
                "Summary:\n${n.summary}\nKey points:\n${n.keyPoints.joinToString("\n") { "- $it" }}\n" +
                    "Action items:\n${n.actionItems.joinToString("\n") { "- [${if (it.done) "x" else " "}] ${it.task}${if (it.owner.isNotBlank()) " (${it.owner})" else ""}${if (it.due.isNotBlank()) " due ${it.due}" else ""}" }}\n"
            }.orEmpty()
            val transcript = r.transcript?.asPlainText(withTimes = false).orEmpty()
            val remaining = budget - ctx.length
            if (remaining <= header.length + 200) break
            ctx.append(header).append(notes)
            if (transcript.isNotBlank()) {
                val room = remaining - header.length - notes.length
                if (room > 2000) ctx.append("Transcript:\n").append(transcript.take(room - 100)).append('\n')
            }
        }
        val system = """You are the user's personal memory assistant with access to their recorded conversations,
meetings and voice notes (below). Answer using this material; name the recording(s) and dates you draw from.
If the answer isn't in the recordings, say so. Today is ${formatDateTime(System.currentTimeMillis())}.
Answer in concise markdown.

LIBRARY:$ctx"""
        return vertex.generate(history + Turn.user(question), system = system, model = settings.value.reasoningModel, temperature = 0.4).text.trim()
    }

    suspend fun dailyDigest(dayLabel: String, recordings: List<Recording>): String {
        val ctx = recordings.joinToString("\n\n") { r ->
            "### ${r.title} (${formatDateTime(r.createdAt)}, ${formatClock(r.durationMs)})\n" +
                (r.notes?.summary ?: r.transcript?.asPlainText(false)?.take(20000) ?: "(not processed)") +
                (r.notes?.actionItems?.takeIf { it.isNotEmpty() }?.joinToString("\n", "\nTasks:\n") { "- ${it.task}" } ?: "")
        }
        val prompt = """Write my daily digest for $dayLabel based on everything I recorded that day (below).
Format in markdown with these sections:
**One-line summary of the day**
## Highlights
## Conversations (one bullet per recording: who, what, outcome)
## People I spoke with
## To-dos & commitments (checkbox list, with owners/dates)
## Ideas & insights worth remembering
## Suggested follow-ups for tomorrow

RECORDINGS:
$ctx"""
        return vertex.generate(listOf(Turn.user(prompt)), model = settings.value.reasoningModel, temperature = 0.5).text.trim()
    }

    suspend fun translate(text: String, language: String): String =
        vertex.generate(
            listOf(Turn.user("Translate the following markdown into $language. Keep the markdown structure, names and numbers. Output only the translation.\n\n$text")),
            temperature = 0.2, maxOutputTokens = 32768,
        ).text.trim()

    suspend fun followUpEmail(rec: Recording): String =
        vertex.generate(
            listOf(Turn.user("Draft a concise, friendly follow-up email to the other participants of this conversation, recapping decisions and action items with owners and dates. Output subject line first as 'Subject: ...'.\n\n" +
                (rec.notes?.let { "Summary:\n${it.summary}\nActions:\n" + it.actionItems.joinToString("\n") { a -> "- ${a.task} ${a.owner} ${a.due}" } } ?: rec.transcript?.asPlainText().orEmpty()))),
            model = settings.value.reasoningModel, temperature = 0.5,
        ).text.trim()

    // ---------- helpers ----------

    private inline fun <reified T> parse(res: GenResult): T {
        val raw = res.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            AppJson.decodeFromString<T>(raw)
        } catch (e: Exception) {
            val hint = if (res.finishReason == "MAX_TOKENS") " (response was cut off — the recording may be too long for this model)" else ""
            throw IllegalStateException("Could not read the AI response$hint: ${e.message?.take(200)}")
        }
    }
}
