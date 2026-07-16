package com.agentpad.app.agent

enum class GuideKind {
    READY,
    CLARIFY,
    NEED_API,
    NEED_ATTACHMENT,
    NEED_IMAGE
}

data class GuideResult(
    val kind: GuideKind,
    val message: String,
    val chips: List<String> = emptyList()
)

/**
 * Decides whether the agent should clarify with the user or start acting.
 * Does not grant permissions; only steers conversation.
 */
class ConversationGuide {
    fun assess(
        goal: String,
        hasDocument: Boolean,
        hasImage: Boolean,
        apiReady: Boolean
    ): GuideResult {
        if (!apiReady) {
            return GuideResult(
                kind = GuideKind.NEED_API,
                message = "请先在设置中配置并测试模型 API Key，通过后再开始任务。",
                chips = listOf("去设置")
            )
        }
        val text = goal.trim()
        if (text.isEmpty()) {
            return GuideResult(
                kind = GuideKind.CLARIFY,
                message = "你想让我帮你处理什么？可以总结文件、查看图片、打开网页，或直接描述目标。",
                chips = listOf("总结文件", "选择图片", "打开网页", "自由描述")
            )
        }
        if (isVague(text)) {
            return GuideResult(
                kind = GuideKind.CLARIFY,
                message = "目标还比较含糊。我可以：① 读取并总结文件 ② 查看图片 ③ 打开链接。你更想先做哪一步？",
                chips = listOf("总结文件", "选择图片", "打开网页")
            )
        }
        if (wantsDocument(text) && !hasDocument) {
            return GuideResult(
                kind = GuideKind.NEED_ATTACHMENT,
                message = "需要你先添加一个文本文件。点下方「文件」选择后，我会开始处理（仅有图片不够）。",
                chips = listOf("添加文件")
            )
        }
        if (wantsImage(text) && !hasImage) {
            return GuideResult(
                kind = GuideKind.NEED_IMAGE,
                message = "需要你先选择图片。点下方「图片」从相册挑选后，我再继续。",
                chips = listOf("选择图片")
            )
        }
        return GuideResult(
            kind = GuideKind.READY,
            message = "目标清楚，开始处理。",
            chips = emptyList()
        )
    }

    private fun isVague(text: String): Boolean {
        val normalized = text.lowercase()
        val vague = listOf(
            "帮我弄一下", "帮我看看", "弄一下", "看看", "处理一下", "随便", "帮忙", "test", "测试"
        )
        return text.length < 4 || vague.any { normalized == it || normalized == "$it。" }
    }

    private fun wantsDocument(text: String): Boolean {
        val keys = listOf("文件", "文档", "报告", "pdf", "总结文件", "读取文件", "合同", "txt")
        return keys.any { text.contains(it, ignoreCase = true) }
    }

    private fun wantsImage(text: String): Boolean {
        val keys = listOf("图片", "照片", "相册", "截图", "image", "photo", "发票图")
        return keys.any { text.contains(it, ignoreCase = true) }
    }
}
