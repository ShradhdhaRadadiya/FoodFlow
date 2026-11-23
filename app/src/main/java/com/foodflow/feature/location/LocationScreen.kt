package com.foodflow.feature.location

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodflow.core.designsystem.components.DeliveryLocationMapPicker
import com.foodflow.core.designsystem.components.FoodFlowTopBar
import com.foodflow.core.designsystem.components.foodFlowFabInsets
import com.foodflow.core.designsystem.components.foodFlowScreenListPadding
import com.foodflow.core.designsystem.theme.FoodFlowSpacing
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.SelectedMapPin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Lets the user view, select, and edit delivery addresses. */
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    viewModel: LocationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                LocationEffect.NavigateBack -> onBack()
                is LocationEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FoodFlowTopBar(
                title = "Delivery Location",
                subtitle = "Pin on map or pick a saved address",
                onBack = onBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onIntent(LocationIntent.OpenAddDialog) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .foodFlowFabInsets()
                    .testTag("add_address_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add new address")
            }
        }
    ) { padding ->
        val density = LocalDensity.current
        val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .testTag("location_screen_list"),
            // Generous bottom clearance ensures the last saved address card never hides behind the FAB or nav bar;
            // collapses cleanly while IME is open so there is no blank gap above the keyboard.
            contentPadding = foodFlowScreenListPadding(
                horizontal = FoodFlowSpacing.md,
                top = 12.dp,
                bottomExtra = if (isKeyboardOpen) 16.dp else 96.dp,
                includeNavBarInset = !isKeyboardOpen
            ),
            verticalArrangement = Arrangement.spacedBy(FoodFlowSpacing.sm)
        ) {
            item(key = "interactive_map_picker_card") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(FoodFlowSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Select on Map",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        DeliveryLocationMapPicker(
                            initialLatitude = state.activeMapPin.latitude,
                            initialLongitude = state.activeMapPin.longitude,
                            initialAddressLine = state.activeMapPin.line1,
                            confirmButtonLabel = "Deliver Here",
                            onPinMoved = { pin -> viewModel.onIntent(LocationIntent.UpdateMapPin(pin)) },
                            onConfirmPin = { pin -> viewModel.onIntent(LocationIntent.ConfirmMapPin(pin)) },
                            onLocationFeedback = { msg ->
                                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                            },
                            mapHeight = 224.dp
                        )
                    }
                }
            }

            item(key = "saved_header", contentType = "section_header") {
                Text(
                    text = "Saved Addresses (${state.addresses.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(state.addresses, key = { it.id }, contentType = { "address_card" }) { addr ->
                val isSelected = addr.id == state.selectedAddressId
                val labelIcon = when (addr.label) {
                    AddressLabel.HOME -> Icons.Filled.Home
                    AddressLabel.WORK -> Icons.Filled.Work
                    AddressLabel.OTHER -> Icons.Filled.LocationOn
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onIntent(LocationIntent.SelectAddress(addr)) }
                        .testTag("address_card_${addr.id}"),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    border = BorderStroke(
                        if (isSelected) 1.5.dp else 0.75.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FoodFlowSpacing.md),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = labelIcon,
                            contentDescription = null,
                            tint = if (addr.isServiceable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(addr.label.displayName, style = MaterialTheme.typography.titleMedium)
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (!addr.isServiceable) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "UNSERVICEABLE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(addr.line1, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${addr.landmark.ifBlank { addr.city }}, ${addr.city} - ${addr.pincode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (addr.deliveryInstructions.isNotBlank()) {
                                Text(
                                    text = "Note: ${addr.deliveryInstructions}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (!addr.isDefault) {
                            IconButton(onClick = { viewModel.onIntent(LocationIntent.DeleteAddress(addr.id)) }) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "Delete address",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddAddressDialog(
            initialPin = state.activeMapPin,
            onDismiss = { viewModel.onIntent(LocationIntent.DismissAddDialog) },
            onSave = { label, line1, landmark, city, pincode, instructions, isDefault ->
                viewModel.onIntent(
                    LocationIntent.SaveNewAddress(
                        label = label,
                        line1 = line1,
                        landmark = landmark,
                        city = city,
                        pincode = pincode,
                        instructions = instructions,
                        isDefault = isDefault,
                        latitude = state.activeMapPin.latitude,
                        longitude = state.activeMapPin.longitude
                    )
                )
            }
        )
    }
}

/** Collects address details and map location before saving a new address. */
@Composable
private fun AddAddressDialog(
    initialPin: SelectedMapPin,
    onDismiss: () -> Unit,
    onSave: (AddressLabel, String, String, String, String, String, Boolean) -> Unit
) {
    var selectedLabel by remember { mutableStateOf(AddressLabel.HOME) }
    var line1 by remember(initialPin) { mutableStateOf(initialPin.line1) }
    var landmark by remember(initialPin) { mutableStateOf(initialPin.landmark) }
    var city by remember(initialPin) { mutableStateOf(initialPin.city) }
    var pincode by remember(initialPin) { mutableStateOf(initialPin.pincode) }
    var instructions by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Delivery Address") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddressLabel.entries.forEach { lbl ->
                        FilterChip(
                            selected = selectedLabel == lbl,
                            onClick = { selectedLabel = lbl },
                            label = { Text(lbl.displayName) }
                        )
                    }
                }
                OutlinedTextField(
                    value = line1,
                    onValueChange = { line1 = it },
                    label = { Text("Flat / Street Address") },
                    singleLine = true,
                    modifier = Modifier.testTag("add_address_line1")
                )
                OutlinedTextField(
                    value = landmark,
                    onValueChange = { landmark = it },
                    label = { Text("Landmark") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text("City") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pincode,
                        onValueChange = { pincode = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("Pincode") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Delivery Instructions") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                    Text("Set as default address", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedLabel, line1, landmark, city, pincode, instructions, isDefault) },
                modifier = Modifier.testTag("save_address_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
