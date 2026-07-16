package com.agentpad.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationGuideTest {
    private val guide = ConversationGuide()

    @Test
    fun needsApiWhenNotConfigured() {
        val result = guide.assess("总结文件", hasDocument = true, hasImage = false, apiReady = false)
        assertEquals(GuideKind.NEED_API, result.kind)
    }

    @Test
    fun clarifiesEmptyGoal() {
        val result = guide.assess("", hasDocument = false, hasImage = false, apiReady = true)
        assertEquals(GuideKind.CLARIFY, result.kind)
        assertEquals(true, result.chips.isNotEmpty())
    }

    @Test
    fun clarifiesVagueGoal() {
        val result = guide.assess("帮我看看", hasDocument = false, hasImage = false, apiReady = true)
        assertEquals(GuideKind.CLARIFY, result.kind)
    }

    @Test
    fun needsAttachmentForDocumentTask() {
        val result = guide.assess("总结这份报告", hasDocument = false, hasImage = false, apiReady = true)
        assertEquals(GuideKind.NEED_ATTACHMENT, result.kind)
    }

    @Test
    fun needsImageForPhotoTask() {
        val result = guide.assess("看看这张照片", hasDocument = false, hasImage = false, apiReady = true)
        assertEquals(GuideKind.NEED_IMAGE, result.kind)
    }

    @Test
    fun readyWhenDocumentPresent() {
        val result = guide.assess("总结这份报告的风险", hasDocument = true, hasImage = false, apiReady = true)
        assertEquals(GuideKind.READY, result.kind)
    }

    @Test
    fun imageDoesNotSatisfyDocumentNeed() {
        val result = guide.assess("总结这份报告", hasDocument = false, hasImage = true, apiReady = true)
        assertEquals(GuideKind.NEED_ATTACHMENT, result.kind)
    }
}
