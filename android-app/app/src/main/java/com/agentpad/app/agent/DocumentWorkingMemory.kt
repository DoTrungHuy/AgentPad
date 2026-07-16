package com.agentpad.app.agent

/**
 * In-session **metadata** about locally read documents.
 * Never stores file body text — body must not be injected into model history
 * without an explicit ACTION-approved upload tool.
 */
class DocumentWorkingMemory {
    private val notes = linkedMapOf<String, String>()

    fun putLocalReadNote(documentName: String, charCount: Int) {
        val name = documentName.trim().ifBlank { "document" }
        notes[name] = "已在本机读取（正文未上传，仅本地）：约 $charCount 字符"
    }

    fun putLocalImageNote(documentName: String, mimeType: String, sizeBytes: Long?) {
        val name = documentName.trim().ifBlank { "image" }
        notes[name] = "已挂载图片（未上传）：$mimeType · ${sizeBytes ?: 0} bytes"
    }

    /** @deprecated use putLocalReadNote — content must not be stored for cloud injection */
    fun put(documentName: String, content: String) {
        putLocalReadNote(documentName, content.length)
    }

    fun preview(documentName: String): String? = notes[documentName]

    fun hasContent(): Boolean = notes.isNotEmpty()

    fun clear() {
        notes.clear()
    }

    fun snapshotForPrompt(): String {
        if (notes.isEmpty()) return ""
        return buildString {
            append("本机附件处理备注（无文件正文、无路径）：\n")
            notes.forEach { (name, note) ->
                append("- ")
                append(name)
                append("：")
                append(note)
                append('\n')
            }
        }.trim()
    }

    companion object {
        const val DEFAULT_MAX_PREVIEW_CHARS = 4_000
    }
}
