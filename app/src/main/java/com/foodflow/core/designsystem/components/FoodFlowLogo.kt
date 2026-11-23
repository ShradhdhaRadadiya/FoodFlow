package com.foodflow.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Theme-aware FoodFlow vector brand mark driven entirely by MaterialTheme.colorScheme.
 * Adapts seamlessly between Light and Dark modes without hardcoded orange accents.
 */
@Composable
fun FoodFlowLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    invertedOnBrandSurface: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerBrush = if (invertedOnBrandSurface) {
        Brush.linearGradient(
            colors = listOf(
                colorScheme.surface,
                colorScheme.surfaceVariant
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                colorScheme.primary,
                colorScheme.secondary
            )
        )
    }
    val foregroundTint = if (invertedOnBrandSurface) {
        colorScheme.primary
    } else {
        colorScheme.onPrimary
    }
    val accentTint = if (invertedOnBrandSurface) {
        colorScheme.secondary
    } else {
        colorScheme.primaryContainer
    }
    val borderTint = if (invertedOnBrandSurface) {
        colorScheme.outlineVariant.copy(alpha = 0.5f)
    } else {
        colorScheme.onPrimary.copy(alpha = 0.18f)
    }
    val corner = RoundedCornerShape(size * 0.28f)

    Box(
        modifier = modifier
            .size(size)
            .clip(corner)
            .background(containerBrush)
            .border(1.dp, borderTint, corner)
            .semantics { contentDescription = "FoodFlow logo" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.64f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = w * 0.085f

            // Cloche dome arc (culinary freshness emblem)
            val domePath = Path().apply {
                moveTo(w * 0.14f, h * 0.66f)
                cubicTo(
                    w * 0.14f, h * 0.28f,
                    w * 0.86f, h * 0.28f,
                    w * 0.86f, h * 0.66f
                )
                close()
            }
            drawPath(
                path = domePath,
                color = foregroundTint,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Cloche top finial knob
            drawCircle(
                color = accentTint,
                radius = w * 0.075f,
                center = Offset(w * 0.50f, h * 0.25f)
            )

            // Serving platter base
            drawRoundRect(
                color = foregroundTint,
                topLeft = Offset(w * 0.08f, h * 0.68f),
                size = Size(w * 0.84f, h * 0.11f),
                cornerRadius = CornerRadius(h * 0.06f, h * 0.06f)
            )

            // Dynamic speed-flow wave underneath representing fast delivery
            val flowPath = Path().apply {
                moveTo(w * 0.20f, h * 0.88f)
                quadraticTo(w * 0.50f, h * 0.80f, w * 0.82f, h * 0.88f)
            }
            drawPath(
                path = flowPath,
                color = accentTint,
                style = Stroke(width = stroke * 0.85f, cap = StrokeCap.Round)
            )

            // Fresh botanical leaf accent inside the dome
            val leafPath = Path().apply {
                moveTo(w * 0.44f, h * 0.55f)
                quadraticTo(w * 0.48f, h * 0.40f, w * 0.62f, h * 0.42f)
                quadraticTo(w * 0.58f, h * 0.56f, w * 0.44f, h * 0.55f)
                close()
            }
            drawPath(path = leafPath, color = accentTint)
        }
    }
}

/**
 * Complete FoodFlow brand lockup combining the theme-aware [FoodFlowLogoMark]
 * with stylized typography that follows MaterialTheme colors in Light & Dark modes.
 */
@Composable
fun FoodFlowLogoLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 40.dp,
    showTagline: Boolean = false,
    invertedOnBrandSurface: Boolean = false
) {
    val titleColor = if (invertedOnBrandSurface) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val accentColor = if (invertedOnBrandSurface) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    val subtitleColor = if (invertedOnBrandSurface) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.84f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FoodFlowLogoMark(
            size = markSize,
            invertedOnBrandSurface = invertedOnBrandSurface
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Food",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = titleColor
                )
                Text(
                    text = "Flow",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor
                )
            }
            if (showTagline) {
                Text(
                    text = "Fresh local kitchens, delivered",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor
                )
            }
        }
    }
}
