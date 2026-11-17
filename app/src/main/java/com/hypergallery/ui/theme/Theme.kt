package com.hypergallery.ui.theme

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
import com.hypergallery.domain.model.ThemeMode

private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF002A32),
    primaryContainer = Color(0xFF004D5A),
    onPrimaryContainer = Color(0xFFB2F5FF),
    secondary = Color(0xFF9E7BFF),
    onSecondary = Color(0xFF1E0052),
    secondaryContainer = Color(0xFF351A75),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFF00E676),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F6FA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF4F6FA),
    surfaceVariant = Color(0xFF12151C),
    onSurfaceVariant = Color(0xFFB8C0CC),
    surfaceContainer = Color(0xFF0C0F14),
    surfaceContainerHigh = Color(0xFF161B24),
    outline = Color(0xFF2B3240)
)

private val SlateDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFB39DDB),
    tertiary = Color(0xFF69F0AE),
    background = Color(0xFF0E131A),
    onBackground = Color(0xFFEAF0F6),
    surface = Color(0xFF131922),
    onSurface = Color(0xFFEAF0F6),
    surfaceVariant = Color(0xFF1E2633),
    onSurfaceVariant = Color(0xFFB8C4D4)
)

private val CrispLightColorScheme = lightColorScheme(
    primary = Color(0xFF006876),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA1EFFF),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Color(0xFF5A45A3),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF12161C),
    surface = Color.White,
    onSurface = Color(0xFF12161C),
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = Color(0xFF424A54)
)

@Composable
fun HyperGalleryTheme(
    themeMode: ThemeMode,
    dynamicColors: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val baseScheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val wantDark = themeMode != ThemeMode.LIGHT && (themeMode != ThemeMode.SYSTEM || systemDark)
            if (wantDark) {
                val dyn = dynamicDarkColorScheme(context)
                if (themeMode == ThemeMode.AMOLED_BLACK) {
                    dyn.copy(
                        background = Color.Black,
                        surface = Color.Black,
                        surfaceContainer = Color(0xFF0B0D11)
                    )
                } else {
                    dyn
                }
            } else {
                dynamicLightColorScheme(context)
            }
        }
        themeMode == ThemeMode.AMOLED_BLACK -> AmoledColorScheme
        themeMode == ThemeMode.SLATE_DARK -> SlateDarkColorScheme
        themeMode == ThemeMode.LIGHT -> CrispLightColorScheme
        themeMode == ThemeMode.SYSTEM -> if (systemDark) AmoledColorScheme else CrispLightColorScheme
        else -> AmoledColorScheme
    }

    MaterialTheme(
        colorScheme = baseScheme,
        content = content
    )
}
