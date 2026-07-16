package com.agentpad.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueFromResultTest {
    @Test
    fun buildsDraftIncludingGoalAndResult() {
        val draft = ContinueFromResult.buildDraft(
            previousGoal = "总结报告",
            previousResult = "主要风险是供应商延迟",
            maxResultChars = 100
        )
        assertTrue(draft.contains("总结报告"))
        assertTrue(draft.contains("主要风险是供应商延迟"))
        assertTrue(draft.contains("下一步"))
    }

    @Test
    fun truncatesLongResults() {
        val draft = ContinueFromResult.buildDraft(
            previousGoal = "g",
            previousResult = "x".repeat(50),
            maxResultChars = 10
        )
        assertTrue(draft.contains("x".repeat(10)))
        assertTrue(draft.contains("已截断"))
    }
}
