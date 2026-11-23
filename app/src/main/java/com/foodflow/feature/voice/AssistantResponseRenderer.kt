package com.foodflow.feature.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodflow.core.designsystem.components.FoodItemCard
import com.foodflow.core.designsystem.components.RestaurantCard
import com.foodflow.domain.model.FoodItem
import com.foodflow.domain.model.VoiceAssistantError
import com.foodflow.domain.model.VoiceCartSummarySnapshot
import com.foodflow.domain.model.VoiceConversationMessage
import com.foodflow.domain.model.VoiceErrorCategory
import com.foodflow.domain.model.VoicePendingConfirmation
import com.foodflow.domain.model.VoiceResponseType
import com.foodflow.domain.model.VoiceStructuredResponse

/**
 * Dedicated structured response renderer for the FoodFlow Voice Assistant.
 * - Renders restaurant recommendations exclusively with FoodFlow's existing RestaurantCard.
 * - Renders food recommendations exclusively with FoodFlow's existing FoodItemCard & live cart stepper.
 * - Renders cart summaries in a compact FoodFlow summary card.
 * - Renders pure conversational text cleanly without bloated chatbot bubbles or raw JSON.
 * - Strictly prevents duplicate text + card rendering.
 */
@Composable
fun AssistantResponseRenderer(
    message: VoiceConversationMessage,
    cartQuantitiesByFoodId: Map<String, Int>,
    onOpenRestaurant: (String) -> Unit,
    onToggleFavoriteRestaurant: (String) -> Unit,
    onAddFoodToCart: (FoodItem) -> Unit,
    onIncrementFoodInCart: (String) -> Unit,
    onDecrementFoodInCart: (String) -> Unit,
    onOpenCart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val structured = message.structuredResponse

    if (structured != null) {
        StructuredPayloadRenderer(
            structured = structured,
            isFallback = message.isFallback,
            cartQuantitiesByFoodId = cartQuantitiesByFoodId,
            onOpenRestaurant = onOpenRestaurant,
            onToggleFavoriteRestaurant = onToggleFavoriteRestaurant,
            onAddFoodToCart = onAddFoodToCart,
            onIncrementFoodInCart = onIncrementFoodInCart,
            onDecrementFoodInCart = onDecrementFoodInCart,
            onOpenCart = onOpenCart,
            modifier = modifier
        )
    } else if (message.text.isNotBlank()) {
        CompactAssistantTextSurface(
            text = sanitizeVisibleText(message.text),
            isInterrupted = message.isInterrupted,
            isFallback = message.isFallback,
            modifier = modifier
        )
    }
}

/** Renders structured assistant results as relevant restaurant, food, or cart cards. */
@Composable
fun StructuredPayloadRenderer(
    structured: VoiceStructuredResponse,
    isFallback: Boolean,
    cartQuantitiesByFoodId: Map<String, Int>,
    onOpenRestaurant: (String) -> Unit,
    onToggleFavoriteRestaurant: (String) -> Unit,
    onAddFoodToCart: (FoodItem) -> Unit,
    onIncrementFoodInCart: (String) -> Unit,
    onDecrementFoodInCart: (String) -> Unit,
    onOpenCart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("assistant_structured_response_${structured.type.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (structured.type) {
            VoiceResponseType.RESTAURANT_RECOMMENDATIONS -> {
                if (structured.headline.isNotBlank()) {
                    ResponseSectionHeadline(
                        headline = structured.headline,
                        isFallback = isFallback
                    )
                }
                structured.restaurants.forEach { restaurant ->
                    RestaurantCard(
                        restaurant = restaurant,
                        onClick = { onOpenRestaurant(restaurant.id) },
                        onToggleFavorite = { onToggleFavoriteRestaurant(restaurant.id) }
                    )
                }
            }

            VoiceResponseType.FOOD_RECOMMENDATIONS -> {
                if (structured.headline.isNotBlank()) {
                    ResponseSectionHeadline(
                        headline = structured.headline,
                        isFallback = isFallback
                    )
                }
                structured.foodItems.forEach { item ->
                    val restaurantLabel = structured.foodRestaurantNames[item.restaurantId]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!restaurantLabel.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Storefront,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = restaurantLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        FoodItemCard(
                            item = item,
                            quantityInCart = cartQuantitiesByFoodId[item.id] ?: 0,
                            onAddClick = { onAddFoodToCart(item) },
                            onIncrement = { onIncrementFoodInCart(item.id) },
                            onDecrement = { onDecrementFoodInCart(item.id) }
                        )
                    }
                }
            }

            VoiceResponseType.CART_SUMMARY -> {
                val summary = structured.cartSummary
                if (summary != null) {
                    AssistantCartSummaryCard(
                        summary = summary,
                        headline = structured.headline,
                        onOpenCart = onOpenCart
                    )
                } else if (structured.bodyText.isNotBlank()) {
                    CompactAssistantTextSurface(
                        text = sanitizeVisibleText(structured.bodyText),
                        isInterrupted = false,
                        isFallback = isFallback
                    )
                }
            }

            VoiceResponseType.ERROR -> {
                CompactAssistantTextSurface(
                    text = sanitizeVisibleText(structured.bodyText.ifBlank { structured.headline }),
                    isInterrupted = false,
                    isFallback = isFallback,
                    isError = true
                )
            }

            VoiceResponseType.CONVERSATIONAL_TEXT,
            VoiceResponseType.NAVIGATION_CONFIRMATION -> {
                val text = sanitizeVisibleText(structured.bodyText.ifBlank { structured.headline })
                if (text.isNotBlank()) {
                    CompactAssistantTextSurface(
                        text = text,
                        isInterrupted = false,
                        isFallback = isFallback
                    )
                }
            }
        }
    }
}

