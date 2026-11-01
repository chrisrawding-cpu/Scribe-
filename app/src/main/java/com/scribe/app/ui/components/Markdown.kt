package com.scribe.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Lightweight markdown renderer for AI output (headings, lists, checkboxes, bold/italic/code, quotes). */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val indent = ((line.length - trimmed.length) / 2).coerceAtMost(4)
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(inline(trimmed.drop(4)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                trimmed.startsWith("## ") -> Text(inline(trimmed.drop(3)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                trimmed.startsWith("# ") -> Text(inline(trimmed.drop(2)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
                trimmed == "---" || trimmed == "***" -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                trimmed.startsWith("> ") -> Text(inline(trimmed.drop(2)), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                Regex("^[-*+] \\[( |x|X)] ").containsMatchIn(trimmed) -> {
                    val checked = trimmed[3].lowercaseChar() == 'x'
                    Bullet(if (checked) "☑" else "☐", trimmed.drop(6), indent)
                }
                Regex("^[-*+] ").containsMatchIn(trimmed) -> Bullet("•", trimmed.drop(2), indent)
                Regex("^\\d+[.)] ").containsMatchIn(trimmed) -> {
                    val num = trimmed.substringBefore(' ')
                    Bullet(num, trimmed.substringAfter(' '), indent)
                }
                else -> Text(inline(trimmed), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Bullet(marker: String, body: String, indent: Int) {
    Row(Modifier.padding(start = (indent * 14).dp)) {
        Text(marker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
        Text(inline(body), style = MaterialTheme.typography.bodyMedium)
    }
}

fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 } else { append(s[i]); i++ }
            }
            s.startsWith("__", i) -> {
                val end = s.indexOf("__", i + 2)
                if (end > i) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2 } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(s[i]); i++ }
            }
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' -> {
                val end = s.indexOf(s[i], i + 1)
                if (end > i + 1) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }; i = end + 1 } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
