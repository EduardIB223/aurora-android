package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme =
    darkColorScheme(
        primary = AuroraAccent,
        onPrimary = AuroraText,
        primaryContainer = AuroraAccentDark,
        onPrimaryContainer = AuroraText,
        secondary = AuroraCyan,
        onSecondary = AuroraBg,
        secondaryContainer = AuroraSurface3,
        onSecondaryContainer = AuroraText,
        tertiary = AuroraFuchsia,
        onTertiary = AuroraText,
        background = AuroraBg,
        onBackground = AuroraText,
        surface = AuroraSurface,
        onSurface = AuroraText,
        surfaceVariant = AuroraSurface2,
        onSurfaceVariant = AuroraTextDim,
        surfaceContainer = AuroraSurface2,
        surfaceContainerHigh = AuroraSurface3,
        surfaceContainerHighest = AuroraSurface3,
        surfaceContainerLow = AuroraSurface,
        surfaceContainerLowest = AuroraBg,
        outline = AuroraTextDim,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = AuroraAccent,
        onPrimary = AuroraSurfaceLight,
        primaryContainer = AuroraSurfaceLight3,
        onPrimaryContainer = AuroraAccentDark,
        secondary = AuroraCyan,
        onSecondary = AuroraSurfaceLight,
        secondaryContainer = AuroraSurfaceLight3,
        onSecondaryContainer = AuroraTextLight,
        tertiary = AuroraFuchsia,
        onTertiary = AuroraSurfaceLight,
        background = AuroraBgLight,
        onBackground = AuroraTextLight,
        surface = AuroraSurfaceLight,
        onSurface = AuroraTextLight,
        surfaceVariant = AuroraSurfaceLight2,
        onSurfaceVariant = AuroraTextLightDim,
        surfaceContainer = AuroraSurfaceLight2,
        surfaceContainerHigh = AuroraSurfaceLight3,
        surfaceContainerHighest = AuroraSurfaceLight3,
        surfaceContainerLow = AuroraSurfaceLight,
        surfaceContainerLowest = AuroraSurfaceLight,
        outline = AuroraTextLightDim,
    )

@Composable
fun SFATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: the Aurora palette should look the same on every device
    // rather than being recoloured by the system wallpaper.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
