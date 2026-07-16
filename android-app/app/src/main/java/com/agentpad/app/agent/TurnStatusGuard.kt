package com.agentpad.app.agent

import com.agentpad.app.domain.TurnStatus

/**
 * Prevents terminal turn statuses from being overwritten by late async outcomes
 * (e.g. cancel racing with execute completion/failure).
 */
class TurnStatusGuard {
    fun canTransition(from: TurnStatus, to: TurnStatus): Boolean {
        if (from == to) return true
        if (from in TERMINAL && to != from) return false
        return true
    }

    fun shouldIgnoreOutcome(current: TurnStatus): Boolean = current in TERMINAL

    private companion object {
        val TERMINAL = setOf(
            TurnStatus.COMPLETED,
            TurnStatus.FAILED,
            TurnStatus.CANCELLED,
            TurnStatus.SUPERSEDED
        )
    }
}
