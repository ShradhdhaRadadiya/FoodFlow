package com.foodflow.feature.tracking

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.designsystem.components.FoodFlowMapUtils
import com.foodflow.core.designsystem.components.FoodFlowPullToRefreshBox
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.OfflineBanner
import com.foodflow.core.designsystem.components.OrderDeliveryJourneyCard
import com.foodflow.core.designsystem.components.OrderStatusFeedbackCard
import com.foodflow.core.designsystem.components.foodFlowScreenListPadding
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.core.designsystem.theme.JetBrainsMonoFontFamily
import com.foodflow.domain.model.DeliveryRoute
import com.foodflow.domain.model.Order
import com.foodflow.domain.model.RiderLocation
import com.foodflow.domain.model.RouteCoordinate
import com.foodflow.domain.model.RouteProgressSample
import com.foodflow.domain.route.RouteGeometryCalculator
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.atan2

/** Displays an order's live status, delivery route, and rider information. */
@Composable
fun OrderTrackingScreen(
    orderId: String,
    onBack: () -> Unit,
    onOpenRestaurant: (String) -> Unit = {},
    onBackHome: () -> Unit = onBack,
    viewModel: OrderTrackingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(orderId) {
        viewModel.onIntent(OrderTrackingIntent.Start(orderId))
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is OrderTrackingEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Track Order",
                subtitle = state.order?.restaurantName ?: "Live delivery updates",
                onBack = onBack
            )
        }
    ) { padding ->
        val order = state.order
        if (order == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading live tracking...")
            }
        } else {
            FoodFlowPullToRefreshBox(
                isRefreshing = state.isReconciling,
                onRefresh = { viewModel.onIntent(OrderTrackingIntent.ReconnectAndReconcile) },
                testTag = "order_tracking_pull_to_refresh",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("order_tracking_screen"),
                contentPadding = foodFlowScreenListPadding(
                    horizontal = FoodFlowSpacing.md,
                    top = 12.dp,
                    bottomExtra = 24.dp,
                    includeNavBarInset = true
                ),
                verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.md)
            ) {
                if (state.isOffline) {
                    item(key = "offline_banner") {
                        OfflineBanner(isOffline = true)
                    }
                }

                item(key = "status_hero") {
                    OrderStatusFeedbackCard(
                        order = order,
                        onCancelOrder = { viewModel.onIntent(OrderTrackingIntent.CancelOrder) }
                    )
                }

                item(key = "delivery_journey_animation") {
                    OrderDeliveryJourneyCard(
                        order = order,
                        timelineEvents = state.timeline
                    )
                }

                item(key = "live_route_map") {
                    LiveDeliveryMapCard(
                        order = order,
                        deliveryLocation = state.deliveryLocation,
                        deliveryRoute = state.deliveryRoute
                    )
                }

                order.deliveryPartner?.let { partner ->
                    item(key = "partner_card") {
                        val dialablePhone = remember(partner.phone) {
                            PhoneDialerHelper.sanitizeDialablePhone(partner.phone)
                        }
                        val onCallPartner = {
                            val launched = PhoneDialerHelper.launchDialer(context, partner.phone)
                            if (!launched) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Delivery partner phone number is unavailable right now."
                                    )
                                }
                            }
                        }

                        Card(
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.testTag("tracking_partner_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.DeliveryDining,
                                            contentDescription = "Delivery partner",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = partner.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Star,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = "${partner.rating}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = partner.vehicleNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (dialablePhone != null) {
                                        Text(
                                            text = partner.phone,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(onClick = onCallPartner)
                                                .padding(vertical = 2.dp)
                                                .testTag("tracking_partner_phone")
                                        )
                                    }
                                }

                                FilledTonalIconButton(
                                    onClick = onCallPartner,
                                    enabled = dialablePhone != null,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("tracking_call_partner_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Call,
                                        contentDescription = if (dialablePhone != null) {
                                            "Call delivery partner ${partner.name}"
                                        } else {
                                            "Phone number unavailable"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "quick_navigation_actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOpenRestaurant(order.restaurantId) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("tracking_open_restaurant_button")
                        ) {
                            Icon(Icons.Filled.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restaurant")
                        }
                        Button(
                            onClick = onBackHome,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("tracking_back_home_button")
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Home")
                        }
                    }
                }
            }
            }
        }
    }
}

