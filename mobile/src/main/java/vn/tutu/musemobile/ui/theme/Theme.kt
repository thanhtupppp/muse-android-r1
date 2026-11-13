package vn.tutu.musemobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Hệ thống thiết kế của Muse Mobile: bảng màu thương hiệu Indigo/Teal,
 * bo góc thống nhất (card 16dp, nút/trường nhập 12dp) cho cả sáng và tối.
 */
private val BrandIndigo = Color(0xFF4F46E5)
private val BrandIndigoDark = Color(0xFF3730A3)
private val BrandTeal = Color(0xFF0F766E)
private val BrandAmber = Color(0xFFD97706)

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = BrandAmber,
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EAF3),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFC7CAD6),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B0E1A),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF151A2E),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF232842),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF3F4560),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Màu trạng thái dùng thống nhất cho chip/log trong toàn app. */
object StatusColors {
    val Success = Color(0xFF15803D)
    val SuccessDark = Color(0xFF4ADE80)
    val Warning = Color(0xFFB45309)
    val Neutral = Color(0xFF6B7280)
    val ConsoleBackground = Color(0xFF11131A)
    val ConsoleText = Color(0xFFE5E7EB)
    val ConsoleError = Color(0xFFFF8A80)
    val ConsoleSuccess = Color(0xFF69F0AE)
    val ConsoleAccent = Color(0xFF82B1FF)
    val ConsoleHighlight = Color(0xFFFFD54F)
}

@Composable
fun MuseMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
