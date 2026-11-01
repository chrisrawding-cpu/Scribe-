package com.scribe.app.util

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.core.content.FileProvider
import com.scribe.app.data.ActionItem
import com.scribe.app.data.MindNode
import com.scribe.app.data.Recording
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object Export {
    fun summaryMarkdown(r: Recording): String = buildString {
        appendLine("# ${r.title}")
        appendLine("_${formatDateTime(r.createdAt)} · ${formatDuration(r.durationMs)}_")
        if (r.tags.isNotEmpty()) appendLine(r.tags.joinToString(" ") { "#$it" })
        appendLine()
        val n = r.notes
        if (n != null) {
            appendLine(n.summary.trim())
            appendLine()
            if (n.keyPoints.isNotEmpty()) { appendLine("## Key points"); n.keyPoints.forEach { appendLine("- $it") }; appendLine() }
            if (n.actionItems.isNotEmpty()) {
                appendLine("## Action items")
                n.actionItems.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.task}${if (it.owner.isNotBlank()) " — ${it.owner}" else ""}${if (it.due.isNotBlank()) " (due ${it.due})" else ""}") }
                appendLine()
            }
            if (n.decisions.isNotEmpty()) { appendLine("## Decisions"); n.decisions.forEach { appendLine("- $it") }; appendLine() }
            if (n.questions.isNotEmpty()) { appendLine("## Open questions"); n.questions.forEach { appendLine("- $it") }; appendLine() }
            if (n.chapters.isNotEmpty()) { appendLine("## Chapters"); n.chapters.forEach { appendLine("- [${formatClock((it.start * 1000).toLong())}] **${it.title}** — ${it.summary}") }; appendLine() }
            n.mindMap?.let { appendLine("## Mind map"); append(mindMapOutline(it)); appendLine() }
        }
    }

    fun fullMarkdown(r: Recording): String = buildString {
        append(summaryMarkdown(r))
        r.transcript?.let { appendLine("## Transcript"); appendLine(); append(it.asPlainText()) }
    }

    fun mindMapOutline(root: MindNode, depth: Int = 0): String = buildString {
        append("  ".repeat(depth)).append("- ").appendLine(root.label)
        root.children.forEach { append(mindMapOutline(it, depth + 1)) }
    }

    fun srt(r: Recording): String = buildString {
        val t = r.transcript ?: return@buildString
        t.segments.forEachIndexed { i, s ->
            appendLine(i + 1)
            val end = if (s.end > s.start) s.end else s.start + 3
            appendLine("${srtTime(s.start)} --> ${srtTime(end)}")
            appendLine((if (s.speaker.isNotBlank()) t.displayName(s.speaker) + ": " else "") + s.text)
            appendLine()
        }
    }

    fun shareAudio(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share audio"))
    }

    /** Opens the calendar app (e.g. Google Calendar) pre-filled with an action item. */
    fun addToCalendar(ctx: Context, item: ActionItem, source: String) {
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, item.task)
            .putExtra(CalendarContract.Events.DESCRIPTION, "From recording: $source" + if (item.owner.isNotBlank()) "\nOwner: ${item.owner}" else "")
        parseDue(item.due)?.let {
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
            i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        runCatching { ctx.startActivity(i) }
    }

    fun parseDue(due: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(due.take(10))?.time
    }.getOrNull()
}
