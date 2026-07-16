package com.agentpad.app.provider

import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.policy.PlanSanitizer
import org.json.JSONException
import org.json.JSONObject

class PlanParser(
    private val policy: ApprovalPolicy,
    private val sanitizer: PlanSanitizer = PlanSanitizer(policy.registry(), policy)
) {
    fun parse(goal: String, raw: String): TaskPlan {
        try {
            val root = JSONObject(extractJson(raw))
            val actionsJson = root.getJSONArray("actions")
            require(actionsJson.length() in 1..MAX_ACTIONS) {
                "计划步骤数量必须在 1 到 $MAX_ACTIONS 之间"
            }
            val actions = buildList {
                for (index in 0 until actionsJson.length()) {
                    val item = actionsJson.getJSONObject(index)
                    val tool = item.getString("tool").trim()
                    require(tool.isNotEmpty()) { "计划步骤缺少工具名称" }
                    require(policy.plannableTools().contains(tool)) {
                        "计划包含未知、未启用或禁止的工具：$tool"
                    }

                    val arguments = when {
                        !item.has("arguments") || item.isNull("arguments") -> null
                        else -> item.getJSONObject("arguments")
                    }
                    val argumentMap = buildMap {
                        arguments?.keys()?.forEach { key ->
                            val value = arguments.get(key)
                            require(value is String || value is Number || value is Boolean) {
                                "工具参数必须是字符串、数字或布尔值"
                            }
                            put(key, value.toString())
                        }
                    }
                    val action = policy.normalize(
                        PlannedAction(
                            title = item.optString("title", tool).take(MAX_TEXT),
                            description = item.optString("description").take(MAX_TEXT),
                            tool = tool,
                            arguments = argumentMap,
                            risk = parseRisk(item.optString("risk")),
                            reversible = item.optBoolean("reversible", false)
                        )
                    )
                    require(action.risk != RiskLevel.FORBIDDEN) {
                        "计划包含永久禁止的操作：$tool"
                    }
                    add(action)
                }
            }
            val plan = TaskPlan(
                goal = goal.take(MAX_GOAL),
                title = root.optString("title", "新任务").take(MAX_TEXT),
                summary = root.optString("summary").take(MAX_TEXT),
                actions = actions,
                stopCondition = root.optString(
                    "stopCondition",
                    "目标完成、用户取消或出现无法安全处理的错误"
                ).take(MAX_TEXT)
            )
            return sanitizer.sanitize(plan)
        } catch (failure: JSONException) {
            throw IllegalArgumentException("模型返回的任务计划格式无效", failure)
        }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型未返回 JSON 任务计划" }
        return trimmed.substring(start, end + 1)
    }

    private fun parseRisk(value: String): RiskLevel =
        runCatching { RiskLevel.valueOf(value.uppercase()) }.getOrDefault(RiskLevel.ACTION_APPROVAL)

    private companion object {
        const val MAX_ACTIONS = 8
        const val MAX_TEXT = 500
        const val MAX_GOAL = 2_000
    }
}
