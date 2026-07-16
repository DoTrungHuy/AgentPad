package com.agentpad.app.agent

object ContinueFromResult {
    fun buildDraft(
        previousGoal: String,
        previousResult: String,
        maxResultChars: Int = 2_000
    ): String {
        val result = previousResult.trim()
        val body = if (result.length > maxResultChars) {
            result.take(maxResultChars) + "\n…(结果已截断)"
        } else {
            result
        }
        return buildString {
            append("基于上一回合结果继续。\n")
            append("上一目标：")
            append(previousGoal.trim().ifBlank { "（无）" })
            append("\n上一结果：\n")
            append(body.ifBlank { "（无结果文本）" })
            append("\n\n请根据以上观察，制定下一步可执行计划。")
        }
    }
}
