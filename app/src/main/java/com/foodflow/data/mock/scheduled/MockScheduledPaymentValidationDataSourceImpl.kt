package com.foodflow.data.mock.scheduled

import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.connectivity.ConnectivityObserver
import com.foodflow.data.mock.MockScenarioController
import com.foodflow.data.mock.SimulatedNetworkFault
import com.foodflow.data.mock.SimulatedPaymentOutcome
import com.foodflow.data.remote.api.ScheduledPaymentValidationDataSource
import com.foodflow.data.remote.dto.ScheduledPaymentValidationDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaceable mock implementation validating and authorizing payment for scheduled occurrences.
 * Honors [MockScenarioController.paymentOutcome] for deterministic failure/pending/success testing.
 */
@Singleton
class MockScheduledPaymentValidationDataSourceImpl @Inject constructor(
    private val scenarioController: MockScenarioController,
    private val connectivityObserver: ConnectivityObserver
) : ScheduledPaymentValidationDataSource {

    /** Simulates payment approval or failure for a scheduled-order occurrence. */
    override suspend fun validateAndAuthorizePayment(
        scheduleId: String,
        occurrenceIdempotencyKey: String,
        paymentMethod: String,
        amount: Double
    ): AppResult<ScheduledPaymentValidationDto> {
        val cfg = scenarioController.current()
        if (cfg.forcedOffline || !connectivityObserver.isCurrentlyOnline()) {
            return AppResult.Error(AppError.Offline())
        }
        if (cfg.networkFault == SimulatedNetworkFault.TIMEOUT) {
            return AppResult.Error(AppError.Timeout())
        }
        if (cfg.networkFault == SimulatedNetworkFault.SERVER_ERROR_500) {
            return AppResult.Error(AppError.ServerError(500))
        }

        if (paymentMethod.equals("COD", ignoreCase = true)) {
            return AppResult.Success(
                ScheduledPaymentValidationDto(
                    approved = true,
                    paymentStatus = "COD_DUE",
                    transactionId = "COD-${occurrenceIdempotencyKey.takeLast(6)}",
                    failureReason = null,
                    retryable = false
                )
            )
        }

        return when (cfg.paymentOutcome) {
            SimulatedPaymentOutcome.SUCCESS -> AppResult.Success(
                ScheduledPaymentValidationDto(
                    approved = true,
                    paymentStatus = "SUCCESS",
                    transactionId = "SCHED-TXN-${System.currentTimeMillis() % 100000}",
                    failureReason = null,
                    retryable = false
                )
            )
            SimulatedPaymentOutcome.PENDING -> AppResult.Success(
                ScheduledPaymentValidationDto(
                    approved = false,
                    paymentStatus = "PENDING",
                    transactionId = null,
                    failureReason = "Payment mandate authorization is pending confirmation from your bank.",
                    retryable = true
                )
            )
            SimulatedPaymentOutcome.FAILED -> AppResult.Success(
                ScheduledPaymentValidationDto(
                    approved = false,
                    paymentStatus = "FAILED",
                    transactionId = null,
                    failureReason = "Scheduled payment authorization was declined. Please check your payment method and reschedule.",
                    retryable = false
                )
            )
        }
    }
}
