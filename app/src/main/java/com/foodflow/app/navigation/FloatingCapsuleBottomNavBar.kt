package com.foodflow.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Animated scroll-responsive floating capsule bottom navigation bar for FoodFlow's 5 top-level tabs.
 *
 * Design & behavior:
 * - **Expanded mode (`showLabels == true`)**: Default at top of screen and on upward scroll —
 *   full-height capsule displaying icons, clear labels, and subtle active indicator.
 * - **Collapsed mode (`showLabels == false`)**: Smoothly compacts on downward scroll —
 *   reduced vertical padding, gently tightened horizontal footprint, and smoothly hidden labels
 *   while preserving >= 48dp accessible touch targets on every tab.
 * - Selected item highlights with a subtle animated pill, filled icon, spring scale, and primary tint.
 * - Cart item badge remains completely unclipped and visible whenever `cartCount > 0` in both
 *   selected & unselected states and both expanded & collapsed modes, formatting values > 99 as "99+".
 */
@Composable
fun FloatingCapsuleBottomNavBar(
    destinations: List<TopLevelDestination>,
    activeTabIndex: Int,
    cartCount: Int,
    showLabels: Boolean,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val capsuleVerticalPadding by animateDpAsState(
        targetValue = if (showLabels) 8.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "capsule_nav_vpadding"
    )
    val outerHorizontalPadding by animateDpAsState(
        targetValue = if (showLabels) 16.dp else 26.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "capsule_nav_hmargin"
    )
    val outerVerticalPadding by animateDpAsState(
        targetValue = if (showLabels) 10.dp else 6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "capsule_nav_vmargin"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (showLabels) 30.dp else 26.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "capsule_nav_corner"
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (showLabels) 10.dp else 7.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "capsule_nav_elevation"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 4.dp,
            shadowElevation = shadowElevation,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .testTag("floating_capsule_bottom_nav")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = capsuleVerticalPadding),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEachIndexed { index, dest ->
                    val isSelected = activeTabIndex == index
                    val badgeCount = if (dest.route == CartRoute) cartCount else 0
                    CapsuleNavItem(
                        destination = dest,
                        isSelected = isSelected,
                        showLabel = showLabels,
                        badgeCount = badgeCount,
                        onClick = { onSelectTab(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Animates one destination's selection, label visibility, accessibility semantics, and click target. */
@Composable
private fun CapsuleNavItem(
    destination: TopLevelDestination,
    isSelected: Boolean,
    showLabel: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "capsule_icon_color_${destination.label}"
    )

    val activeIndicatorColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(200),
        label = "capsule_indicator_color_${destination.label}"
    )

    val iconScale by animateFloatAsState(
        targetValue = when {
            isSelected && showLabel -> 1.10f
            isSelected && !showLabel -> 1.14f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "capsule_icon_scale_${destination.label}"
    )

    val itemVerticalPadding by animateDpAsState(
        targetValue = if (showLabel) 4.dp else 2.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "capsule_item_vpad_${destination.label}"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(activeIndicatorColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = MaterialTheme.colorScheme.primary),
                role = Role.Tab,
                onClick = onClick
            )
            .semantics {
                selected = isSelected
                contentDescription = if (badgeCount > 0) {
                    "${destination.label}, $badgeCount items"
                } else {
                    destination.label
                }
            }
            .padding(vertical = itemVerticalPadding, horizontal = 2.dp)
            .testTag(destination.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CapsuleNavIconWithBadge(
            selected = isSelected,
            selectedIcon = destination.selectedIcon,
            unselectedIcon = destination.unselectedIcon,
            badgeCount = badgeCount,
            tint = iconColor,
            scale = iconScale
        )

        AnimatedVisibility(
            visible = showLabel,
            enter = fadeIn(tween(180)) + expandVertically(
                animationSpec = tween(210, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(130)) + shrinkVertically(
                animationSpec = tween(190, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            )
        ) {
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = iconColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("${destination.testTag}_label")
            )
        }
    }
}

/** Chooses the selected icon and overlays the cart count without clipping it. */
@Composable
internal fun CapsuleNavIconWithBadge(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    badgeCount: Int,
    tint: Color,
    scale: Float
) {
    val icon = if (selected) selectedIcon else unselectedIcon
    val iconModifier = Modifier
        .size(23.dp)
        .scale(scale)

    Box(
        modifier = Modifier
            .widthIn(min = 44.dp)
            .defaultMinSize(minHeight = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = iconModifier
        )
        if (badgeCount > 0) {
            val badgeText = formatBadgeCount(badgeCount)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .testTag("cart_badge_count")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Caps large navigation badges at `99+` so the count remains compact. */
fun formatBadgeCount(count: Int): String = if (count > 99) "99+" else count.toString()