/** Shows the delivery route and current rider position for an order. */
@Composable
private fun LiveDeliveryMapCard(
    order: Order,
    deliveryLocation: RiderLocation?,
    deliveryRoute: DeliveryRoute?
) {
    val hasValidMapsApiKey = remember { FoodFlowMapUtils.hasValidMapsApiKey() }

    val routePoints = remember(order.restaurantLat, order.restaurantLng, order.deliveryLat, order.deliveryLng, deliveryRoute) {
        val provided = deliveryRoute?.points
        if (!provided.isNullOrEmpty() && provided.size >= 2) {
            provided
        } else {
            RouteGeometryCalculator.buildUrbanRoadFallback(
                origin = RouteCoordinate(order.restaurantLat, order.restaurantLng),
                destination = RouteCoordinate(order.deliveryLat, order.deliveryLng)
            )
        }
    }

    val targetProgress = deliveryLocation?.progressFraction ?: order.riderProgress
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "rider_progress_anim"
    )

    val routeSample = remember(routePoints, animatedProgress) {
        RouteGeometryCalculator.sampleAlongRoute(routePoints, animatedProgress)
    }

    val etaMinutes = deliveryLocation?.etaMinutes ?: order.estimatedDeliveryMinutes

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_delivery_map_card")
    ) {
        Column(
            modifier = Modifier.padding(FoodFlowSpacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Navigation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Live Bike Route",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeliveryDining,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = deliveryRoute?.distanceText?.let { "$it • $etaMinutes min" }
                                ?: "$etaMinutes min ETA",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (hasValidMapsApiKey) {
                GoogleMapsRoadRoute(
                    order = order,
                    routeSample = routeSample,
                    etaMinutes = etaMinutes
                )
            } else {
                InteractiveRoadCanvasWithBike(
                    routePoints = routePoints,
                    routeSample = routeSample,
                    animatedProgress = animatedProgress
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = order.restaurantName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(animatedProgress * 100).toInt()}% • Your Doorstep",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun GoogleMapsRoadRoute(
    order: Order,
    routeSample: RouteProgressSample,
    etaMinutes: Int
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f
    val mapStyle = remember(isDark) { FoodFlowMapUtils.mapStyleForTheme(isDark) }
    var isMapLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        FoodFlowMapUtils.ensureInitialized(context)
    }

    val restaurantLatLng = remember(order.restaurantLat, order.restaurantLng) {
        LatLng(order.restaurantLat, order.restaurantLng)
    }
    val destinationLatLng = remember(order.deliveryLat, order.deliveryLng) {
        LatLng(order.deliveryLat, order.deliveryLng)
    }
    val riderLatLng = LatLng(routeSample.position.latitude, routeSample.position.longitude)

    val completedLatLngs = remember(routeSample.completedPoints) {
        routeSample.completedPoints.map { LatLng(it.latitude, it.longitude) }
    }
    val remainingLatLngs = remember(routeSample.remainingPoints) {
        routeSample.remainingPoints.map { LatLng(it.latitude, it.longitude) }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(riderLatLng, 14f)
    }

    LaunchedEffect(isMapLoaded, completedLatLngs.firstOrNull(), remainingLatLngs.lastOrNull()) {
        if (!isMapLoaded) return@LaunchedEffect
        val builder = LatLngBounds.builder()
            .include(restaurantLatLng)
            .include(destinationLatLng)
        completedLatLngs.forEach { builder.include(it) }
        remainingLatLngs.forEach { builder.include(it) }
        runCatching {
            cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(builder.build(), 76))
        }
    }

    val restaurantMarkerState = remember(restaurantLatLng) { MarkerState(position = restaurantLatLng) }
    val destinationMarkerState = remember(destinationLatLng) { MarkerState(position = destinationLatLng) }
    val riderMarkerState = remember { MarkerState(position = riderLatLng) }
    LaunchedEffect(riderLatLng) {
        riderMarkerState.position = riderLatLng
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(228.dp)
            .clip(RoundedCornerShape(16.dp))
            .testTag("google_map_tracking_view")
    ) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapStyleOptions = mapStyle),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = true
            ),
            onMapLoaded = { isMapLoaded = true }
        ) {
            val bikeIcon = remember(
                isMapLoaded,
                colorScheme.primary,
                colorScheme.secondary,
                colorScheme.surface
            ) {
                FoodFlowMapUtils.createDeliveryBikeDescriptor(
                    primaryColor = colorScheme.primary.toArgb(),
                    secondaryColor = colorScheme.secondary.toArgb(),
                    surfaceColor = colorScheme.surface.toArgb()
                )
            }
            val restaurantIcon = remember(isMapLoaded) {
                FoodFlowMapUtils.safeDefaultMarker(BitmapDescriptorFactory.HUE_AZURE)
            }
            val destinationIcon = remember(isMapLoaded) {
                FoodFlowMapUtils.safeDefaultMarker(BitmapDescriptorFactory.HUE_GREEN)
            }

            Marker(
                state = restaurantMarkerState,
                title = order.restaurantName,
                snippet = "Restaurant Pickup",
                icon = restaurantIcon
            )
            Marker(
                state = destinationMarkerState,
                title = "Delivery Address",
                snippet = order.deliveryAddress,
                icon = destinationIcon
            )
            Marker(
                state = riderMarkerState,
                title = order.deliveryPartner?.name ?: "Delivery Bike",
                snippet = "On the way • ETA $etaMinutes mins",
                icon = bikeIcon,
                flat = true,
                rotation = routeSample.bearingDegrees,
                anchor = Offset(0.5f, 0.5f)
            )
            if (completedLatLngs.size >= 2) {
                Polyline(
                    points = completedLatLngs,
                    color = colorScheme.primary,
                    width = 12f
                )
            }
            if (remainingLatLngs.size >= 2) {
                Polyline(
                    points = remainingLatLngs,
                    color = colorScheme.secondary.copy(alpha = 0.55f),
                    width = 9f,
                    pattern = listOf(Dash(18f), Gap(10f))
                )
            }
        }
    }
}

