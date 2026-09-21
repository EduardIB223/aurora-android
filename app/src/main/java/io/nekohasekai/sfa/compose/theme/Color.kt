package io.nekohasekai.sfa.compose.theme

import androidx.compose.ui.graphics.Color

// Primary colors from existing app
val SingBoxPrimary = Color(0xFFD81B60)
val SingBoxPrimaryDark = Color(0xFFA00037)
val SingBoxPrimaryLight = Color(0xFFFF5C8D)

// ── Aurora palette (mirrors the desktop client's design tokens) ──
val AuroraAccent = Color(0xFF6366F1)
val AuroraAccentDark = Color(0xFF4F46E5)
val AuroraCyan = Color(0xFF06B6D4)
val AuroraFuchsia = Color(0xFFC026D3)

val AuroraBg = Color(0xFF0A0E23)
val AuroraSurface = Color(0xFF10152E)
val AuroraSurface2 = Color(0xFF161C3A)
val AuroraSurface3 = Color(0xFF1D2447)
val AuroraText = Color(0xFFE7EAF6)
val AuroraTextDim = Color(0xFF9AA3C7)

val AuroraBgLight = Color(0xFFF3F4FB)
val AuroraSurfaceLight = Color(0xFFFFFFFF)
val AuroraSurfaceLight2 = Color(0xFFF7F8FD)
val AuroraSurfaceLight3 = Color(0xFFECEEF9)
val AuroraTextLight = Color(0xFF171A2E)
val AuroraTextLightDim = Color(0xFF5D6383)

/** Cyan to indigo to fuchsia, the aurora sweep used for the connect button. */
val AuroraGradient = listOf(AuroraCyan, AuroraAccent, AuroraFuchsia)

// Service status colors
val ServiceRunning = Color(0xFF4CAF50)
val ServiceStopped = Color(0xFF9E9E9E)
val ServiceError = Color(0xFFF44336)

// Log colors
val LogRed = Color(0xFFFF2158)
val LogGreen = Color(0xFF2ECC71)
val LogYellow = Color(0xFFE5E500)
val LogBlue = Color(0xFF3498DB)
val LogPurple = Color(0xFFE500E5)
val LogRedLight = Color(0xFFE91E63)
val LogBlueLight = Color(0xFF00A6B2)
val LogWhite = Color(0xFFECECEC)

// Material You seed color
val SeedColor = Color(0xFFD81B60)

// Additional semantic colors
val SuccessGreen = Color(0xFF4CAF50)
val WarningOrange = Color(0xFFFF9800)
val ErrorRed = Color(0xFFF44336)
val InfoBlue = Color(0xFF2196F3)
