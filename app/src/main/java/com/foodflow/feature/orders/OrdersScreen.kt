package com.foodflow.feature.orders

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.common.toInr
import com.foodflow.core.designsystem.components.FoodFlowEmptyState
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.FoodImage
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.FoodFlowThemeTokens
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.core.invoice.InvoiceIntentHelper
import com.foodflow.domain.model.InvoiceDocument
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.model.PaymentStatus
import com.foodflow.feature.scheduled.ScheduledOrderFailureDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows order history and scheduled orders with their available actions. */
@Composable
fun OrdersScreen(
    onTrackOrder: (String) -> Unit,
    onOpenCart: () -> Unit,
    onReportIssue: (String) -> Unit,
    onOpenScheduledOrders: () -> Unit = {},
    onOpenRestaurant: (String) -> Unit = {},
    onScrollAtTopChanged: (Boolean) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = FoodFlowSpacing.xl,
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val tabs = OrdersTab.entries

    val pagerState = rememberPagerState(
        initialPage = tabs.indexOf(state.selectedTab).coerceAtLeast(0),
        pageCount = { tabs.size }
    )

    // Preserve independent scroll state for each order tab
    val activeListState = rememberLazyListState()
    val scheduledListState = rememberLazyListState()
    val completedListState = rememberLazyListState()
    val cancelledListState = rememberLazyListState()

    LaunchedEffect(
        pagerState.currentPage,
        activeListState,
        scheduledListState,
        completedListState,
        cancelledListState
    ) {
        snapshotFlow {
            val activeState = when (tabs.getOrElse(pagerState.currentPage) { OrdersTab.ACTIVE }) {
                OrdersTab.ACTIVE -> activeListState
                OrdersTab.SCHEDULED -> scheduledListState
                OrdersTab.COMPLETED -> completedListState
                OrdersTab.CANCELLED -> cancelledListState
            }
            activeState.firstVisibleItemIndex == 0 && activeState.firstVisibleItemScrollOffset <= 12
        }.distinctUntilChanged().collectLatest { atTop ->
            onScrollAtTopChanged(atTop)
        }
    }

    // Sync ViewModel tab changes -> Pager (e.g. initial auto-selection or tap)
    LaunchedEffect(state.selectedTab) {
        val targetIndex = tabs.indexOf(state.selectedTab).coerceAtLeast(0)
        if (pagerState.currentPage != targetIndex) {
            pagerState.animateScrollToPage(targetIndex)
        }
    }

    // Sync user swipes on Pager -> ViewModel
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collectLatest { page ->
                val tab = tabs.getOrNull(page) ?: return@collectLatest
                if (tab != state.selectedTab) {
                    viewModel.onIntent(OrdersIntent.SelectTab(tab))
                }
            }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                OrdersEffect.NavigateToCart -> onOpenCart()
                is OrdersEffect.NavigateToRestaurant -> onOpenRestaurant(effect.restaurantId)
                is OrdersEffect.NavigateToTrackOrder -> onTrackOrder(effect.orderId)
                is OrdersEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Orders",
                subtitle = "${state.totalOrderAndScheduleCount} total orders",
                actions = {
                    TextButton(
                        onClick = onOpenScheduledOrders,
                        modifier = Modifier.testTag("orders_open_scheduled_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = "Scheduled orders",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (state.upcomingScheduledCount > 0) {
                                "Scheduled (${state.upcomingScheduledCount})"
                            } else {
                                "Scheduled"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedOrdersTabBar(
                selectedTab = tabs.getOrElse(pagerState.currentPage) { state.selectedTab },
                activeCount = state.activeOrders.size,
                scheduledCount = state.scheduledOrders.size,
                completedCount = state.completedOrders.size,
                cancelledCount = state.cancelledOrders.size,
                onSelectTab = { tab ->
                    // The selected-tab observer performs the pager animation after state updates.
                    viewModel.onIntent(OrdersIntent.SelectTab(tab))
                },
                modifier = Modifier.padding(horizontal = FoodFlowSpacing.md, vertical = FoodFlowSpacing.sm)
            )

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { page -> tabs[page].name },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("orders_horizontal_pager")
            ) { page ->
                val tab = tabs[page]
                val listState = when (tab) {
                    OrdersTab.ACTIVE -> activeListState
                    OrdersTab.SCHEDULED -> scheduledListState
                    OrdersTab.COMPLETED -> completedListState
                    OrdersTab.CANCELLED -> cancelledListState
                }

                FoodFlowPullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.onIntent(OrdersIntent.Refresh) },
                    testTag = "orders_pull_to_refresh_${tab.name.lowercase()}",
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("orders_list_${tab.name.lowercase()}"),
                    contentPadding = PaddingValues(
                        start = FoodFlowSpacing.md,
                        end = FoodFlowSpacing.md,
                        top = 4.dp,
                        bottom = bottomContentPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm)
                ) {
                    if (state.isOffline) {
                        item(key = "offline_banner_${tab.name}", contentType = "offline_banner") {
                            OfflineBanner(isOffline = true)
                        }
                    }

                    if (tab == OrdersTab.SCHEDULED) {
                        if (state.scheduledOrders.isEmpty()) {
                            item(key = "empty_tab_scheduled", contentType = "empty_state") {
                                FoodFlowEmptyState(
                                    icon = Icons.Filled.Schedule,
                                    title = "No scheduled orders",
                                    subtitle = "Select Schedule for Later at checkout to schedule when your order should be placed with the restaurant."
                                )
                            }
                        } else {
                            items(
                                items = state.scheduledOrders,
                                key = { "schedule_${it.id}" },
                                contentType = { "scheduled_order_card" }
                            ) { schedule ->
                                ScheduledOrderHistoryCard(
                                    schedule = schedule,
                                    imageKey = state.restaurantImageById[schedule.restaurantId] ?: "cuisine_indian",
                                    placementSentence = viewModel.formatScheduleSentence(schedule),
                                    recurrenceSummary = viewModel.formatRecurrenceSummary(schedule.recurrenceRule),
                                    isExecuting = state.executingScheduleId == schedule.id,
                                    onReschedule = { viewModel.onIntent(OrdersIntent.OpenReschedule(schedule)) },
                                    onCancel = { viewModel.onIntent(OrdersIntent.CancelScheduledOrder(schedule.id)) },
                                    onViewDetails = { viewModel.onIntent(OrdersIntent.OpenScheduledDetails(schedule)) },
                                    onPlaceNowDemo = { viewModel.onIntent(OrdersIntent.PlaceScheduledOrderNow(schedule.id)) },
                                    onTrackOrder = onTrackOrder
                                )
                            }
                        }
                    } else {
                        val list = when (tab) {
                            OrdersTab.ACTIVE -> state.activeOrders
                            OrdersTab.COMPLETED -> state.completedOrders
                            OrdersTab.CANCELLED -> state.cancelledOrders
                            OrdersTab.SCHEDULED -> emptyList()
                        }

                        if (list.isEmpty()) {
                            item(key = "empty_tab_${tab.name}", contentType = "empty_state") {
                                val (title, subtitle) = when (tab) {
                                    OrdersTab.ACTIVE -> "No active orders" to "When you place an order, you can track its live progress here."
                                    OrdersTab.COMPLETED -> "No completed orders yet" to "Delivered orders, ratings, and downloadable PDF invoices will appear here."
                                    OrdersTab.CANCELLED -> "No cancelled orders" to "Any cancelled orders and refund updates will be listed here."
                                    OrdersTab.SCHEDULED -> "No scheduled orders" to ""
                                }
                                FoodFlowEmptyState(
                                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                                    title = title,
                                    subtitle = subtitle
                                )
                            }
                        } else {
                            items(
                                items = list,
                                key = { it.id },
                                contentType = { "order_card" }
                            ) { order ->
                                OrderHistoryCard(
                                    order = order,
                                    imageKey = state.restaurantImageById[order.restaurantId] ?: "cuisine_indian",
                                    isGeneratingInvoice = state.generatingInvoiceOrderId == order.id,
                                    onTrack = { onTrackOrder(order.id) },
                                    onViewDetails = { viewModel.onIntent(OrdersIntent.OpenOrderDetails(order)) },
                                    onReorder = { viewModel.onIntent(OrdersIntent.Reorder(order.id)) },
                                    onDownloadInvoice = { viewModel.onIntent(OrdersIntent.DownloadInvoice(order)) },
                                    onRate = { viewModel.onIntent(OrdersIntent.OpenRatingDialog(order.id)) },
                                    onHelp = { onReportIssue(order.id) }
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    state.detailsOrder?.let { order ->
        OrderDetailsDialog(
            order = order,
            isGeneratingInvoice = state.generatingInvoiceOrderId == order.id,
            onDismiss = { viewModel.onIntent(OrdersIntent.DismissOrderDetails) },
            onTrack = {
                viewModel.onIntent(OrdersIntent.DismissOrderDetails)
                onTrackOrder(order.id)
            },
            onDownloadInvoice = { viewModel.onIntent(OrdersIntent.DownloadInvoice(order)) },
            onHelp = {
                viewModel.onIntent(OrdersIntent.DismissOrderDetails)
                onReportIssue(order.id)
            }
        )
    }

    state.readyInvoice?.let { doc ->
        InvoiceReadyDialog(
            document = doc,
            onOpen = {
                val opened = InvoiceIntentHelper.openInvoice(context, Uri.parse(doc.uriString))
                if (!opened) {
                    viewModel.onIntent(OrdersIntent.DismissInvoiceReady)
                }
            },
            onShare = {
                InvoiceIntentHelper.shareInvoice(context, doc)
            },
            onDismiss = { viewModel.onIntent(OrdersIntent.DismissInvoiceReady) }
        )
    }

    state.ratingOrderId?.let { orderId ->
        RateOrderDialog(
            initialStars = state.ratingInitialStars,
            initialComment = state.ratingInitialComment,
            isEditing = state.isEditingRating,
            isSubmitting = state.isSubmittingRating,
            onDismiss = { viewModel.onIntent(OrdersIntent.DismissRatingDialog) },
            onSubmit = { rating, comment ->
                viewModel.onIntent(OrdersIntent.SubmitRating(orderId, rating, comment))
            }
        )
    }

    state.detailsScheduledOrder?.let { schedule ->
        ScheduledOrderHistoryDetailsDialog(
            schedule = schedule,
            placementSentence = viewModel.formatScheduleSentence(schedule),
            recurrenceSummary = viewModel.formatRecurrenceSummary(schedule.recurrenceRule),
            onReschedule = {
                viewModel.onIntent(OrdersIntent.DismissScheduledDetails)
                viewModel.onIntent(OrdersIntent.OpenReschedule(schedule))
            },
            onCancel = {
                viewModel.onIntent(OrdersIntent.DismissScheduledDetails)
                viewModel.onIntent(OrdersIntent.CancelScheduledOrder(schedule.id))
            },
            onTrackOrder = { generatedId ->
                viewModel.onIntent(OrdersIntent.DismissScheduledDetails)
                onTrackOrder(generatedId)
            },
            onDismiss = { viewModel.onIntent(OrdersIntent.DismissScheduledDetails) }
        )
    }

    state.rescheduleTarget?.let { target ->
        OrdersRescheduleDialogs(
            schedule = target,
            draftUtcMs = state.rescheduleDraftUtcMs,
            showDatePicker = state.showRescheduleDatePicker,
            showTimePicker = state.showRescheduleTimePicker,
            onPickDate = { viewModel.onIntent(OrdersIntent.OpenRescheduleDatePicker) },
            onPickTime = { viewModel.onIntent(OrdersIntent.OpenRescheduleTimePicker) },
            onConfirmDate = { viewModel.onIntent(OrdersIntent.ConfirmRescheduleDate(it)) },
            onDismissDate = { viewModel.onIntent(OrdersIntent.DismissRescheduleDatePicker) },
            onConfirmTime = { hour, minute ->
                viewModel.onIntent(OrdersIntent.ConfirmRescheduleTime(hour, minute))
            },
            onDismissTime = { viewModel.onIntent(OrdersIntent.DismissRescheduleTimePicker) },
            onSave = { viewModel.onIntent(OrdersIntent.SaveReschedule) },
            onDismiss = { viewModel.onIntent(OrdersIntent.DismissReschedule) }
        )
    }

    state.activeFailurePopup?.let { popup ->
        ScheduledOrderFailureDialog(
            popup = popup,
            onPrimaryAction = { viewModel.onIntent(OrdersIntent.HandleFailurePrimaryAction(popup)) },
            onCancelOrder = { viewModel.onIntent(OrdersIntent.HandleFailureCancelOrder(popup)) },
            onDismiss = { viewModel.onIntent(OrdersIntent.AcknowledgeFailurePopup(popup.scheduleId)) }
        )
    }
}

/** Shows order-history tabs and their item counts. */
@Composable
private fun AnimatedOrdersTabBar(
    selectedTab: OrdersTab,
    activeCount: Int,
    scheduledCount: Int,
    completedCount: Int,
    cancelledCount: Int,
    onSelectTab: (OrdersTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = OrdersTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("orders_tab_bar"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            val tabWidth = maxWidth / tabs.size
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "orders_tab_indicator"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surface)
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val count = when (tab) {
                        OrdersTab.ACTIVE -> activeCount
                        OrdersTab.SCHEDULED -> scheduledCount
                        OrdersTab.COMPLETED -> completedCount
                        OrdersTab.CANCELLED -> cancelledCount
                    }
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(200),
                        label = "tab_text_${tab.name}"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .clickable { onSelectTab(tab) }
                            .padding(horizontal = 2.dp)
                            .testTag("orders_tab_${tab.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (count > 0) "${tab.label} ($count)" else tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Displays an order summary with actions available for its current status. */
@Composable
private fun OrderHistoryCard(
    order: Order,
    imageKey: String,
    isGeneratingInvoice: Boolean,
    onTrack: () -> Unit,
    onViewDetails: () -> Unit,
    onReorder: () -> Unit,
    onDownloadInvoice: () -> Unit,
    onRate: () -> Unit,
    onHelp: () -> Unit
) {
    val extended = FoodFlowThemeTokens.extendedColors
    val statusColor = when (order.orderStatus) {
        OrderStatus.DELIVERED -> extended.vegGreen
        OrderStatus.CANCELLED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val dateFormatted = remember(order.createdAtMs) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(order.createdAtMs))
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetails)
            .testTag("order_card_${order.id}")
    ) {
        Column(
            modifier = Modifier.padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FoodImage(
                    imageKey = imageKey,
                    contentDescription = order.restaurantName,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = order.restaurantName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = order.orderStatus.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (order.orderStatus.isActive) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Arriving in ~${order.estimatedDeliveryMinutes} mins • ${order.orderStatus.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (order.orderStatus == OrderStatus.CANCELLED) {
                val refundText = when (order.paymentStatus) {
                    PaymentStatus.REFUNDED -> "Refund credited to original payment method"
                    PaymentStatus.REFUND_PENDING -> "Refund initiated • Usually settles within 3-5 business days"
                    else -> "Order cancelled before delivery • No charges applied"
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = refundText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = order.items.joinToString(", ") { "${it.quantity} × ${it.foodName}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = order.totalPayable.toInr(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                )
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    order.ratingGiven?.let { stars ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "$stars/5",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                    Text(
                        text = "${order.paymentMethod.displayName} • ${order.paymentStatus.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compactDeliveredActions =
                    order.orderStatus == OrderStatus.DELIVERED && maxWidth < 390.dp

                if (compactDeliveredActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onReorder,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.weight(1f).testTag("reorder_${order.id}")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reorder", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = onDownloadInvoice,
                                enabled = !isGeneratingInvoice,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.weight(1f).testTag("download_invoice_${order.id}")
                            ) {
                                if (isGeneratingInvoice) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Invoice", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onRate,
                                modifier = Modifier.testTag("rate_order_${order.id}")
                            ) {
                                Text(if (order.ratingGiven == null) "Rate" else "Edit")
                            }
                            IconButton(onClick = onHelp) {
                                Icon(
                                    imageVector = Icons.Filled.SupportAgent,
                                    contentDescription = "Get help for order ${order.id}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            order.orderStatus.isActive -> {
                                Button(
                                    onClick = onTrack,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("track_order_${order.id}")
                                ) {
                                    Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Track Order")
                                }
                                OutlinedButton(
                                    onClick = onViewDetails,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("details_order_${order.id}")
                                ) {
                                    Text("Details")
                                }
                            }
                            order.orderStatus == OrderStatus.DELIVERED -> {
                                Button(
                                    onClick = onReorder,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("reorder_${order.id}")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reorder", maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    onClick = onDownloadInvoice,
                                    enabled = !isGeneratingInvoice,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("download_invoice_${order.id}")
                                ) {
                                    if (isGeneratingInvoice) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Invoice", maxLines = 1, softWrap = false)
                                    }
                                }
                                TextButton(
                                    onClick = onRate,
                                    modifier = Modifier.testTag("rate_order_${order.id}")
                                ) {
                                    Text(if (order.ratingGiven == null) "Rate" else "Edit")
                                }
                            }
                            else -> {
                                OutlinedButton(
                                    onClick = onViewDetails,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("details_order_${order.id}")
                                ) {
                                    Text("View Details")
                                }
                                Button(
                                    onClick = onReorder,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("reorder_${order.id}")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reorder")
                                }
                            }
                        }
                        IconButton(onClick = onHelp) {
                            Icon(
                                imageVector = Icons.Filled.SupportAgent,
                                contentDescription = "Get help for order ${order.id}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shows full order details and the available tracking or support actions. */
@Composable
private fun OrderDetailsDialog(
    order: Order,
    isGeneratingInvoice: Boolean,
    onDismiss: () -> Unit,
    onTrack: () -> Unit,
    onDownloadInvoice: () -> Unit,
    onHelp: () -> Unit
) {
    val dateFormatted = remember(order.createdAtMs) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(order.createdAtMs))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(order.restaurantName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = dateFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delivery Address", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = order.deliveryAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                Text("Items", style = MaterialTheme.typography.labelLarge)
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${item.quantity} × ${item.foodName}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = (item.unitPrice * item.quantity).toInr(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                        )
                    }
                }
                HorizontalDivider()
                OrderDetailRow("Item Subtotal", order.subtotal.toInr())
                OrderDetailRow("Delivery Fee", if (order.deliveryFee == 0.0) "FREE" else order.deliveryFee.toInr())
                OrderDetailRow("Packaging", order.packagingCharge.toInr())
                OrderDetailRow("Taxes (GST)", order.taxes.toInr())
                if (order.discount > 0.0) {
                    OrderDetailRow("Discount", "-${order.discount.toInr()}")
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Paid", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = order.totalPayable.toInr(),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Payment: ${order.paymentMethod.displayName} (${order.paymentStatus.label})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            when {
                order.orderStatus.isActive -> Button(onClick = onTrack) { Text("Track Order") }
                order.orderStatus == OrderStatus.DELIVERED -> Button(
                    onClick = onDownloadInvoice,
                    enabled = !isGeneratingInvoice
                ) {
                    Text(if (isGeneratingInvoice) "Generating..." else "Download Invoice")
                }
                else -> Button(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            TextButton(onClick = onHelp) { Text("Need Help") }
        }
    )
}

/** Displays one label and value in the order details dialog. */
@Composable
private fun OrderDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily))
    }
}

/** Shows invoice actions after the PDF has been generated. */
@Composable
private fun InvoiceReadyDialog(
    document: InvoiceDocument,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invoice Ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "PDF invoice for order ${document.orderId} has been saved.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpen,
                modifier = Modifier.testTag("open_invoice_pdf_button")
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open PDF")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onShare,
                    modifier = Modifier.testTag("share_invoice_pdf_button")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    )
}

/** Collects or edits the customer's rating and review for an order. */
@Composable
private fun RateOrderDialog(
    initialStars: Int,
    initialComment: String,
    isEditing: Boolean,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (Int, String) -> Unit
) {
    var selectedStars by remember(initialStars) { mutableIntStateOf(initialStars.coerceIn(1, 5)) }
    var comment by remember(initialComment) { mutableStateOf(initialComment) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(if (isEditing) "Edit Your Rating" else "Rate Your Meal") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    (1..5).forEach { star ->
                        IconButton(
                            onClick = { selectedStars = star },
                            enabled = !isSubmitting,
                            modifier = Modifier.testTag("rate_star_$star")
                        ) {
                            Icon(
                                imageVector = if (star <= selectedStars) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = "$star stars",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    enabled = !isSubmitting,
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Share feedback (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rate_comment_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selectedStars, comment) },
                enabled = !isSubmitting,
                modifier = Modifier.testTag("submit_rating_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isEditing) "Update" else "Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") }
        }
    )
}
