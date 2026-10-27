package app.rollentrainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.rollentrainer.core.metrics.PowerZone

/** Colours for the three live metrics, used in tiles and charts alike. */
object Metric {
    val Power = Color(0xFFFF7A45)
    val Target = Color(0xFF9AA3AF)
    val HeartRate = Color(0xFFF43F5E)
    val Cadence = Color(0xFF38BDF8)
}

/** Zone colours by index, shared by the Coggan (7) and Zwift (6) schemes. */
private val ZoneColors = listOf(
    Color(0xFF8A94A6), Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFEAB308),
    Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFA855F7),
)

fun zoneColorAt(index: Int): Color = ZoneColors[index.coerceIn(0, ZoneColors.lastIndex)]

fun zoneColor(zone: PowerZone): Color = when (zone) {
    PowerZone.Z1 -> Color(0xFF8A94A6)
    PowerZone.Z2 -> Color(0xFF3B82F6)
    PowerZone.Z3 -> Color(0xFF22C55E)
    PowerZone.Z4 -> Color(0xFFEAB308)
    PowerZone.Z5 -> Color(0xFFF97316)
    PowerZone.Z6 -> Color(0xFFEF4444)
    PowerZone.Z7 -> Color(0xFFA855F7)
}

private val Accent = Color(0xFFFF5A1F)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1B0F),
    onPrimaryContainer = Color(0xFFFFD9CC),
    secondary = Color(0xFFB6BDC8),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1A1D22),
    onSurfaceVariant = Color(0xFF9AA3AF),
    surfaceContainer = Color(0xFF16191D),
    surfaceContainerHigh = Color(0xFF1C2025),
    surfaceContainerHighest = Color(0xFF23272D),
    surfaceContainerLow = Color(0xFF131518),
    outline = Color(0xFF353A42),
    outlineVariant = Color(0xFF262A30),
    error = Color(0xFFF87171),
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4D9),
    onPrimaryContainer = Color(0xFF3A1B0F),
    secondary = Color(0xFF4B5563),
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF111316),
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF111316),
    surfaceVariant = Color(0xFFECEDEF),
    onSurfaceVariant = Color(0xFF5B6270),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEFF0F2),
    surfaceContainerLow = Color(0xFFFFFFFF),
    outline = Color(0xFFD5D8DD),
    outlineVariant = Color(0xFFE6E8EB),
)

private val base = Typography()

/** Numbers use a heavy weight with tabular figures so they don't jump while changing. */
val NumberStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

private val AppTypography = base.copy(
    displayLarge = base.displayLarge.merge(NumberStyle),
    displayMedium = base.displayMedium.merge(NumberStyle),
    displaySmall = base.displaySmall.merge(NumberStyle),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.6.sp),
)

@Composable
fun RollentrainerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
