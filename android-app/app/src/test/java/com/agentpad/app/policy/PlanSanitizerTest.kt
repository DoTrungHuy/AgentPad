package com.agentpad.app.policy

import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanSanitizerTest {
    private val sanitizer = PlanSanitizer()

    @Test
    fun upgradesModelDowngradedRisk() {
        val plan = plan(
            PlannedAction(
                title = "上传",
                description = "",
                tool = "upload_document_for_summary",
                risk = RiskLevel.READ_ONLY,
                reversible = false
            )
        )

        val sanitized = sanitizer.sanitize(plan)
        assertEquals(RiskLevel.ACTION_APPROVAL, sanitized.actions.single().risk)
    }

    @Test
    fun rejectsPlannedButUnimplementedTools() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizer.sanitize(
                plan(
                    PlannedAction(
                        title = "运行",
                        description = "",
                        tool = "run_command",
                        risk = RiskLevel.ACTION_APPROVAL,
                        reversible = false
                    )
                )
            )
        }
    }

    @Test
    fun rejectsForbiddenTools() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizer.sanitize(
                plan(
                    PlannedAction(
                        title = "支付",
                        description = "",
                        tool = "payment",
                        risk = RiskLevel.READ_ONLY,
                        reversible = false
                    )
                )
            )
        }
    }

    @Test
    fun rejectsOpenUrlWithoutHttps() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizer.sanitize(
                plan(
                    PlannedAction(
                        title = "打开",
                        description = "",
                        tool = "open_url",
                        arguments = mapOf("url" to "http://example.com"),
                        risk = RiskLevel.TASK_APPROVAL,
                        reversible = true
                    )
                )
            )
        }
    }

    @Test
    fun rejectsMissingRequiredArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            sanitizer.sanitize(
                plan(
                    PlannedAction(
                        title = "启动",
                        description = "",
                        tool = "launch_app",
                        arguments = emptyMap(),
                        risk = RiskLevel.TASK_APPROVAL,
                        reversible = true
                    )
                )
            )
        }
    }

    @Test
    fun acceptsValidOpenUrl() {
        val sanitized = sanitizer.sanitize(
            plan(
                PlannedAction(
                    title = "打开",
                    description = "",
                    tool = "open_url",
                    arguments = mapOf("url" to "https://example.com"),
                    risk = RiskLevel.TASK_APPROVAL,
                    reversible = true
                )
            )
        )
        assertEquals("open_url", sanitized.actions.single().tool)
    }

    private fun plan(vararg actions: PlannedAction) = TaskPlan(
        id = "plan-1",
        goal = "goal",
        title = "title",
        summary = "summary",
        actions = actions.toList()
    )
}
