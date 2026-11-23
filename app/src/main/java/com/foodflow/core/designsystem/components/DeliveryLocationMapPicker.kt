package com.foodflow.core.designsystem.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.location.LocationManager
import com.foodflow.domain.model.MapLocationSuggestion
import com.foodflow.domain.model.SelectedMapPin
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot

/**
 * Curated Ahmedabad locality directory used for instant offline address search and
 * reverse-geocoding fallback when offline or when Maps Geocoder is unavailable.
 */
private val AhmedabadLocalities = listOf(
    MapLocationSuggestion("Satellite Road", "Near Jodhpur Cross Road", "42 Satellite Road", "Near Jodhpur Cross Road", "Ahmedabad", "380015", 23.0258, 72.5293),
    MapLocationSuggestion("Prahlad Nagar", "100 Feet Anand Nagar Road", "18 Corporate Park, Prahlad Nagar", "Near AUDA Garden", "Ahmedabad", "380015", 23.0120, 72.5108),
    MapLocationSuggestion("SG Highway", "Near Iskcon Cross Road", "704 Titanium One, SG Highway", "Near Iskcon Temple", "Ahmedabad", "380054", 23.0279, 72.5071),
    MapLocationSuggestion("Navrangpura", "CG Road & University Area", "14 Commerce Six Roads, Navrangpura", "Near Gujarat University", "Ahmedabad", "380009", 23.0365, 72.5611),
    MapLocationSuggestion("Vastrapur", "Near Vastrapur Lake", "205 Lakeview Residency, Vastrapur", "Opposite Vastrapur Lake", "Ahmedabad", "380015", 23.0387, 72.5290),
    MapLocationSuggestion("Bodakdev", "Sindhu Bhavan Road", "88 Emerald Heights, Bodakdev", "Off Sindhu Bhavan Road", "Ahmedabad", "380054", 23.0405, 72.5060),
    MapLocationSuggestion("Thaltej", "Thaltej Cross Road", "12 Maple Greens, Thaltej", "Near Thaltej Metro Station", "Ahmedabad", "380059", 23.0504, 72.4998),
    MapLocationSuggestion("Maninagar", "Kankaria Lake Road", "31 Heritage Homes, Maninagar", "Near Kankaria Lake", "Ahmedabad", "380008", 22.9972, 72.6041),
    MapLocationSuggestion("Bopal", "South Bopal Main Road", "502 Orchid Harmony, South Bopal", "Near Bopal Cross Road", "Ahmedabad", "380058", 23.0225, 72.4670)
)

/** Uses the nearest bundled locality for immediate offline address details at the selected coordinates. */
private fun resolveNearestLocality(lat: Double, lng: Double): SelectedMapPin {
    val nearest = AhmedabadLocalities.minByOrNull { loc ->
        hypot(loc.latitude - lat, loc.longitude - lng)
    } ?: AhmedabadLocalities.first()
    return SelectedMapPin(
        line1 = nearest.line1,
        landmark = nearest.landmark,
        city = nearest.city,
        pincode = nearest.pincode,
        latitude = lat,
        longitude = lng
    )
}

/**
 * Reusable interactive Google Maps delivery location picker shared by LocationScreen
 * and CheckoutScreen's quick-adjust map modal sheet.
 *
 * Supports:
 * - Interactive Google Map with tap-to-move & draggable delivery marker (plus theme-aware dark style)
 * - Address search with instant suggestions
 * - Runtime-permission-safe "Use Current Location" (FINE/COARSE location)
 * - Automatic graceful interactive fallback when MAPS_API_KEY is unconfigured
 */
