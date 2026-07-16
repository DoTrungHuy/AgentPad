package com.agentpad.app.agent

import com.agentpad.app.domain.ApprovalScope
import com.agentpad.app.domain.ApprovalToken
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.policy.ApprovalTokenPolicy

/**
 * In-memory approval token lifecycle. Tokens are never persisted.
 */
class ApprovalService(
    private val approvalPolicy: ApprovalPolicy,
    private val tokenPolicy: ApprovalTokenPolicy = ApprovalTokenPolicy(approvalPolicy)
) {
    fun scopesFor(plan: TaskPlan): List<Pair<String, ApprovalScope>> =
        plan.actions.map { it.id to approvalPolicy.requiredScope(it) }

    fun approveTask(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan,
        now: Long,
        ttlMillis: Long
    ): Map<String, ApprovalToken> {
        val token = tokenPolicy.createTaskToken(plan, now, ttlMillis)
        return tokens + (tokenPolicy.taskTokenKey(plan.id) to token)
    }

    fun approveAction(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan,
        actionId: String,
        now: Long,
        ttlMillis: Long
    ): Map<String, ApprovalToken> {
        val action = plan.actions.firstOrNull { it.id == actionId } ?: return tokens
        val token = tokenPolicy.createActionToken(plan, action, now, ttlMillis)
        return tokens + (action.id to token)
    }

    fun isTaskApproved(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan,
        now: Long
    ): Boolean {
        val token = tokens[tokenPolicy.taskTokenKey(plan.id)]
        return tokenPolicy.isTaskValid(token, plan, now)
    }

    fun isActionApproved(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan,
        actionId: String,
        now: Long
    ): Boolean {
        val action = plan.actions.firstOrNull { it.id == actionId } ?: return false
        return tokenPolicy.isActionValid(tokens[actionId], plan, action, now)
    }

    fun missingApprovals(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan,
        now: Long
    ): List<String> =
        plan.actions.mapNotNull { action ->
            when (approvalPolicy.requiredScope(action)) {
                ApprovalScope.NONE -> null
                ApprovalScope.TASK ->
                    if (isTaskApproved(tokens, plan, now)) null else action.id
                ApprovalScope.ACTION ->
                    if (isActionApproved(tokens, plan, action.id, now)) null else action.id
            }
        }

    fun consume(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan
    ): Map<String, ApprovalToken> {
        var next = tokens
        next = consumeTaskToken(next, plan)
        plan.actions
            .filter { approvalPolicy.requiredScope(it) == ApprovalScope.ACTION }
            .forEach { action ->
                next = consumeActionToken(next, action.id)
            }
        return next
    }

    /** Consume only the task-level token (for TASK_APPROVAL tools). */
    fun consumeTaskToken(
        tokens: Map<String, ApprovalToken>,
        plan: TaskPlan
    ): Map<String, ApprovalToken> {
        val consumed = tokens.toMutableMap()
        val taskKey = tokenPolicy.taskTokenKey(plan.id)
        consumed[taskKey]?.let { token ->
            if (token.remainingUses > 0) {
                consumed[taskKey] = tokenPolicy.consume(token)
            }
        }
        return consumed
    }

    /** Consume a single action-level token. */
    fun consumeActionToken(
        tokens: Map<String, ApprovalToken>,
        actionId: String
    ): Map<String, ApprovalToken> {
        val consumed = tokens.toMutableMap()
        consumed[actionId]?.let { token ->
            if (token.remainingUses > 0) {
                consumed[actionId] = tokenPolicy.consume(token)
            }
        }
        return consumed
    }
}
