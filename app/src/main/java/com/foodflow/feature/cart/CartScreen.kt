package com.foodflow.feature.cart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.AnimatedEmptyCartIllustration
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.FoodImage
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.PriceBreakdownCard
import com.foodflow.core.designsystem.components.QuantityStepper
import com.foodflow.core.designsystem.components.VegNonVegIndicator
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.CartReconciliationNotice
import com.foodflow.domain.model.FoodItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shows the current cart, coupon controls, totals, and checkout action. */
@Composable
fun CartScreen(
    onBrowseRestaurants: () -> Unit,
    onNavigateToCheckout: (String?) -> Unit,
    onScrollAtTopChanged: (Boolean) -> Unit = {},
    bottomBarBottomPadding: Dp = 0.dp,
    viewModel: CartViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val isAtTop by remember(state.isEmpty) {
        derivedStateOf {
            state.isEmpty || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 12)
        }
    }

    LaunchedEffect(listState, state.isEmpty) {
        snapshotFlow { isAtTop }.collectLatest { onScrollAtTopChanged(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is CartEffect.NavigateToCheckout -> onNavigateToCheckout(effect.couponCode)
                is CartEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Your Cart",
                subtitle = state.restaurant?.name,
                actions = {
                    if (!state.isEmpty) {
                        TextButton(
                            onClick = { viewModel.onIntent(CartIntent.ClearCart) },
                            modifier = Modifier.testTag("cart_clear_button")
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!state.isEmpty) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(bottom = bottomBarBottomPadding)
                        .testTag("cart_checkout_bottom_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FoodFlowSpacing.md, vertical = FoodFlowSpacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = state.breakdown.totalPayable.toInr(),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Total payable",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { viewModel.onIntent(CartIntent.ProceedToCheckout) },
                            enabled = !state.isValidating,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("proceed_to_checkout_button")
                        ) {
                            if (state.isValidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    text = "Proceed to Checkout",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (state.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                AnimatedEmptyCartIllustration(
                    onExploreRestaurants = onBrowseRestaurants
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .testTag("cart_items_list"),
                contentPadding = PaddingValues(
                    start = FoodFlowSpacing.md,
                    end = FoodFlowSpacing.md,
                    top = FoodFlowSpacing.md,
                    bottom = FoodFlowSpacing.lg
                ),
                verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm)
            ) {
                if (state.isOffline) {
                    item(key = "offline_banner", contentType = "offline_banner") {
                        OfflineBanner(isOffline = true)
                    }
                }

                if (state.reconciliationNotices.isNotEmpty()) {
                    item(key = "reconciliation_notices", contentType = "reconciliation_notices") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier.padding(FoodFlowSpacing.sm),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Filled.WarningAmber, contentDescription = null)
                                    Text("Menu Updates", style = MaterialTheme.typography.titleSmall)
                                }
                                state.reconciliationNotices.forEach { notice ->
                                    val msg = when (notice) {
                                        is CartReconciliationNotice.PriceChanged ->
                                            "${notice.foodName} price updated from ${notice.oldPrice.toInr()} to ${notice.newPrice.toInr()}."
                                        is CartReconciliationNotice.ItemUnavailable ->
                                            "${notice.foodName} sold out and was removed from your cart."
                                        is CartReconciliationNotice.RestaurantClosed ->
                                            "${notice.restaurantName} is currently closed."
                                    }
                                    Text("• $msg", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { viewModel.onIntent(CartIntent.DismissNotices) }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }

                state.restaurant?.let { restaurant ->
                    item(key = "restaurant_header", contentType = "restaurant_header") {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Storefront,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(restaurant.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "${restaurant.deliveryTimeMin} mins • ${restaurant.address}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                items(
                    items = state.items,
                    key = { it.itemId },
                    contentType = { "cart_item" }
                ) { item ->
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag("cart_item_${item.itemId}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                VegNonVegIndicator(isVeg = item.isVeg)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.foodName, style = MaterialTheme.typography.titleMedium)
                                    if (item.selectedAddons.isNotEmpty()) {
                                        Text(
                                            text = "Add-ons: ${item.selectedAddons.joinToString { it.name }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = item.lineTotal.toInr(),
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                QuantityStepper(
                                    quantity = item.quantity,
                                    onIncrement = {
                                        viewModel.onIntent(CartIntent.IncrementItem(item.itemId, item.quantity))
                                    },
                                    onDecrement = {
                                        viewModel.onIntent(CartIntent.DecrementItem(item.itemId, item.quantity))
                                    },
                                    itemTagPrefix = "cart_${item.itemId}",
                                    itemKey = item.itemId
                                )
                                IconButton(
                                    onClick = { viewModel.onIntent(CartIntent.RemoveItem(item.itemId)) },
                                    modifier = Modifier.testTag("cart_remove_${item.itemId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Remove item",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            CartCookingNotesField(
                                itemId = item.itemId,
                                value = state.cookingInstructionsFor(item),
                                onValueChange = { updated ->
                                    viewModel.onIntent(CartIntent.UpdateInstructions(item.itemId, updated))
                                }
                            )
                        }
                    }
                }

                if (state.suggestedAddons.isNotEmpty()) {
                    item(key = "suggested_addons_section", contentType = "suggested_addons") {
                        SuggestedAddonsCarousel(
                            addons = state.suggestedAddons,
                            onAddClick = { food ->
                                viewModel.onIntent(CartIntent.AddSuggestedItem(food))
                            }
                        )
                    }
                }

                item(key = "coupon_section", contentType = "coupon_section") {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(FoodFlowSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocalOffer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text("Offers & Coupons", style = MaterialTheme.typography.titleMedium)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.couponInput,
                                    onValueChange = { viewModel.onIntent(CartIntent.CouponInputChanged(it)) },
                                    placeholder = { Text("Enter promo code") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("coupon_code_input")
                                )
                                Button(
                                    onClick = { viewModel.onIntent(CartIntent.ApplyCouponCode(state.couponInput)) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("apply_coupon_button")
                                ) {
                                    Text("Apply")
                                }
                            }
                            state.couponError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("coupon_error_text")
                                )
                            }
                            state.appliedCoupon?.let { applied ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${applied.code} applied · You save ${state.breakdown.discount.toInr()}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    TextButton(onClick = { viewModel.onIntent(CartIntent.RemoveCoupon) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.coupons, key = { it.code }) { coupon ->
                                    AssistChip(
                                        onClick = { viewModel.onIntent(CartIntent.ApplyCouponCode(coupon.code)) },
                                        label = { Text("${coupon.code} · ${coupon.title}") },
                                        modifier = Modifier.testTag("coupon_chip_${coupon.code}")
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "bill_breakdown", contentType = "bill_breakdown") {
                    PriceBreakdownCard(breakdown = state.breakdown)
                }

                item(key = "bottom_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Provides an editable cooking-note field for a cart item. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CartCookingNotesField(
    itemId: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = { updated ->
            onValueChange(updated)
            coroutineScope.launch {
                bringIntoViewRequester.bringIntoView()
            }
        },
        placeholder = {
            Text(
                text = "Cooking notes (e.g., less spicy, no onion)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        singleLine = false,
        minLines = 1,
        maxLines = 4,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    coroutineScope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
            .testTag("cart_cooking_notes_$itemId")
    )
}

/** Shows suggested menu items that can be added to the cart. */
@Composable
private fun SuggestedAddonsCarousel(
    addons: List<FoodItem>,
    onAddClick: (FoodItem) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cart_suggested_addons_section")
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "Complete Your Meal",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Popular pairings from this kitchen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(addons, key = { it.id }) { food ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.width(156.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FoodImage(
                                imageKey = food.imageKey,
                                contentDescription = food.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(78.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                VegNonVegIndicator(isVeg = food.isVeg, size = 13.dp)
                                Text(
                                    text = food.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = food.price.toInr(),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                FilledTonalButton(
                                    onClick = { onAddClick(food) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("cart_addon_${food.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text("Add", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
