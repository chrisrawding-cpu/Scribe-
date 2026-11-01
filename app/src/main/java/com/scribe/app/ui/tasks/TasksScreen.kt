package com.scribe.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.ui.components.EmptyState
import com.scribe.app.ui.components.container
import com.scribe.app.ui.components.shareText
import com.scribe.app.util.Export
import com.scribe.app.util.formatShort
import kotlinx.coroutines.launch

/** Every action item from every recording, in one to-do list. */
@Composable
fun TasksScreen(onOpen: (String) -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by remember { c.repo.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by rememberSaveable { mutableStateOf("open") }

    val groups = all.mapNotNull { r ->
        val items = r.notes?.actionItems.orEmpty().filter {
            when (filter) { "open" -> !it.done; "done" -> it.done; else -> true }
        }
        if (items.isEmpty()) null else r to items
    }
    val openCount = all.sumOf { r -> r.notes?.actionItems.orEmpty().count { !it.done } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tasks") },
            actions = {
                IconButton(onClick = {
                    val text = groups.joinToString("\n\n") { (r, items) ->
                        "${r.title} (${formatShort(r.createdAt)})\n" + items.joinToString("\n") { "- [${if (it.done) "x" else " "}] ${it.task}${if (it.owner.isNotBlank()) " — ${it.owner}" else ""}${if (it.due.isNotBlank()) " (due ${it.due})" else ""}" }
                    }
                    ctx.shareText("My tasks", text)
                }) { Icon(Icons.Filled.Share, "Share tasks") }
            },
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == "open", { filter = "open" }, { Text("Open ($openCount)") })
                    FilterChip(filter == "done", { filter = "done" }, { Text("Done") })
                    FilterChip(filter == "all", { filter = "all" }, { Text("All") })
                }
            }
            if (groups.isEmpty()) item {
                EmptyState("No tasks", "Action items the AI finds in your recordings appear here, across every meeting and note.")
            }
            items(groups, key = { it.first.id }) { (r, items) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        TextButton(onClick = { onOpen(r.id) }) {
                            Text("${r.title} · ${formatShort(r.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        }
                        items.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(a.done, { scope.launch { c.repo.toggleAction(r.id, a.id) } })
                                Column(Modifier.weight(1f)) {
                                    Text(a.task, style = MaterialTheme.typography.bodyMedium, textDecoration = if (a.done) TextDecoration.LineThrough else null)
                                    val meta = listOf(a.owner, if (a.due.isNotBlank()) "due ${a.due}" else "", a.priority).filter { it.isNotBlank() }.joinToString(" · ")
                                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { Export.addToCalendar(ctx, a, r.title) }) { Icon(Icons.Filled.Event, "Add to calendar") }
                            }
                        }
                    }
                }
            }
        }
    }
}