@Composable
private fun InteractiveRoadCanvasWithBike(
    routePoints: List<RouteCoordinate>,
    routeSample: RouteProgressSample,
    animatedProgress: Float
) {
    val colorScheme = MaterialTheme.colorScheme
    val primaryColor = colorScheme.primary
    val secondaryColor = colorScheme.secondary
    val outlineColor = colorScheme.outlineVariant
    val bgColor = colorScheme.surfaceVariant.copy(alpha = 0.58f)
    val surfaceColor = colorScheme.surface

    val pulseTransition = rememberInfiniteTransition(label = "bike_pulse")
    val haloScale by pulseTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.26f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bike_halo_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(14.dp)
            .testTag("route_map_fallback_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Subtle city street-grid blocks
            val gridSteps = 6
            for (i in 1 until gridSteps) {
                val y = size.height * i / gridSteps
                drawLine(outlineColor.copy(alpha = 0.36f), Offset(0f, y), Offset(size.width, y), 1.5f)
                val x = size.width * i / gridSteps
                drawLine(outlineColor.copy(alpha = 0.36f), Offset(x, 0f), Offset(x, size.height), 1.5f)
            }

            if (routePoints.size < 2) return@Canvas

            val minLat = routePoints.minOf { it.latitude }
            val maxLat = routePoints.maxOf { it.latitude }
            val minLng = routePoints.minOf { it.longitude }
            val maxLng = routePoints.maxOf { it.longitude }
            val latSpan = (maxLat - minLat).coerceAtLeast(1e-4)
            val lngSpan = (maxLng - minLng).coerceAtLeast(1e-4)

            val padX = size.width * 0.12f
            val padY = size.height * 0.16f
            val drawW = size.width - padX * 2f
            val drawH = size.height - padY * 2f

            fun project(coord: RouteCoordinate): Offset {
                val nx = ((coord.longitude - minLng) / lngSpan).toFloat()
                val ny = 1f - ((coord.latitude - minLat) / latSpan).toFloat()
                return Offset(padX + nx * drawW, padY + ny * drawH)
            }

            val projectedFull = routePoints.map(::project)
            val projectedCompleted = routeSample.completedPoints.map(::project)
            val projectedRemaining = routeSample.remainingPoints.map(::project)

            // Draw road casing (subtle wider road base)
            val fullRoadPath = Path().apply {
                moveTo(projectedFull.first().x, projectedFull.first().y)
                for (i in 1 until projectedFull.size) {
                    lineTo(projectedFull[i].x, projectedFull[i].y)
                }
            }
            drawPath(
                path = fullRoadPath,
                color = surfaceColor.copy(alpha = 0.72f),
                style = Stroke(width = 16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Draw remaining dashed road polyline
            if (projectedRemaining.size >= 2) {
                val remPath = Path().apply {
                    moveTo(projectedRemaining.first().x, projectedRemaining.first().y)
                    for (i in 1 until projectedRemaining.size) {
                        lineTo(projectedRemaining[i].x, projectedRemaining[i].y)
                    }
                }
                drawPath(
                    path = remPath,
                    color = secondaryColor.copy(alpha = 0.50f),
                    style = Stroke(
                        width = 7f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                )
            }

            // Draw completed glowing road polyline
            if (projectedCompleted.size >= 2) {
                val donePath = Path().apply {
                    moveTo(projectedCompleted.first().x, projectedCompleted.first().y)
                    for (i in 1 until projectedCompleted.size) {
                        lineTo(projectedCompleted[i].x, projectedCompleted[i].y)
                    }
                }
                drawPath(
                    path = donePath,
                    color = primaryColor,
                    style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Restaurant origin marker
            val startPt = projectedFull.first()
            drawCircle(secondaryColor, radius = 13f, center = startPt)
            drawCircle(Color.White, radius = 5.5f, center = startPt)

            // Customer destination marker
            val endPt = projectedFull.last()
            drawCircle(primaryColor, radius = 13f, center = endPt)
            drawCircle(Color.White, radius = 5.5f, center = endPt)

            // Compute exact screen-space direction angle along the active road segment
            val bikeCenter = project(routeSample.position)
            val screenAngleDeg = run {
                val ahead = projectedRemaining.getOrNull(1)
                val behind = projectedCompleted.getOrNull(projectedCompleted.lastIndex - 1)
                val dx = when {
                    ahead != null && ahead != bikeCenter -> ahead.x - bikeCenter.x
                    behind != null && behind != bikeCenter -> bikeCenter.x - behind.x
                    else -> 1f
                }
                val dy = when {
                    ahead != null && ahead != bikeCenter -> ahead.y - bikeCenter.y
                    behind != null && behind != bikeCenter -> bikeCenter.y - behind.y
                    else -> 0f
                }
                // Our drawn top-down bike points UP (-Y), so add +90° to atan2(dy, dx)
                Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            }

            // Pulsing live bike halo
            drawCircle(
                color = primaryColor.copy(alpha = 0.22f),
                radius = 28f * haloScale,
                center = bikeCenter
            )

            // Draw direction-rotated top-down delivery bike
            rotate(degrees = screenAngleDeg, pivot = bikeCenter) {
                // Badge circle
                drawCircle(surfaceColor, radius = 21f, center = bikeCenter)
                drawCircle(
                    color = primaryColor,
                    radius = 21f,
                    center = bikeCenter,
                    style = Stroke(width = 3f)
                )
                // Forward direction nose triangle
                val nose = Path().apply {
                    moveTo(bikeCenter.x, bikeCenter.y - 28f)
                    lineTo(bikeCenter.x - 6f, bikeCenter.y - 19f)
                    lineTo(bikeCenter.x + 6f, bikeCenter.y - 19f)
                    close()
                }
                drawPath(nose, color = primaryColor)

                // Front tire
                drawRoundRect(
                    color = Color(0xFF1F2937),
                    topLeft = Offset(bikeCenter.x - 2.5f, bikeCenter.y - 16f),
                    size = Size(5f, 9f),
                    cornerRadius = CornerRadius(2.5f, 2.5f)
                )
                // Handlebar
                drawLine(
                    color = secondaryColor,
                    start = Offset(bikeCenter.x - 10f, bikeCenter.y - 7f),
                    end = Offset(bikeCenter.x + 10f, bikeCenter.y - 7f),
                    strokeWidth = 3.2f,
                    cap = StrokeCap.Round
                )
                // Bike body
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(bikeCenter.x - 4.5f, bikeCenter.y - 7f),
                    size = Size(9f, 15f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                // Rear thermal delivery box
                drawRoundRect(
                    color = secondaryColor,
                    topLeft = Offset(bikeCenter.x - 7f, bikeCenter.y + 3f),
                    size = Size(14f, 11f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                // Rider helmet
                drawCircle(Color.White, radius = 3.8f, center = Offset(bikeCenter.x, bikeCenter.y - 1f))
            }
        }

        // Direction pill chip in bottom-left of fallback canvas
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colorScheme.surface.copy(alpha = 0.90f),
            border = BorderStroke(0.5.dp, colorScheme.outlineVariant),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Text(
                text = if (animatedProgress >= 0.99f) "Arrived at doorstep" else "Following road route",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

