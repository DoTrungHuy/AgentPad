package com.agentpad.app.policy

import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.tool.ToolRegistry

/**
 * Re-validates a plan against the local tool registry before persistence or execution.
 * Model output and stored JSON are never treated as a permission source.
 */
class PlanSanitizer(
    private val registry: ToolRegistry = ToolRegistry(),
    private val approvalPolicy: ApprovalPolicy = ApprovalPolicy(registry)
) {
    fun sanitize(plan: TaskPlan): TaskPlan {
        require(plan.actions.isNotEmpty()) { "计划不能为空" }
        require(plan.actions.size <= plan.maxSteps.coerceAtMost(MAX_ACTIONS)) {
            "计划步骤数量必须在 1 到 ${plan.maxSteps.coerceAtMost(MAX_ACTIONS)} 之间"
        }
        val actions = plan.actions.map { sanitizeAction(it) }
        return plan.copy(actions = actions)
    }

    fun sanitizeAction(action: PlannedAction): PlannedAction {
        val tool = action.tool.trim()
        require(tool.isNotEmpty()) { "计划步骤缺少工具名称" }
        require(registry.isAvailable(tool)) {
            if (registry.descriptor(tool)?.availability?.name == "PLANNED") {
                "工具尚未在当前版本启用：$tool"
            } else {
                "计划包含未知或禁止的工具：$tool"
            }
        }
        val required = registry.requiredArguments(tool)
        val missing = required.filter { key ->
            action.arguments[key].orEmpty().trim().isEmpty()
        }
        require(missing.isEmpty()) {
            "工具 $tool 缺少参数：${missing.joinToString(", ")}"
        }
        validateToolArguments(tool, action.arguments)
        val normalized = approvalPolicy.normalize(action.copy(tool = tool))
        require(normalized.risk != RiskLevel.FORBIDDEN) {
            "计划包含永久禁止的操作：$tool"
        }
        return normalized
    }

    private fun validateToolArguments(tool: String, arguments: Map<String, String>) {
        when (tool) {
            "open_url" -> {
                val url = arguments["url"].orEmpty().trim()
                require(url.startsWith("https://", ignoreCase = true)) {
                    "网址必须是有效的 HTTPS 地址"
                }
            }
            "launch_app" -> {
                val packageName = arguments["package"].orEmpty().trim()
                require(packageName.matches(PACKAGE_NAME)) {
                    "应用包名格式无效"
                }
            }
            "share_preview" -> {
                require(arguments["text"].orEmpty().isNotBlank()) {
                    "没有可分享的内容"
                }
            }
        }
    }

    private companion object {
        const val MAX_ACTIONS = 8
        val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
