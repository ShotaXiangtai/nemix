package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Bitchat-specific color tokens formatted for the classic messenger format.
 */
@Immutable
data class BitchatPalette(
    // MARK: - Form controls
    val inputOutline: Color,
    val inputOutlineFocused: Color,
    val inputSurface: Color,
    val inputSurfaceFocused: Color,
    val inputButton: Color,

    // MARK: - Extra semantics
    val textTertiary: Color,
    val accentOrange: Color,
    val accentPurple: Color,

    // MARK: - Deterministic peer colors
    val peerColors: PeerColorStyle,
)

val DarkBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFF2A3942),
    inputOutlineFocused = Color(0xFF00A884),
    inputSurface = Color(0xFF202C33),
    inputSurfaceFocused = Color(0xFF2A3942),
    inputButton = Color(0xFF2A3942),
    textTertiary = Color(0xFF8696A0),
    accentOrange = Color(0xFF25D366),
    accentPurple = Color(0xFF00A884),
    peerColors = PeerColorStyle.Dark,
)

val LightBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFFE9EDEF),
    inputOutlineFocused = Color(0xFF008069),
    inputSurface = Color(0xFFFFFFFF),
    inputSurfaceFocused = Color(0xFFF7F8FA),
    inputButton = Color(0xFFF0F2F5),
    textTertiary = Color(0xFF667781),
    accentOrange = Color(0xFF008069),
    accentPurple = Color(0xFF128C7E),
    peerColors = PeerColorStyle.Light,
)

val LocalBitchatPalette = staticCompositionLocalOf { DarkBitchatPalette }

object BitchatMotion {
    const val QUICK_MS = 120
    const val STANDARD_MS = 180
    const val EMPHASIZED_MS = 240
}
