package com.scribe.app.ui.detail

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.AppContainer
import com.scribe.app.data.Recording
import com.scribe.app.data.Status
import com.scribe.app.data.Template
import com.scribe.app.ui.ask.ChatPanel
import com.scribe.app.ui.components.Markdown
import com.scribe.app.ui.components.SectionCard
import com.scribe.app.ui.components.StatusPill
import com.scribe.app.ui.components.container
import com.scribe.app.ui.components.shareText
import com.scribe.app.ui.components.statusLabel
import com.scribe.app.ui.components.toast
import com.scribe.app.ui.mindmap.MindMapView
import com.scribe.app.ui.theme.Palette
import com.scribe.app.util.Export
import com.scribe.app.util.formatClock
import com.scribe.app.util.formatDateTime
import com.scribe.app.util.formatDuration
import com.scribe.app.work.Jobs
import kotlinx.coroutines.launch

private enum class DialogKind { NONE, RENAME, TAGS, TEMPLATE, TRANSLATE, RESULT, DELETE, SPEAKER, SEGMENT }

@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by remember(id) { c.repo.observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    val templates by remember { c.repo.observeTemplates() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val player = remember(id) { AudioPlayer(scope) }
    val ps by player.state.collectAsStateWithLifecycle()
    DisposableEffect(id) { onDispose { player.release() } }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(DialogKind.NONE) }
    var dialogArg by remember { mutableStateOf("") }
    var dialogIndex by remember { mutableIntStateOf(-1) }
    var resultTitle by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var mapFit by remember { mutableIntStateOf(0) }

    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val cur = rec
        if (uri != null && cur != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Export.fullMarkdown(cur).toByteArray()) }; ctx.toast("Saved") }
    }
    val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val cur = rec
        if (uri != null && cur != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Export.srt(cur).toByteArray()) }; ctx.toast("Saved") }
    }

    val r = rec
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }


    fun ensurePlayer(autoPlay: Boolean, seekMs: Long? = null) {
        if (ps.loaded) {
            seekMs?.let { player.seekTo(it) }
            if (autoPlay && !ps.playing) player.play()
            return
        }
        seekMs?.let { player.seekTo(it) }
        player.setLoading(true)
        scope.launch {
            try {
                val f = c.sync.ensureLocalAudio(r.id)
                player.load(f, autoPlay)
            } catch (e: Exception) {
                player.setLoading(false, e.message)
                ctx.toast("Audio unavailable: ${e.message}")
            }
        }
    }
    val seek: (Long) -> Unit = { ms -> ensurePlayer(autoPlay = true, seekMs = ms) }

    fun runAi(label: String, block: suspend () -> Pair<String, String>) {
        if (!c.settings.value.isCloudConfigured) { ctx.toast("Connect Google Cloud in Settings first"); return }
        working = label
        scope.launch {
            try {
                val (t, body) = block()
                resultTitle = t; resultText = body; dialog = DialogKind.RESULT
            } catch (e: Exception) {
                ctx.toast("${e.message}")
            } finally { working = null }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { dialogArg = r.title; dialog = DialogKind.RENAME })
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { scope.launch { c.repo.toggleStar(r.id); if (c.settings.value.autoSync) Jobs.syncNow(ctx) } }) {
                        Icon(if (r.starred) Icons.Filled.Star else Icons.Filled.StarBorder, "Star")
                    }
                    Box {
                        IconButton(onClick = { shareMenu = true }) { Icon(Icons.Filled.Share, "Share") }
                        DropdownMenu(shareMenu, { shareMenu = false }) {
                            DropdownMenuItem(text = { Text("Share summary") }, onClick = { shareMenu = false; ctx.shareText(r.title, Export.summaryMarkdown(r)) })
                            DropdownMenuItem(text = { Text("Share summary + transcript") }, onClick = { shareMenu = false; ctx.shareText(r.title, Export.fullMarkdown(r)) })
                            DropdownMenuItem(text = { Text("Save as Markdown file…") }, onClick = { shareMenu = false; saveMd.launch("${r.title.take(40)}.md") })
                            DropdownMenuItem(text = { Text("Export subtitles (.srt)…") }, enabled = r.transcript != null, onClick = { shareMenu = false; saveSrt.launch("${r.title.take(40)}.srt") })
                            DropdownMenuItem(text = { Text("Share audio file") }, onClick = {
                                shareMenu = false
                                scope.launch { runCatching { Export.shareAudio(ctx, c.sync.ensureLocalAudio(r.id), r.mimeType) }.onFailure { ctx.toast("${it.message}") } }
                            })
                            DropdownMenuItem(text = { Text("Draft follow-up email (AI)") }, onClick = {
                                shareMenu = false
                                runAi("Drafting email…") { "Follow-up email" to c.ai.followUpEmail(r) }
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialogArg = r.title; dialog = DialogKind.RENAME })
                            DropdownMenuItem(text = { Text("Edit tags") }, onClick = { menu = false; dialogArg = r.tags.joinToString(", "); dialog = DialogKind.TAGS })
                            DropdownMenuItem(text = { Text("Summarise with template…") }, onClick = { menu = false; dialog = DialogKind.TEMPLATE })
                            DropdownMenuItem(text = { Text("Re-transcribe") }, onClick = { menu = false; Jobs.process(ctx, r.id, retranscribe = true) })
                            DropdownMenuItem(text = { Text("Translate summary…") }, enabled = r.notes != null, onClick = { menu = false; dialogArg = ""; dialog = DialogKind.TRANSLATE })
                            DropdownMenuItem(text = { Text("Sync to Cloud now") }, onClick = { menu = false; Jobs.syncNow(ctx); ctx.toast("Syncing…") })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dialog = DialogKind.DELETE })
                        }
                    }
                },
            )
        },
        bottomBar = { PlayerBar(r, ps, onToggle = { if (ps.loaded) player.toggle() else ensurePlayer(true) }, player = player) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (working != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("Summary", "Transcript", "Mind map", "Ask AI").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> SummaryTab(r, templates, c, ctx, seek, onTemplate = { dialog = DialogKind.TEMPLATE })
                1 -> TranscriptTab(r, ps.positionMs, ps.playing, seek,
                    onSpeaker = { raw -> dialogArg = r.transcript?.displayName(raw).orEmpty(); resultTitle = raw; dialog = DialogKind.SPEAKER },
                    onEdit = { i, text -> dialogIndex = i; dialogArg = text; dialog = DialogKind.SEGMENT })
                2 -> Box(Modifier.fillMaxSize()) {
                    val mm = r.notes?.mindMap
                    if (mm == null) NotReady(r, "The mind map appears once the recording has been processed.") { Jobs.process(ctx, r.id) }
                    else {
                        MindMapView(mm, Modifier.fillMaxSize(), fitKey = mapFit)
                        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallFloatingActionButton(onClick = { mapFit++ }) { Icon(Icons.Filled.ZoomOutMap, "Fit") }
                            SmallFloatingActionButton(onClick = { ctx.shareText("Mind map: ${r.title}", Export.mindMapOutline(mm)) }) { Icon(Icons.Filled.Share, "Share outline") }
                        }
                        Text("Pinch to zoom · drag to pan · tap a node to fold", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                    }
                }
                3 -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { scope.launch { c.repo.clearChat("rec:${r.id}") } }) {
                            Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp)); Text(" Clear")
                        }
                    }
                    ChatPanel(
                        scope = "rec:${r.id}",
                        suggestions = listOf("What were the main decisions?", "What are my action items?", "Summarise in 3 bullet points",
                            "What did each person say?", "Write a LinkedIn post about this", "What questions remain open?"),
                        emptyTitle = "Ask about this recording",
                        emptyBody = "Gemini answers using the transcript and summary, citing timestamps.",
                        modifier = Modifier.weight(1f),
                    ) { history, q ->
                        check(c.settings.value.isCloudConfigured) { "Connect Google Cloud in Settings first" }
                        c.ai.askRecording(c.repo.get(r.id) ?: r, history, q)
                    }
                }
            }
        }
    }

    // ---------- dialogs ----------
    when (dialog) {
        DialogKind.RENAME, DialogKind.TAGS, DialogKind.TRANSLATE, DialogKind.SPEAKER, DialogKind.SEGMENT -> {
            val (title, hint) = when (dialog) {
                DialogKind.RENAME -> "Rename" to "Title"
                DialogKind.TAGS -> "Tags" to "Comma separated, e.g. client, project-x"
                DialogKind.TRANSLATE -> "Translate summary" to "Language, e.g. French, Spanish, Japanese"
                DialogKind.SPEAKER -> "Name for $resultTitle" to "e.g. Sarah (Finance)"
                else -> "Edit text" to ""
            }
            var text by remember(dialog) { mutableStateOf(dialogArg) }
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text(title) },
                text = { OutlinedTextField(text, { text = it }, label = { Text(hint) }, singleLine = dialog != DialogKind.SEGMENT, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = {
                        val kind = dialog
                        val raw = resultTitle
                        val idx = dialogIndex
                        dialog = DialogKind.NONE
                        when (kind) {
                            DialogKind.RENAME -> scope.launch { c.repo.rename(r.id, text.trim()) }
                            DialogKind.TAGS -> scope.launch { c.repo.setTags(r.id, text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
                            DialogKind.SPEAKER -> scope.launch { c.repo.renameSpeaker(r.id, raw, text) }
                            DialogKind.SEGMENT -> scope.launch { c.repo.editSegment(r.id, idx, text) }
                            DialogKind.TRANSLATE -> runAi("Translating…") {
                                "Summary in $text" to c.ai.translate(Export.summaryMarkdown(r), text)
                            }
                            else -> {}
                        }
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { dialog = DialogKind.NONE }) { Text("Cancel") } },
            )
        }
        DialogKind.TEMPLATE -> AlertDialog(
            onDismissRequest = { dialog = DialogKind.NONE },
            title = { Text("Summarise with template") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    templates.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                dialog = DialogKind.NONE
                                scope.launch {
                                    c.repo.setTemplate(r.id, t.id)
                                    Jobs.process(ctx, r.id, renotes = true)
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = r.templateId == t.id, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                                if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = DialogKind.NONE }) { Text("Close") } },
        )
        DialogKind.RESULT -> AlertDialog(
            onDismissRequest = { dialog = DialogKind.NONE },
            title = { Text(resultTitle) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { SelectionContainer { Markdown(resultText) } } },
            confirmButton = { TextButton(onClick = { ctx.shareText(resultTitle, resultText) }) { Text("Share") } },
            dismissButton = { TextButton(onClick = { dialog = DialogKind.NONE }) { Text("Close") } },
        )
        DialogKind.DELETE -> AlertDialog(
            onDismissRequest = { dialog = DialogKind.NONE },
            title = { Text("Delete recording?") },
            text = { Text("This removes the audio, transcript and notes from this phone and from your Cloud Storage bucket.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = DialogKind.NONE
                    player.release()
                    scope.launch { c.sync.deleteEverywhere(r.id) }
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = DialogKind.NONE }) { Text("Cancel") } },
        )
        DialogKind.NONE -> {}
    }
}

