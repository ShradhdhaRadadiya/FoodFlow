package com.foodflow.feature.location

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.SelectedMapPin
import com.foodflow.domain.usecase.address.DeleteAddressUseCase
import com.foodflow.domain.usecase.address.DetectCurrentLocationUseCase
import com.foodflow.domain.usecase.address.ObserveAddressesUseCase
import com.foodflow.domain.usecase.address.ObserveSelectedAddressUseCase
import com.foodflow.domain.usecase.address.SaveAddressUseCase
import com.foodflow.domain.usecase.address.SelectAddressUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocationViewModel @Inject constructor(
    observeAddresses: ObserveAddressesUseCase,
    observeSelectedAddress: ObserveSelectedAddressUseCase,
    private val selectAddress: SelectAddressUseCase,
    private val saveAddress: SaveAddressUseCase,
    private val deleteAddress: DeleteAddressUseCase,
    private val detectCurrentLocation: DetectCurrentLocationUseCase
) : MviViewModel<LocationUiState, LocationIntent, LocationEffect>(LocationUiState()) {

    private var hasSeededPinFromSelected = false

    init {
        combine(
            observeAddresses(),
            observeSelectedAddress()
        ) { addresses, selected ->
            updateState {
                val nextPin = if (!hasSeededPinFromSelected && selected != null) {
                    hasSeededPinFromSelected = true
                    SelectedMapPin(
                        line1 = selected.line1,
                        landmark = selected.landmark,
                        city = selected.city,
                        pincode = selected.pincode,
                        latitude = selected.latitude,
                        longitude = selected.longitude
                    )
                } else {
                    activeMapPin
                }
                copy(
                    addresses = addresses,
                    selectedAddressId = selected?.id,
                    activeMapPin = nextPin
                )
            }
        }.launchIn(viewModelScope)
    }

    /** Handles address selection, map pin changes, and address management actions. */
    override fun onIntent(intent: LocationIntent) {
        when (intent) {
            is LocationIntent.SelectAddress -> {
                if (!intent.address.isServiceable) {
                    emitEffect(LocationEffect.ShowMessage("Sorry, ${intent.address.city} is outside our delivery area."))
                    return
                }
                viewModelScope.launch {
                    selectAddress(intent.address.id)
                    emitEffect(LocationEffect.ShowMessage("Delivering to ${intent.address.label.displayName}"))
                    emitEffect(LocationEffect.NavigateBack)
                }
            }
            is LocationIntent.UpdateMapPin -> {
                updateState { copy(activeMapPin = intent.pin) }
            }
            is LocationIntent.ConfirmMapPin -> {
                viewModelScope.launch {
                    updateState { copy(isSaving = true, activeMapPin = intent.pin) }
                    val draft = DeliveryAddress(
                        id = "",
                        label = AddressLabel.OTHER,
                        recipientName = "Aarav Sharma",
                        line1 = intent.pin.line1,
                        landmark = intent.pin.landmark,
                        city = intent.pin.city,
                        pincode = intent.pin.pincode,
                        deliveryInstructions = "Pinned via Map",
                        latitude = intent.pin.latitude,
                        longitude = intent.pin.longitude,
                        isDefault = true,
                        isServiceable = true
                    )
                    when (val res = saveAddress(draft)) {
                        is AppResult.Success -> {
                            selectAddress(res.data.id)
                            updateState { copy(isSaving = false) }
                            emitEffect(LocationEffect.ShowMessage("Delivery location set to ${res.data.line1}"))
                            emitEffect(LocationEffect.NavigateBack)
                        }
                        is AppResult.Error -> {
                            updateState { copy(isSaving = false) }
                            emitEffect(LocationEffect.ShowMessage(res.error.userMessage))
                        }
                    }
                }
            }
            LocationIntent.DetectCurrentLocation -> {
                viewModelScope.launch {
                    when (val res = detectCurrentLocation()) {
                        is AppResult.Success -> {
                            emitEffect(LocationEffect.ShowMessage("Location updated: ${res.data.line1}"))
                            emitEffect(LocationEffect.NavigateBack)
                        }
                        is AppResult.Error -> emitEffect(LocationEffect.ShowMessage(res.error.userMessage))
                    }
                }
            }
            LocationIntent.OpenAddDialog -> updateState { copy(showAddDialog = true) }
            LocationIntent.DismissAddDialog -> updateState { copy(showAddDialog = false) }
            is LocationIntent.SaveNewAddress -> {
                viewModelScope.launch {
                    updateState { copy(isSaving = true) }
                    val draft = DeliveryAddress(
                        id = "",
                        label = intent.label,
                        recipientName = "Aarav Sharma",
                        line1 = intent.line1.trim(),
                        landmark = intent.landmark.trim(),
                        city = intent.city.trim(),
                        pincode = intent.pincode.trim(),
                        deliveryInstructions = intent.instructions.trim(),
                        latitude = intent.latitude,
                        longitude = intent.longitude,
                        isDefault = intent.isDefault,
                        isServiceable = true
                    )
                    when (val res = saveAddress(draft)) {
                        is AppResult.Success -> {
                            updateState { copy(isSaving = false, showAddDialog = false) }
                            val note = if (res.data.isServiceable) {
                                "Address saved and selected."
                            } else {
                                "Address saved (outside delivery radius)."
                            }
                            emitEffect(LocationEffect.ShowMessage(note))
                        }
                        is AppResult.Error -> {
                            updateState { copy(isSaving = false) }
                            emitEffect(LocationEffect.ShowMessage(res.error.userMessage))
                        }
                    }
                }
            }
            is LocationIntent.DeleteAddress -> {
                viewModelScope.launch {
                    deleteAddress(intent.addressId)
                    emitEffect(LocationEffect.ShowMessage("Address deleted."))
                }
            }
        }
    }
}
