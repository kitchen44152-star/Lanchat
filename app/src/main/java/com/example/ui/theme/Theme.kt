package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class LanChatExtendedColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val directGreen: Color,
    val multiHopAmber: Color,
    val dtnIndigo: Color,
    val verifiedTeal: Color,
    val isDark: Boolean
)

val LocalLanChatColors = staticCompositionLocalOf {
    LanChatExtendedColors(
        outgoingBubble = OutgoingBubbleLight,
        incomingBubble = IncomingBubbleLight,
        directGreen = StatusDirectEmerald,
        multiHopAmber = StatusMultiHopAmber,
        dtnIndigo = StatusDtnIndigo,
        verifiedTeal = StatusVerifiedTeal,
        isDark = false
    )
}

private val LightColorScheme = lightColorScheme(
    primary = MeshTealPrimary,
    onPrimary = MeshTealOnPrimary,
    primaryContainer = MeshTealContainer,
    onPrimaryContainer = MeshTealOnContainer,
    secondary = SignalCyanSecondary,
    onSecondary = SignalCyanOnSecondary,
    secondaryContainer = SignalCyanContainer,
    onSecondaryContainer = SignalCyanOnContainer,
    tertiary = RelayIndigoTertiary,
    onTertiary = RelayIndigoOnTertiary,
    tertiaryContainer = RelayIndigoContainer,
    onTertiaryContainer = RelayIndigoOnContainer,
    background = SlateLightBackground,
    onBackground = SlateLightOnSurface,
    surface = SlateLightSurface,
    onSurface = SlateLightOnSurface,
    surfaceVariant = SlateLightSurfaceVariant,
    onSurfaceVariant = SlateLightOnSurfaceVariant,
    outline = SlateLightOutline,
    outlineVariant = SlateLightOutlineVariant,
    error = StatusErrorRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = MeshTealDarkPrimary,
    onPrimary = MeshTealDarkOnPrimary,
    primaryContainer = MeshTealDarkContainer,
    onPrimaryContainer = MeshTealDarkOnContainer,
    secondary = SignalCyanDarkSecondary,
    onSecondary = SignalCyanDarkOnSecondary,
    secondaryContainer = SignalCyanDarkContainer,
    onSecondaryContainer = SignalCyanDarkOnContainer,
    tertiary = RelayIndigoDarkTertiary,
    onTertiary = RelayIndigoDarkOnTertiary,
    tertiaryContainer = RelayIndigoDarkContainer,
    onTertiaryContainer = RelayIndigoDarkOnContainer,
    background = SlateDarkBackground,
    onBackground = SlateDarkOnSurface,
    surface = SlateDarkSurface,
    onSurface = SlateDarkOnSurface,
    surfaceVariant = SlateDarkSurfaceVariant,
    onSurfaceVariant = SlateDarkOnSurfaceVariant,
    outline = SlateDarkOutline,
    outlineVariant = SlateDarkOutlineVariant,
    error = StatusErrorRedDark,
    onError = Color(0xFF450A0A)
)

val LanChatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LanChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = LanChatExtendedColors(
        outgoingBubble = if (darkTheme) OutgoingBubbleDark else OutgoingBubbleLight,
        incomingBubble = if (darkTheme) IncomingBubbleDark else IncomingBubbleLight,
        directGreen = StatusDirectEmerald,
        multiHopAmber = StatusMultiHopAmber,
        dtnIndigo = StatusDtnIndigo,
        verifiedTeal = StatusVerifiedTeal,
        isDark = darkTheme
    )

    CompositionLocalProvider(LocalLanChatColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LanChatTypography,
            shapes = LanChatShapes,
            content = content
        )
    }
}
