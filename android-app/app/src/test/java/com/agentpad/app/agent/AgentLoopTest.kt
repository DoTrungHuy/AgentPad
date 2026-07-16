package com.agentpad.app.agent

import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ToolResult
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.policy.PlanSanitizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val policy = ApprovalPolicy()
    private val sanitizer = PlanSanitizer(policy.registry(), policy)
    private val approvals = ApprovalService(policy)

    @Test
    fun standardModeRunsReadOnlyWithoutTokens() = runBlocking {
        val events = mutableListOf<LoopEvent>()
        val loop = AgentLoop(
            approvalPolicy = policy,
            sanitizer = sanitizer,
            approvalService = approvals,
            toolRunner = { action -> ToolResult(action.id, true, "ok-${action.tool}") }
        )
        val plan = TaskPlan(
            id = "p1",
            goal = "检查",
            title = "检查",
            summary = "",
            actions = listOf(
                PlannedAction(
                    id = "a1",
                    title = "检查",
                    description = "",
                    tool = "inspect_task",
                    risk = RiskLevel.READ_ONLY,
                    reversible = true
                )
            )
        )
        val outcome = loop.runPlan(plan, RunMode.STANDARD, emptyMap(), now = 1_000) {
            events += it
        }
        assertTrue(outcome is LoopOutcome.Completed)
        assertTrue(events.any { it is LoopEvent.ToolFinished })
    }

    @Test
    fun standardModePausesForOpenUrlWithoutApproval() = runBlocking {
        val loop = AgentLoop(
            approvalPolicy = policy,
            sanitizer = sanitizer,
            approvalService = approvals,
            toolRunner = { error("should not run") }
        )
        val plan = TaskPlan(
            id = "p1",
            goal = "打开",
            title = "打开",
            summary = "",
            actions = listOf(
                PlannedAction(
                    id = "a1",
                    title = "打开",
                    description = "",
                    tool = "open_url",
                    arguments = mapOf("url" to "https://example.com"),
                    risk = RiskLevel.TASK_APPROVAL,
                    reversible = true
                )
            )
        )
        val outcome = loop.runPlan(plan, RunMode.STANDARD, emptyMap(), now = 1_000) {}
        assertTrue(outcome is LoopOutcome.PausedForApproval)
    }

    @Test
    fun cancelsBetweenSteps() = runBlocking {
        var calls = 0
        val loop = AgentLoop(
            approvalPolicy = policy,
            sanitizer = sanitizer,
            approvalService = approvals,
            toolRunner = {
                calls++
                ToolResult(it.id, true, "ok")
            }
        )
        val plan = TaskPlan(
            id = "p1",
            goal = "两步",
            title = "两步",
            summary = "",
            actions = listOf(
                PlannedAction(
                    id = "a1",
                    title = "1",
                    description = "",
                    tool = "inspect_task",
                    risk = RiskLevel.READ_ONLY,
                    reversible = true
                ),
                PlannedAction(
                    id = "a2",
                    title = "2",
                    description = "",
                    tool = "inspect_task",
                    risk = RiskLevel.READ_ONLY,
                    reversible = true
                )
            )
        )
        var cancel = false
        val outcome = loop.runPlan(
            plan = plan,
            mode = RunMode.STANDARD,
            tokens = emptyMap(),
            now = 1_000,
            isCancelled = { cancel }
        ) { event ->
            if (event is LoopEvent.ToolFinished) cancel = true
        }
        assertTrue(outcome is LoopOutcome.Cancelled)
        assertTrue(calls == 1)
    }
}