@Composable
fun DeliveryLocationMapPicker(
    initialLatitude: Double,
    initialLongitude: Double,
    initialAddressLine: String,
    confirmButtonLabel: String,
    onConfirmPin: (SelectedMapPin) -> Unit,
    onPinMoved: (SelectedMapPin) -> Unit = {},
    onLocationFeedback: (String) -> Unit = {},
    mapHeight: Dp = 230.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val hasValidKey = remember { FoodFlowMapUtils.hasValidMapsApiKey() }

    var pinLat by remember(initialLatitude) { mutableDoubleStateOf(initialLatitude) }
    var pinLng by remember(initialLongitude) { mutableDoubleStateOf(initialLongitude) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedPin by remember(initialLatitude, initialLongitude, initialAddressLine) {
        val fallback = resolveNearestLocality(initialLatitude, initialLongitude)
        mutableStateOf(
            if (initialAddressLine.isNotBlank()) fallback.copy(line1 = initialAddressLine) else fallback
        )
    }

    /** Updates the pin immediately, then refines its address asynchronously with Geocoder when available. */
    fun updatePinCoordinates(newLat: Double, newLng: Double, explicitSuggestion: MapLocationSuggestion? = null) {
        pinLat = newLat
        pinLng = newLng
        if (explicitSuggestion != null) {
            val resolved = SelectedMapPin(
                line1 = explicitSuggestion.line1,
                landmark = explicitSuggestion.landmark,
                city = explicitSuggestion.city,
                pincode = explicitSuggestion.pincode,
                latitude = newLat,
                longitude = newLng
            )
            selectedPin = resolved
            onPinMoved(resolved)
            return
        }

        // Immediate responsive locality resolution, refined asynchronously with Geocoder if available
        val immediate = resolveNearestLocality(newLat, newLng)
        selectedPin = immediate
        onPinMoved(immediate)

        coroutineScope.launch {
            val geocoded = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    if (Geocoder.isPresent()) {
                        val list = Geocoder(context, Locale.getDefault()).getFromLocation(newLat, newLng, 1)
                        val addr = list?.firstOrNull()
                        if (addr != null) {
                            val street = listOfNotNull(addr.featureName, addr.thoroughfare, addr.subLocality)
                                .distinct()
                                .joinToString(", ")
                                .ifBlank { immediate.line1 }
                            SelectedMapPin(
                                line1 = street,
                                landmark = addr.subLocality ?: immediate.landmark,
                                city = addr.locality ?: immediate.city,
                                pincode = addr.postalCode?.filter { it.isDigit() }?.take(6)
                                    ?.takeIf { it.length == 6 } ?: immediate.pincode,
                                latitude = newLat,
                                longitude = newLng
                            )
                        } else null
                    } else null
                }.getOrNull()
            }
            if (geocoded != null) {
                selectedPin = geocoded
                onPinMoved(geocoded)
            }
        }
    }

    @SuppressLint("MissingPermission")
    /** Picks the newest cached Android location, falling back to the bundled local delivery hub. */
    fun fetchDeviceLocation() {
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val bestLocation = providers
            .mapNotNull { provider ->
                runCatching { locationManager?.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (bestLocation != null) {
            updatePinCoordinates(bestLocation.latitude, bestLocation.longitude)
            onLocationFeedback("Centered on your current location.")
        } else {
            val defaultLoc = AhmedabadLocalities.first()
            updatePinCoordinates(defaultLoc.latitude, defaultLoc.longitude, defaultLoc)
            onLocationFeedback("Centered on nearby Ahmedabad delivery hub.")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchDeviceLocation()
        } else {
            onLocationFeedback("Location permission declined. Drag or tap the map to adjust your pin.")
        }
    }

    val matchingSuggestions = remember(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            emptyList()
        } else {
            AhmedabadLocalities.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.subtitle.contains(q, ignoreCase = true) ||
                    it.line1.contains(q, ignoreCase = true) ||
                    it.pincode.contains(q)
            }.take(4)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Address Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search area, street or pincode...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search address",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear address search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("map_address_search_input")
        )

        // Instant Search Suggestions
        if (matchingSuggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    matchingSuggestions.forEachIndexed { idx, suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    searchQuery = ""
                                    updatePinCoordinates(
                                        suggestion.latitude,
                                        suggestion.longitude,
                                        explicitSuggestion = suggestion
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("map_suggestion_$idx"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = suggestion.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${suggestion.subtitle} • ${suggestion.pincode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (idx < matchingSuggestions.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        // Interactive Google Map or Theme-Aware Touch-Pin Fallback
        if (hasValidKey) {
            GoogleMapsInteractivePickerSurface(
                pinLat = pinLat,
                pinLng = pinLng,
                mapHeight = mapHeight,
                onPinCoordinateChanged = { lat, lng -> updatePinCoordinates(lat, lng) }
            )
        } else {
            InteractiveFallbackMapPickerSurface(
                pinLat = pinLat,
                pinLng = pinLng,
                mapHeight = mapHeight,
                onPinCoordinateChanged = { lat, lng -> updatePinCoordinates(lat, lng) }
            )
        }

        // Selected Address Summary Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "Selected delivery pin",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedPin.line1,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("map_selected_address_line")
                    )
                    Text(
                        text = "${selectedPin.landmark}, ${selectedPin.city} - ${selectedPin.pincode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Action Row: Use Current Location + Confirm Pin
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val hasFine = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        fetchDeviceLocation()
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("detect_gps_button")
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Current")
            }

            Button(
                onClick = { onConfirmPin(selectedPin) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("confirm_map_pin_button")
            ) {
                Text(confirmButtonLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Displays the Google Map, moves the selected marker, and forwards map taps or drags to the parent picker. */
@Composable
private fun GoogleMapsInteractivePickerSurface(
    pinLat: Double,
    pinLng: Double,
    mapHeight: Dp,
    onPinCoordinateChanged: (Double, Double) -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val mapStyle = remember(isDark) { FoodFlowMapUtils.mapStyleForTheme(isDark) }
    val targetLatLng = LatLng(pinLat, pinLng)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(targetLatLng, 15f)
    }
    val markerState = remember { MarkerState(position = targetLatLng) }

    LaunchedEffect(pinLat, pinLng) {
        val next = LatLng(pinLat, pinLng)
        markerState.position = next
        runCatching {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(next, 15f), 450)
        }
    }

    LaunchedEffect(markerState.position) {
        val pos = markerState.position
        if (hypot(pos.latitude - pinLat, pos.longitude - pinLng) > 1e-5) {
            onPinCoordinateChanged(pos.latitude, pos.longitude)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(RoundedCornerShape(16.dp))
            .testTag("google_map_picker_view")
    ) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapStyleOptions = mapStyle),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                mapToolbarEnabled = false,
                compassEnabled = true
            ),
            onMapClick = { clicked ->
                markerState.position = clicked
                onPinCoordinateChanged(clicked.latitude, clicked.longitude)
            }
        ) {
            Marker(
                state = markerState,
                draggable = true,
                title = "Delivery Location",
                snippet = "Tap map or drag pin to adjust"
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        ) {
            Text(
                text = "Tap map or drag pin to fine-tune",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** Renders a local coordinate grid and maps touch positions back into the supported Ahmedabad bounds. */
@Composable
private fun InteractiveFallbackMapPickerSurface(
    pinLat: Double,
    pinLng: Double,
    mapHeight: Dp,
    onPinCoordinateChanged: (Double, Double) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val minLat = 22.985
    val maxLat = 23.065
    val minLng = 72.455
    val maxLng = 72.620

    val normX = ((pinLng - minLng) / (maxLng - minLng)).toFloat().coerceIn(0.08f, 0.92f)
    val normY = (1.0 - (pinLat - minLat) / (maxLat - minLat)).toFloat().coerceIn(0.10f, 0.90f)

    /** Converts a bounded canvas point into latitude/longitude within the fallback map region. */
    fun updateFromCanvasPoint(offset: Offset, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val nx = (offset.x / width).coerceIn(0.05f, 0.95f)
        val ny = (offset.y / height).coerceIn(0.05f, 0.95f)
        val newLng = minLng + nx * (maxLng - minLng)
        val newLat = minLat + (1f - ny) * (maxLat - minLat)
        onPinCoordinateChanged(newLat, newLng)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.65f))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    updateFromCanvasPoint(offset, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromCanvasPoint(change.position, size.width.toFloat(), size.height.toFloat())
                }
            }
            .testTag("fallback_map_picker_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Subtle city block roads
            val grid = 6
            for (i in 1 until grid) {
                val y = size.height * i / grid
                drawLine(colorScheme.outlineVariant.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 2f)
                val x = size.width * i / grid
                drawLine(colorScheme.outlineVariant.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 2f)
            }
            // Major arterial avenue highlight
            drawLine(
                color = colorScheme.secondary.copy(alpha = 0.25f),
                start = Offset(0f, size.height * 0.56f),
                end = Offset(size.width, size.height * 0.34f),
                strokeWidth = 10f
            )
            drawLine(
                color = colorScheme.secondary.copy(alpha = 0.22f),
                start = Offset(size.width * 0.36f, 0f),
                end = Offset(size.width * 0.58f, size.height),
                strokeWidth = 8f
            )

            // Draw locality landmark dots
            AhmedabadLocalities.forEach { loc ->
                val lx = ((loc.longitude - minLng) / (maxLng - minLng)).toFloat().coerceIn(0.06f, 0.94f) * size.width
                val ly = (1.0 - (loc.latitude - minLat) / (maxLat - minLat)).toFloat().coerceIn(0.08f, 0.92f) * size.height
                drawCircle(colorScheme.secondary.copy(alpha = 0.35f), radius = 6f, center = Offset(lx, ly))
            }

            // Active delivery pin
            val pinCenter = Offset(normX * size.width, normY * size.height)
            drawCircle(colorScheme.primary.copy(alpha = 0.22f), radius = 30f, center = pinCenter)
            drawCircle(colorScheme.primary, radius = 14f, center = pinCenter)
            drawCircle(Color.White, radius = 5.5f, center = pinCenter)
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(0.5.dp, colorScheme.outlineVariant),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        ) {
            Text(
                text = "Tap or drag pin to adjust delivery spot",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colorScheme.surface.copy(alpha = 0.88f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Text(
                text = String.format(Locale.US, "%.4f, %.4f", pinLat, pinLng),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