@Composable
private fun PlayerBar(r: Recording, ps: PlayerState, onToggle: () -> Unit, player: AudioPlayer) {
    BottomAppBar(contentPadding = PaddingValues(horizontal = 8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            val dur = if (ps.durationMs > 0) ps.durationMs else r.durationMs
            Slider(
                value = if (dur > 0) (ps.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (ps.loaded) player.seekTo((it * dur).toLong()) },
                enabled = ps.loaded,
                modifier = Modifier.height(24.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(ps.positionMs), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { player.skip(-10_000) }, enabled = ps.loaded) { Icon(Icons.Filled.Replay10, "Back 10s") }
                IconButton(onClick = onToggle) {
                    if (ps.loading && !ps.loaded) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (ps.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (ps.playing) "Pause" else "Play")
                }
                IconButton(onClick = { player.skip(10_000) }, enabled = ps.loaded) { Icon(Icons.Filled.Forward10, "Forward 10s") }
                TextButton(onClick = { player.cycleSpeed() }) { Text("${ps.speed}x") }
                Spacer(Modifier.weight(1f))
                Text(formatClock(dur), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NotReady(r: Recording, text: String, onProcess: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        if (r.isBusy) {
            CircularProgressIndicator()
            Text(statusLabel(r.status), style = MaterialTheme.typography.titleMedium)
            Text("This keeps running in the background — you can leave this screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (r.status == Status.FAILED) Text(r.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onProcess) { Icon(Icons.Filled.AutoAwesome, null); Text("  Transcribe & summarise") }
        }
    }
}

@Composable
private fun SummaryTab(
    r: Recording,
    templates: List<Template>,
    c: AppContainer,
    ctx: Context,
    seek: (Long) -> Unit,
    onTemplate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val n = r.notes
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${formatDateTime(r.createdAt)} · ${formatDuration(r.durationMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(r)
                    AssistChip(onClick = onTemplate, label = { Text("Template: " + (templates.firstOrNull { it.id == r.templateId }?.name ?: r.templateId)) })
                    r.tags.forEach { Text("#$it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (n == null || r.isBusy || r.status == Status.FAILED) item {
            SectionCard(if (r.isBusy) "Processing" else "AI notes") {
                if (r.isBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  " + statusLabel(r.status))
                    }
                    Text("Runs in the background; safe to close the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (r.status == Status.FAILED) Text(r.error ?: "Processing failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!c.settings.value.isCloudConfigured) Text("Connect Google Cloud in Settings to transcribe and summarise.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { Jobs.process(ctx, r.id) }) {
                        Icon(Icons.Filled.AutoAwesome, null); Text(if (r.status == Status.FAILED) "  Retry" else "  Transcribe & summarise")
                    }
                }
            }
        }
        if (n != null) {
            if (n.summary.isNotBlank()) item { SectionCard("Summary") { SelectionContainer { Markdown(n.summary) } } }
            if (n.keyPoints.isNotEmpty()) item {
                SectionCard("Key points") { n.keyPoints.forEach { Markdown("- $it") } }
            }
            if (n.actionItems.isNotEmpty()) item {
                SectionCard("Action items") {
                    n.actionItems.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(a.done, onCheckedChange = { scope.launch { c.repo.toggleAction(r.id, a.id) } })
                            Column(Modifier.weight(1f)) {
                                Text(a.task, style = MaterialTheme.typography.bodyMedium, textDecoration = if (a.done) TextDecoration.LineThrough else null)
                                val meta = listOf(a.owner, a.due.let { if (it.isNotBlank()) "due $it" else "" }, a.priority).filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { Export.addToCalendar(ctx, a, r.title) }) { Icon(Icons.Filled.Event, "Add to calendar") }
                        }
                    }
                }
            }
            if (n.decisions.isNotEmpty()) item { SectionCard("Decisions") { n.decisions.forEach { Markdown("- $it") } } }
            if (n.questions.isNotEmpty()) item { SectionCard("Open questions") { n.questions.forEach { Markdown("- $it") } } }
            if (n.chapters.isNotEmpty()) item {
                SectionCard("Chapters") {
                    n.chapters.forEach { ch ->
                        Row(Modifier.fillMaxWidth().clickable { seek((ch.start * 1000).toLong()) }.padding(vertical = 4.dp)) {
                            Text(formatClock((ch.start * 1000).toLong()), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                            Column {
                                Text(ch.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (ch.summary.isNotBlank()) Text(ch.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (r.bookmarks.isNotEmpty()) item {
            SectionCard("Flagged moments") {
                r.bookmarks.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { seek(b.timeMs) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Text("  " + formatClock(b.timeMs), color = MaterialTheme.colorScheme.primary)
                        r.transcript?.segments?.lastOrNull { it.start * 1000 <= b.timeMs }?.let {
                            Text("  " + it.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (n != null && n.model.isNotBlank()) item {
            Text("Generated by ${n.model} in your Google Cloud project", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscriptTab(
    r: Recording,
    positionMs: Long,
    playing: Boolean,
    seek: (Long) -> Unit,
    onSpeaker: (String) -> Unit,
    onEdit: (Int, String) -> Unit,
) {
    val ctx = LocalContext.current
    val t = r.transcript
    if (t == null) {
        NotReady(r, "No transcript yet.") { Jobs.process(ctx, r.id) }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val speakers = t.speakers
    val colorOf = { s: String -> Palette[(speakers.indexOf(s).coerceAtLeast(0)) % Palette.size] }
    val current = if (playing || positionMs > 0) t.segments.indexOfLast { it.start * 1000 <= positionMs } else -1

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                speakers.forEach { s ->
                    AssistChip(onClick = { onSpeaker(s) }, label = { Text(t.displayName(s), color = colorOf(s)) })
                }
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search transcript") }
        }
        if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), placeholder = { Text("Find in transcript") }, singleLine = true)
        Text("Tap a speaker to rename · tap a line to play from there · long-press to edit", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val rows = t.segments.withIndex().filter { query.isBlank() || it.value.text.contains(query, ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(rows, key = { _, iv -> iv.index }) { _, iv ->
                val s = iv.value
                val active = iv.index == current
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { seek((s.start * 1000).toLong()) }, onLongClick = { onEdit(iv.index, s.text) })
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.displayName(s.speaker), color = colorOf(s.speaker), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("  " + formatClock((s.start * 1000).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(s.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

