package com.scribe.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Segment(
    val speaker: String = "",
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
)

@Serializable
data class Transcript(
    val language: String = "",
    val segments: List<Segment> = emptyList(),
    /** Maps raw speaker labels (e.g. "Speaker 1") to names the user has assigned. */
    val speakerNames: Map<String, String> = emptyMap(),
) {
    fun displayName(raw: String): String = speakerNames[raw]?.takeIf { it.isNotBlank() } ?: raw
    val speakers: List<String> get() = segments.map { it.speaker }.distinct()

    fun asPlainText(withTimes: Boolean = true): String = buildString {
        segments.forEach { s ->
            if (withTimes) append('[').append(com.scribe.app.util.formatClock((s.start * 1000).toLong())).append("] ")
            if (s.speaker.isNotBlank()) append(displayName(s.speaker)).append(": ")
            append(s.text.trim()).append('\n')
        }
    }
}

@Serializable
data class ActionItem(
    val id: String = UUID.randomUUID().toString(),
    val task: String = "",
    val owner: String = "",
    val due: String = "",
    val priority: String = "",
    val done: Boolean = false,
)

@Serializable
data class Chapter(
    val start: Double = 0.0,
    val title: String = "",
    val summary: String = "",
)

@Serializable
data class MindNode(
    val label: String = "",
    val children: List<MindNode> = emptyList(),
)

@Serializable
data class Notes(
    val title: String = "",
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val decisions: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val mindMap: MindNode? = null,
    val templateId: String = "",
    val model: String = "",
    val generatedAt: Long = 0,
)

@Serializable
data class Bookmark(
    val timeMs: Long = 0,
    val note: String = "",
)

enum class Status { RECORDED, QUEUED, UPLOADING, TRANSCRIBING, SUMMARIZING, READY, FAILED }

/** Fully parsed recording used by the UI. */
data class Recording(
    val id: String,
    val title: String,
    val autoTitle: Boolean,
    val createdAt: Long,
    val durationMs: Long,
    val localPath: String?,
    val mimeType: String,
    val tags: List<String>,
    val starred: Boolean,
    val bookmarks: List<Bookmark>,
    val transcript: Transcript?,
    val notes: Notes?,
    val status: Status,
    val error: String?,
    val templateId: String,
    val cloudAudio: Boolean,
    val updatedAt: Long,
    val syncedAt: Long,
) {
    val isSynced: Boolean get() = syncedAt >= updatedAt && syncedAt > 0
    val isBusy: Boolean get() = status in setOf(Status.QUEUED, Status.UPLOADING, Status.TRANSCRIBING, Status.SUMMARIZING)

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        if (title.lowercase().contains(q)) return true
        if (tags.any { it.lowercase().contains(q) }) return true
        notes?.let { n ->
            if (n.summary.lowercase().contains(q)) return true
            if (n.keyPoints.any { it.lowercase().contains(q) }) return true
            if (n.actionItems.any { it.task.lowercase().contains(q) }) return true
            if (n.topics.any { it.lowercase().contains(q) }) return true
        }
        transcript?.let { t -> if (t.segments.any { it.text.lowercase().contains(q) }) return true }
        return false
    }
}

/** The document stored per recording in the Cloud Storage bucket (record.json). */
@Serializable
data class RecordingDoc(
    val id: String,
    val title: String,
    val autoTitle: Boolean = true,
    val createdAt: Long,
    val durationMs: Long = 0,
    val mimeType: String = "audio/mp4",
    val audioObject: String = "",
    val tags: List<String> = emptyList(),
    val starred: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val transcript: Transcript? = null,
    val notes: Notes? = null,
    val templateId: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class Template(
    val id: String,
    val name: String,
    val description: String = "",
    val instructions: String,
    val builtIn: Boolean = false,
)
