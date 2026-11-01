package com.scribe.app.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.cloud.Turn
import com.scribe.app.ui.components.EmptyState
import com.scribe.app.ui.components.Markdown
import com.scribe.app.ui.components.container
import kotlinx.coroutines.launch

/**
 * Chat UI persisted per scope ("global" or "rec:{id}").
 * [answer] receives prior turns + the new question and returns the model's markdown reply.
 */
@Composable
fun ChatPanel(
    scope: String,
    suggestions: List<String>,
    emptyTitle: String,
    emptyBody: String,
    modifier: Modifier = Modifier,
    answer: suspend (history: List<Turn>, question: String) -> String,
) {
    val c = container()
    val cs = rememberCoroutineScope()
    val messages by remember(scope) { c.repo.observeChat(scope) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var input by rememberSaveable(scope) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    LaunchedEffect(messages.size, busy) {
        val n = messages.size + if (busy) 1 else 0
        if (n > 0) list.animateScrollToItem(n - 1)
    }

    fun send(q: String) {
        if (q.isBlank() || busy) return
        input = ""
        busy = true
        cs.launch {
            val history = c.repo.chatHistory(scope).takeLast(20).map { if (it.role == "user") Turn.user(it.text) else Turn.model(it.text) }
            c.repo.addChat(scope, "user", q)
            val reply = try { answer(history, q) } catch (e: Exception) { "⚠️ ${e.message}" }
            c.repo.addChat(scope, "model", reply)
            busy = false
        }
    }

    Column(modifier.imePadding()) {
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty() && !busy) {
                Column {
                    EmptyState(emptyTitle, emptyBody)
                }
            }
            LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages, key = { it.id }) { m ->
                    val mine = m.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier
                                .widthIn(max = 340.dp)
                                .background(
                                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(16.dp),
                                )
                                .padding(12.dp)
                        ) {
                            if (mine) Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            else SelectionContainer { Markdown(m.text) }
                        }
                    }
                }
                if (busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Thinking…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (messages.isEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask anything…") },
                maxLines = 5,
            )
            FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !busy, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}
