package com.agentpad.app.tool

import com.agentpad.app.domain.RiskLevel

enum class ToolAvailability {
    AVAILABLE,
    PLANNED,
    FORBIDDEN
}

data class ToolDescriptor(
    val name: String,
    val risk: RiskLevel,
    val availability: ToolAvailability,
    val requiredArguments: Set<String> = emptySet(),
    val summary: String = ""
)

/**
 * Single source of truth for tool names, risk, availability, and required arguments.
 * Model prompts and PlanParser must only expose [availableToolNames].
 */
class ToolRegistry(
    private val tools: List<ToolDescriptor> = DEFAULT_TOOLS
) {
    private val byName = tools.associateBy { it.name }

    fun knownToolNames(): Set<String> = byName.keys

    fun availableToolNames(): Set<String> =
        tools.filter { it.availability == ToolAvailability.AVAILABLE }.map { it.name }.toSet()

    fun isAvailable(tool: String): Boolean =
        byName[tool]?.availability == ToolAvailability.AVAILABLE

    fun isPlannable(tool: String): Boolean = isAvailable(tool)

    fun riskFor(tool: String): RiskLevel = byName[tool]?.risk ?: RiskLevel.FORBIDDEN

    fun requiredArguments(tool: String): Set<String> = byName[tool]?.requiredArguments.orEmpty()

    fun descriptor(tool: String): ToolDescriptor? = byName[tool]

    fun descriptors(availability: ToolAvailability? = null): List<ToolDescriptor> =
        if (availability == null) tools else tools.filter { it.availability == availability }

    companion object {
        val DEFAULT_TOOLS: List<ToolDescriptor> = listOf(
            ToolDescriptor(
                name = "inspect_task",
                risk = RiskLevel.READ_ONLY,
                availability = ToolAvailability.AVAILABLE,
                summary = "检查当前任务状态"
            ),
            ToolDescriptor(
                name = "read_document_metadata",
                risk = RiskLevel.READ_ONLY,
                availability = ToolAvailability.AVAILABLE,
                summary = "读取用户授权文件的元数据"
            ),
            ToolDescriptor(
                name = "read_document",
                risk = RiskLevel.READ_ONLY,
                availability = ToolAvailability.AVAILABLE,
                summary = "在本机读取用户授权文件"
            ),
            ToolDescriptor(
                name = "upload_document_for_summary",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.AVAILABLE,
                summary = "将授权文件原文发送给模型做摘要"
            ),
            ToolDescriptor(
                name = "analyze_image",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.AVAILABLE,
                summary = "将用户授权的图片发送给模型做视觉分析（需审批）"
            ),
            ToolDescriptor(
                name = "open_url",
                risk = RiskLevel.TASK_APPROVAL,
                availability = ToolAvailability.AVAILABLE,
                requiredArguments = setOf("url"),
                summary = "用系统浏览器打开 HTTPS 网页"
            ),
            ToolDescriptor(
                name = "launch_app",
                risk = RiskLevel.TASK_APPROVAL,
                availability = ToolAvailability.AVAILABLE,
                requiredArguments = setOf("package"),
                summary = "启动已安装应用"
            ),
            ToolDescriptor(
                name = "share_preview",
                risk = RiskLevel.TASK_APPROVAL,
                availability = ToolAvailability.AVAILABLE,
                requiredArguments = setOf("text"),
                summary = "打开系统分享面板"
            ),
            ToolDescriptor(
                name = "write_document",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "写入文档（后续版本）"
            ),
            ToolDescriptor(
                name = "delete_document",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "删除文档（后续版本）"
            ),
            ToolDescriptor(
                name = "send_text",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "跨应用发送文本（后续版本）"
            ),
            ToolDescriptor(
                name = "capture_screen",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "截屏（后续版本）"
            ),
            ToolDescriptor(
                name = "accessibility_input",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "无障碍输入（v0.3 计划）"
            ),
            ToolDescriptor(
                name = "install_package",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "安装包（后续版本，非静默）"
            ),
            ToolDescriptor(
                name = "run_command",
                risk = RiskLevel.ACTION_APPROVAL,
                availability = ToolAvailability.PLANNED,
                summary = "受限命令（Runtime 阶段）"
            ),
            ToolDescriptor(
                name = "payment",
                risk = RiskLevel.FORBIDDEN,
                availability = ToolAvailability.FORBIDDEN,
                summary = "支付操作永久禁止"
            ),
            ToolDescriptor(
                name = "read_password",
                risk = RiskLevel.FORBIDDEN,
                availability = ToolAvailability.FORBIDDEN,
                summary = "读取密码永久禁止"
            ),
            ToolDescriptor(
                name = "read_otp",
                risk = RiskLevel.FORBIDDEN,
                availability = ToolAvailability.FORBIDDEN,
                summary = "读取验证码永久禁止"
            ),
            ToolDescriptor(
                name = "bypass_lock_screen",
                risk = RiskLevel.FORBIDDEN,
                availability = ToolAvailability.FORBIDDEN,
                summary = "绕过锁屏永久禁止"
            ),
            ToolDescriptor(
                name = "silent_install",
                risk = RiskLevel.FORBIDDEN,
                availability = ToolAvailability.FORBIDDEN,
                summary = "静默安装永久禁止"
            )
        )
    }
}
