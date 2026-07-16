package com.agentpad.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentpad.app.BuildConfig
import com.agentpad.app.data.ThemePreference
import com.agentpad.app.domain.AgentThread
import com.agentpad.app.domain.ApprovalScope
import com.agentpad.app.domain.CapabilityDescriptor
import com.agentpad.app.domain.CapabilityState
import com.agentpad.app.domain.MessageKind
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ThreadMessage
import com.agentpad.app.domain.TurnStatus
import com.agentpad.app.ui.theme.AgentPadTheme
import com.agentpad.app.ui.theme.Danger
import com.agentpad.app.ui.theme.Success
import com.agentpad.app.ui.theme.Warning
import java.text.DateFormat
import java.util.Date

private val compactSections = listOf(
    AppSection.THREAD to "线程",
    AppSection.PLAN to "计划",
    AppSection.APPROVALS to "审批",
    AppSection.SETTINGS to "设置"
)

@Composable
fun AgentPadRoot(
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit = {},
    onPrivacyModeChanged: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val threads by viewModel.threads.collectAsStateWithLifecycle()

    LaunchedEffect(state.privacyMode) {
        onPrivacyModeChanged(state.privacyMode)
    }

    AgentPadTheme(darkTheme = state.theme == ThemePreference.DARK) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            LaunchedEffect(state.section, maxWidth) {
                viewModel.recordUiContext(state.section, maxWidth.value.toInt())
            }
            when {
                maxWidth < 600.dp -> CompactLayout(
                    state,
                    threads,
                    viewModel,
                    onChooseDocument,
                    onChooseImage,
                    onExportDiagnostics
                )
                maxWidth < 1000.dp -> MediumLayout(
                    state,
                    threads,
                    viewModel,
                    onChooseDocument,
                    onChooseImage,
                    onExportDiagnostics
                )
                else -> ExpandedLayout(
                    state,
                    threads,
                    viewModel,
                    onChooseDocument,
                    onChooseImage,
                    onExportDiagnostics
                )
            }
        }
    }

    if (state.compressionRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCompression,
            title = { Text("线程上下文较长") },
            text = {
                Text("继续前需要生成上下文检查点。原始消息会完整保留，后续请求将使用摘要和检查点后的消息。")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCompressionAndCreatePlan) {
                    Text("确认压缩并继续")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCompression) { Text("取消") }
            }
        )
    }
    if (state.deleteConfirmationThreadId != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteThread,
            title = { Text("删除线程？") },
            text = { Text("将删除该线程的消息、计划、结果、附件授权和审计记录。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteThread) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteThread) { Text("取消") }
            }
        )
    }
    if (
        state.crashReportAvailable &&
        !state.compressionRequired &&
        state.deleteConfirmationThreadId == null
    ) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCrashReport,
            title = { Text("检测到上次异常") },
            text = {
                Text(
                    "AgentPad 保存了一份仅位于本机的脱敏崩溃报告。" +
                        "报告不包含 API Key、文件原文或完整模型输出。"
                )
            },
            confirmButton = {
                TextButton(onClick = onExportDiagnostics) {
                    Text("导出诊断文件")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCrashReport) {
                    Text("忽略并删除")
                }
            }
        )
    }
}

