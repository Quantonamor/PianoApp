package de.pianotiles.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object PtColors {
    val Background = Color(0xFF0E1220)
    val Surface = Color(0xFF171C30)
    val SurfaceHigh = Color(0xFF222944)
    val Outline = Color(0xFF343C5E)
    val RightHand = Color(0xFFFF8A3D)
    val RightHandDark = Color(0xFFD9481A)
    val LeftHand = Color(0xFF6E7BFF)
    val LeftHandDark = Color(0xFF3B3FC9)
    val Hit = Color(0xFF69F0AE)
    val Miss = Color(0xFFFF5252)
    val Loop = Color(0xFFB388FF)
    val TextDim = Color(0xFF9AA3C7)
    val WhiteKey = Color(0xFFF4F5FA)
    val BlackKey = Color(0xFF1A1D2B)
}

private val scheme = darkColorScheme(
    primary = PtColors.RightHand,
    onPrimary = Color(0xFF00283A),
    secondary = PtColors.LeftHand,
    onSecondary = Color(0xFF3A2000),
    tertiary = PtColors.Loop,
    background = PtColors.Background,
    onBackground = Color(0xFFE6E8F2),
    surface = PtColors.Surface,
    onSurface = Color(0xFFE6E8F2),
    surfaceVariant = PtColors.SurfaceHigh,
    onSurfaceVariant = PtColors.TextDim,
    surfaceContainer = PtColors.Surface,
    surfaceContainerHigh = PtColors.SurfaceHigh,
    surfaceContainerHighest = PtColors.SurfaceHigh,
    outline = PtColors.Outline,
    error = PtColors.Miss,
)

@Composable
fun PianoTilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
