package com.agentpad.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentWorkingMemoryTest {
    @Test
    fun putDoesNotStoreFileBody() {
        val memory = DocumentWorkingMemory()
        memory.put("a.txt", "SECRET_BODY_CONTENT_XYZ")
        val snapshot = memory.snapshotForPrompt()
        assertTrue(snapshot.contains("a.txt"))
        assertFalse(snapshot.contains("SECRET_BODY_CONTENT_XYZ"))
        assertTrue(snapshot.contains("未上传"))
    }

    @Test
    fun clearRemovesAllEntries() {
        val memory = DocumentWorkingMemory()
        memory.putLocalReadNote("a.txt", 12)
        assertTrue(memory.hasContent())
        memory.clear()
        assertFalse(memory.hasContent())
        assertEquals("", memory.snapshotForPrompt())
    }

    @Test
    fun laterPutReplacesSameDocument() {
        val memory = DocumentWorkingMemory()
        memory.putLocalReadNote("a.txt", 10)
        memory.putLocalReadNote("a.txt", 99)
        assertEquals(
            "已在本机读取（正文未上传，仅本地）：约 99 字符",
            memory.preview("a.txt")
        )
    }
}
