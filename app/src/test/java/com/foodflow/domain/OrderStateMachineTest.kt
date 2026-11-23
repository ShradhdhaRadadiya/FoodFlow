package com.foodflow.domain

import com.foodflow.domain.model.OrderStatus
import com.foodflow.domain.usecase.ValidateOrderTransitionUseCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStateMachineTest {

    private val validateTransition = ValidateOrderTransitionUseCase()

    @Test
    fun `happy path sequential lifecycle transitions are all valid`() {
        val sequence = listOf(
            OrderStatus.PLACED,
            OrderStatus.ACCEPTED,
            OrderStatus.PREPARING,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.PARTNER_ASSIGNED,
            OrderStatus.PICKED_UP,
            OrderStatus.DELIVERED
        )
        for (i in 0 until sequence.lastIndex) {
            assertTrue(
                "Expected ${sequence[i]} -> ${sequence[i + 1]} to be valid",
                validateTransition(sequence[i], sequence[i + 1], strictSequential = true)
            )
            assertTrue(
                "Expected ${sequence[i]} -> ${sequence[i + 1]} to be valid in non-strict mode",
                validateTransition(sequence[i], sequence[i + 1], strictSequential = false)
            )
        }
    }

    @Test
    fun `direct jump from PLACED to DELIVERED is strictly blocked`() {
        assertFalse(validateTransition(OrderStatus.PLACED, OrderStatus.DELIVERED, strictSequential = false))
        assertFalse(validateTransition(OrderStatus.PLACED, OrderStatus.DELIVERED, strictSequential = true))
    }

    @Test
    fun `backward and same-status transitions are rejected`() {
        assertFalse(validateTransition(OrderStatus.PREPARING, OrderStatus.ACCEPTED))
        assertFalse(validateTransition(OrderStatus.PICKED_UP, OrderStatus.PREPARING))
        assertFalse(validateTransition(OrderStatus.ACCEPTED, OrderStatus.ACCEPTED))
    }

    @Test
    fun `cancellation allowed before pickup and blocked once picked up or delivered`() {
        assertTrue(validateTransition(OrderStatus.PLACED, OrderStatus.CANCELLED))
        assertTrue(validateTransition(OrderStatus.ACCEPTED, OrderStatus.CANCELLED))
        assertTrue(validateTransition(OrderStatus.PREPARING, OrderStatus.CANCELLED))
        assertTrue(validateTransition(OrderStatus.PARTNER_ASSIGNED, OrderStatus.CANCELLED))

        assertFalse(validateTransition(OrderStatus.PICKED_UP, OrderStatus.CANCELLED))
        assertFalse(validateTransition(OrderStatus.DELIVERED, OrderStatus.CANCELLED))
    }

    @Test
    fun `terminal states cannot transition to any other state`() {
        OrderStatus.entries.forEach { target ->
            assertFalse(validateTransition(OrderStatus.DELIVERED, target))
            assertFalse(validateTransition(OrderStatus.CANCELLED, target))
        }
    }
}
