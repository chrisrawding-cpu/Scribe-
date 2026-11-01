package com.scribe.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.data.Recording
import com.scribe.app.data.Status
import com.scribe.app.recording.RecPhase
import com.scribe.app.recording.Recorder
import com.scribe.app.ui.components.EmptyState
import com.scribe.app.ui.components.StatusPill
import com.scribe.app.ui.components.container
import com.scribe.app.ui.components.toast
import com.scribe.app.ui.theme.RecordRed
import com.scribe.app.util.dayKey
import com.scribe.app.util.formatClock
import com.scribe.app.util.formatDate
import com.scribe.app.util.formatDuration
import com.scribe.app.work.Jobs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
    onOpenRecorder: () -> Unit,
    onDigest: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by remember { c.repo.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val rec by Recorder.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var searching by rememberSaveable { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val id = c.repo.importUri(uri)
                if (settings.autoProcess && settings.isCloudConfigured) Jobs.process(ctx, id)
                onOpen(id)
            } catch (e: Exception) { ctx.toast("Import failed: ${e.message}") }
        }
    }

    val tags = remember(all) { all.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key } }
    val shown = remember(all, query, filter) {
        all.filter { r ->
            r.matches(query) && when (filter) {
                "all" -> true
                "starred" -> r.starred
                "pending" -> r.status != Status.READY
                else -> filter in r.tags
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scribe") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = onDigest) { Icon(Icons.Filled.Today, "Daily digest") }
                    IconButton(onClick = { importer.launch(arrayOf("audio/*", "video/*")) }) { Icon(Icons.Filled.FileUpload, "Import audio") }
                    IconButton(onClick = {
                        if (settings.isCloudConfigured) { Jobs.syncNow(ctx); ctx.toast("Syncing with Google Cloud…") } else onSettings()
                    }) { Icon(Icons.Filled.CloudSync, "Sync") }
                },
            )
        },
        floatingActionButton = {
            if (rec.phase == RecPhase.IDLE) {
                ExtendedFloatingActionButton(
                    onClick = onRecord,
                    icon = { Icon(Icons.Filled.Mic, null) },
                    text = { Text("Record") },
                    containerColor = RecordRed,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                )
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (searching) item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search titles, summaries, transcripts…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    singleLine = true,
                )
            }
            if (rec.phase != RecPhase.IDLE) item {
                Card(
                    onClick = onOpenRecorder,
                    colors = CardDefaults.cardColors(containerColor = RecordRed.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FiberManualRecord, null, tint = RecordRed)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (rec.phase == RecPhase.PAUSED) "Paused · " else "Recording · ") + formatClock(rec.elapsedMs),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("Open", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (!settings.isCloudConfigured) item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Connect your Google Cloud", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Recordings are stored in your own Cloud Storage bucket and processed with Vertex AI Gemini in your project. Nothing goes to third-party servers.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onSettings) { Text("Set up now") }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == "all", { filter = "all" }, { Text("All (${all.size})") })
                    FilterChip(filter == "starred", { filter = "starred" }, { Text("Starred") }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) })
                    FilterChip(filter == "pending", { filter = "pending" }, { Text("Unprocessed") })
                    tags.forEach { t -> FilterChip(filter == t, { filter = if (filter == t) "all" else t }, { Text("#$t") }) }
                }
            }
            if (shown.isEmpty()) item {
                EmptyState(
                    if (all.isEmpty()) "No recordings yet" else "Nothing matches",
                    if (all.isEmpty()) "Tap Record to capture a meeting, call, lecture or idea. You can also import or share audio files into Scribe." else "Try another search or filter.",
                )
            }
            var lastDay = ""
            shown.forEach { r ->
                val day = dayKey(r.createdAt)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "h$day") {
                        Text(dayLabel(r.createdAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item(key = r.id) { RecordingCard(r) { onOpen(r.id) } }
            }
        }
    }
}

fun dayLabel(ts: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val yesterday = dayKey(System.currentTimeMillis() - 86_400_000)
    return when (dayKey(ts)) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> formatDate(ts)
    }
}

@Composable
private fun RecordingCard(r: Recording, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (r.starred) Icon(Icons.Filled.Star, "Starred", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            }
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.createdAt)) + " · " + formatDuration(r.durationMs) +
                    (r.notes?.actionItems?.count { !it.done }?.takeIf { it > 0 }?.let { " · $it open tasks" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val snippet = r.notes?.keyPoints?.firstOrNull()
                ?: r.notes?.summary?.lineSequence()?.map { it.trim().trim('*', '#', ' ') }?.firstOrNull { it.length > 20 }
                ?: r.transcript?.segments?.firstOrNull()?.text
            if (snippet != null) Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(r)
                Spacer(Modifier.weight(1f))
                r.tags.take(3).forEach { Text("#$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { }) }
            }
            if (r.status == Status.FAILED && r.error != null) Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
