package com.scribe.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.data.Template
import com.scribe.app.ui.components.container
import kotlinx.coroutines.launch
import java.util.UUID

/** Custom summary templates — like Plaud's template community, but yours, synced to your bucket. */
@Composable
fun TemplatesScreen(onBack: () -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    val templates by remember { c.repo.observeTemplates() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<Template?>(null) }

    fun persist(block: suspend () -> Unit) = scope.launch {
        block()
        if (c.settings.value.isCloudConfigured) runCatching { c.sync.pushTemplates() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Summary templates") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Template(id = "", name = "", description = "", instructions = "") },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New template") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(templates, key = { it.id }) { t ->
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text(t.description.ifBlank { t.instructions }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    overlineContent = { Text(if (t.builtIn) "Built-in" else "Custom") },
                    trailingContent = {
                        if (t.builtIn) IconButton(onClick = { editing = t.copy(id = "", name = t.name + " (copy)", builtIn = false) }) { Icon(Icons.Filled.ContentCopy, "Duplicate") }
                        else androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { editing = t }) { Icon(Icons.Filled.Edit, "Edit") }
                            IconButton(onClick = { persist { c.repo.deleteTemplate(t.id) } }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    },
                )
            }
        }
    }

    editing?.let { t ->
        var name by remember(t) { mutableStateOf(t.name) }
        var desc by remember(t) { mutableStateOf(t.description) }
        var instr by remember(t) { mutableStateOf(t.instructions) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (t.id.isBlank()) "New template" else "Edit template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Short description") }, singleLine = true)
                    OutlinedTextField(
                        instr, { instr = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        label = { Text("Instructions for the AI") },
                        placeholder = { Text("e.g. Write a client-call summary with sections: Context, Requirements, Pricing, Risks, Next steps…") },
                    )
                    Text("Describe the sections and style you want. Key points, action items, chapters and the mind map are always generated too.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && instr.isNotBlank(), onClick = {
                    val saved = Template(t.id.ifBlank { "custom-" + UUID.randomUUID().toString().take(8) }, name.trim(), desc.trim(), instr.trim())
                    editing = null
                    persist { c.repo.saveTemplate(saved) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
