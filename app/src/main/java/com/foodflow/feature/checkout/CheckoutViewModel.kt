package com.foodflow.feature.checkout

import androidx.lifecycle.viewModelScope
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.core.mvi.MviViewModel
import com.foodflow.domain.model.AddressLabel
import com.foodflow.domain.model.CheckoutOrderMode
import com.foodflow.domain.model.CreateScheduledOrderParams
import com.foodflow.domain.model.DeliveryAddress
import com.foodflow.domain.model.PaymentMethod
import com.foodflow.domain.model.ScheduleEndConditionType
import com.foodflow.domain.model.ScheduleRecurrenceType
import com.foodflow.domain.model.SelectedMapPin
import com.foodflow.domain.usecase.CalculateCartTotalsUseCase
import com.foodflow.domain.usecase.address.ObserveSelectedAddressUseCase
import com.foodflow.domain.usecase.address.SaveAddressUseCase
import com.foodflow.domain.usecase.address.SelectAddressUseCase
import com.foodflow.domain.usecase.cart.ObserveCartItemsUseCase
import com.foodflow.domain.usecase.order.ObserveActiveCheckoutAttemptUseCase
import com.foodflow.domain.usecase.order.StartOrResumeCheckoutUseCase
import com.foodflow.domain.usecase.order.SubmitIdempotentOrderUseCase
import com.foodflow.domain.usecase.preferences.ObserveUserPreferencesUseCase
import com.foodflow.domain.usecase.restaurant.ObserveCouponsUseCase
import com.foodflow.domain.usecase.restaurant.ObserveRestaurantsUseCase
import com.foodflow.domain.usecase.scheduled.CalculateNextScheduleOccurrenceUseCase
import com.foodflow.domain.usecase.scheduled.CreateScheduledOrderUseCase
import com.foodflow.domain.usecase.scheduled.FormatScheduledPlacementUseCase
import com.foodflow.domain.usecase.scheduled.GetRestaurantOperatingHoursUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    observeCartItems: ObserveCartItemsUseCase,
    observeRestaurants: ObserveRestaurantsUseCase,
    observeCoupons: ObserveCouponsUseCase,
    observeSelectedAddress: ObserveSelectedAddressUseCase,
    observeActiveCheckoutAttempt: ObserveActiveCheckoutAttemptUseCase,
    private val saveAddress: SaveAddressUseCase,
    private val selectAddress: SelectAddressUseCase,
    private val observeUserPreferences: ObserveUserPreferencesUseCase,
    private val startOrResumeCheckout: StartOrResumeCheckoutUseCase,
    private val submitIdempotentOrder: SubmitIdempotentOrderUseCase,
    private val calculateCartTotals: CalculateCartTotalsUseCase,
    private val createScheduledOrder: CreateScheduledOrderUseCase,
    private val calculateNextOccurrence: CalculateNextScheduleOccurrenceUseCase,
    private val formatScheduledPlacement: FormatScheduledPlacementUseCase,
    private val getRestaurantOperatingHours: GetRestaurantOperatingHoursUseCase,
    private val connectivityObserver: ConnectivityObserver
) : MviViewModel<CheckoutUiState, CheckoutIntent, CheckoutEffect>(CheckoutUiState()) {

    private val couponCodeFlow = MutableStateFlow<String?>(null)
    private var inFlightSubmit = false
    private var loadedHoursRestaurantId: String? = null

    init {
        viewModelScope.launch {
            val prefs = observeUserPreferences().first()
            val preferred = runCatching { PaymentMethod.valueOf(prefs.preferredPaymentMethod) }
                .getOrDefault(PaymentMethod.UPI)
            updateState { copy(selectedPaymentMethod = preferred) }
        }

        val catalogFlow = combine(
            observeCartItems(),
            observeRestaurants(),
            observeCoupons(),
            couponCodeFlow
        ) { items, restaurants, coupons, couponCode ->
            val restaurant = items.firstOrNull()?.let { first ->
                restaurants.firstOrNull { it.id == first.restaurantId }
            }
            val coupon = couponCode?.let { code ->
                coupons.firstOrNull { it.code.equals(code, ignoreCase = true) }
            }
            val breakdown = calculateCartTotals(
                items = items,
                baseDeliveryFee = restaurant?.deliveryFee ?: CalculateCartTotalsUseCase.DEFAULT_DELIVERY_FEE,
                coupon = coupon
            )
            Triple(items, restaurant, breakdown)
        }

        combine(
            catalogFlow,
            observeSelectedAddress(),
            observeActiveCheckoutAttempt(),
            connectivityObserver.isOnline
        ) { (items, restaurant, breakdown), address, attempt, online ->
            updateState {
                copy(
                    items = items,
                    restaurant = restaurant,
                    selectedAddress = address,
                    breakdown = breakdown,
                    activeAttempt = attempt,
                    appliedCouponCode = breakdown.appliedCouponCode,
                    isOffline = !online
                )
            }
            if (restaurant != null && restaurant.id != loadedHoursRestaurantId) {
                loadedHoursRestaurantId = restaurant.id
                loadOperatingHours(restaurant.id)
            }
        }.launchIn(viewModelScope)

        recomputeSchedulePreview()
    }

    /** Applies checkout actions, including payment choice, schedule settings, and order submission. */
    override fun onIntent(intent: CheckoutIntent) {
        when (intent) {
            is CheckoutIntent.Initialize -> couponCodeFlow.value = intent.couponCode
            is CheckoutIntent.SelectPaymentMethod -> updateState { copy(selectedPaymentMethod = intent.method) }
            CheckoutIntent.OpenQuickMapSheet -> updateState { copy(showQuickMapSheet = true) }
            CheckoutIntent.DismissQuickMapSheet -> updateState { copy(showQuickMapSheet = false) }
            is CheckoutIntent.ConfirmAdjustedPin -> applyAdjustedPin(intent.pin)
            CheckoutIntent.PlaceOrder -> {
                if (state.value.orderMode == CheckoutOrderMode.SCHEDULE_ORDER) {
                    confirmScheduledOrder()
                } else {
                    placeOrder()
                }
            }
            CheckoutIntent.DismissError -> updateState { copy(errorMessage = null) }
            is CheckoutIntent.SelectOrderMode -> {
                updateState { copy(orderMode = intent.mode, errorMessage = null) }
                recomputeSchedulePreview()
            }
            is CheckoutIntent.SelectRecurrenceType -> {
                val cal = Calendar.getInstance().apply { timeInMillis = state.value.scheduledAtUtcMs }
                updateState {
                    copy(
                        recurrenceType = intent.type,
                        selectedWeekdays = if (selectedWeekdays.isEmpty()) {
                            setOf(cal.get(Calendar.DAY_OF_WEEK))
                        } else {
                            selectedWeekdays
                        },
                        selectedDayOfMonth = if (intent.type == ScheduleRecurrenceType.MONTHLY) {
                            cal.get(Calendar.DAY_OF_MONTH)
                        } else {
                            selectedDayOfMonth
                        },
                        errorMessage = null
                    )
                }
                recomputeSchedulePreview()
            }
            CheckoutIntent.OpenDatePicker -> updateState { copy(showDatePicker = true) }
            CheckoutIntent.DismissDatePicker -> updateState { copy(showDatePicker = false) }
            is CheckoutIntent.ConfirmDateSelected -> applySelectedDate(intent.utcDateMillis)
            CheckoutIntent.OpenTimePicker -> updateState { copy(showTimePicker = true) }
            CheckoutIntent.DismissTimePicker -> updateState { copy(showTimePicker = false) }
            is CheckoutIntent.ConfirmTimeSelected -> applySelectedTime(intent.hourOfDay, intent.minute)
            is CheckoutIntent.ToggleWeekday -> {
                val updated = state.value.selectedWeekdays.toMutableSet().apply {
                    if (contains(intent.calendarDayOfWeek) && size > 1) {
                        remove(intent.calendarDayOfWeek)
                    } else {
                        add(intent.calendarDayOfWeek)
                    }
                }
                updateState { copy(selectedWeekdays = updated) }
                recomputeSchedulePreview()
            }
            is CheckoutIntent.SetDayOfMonth -> {
                updateState { copy(selectedDayOfMonth = intent.day.coerceIn(1, 31)) }
                recomputeSchedulePreview()
            }
            is CheckoutIntent.SelectEndCondition -> {
                val defaultEnd = state.value.endDateUtcMs
                    ?: (state.value.scheduledAtUtcMs + 30L * 86_400_000L)
                updateState {
                    copy(
                        endConditionType = intent.endCondition,
                        endDateUtcMs = if (intent.endCondition == ScheduleEndConditionType.ON_DATE) defaultEnd else endDateUtcMs
                    )
                }
                recomputeSchedulePreview()
            }
            CheckoutIntent.OpenEndDatePicker -> updateState { copy(showEndDatePicker = true) }
            CheckoutIntent.DismissEndDatePicker -> updateState { copy(showEndDatePicker = false) }
            is CheckoutIntent.ConfirmEndDateSelected -> {
                updateState {
                    copy(
                        endDateUtcMs = normalizeDatePickerUtcToLocalDay(intent.utcDateMillis),
                        showEndDatePicker = false
                    )
                }
                recomputeSchedulePreview()
            }
            is CheckoutIntent.SetMaxOccurrences -> {
                updateState { copy(maxOccurrences = intent.count.coerceIn(1, 100)) }
                recomputeSchedulePreview()
            }
            is CheckoutIntent.SelectPriceConsentPolicy -> {
                updateState { copy(priceConsentPolicy = intent.policy) }
            }
        }
    }

    /** Loads the restaurant's opening hours for scheduled-order validation. */
    private fun loadOperatingHours(restaurantId: String) {
        viewModelScope.launch {
            val hours = getRestaurantOperatingHours(restaurantId)
            updateState { copy(operatingHours = hours) }
            recomputeSchedulePreview()
        }
    }

    /** Updates the scheduled date while preserving the currently selected local time. */
    private fun applySelectedDate(pickerUtcMillis: Long) {
        val currentLocal = Calendar.getInstance().apply { timeInMillis = state.value.scheduledAtUtcMs }
        val utcPicker = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = pickerUtcMillis
        }
        val merged = Calendar.getInstance().apply {
            set(Calendar.YEAR, utcPicker.get(Calendar.YEAR))
            set(Calendar.MONTH, utcPicker.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcPicker.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, currentLocal.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, currentLocal.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        updateState {
            copy(
                scheduledAtUtcMs = merged.timeInMillis,
                selectedDayOfMonth = merged.get(Calendar.DAY_OF_MONTH),
                showDatePicker = false,
                errorMessage = null
            )
        }
        recomputeSchedulePreview()
    }

    /** Updates the scheduled time while preserving the selected date. */
    private fun applySelectedTime(hourOfDay: Int, minute: Int) {
        val merged = Calendar.getInstance().apply {
            timeInMillis = state.value.scheduledAtUtcMs
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        updateState {
            copy(
                scheduledAtUtcMs = merged.timeInMillis,
                showTimePicker = false,
                errorMessage = null
            )
        }
        recomputeSchedulePreview()
    }

    /** Converts the date picker's UTC timestamp into the chosen local calendar day. */
    private fun normalizeDatePickerUtcToLocalDay(pickerUtcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = pickerUtcMillis
        }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, utc.get(Calendar.YEAR))
            set(Calendar.MONTH, utc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Recalculates the next placement time, recurrence summary, and schedule validation messages. */
    private fun recomputeSchedulePreview() {
        val current = state.value
        val rule = current.toRecurrenceRule()
        val now = System.currentTimeMillis()
        val effectiveFirst = if (rule.isRecurring) {
            calculateNextOccurrence(
                rule = rule,
                baseScheduledAtUtcMs = current.scheduledAtUtcMs,
                afterUtcMs = now,
                completedOccurrences = 0
            ) ?: current.scheduledAtUtcMs
        } else {
            current.scheduledAtUtcMs
        }
        val errors = calculateNextOccurrence.validateScheduleConfiguration(
            scheduledAtUtcMs = effectiveFirst,
            rule = rule,
            operatingHours = current.operatingHours,
            nowUtcMs = now
        )
        val summary = calculateNextOccurrence.formatRecurrenceSummary(rule)
        val confirmationSentence = formatScheduledPlacement.formatConfirmationSentence(
            placementUtcMs = effectiveFirst,
            timezoneId = rule.timezoneId
        )
        updateState {
            copy(
                scheduleValidationErrors = errors,
                scheduleSummaryLabel = summary,
                scheduleConfirmationSentence = confirmationSentence
            )
        }
    }

    /** Validates and saves a scheduled order, then opens its confirmation screen. */
    private fun confirmScheduledOrder() {
        if (inFlightSubmit) return
        val current = state.value
        val address = current.selectedAddress
        val restaurant = current.restaurant
        if (address == null) {
            updateState { copy(errorMessage = "Please select a delivery address first.") }
            return
        }
        if (!address.isServiceable) {
            updateState { copy(errorMessage = "Selected address (${address.city}) is not serviceable.") }
            return
        }
        if (restaurant == null || current.items.isEmpty()) {
            updateState { copy(errorMessage = "Your cart is empty.") }
            return
        }

        recomputeSchedulePreview()
        val refreshed = state.value
        if (refreshed.scheduleValidationErrors.isNotEmpty()) {
            updateState { copy(errorMessage = refreshed.scheduleValidationErrors.first()) }
            return
        }

        inFlightSubmit = true
        viewModelScope.launch {
            try {
                updateState { copy(isSubmitting = true, errorMessage = null) }
                val params = CreateScheduledOrderParams(
                    restaurantId = restaurant.id,
                    addressId = address.id,
                    paymentMethod = refreshed.selectedPaymentMethod,
                    couponCode = refreshed.appliedCouponCode,
                    scheduledAtUtcMs = refreshed.scheduledAtUtcMs,
                    recurrenceRule = refreshed.toRecurrenceRule(),
                    priceConsentPolicy = refreshed.priceConsentPolicy
                )
                when (val res = createScheduledOrder(params)) {
                    is AppResult.Success -> {
                        updateState { copy(isSubmitting = false) }
                        emitEffect(CheckoutEffect.NavigateToScheduleConfirmation(res.data.id))
                    }
                    is AppResult.Error -> {
                        updateState {
                            copy(
                                isSubmitting = false,
                                errorMessage = res.error.userMessage
                            )
                        }
                    }
                }
            } finally {
                inFlightSubmit = false
            }
        }
    }

    /** Saves the map-adjusted delivery address and selects it for checkout. */
    private fun applyAdjustedPin(pin: SelectedMapPin) {
        viewModelScope.launch {
            val currentAddr = state.value.selectedAddress
            val updated = DeliveryAddress(
                id = currentAddr?.id.orEmpty(),
                label = currentAddr?.label ?: AddressLabel.OTHER,
                recipientName = currentAddr?.recipientName ?: "Aarav Sharma",
                line1 = pin.line1,
                landmark = pin.landmark,
                city = pin.city,
                pincode = pin.pincode,
                deliveryInstructions = currentAddr?.deliveryInstructions.orEmpty(),
                latitude = pin.latitude,
                longitude = pin.longitude,
                isDefault = true,
                isServiceable = true
            )
            when (val res = saveAddress(updated)) {
                is AppResult.Success -> {
                    selectAddress(res.data.id)
                    updateState { copy(showQuickMapSheet = false) }
                    emitEffect(CheckoutEffect.ShowMessage("Delivery pin updated to ${res.data.line1}"))
                }
                is AppResult.Error -> {
                    updateState { copy(showQuickMapSheet = false) }
                    emitEffect(CheckoutEffect.ShowMessage(res.error.userMessage))
                }
            }
        }
    }

    /** Starts or resumes checkout and submits the order using its retry-safe attempt key. */
    private fun placeOrder() {
        if (inFlightSubmit) return
        val current = state.value
        val address = current.selectedAddress
        if (address == null) {
            updateState { copy(errorMessage = "Please select a delivery address first.") }
            return
        }
        if (!address.isServiceable) {
            updateState { copy(errorMessage = "Selected address (${address.city}) is not serviceable.") }
            return
        }
        if (!connectivityObserver.isCurrentlyOnline()) {
            updateState {
                copy(errorMessage = "You're offline. Your cart is safely saved — reconnect to place your order.")
            }
            return
        }

        inFlightSubmit = true
        viewModelScope.launch {
            try {
                updateState { copy(isSubmitting = true, errorMessage = null) }
                val attemptRes = startOrResumeCheckout(
                    addressId = address.id,
                    paymentMethod = current.selectedPaymentMethod,
                    couponCode = current.appliedCouponCode
                )
                val attempt = when (attemptRes) {
                    is AppResult.Success -> attemptRes.data
                    is AppResult.Error -> {
                        updateState { copy(isSubmitting = false, errorMessage = attemptRes.error.userMessage) }
                        return@launch
                    }
                }

                when (val orderRes = submitIdempotentOrder(attempt)) {
                    is AppResult.Success -> {
                        updateState { copy(isSubmitting = false) }
                        emitEffect(
                            CheckoutEffect.NavigateToPayment(
                                orderId = orderRes.data.id,
                                idempotencyKey = attempt.idempotencyKey
                            )
                        )
                    }
                    is AppResult.Error -> {
                        updateState { copy(isSubmitting = false, errorMessage = orderRes.error.userMessage) }
                    }
                }
            } finally {
                inFlightSubmit = false
            }
        }
    }
}
