package com.agentpad.app.tool

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentpad.app.domain.PlannedAction
import com.agentpad.app.domain.ToolResult

class AndroidToolExecutor(
    private val context: Context,
    private val registry: ToolRegistry = ToolRegistry()
) {
    val availableTools: Set<String>
        get() = registry.availableToolNames()

    fun executeIntentAction(action: PlannedAction): ToolResult {
        if (!registry.isAvailable(action.tool)) {
            return ToolResult(
                actionId = action.id,
                success = false,
                summary = "当前版本不支持该工具：${action.tool}",
                errorCode = "TOOL_NOT_AVAILABLE",
                recoverable = false
            )
        }
        return when (action.tool) {
            "open_url" -> openUrl(action)
            "launch_app" -> launchApp(action)
            "share_preview" -> sharePreview(action)
            "inspect_task" -> ToolResult(
                actionId = action.id,
                success = true,
                summary = "任务状态已检查"
            )
            // Document/image tools need attachment URI + optional network; orchestrator only.
            "read_document_metadata",
            "read_document",
            "upload_document_for_summary",
            "analyze_image" -> ToolResult(
                actionId = action.id,
                success = false,
                summary = "文档/图像工具需由任务编排层执行",
                errorCode = "ORCHESTRATOR_REQUIRED",
                recoverable = false
            )
            else -> ToolResult(
                actionId = action.id,
                success = false,
                summary = "当前版本不支持该 Android 工具",
                errorCode = "TOOL_NOT_AVAILABLE",
                recoverable = false
            )
        }
    }

    private fun openUrl(action: PlannedAction): ToolResult {
        val raw = action.arguments["url"].orEmpty().trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            return ToolResult(action.id, false, "网址必须是有效的 HTTPS 地址", errorCode = "INVALID_URL")
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ToolResult(action.id, true, "已交给系统浏览器打开", evidence = uri.host)
        }.getOrElse {
            ToolResult(action.id, false, "没有可处理该网址的应用", errorCode = "NO_HANDLER")
        }
    }

    private fun launchApp(action: PlannedAction): ToolResult {
        val packageName = action.arguments["package"].orEmpty().trim()
        if (packageName.isBlank()) {
            return ToolResult(action.id, false, "缺少应用包名", errorCode = "MISSING_PACKAGE")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(action.id, false, "未找到该应用", errorCode = "APP_NOT_FOUND")
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult(action.id, true, "已启动应用", evidence = packageName)
        }.getOrElse {
            ToolResult(action.id, false, "应用启动失败", errorCode = "LAUNCH_FAILED")
        }
    }

    private fun sharePreview(action: PlannedAction): ToolResult {
        val text = action.arguments["text"].orEmpty().take(10_000)
        if (text.isBlank()) {
            return ToolResult(action.id, false, "没有可分享的内容", errorCode = "EMPTY_SHARE")
        }
        return runCatching {
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(
                Intent.createChooser(share, "选择分享目标")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ToolResult(action.id, true, "已打开系统分享面板")
        }.getOrElse {
            ToolResult(action.id, false, "无法打开分享面板", errorCode = "SHARE_FAILED")
        }
    }
}
