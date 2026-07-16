package com.agentpad.app.agent

import com.agentpad.app.domain.TurnStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStatusGuardTest {
    private val guard = TurnStatusGuard()

    @Test
    fun terminalStatusesCannotBeOverwrittenBySuccessOrFailure() {
        assertFalse(guard.canTransition(TurnStatus.CANCELLED, TurnStatus.COMPLETED))
        assertFalse(guard.canTransition(TurnStatus.CANCELLED, TurnStatus.FAILED))
        assertFalse(guard.canTransition(TurnStatus.COMPLETED, TurnStatus.FAILED))
        assertFalse(guard.canTransition(TurnStatus.SUPERSEDED, TurnStatus.RUNNING))
    }

    @Test
    fun runningCanCompleteOrFailOrCancel() {
        assertTrue(guard.canTransition(TurnStatus.RUNNING, TurnStatus.COMPLETED))
        assertTrue(guard.canTransition(TurnStatus.RUNNING, TurnStatus.FAILED))
        assertTrue(guard.canTransition(TurnStatus.RUNNING, TurnStatus.CANCELLED))
        assertTrue(guard.canTransition(TurnStatus.RUNNING, TurnStatus.VERIFYING))
    }

    @Test
    fun cancelWinsOverInFlightOutcomes() {
        assertTrue(guard.shouldIgnoreOutcome(TurnStatus.CANCELLED))
        assertTrue(guard.shouldIgnoreOutcome(TurnStatus.SUPERSEDED))
        assertFalse(guard.shouldIgnoreOutcome(TurnStatus.RUNNING))
    }
}