/** Shows the heading and fallback indicator for a structured assistant response. */
@Composable
private fun ResponseSectionHeadline(
    headline: String,
    isFallback: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag("assistant_response_headline")
        )
    }
}

/** Displays assistant text with visual states for fallback, interruption, or error. */
@Composable
private fun CompactAssistantTextSurface(
    text: String,
    isInterrupted: Boolean,
    isFallback: Boolean,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        },
        border = BorderStroke(
            width = 0.75.dp,
            color = if (isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("assistant_text_response")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (isInterrupted) {
                Text(
                    text = "Stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Summarizes the cart and provides an action to open it. */
@Composable
private fun AssistantCartSummaryCard(
    summary: VoiceCartSummarySnapshot,
    headline: String,
    onOpenCart: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("assistant_cart_summary_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ShoppingBag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = headline.ifBlank { "Your Cart" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "₹${summary.subtotal.toInt()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (summary.items.isEmpty()) {
                Text(
                    text = "Your cart is currently empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                summary.items.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${line.quantity}x ${line.foodName}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "₹${line.lineTotal.toInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Button(
                    onClick = onOpenCart,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_open_cart_button")
                ) {
                    Text("View Cart (${summary.itemCount})")
                }
            }
        }
    }
}

/** Explains an assistant error and presents the available retry or permission action. */
@Composable
fun VoiceErrorBanner(
    error: VoiceAssistantError,
    onRetry: () -> Unit,
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_error_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = error.userMessage,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                error.recoveryHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (error.category == VoiceErrorCategory.PERMISSION_DENIED ||
                error.category == VoiceErrorCategory.PERMISSION_REVOKED
            ) {
                TextButton(
                    onClick = onRequestMicPermission,
                    modifier = Modifier.testTag("voice_grant_mic_button")
                ) {
                    Text("Allow Mic")
                }
            } else if (error.canRetry) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("voice_retry_button")
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

/** Shows an assistant action that needs the user's approval or rejection. */
@Composable
fun VoicePendingConfirmationBanner(
    confirmation: VoicePendingConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_confirmation_banner")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = confirmation.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = confirmation.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_confirm_cancel_button")
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_confirm_accept_button")
                ) {
                    Text("Confirm")
                }
            }
        }
    }
}

/**
 * Strips accidental raw JSON blocks or fenced code markers so raw JSON is never surfaced to users.
 */
private fun sanitizeVisibleText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))
    ) {
        return "Here are the latest details from FoodFlow."
    }
    return trimmed
        .replace(Regex("^\\[Local FoodFlow Assistant\\]\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("```[a-zA-Z]*\\s*[\\s\\S]*?```"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
