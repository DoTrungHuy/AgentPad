package com.agentpad.app.agent

/**
 * Ensures document working memory does not leak across threads.
 */
class DocumentMemorySession(
    private val memory: DocumentWorkingMemory
) {
    private var activeThreadId: String? = null

    fun onThreadOpened(threadId: String) {
        if (activeThreadId != null && activeThreadId != threadId) {
            memory.clear()
        }
        activeThreadId = threadId
    }

    fun onNewThread() {
        memory.clear()
        activeThreadId = null
    }

    fun onThreadDeleted(threadId: String) {
        if (activeThreadId == threadId) {
            memory.clear()
            activeThreadId = null
        }
    }
}
