package com.agentpad.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBudgetTest {
    @Test
    fun allowsProviderCallsUntilLimit() {
        val budget = RuntimeBudget(maxProviderCalls = 2, maxWallTimeMillis = 60_000)
        budget.consumeProviderCall()
        budget.consumeProviderCall()
        assertThrows(IllegalStateException::class.java) {
            budget.consumeProviderCall()
        }
    }

    @Test
    fun detectsWallTimeExceeded() {
        val budget = RuntimeBudget(
            maxProviderCalls = 10,
            maxWallTimeMillis = 100,
            nowMillis = { 1_000L }
        )
        budget.start(now = 1_000L)
        assertFalse(budget.isExpired(now = 1_050L))
        assertTrue(budget.isExpired(now = 1_200L))
        assertThrows(IllegalStateException::class.java) {
            budget.checkNotExpired(now = 1_200L)
        }
    }

    @Test
    fun resetClearsCounters() {
        val budget = RuntimeBudget(maxProviderCalls = 1, maxWallTimeMillis = 60_000)
        budget.consumeProviderCall()
        budget.reset()
        budget.consumeProviderCall()
    }
}
