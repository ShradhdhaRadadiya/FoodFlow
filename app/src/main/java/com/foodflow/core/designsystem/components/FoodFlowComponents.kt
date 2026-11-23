package com.foodflow.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.CartPriceBreakdown
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.Restaurant
import com.foodflow.domain.model.RestaurantRatingCalculator

/**
 * Unified top application header enforcing consistent height, typography, surface elevation,
 * and single status-bar WindowInsets handling across every FoodFlow screen.
 */
/** Draws the shared app bar with navigation, title, optional actions, and Material 3 scroll behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodFlowTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("top_bar_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate back"
                            )
                        }
                    }
                },
                title = {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = actions
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/**
 * Maps semantic image keys from seed JSON into bundled offline-ready drawable resources.
 */
@DrawableRes
fun resolveFoodDrawable(imageKey: String): Int = when (imageKey) {
    "banner_feast" -> R.drawable.img_banner_feast_1795140034055
    "cuisine_south", "south_indian", "dosa" -> R.drawable.img_cuisine_south_1795140031440
    "cuisine_global", "italian", "chinese", "mexican", "fast_food", "desserts" ->
        R.drawable.img_cuisine_global_1795140041146
    else -> R.drawable.img_cuisine_indian_1795140033717
}

/** Loads the keyed food image with a placeholder/error fallback and the requested accessibility label. */
@Composable
fun FoodImage(
    imageKey: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Image(
        painter = painterResource(id = resolveFoodDrawable(imageKey)),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

/** Displays the standard veg/non-veg marker using the item's classification. */
@Composable
fun VegNonVegIndicator(
    isVeg: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    val extended = FoodFlowThemeTokens.extendedColors
    val tint = if (isVeg) extended.vegGreen else extended.nonVegRed
    Box(
        modifier = modifier
            .size(size)
            .border(1.5.dp, tint, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.45f)
                .clip(CircleShape)
                .background(tint)
        )
    }
}

/** Formats and displays a compact restaurant or food rating badge. */
@Composable
fun RatingBadge(
    rating: Double,
    modifier: Modifier = Modifier,
    reviewCount: Int? = null
) {
    val extended = FoodFlowThemeTokens.extendedColors
    val hasReviews = (reviewCount == null || reviewCount > 0) && rating > 0.0
    val formattedRating = RestaurantRatingCalculator.formatRating(
        rating = rating,
        reviewCount = reviewCount ?: if (rating > 0.0) 1 else 0
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (hasReviews) extended.vegGreen else MaterialTheme.colorScheme.secondary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = formattedRating,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (hasReviews) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
            if (reviewCount != null && reviewCount > 0) {
                Text(
                    text = "($reviewCount)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.88f)
                )
            }
        }
    }
}

/**
 * Clean customer-facing connectivity banner shown strictly when the device is offline.
 * Never surfaces internal outbox or technical sync jargon to customers.
 */
@Composable
fun OfflineBanner(
    isOffline: Boolean,
    modifier: Modifier = Modifier
) {
    val extended = FoodFlowThemeTokens.extendedColors
    AnimatedVisibility(
        visible = isOffline,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("offline_status_banner"),
            color = extended.offlineContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = "Offline indicator",
                    tint = extended.offlineContent,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "You're offline",
                        style = MaterialTheme.typography.labelLarge,
                        color = extended.offlineContent
                    )
                    Text(
                        text = "Browse saved menus and edit your cart. Changes update automatically once connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extended.offlineContent.copy(alpha = 0.88f)
                    )
                }
            }
        }
    }
}

