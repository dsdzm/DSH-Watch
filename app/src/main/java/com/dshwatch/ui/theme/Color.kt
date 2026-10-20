package com.dshwatch.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * MD3 配色。
 * 手表多数是 OLED，纯黑底最省电，所以深色方案用接近纯黑的 surface。
 */
private val Blue40 = Color(0xFF4C6FFF)
private val Blue80 = Color(0xFFB0C2FF)
private val Blue90 = Color(0xFFDCE1FF)
private val Blue10 = Color(0xFF001551)
private val Blue30 = Color(0xFF3B56D6)

private val Teal80 = Color(0xFF7DD3FC)
private val Teal40 = Color(0xFF0369A1)
private val Teal10 = Color(0xFF001E2E)

private val NeutralDark0 = Color(0xFF000000)
private val NeutralDark1 = Color(0xFF0B0F14)
private val NeutralDark2 = Color(0xFF121821)
private val NeutralDark3 = Color(0xFF1B222C)
private val NeutralDark4 = Color(0xFF262E3A)
private val NeutralDark5 = Color(0xFF323B49)

private val NeutralLight0 = Color(0xFFFFFFFF)
private val NeutralLight1 = Color(0xFFF7F8FB)
private val NeutralLight2 = Color(0xFFEDEFF5)
private val NeutralLight3 = Color(0xFFE0E3EB)

val DshDarkColors = darkColorScheme(
    primary = Color(0xFFD6E3FF),
    onPrimary = Color(0xFF101418),
    primaryContainer = Color(0xFF2C3A52),
    onPrimaryContainer = Color(0xFFDCE6F8),
    secondary = Color(0xFFBFD4F0),
    onSecondary = Color(0xFF14181E),
    secondaryContainer = Color(0xFF33383F),
    onSecondaryContainer = Color(0xFFDDE3EC),
    tertiary = Color(0xFFD9D2E8),
    onTertiary = Color(0xFF1A1720),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFC4C4C4),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF343434),
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC000000),
)

val DshLightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary = Teal40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8FF),
    onSecondaryContainer = Teal10,
    tertiary = Color(0xFF6B5FD4),
    onTertiary = Color(0xFFFFFFFF),
    background = NeutralLight0,
    onBackground = Color(0xFF121821),
    surface = NeutralLight0,
    onSurface = Color(0xFF121821),
    surfaceVariant = NeutralLight3,
    onSurfaceVariant = Color(0xFF444C58),
    surfaceContainerLowest = NeutralLight0,
    surfaceContainerLow = NeutralLight1,
    surfaceContainer = NeutralLight2,
    surfaceContainerHigh = NeutralLight3,
    surfaceContainerHighest = Color(0xFFD3D7E0),
    outline = Color(0xFF757D8A),
    outlineVariant = Color(0xFFC4C9D4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0x99000000),
)
