package com.scribe.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val RecordRed = Color(0xFFE5484D)

private val Light = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    secondary = Color(0xFF6E5DC6),
    tertiary = Color(0xFF0E9F8E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9B2FF),
    secondary = Color(0xFFC9B8FF),
    tertiary = Color(0xFF5FD6C4),
    background = Color(0xFF101322),
    surface = Color(0xFF101322),
)

/** Distinct colours for speakers / mind-map branches. */
val Palette = listOf(
    Color(0xFF5B6CF0), Color(0xFF0E9F8E), Color(0xFFE5793B), Color(0xFFC2459B),
    Color(0xFF3A9BDC), Color(0xFF8D6E2F), Color(0xFF7C4DFF), Color(0xFF2E7D32),
)

@Composable
fun ScribeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
