package com.triggerex.hotspotstats.ui.theme

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

private val Light = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    secondary = Color(0xFF52606F),
    tertiary = Color(0xFF00897B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA4C8FF),
    secondary = Color(0xFFBAC8DA),
    tertiary = Color(0xFF80CBC4),
)

/** Semantic colours for good / fair / poor readings, readable in both themes. */
object Quality {
    val good = Color(0xFF2E7D32)
    val fair = Color(0xFFF9A825)
    val poor = Color(0xFFC62828)
}

@Composable
fun HotspotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
