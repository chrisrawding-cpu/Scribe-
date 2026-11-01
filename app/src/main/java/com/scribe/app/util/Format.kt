package com.scribe.app.util

import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60000
    val sec = (ms / 1000) % 60
    return when {
        totalMin >= 60 -> "${totalMin / 60}h ${totalMin % 60}m"
        totalMin > 0 -> "${totalMin}m ${sec}s"
        else -> "${sec}s"
    }
}

fun formatDateTime(ts: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ts))
fun formatDate(ts: Long): String = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(ts))
fun formatShort(ts: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ts))
fun dayKey(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

fun srtTime(sec: Double): String {
    val ms = (sec * 1000).toLong().coerceAtLeast(0)
    return "%02d:%02d:%02d,%03d".format(ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
}
