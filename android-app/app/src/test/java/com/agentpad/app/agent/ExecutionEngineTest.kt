package com.agentpad.app.agent

import com.agentpad.app.domain.AgentTurn
import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ToolResult
import com.agentpad.app.domain.TurnStatus
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.policy.PlanSanitizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEngineTest {
    private val policy = ApprovalPolicy()
    private val sanitizer = PlanSanitizer(policy.registry(), policy)

    @Test
    fun runsAllStepsAndVerifiesSuccess() = runBlocking {
        val events = mutableListOf<String>()
        val engine = ExecutionEngine(
            sanitizer = sanitizer,
            toolRunner = { action ->
                ToolResult(action.id, true, "ok-${action.tool}", evidence = action.tool)
            },
            audit = { _, actionId, type, summary ->
                events += "$type:${actionId.orEmpty()}:$summary"
            },
            markVerifying = { events += "VERIFYING" }
        )
        val plan = plan(
            PlannedAction(
                id = "s1",
                title = "检查",
                description = "",
                tool = "inspect_task",
                risk = RiskLevel.READ_ONLY,
                reversible = true
            )
        )
        val result = engine.execute(turn("t1"), plan)
        assertEquals("任务步骤已完成", result)
        assertTrue(events.any { it.startsWith("TOOL_STARTED") })
        assertTrue(events.any { it.startsWith("TOOL_SUCCEEDED") })
        assertTrue(events.contains("VERIFYING"))
    }

    @Test
    fun failsFastOnToolErrorAndDoesNotComplete() {
        val engine = ExecutionEngine(
            sanitizer = sanitizer,
            toolRunner = { action ->
                ToolResult(action.id, false, "boom", errorCode = "X")
            },
            audit = { _, _, _, _ -> },
            markVerifying = { error("should not verify") }
        )
        val plan = plan(
            PlannedAction(
                id = "s1",
                title = "打开",
                description = "",
                tool = "open_url",
                arguments = mapOf("url" to "https://example.com"),
                risk = RiskLevel.TASK_APPROVAL,
                reversible = true
            )
        )
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.execute(turn("t1"), plan) }
        }
        assertEquals("boom", error.message)
    }

    @Test
    fun rejectsPlannedToolsBeforeRunning() {
        val engine = ExecutionEngine(
            sanitizer = sanitizer,
            toolRunner = { error("should not run") },
            audit = { _, _, _, _ -> },
            markVerifying = {}
        )
        val plan = plan(
            PlannedAction(
                id = "s1",
                title = "shell",
                description = "",
                tool = "run_command",
                risk = RiskLevel.ACTION_APPROVAL,
                reversible = false
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.execute(turn("t1"), plan) }
        }
    }

    @Test
    fun verifyRequiresSummaryWhenUploadToolPresent() {
        val engine = ExecutionEngine(
            sanitizer = sanitizer,
            toolRunner = { error("n/a") },
            audit = { _, _, _, _ -> },
            markVerifying = {}
        )
        val plan = plan(
            PlannedAction(
                id = "s1",
                title = "上传",
                description = "",
                tool = "upload_document_for_summary",
                risk = RiskLevel.ACTION_APPROVAL,
                reversible = false
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            engine.verify(plan, "任务步骤已完成")
        }
        assertEquals("摘要内容", engine.verify(plan, "摘要内容"))
    }

    @Test
    fun usesCustomFinalResultFromRunner() = runBlocking {
        val engine = ExecutionEngine(
            sanitizer = sanitizer,
            toolRunner = { action ->
                if (action.tool == "upload_document_for_summary") {
                    ToolResult(action.id, true, "文档总结已完成", evidence = "模型返回 4 字符")
                } else {
                    ToolResult(action.id, true, "ok")
                }
            },
            audit = { _, _, _, _ -> },
            markVerifying = {},
            resultExtractor = { result, action ->
                if (action.tool == "upload_document_for_summary" && result.success) {
                    "摘要ABCD"
                } else {
                    null
                }
            }
        )
        val plan = plan(
            PlannedAction(
                id = "s1",
                title = "上传",
                description = "",
                tool = "upload_document_for_summary",
                risk = RiskLevel.ACTION_APPROVAL,
                reversible = false
            )
        )
        val out = engine.execute(turn("t1"), plan)
        assertEquals("摘要ABCD", out)
    }

    private fun plan(vararg actions: PlannedAction) = TaskPlan(
        id = "plan-1",
        goal = "goal",
        title = "title",
        summary = "summary",
        actions = actions.toList()
    )

    private fun turn(id: String) = AgentTurn(
        id = id,
        threadId = "thread-1",
        ordinal = 1,
        goal = "goal",
        plan = null,
        status = TurnStatus.RUNNING,
        result = null
    )
}
