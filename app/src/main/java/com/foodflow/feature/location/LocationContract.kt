package com.foodflow.feature.location

import com.foodflow.core.mvi.MviEffect
import com.foodflow.core.mvi.MviIntent
import com.foodflow.core.mvi.MviState
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.SelectedMapPin

data class LocationUiState(
    val addresses: List<DeliveryAddress> = emptyList(),
    val selectedAddressId: String? = null,
    val activeMapPin: SelectedMapPin = SelectedMapPin(
        line1 = "42 Satellite Road",
        landmark = "Near Jodhpur Cross Road",
        city = "Ahmedabad",
        pincode = "380015",
        latitude = 23.0258,
        longitude = 72.5293
    ),
    val showAddDialog: Boolean = false,
    val isSaving: Boolean = false
) : MviState

sealed interface LocationIntent : MviIntent {
    data class SelectAddress(val address: DeliveryAddress) : LocationIntent
    data class UpdateMapPin(val pin: SelectedMapPin) : LocationIntent
    data class ConfirmMapPin(val pin: SelectedMapPin) : LocationIntent
    data object DetectCurrentLocation : LocationIntent
    data object OpenAddDialog : LocationIntent
    data object DismissAddDialog : LocationIntent
    data class SaveNewAddress(
        val label: AddressLabel,
        val line1: String,
        val landmark: String,
        val city: String,
        val pincode: String,
        val instructions: String,
        val isDefault: Boolean,
        val latitude: Double = 23.0258,
        val longitude: Double = 72.5293
    ) : LocationIntent
    data class DeleteAddress(val addressId: String) : LocationIntent
}

sealed interface LocationEffect : MviEffect {
    data object NavigateBack : LocationEffect
    data class ShowMessage(val message: String) : LocationEffect
}
