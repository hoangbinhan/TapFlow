package app.tapflow.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Minimalist palette: neutral surfaces, hairline outlines and a single accent. Colour carries meaning
 * (accent = primary action, danger = destructive, success/warn = status), never decoration.
 */
@Immutable
class Palette(
    val bg: Color,
    val surface: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
    val warn: Color,
)

private val LightPalette = Palette(
    bg = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    outline = Color(0xFFE2E5EA),
    text = Color(0xFF14171F),
    muted = Color(0xFF5B6372),
    accent = Color(0xFF2F5BEA),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE8EEFF),
    onAccentSoft = Color(0xFF1E3FB0),
    danger = Color(0xFFC62839),
    dangerSoft = Color(0xFFFDECEE),
    success = Color(0xFF1B8A50),
    warn = Color(0xFF9A5B00),
)

private val DarkPalette = Palette(
    bg = Color(0xFF0E1014),
    surface = Color(0xFF171A20),
    outline = Color(0xFF2B303A),
    text = Color(0xFFECEFF4),
    muted = Color(0xFF9AA3B2),
    accent = Color(0xFF7C9BFF),
    onAccent = Color(0xFF0B1533),
    accentSoft = Color(0xFF222F5A),
    onAccentSoft = Color(0xFFC9D6FF),
    danger = Color(0xFFFF7A86),
    dangerSoft = Color(0xFF3A1F24),
    success = Color(0xFF4CCB8A),
    warn = Color(0xFFF0B04D),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val CardShape = RoundedCornerShape(16.dp)
val ControlShape = RoundedCornerShape(12.dp)

private fun scheme(p: Palette, dark: Boolean) =
    if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.onAccentSoft,
        secondary = p.accent, onSecondary = p.onAccent,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.onAccentSoft,
        tertiary = p.accent, onTertiary = p.onAccent,
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, onSurfaceVariant = p.muted,
        surfaceVariant = p.bg, surfaceTint = p.surface,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.bg,
        outline = p.outline, outlineVariant = p.outline,
        error = p.danger, onError = p.onAccent, errorContainer = p.dangerSoft, onErrorContainer = p.danger,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.onAccentSoft,
        secondary = p.accent, onSecondary = p.onAccent,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.onAccentSoft,
        tertiary = p.accent, onTertiary = p.onAccent,
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, onSurfaceVariant = p.muted,
        surfaceVariant = p.bg, surfaceTint = p.surface,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.bg,
        outline = p.outline, outlineVariant = p.outline,
        error = p.danger, onError = p.onAccent, errorContainer = p.dangerSoft, onErrorContainer = p.danger,
    )

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = ControlShape,
    large = CardShape,
    extraLarge = CardShape,
)

private fun weight(base: TextStyle, w: FontWeight) = base.copy(fontWeight = w)

private val AppType = Typography().let { t ->
    t.copy(
        headlineMedium = weight(t.headlineMedium, FontWeight.SemiBold),
        headlineSmall = weight(t.headlineSmall, FontWeight.SemiBold),
        titleLarge = weight(t.titleLarge, FontWeight.SemiBold),
        titleMedium = weight(t.titleMedium, FontWeight.SemiBold),
        titleSmall = weight(t.titleSmall, FontWeight.SemiBold),
        labelLarge = weight(t.labelLarge, FontWeight.SemiBold),
        labelMedium = weight(t.labelMedium, FontWeight.Medium),
    )
}

@Composable
fun TapFlowTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme(palette, dark), shapes = AppShapes, typography = AppType, content = content)
    }
}
