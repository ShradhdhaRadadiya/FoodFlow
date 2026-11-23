package com.foodflow.core.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily

/**
 * Reusable animated floating Cart Preview Bar.
 *
 * - Enters smoothly from the bottom when the first item is added (`itemCount > 0`).
 * - Updates item count & total price in place with subtle numeric transitions without recreating the bar.
 * - Exits smoothly downward when cart becomes empty (`itemCount == 0`).
 * - Uses FoodFlow theme tokens, rounded corners, elevation, and accessible 48dp touch height.
 */
@Composable
fun FloatingCartPreviewBar(
    itemCount: Int,
    totalAmount: Double,
    onViewCart: () -> Unit,
    modifier: Modifier = Modifier,
    restaurantName: String = "",
    containerTestTag: String = "floating_cart_preview_bar",
    ctaTestTag: String = "view_cart_cta"
) {
    AnimatedVisibility(
        visible = itemCount > 0,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight + 48 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight + 48 },
            animationSpec = tween(220, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(180)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = onViewCart,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 8.dp,
                tonalElevation = 4.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .defaultMinSize(minHeight = 56.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "View Cart, $itemCount items, total ${totalAmount.toInr()}"
                    }
                    .testTag(containerTestTag)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.ShoppingBag,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AnimatedContent(
                                    targetState = itemCount,
                                    transitionSpec = {
                                        val enteringFromBottom = targetState > initialState
                                        (slideInVertically(tween(200)) { if (enteringFromBottom) it / 2 else -it / 2 } + fadeIn(tween(180)))
                                            .togetherWith(
                                                slideOutVertically(tween(180)) { if (enteringFromBottom) -it / 2 else it / 2 } + fadeOut(tween(140))
                                            )
                                            .using(SizeTransform(clip = false))
                                    },
                                    label = "cart_preview_count"
                                ) { count ->
                                    Text(
                                        text = if (count == 1) "1 item" else "$count items",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.testTag("cart_preview_item_count")
                                    )
                                }

                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
                                )

                                AnimatedContent(
                                    targetState = totalAmount.toInr(),
                                    transitionSpec = {
                                        (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 3 })
                                            .togetherWith(fadeOut(tween(140)))
                                            .using(SizeTransform(clip = false))
                                    },
                                    label = "cart_preview_price"
                                ) { formattedTotal ->
                                    Text(
                                        text = formattedTotal,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.testTag("cart_preview_total_amount")
                                    )
                                }
                            }

                            if (restaurantName.isNotBlank()) {
                                Text(
                                    text = restaurantName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                        modifier = Modifier.testTag(ctaTestTag)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "View Cart",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