@Composable
private fun CompactLayout(
    state: AgentPadUiState,
    threads: List<AgentThread>,
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { WorkspaceHeader(state, viewModel) },
        bottomBar = {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                compactSections.forEach { (section, label) ->
                    NavigationBarItem(
                        selected = state.section == section,
                        onClick = { viewModel.setSection(section) },
                        icon = { Icon(sectionIcon(section), label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            SectionContent(
                state,
                threads,
                viewModel,
                onChooseDocument,
                onChooseImage,
                onExportDiagnostics,
                showThreadPicker = true
            )
        }
    }
}

@Composable
private fun MediumLayout(
    state: AgentPadUiState,
    threads: List<AgentThread>,
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        ThreadSidebar(threads, state, viewModel, Modifier.width(260.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            WorkspaceHeader(state, viewModel)
            StatusStrip(state)
            SectionContent(
                state,
                threads,
                viewModel,
                onChooseDocument,
                onChooseImage,
                onExportDiagnostics,
                showThreadPicker = false
            )
        }
    }
}

@Composable
private fun ExpandedLayout(
    state: AgentPadUiState,
    threads: List<AgentThread>,
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        ThreadSidebar(threads, state, viewModel, Modifier.width(280.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            WorkspaceHeader(state, viewModel)
            StatusStrip(state)
            if (state.section == AppSection.THREAD) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ThreadPage(
                        state,
                        viewModel,
                        onChooseDocument,
                        onChooseImage,
                        Modifier.weight(1.25f)
                    )
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    InspectorPage(state, viewModel, Modifier.weight(0.85f))
                }
            } else {
                SectionContent(
                    state,
                    threads,
                    viewModel,
                    onChooseDocument,
                    onChooseImage,
                    onExportDiagnostics,
                    showThreadPicker = false
                )
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(state: AgentPadUiState, viewModel: AgentPadViewModel? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "AP",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("AgentPad", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    state.snapshot?.thread?.title ?: "工作区 · 新任务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            ModelBadge(state)
            if (viewModel != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { viewModel.setSection(AppSection.SETTINGS) }) {
                    Icon(Icons.Rounded.Settings, "设置")
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(state: AgentPadUiState) {
    val turn = state.currentTurn
    val (label, color) = when {
        state.busy && turn?.status == TurnStatus.PLANNING -> "规划中…" to MaterialTheme.colorScheme.primary
        state.busy && turn?.status == TurnStatus.RUNNING -> "执行中…" to MaterialTheme.colorScheme.primary
        turn?.status == TurnStatus.AWAITING_APPROVAL -> "待审批" to Warning
        turn?.status == TurnStatus.INTERRUPTED -> "已中断 · 需重新审批" to Warning
        turn?.status == TurnStatus.FAILED -> "失败" to Danger
        turn?.status == TurnStatus.COMPLETED -> "已完成" to Success
        else -> "就绪" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.width(8.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            if (!state.apiKeyConfigured) {
                Text("模型未配置", color = Warning, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ModelBadge(state: AgentPadUiState) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            state.activeProfileLabel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadSidebar(
    threads: List<AgentThread>,
    state: AgentPadUiState,
    viewModel: AgentPadViewModel,
    modifier: Modifier
) {
    Surface(
        modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Button(
                onClick = viewModel::newThread,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新建任务")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "会话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(threads, key = { it.id }) { thread ->
                    ThreadRow(
                        thread = thread,
                        selected = thread.id == state.selectedThreadId,
                        onOpen = { viewModel.openThread(thread.id) },
                        onDelete = { viewModel.requestDeleteThread(thread.id) }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            SidebarDestination("计划", AppSection.PLAN, state.section, viewModel)
            SidebarDestination("审批", AppSection.APPROVALS, state.section, viewModel)
            SidebarDestination("能力", AppSection.CAPABILITIES, state.section, viewModel)
            SidebarDestination("设置", AppSection.SETTINGS, state.section, viewModel)
        }
    }
}

@Composable
private fun ThreadRow(
    thread: AgentThread,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(10.dp),
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
                modifier = Modifier.size(8.dp)
            ) {}
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    thread.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(thread.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    "删除线程",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SidebarDestination(
    label: String,
    section: AppSection,
    current: AppSection,
    viewModel: AgentPadViewModel
) {
    val selected = current == section
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { viewModel.setSection(section) }
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                sectionIcon(section),
                null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun SectionContent(
    state: AgentPadUiState,
    threads: List<AgentThread>,
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit,
    onExportDiagnostics: () -> Unit,
    showThreadPicker: Boolean
) {
    when (state.section) {
        AppSection.THREAD -> ThreadPage(
            state,
            viewModel,
            onChooseDocument,
            onChooseImage,
            Modifier.fillMaxSize(),
            if (showThreadPicker) threads else emptyList()
        )
        AppSection.PLAN -> PlanPage(state, viewModel, Modifier.fillMaxSize())
        AppSection.APPROVALS -> ApprovalsPage(state, viewModel, Modifier.fillMaxSize())
        AppSection.CAPABILITIES -> CapabilitiesPage(viewModel.capabilities, state.apiKeyConfigured)
        AppSection.SETTINGS -> SettingsPage(state, viewModel, onExportDiagnostics)
    }
}

@Composable
private fun ThreadPage(
    state: AgentPadUiState,
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit,
    modifier: Modifier,
    compactThreads: List<AgentThread> = emptyList()
) {
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (compactThreads.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = viewModel::newThread,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("新建任务")
                    }
                }
                items(compactThreads.take(5), key = { "compact-${it.id}" }) { thread ->
                    if (state.selectedThreadId == null || thread.id != state.selectedThreadId) {
                        ThreadRow(
                            thread,
                            false,
                            onOpen = { viewModel.openThread(thread.id) },
                            onDelete = { viewModel.requestDeleteThread(thread.id) }
                        )
                    }
                }
            }
            val messages = state.snapshot?.messages.orEmpty()
            if (messages.isEmpty() && state.guideMessage == null) {
                item {
                    EmptyPanel(
                        "开始一个任务线程",
                        "直接描述目标。信息不够时我会先引导你；清楚后会自动调用工具推进。"
                    )
                }
            } else {
                items(messages, key = { it.id }) { message -> MessageCard(message) }
            }
            state.guideMessage?.let { guide ->
                item { NoticeCard(guide, MaterialTheme.colorScheme.primary) }
            }
            state.photoSearchNotice?.let { notice ->
                item { NoticeCard(notice, MaterialTheme.colorScheme.primary) }
            }
            if (state.photoCandidates.isNotEmpty()) {
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本地候选照片", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearPhotoCandidates) {
                                Text("清除")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        state.photoCandidates.take(20).forEach { photo ->
                            TextButton(
                                onClick = { viewModel.selectPhotoCandidate(photo) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    photo.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                                        .format(java.util.Date(photo.dateTakenMillis)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (state.photoCandidates.size > 20) {
                            Text(
                                "仅显示前 20 张，共 ${state.photoCandidates.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (state.loopLog.isNotEmpty()) {
                item {
                    Panel {
                        Text("工具过程", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        state.loopLog.takeLast(12).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.resultNotice?.let { notice ->
                item { NoticeCard(notice, Success) }
            }
            state.error?.let { error ->
                item { NoticeCard(error, MaterialTheme.colorScheme.error) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Composer(state, viewModel, onChooseDocument, onChooseImage)
    }
}

@Composable
private fun MessageCard(message: ThreadMessage) {
    val isUser = message.kind == MessageKind.GOAL
    val label = when (message.kind) {
        MessageKind.GOAL -> "你"
        MessageKind.PLAN -> "计划"
        MessageKind.RESULT -> "结果"
        MessageKind.STATUS -> "状态"
        MessageKind.CONTEXT_SUMMARY -> "上下文检查点"
    }
    val accent = when (message.kind) {
        MessageKind.GOAL -> MaterialTheme.colorScheme.primary
        MessageKind.PLAN -> MaterialTheme.colorScheme.secondary
        MessageKind.RESULT -> Success
        MessageKind.STATUS -> Warning
        MessageKind.CONTEXT_SUMMARY -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.92f else 1f),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.height(4.dp))
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Composer(
    state: AgentPadUiState,
    viewModel: AgentPadViewModel,
    onChooseDocument: () -> Unit,
    onChooseImage: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (state.selectedThreadId == null) "新任务" else "继续这个线程",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.guideChips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.guideChips.take(4).forEach { chip ->
                        OutlinedButton(
                            onClick = {
                                when (chip) {
                                    "添加文件" -> onChooseDocument()
                                    "选择图片" -> onChooseImage()
                                    else -> viewModel.applyGuideChip(chip)
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(chip, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.draftGoal,
                onValueChange = viewModel::setDraftGoal,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
                placeholder = { Text("告诉 AgentPad 要完成什么…") },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onChooseDocument,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.AttachFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("文件")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onChooseImage,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.Description, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("图片")
                }
                state.selectedDocument?.let { document ->
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Row(
                            Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                document.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(100.dp)
                            )
                            TextButton(onClick = viewModel::clearDocument) { Text("×") }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = viewModel::createPlan,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("开始")
                }
            }
        }
    }
}

@Composable
private fun InspectorPage(
    state: AgentPadUiState,
    viewModel: AgentPadViewModel,
    modifier: Modifier
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PlanPanel(state, viewModel) }
        item { ApprovalPanel(state, viewModel) }
        state.currentTurn?.result?.let { result ->
            item {
                Panel {
                    Text("输出", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(result)
                }
            }
        }
    }
}

@Composable
private fun PlanPage(state: AgentPadUiState, viewModel: AgentPadViewModel, modifier: Modifier) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PlanPanel(state, viewModel) }
        state.currentTurn?.result?.let { item { NoticeCard(it, Success) } }
    }
}

@Composable
private fun PlanPanel(state: AgentPadUiState, viewModel: AgentPadViewModel) {
    val plan = state.currentPlan
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("当前计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            state.currentTurn?.let { StatusBadge(it.status) }
        }
        Spacer(Modifier.height(10.dp))
        if (plan == null) {
            Text("当前线程还没有可执行计划。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (
                state.currentTurn?.status in setOf(
                    TurnStatus.PLANNING,
                    TurnStatus.INTERRUPTED
                )
            ) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = viewModel::cancelTurn,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.StopCircle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("取消当前回合")
                }
            }
        } else {
            if (state.currentTurn?.status == TurnStatus.INTERRUPTED) {
                Text(
                    "此回合曾被中断。请重新审批后执行；旧审批令牌已失效。",
                    color = Warning
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(plan.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            plan.actions.forEachIndexed { index, action ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(action.title, fontWeight = FontWeight.Bold)
                        Text(action.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(action.tool, style = MaterialTheme.typography.labelSmall)
                    }
                    RiskBadge(action.risk)
                }
                if (index != plan.actions.lastIndex) HorizontalDivider()
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::cancelTurn,
                    enabled = state.currentTurn?.status !in setOf(
                        TurnStatus.COMPLETED,
                        TurnStatus.FAILED,
                        TurnStatus.CANCELLED,
                        TurnStatus.SUPERSEDED
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.StopCircle, null)
                    Spacer(Modifier.width(6.dp))
                    Text("取消")
                }
                Button(
                    onClick = viewModel::executePlan,
                    enabled = !state.busy &&
                        state.currentTurn?.status in setOf(
                            TurnStatus.AWAITING_APPROVAL,
                            TurnStatus.INTERRUPTED,
                            TurnStatus.FAILED
                        ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (state.currentTurn?.status) {
                            TurnStatus.INTERRUPTED -> "重新审批后执行"
                            TurnStatus.FAILED -> "重新审批后重试"
                            else -> "执行"
                        }
                    )
                }
            }
            if (
                state.currentTurn?.status in setOf(TurnStatus.COMPLETED, TurnStatus.FAILED) &&
                !state.currentTurn?.result.isNullOrBlank()
            ) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = viewModel::continueFromResult,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.width(6.dp))
                    Text("基于结果继续")
                }
            }
        }
    }
}

@Composable
private fun ApprovalsPage(state: AgentPadUiState, viewModel: AgentPadViewModel, modifier: Modifier) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ApprovalPanel(state, viewModel) }
        state.error?.let { item { NoticeCard(it, MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun ApprovalPanel(state: AgentPadUiState, viewModel: AgentPadViewModel) {
    val plan = state.currentPlan
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("审批", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        if (plan == null) {
            Text("没有待审批计划。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Panel
        }
        val scopes = viewModel.approvalsFor(plan).toMap()
        val taskActions = plan.actions.filter { scopes[it.id] == ApprovalScope.TASK }
        if (taskActions.isNotEmpty()) {
            ApprovalRow(
                title = "允许当前任务的普通外部操作",
                description = taskActions.joinToString("、") { it.title },
                approved = viewModel.isTaskApproved(state, plan),
                onApprove = viewModel::approveTask
            )
        }
        plan.actions.filter { scopes[it.id] == ApprovalScope.ACTION }.forEach { action ->
            ApprovalRow(
                title = action.title,
                description = action.description,
                approved = viewModel.isActionApproved(state, plan, action.id),
                onApprove = { viewModel.approveAction(action.id) }
            )
        }
        if (taskActions.isEmpty() && plan.actions.none { scopes[it.id] == ApprovalScope.ACTION }) {
            Text("该计划仅包含只读操作，无需额外批准。")
        }
    }
}

@Composable
private fun ApprovalRow(
    title: String,
    description: String,
    approved: Boolean,
    onApprove: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (approved) Icons.Rounded.CheckCircle else Icons.Rounded.PendingActions,
            null,
            tint = if (approved) Success else Warning
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (approved) {
            Text("已允许", color = Success)
        } else {
            FilledTonalButton(onClick = onApprove) { Text("允许本次") }
        }
    }
}

@Composable
private fun CapabilitiesPage(
    capabilities: List<CapabilityDescriptor>,
    apiKeyConfigured: Boolean
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageHeading("能力", "这里只展示真实可用或明确规划中的能力。") }
        items(capabilities, key = { it.id }) { capability ->
            val state = if (capability.id == "model" && apiKeyConfigured) {
                CapabilityState.AVAILABLE
            } else {
                capability.state
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, null, tint = stateColor(state))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(capability.name, fontWeight = FontWeight.Bold)
                        Text(capability.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(capability.enableHint, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(stateLabel(state), color = stateColor(state))
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    state: AgentPadUiState,
    viewModel: AgentPadViewModel,
    onExportDiagnostics: () -> Unit
) {
    val settings = state.providerSettings
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageHeading("设置", "模型配置只有在连接测试成功后才会保存。") }
        item {
            Panel {
                Text("1. 服务商模板 / 配置档案", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.addProviderTemplate("deepseek") },
                        modifier = Modifier.weight(1f)
                    ) { Text("DeepSeek") }
                    OutlinedButton(
                        onClick = { viewModel.addProviderTemplate("openai") },
                        modifier = Modifier.weight(1f)
                    ) { Text("OpenAI") }
                    OutlinedButton(
                        onClick = { viewModel.addProviderTemplate("custom") },
                        modifier = Modifier.weight(1f)
                    ) { Text("自定义") }
                }
                if (state.providerProfiles.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("已保存档案", style = MaterialTheme.typography.labelMedium)
                    state.providerProfiles.forEach { profile ->
                        val selected = profile.id == state.activeProfileId
                        TextButton(onClick = { viewModel.selectActiveProfile(profile.id) }) {
                            Text(
                                (if (selected) "● " else "○ ") +
                                    "${profile.displayName} · ${profile.model.ifBlank { "未填模型" }}",
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
        item {
            Panel {
                Text("2. 模型与接口", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.endpoint,
                    onValueChange = { viewModel.setProviderSettings(settings.copy(endpoint = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS 接口地址") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = { viewModel.setProviderSettings(settings.copy(model = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型名称") },
                    singleLine = true
                )
            }
        }
        item {
            Panel {
                Text("3. API Key 与连接测试", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = viewModel::setApiKeyDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (state.apiKeyConfigured) "API Key（留空保持不变）" else "API Key")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = viewModel::testAndSaveProvider,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("测试连接并保存")
                }
                state.resultNotice?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Success)
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Panel {
                Text("外观与隐私", fontWeight = FontWeight.Bold)
                SettingSwitchRow(
                    "深色主题",
                    state.theme == ThemePreference.DARK,
                    onChange = {
                        viewModel.setTheme(if (it) ThemePreference.DARK else ThemePreference.LIGHT)
                    }
                )
                SettingSwitchRow(
                    "隐私模式",
                    state.privacyMode,
                    "开启后系统截图和录屏会显示黑屏。",
                    viewModel::setPrivacyMode
                )
            }
        }
        item {
            Panel {
                Text("版本与诊断", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("构建 ${BuildConfig.GIT_SHA} · ${BuildConfig.BUILD_CHANNEL}")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onExportDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("导出本地诊断")
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RiskBadge(risk: RiskLevel) {
    val color = when (risk) {
        RiskLevel.READ_ONLY -> Success
        RiskLevel.TASK_APPROVAL -> MaterialTheme.colorScheme.primary
        RiskLevel.ACTION_APPROVAL -> Warning
        RiskLevel.FORBIDDEN -> Danger
    }
    Text(
        when (risk) {
            RiskLevel.READ_ONLY -> "只读"
            RiskLevel.TASK_APPROVAL -> "任务审批"
            RiskLevel.ACTION_APPROVAL -> "逐项审批"
            RiskLevel.FORBIDDEN -> "禁止"
        },
        color = color,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun StatusBadge(status: TurnStatus) {
    val color = when (status) {
        TurnStatus.COMPLETED -> Success
        TurnStatus.FAILED -> Danger
        TurnStatus.AWAITING_APPROVAL -> Warning
        TurnStatus.INTERRUPTED -> Warning
        TurnStatus.SUPERSEDED, TurnStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Text(statusLabel(status), color = color, style = MaterialTheme.typography.labelMedium)
}

private fun sectionIcon(section: AppSection) = when (section) {
    AppSection.THREAD -> Icons.Rounded.AutoAwesome
    AppSection.PLAN -> Icons.Rounded.Description
    AppSection.APPROVALS -> Icons.Rounded.PendingActions
    AppSection.CAPABILITIES -> Icons.Rounded.Memory
    AppSection.SETTINGS -> Icons.Rounded.Settings
}

private fun statusLabel(status: TurnStatus) = when (status) {
    TurnStatus.DRAFT -> "草稿"
    TurnStatus.PLANNING -> "计划中"
    TurnStatus.AWAITING_APPROVAL -> "待审批"
    TurnStatus.RUNNING -> "执行中"
    TurnStatus.VERIFYING -> "验证中"
    TurnStatus.COMPLETED -> "已完成"
    TurnStatus.FAILED -> "失败"
    TurnStatus.CANCELLED -> "已取消"
    TurnStatus.INTERRUPTED -> "已中断"
    TurnStatus.SUPERSEDED -> "已作废"
}

private fun stateColor(state: CapabilityState) = when (state) {
    CapabilityState.AVAILABLE -> Success
    CapabilityState.NEEDS_CONFIGURATION, CapabilityState.NEEDS_PERMISSION -> Warning
    CapabilityState.PLANNED -> Color(0xFF2563EB)
    CapabilityState.UNAVAILABLE -> Danger
}

private fun stateLabel(state: CapabilityState) = when (state) {
    CapabilityState.AVAILABLE -> "可用"
    CapabilityState.NEEDS_CONFIGURATION -> "待配置"
    CapabilityState.NEEDS_PERMISSION -> "待授权"
    CapabilityState.PLANNED -> "规划中"
    CapabilityState.UNAVAILABLE -> "不可用"
}
