package com.foodflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FoodFlowSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

val FoodFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Immutable
data class FoodFlowExtendedColors(
    val vegGreen: Color,
    val vegSurface: Color,
    val nonVegRed: Color,
    val nonVegSurface: Color,
    val ratingGold: Color,
    val offlineContainer: Color,
    val offlineContent: Color,
    val promoGold: Color,
    val promoSurface: Color,
    val syncedGreen: Color,
    val pendingAmber: Color,
    val accentTeal: Color,
    val accentTealSurface: Color
)

val LocalFoodFlowColors = staticCompositionLocalOf {
    FoodFlowExtendedColors(
        vegGreen = PureVegGreen,
        vegSurface = PureVegSurface,
        nonVegRed = NonVegCrimson,
        nonVegSurface = NonVegSurface,
        ratingGold = RatingStarGold,
        offlineContainer = OfflineBannerBg,
        offlineContent = OfflineBannerAmber,
        promoGold = WarmAmber,
        promoSurface = WarmAmberSoft,
        syncedGreen = FreshGreen,
        pendingAmber = OfflineBannerAmber,
        accentTeal = ElectricTeal,
        accentTealSurface = ElectricTealSoft
    )
}

private val LightColorScheme = lightColorScheme(
    primary = FreshGreen,
    onPrimary = Color.White,
    primaryContainer = FreshGreenSoft,
    onPrimaryContainer = FreshGreenDeep,
    secondary = ElectricTeal,
    onSecondary = Color.White,
    secondaryContainer = ElectricTealSoft,
    onSecondaryContainer = ElectricTealDeep,
    tertiary = WarmAmber,
    onTertiary = InkPrimaryLight,
    tertiaryContainer = WarmAmberSoft,
    onTertiaryContainer = Color(0xFF78350F),
    background = NeutralBackgroundLight,
    onBackground = InkPrimaryLight,
    surface = NeutralSurfaceLight,
    onSurface = InkPrimaryLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = InkSecondaryLight,
    outline = NeutralOutlineLight,
    outlineVariant = NeutralOutlineVariantLight,
    error = NonVegCrimson,
    onError = Color.White,
    errorContainer = NonVegSurface,
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2DD4A4),
    onPrimary = Color(0xFF00291D),
    primaryContainer = FreshGreenContainerDark,
    onPrimaryContainer = FreshGreenSoft,
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF002B33),
    secondaryContainer = ElectricTealContainerDark,
    onSecondaryContainer = ElectricTealSoft,
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF2E1E00),
    tertiaryContainer = WarmAmberDark,
    onTertiaryContainer = WarmAmberSoft,
    background = NeutralBackgroundDark,
    onBackground = InkPrimaryDark,
    surface = NeutralSurfaceDark,
    onSurface = InkPrimaryDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = InkSecondaryDark,
    outline = NeutralOutlineDark,
    outlineVariant = NeutralOutlineVariantDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0909),
    errorContainer = Color(0xFF5C1515),
    onErrorContainer = Color(0xFFFFDAD6)
)

object FoodFlowThemeTokens {
    val extendedColors: FoodFlowExtendedColors
        @Composable
        get() = LocalFoodFlowColors.current
}

/** Applies FoodFlow colors and typography, choosing the requested or system theme palette. */
@Composable
fun FoodFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) {
        FoodFlowExtendedColors(
            vegGreen = Color(0xFF4ADE80),
            vegSurface = Color(0xFF103320),
            nonVegRed = Color(0xFFF87171),
            nonVegSurface = Color(0xFF3E1818),
            ratingGold = Color(0xFFFBBF24),
            offlineContainer = OfflineBannerBgDark,
            offlineContent = Color(0xFFFCD34D),
            promoGold = Color(0xFFFBBF24),
            promoSurface = WarmAmberDark,
            syncedGreen = Color(0xFF2DD4A4),
            pendingAmber = Color(0xFFFBBF24),
            accentTeal = Color(0xFF22D3EE),
            accentTealSurface = ElectricTealContainerDark
        )
    } else {
        FoodFlowExtendedColors(
            vegGreen = PureVegGreen,
            vegSurface = PureVegSurface,
            nonVegRed = NonVegCrimson,
            nonVegSurface = NonVegSurface,
            ratingGold = RatingStarGold,
            offlineContainer = OfflineBannerBg,
            offlineContent = OfflineBannerAmber,
            promoGold = WarmAmber,
            promoSurface = WarmAmberSoft,
            syncedGreen = FreshGreen,
            pendingAmber = OfflineBannerAmber,
            accentTeal = ElectricTeal,
            accentTealSurface = ElectricTealSoft
        )
    }

    CompositionLocalProvider(LocalFoodFlowColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FoodFlowTypography,
            shapes = FoodFlowShapes,
            content = content
        )
    }
}
