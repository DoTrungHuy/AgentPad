package com.agentpad.app.agent

import com.agentpad.app.domain.AgentTurn
import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ToolResult
import com.agentpad.app.policy.PlanSanitizer

fun interface ToolRunner {
    suspend fun run(action: PlannedAction): ToolResult
}

/**
 * Executes a sanitized plan step-by-step with audit hooks and post-run verification.
 * Android-specific tool IO is injected via [toolRunner].
 */
class ExecutionEngine(
    private val sanitizer: PlanSanitizer,
    private val toolRunner: ToolRunner,
    private val audit: suspend (
        taskId: String,
        actionId: String?,
        eventType: String,
        summary: String
    ) -> Unit,
    private val markVerifying: suspend (AgentTurn) -> Unit,
    private val resultExtractor: (ToolResult, PlannedAction) -> String? = { _, _ -> null }
) {
    fun prepare(plan: TaskPlan): TaskPlan = sanitizer.sanitize(plan)

    suspend fun execute(turn: AgentTurn, plan: TaskPlan): String {
        val prepared = sanitizer.sanitize(plan)
        var finalResult = DEFAULT_RESULT
        val stepResults = mutableListOf<ToolResult>()

        for (action in prepared.actions.take(prepared.maxSteps)) {
            val normalized = sanitizer.sanitizeAction(action)
            audit(turn.id, normalized.id, "TOOL_STARTED", "开始执行 ${normalized.tool}")
            val result = toolRunner.run(normalized)
            stepResults += result
            audit(
                turn.id,
                normalized.id,
                if (result.success) "TOOL_SUCCEEDED" else "TOOL_FAILED",
                result.summary
            )
            if (!result.success) {
                error(result.summary)
            }
            resultExtractor(result, normalized)?.let { finalResult = it }
        }

        markVerifying(turn)
        require(stepResults.isNotEmpty()) { "没有可验证的执行结果" }
        require(stepResults.all { it.success }) { "存在未成功的步骤，不能完成任务" }
        return verify(prepared, finalResult)
    }

    fun verify(plan: TaskPlan, result: String): String {
        require(result.isNotBlank()) { "验证失败：任务结果为空" }
        if (plan.actions.any { it.tool in setOf("upload_document_for_summary", "analyze_image") }) {
            require(result != DEFAULT_RESULT) {
                "验证失败：摘要/图像分析工具未产生结果"
            }
        }
        return result
    }

    private companion object {
        const val DEFAULT_RESULT = "任务步骤已完成"
    }
}
