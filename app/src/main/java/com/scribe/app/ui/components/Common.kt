package com.scribe.app.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scribe.app.data.Recording
import com.scribe.app.data.Status

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.shareText(subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(i, "Share"))
}

fun statusLabel(s: Status): String = when (s) {
    Status.RECORDED -> "Not processed"
    Status.QUEUED -> "Waiting for network…"
    Status.UPLOADING -> "Uploading to Cloud…"
    Status.TRANSCRIBING -> "Transcribing…"
    Status.SUMMARIZING -> "Summarising…"
    Status.READY -> "Ready"
    Status.FAILED -> "Failed"
}

@Composable
fun StatusPill(r: Recording) {
    val (icon, label, color) = when {
        r.isBusy -> Triple(null, statusLabel(r.status), MaterialTheme.colorScheme.primary)
        r.status == Status.FAILED -> Triple(Icons.Filled.ErrorOutline, "Failed", MaterialTheme.colorScheme.error)
        r.isSynced -> Triple(Icons.Filled.CloudDone, "Synced", MaterialTheme.colorScheme.tertiary)
        r.cloudAudio -> Triple(Icons.Filled.CloudUpload, "Changes not synced", MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(Icons.Filled.CloudOff, "Local only", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = color)
        else Icon(icon, null, Modifier.size(14.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun Waveform(levels: List<Float>, color: Color, modifier: Modifier = Modifier, bars: Int = 60) {
    Canvas(modifier) {
        val data = levels.takeLast(bars)
        val barW = size.width / bars
        val mid = size.height / 2
        data.forEachIndexed { i, v ->
            val h = (v.coerceIn(0.02f, 1f)) * size.height * 0.95f
            val x = (bars - data.size + i) * barW
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * v.coerceIn(0f, 1f)),
                topLeft = Offset(x + barW * 0.2f, mid - h / 2),
                size = Size(barW * 0.6f, h),
                cornerRadius = CornerRadius(barW, barW),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