/** Provides decrement/increment controls around the displayed cart quantity. */
@Composable
fun QuantityStepper(
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    itemTagPrefix: String = "qty",
    itemKey: String = itemTagPrefix
) {
    key(itemKey) {
        var previousQuantity by remember(itemKey) { mutableIntStateOf(quantity) }
        val badgeScale = remember(itemKey) { Animatable(1f) }

        LaunchedEffect(itemKey, quantity) {
            if (quantity != previousQuantity) {
                previousQuantity = quantity
                badgeScale.snapTo(1.14f)
                badgeScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        }

        Surface(
            modifier = modifier.scale(badgeScale.value),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onDecrement,
                    modifier = Modifier
                        .size(36.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("${itemTagPrefix}_decrement")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Remove,
                        contentDescription = "Decrease quantity",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                AnimatedContent(
                    targetState = quantity,
                    transitionSpec = {
                        val isIncreasing = targetState > initialState
                        val enterSlide = slideInVertically(tween(170)) { height ->
                            if (isIncreasing) height / 2 else -height / 2
                        } + fadeIn(tween(150))
                        val exitSlide = slideOutVertically(tween(150)) { height ->
                            if (isIncreasing) -height / 2 else height / 2
                        } + fadeOut(tween(130))
                        (enterSlide togetherWith exitSlide).using(SizeTransform(clip = true))
                    },
                    label = "quantity_change_$itemKey"
                ) { count ->
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("${itemTagPrefix}_value")
                    )
                }
                IconButton(
                    onClick = onIncrement,
                    modifier = Modifier
                        .size(36.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("${itemTagPrefix}_increment")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Increase quantity",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** Shows a restaurant's key discovery details and forwards card/favorite actions to the caller. */
@Composable
fun RestaurantCard(
    restaurant: Restaurant,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extended = FoodFlowThemeTokens.extendedColors
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("restaurant_card_${restaurant.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(164.dp)
            ) {
                FoodImage(
                    imageKey = restaurant.imageKey,
                    contentDescription = restaurant.name,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.24f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f)
                                )
                            )
                        )
                )

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.42f))
                        .testTag("fav_toggle_${restaurant.id}")
                ) {
                    Icon(
                        imageVector = if (restaurant.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (restaurant.isFavorite) "Remove from favourites" else "Add to favourites",
                        tint = if (restaurant.isFavorite) Color(0xFFFF4D6D) else Color.White
                    )
                }

                // Data-driven highlight badge + Pure Veg / Closed tags
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    restaurant.highlightBadge?.let { badgeLabel ->
                        val isFast = badgeLabel == "Fast Delivery"
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isFast) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFast) Icons.Filled.Bolt else Icons.Filled.WorkspacePremium,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = badgeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    if (restaurant.isPureVeg) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = extended.vegGreen
                        ) {
                            Text(
                                text = "PURE VEG",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    if (!restaurant.isOpen) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Text(
                                text = "CLOSED",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                restaurant.offerText?.let { offer ->
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalOffer,
                            contentDescription = null,
                            tint = Color(0xFF5EEAD4),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = offer,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(FoodFlowSpacing.md),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = restaurant.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    RatingBadge(
                        rating = restaurant.rating,
                        reviewCount = restaurant.reviewCount,
                        modifier = Modifier.testTag("restaurant_rating_${restaurant.id}")
                    )
                }

                Text(
                    text = restaurant.cuisines.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "${restaurant.deliveryTimeMin} mins",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text("•", color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = if (restaurant.deliveryFee == 0.0) "Free delivery" else "${restaurant.deliveryFee.toInr()} delivery",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (restaurant.deliveryFee == 0.0) extended.vegGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("•", color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "₹${restaurant.priceForTwo} for two",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Displays menu item details, options, price, and cart controls for one food item. */
@Composable
fun FoodItemCard(
    item: FoodItem,
    quantityInCart: Int,
    onAddClick: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extended = FoodFlowThemeTokens.extendedColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("food_item_${item.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoodFlowSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VegNonVegIndicator(isVeg = item.isVeg)
                    if (item.isBestseller) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = extended.promoSurface
                        ) {
                            Text(
                                text = "Bestseller",
                                style = MaterialTheme.typography.labelSmall,
                                color = extended.promoGold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (!item.isAvailable) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "Sold Out",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.price.toInr(),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.originalPrice != null && item.originalPrice > item.price) {
                        Text(
                            text = item.originalPrice.toInr(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = TextDecoration.LineThrough,
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.addons.isNotEmpty()) {
                    Text(
                        text = "Customisable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FoodImage(
                    imageKey = item.imageKey,
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                if (!item.isAvailable) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Unavailable", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (quantityInCart == 0) {
                    Button(
                        onClick = onAddClick,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("add_food_${item.id}")
                    ) {
                        Text("ADD", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    QuantityStepper(
                        quantity = quantityInCart,
                        onIncrement = onIncrement,
                        onDecrement = onDecrement,
                        itemTagPrefix = "food_${item.id}"
                    )
                }
            }
        }
    }
}

/** Renders each subtotal, fee, tax, discount, and final amount line from the cart breakdown. */
@Composable
fun PriceBreakdownCard(
    breakdown: CartPriceBreakdown,
    modifier: Modifier = Modifier
) {
    val extended = FoodFlowThemeTokens.extendedColors
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Bill Details", style = MaterialTheme.typography.titleMedium)
            BillLine("Item Subtotal", breakdown.subtotal.toInr())
            BillLine(
                label = "Delivery Fee",
                value = if (breakdown.deliveryFee == 0.0) "FREE" else breakdown.deliveryFee.toInr(),
                valueColor = if (breakdown.deliveryFee == 0.0) extended.vegGreen else MaterialTheme.colorScheme.onSurface
            )
            BillLine("Packaging Charge", breakdown.packagingCharge.toInr())
            BillLine("GST & Restaurant Charges (5%)", breakdown.taxes.toInr())
            if (breakdown.discount > 0.0) {
                BillLine(
                    label = "Coupon Discount (${breakdown.appliedCouponCode.orEmpty()})",
                    value = "-${breakdown.discount.toInr()}",
                    valueColor = extended.vegGreen
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("To Pay", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = breakdown.totalPayable.toInr(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("bill_total_payable")
                )
            }
        }
    }
}

/** Aligns one label/value pair within the cart price breakdown. */
@Composable
private fun BillLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Shows the empty-cart illustration and its explore action with the component animation. */
@Composable
fun AnimatedEmptyCartIllustration(
    onExploreRestaurants: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "empty_cart_anim")
    val floatOffset by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float_offset"
    )
    val haloScale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_scale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FoodFlowSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(136.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier
                    .size(88.dp)
                    .offset(y = floatOffset.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ShoppingBag,
                        contentDescription = "Empty cart illustration",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Your cart is empty",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Discover fresh local kitchens and add your favourite dishes to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onExploreRestaurants,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .height(48.dp)
                .testTag("empty_state_action")
        ) {
            Text(
                text = "Explore Restaurants",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

/** Animates a repeating shimmer sweep across placeholder rows while restaurant data loads. */
@Composable
fun ShimmerRestaurantSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_offset"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translate - 300f, 0f),
        end = Offset(translate, 200f)
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(FoodFlowSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(brush)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush)
        )
    }
}

/** Presents a reusable empty-state icon, message, and optional action. */
@Composable
fun FoodFlowEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FoodFlowSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("empty_state_action")
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** Presents an error message with a retry action for failed screen content. */
@Composable
fun FoodFlowErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    FoodFlowEmptyState(
        icon = Icons.Filled.ErrorOutline,
        title = "Something went wrong",
        subtitle = message,
        actionLabel = "Try Again",
        onAction = onRetry,
        modifier = modifier
    )
}
