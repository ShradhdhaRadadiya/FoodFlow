package com.foodflow.data.mock

import com.foodflow.core.connectivity.ConnectivityObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class SimulatedPaymentOutcome {
    SUCCESS,
    FAILED,
    PENDING
}

enum class SimulatedNetworkFault {
    NONE,
    TIMEOUT,
    TIMEOUT_AFTER_SERVER_WRITE,
    SERVER_ERROR_500,
    INVALID_JSON_RESPONSE
}

/**
 * Deterministic configuration driving [LocalMockBackend] behaviour.
 * Controlled from the Developer Scenario Lab and directly from unit/integration tests
 * without scattering mock flags into ViewModels.
 */
data class MockScenarioConfig(
    val forcedOffline: Boolean = false,
    val networkFault: SimulatedNetworkFault = SimulatedNetworkFault.NONE,
    val paymentOutcome: SimulatedPaymentOutcome = SimulatedPaymentOutcome.SUCCESS,
    val restaurantRejectsOrder: Boolean = false,
    val unavailableFoodIds: Set<String> = emptySet(),
    val priceOverrides: Map<String, Double> = emptyMap(),
    val delayedOrderActive: Boolean = false,
    val simulatedLatencyMs: Long = 280L,
    val otpExpiredMode: Boolean = false
)

@Singleton
class MockScenarioController @Inject constructor(
    private val connectivityObserver: ConnectivityObserver
) {
    private val _config = MutableStateFlow(MockScenarioConfig())
    val config: StateFlow<MockScenarioConfig> = _config.asStateFlow()

    /** Returns the current mock settings snapshot. */
    fun current(): MockScenarioConfig = _config.value

    /** Turns simulated offline mode on or off for mock requests. */
    fun setOffline(offline: Boolean) {
        connectivityObserver.setSimulatedOffline(offline)
        _config.update { it.copy(forcedOffline = offline) }
    }

    /** Selects the network failure that mock requests should simulate. */
    fun setNetworkFault(fault: SimulatedNetworkFault) {
        _config.update { it.copy(networkFault = fault) }
    }

    /** Sets the payment result returned by the mock payment flow. */
    fun setPaymentOutcome(outcome: SimulatedPaymentOutcome) {
        _config.update { it.copy(paymentOutcome = outcome) }
    }

    /** Controls whether the mock restaurant accepts new orders. */
    fun setRestaurantRejectsOrder(reject: Boolean) {
        _config.update { it.copy(restaurantRejectsOrder = reject) }
    }

    /** Adds or removes a food item from the mock unavailable-items list. */
    fun toggleUnavailableFood(foodId: String) {
        _config.update { state ->
            val updated = if (foodId in state.unavailableFoodIds) {
                state.unavailableFoodIds - foodId
            } else {
                state.unavailableFoodIds + foodId
            }
            state.copy(unavailableFoodIds = updated)
        }
    }

    /** Sets or clears a mock price override for a food item. */
    fun overrideFoodPrice(foodId: String, newPrice: Double?) {
        _config.update { state ->
            val map = state.priceOverrides.toMutableMap()
            if (newPrice == null) map.remove(foodId) else map[foodId] = newPrice
            state.copy(priceOverrides = map)
        }
    }

    /** Controls whether mock scheduled-order placement is delayed. */
    fun setDelayedOrder(delayed: Boolean) {
        _config.update { it.copy(delayedOrderActive = delayed) }
    }

    /** Sets the delay, in milliseconds, that mock requests should simulate. */
    fun setLatencyMs(latencyMs: Long) {
        _config.update { it.copy(simulatedLatencyMs = latencyMs.coerceAtLeast(0L)) }
    }

    /** Controls whether the mock sign-in flow treats the code as expired. */
    fun setOtpExpiredMode(expired: Boolean) {
        _config.update { it.copy(otpExpiredMode = expired) }
    }

    /** Restores all mock settings and simulated connectivity to their defaults. */
    fun resetAllScenarios() {
        connectivityObserver.setSimulatedOffline(false)
        _config.value = MockScenarioConfig()
    }
}
