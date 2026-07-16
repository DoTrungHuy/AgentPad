package com.agentpad.app.policy

import com.agentpad.app.domain.ApprovalScope
import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.tool.ToolRegistry
import java.security.MessageDigest

class ApprovalPolicy(
    private val registry: ToolRegistry = ToolRegistry()
) {
    fun registry(): ToolRegistry = registry

    fun knownTools(): Set<String> = registry.knownToolNames()

    /** Tools the model may include in a plan (AVAILABLE only). */
    fun plannableTools(): Set<String> = registry.availableToolNames()

    fun riskFor(tool: String): RiskLevel = registry.riskFor(tool)

    fun normalize(action: PlannedAction): PlannedAction {
        val localRisk = riskFor(action.tool)
        val effectiveRisk = maxOf(localRisk, action.risk)
        return action.copy(risk = effectiveRisk)
    }

    fun requiredScope(action: PlannedAction): ApprovalScope = when (normalize(action).risk) {
        RiskLevel.READ_ONLY -> ApprovalScope.NONE
        RiskLevel.TASK_APPROVAL -> ApprovalScope.TASK
        RiskLevel.ACTION_APPROVAL -> ApprovalScope.ACTION
        RiskLevel.FORBIDDEN -> ApprovalScope.ACTION
    }

    fun argumentDigest(action: PlannedAction): String {
        val canonical = buildString {
            append(action.tool)
            action.arguments.toSortedMap().forEach { (key, value) ->
                append('\u0000')
                append(key)
                append('=')
                append(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
