package com.agentpad.app.agent

import com.agentpad.app.domain.ApprovalScope
import com.agentpad.app.domain.ApprovalToken
import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ToolResult
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.policy.PlanSanitizer
import kotlinx.coroutines.CancellationException

enum class RunMode {
    CAUTIOUS,
    STANDARD,
    EFFICIENT
}

sealed class LoopEvent {
    data class Message(val text: String) : LoopEvent()
    data class ToolStarted(val action: PlannedAction) : LoopEvent()
    data class ToolFinished(val result: ToolResult) : LoopEvent()
    data class NeedApproval(val plan: TaskPlan, val missingActionIds: List<String>) : LoopEvent()
    data class Finished(val summary: String) : LoopEvent()
    data class Failed(val message: String) : LoopEvent()
    data class Cancelled(val message: String = "已取消") : LoopEvent()
}

/**
 * Sequential tool loop over a sanitized plan with live events.
 * Consumes ACTION tokens per action; TASK token once for the run.
 */
class AgentLoop(
    private val approvalPolicy: ApprovalPolicy,
    private val sanitizer: PlanSanitizer,
    private val approvalService: ApprovalService,
    private val toolRunner: ToolRunner
) {
    suspend fun runPlan(
        plan: TaskPlan,
        mode: RunMode,
        tokens: Map<String, ApprovalToken>,
        now: Long,
        isCancelled: () -> Boolean = { false },
        onEvent: suspend (LoopEvent) -> Unit
    ): LoopOutcome {
        val prepared = try {
            sanitizer.sanitize(plan)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            onEvent(LoopEvent.Failed(failure.message ?: "计划无效"))
            return LoopOutcome.Failed(failure.message ?: "计划无效")
        }
        var mutableTokens = tokens.toMutableMap()
        val observations = mutableListOf<String>()
        var taskTokenConsumed = false

        for (action in prepared.actions.take(prepared.maxSteps)) {
            if (isCancelled()) {
                onEvent(LoopEvent.Cancelled())
                return LoopOutcome.Cancelled
            }
            val normalized = sanitizer.sanitizeAction(action)
            val scope = approvalPolicy.requiredScope(normalized)
            val needsUser = when (mode) {
                RunMode.CAUTIOUS -> normalized.risk != RiskLevel.READ_ONLY
                RunMode.STANDARD, RunMode.EFFICIENT -> scope != ApprovalScope.NONE
            }
            if (needsUser) {
                val missing = approvalService.missingApprovals(mutableTokens, prepared, now)
                val taskOk = scope != ApprovalScope.TASK ||
                    approvalService.isTaskApproved(mutableTokens, prepared, now)
                val actionOk = scope != ApprovalScope.ACTION ||
                    approvalService.isActionApproved(mutableTokens, prepared, normalized.id, now)
                if (missing.isNotEmpty() || !taskOk || !actionOk) {
                    onEvent(
                        LoopEvent.NeedApproval(
                            prepared,
                            missing.ifEmpty { listOf(normalized.id) }
                        )
                    )
                    return LoopOutcome.PausedForApproval(prepared, mutableTokens, observations)
                }
                when (scope) {
                    ApprovalScope.TASK -> {
                        if (!taskTokenConsumed) {
                            mutableTokens =
                                approvalService.consumeTaskToken(mutableTokens, prepared)
                                    .toMutableMap()
                            taskTokenConsumed = true
                        }
                    }
                    ApprovalScope.ACTION -> {
                        mutableTokens =
                            approvalService.consumeActionToken(mutableTokens, normalized.id)
                                .toMutableMap()
                    }
                    ApprovalScope.NONE -> Unit
                }
            }
            onEvent(LoopEvent.ToolStarted(normalized))
            val result = try {
                toolRunner.run(normalized)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                val failed = ToolResult(
                    actionId = normalized.id,
                    success = false,
                    summary = failure.message ?: "工具执行失败",
                    errorCode = "EXCEPTION"
                )
                onEvent(LoopEvent.ToolFinished(failed))
                onEvent(LoopEvent.Failed(failed.summary))
                return LoopOutcome.Failed(failed.summary)
            }
            onEvent(LoopEvent.ToolFinished(result))
            observations += "${normalized.tool}: ${result.summary}"
            if (!result.success) {
                onEvent(LoopEvent.Failed(result.summary))
                return LoopOutcome.Failed(result.summary)
            }
        }
        if (isCancelled()) {
            onEvent(LoopEvent.Cancelled())
            return LoopOutcome.Cancelled
        }
        val summary = observations.lastOrNull() ?: "任务步骤已完成"
        onEvent(LoopEvent.Finished(summary))
        return LoopOutcome.Completed(summary, observations, mutableTokens)
    }
}

sealed class LoopOutcome {
    data class Completed(
        val summary: String,
        val observations: List<String>,
        val tokens: Map<String, ApprovalToken> = emptyMap()
    ) : LoopOutcome()

    data class PausedForApproval(
        val plan: TaskPlan,
        val tokens: Map<String, ApprovalToken>,
        val observations: List<String>
    ) : LoopOutcome()

    data class Failed(val message: String) : LoopOutcome()
    data object Cancelled : LoopOutcome()
}
