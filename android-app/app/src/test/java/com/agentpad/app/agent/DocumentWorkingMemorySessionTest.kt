package com.agentpad.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentWorkingMemorySessionTest {
    @Test
    fun switchingThreadClearsMemoryViaSessionHelper() {
        val memory = DocumentWorkingMemory()
        val session = DocumentMemorySession(memory)
        session.onThreadOpened("thread-a")
        memory.putLocalReadNote("a.txt", 12)
        assertTrue(memory.hasContent())

        session.onThreadOpened("thread-b")
        assertFalse(memory.hasContent())
        assertEquals("", memory.snapshotForPrompt())
    }

    @Test
    fun reopeningSameThreadKeepsMemory() {
        val memory = DocumentWorkingMemory()
        val session = DocumentMemorySession(memory)
        session.onThreadOpened("thread-a")
        memory.putLocalReadNote("a.txt", 42)
        session.onThreadOpened("thread-a")
        assertEquals(
            "已在本机读取（正文未上传，仅本地）：约 42 字符",
            memory.preview("a.txt")
        )
    }

    @Test
    fun newThreadClearsMemory() {
        val memory = DocumentWorkingMemory()
        val session = DocumentMemorySession(memory)
        session.onThreadOpened("thread-a")
        memory.putLocalReadNote("a.txt", 1)
        session.onNewThread()
        assertFalse(memory.hasContent())
    }
}
