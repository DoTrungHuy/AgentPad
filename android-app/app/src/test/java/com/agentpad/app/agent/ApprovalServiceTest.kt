package com.agentpad.app.agent

import com.agentpad.app.domain.ApprovalScope
import com.agentpad.app.domain.ApprovalToken
import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.policy.ApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalServiceTest {
    private val service = ApprovalService(ApprovalPolicy())

    private val openUrl = PlannedAction(
        id = "a1",
        title = "打开网页",
        description = "",
        tool = "open_url",
        arguments = mapOf("url" to "https://example.com"),
        risk = RiskLevel.TASK_APPROVAL,
        reversible = true
    )
    private val upload = PlannedAction(
        id = "a2",
        title = "上传摘要",
        description = "",
        tool = "upload_document_for_summary",
        risk = RiskLevel.ACTION_APPROVAL,
        reversible = false
    )
    private val inspect = PlannedAction(
        id = "a3",
        title = "检查",
        description = "",
        tool = "inspect_task",
        risk = RiskLevel.READ_ONLY,
        reversible = true
    )

    @Test
    fun missingApprovalsForTaskAndActionScopes() {
        val plan = plan(openUrl, upload, inspect)
        val missing = service.missingApprovals(emptyMap(), plan, now = 1_000)
        assertEquals(listOf("a1", "a2"), missing)
    }

    @Test
    fun taskTokenCoversTaskScopedActions() {
        val plan = plan(openUrl, inspect)
        val tokens = service.approveTask(emptyMap(), plan, now = 1_000, ttlMillis = 60_000)
        assertTrue(service.missingApprovals(tokens, plan, now = 1_500).isEmpty())
        assertTrue(service.isTaskApproved(tokens, plan, now = 1_500))
    }

    @Test
    fun actionTokenRequiredForSensitiveTools() {
        val plan = plan(upload)
        val afterTask = service.approveTask(emptyMap(), plan, now = 1_000, ttlMillis = 60_000)
        assertEquals(listOf("a2"), service.missingApprovals(afterTask, plan, now = 1_500))

        val afterAction = service.approveAction(afterTask, plan, "a2", now = 1_000, ttlMillis = 60_000)
        assertTrue(service.missingApprovals(afterAction, plan, now = 1_500).isEmpty())
        assertTrue(service.isActionApproved(afterAction, plan, "a2", now = 1_500))
    }

    @Test
    fun consumeInvalidatesTokensForRerun() {
        val plan = plan(openUrl, upload)
        var tokens = service.approveTask(emptyMap(), plan, now = 1_000, ttlMillis = 60_000)
        tokens = service.approveAction(tokens, plan, "a2", now = 1_000, ttlMillis = 60_000)
        assertTrue(service.missingApprovals(tokens, plan, now = 1_500).isEmpty())

        tokens = service.consume(tokens, plan)
        assertFalse(service.missingApprovals(tokens, plan, now = 1_500).isEmpty())
    }

    @Test
    fun expiredTokenIsMissing() {
        val plan = plan(openUrl)
        val tokens = service.approveTask(emptyMap(), plan, now = 1_000, ttlMillis = 100)
        assertFalse(service.isTaskApproved(tokens, plan, now = 1_200))
        assertEquals(listOf("a1"), service.missingApprovals(tokens, plan, now = 1_200))
    }

    @Test
    fun scopesMapMatchesPolicy() {
        val plan = plan(inspect, openUrl, upload)
        val scopes = service.scopesFor(plan).toMap()
        assertEquals(ApprovalScope.NONE, scopes["a3"])
        assertEquals(ApprovalScope.TASK, scopes["a1"])
        assertEquals(ApprovalScope.ACTION, scopes["a2"])
    }

    private fun plan(vararg actions: PlannedAction) = TaskPlan(
        id = "plan-1",
        goal = "goal",
        title = "title",
        summary = "summary",
        actions = actions.toList()
    )
}
