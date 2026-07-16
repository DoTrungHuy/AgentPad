package com.agentpad.app.agent

import com.agentpad.app.domain.AgentTurn
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.TurnStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLifecycleTest {
    private val lifecycle = TurnLifecycle()

    @Test
    fun detectsInFlightStatuses() {
        assertTrue(lifecycle.isInFlight(turn(TurnStatus.PLANNING)))
        assertTrue(lifecycle.isInFlight(turn(TurnStatus.RUNNING)))
        assertTrue(lifecycle.isInFlight(turn(TurnStatus.VERIFYING)))
        assertFalse(lifecycle.isInFlight(turn(TurnStatus.AWAITING_APPROVAL)))
        assertFalse(lifecycle.isInFlight(turn(TurnStatus.INTERRUPTED)))
        assertFalse(lifecycle.isInFlight(null))
    }

    @Test
    fun interruptedWithPlanCanResumeAfterApproval() {
        val withPlan = turn(TurnStatus.INTERRUPTED).copy(
            plan = TaskPlan(
                id = "p",
                goal = "g",
                title = "t",
                summary = "s",
                actions = emptyList()
            )
        )
        assertTrue(lifecycle.canResumeInterrupted(withPlan))
        assertFalse(lifecycle.canResumeInterrupted(turn(TurnStatus.INTERRUPTED)))
        assertFalse(lifecycle.canResumeInterrupted(turn(TurnStatus.COMPLETED)))
    }

    @Test
    fun executableStatusesIncludeAwaitingAndInterrupted() {
        val plan = TaskPlan(
            id = "p",
            goal = "g",
            title = "t",
            summary = "s",
            actions = emptyList()
        )
        assertTrue(lifecycle.canExecute(turn(TurnStatus.AWAITING_APPROVAL).copy(plan = plan)))
        assertTrue(lifecycle.canExecute(turn(TurnStatus.INTERRUPTED).copy(plan = plan)))
        assertTrue(lifecycle.canExecute(turn(TurnStatus.FAILED).copy(plan = plan)))
        assertTrue(lifecycle.canRetryAfterFailure(turn(TurnStatus.FAILED).copy(plan = plan)))
        assertFalse(lifecycle.canExecute(turn(TurnStatus.AWAITING_APPROVAL)))
        assertFalse(lifecycle.canExecute(turn(TurnStatus.COMPLETED).copy(plan = plan)))
        assertFalse(lifecycle.canExecute(turn(TurnStatus.SUPERSEDED).copy(plan = plan)))
        assertFalse(lifecycle.canExecute(turn(TurnStatus.RUNNING).copy(plan = plan)))
        assertFalse(lifecycle.canExecute(turn(TurnStatus.CANCELLED).copy(plan = plan)))
    }

    private fun turn(status: TurnStatus) = AgentTurn(
        id = "t1",
        threadId = "th",
        ordinal = 1,
        goal = "g",
        plan = null,
        status = status,
        result = null
    )
}
