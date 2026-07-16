package com.agentpad.app.agent

import com.agentpad.app.domain.AgentTurn
import com.agentpad.app.domain.TurnStatus

/**
 * Pure helpers for turn status transitions that UI and orchestration share.
 */
class TurnLifecycle {
    fun isInFlight(turn: AgentTurn?): Boolean =
        turn?.status in IN_FLIGHT

    fun canLeave(turn: AgentTurn?, workActive: Boolean): Boolean =
        !workActive && !isInFlight(turn)

    fun canExecute(turn: AgentTurn?): Boolean {
        if (turn?.plan == null) return false
        return turn.status in EXECUTABLE
    }

    fun canResumeInterrupted(turn: AgentTurn?): Boolean =
        turn?.status == TurnStatus.INTERRUPTED && turn.plan != null

    fun canRetryAfterFailure(turn: AgentTurn?): Boolean =
        turn?.status == TurnStatus.FAILED && turn.plan != null

    private companion object {
        val IN_FLIGHT = setOf(
            TurnStatus.PLANNING,
            TurnStatus.RUNNING,
            TurnStatus.VERIFYING
        )
        // FAILED keeps the plan so users can re-approve and retry without regenerating.
        val EXECUTABLE = setOf(
            TurnStatus.AWAITING_APPROVAL,
            TurnStatus.INTERRUPTED,
            TurnStatus.FAILED
        )
    }
}
