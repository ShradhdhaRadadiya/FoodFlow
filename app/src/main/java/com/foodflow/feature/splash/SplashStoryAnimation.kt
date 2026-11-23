package com.foodflow.feature.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foodflow.core.designsystem.components.FoodFlowLogoMark
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Connected 5-stage FoodFlow delivery story animation rendered purely with Jetpack Compose Canvas
 * and Material 3 color tokens:
 * 1. Kitchen cooking & packing parcel into delivery bag
 * 2. Delivery partner arriving & receiving parcel
 * 3. Rider travelling smoothly along the road to the customer
 * 4. Doorstep parcel handover to customer
 * 5. FoodFlow brand finale reveal
 */
@Composable
fun SplashStoryScene(
    progress: Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val stage = if (reducedMotion) {
        SplashStoryStage.BRAND_FINALE
    } else {
        SplashStoryTimeline.stageForProgress(progress)
    }

    val finaleT = if (reducedMotion) {
        1f
    } else {
        SplashStoryTimeline.localStageProgress(progress, SplashStoryStage.BRAND_FINALE)
    }
    val storyAlpha = if (reducedMotion) 0f else (1f - (finaleT * 1.55f)).coerceIn(0f, 1f)
    val brandAlpha = if (reducedMotion) 1f else ((progress - 0.80f) / 0.16f).coerceIn(0f, 1f)
    val brandScale = if (reducedMotion) 1f else (0.78f + 0.22f * easeOutBack(finaleT))

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "FoodFlow delivery story splash animation" },
        contentAlignment = Alignment.Center
    ) {
        val compactHeight = maxHeight < 560.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (compactHeight) 1.75f else 1.48f),
                contentAlignment = Alignment.Center
            ) {
                if (storyAlpha > 0.01f) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(storyAlpha)
                            .testTag("splash_story_canvas")
                    ) {
                        drawConnectedDeliveryStory(
                            progress = progress,
                            primary = colorScheme.primary,
                            primaryContainer = colorScheme.primaryContainer,
                            secondary = colorScheme.secondary,
                            tertiary = colorScheme.tertiary,
                            surface = colorScheme.surface,
                            surfaceVariant = colorScheme.surfaceVariant,
                            onSurface = colorScheme.onSurface,
                            outlineVariant = colorScheme.outlineVariant
                        )
                    }
                }

                if (brandAlpha > 0.01f) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .alpha(brandAlpha)
                            .scale(brandScale)
                            .testTag("splash_brand_finale")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(if (compactHeight) 128.dp else 156.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (compactHeight) 120.dp else 148.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                colorScheme.primary.copy(alpha = 0.24f),
                                                colorScheme.primary.copy(alpha = 0.02f)
                                            )
                                        )
                                    )
                            )
                            FoodFlowLogoMark(size = if (compactHeight) 88.dp else 104.dp)
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = "FoodFlow",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (compactHeight) 12.dp else 20.dp))

            AnimatedContent(
                targetState = stage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "splash_stage_caption"
            ) { activeStage ->
                Text(
                    text = activeStage.caption,
                    style = if (activeStage == SplashStoryStage.BRAND_FINALE) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = if (activeStage == SplashStoryStage.BRAND_FINALE) {
                        FontWeight.Medium
                    } else {
                        FontWeight.SemiBold
                    },
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("splash_stage_caption")
                )
            }

            if (!reducedMotion) {
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha((1f - finaleT * 1.4f).coerceIn(0f, 1f))
                ) {
                    val narrativeSteps = 4
                    repeat(narrativeSteps) { idx ->
                        val isActive = stage.stepIndex == idx
                        val isPast = stage.stepIndex > idx
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = when {
                                isActive -> colorScheme.primary
                                isPast -> colorScheme.primary.copy(alpha = 0.45f)
                                else -> colorScheme.outlineVariant
                            },
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (isActive) 22.dp else 6.dp)
                        ) {}
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawConnectedDeliveryStory(
    progress: Float,
    primary: Color,
    primaryContainer: Color,
    secondary: Color,
    tertiary: Color,
    surface: Color,
    surfaceVariant: Color,
    onSurface: Color,
    outlineVariant: Color
) {
    val w = size.width
    val h = size.height
    val groundY = h * 0.78f

    val cookT = SplashStoryTimeline.localStageProgress(progress, SplashStoryStage.KITCHEN_COOKING)
    val pickupT = SplashStoryTimeline.localStageProgress(progress, SplashStoryStage.PARTNER_PICKUP)
    val rideT = SplashStoryTimeline.localStageProgress(progress, SplashStoryStage.ROAD_DELIVERY)
    val dropT = SplashStoryTimeline.localStageProgress(progress, SplashStoryStage.DOORSTEP_HANDOVER)

    // Soft backdrop card glow
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                primaryContainer.copy(alpha = 0.36f),
                surface.copy(alpha = 0.82f)
            )
        ),
        topLeft = Offset(w * 0.03f, h * 0.06f),
        size = Size(w * 0.94f, h * 0.86f),
        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
    )

    // Subtle skyline / horizon hills shifting gently during road journey
    val parallaxShift = rideT * w * 0.14f
    val skylineColor = primary.copy(alpha = 0.08f)
    for (i in 0..4) {
        val bx = w * (0.14f + i * 0.18f) - parallaxShift * 0.35f
        val bh = h * (0.14f + (i % 3) * 0.06f)
        drawRoundRect(
            color = skylineColor,
            topLeft = Offset(bx, groundY - bh),
            size = Size(w * 0.09f, bh),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )
    }

    // Roadway surface & animated center lane dashes
    drawRoundRect(
        color = surfaceVariant.copy(alpha = 0.9f),
        topLeft = Offset(w * 0.06f, groundY),
        size = Size(w * 0.88f, h * 0.10f),
        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
    )
    val dashOffset = (rideT * 140f) % 36f
    val dashY = groundY + h * 0.05f
    var dx = w * 0.10f - dashOffset
    while (dx < w * 0.90f) {
        val startX = dx.coerceAtLeast(w * 0.08f)
        val endX = (dx + 18.dp.toPx()).coerceAtMost(w * 0.92f)
        if (endX > startX) {
            drawLine(
                color = outlineVariant,
                start = Offset(startX, dashY),
                end = Offset(endX, dashY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        dx += 36.dp.toPx()
    }

    // Left Anchor: Restaurant Kitchen & Awning
    val kitchenLeft = w * 0.09f
    val kitchenWidth = w * 0.23f
    val kitchenHeight = h * 0.34f
    val kitchenTop = groundY - kitchenHeight
    drawKitchenBuilding(
        left = kitchenLeft,
        top = kitchenTop,
        width = kitchenWidth,
        height = kitchenHeight,
        cookT = cookT,
        primary = primary,
        tertiary = tertiary,
        surface = surface,
        onSurface = onSurface,
        outlineVariant = outlineVariant
    )

    // Right Anchor: Customer Home Doorstep
    val homeWidth = w * 0.22f
    val homeHeight = h * 0.33f
    val homeLeft = w * 0.69f
    val homeTop = groundY - homeHeight
    drawCustomerHome(
        left = homeLeft,
        top = homeTop,
        width = homeWidth,
        height = homeHeight,
        doorGlow = dropT,
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        surface = surface,
        onSurface = onSurface
    )

    // Scooter Rider horizontal position smoothly connects all 4 acts
    val pickupSpotX = kitchenLeft + kitchenWidth + w * 0.07f
    val doorstepSpotX = homeLeft - w * 0.08f
    val riderX = when {
        progress < SplashStoryStage.PARTNER_PICKUP.startFraction -> {
            // Rider glides in from just outside left-center during late Act 1
            val approach = ((cookT - 0.65f) / 0.35f).coerceIn(0f, 1f)
            lerp(w * 0.24f, pickupSpotX, easeOutCubic(approach))
        }
        progress < SplashStoryStage.ROAD_DELIVERY.startFraction -> pickupSpotX
        progress < SplashStoryStage.DOORSTEP_HANDOVER.startFraction -> {
            lerp(pickupSpotX, doorstepSpotX, easeInOutCubic(rideT))
        }
        else -> doorstepSpotX
    }

    val riderAlpha = when {
        progress < SplashStoryStage.PARTNER_PICKUP.startFraction -> ((cookT - 0.55f) / 0.35f).coerceIn(0f, 1f)
        else -> 1f
    }

    val parcelOnBike = (progress >= SplashStoryStage.PARTNER_PICKUP.startFraction + 0.11f) &&
        (progress < SplashStoryStage.DOORSTEP_HANDOVER.startFraction + 0.04f)

    if (riderAlpha > 0.02f) {
        val bounceY = if (rideT in 0.01f..0.99f) sin(rideT * 8f * PI.toFloat()) * 2.5.dp.toPx() else 0f
        drawDeliveryRider(
            centerX = riderX,
            groundY = groundY + bounceY,
            wheelSpinRad = rideT * 14f,
            hasParcel = parcelOnBike,
            showSpeedLines = rideT in 0.05f..0.94f,
            alpha = riderAlpha,
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            onSurface = onSurface
        )
    }

    // Parcel arc: Act 2 (Kitchen window -> Rider box)
    if (progress in SplashStoryStage.PARTNER_PICKUP.startFraction..SplashStoryStage.ROAD_DELIVERY.startFraction) {
        val handoffT = (pickupT / 0.72f).coerceIn(0f, 1f)
        if (handoffT < 1f) {
            val start = Offset(kitchenLeft + kitchenWidth * 0.85f, kitchenTop + kitchenHeight * 0.58f)
            val end = Offset(riderX - 18.dp.toPx(), groundY - 34.dp.toPx())
            val arcPos = quadraticBezier(
                p0 = start,
                p1 = Offset((start.x + end.x) * 0.5f, minOf(start.y, end.y) - 28.dp.toPx()),
                p2 = end,
                t = easeInOutCubic(handoffT)
            )
            drawParcelBag(center = arcPos, primary = primary, tertiary = tertiary)
        } else {
            // Quick seal confirmation pulse on the delivery box
            val pulseT = ((pickupT - 0.72f) / 0.28f).coerceIn(0f, 1f)
            drawCircle(
                color = primary.copy(alpha = (1f - pulseT) * 0.45f),
                radius = (8f + pulseT * 18f).dp.toPx(),
                center = Offset(riderX - 18.dp.toPx(), groundY - 34.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }

    // Parcel arc & celebration sparkles: Act 4 (Rider -> Customer doorstep)
    if (progress >= SplashStoryStage.DOORSTEP_HANDOVER.startFraction) {
        val dropArcT = (dropT / 0.70f).coerceIn(0f, 1f)
        val start = Offset(riderX - 10.dp.toPx(), groundY - 34.dp.toPx())
        val end = Offset(homeLeft + homeWidth * 0.26f, groundY - 26.dp.toPx())
        val parcelPos = quadraticBezier(
            p0 = start,
            p1 = Offset((start.x + end.x) * 0.5f, minOf(start.y, end.y) - 26.dp.toPx()),
            p2 = end,
            t = easeOutCubic(dropArcT)
        )
        drawParcelBag(center = parcelPos, primary = primary, tertiary = tertiary)

        if (dropT > 0.55f) {
            val sparkleT = ((dropT - 0.55f) / 0.45f).coerceIn(0f, 1f)
            for (angleIdx in 0 until 6) {
                val angle = (angleIdx * 60f - 30f) * (PI / 180.0)
                val dist = (10f + sparkleT * 18f).dp.toPx()
                val sx = end.x + (cos(angle) * dist).toFloat()
                val sy = (end.y - 10.dp.toPx()) + (sin(angle) * dist).toFloat()
                drawCircle(
                    color = tertiary.copy(alpha = (1f - sparkleT) * 0.9f),
                    radius = (3.5f - sparkleT * 1.5f).dp.toPx(),
                    center = Offset(sx, sy)
                )
            }
        }
    }
}

private fun DrawScope.drawKitchenBuilding(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cookT: Float,
    primary: Color,
    tertiary: Color,
    surface: Color,
    onSurface: Color,
    outlineVariant: Color
) {
    // Main kitchen body
    drawRoundRect(
        color = surface,
        topLeft = Offset(left, top + height * 0.16f),
        size = Size(width, height * 0.84f),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
    )
    drawRoundRect(
        color = outlineVariant,
        topLeft = Offset(left, top + height * 0.16f),
        size = Size(width, height * 0.84f),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        style = Stroke(width = 1.5.dp.toPx())
    )

    // Striped restaurant awning
    drawRoundRect(
        color = primary,
        topLeft = Offset(left - 4.dp.toPx(), top + height * 0.10f),
        size = Size(width + 8.dp.toPx(), height * 0.18f),
        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
    )

    // Warm kitchen counter window
    val winLeft = left + width * 0.16f
    val winTop = top + height * 0.36f
    val winW = width * 0.68f
    val winH = height * 0.42f
    drawRoundRect(
        color = tertiary.copy(alpha = 0.22f + 0.14f * sin(cookT * PI.toFloat())),
        topLeft = Offset(winLeft, winTop),
        size = Size(winW, winH),
        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
    )

    // Cooking pan & cloche dome gently bobbing during Act 1
    val panBob = if (cookT in 0.01f..0.95f) sin(cookT * 6f * PI.toFloat()) * 2.dp.toPx() else 0f
    val panCenterX = winLeft + winW * 0.5f
    val panY = winTop + winH * 0.72f + panBob
    drawLine(
        color = onSurface.copy(alpha = 0.8f),
        start = Offset(panCenterX - 12.dp.toPx(), panY),
        end = Offset(panCenterX + 12.dp.toPx(), panY),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round
    )

    // Rising curved steam wisps
    val steamAlpha = (1f - (cookT - 0.7f).coerceAtLeast(0f) * 3f).coerceIn(0.15f, 0.85f)
    for (i in -1..1) {
        val sx = panCenterX + i * 7.dp.toPx()
        val rise = ((cookT * 2.2f + i * 0.25f) % 1f) * 14.dp.toPx()
        val path = Path().apply {
            moveTo(sx, panY - 4.dp.toPx() - rise)
            quadraticTo(
                sx + 3.dp.toPx(),
                panY - 10.dp.toPx() - rise,
                sx - 2.dp.toPx(),
                panY - 16.dp.toPx() - rise
            )
        }
        drawPath(
            path = path,
            color = primary.copy(alpha = steamAlpha * 0.7f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawCustomerHome(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    doorGlow: Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    surface: Color,
    onSurface: Color
) {
    // Pitched roof
    val roofPath = Path().apply {
        moveTo(left - 6.dp.toPx(), top + height * 0.30f)
        lineTo(left + width * 0.5f, top)
        lineTo(left + width + 6.dp.toPx(), top + height * 0.30f)
        close()
    }
    drawPath(path = roofPath, color = secondary)

    // House walls
    drawRoundRect(
        color = surface,
        topLeft = Offset(left, top + height * 0.28f),
        size = Size(width, height * 0.72f),
        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
    )

    // Warm porch doorway
    val doorW = width * 0.36f
    val doorH = height * 0.48f
    val doorLeft = left + width * 0.16f
    val doorTop = top + height - doorH
    drawRoundRect(
        color = tertiary.copy(alpha = 0.25f + doorGlow * 0.45f),
        topLeft = Offset(doorLeft, doorTop),
        size = Size(doorW, doorH),
        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
    )

    // Customer silhouette welcoming at doorstep when rider arrives
    if (doorGlow > 0.05f) {
        val cx = doorLeft + doorW * 0.5f
        val headY = doorTop + doorH * 0.36f
        drawCircle(
            color = onSurface.copy(alpha = 0.85f * doorGlow.coerceIn(0f, 1f)),
            radius = 5.5.dp.toPx(),
            center = Offset(cx, headY)
        )
        drawLine(
            color = primary.copy(alpha = 0.9f * doorGlow.coerceIn(0f, 1f)),
            start = Offset(cx, headY + 6.dp.toPx()),
            end = Offset(cx, doorTop + doorH - 2.dp.toPx()),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    // Warm home window on right side
    drawRoundRect(
        color = tertiary.copy(alpha = 0.38f),
        topLeft = Offset(left + width * 0.62f, top + height * 0.44f),
        size = Size(width * 0.24f, height * 0.22f),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
}

private fun DrawScope.drawDeliveryRider(
    centerX: Float,
    groundY: Float,
    wheelSpinRad: Float,
    hasParcel: Boolean,
    showSpeedLines: Boolean,
    alpha: Float,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    onSurface: Color
) {
    val wheelR = 9.5.dp.toPx()
    val wheelY = groundY - wheelR
    val backWheelX = centerX - 16.dp.toPx()
    val frontWheelX = centerX + 16.dp.toPx()

    // Motion wind lines behind rider while travelling
    if (showSpeedLines) {
        for (i in 0..2) {
            val ly = groundY - (14 + i * 10).dp.toPx()
            val len = (14 + (i % 2) * 8).dp.toPx()
            drawLine(
                color = primary.copy(alpha = 0.38f * alpha),
                start = Offset(backWheelX - 18.dp.toPx() - len, ly),
                end = Offset(backWheelX - 14.dp.toPx(), ly),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }

    // Wheels + spinning spokes
    listOf(backWheelX, frontWheelX).forEach { wx ->
        drawCircle(
            color = onSurface.copy(alpha = 0.85f * alpha),
            radius = wheelR,
            center = Offset(wx, wheelY),
            style = Stroke(width = 3.dp.toPx())
        )
        val spokeDx = (cos(wheelSpinRad) * (wheelR * 0.6f))
        val spokeDy = (sin(wheelSpinRad) * (wheelR * 0.6f))
        drawLine(
            color = primary.copy(alpha = 0.8f * alpha),
            start = Offset(wx - spokeDx, wheelY - spokeDy),
            end = Offset(wx + spokeDx, wheelY + spokeDy),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    // Scooter chassis
    val chassisPath = Path().apply {
        moveTo(backWheelX - 6.dp.toPx(), wheelY - 6.dp.toPx())
        lineTo(centerX + 4.dp.toPx(), wheelY - 4.dp.toPx())
        lineTo(frontWheelX + 2.dp.toPx(), wheelY - 22.dp.toPx())
        lineTo(frontWheelX + 8.dp.toPx(), wheelY - 22.dp.toPx())
    }
    drawPath(
        path = chassisPath,
        color = primary.copy(alpha = alpha),
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Rear thermal delivery box
    val boxLeft = backWheelX - 10.dp.toPx()
    val boxTop = wheelY - 28.dp.toPx()
    drawRoundRect(
        color = (if (hasParcel) primary else primary.copy(alpha = 0.35f)).copy(alpha = alpha),
        topLeft = Offset(boxLeft, boxTop),
        size = Size(17.dp.toPx(), 16.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
    if (hasParcel) {
        drawCircle(
            color = tertiary.copy(alpha = alpha),
            radius = 2.6.dp.toPx(),
            center = Offset(boxLeft + 8.5.dp.toPx(), boxTop + 8.dp.toPx())
        )
    }

    // Rider torso & helmet
    val riderTorsoX = centerX + 2.dp.toPx()
    drawLine(
        color = secondary.copy(alpha = alpha),
        start = Offset(riderTorsoX - 2.dp.toPx(), wheelY - 12.dp.toPx()),
        end = Offset(riderTorsoX + 4.dp.toPx(), wheelY - 28.dp.toPx()),
        strokeWidth = 6.dp.toPx(),
        cap = StrokeCap.Round
    )
    drawCircle(
        color = primary.copy(alpha = alpha),
        radius = 6.5.dp.toPx(),
        center = Offset(riderTorsoX + 6.dp.toPx(), wheelY - 35.dp.toPx())
    )
    // Helmet visor highlight
    drawLine(
        color = tertiary.copy(alpha = alpha),
        start = Offset(riderTorsoX + 8.dp.toPx(), wheelY - 36.dp.toPx()),
        end = Offset(riderTorsoX + 12.dp.toPx(), wheelY - 34.dp.toPx()),
        strokeWidth = 2.4.dp.toPx(),
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawParcelBag(
    center: Offset,
    primary: Color,
    tertiary: Color
) {
    val bagW = 14.dp.toPx()
    val bagH = 15.dp.toPx()
    drawRoundRect(
        color = primary,
        topLeft = Offset(center.x - bagW / 2f, center.y - bagH / 2f),
        size = Size(bagW, bagH),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
    drawLine(
        color = tertiary,
        start = Offset(center.x - bagW * 0.25f, center.y - bagH / 2f),
        end = Offset(center.x + bagW * 0.25f, center.y - bagH / 2f - 4.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/** Interpolates between two values using a clamped 0-to-1 fraction. */
private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

/** Applies a cubic ease-out curve to animation progress. */
private fun easeOutCubic(t: Float): Float {
    val inv = 1f - t.coerceIn(0f, 1f)
    return 1f - inv * inv * inv
}

/** Applies a cubic curve that eases animation progress in and out. */
private fun easeInOutCubic(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f)) / 2f
}

/** Applies an ease-out curve with a small overshoot at the end. */
private fun easeOutBack(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val xm1 = x - 1f
    return 1f + c3 * xm1 * xm1 * xm1 + c1 * xm1 * xm1
}

/** Calculates a point on a quadratic curve between its start and end points. */
private fun quadraticBezier(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        x = u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
        y = u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y
    )
}
