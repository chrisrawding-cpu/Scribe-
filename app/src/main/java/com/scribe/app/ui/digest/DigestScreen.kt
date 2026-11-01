package com.scribe.app.ui.digest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.ui.components.EmptyState
import com.scribe.app.ui.components.Markdown
import com.scribe.app.ui.components.SectionCard
import com.scribe.app.ui.components.container
import com.scribe.app.ui.components.shareText
import com.scribe.app.ui.components.toast
import com.scribe.app.ui.library.dayLabel
import com.scribe.app.util.dayKey
import com.scribe.app.util.formatDuration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Daily journal: an AI digest of everything recorded on a given day. */
@Composable
fun DigestScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by remember { c.repo.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var day by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var digest by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val key = dayKey(day)
    val todays = all.filter { dayKey(it.createdAt) == key }.sortedBy { it.createdAt }

    LaunchedEffect(key) {
        digest = null
        digest = runCatching { c.sync.loadDigest(key) }.getOrNull()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Daily digest") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                digest?.let { d -> IconButton(onClick = { ctx.shareText("Digest $key", d) }) { Icon(Icons.Filled.Share, "Share") } }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day -= 86_400_000 }) { Icon(Icons.Filled.ChevronLeft, "Previous day") }
                    Text(dayLabel(day) + "  ·  " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(day)),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { day += 86_400_000 }, enabled = key < dayKey(System.currentTimeMillis())) { Icon(Icons.Filled.ChevronRight, "Next day") }
                }
            }
            if (todays.isEmpty()) item { EmptyState("Nothing recorded", "No recordings on this day.") }
            items(todays, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.title) },
                    supportingContent = { Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.createdAt)) + " · " + formatDuration(r.durationMs)) },
                    modifier = Modifier.padding(0.dp),
                    trailingContent = { IconButton(onClick = { onOpen(r.id) }) { Icon(Icons.Filled.ChevronRight, "Open") } },
                )
            }
            if (todays.isNotEmpty()) item {
                Button(
                    enabled = !busy,
                    onClick = {
                        if (!c.settings.value.isCloudConfigured) { ctx.toast("Connect Google Cloud in Settings first"); return@Button }
                        busy = true
                        scope.launch {
                            try {
                                val md = c.ai.dailyDigest(dayLabel(day) + " (" + key + ")", todays)
                                digest = md
                                c.sync.saveDigest(key, md)
                            } catch (e: Exception) { ctx.toast("${e.message}") } finally { busy = false }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.AutoAwesome, null)
                    Text(if (digest == null) "  Generate digest" else "  Regenerate")
                }
            }
            digest?.let { d -> item { SectionCard("Digest") { SelectionContainer { Markdown(d) } } } }
        }
    }
}
