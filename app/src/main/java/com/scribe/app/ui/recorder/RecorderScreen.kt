package com.scribe.app.ui.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.recording.RecPhase
import com.scribe.app.recording.Recorder
import com.scribe.app.ui.components.Waveform
import com.scribe.app.ui.components.toast
import com.scribe.app.ui.theme.RecordRed
import com.scribe.app.util.formatClock

@Composable
fun RecorderScreen(onClose: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val st by Recorder.state.collectAsStateWithLifecycle()

    LaunchedEffect(st.lastSavedId) {
        st.lastSavedId?.let { Recorder.consumeSaved(); onSaved(it) }
    }
    LaunchedEffect(st.error) {
        st.error?.let { ctx.toast(it) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (st.phase == RecPhase.IDLE) "Recorder" else if (st.phase == RecPhase.PAUSED) "Paused" else "Recording") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                formatClock(st.elapsedMs),
                fontSize = 64.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace,
                color = if (st.phase == RecPhase.RECORDING) RecordRed else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when (st.phase) {
                    RecPhase.IDLE -> "Ready"
                    RecPhase.PAUSED -> "Paused — tap play to continue"
                    RecPhase.RECORDING -> "Keeps recording with the screen off"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Waveform(st.levels, RecordRed, Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(24.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (st.bookmarks.isNotEmpty()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { Text("Flagged moments", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                        items(st.bookmarks) { b ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                                Text("  " + formatClock(b.timeMs), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                } else if (st.phase != RecPhase.IDLE) {
                    Text(
                        "Tip: tap the flag to mark an important moment. Flagged moments get extra attention in the AI summary.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { Recorder.bookmark(ctx) },
                    enabled = st.phase != RecPhase.IDLE,
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Filled.Flag, "Flag moment") }

                when (st.phase) {
                    RecPhase.IDLE -> FilledIconButton(
                        onClick = { Recorder.start(ctx) },
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RecordRed, contentColor = Color.White),
                    ) { Icon(Icons.Filled.Mic, "Start", Modifier.size(40.dp)) }
                    else -> FilledIconButton(
                        onClick = { Recorder.stop(ctx) },
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RecordRed, contentColor = Color.White),
                    ) { Icon(Icons.Filled.Stop, "Stop and save", Modifier.size(40.dp)) }
                }

                FilledTonalIconButton(
                    onClick = { if (st.phase == RecPhase.PAUSED) Recorder.resume(ctx) else Recorder.pause(ctx) },
                    enabled = st.phase != RecPhase.IDLE,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(if (st.phase == RecPhase.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (st.phase == RecPhase.PAUSED) "Resume" else "Pause")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
