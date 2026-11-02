package com.bitchat.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Classic-format Color Schemes for bitchat Messenger
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = ClassicColors.DarkAccentGreen,
    onPrimary = Color.White,
    primaryContainer = ClassicColors.OutgoingBubbleDark,
    onPrimaryContainer = Color(0xFFE9EDEF),
    secondary = ClassicColors.AccentGreen,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1F2C34),
    onSecondaryContainer = Color(0xFFE9EDEF),
    tertiary = ClassicColors.AccentGreen,
    onTertiary = Color.Black,
    background = ClassicColors.ChatBackgroundDark,
    onBackground = ClassicColors.TextPrimaryDark,
    surface = ClassicColors.SurfaceDark,
    onSurface = ClassicColors.TextPrimaryDark,
    surfaceVariant = ClassicColors.SurfaceVariantDark,
    onSurfaceVariant = ClassicColors.TextSecondaryDark,
    outline = ClassicColors.DividerDark,
    outlineVariant = Color(0xFF222D34),
    error = Color(0xFFFF5252),
    onError = Color.White
)

internal val LightBitchatColorScheme = lightColorScheme(
    primary = ClassicColors.Teal,
    onPrimary = Color.White,
    primaryContainer = ClassicColors.OutgoingBubbleLight,
    onPrimaryContainer = Color(0xFF003822),
    secondary = ClassicColors.AccentGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9FDD3),
    onSecondaryContainer = Color(0xFF003822),
    tertiary = ClassicColors.AccentGreen,
    onTertiary = Color.White,
    background = ClassicColors.SurfaceLight,
    onBackground = ClassicColors.TextPrimaryLight,
    surface = ClassicColors.SurfaceLight,
    onSurface = ClassicColors.TextPrimaryLight,
    surfaceVariant = ClassicColors.SurfaceVariantLight,
    onSurfaceVariant = ClassicColors.TextSecondaryLight,
    outline = ClassicColors.DividerLight,
    outlineVariant = Color(0xFFE9EDEF),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkBitchatColorScheme else LightBitchatColorScheme
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette

    val view = LocalView.current
    SideEffect {
        view.context.findActivity()?.window?.let { window ->
            // Transparent: every screen draws its own header colour behind the status bar
            // (teal home, blue stream group, grey channel group). A fixed colour here
            // covered those on Android 9–14.
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            // Light icons on the teal / dark top bars. Go through the compat controller:
            // it works on every API level and, unlike overwriting systemUiVisibility,
            // keeps the edge-to-edge layout flags intact.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = !shouldUseDark
            }
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.surface.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalBitchatPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Switches the status bar to dark icons while a screen with a light top bar is shown
 * (e.g. the light channel-format header), and back to the app default when it leaves.
 */
@Composable
fun DarkStatusBarIcons(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val controller = view.context.findActivity()?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = enabled
        onDispose { controller?.isAppearanceLightStatusBars = false }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
