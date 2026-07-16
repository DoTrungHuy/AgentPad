package com.agentpad.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agentpad.app.AgentPadApplication
import com.agentpad.app.data.ThemePreference
import com.agentpad.app.domain.AgentThread
import com.agentpad.app.domain.AgentTurn
import com.agentpad.app.domain.ApprovalScope
import com.agentpad.app.domain.ApprovalToken
import com.agentpad.app.domain.CapabilityDescriptor
import com.agentpad.app.domain.CapabilityState
import com.agentpad.app.domain.ProviderSettings
import com.agentpad.app.domain.RiskLevel
import com.agentpad.app.domain.TaskPlan
import com.agentpad.app.domain.ThreadAttachment
import com.agentpad.app.domain.ThreadMessage
import com.agentpad.app.domain.ThreadSnapshot
import com.agentpad.app.domain.ToolResult
import com.agentpad.app.domain.TurnStatus
import com.agentpad.app.agent.AgentLoop
import com.agentpad.app.agent.ApprovalService
import com.agentpad.app.agent.ContinueFromResult
import com.agentpad.app.agent.ConversationGuide
import com.agentpad.app.agent.DocumentMemorySession
import com.agentpad.app.agent.DocumentWorkingMemory
import com.agentpad.app.agent.ExecutionEngine
import com.agentpad.app.agent.GuideKind
import com.agentpad.app.agent.LoopEvent
import com.agentpad.app.agent.LoopOutcome
import com.agentpad.app.agent.RunMode
import com.agentpad.app.agent.RuntimeBudget
import com.agentpad.app.agent.TurnLifecycle
import com.agentpad.app.agent.TurnStatusGuard
import com.agentpad.app.agent.UntrustedContext
import com.agentpad.app.domain.ProviderProfile
import android.util.Base64
import com.agentpad.app.media.LocalMediaLibrary
import com.agentpad.app.media.LocalPhotoItem
import com.agentpad.app.media.PhotoDateQueryParser
import com.agentpad.app.media.PhotoSearchQuery
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.policy.PlanSanitizer
import com.agentpad.app.provider.OpenAiCompatibleClient
import com.agentpad.app.provider.ProviderTemplates
import com.agentpad.app.provider.ThreadContextPolicy
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppSection {
    THREAD,
    PLAN,
    APPROVALS,
    CAPABILITIES,
    SETTINGS
}

data class SelectedDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long?
) {
    fun toAttachment() = ThreadAttachment(
        threadId = "",
        turnId = null,
        uri = uri.toString(),
        name = name,
        mimeType = mimeType,
        size = size
    )
}

data class AgentPadUiState(
    val section: AppSection = AppSection.THREAD,
    val selectedThreadId: String? = null,
    val snapshot: ThreadSnapshot? = null,
    val draftGoal: String = "",
    val selectedDocument: SelectedDocument? = null,
    val providerSettings: ProviderSettings = ProviderSettings(),
    val apiKeyConfigured: Boolean = false,
    val apiKeyDraft: String = "",
    val providerProfiles: List<ProviderProfile> = emptyList(),
    val activeProfileId: String? = null,
    val guideMessage: String? = null,
    val guideChips: List<String> = emptyList(),
    val loopLog: List<String> = emptyList(),
    val photoCandidates: List<LocalPhotoItem> = emptyList(),
    val photoSearchNotice: String? = null,
    val needsMediaPermission: Boolean = false,
    val approvalTokens: Map<String, ApprovalToken> = emptyMap(),
    val theme: ThemePreference = ThemePreference.DARK,
    val privacyMode: Boolean = false,
    val crashReportAvailable: Boolean = false,
    val compressionRequired: Boolean = false,
    val deleteConfirmationThreadId: String? = null,
    val resultNotice: String? = null,
    val error: String? = null,
    val busy: Boolean = false
) {
    val currentTurn: AgentTurn?
        get() = snapshot?.turns?.lastOrNull { it.status != TurnStatus.SUPERSEDED }
            ?: snapshot?.turns?.lastOrNull()

    val currentPlan: TaskPlan?
        get() = currentTurn?.plan

    val hasImageAttachment: Boolean
        get() = selectedDocument?.mimeType?.startsWith("image/") == true

    val hasDocumentAttachment: Boolean
        get() = selectedDocument != null && !hasImageAttachment

    val activeProfileLabel: String
        get() {
            val profile = providerProfiles.firstOrNull { it.id == activeProfileId }
            return if (profile != null && apiKeyConfigured) {
                "${profile.displayName} · ${profile.model}"
            } else if (apiKeyConfigured) {
                "${providerSettings.providerId} · ${providerSettings.model}"
            } else {
                "模型未配置"
            }
        }
}

class AgentPadViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AgentPadApplication
    private val policy: ApprovalPolicy = app.approvalPolicy
    private val planSanitizer = PlanSanitizer(policy.registry(), policy)
    private val approvalService = ApprovalService(policy)
    private val conversationGuide = ConversationGuide()
    private val turnLifecycle = TurnLifecycle()
    private val turnStatusGuard = TurnStatusGuard()
    private val documentMemory = DocumentWorkingMemory()
    private val documentMemorySession = DocumentMemorySession(documentMemory)
    private val runtimeBudget = RuntimeBudget()
    private val contextPolicy = ThreadContextPolicy()
    private val localMediaLibrary = LocalMediaLibrary(application)
    private var pendingPhotoSearchGoal: String? = null
    private var activeWorkJob: Job? = null
    private var activeWorkTurnId: String? = null
    private val cancelledTurnIds = mutableSetOf<String>()
    private var planningBudgetActive: Boolean = false
    private val _uiState = MutableStateFlow(
        AgentPadUiState(
            apiKeyConfigured = app.secureApiKeyStore.hasKey(),
            crashReportAvailable = app.crashReporter.hasCrashReport()
        )
    )
    val uiState: StateFlow<AgentPadUiState> = _uiState.asStateFlow()
    val threads: StateFlow<List<AgentThread>> = app.repository.observeThreads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val capabilities = listOf(
        CapabilityDescriptor(
            "model",
            "模型服务",
            "DeepSeek 或自定义 OpenAI-compatible 接口",
            CapabilityState.NEEDS_CONFIGURATION,
            RiskLevel.READ_ONLY,
            "在设置中完成连接测试"
        ),
        CapabilityDescriptor(
            "documents",
            "授权文件",
            "通过系统文件选择器读取单个文本文件",
            CapabilityState.AVAILABLE,
            RiskLevel.READ_ONLY,
            "每个文件由用户明确选择"
        ),
        CapabilityDescriptor(
            "photos",
            "相册检索",
            "选图（Picker）或授权后按日期本地检索；默认不上传全库",
            CapabilityState.AVAILABLE,
            RiskLevel.READ_ONLY,
            "系统授权后仅本机筛选；发给模型前会再审批"
        ),
        CapabilityDescriptor(
            "intents",
            "系统操作",
            "打开网页、启动已知应用和系统分享",
            CapabilityState.AVAILABLE,
            RiskLevel.TASK_APPROVAL,
            "执行前显示计划并等待批准"
        ),
        CapabilityDescriptor(
            "accessibility",
            "跨应用操作",
            "观察、点击、输入和滑动",
            CapabilityState.PLANNED,
            RiskLevel.ACTION_APPROVAL,
            "计划在 v0.3.0-alpha 按需启用"
        ),
        CapabilityDescriptor(
            "runtime",
            "开发运行时",
            "Python、Git 和受限 Shell",
            CapabilityState.PLANNED,
            RiskLevel.ACTION_APPROVAL,
            "计划由后续独立签名 Runtime 提供"
        )
    )

    init {
        viewModelScope.launch {
            runCatching { app.providerProfileStore.ensureMigrated() }
            app.repository.interruptActiveTurns()
            val firstThread = app.repository.observeThreads().first().firstOrNull()
            firstThread?.let { openThread(it.id) }
        }
        viewModelScope.launch {
            app.settingsStore.preferences.collect { preferences ->
                _uiState.update {
                    it.copy(
                        providerSettings = preferences.providerSettings,
                        theme = preferences.theme,
                        privacyMode = preferences.privacyMode,
                        apiKeyConfigured = isActiveProfileKeyReady(it.activeProfileId)
                    )
                }
            }
        }
        viewModelScope.launch {
            app.providerProfileStore.state.collect { profileState ->
                val active = profileState.activeProfile
                _uiState.update {
                    it.copy(
                        providerProfiles = profileState.profiles,
                        activeProfileId = profileState.activeProfileId,
                        providerSettings = active?.toProviderSettings() ?: it.providerSettings,
                        apiKeyConfigured = isActiveProfileKeyReady(profileState.activeProfileId)
                    )
                }
            }
        }
    }

    fun setSection(section: AppSection) {
        _uiState.update { it.copy(section = section, error = null) }
    }

    fun newThread() {
        checkCanLeaveActiveTurn() ?: return
        documentMemorySession.onNewThread()
        planningBudgetActive = false
        runtimeBudget.reset()
        _uiState.update {
            it.copy(
                section = AppSection.THREAD,
                selectedThreadId = null,
                snapshot = null,
                draftGoal = "",
                selectedDocument = null,
                approvalTokens = emptyMap(),
                compressionRequired = false,
                resultNotice = null,
                error = null
            )
        }
    }

    fun openThread(threadId: String) {
        if (threadId != _uiState.value.selectedThreadId && checkCanLeaveActiveTurn() == null) {
            return
        }
        viewModelScope.launch {
            documentMemorySession.onThreadOpened(threadId)
            val snapshot = app.repository.loadThread(threadId) ?: return@launch
            _uiState.update {
                it.copy(
                    selectedThreadId = threadId,
                    snapshot = snapshot,
                    section = AppSection.THREAD,
                    draftGoal = "",
                    selectedDocument = null,
                    approvalTokens = emptyMap(),
                    compressionRequired = false,
                    resultNotice = null,
                    error = null
                )
            }
        }
    }

    fun setDraftGoal(goal: String) {
        _uiState.update {
            it.copy(
                draftGoal = goal.take(MAX_GOAL_CHARS),
                compressionRequired = false,
                error = null
            )
        }
    }

    fun setProviderSettings(settings: ProviderSettings) {
        _uiState.update { it.copy(providerSettings = settings, error = null, resultNotice = null) }
    }

    fun selectDeepSeek() {
        setProviderSettings(
            _uiState.value.providerSettings.copy(
                providerId = "deepseek",
                endpoint = "https://api.deepseek.com/chat/completions",
                model = "deepseek-chat"
            )
        )
    }

    fun selectCustomProvider() {
        setProviderSettings(_uiState.value.providerSettings.copy(providerId = "custom"))
    }

    fun setApiKeyDraft(value: String) {
        _uiState.update { it.copy(apiKeyDraft = value.take(512), error = null, resultNotice = null) }
    }

    fun testAndSaveProvider() {
        saveProviderProfile()
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { app.settingsStore.setTheme(theme) }
    }

    fun setPrivacyMode(enabled: Boolean) {
        viewModelScope.launch { app.settingsStore.setPrivacyMode(enabled) }
    }

    fun recordUiContext(section: AppSection, widthDp: Int) {
        app.crashReporter.recordUiContext(section.name, widthDp)
    }

    fun dismissCrashReport() {
        app.crashReporter.clearCrashReport()
        _uiState.update { it.copy(crashReportAvailable = false) }
    }

    fun selectDocument(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val appCtx = getApplication<Application>()
                    runCatching {
                        appCtx.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val document = describeDocument(uri)
                    // Fail early if the URI is not readable after picker/media grant.
                    appCtx.contentResolver.openInputStream(uri)?.close()
                        ?: error("无法打开所选文件，可能未获得读取授权")
                    document
                }
            }
                .onSuccess { document ->
                    _uiState.update {
                        it.copy(selectedDocument = document, error = null)
                    }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            selectedDocument = null,
                            error = "附件不可用：${failure.safeMessage()}"
                        )
                    }
                }
        }
    }

    fun onDocumentPermissionFailed(uri: Uri? = null) {
        _uiState.update {
            it.copy(
                selectedDocument = null,
                error = "未能获得文件持久读取权限。请重新选择文件；部分文件管理器不支持持久授权。"
            )
        }
    }

    fun clearDocument() {
        _uiState.update { it.copy(selectedDocument = null) }
    }

    fun continueFromResult() {
        val turn = _uiState.value.currentTurn ?: return
        if (turn.status !in setOf(TurnStatus.COMPLETED, TurnStatus.FAILED)) {
            _uiState.update { it.copy(error = "只有已完成或失败的回合可以基于结果继续") }
            return
        }
        if (!turnLifecycle.canLeave(turn, activeWorkJob?.isActive == true)) {
            _uiState.update { it.copy(error = "当前回合仍在执行，请先完成或取消") }
            return
        }
        val draft = ContinueFromResult.buildDraft(
            previousGoal = turn.goal,
            previousResult = turn.result.orEmpty()
        )
        _uiState.update {
            it.copy(
                section = AppSection.THREAD,
                draftGoal = draft.take(MAX_GOAL_CHARS),
                approvalTokens = emptyMap(),
                resultNotice = "已填入上一回合观察，可直接生成下一步计划",
                error = null
            )
        }
    }

    fun createPlan() {
        val state = _uiState.value
        // G1: local album date search does not require cloud API.
        if (tryHandleLocalPhotoSearch(state.draftGoal)) {
            return
        }
        val guide = conversationGuide.assess(
            goal = state.draftGoal,
            hasDocument = state.hasDocumentAttachment,
            hasImage = state.hasImageAttachment,
            apiReady = state.apiKeyConfigured
        )
        when (guide.kind) {
            GuideKind.NEED_API -> {
                _uiState.update {
                    it.copy(
                        section = AppSection.SETTINGS,
                        guideMessage = guide.message,
                        guideChips = guide.chips,
                        error = guide.message
                    )
                }
                return
            }
            GuideKind.CLARIFY, GuideKind.NEED_ATTACHMENT, GuideKind.NEED_IMAGE -> {
                _uiState.update {
                    it.copy(
                        guideMessage = guide.message,
                        guideChips = guide.chips,
                        error = null,
                        resultNotice = null
                    )
                }
                return
            }
            GuideKind.READY -> {
                _uiState.update { it.copy(guideMessage = null, guideChips = emptyList()) }
            }
        }
        if (hasRunningTurn(state.currentTurn)) {
            _uiState.update { it.copy(error = "当前回合仍在执行，请先完成或取消") }
            return
        }
        val messages = state.snapshot?.messages.orEmpty()
        if (contextPolicy.needsCompression(messages)) {
            _uiState.update { it.copy(compressionRequired = true, error = null) }
            return
        }
        createPlanAfterContextReady()
    }

    /**
     * Local-only MediaStore search. Returns true if the goal was handled as photo search.
     */
    private fun tryHandleLocalPhotoSearch(goal: String): Boolean {
        val range = PhotoDateQueryParser.parseYearMonth(goal) ?: return false
        val looksLikePhoto =
            goal.contains("照片") || goal.contains("图片") || goal.contains("相册") ||
                goal.contains("photo", ignoreCase = true) || goal.contains("image", ignoreCase = true)
        if (!looksLikePhoto) return false

        if (!localMediaLibrary.hasReadPermission()) {
            pendingPhotoSearchGoal = goal
            _uiState.update {
                it.copy(
                    needsMediaPermission = true,
                    guideMessage = "按日期查找照片需要系统相册只读权限。授权后仅在本机筛选，不会自动上传。",
                    guideChips = listOf("授权相册", "改用选图"),
                    photoCandidates = emptyList(),
                    photoSearchNotice = null,
                    error = null
                )
            }
            return true
        }
        runLocalPhotoSearch(goal, range.first, range.second)
        return true
    }

    fun onMediaPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(needsMediaPermission = false) }
        val goal = pendingPhotoSearchGoal
        pendingPhotoSearchGoal = null
        if (!granted) {
            _uiState.update {
                it.copy(
                    guideMessage = "未获得相册权限。你仍可点「图片」手动选择，或在系统设置中开启权限。",
                    guideChips = listOf("选择图片"),
                    error = null
                )
            }
            return
        }
        if (goal != null) {
            val range = PhotoDateQueryParser.parseYearMonth(goal)
            if (range != null) {
                runLocalPhotoSearch(goal, range.first, range.second)
            }
        }
    }

    private fun runLocalPhotoSearch(goal: String, start: Long, end: Long) {
        if (activeWorkJob?.isActive == true || _uiState.value.busy) return
        setBusy(true)
        activeWorkJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    localMediaLibrary.searchByDate(
                        PhotoSearchQuery(
                            startMillisInclusive = start,
                            endMillisExclusive = end,
                            limit = 50
                        )
                    )
                }
                when (result) {
                    is LocalMediaLibrary.MediaSearchResult.PermissionDenied -> {
                        pendingPhotoSearchGoal = goal
                        _uiState.update {
                            it.copy(
                                needsMediaPermission = true,
                                photoCandidates = emptyList(),
                                photoSearchNotice = null,
                                guideMessage = "相册权限已失效或未授予。请重新授权后仅在本机筛选。",
                                guideChips = listOf("授权相册", "改用选图"),
                                error = null
                            )
                        }
                    }
                    is LocalMediaLibrary.MediaSearchResult.Ok -> {
                        val items = result.items
                        val baseNotice = if (items.isEmpty()) {
                            "在已授权范围内未找到该日期的照片。"
                        } else {
                            "本机找到 ${items.size} 张候选（只读，未上传）。点选一张设为当前附件。"
                        }
                        val notice = listOfNotNull(baseNotice, result.partialAccessNote)
                            .joinToString(" ")
                        _uiState.update {
                            it.copy(
                                photoCandidates = items,
                                photoSearchNotice = notice,
                                guideMessage = null,
                                guideChips = if (items.isEmpty()) listOf("选择图片", "授权相册") else emptyList(),
                                loopLog = (it.loopLog + "本地相册检索：$goal → ${items.size} 张").takeLast(40),
                                error = null,
                                resultNotice = null
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update {
                    it.copy(resultNotice = "已取消相册检索", photoCandidates = emptyList())
                }
            } catch (failure: Throwable) {
                _uiState.update {
                    it.copy(error = failure.safeMessage(), photoCandidates = emptyList())
                }
            } finally {
                activeWorkJob = null
                setBusy(false)
            }
        }
    }

    fun selectPhotoCandidate(item: LocalPhotoItem) {
        selectDocument(Uri.parse(item.uri))
        _uiState.update {
            it.copy(
                resultNotice = "已选择 ${item.displayName} 作为附件",
                photoSearchNotice = "已选中候选图。云端识图请让 Agent 使用 analyze_image（需审批）；本地检索本身不会上传。"
            )
        }
    }

    fun clearPhotoCandidates() {
        _uiState.update {
            it.copy(photoCandidates = emptyList(), photoSearchNotice = null, needsMediaPermission = false)
        }
    }

    fun applyGuideChip(chip: String) {
        when (chip) {
            "去设置" -> setSection(AppSection.SETTINGS)
            "总结文件" -> setDraftGoal("请总结我添加的文件，并列出主要要点与风险")
            "打开网页" -> setDraftGoal("打开我指定的 HTTPS 网页")
            "自由描述" -> setDraftGoal("")
            "改用选图", "选择图片" -> {
                _uiState.update {
                    it.copy(
                        guideMessage = "请点下方「图片」从相册选择（不需要全库权限）。",
                        needsMediaPermission = false,
                        error = null
                    )
                }
            }
            "授权相册" -> {
                _uiState.update { it.copy(needsMediaPermission = true, error = null) }
            }
            "添加文件" -> {
                _uiState.update {
                    it.copy(guideMessage = "请点下方「文件」选择文档。", error = null)
                }
            }
            else -> setDraftGoal(chip)
        }
    }

    fun selectActiveProfile(profileId: String) {
        viewModelScope.launch {
            runCatching { app.providerProfileStore.setActive(profileId) }
                .onSuccess {
                    _uiState.update { it.copy(apiKeyDraft = "", error = null) }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.safeMessage()) }
                }
        }
    }

    fun confirmCompressionAndCreatePlan() {
        val state = _uiState.value
        val threadId = state.selectedThreadId ?: return
        if (activeWorkJob?.isActive == true || state.busy) return
        val history = contextPolicy.requestMessages(state.snapshot?.messages.orEmpty())
        val frozenSettings = state.providerSettings
        val frozenKey = try {
            requireApiKeyForProfile(state.activeProfileId)
        } catch (failure: Throwable) {
            _uiState.update { it.copy(error = failure.safeMessage(), section = AppSection.SETTINGS) }
            return
        }
        setBusy(true)
        activeWorkJob = viewModelScope.launch {
            try {
                beginPlanningBudget()
                runtimeBudget.consumeProviderCall()
                val summary = app.providerClient.compressContext(
                    history = history,
                    settings = frozenSettings,
                    apiKey = frozenKey
                )
                app.repository.addContextSummary(threadId, summary)
                loadSelectedThread()
                _uiState.update { it.copy(compressionRequired = false) }
                activeWorkJob = null
                setBusy(false)
                // Continues with the same planning budget session.
                createPlanAfterContextReady()
            } catch (_: CancellationException) {
                planningBudgetActive = false
                activeWorkJob = null
                setBusy(false)
                _uiState.update {
                    it.copy(compressionRequired = false, resultNotice = "已取消上下文压缩")
                }
            } catch (failure: Throwable) {
                planningBudgetActive = false
                activeWorkJob = null
                setBusy(false)
                _uiState.update {
                    it.copy(error = failure.safeMessage(), compressionRequired = false)
                }
            }
        }
    }

    fun dismissCompression() {
        _uiState.update { it.copy(compressionRequired = false) }
    }

    private fun createPlanAfterContextReady() {
        if (activeWorkJob?.isActive == true || _uiState.value.busy) return
        val state = _uiState.value
        val goal = state.draftGoal.trim()
        // Freeze profile binding for the whole job (avoid mid-flight key/endpoint desync).
        val frozenSettings = state.providerSettings
        val frozenProfileId = state.activeProfileId
        val frozenApiKey = try {
            requireApiKeyForProfile(frozenProfileId)
        } catch (failure: Throwable) {
            _uiState.update { it.copy(error = failure.safeMessage(), section = AppSection.SETTINGS) }
            return
        }
        val baseHistory = contextPolicy.requestMessages(state.snapshot?.messages.orEmpty())
        val memoryNote = UntrustedContext.wrapWorkingMemorySnapshot(documentMemory.snapshotForPrompt())
        val history = if (memoryNote.isBlank()) {
            baseHistory
        } else {
            baseHistory + ThreadMessage(
                threadId = state.selectedThreadId.orEmpty(),
                turnId = null,
                role = com.agentpad.app.domain.MessageRole.USER,
                kind = com.agentpad.app.domain.MessageKind.STATUS,
                content = memoryNote
            )
        }
        val attachment = state.selectedDocument?.toAttachment()
        // Never send raw content:// URIs to the model — metadata only, redacted location.
        val attachmentMetadata = (state.snapshot?.attachments.orEmpty() + listOfNotNull(attachment))
            .map { meta ->
                meta.copy(
                    uri = if (meta.mimeType.startsWith("image/")) {
                        "content://local-image-redacted"
                    } else {
                        "content://local-file-redacted"
                    }
                )
            }
        setBusy(true)
        activeWorkJob = viewModelScope.launch {
            var turn: AgentTurn? = null
            beginPlanningBudget()
            try {
                turn = app.repository.beginTurn(state.selectedThreadId, goal, attachment)
                activeWorkTurnId = turn?.id
                turn?.id?.let { cancelledTurnIds.remove(it) }
                turn?.threadId?.let { documentMemorySession.onThreadOpened(it) }
                _uiState.update {
                    it.copy(
                        selectedThreadId = turn?.threadId,
                        section = AppSection.PLAN,
                        approvalTokens = emptyMap(),
                        compressionRequired = false
                    )
                }
                loadSelectedThread()
                runtimeBudget.consumeProviderCall()
                val rawPlan = app.providerClient.createPlan(
                    goal = goal,
                    history = history,
                    attachments = attachmentMetadata,
                    availableTools = app.toolExecutor.availableTools + setOf("analyze_image"),
                    settings = frozenSettings,
                    apiKey = frozenApiKey
                )
                val plan = planSanitizer.sanitize(rawPlan)
                if (isWorkCancelled(turn?.id)) return@launch
                app.repository.savePlan(requireNotNull(turn), plan)
                _uiState.update {
                    it.copy(
                        draftGoal = "",
                        selectedDocument = null,
                        section = AppSection.PLAN,
                        resultNotice = "计划已生成。只读步骤可自动执行；外部操作需审批。",
                        error = null,
                        loopLog = listOf("计划已生成，共 ${plan.actions.size} 步")
                    )
                }
                loadSelectedThread()
                // Auto-run read-only prefix via AgentLoop; pause when approval needed.
                val loop = AgentLoop(
                    approvalPolicy = policy,
                    sanitizer = planSanitizer,
                    approvalService = approvalService,
                    toolRunner = { action -> runTool(turn!!, action) }
                )
                val outcome = loop.runPlan(
                    plan = plan,
                    mode = RunMode.STANDARD,
                    tokens = _uiState.value.approvalTokens,
                    now = System.currentTimeMillis(),
                    isCancelled = { isWorkCancelled(turn?.id) }
                ) { event ->
                    when (event) {
                        is LoopEvent.ToolStarted -> appendLoopLog("→ ${event.action.tool}")
                        is LoopEvent.ToolFinished -> appendLoopLog(
                            if (event.result.success) "✓ ${event.result.summary}"
                            else "✗ ${event.result.summary}"
                        )
                        is LoopEvent.NeedApproval -> {
                            appendLoopLog("需要审批后继续")
                            _uiState.update {
                                it.copy(
                                    section = AppSection.APPROVALS,
                                    approvalTokens = emptyMap(),
                                    error = "还有外部操作需要批准"
                                )
                            }
                        }
                        is LoopEvent.Finished -> appendLoopLog(event.summary)
                        is LoopEvent.Failed -> appendLoopLog(event.message)
                        is LoopEvent.Message -> appendLoopLog(event.text)
                        is LoopEvent.Cancelled -> appendLoopLog(event.message)
                    }
                }
                val currentTurn = turn ?: return@launch
                when (outcome) {
                    is LoopOutcome.Completed -> {
                        commitTerminal(currentTurn, TurnStatus.COMPLETED, outcome.summary)
                        if (!isWorkCancelled(currentTurn.id)) {
                            _uiState.update {
                                it.copy(
                                    section = AppSection.THREAD,
                                    resultNotice = "任务已完成",
                                    error = null,
                                    approvalTokens = outcome.tokens
                                )
                            }
                            loadSelectedThread()
                        }
                    }
                    is LoopOutcome.PausedForApproval -> {
                        _uiState.update {
                            it.copy(approvalTokens = outcome.tokens)
                        }
                    }
                    is LoopOutcome.Failed -> {
                        commitTerminal(currentTurn, TurnStatus.FAILED, outcome.message)
                        if (!isWorkCancelled(currentTurn.id)) {
                            _uiState.update {
                                it.copy(
                                    error = outcome.message,
                                    resultNotice = null,
                                    section = AppSection.APPROVALS
                                )
                            }
                            loadSelectedThread()
                        }
                    }
                    is LoopOutcome.Cancelled -> {
                        commitTerminal(currentTurn, TurnStatus.CANCELLED, null)
                    }
                }
            } catch (_: CancellationException) {
                turn?.let { cancelled ->
                    runCatching {
                        app.repository.updateStatus(cancelled, TurnStatus.CANCELLED)
                    }
                }
            } catch (failure: Throwable) {
                if (!isWorkCancelled(turn?.id)) {
                    turn?.let {
                        app.repository.updateStatus(it, TurnStatus.FAILED, failure.safeMessage())
                    }
                    _uiState.update { it.copy(error = failure.safeMessage(), resultNotice = null) }
                    loadSelectedThread()
                }
            } finally {
                if (activeWorkTurnId == turn?.id) {
                    activeWorkTurnId = null
                }
                activeWorkJob = null
                planningBudgetActive = false
                setBusy(false)
            }
        }
    }

    fun approveTask() {
        val plan = _uiState.value.currentPlan ?: return
        _uiState.update {
            it.copy(
                approvalTokens = approvalService.approveTask(
                    tokens = it.approvalTokens,
                    plan = plan,
                    now = System.currentTimeMillis(),
                    ttlMillis = APPROVAL_TTL_MILLIS
                ),
                error = null
            )
        }
    }

    fun approveAction(actionId: String) {
        val plan = _uiState.value.currentPlan ?: return
        _uiState.update {
            it.copy(
                approvalTokens = approvalService.approveAction(
                    tokens = it.approvalTokens,
                    plan = plan,
                    actionId = actionId,
                    now = System.currentTimeMillis(),
                    ttlMillis = APPROVAL_TTL_MILLIS
                ),
                error = null
            )
        }
    }

    fun cancelTurn() {
        val turn = _uiState.value.currentTurn
        if (turn == null) {
            // e.g. local media search without a turn
            activeWorkJob?.cancel()
            activeWorkJob = null
            planningBudgetActive = false
            _uiState.update {
                it.copy(
                    busy = false,
                    photoCandidates = emptyList(),
                    resultNotice = "已取消",
                    error = null
                )
            }
            return
        }
        cancelledTurnIds += turn.id
        pruneCancelledTurnIds()
        // Cancel job first so in-flight work stops; then force CANCELLED (may override
        // a racing COMPLETED/FAILED write via repository cancel-override rule).
        activeWorkJob?.cancel()
        activeWorkJob = null
        activeWorkTurnId = null
        planningBudgetActive = false
        viewModelScope.launch {
            app.repository.updateStatus(turn, TurnStatus.CANCELLED)
            _uiState.update {
                it.copy(
                    approvalTokens = emptyMap(),
                    busy = false,
                    resultNotice = "当前回合已取消",
                    error = null
                )
            }
            loadSelectedThread()
        }
    }

    fun executePlan() {
        if (activeWorkJob?.isActive == true || _uiState.value.busy) return
        val state = _uiState.value
        val turn = state.currentTurn ?: return
        if (!turnLifecycle.canExecute(turn)) {
            _uiState.update {
                it.copy(error = "当前回合状态不允许执行，请重新生成计划或取消")
            }
            return
        }
        val loadedPlan = turn.plan ?: return
        val plan = try {
            planSanitizer.sanitize(loadedPlan)
        } catch (failure: Throwable) {
            _uiState.update {
                it.copy(error = failure.safeMessage(), section = AppSection.PLAN)
            }
            return
        }
        val missing = approvalService.missingApprovals(
            state.approvalTokens,
            plan,
            System.currentTimeMillis()
        )
        if (missing.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    section = AppSection.APPROVALS,
                    error = when (turn.status) {
                        TurnStatus.INTERRUPTED ->
                            "中断恢复需要重新审批（${missing.size} 项）"
                        TurnStatus.FAILED ->
                            "失败重试需要重新审批（${missing.size} 项）"
                        else ->
                            "还有 ${missing.size} 项操作需要批准"
                    }
                )
            }
            return
        }
        // Tokens are single-use and consumed before run for safety. Any failure requires re-approval.
        approvalService.consume(state.approvalTokens, plan)
        _uiState.update { it.copy(approvalTokens = emptyMap()) }
        cancelledTurnIds.remove(turn.id)
        setBusy(true)
        activeWorkJob = viewModelScope.launch {
            pendingSummaryResult = null
            planningBudgetActive = false
            runtimeBudget.start()
            activeWorkTurnId = turn.id
            var runningTurn = app.repository.updateStatus(turn.copy(plan = plan), TurnStatus.RUNNING)
            loadSelectedThread()
            if (isWorkCancelled(turn.id)) return@launch
            val engine = buildExecutionEngine(runningTurn)
            try {
                val verified = engine.execute(runningTurn, plan)
                if (isWorkCancelled(turn.id) || shouldIgnoreTerminalWrite(turn)) return@launch
                runningTurn = app.repository.updateStatus(
                    runningTurn,
                    TurnStatus.COMPLETED,
                    verified
                )
                _uiState.update {
                    it.copy(
                        approvalTokens = emptyMap(),
                        section = AppSection.THREAD,
                        resultNotice = "任务已完成",
                        error = null
                    )
                }
            } catch (_: CancellationException) {
                if (!isWorkCancelled(turn.id)) {
                    runCatching {
                        app.repository.updateStatus(runningTurn, TurnStatus.CANCELLED)
                    }
                }
            } catch (failure: Throwable) {
                if (isWorkCancelled(turn.id) || shouldIgnoreTerminalWrite(turn)) return@launch
                app.repository.updateStatus(runningTurn, TurnStatus.FAILED, failure.safeMessage())
                _uiState.update {
                    it.copy(
                        approvalTokens = emptyMap(),
                        section = AppSection.APPROVALS,
                        error = "执行失败，需重新审批后才能再次执行：${failure.safeMessage()}",
                        resultNotice = null
                    )
                }
            } finally {
                if (activeWorkTurnId == turn.id) {
                    activeWorkTurnId = null
                }
                loadSelectedThread()
                activeWorkJob = null
                setBusy(false)
            }
        }
    }

    fun approvalsFor(plan: TaskPlan): List<Pair<String, ApprovalScope>> =
        approvalService.scopesFor(plan)

    fun isTaskApproved(state: AgentPadUiState, plan: TaskPlan): Boolean =
        approvalService.isTaskApproved(
            state.approvalTokens,
            plan,
            System.currentTimeMillis()
        )

    fun isActionApproved(
        state: AgentPadUiState,
        plan: TaskPlan,
        actionId: String
    ): Boolean =
        approvalService.isActionApproved(
            state.approvalTokens,
            plan,
            actionId,
            System.currentTimeMillis()
        )

    fun canResumeCurrentTurn(): Boolean =
        turnLifecycle.canResumeInterrupted(_uiState.value.currentTurn)

    fun requestDeleteThread(threadId: String) {
        if (threadId == _uiState.value.selectedThreadId && checkCanLeaveActiveTurn() == null) {
            return
        }
        _uiState.update { it.copy(deleteConfirmationThreadId = threadId) }
    }

    fun dismissDeleteThread() {
        _uiState.update { it.copy(deleteConfirmationThreadId = null) }
    }

    fun confirmDeleteThread() {
        val threadId = _uiState.value.deleteConfirmationThreadId ?: return
        viewModelScope.launch {
            documentMemorySession.onThreadDeleted(threadId)
            val attachments = app.repository.deleteThread(threadId)
            attachments.map { Uri.parse(it.uri) }.distinct().forEach { uri ->
                runCatching {
                    getApplication<Application>().contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            _uiState.update {
                it.copy(
                    selectedThreadId = null,
                    snapshot = null,
                    deleteConfirmationThreadId = null,
                    approvalTokens = emptyMap(),
                    draftGoal = "",
                    resultNotice = "线程已删除"
                )
            }
            threads.value.firstOrNull()?.takeIf { it.id != threadId }?.let { openThread(it.id) }
        }
    }

    private fun buildExecutionEngine(turn: AgentTurn): ExecutionEngine {
        return ExecutionEngine(
            sanitizer = planSanitizer,
            toolRunner = { action -> runTool(turn, action) },
            audit = { taskId, actionId, eventType, summary ->
                app.repository.audit(taskId, actionId, eventType, summary)
            },
            markVerifying = { current ->
                app.repository.updateStatus(current, TurnStatus.VERIFYING)
            },
            resultExtractor = { result, action ->
                if (
                    result.success &&
                    action.tool in setOf("upload_document_for_summary", "analyze_image")
                ) {
                    pendingSummaryResult
                } else {
                    null
                }
            }
        )
    }

    // Holds the latest document summary produced during a single execute() call.
    private var pendingSummaryResult: String? = null

    private suspend fun runTool(turn: AgentTurn, action: com.agentpad.app.domain.PlannedAction): ToolResult {
        runtimeBudget.checkNotExpired()
        return when (action.tool) {
            "read_document_metadata" -> {
                val doc = requireAttachment(turn)
                ToolResult(
                    action.id,
                    true,
                    "已读取文件元数据",
                    "${doc.name} · ${doc.size ?: 0} bytes"
                )
            }
            "read_document" -> {
                val doc = requireAttachment(turn)
                if (doc.mimeType.startsWith("image/")) {
                    documentMemory.putLocalImageNote(doc.name, doc.mimeType, doc.size)
                    ToolResult(
                        action.id,
                        true,
                        "已挂载图片附件（本机，未上传）",
                        "${doc.name} · ${doc.mimeType} · ${doc.size ?: 0} bytes"
                    )
                } else {
                    val content = readDocument(Uri.parse(doc.uri))
                    // Metadata only — body must not enter model history without ACTION upload.
                    documentMemory.putLocalReadNote(doc.name, content.length)
                    ToolResult(
                        action.id,
                        true,
                        "已在本机读取文件（正文未上传）",
                        "${doc.name} · ${content.length} 字符"
                    )
                }
            }
            "upload_document_for_summary" -> {
                val doc = requireAttachment(turn)
                if (doc.mimeType.startsWith("image/")) {
                    ToolResult(
                        action.id,
                        false,
                        "文本总结不支持图片；请使用 analyze_image（需审批后才会上传图像）",
                        errorCode = "IMAGE_SUMMARY_UNSUPPORTED"
                    )
                } else {
                    val content = readDocument(Uri.parse(doc.uri))
                    documentMemory.putLocalReadNote(doc.name, content.length)
                    runtimeBudget.consumeProviderCall()
                    val frozen = _uiState.value.providerSettings
                    val key = requireApiKey()
                    val summary = app.providerClient.summarizeDocument(
                        goal = turn.plan?.goal ?: turn.goal,
                        documentName = doc.name,
                        content = content,
                        settings = frozen,
                        apiKey = key
                    )
                    pendingSummaryResult = summary
                    ToolResult(
                        action.id,
                        true,
                        "文档总结已完成",
                        "模型返回 ${summary.length} 字符"
                    )
                }
            }
            "analyze_image" -> {
                val doc = requireAttachment(turn)
                if (!doc.mimeType.startsWith("image/")) {
                    ToolResult(
                        action.id,
                        false,
                        "analyze_image 需要图片附件",
                        errorCode = "NOT_IMAGE"
                    )
                } else {
                    val bytes = readBytesCapped(
                        Uri.parse(doc.uri),
                        OpenAiCompatibleClient.MAX_IMAGE_UPLOAD_BYTES
                    )
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    runtimeBudget.consumeProviderCall()
                    val frozen = _uiState.value.providerSettings
                    val key = requireApiKey()
                    val analysis = app.providerClient.analyzeImage(
                        goal = turn.plan?.goal ?: turn.goal,
                        imageName = doc.name,
                        mimeType = doc.mimeType,
                        imageBase64 = b64,
                        settings = frozen,
                        apiKey = key
                    )
                    pendingSummaryResult = analysis
                    ToolResult(
                        action.id,
                        true,
                        "图像分析已完成（已按审批上传至你配置的模型）",
                        "模型返回 ${analysis.length} 字符"
                    )
                }
            }
            else -> app.toolExecutor.executeIntentAction(action)
        }
    }

    private suspend fun readBytesCapped(uri: Uri, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) {
                        "图片超过 ${maxBytes / (1024 * 1024)} MB 上传上限"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("无法读取图片")
        }

    private fun requireAttachment(turn: AgentTurn): ThreadAttachment {
        return _uiState.value.snapshot?.attachments
            ?.lastOrNull { it.turnId == turn.id }
            ?: error("计划需要文件，但当前回合没有可用附件")
    }

    private fun requireApiKey(): String =
        requireApiKeyForProfile(_uiState.value.activeProfileId)

    private fun requireApiKeyForProfile(profileId: String?): String {
        val activeId = profileId
            ?: error("尚未选择模型配置档案，请先在设置中配置 API Key")
        return app.providerProfileStore.readKey(activeId)
            ?: error("当前配置档案尚未保存 API Key，请测试连接并保存")
    }

    private fun isActiveProfileKeyReady(activeProfileId: String?): Boolean {
        val id = activeProfileId ?: return false
        return app.providerProfileStore.hasKey(id)
    }

    private suspend fun commitTerminal(
        turn: AgentTurn,
        status: TurnStatus,
        result: String?
    ) {
        if (isWorkCancelled(turn.id) && status != TurnStatus.CANCELLED) return
        if (status != TurnStatus.CANCELLED && shouldIgnoreTerminalWrite(turn)) return
        app.repository.updateStatus(turn, status, result)
    }

    private fun validateSettings(settings: ProviderSettings) {
        require(settings.providerId in setOf("deepseek", "openai", "custom")) {
            "支持 DeepSeek、OpenAI 或自定义 OpenAI-compatible 服务商"
        }
        require(ProviderTemplates.isEndpointAllowed(settings.endpoint)) {
            "接口必须使用 HTTPS；仅本机回环地址允许 HTTP"
        }
        require(settings.model.isNotBlank()) { "模型名称不能为空" }
    }

    fun saveProviderProfile() {
        val state = _uiState.value
        viewModelScope.launch {
            setBusy(true)
            runCatching {
                validateSettings(state.providerSettings)
                val profile = state.providerProfiles
                    .firstOrNull { it.id == state.activeProfileId }
                    ?.copy(
                        displayName = when (state.providerSettings.providerId) {
                            "deepseek" -> "DeepSeek"
                            "openai" -> "OpenAI"
                            else -> "自定义"
                        },
                        templateId = state.providerSettings.providerId,
                        endpoint = state.providerSettings.endpoint.trim(),
                        model = state.providerSettings.model.trim()
                    )
                    ?: ProviderTemplates.newProfileFromTemplate(
                        state.providerSettings.providerId.ifBlank { "custom" }
                    ).copy(
                        endpoint = state.providerSettings.endpoint.trim(),
                        model = state.providerSettings.model.trim()
                    )
                val candidateKey = state.apiKeyDraft.ifBlank {
                    app.providerProfileStore.readKey(profile.id)
                        ?: error("请输入 API Key")
                }
                app.providerClient.test(profile.toProviderSettings(), candidateKey)
                app.providerProfileStore.upsert(
                    profile = profile,
                    apiKey = if (state.apiKeyDraft.isNotBlank()) candidateKey else null,
                    makeActive = true
                )
                // legacy mirror
                app.secureApiKeyStore.save(candidateKey)
                app.settingsStore.saveProvider(profile.toProviderSettings())
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        apiKeyConfigured = true,
                        apiKeyDraft = "",
                        resultNotice = "连接测试成功，配置档案已加密保存",
                        error = null,
                        guideMessage = null
                    )
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(error = failure.safeMessage(), resultNotice = null) }
            }
            setBusy(false)
        }
    }

    fun addProviderTemplate(templateId: String) {
        viewModelScope.launch {
            val profile = ProviderTemplates.newProfileFromTemplate(templateId)
            setProviderSettings(profile.toProviderSettings())
            _uiState.update {
                it.copy(
                    activeProfileId = profile.id,
                    apiKeyDraft = "",
                    providerProfiles = if (it.providerProfiles.any { p -> p.id == profile.id }) {
                        it.providerProfiles
                    } else {
                        it.providerProfiles + profile
                    },
                    resultNotice = "已载入 ${profile.displayName} 模板，请填写 API Key 并测试保存",
                    apiKeyConfigured = false
                )
            }
            runCatching {
                app.providerProfileStore.upsert(profile, apiKey = null, makeActive = true)
            }
        }
    }

    private suspend fun loadSelectedThread() {
        val threadId = _uiState.value.selectedThreadId ?: return
        val snapshot = app.repository.loadThread(threadId) ?: return
        _uiState.update { it.copy(snapshot = snapshot) }
        app.crashReporter.updateAuditSummaries(app.repository.recentAuditSummaries())
    }

    private fun checkCanLeaveActiveTurn(): Unit? {
        if (!turnLifecycle.canLeave(_uiState.value.currentTurn, activeWorkJob?.isActive == true)) {
            _uiState.update { it.copy(error = "当前回合仍在执行，请先完成或取消") }
            return null
        }
        return Unit
    }

    private fun hasRunningTurn(turn: AgentTurn?): Boolean =
        turnLifecycle.isInFlight(turn)

    private fun beginPlanningBudget() {
        if (!planningBudgetActive) {
            runtimeBudget.start()
            planningBudgetActive = true
        }
    }

    private fun isWorkCancelled(turnId: String?): Boolean =
        turnId != null && turnId in cancelledTurnIds

    private suspend fun shouldIgnoreTerminalWrite(turn: AgentTurn): Boolean {
        if (isWorkCancelled(turn.id)) return true
        val latest = app.repository.loadThread(turn.threadId)
            ?.turns
            ?.firstOrNull { it.id == turn.id }
            ?: return false
        return turnStatusGuard.shouldIgnoreOutcome(latest.status)
    }

    private fun pruneCancelledTurnIds() {
        // Keep the set small; only recent cancel markers matter for in-flight races.
        if (cancelledTurnIds.size > 32) {
            val overflow = cancelledTurnIds.size - 16
            cancelledTurnIds.toList().take(overflow).forEach { cancelledTurnIds.remove(it) }
        }
    }

    private fun appendLoopLog(line: String) {
        _uiState.update { it.copy(loopLog = (it.loopLog + line).takeLast(40)) }
    }

    private fun describeDocument(uri: Uri): SelectedDocument {
        val resolver = getApplication<Application>().contentResolver
        var name = "已选择的文件"
        var size: Long? = null
        val mimeType = resolver.getType(uri).orEmpty().lowercase()
        require(
            mimeType.startsWith("text/") ||
                mimeType.startsWith("image/") ||
                mimeType in setOf(
                    "application/json",
                    "application/xml",
                    "application/octet-stream"
                )
        ) {
            "当前版本支持文本、JSON、XML 与图片"
        }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        val maxBytes =
            if (mimeType.startsWith("image/")) {
                OpenAiCompatibleClient.MAX_IMAGE_UPLOAD_BYTES.toLong()
            } else {
                MAX_DOCUMENT_BYTES.toLong()
            }
        require((size ?: 0L) <= maxBytes) {
            if (mimeType.startsWith("image/")) {
                "图片请控制在 ${OpenAiCompatibleClient.MAX_IMAGE_UPLOAD_BYTES / (1024 * 1024)} MB 以内（与上传上限一致）"
            } else {
                "当前版本只读取 1 MB 以内的文本文件"
            }
        }
        return SelectedDocument(uri, name.take(200), mimeType, size)
    }

    private suspend fun readDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_DOCUMENT_BYTES) { "文件超过当前版本的 1 MB 限制" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: error("无法读取所选文件")
    }

    private fun setBusy(busy: Boolean) {
        _uiState.update { it.copy(busy = busy, error = if (busy) null else it.error) }
    }

    private fun Throwable.safeMessage(): String {
        var value = message.orEmpty()
            .replace(Regex("""sk-[A-Za-z0-9_-]{8,}"""), "***REDACTED***")
            .replace(
                Regex("""Bearer\s+[A-Za-z0-9._-]+""", RegexOption.IGNORE_CASE),
                "Bearer ***REDACTED***"
            )
        val activeId = _uiState.value.activeProfileId
        if (activeId != null) {
            app.providerProfileStore.readKey(activeId)?.let { key ->
                if (key.length >= 8) value = value.replace(key, "***REDACTED***")
            }
        }
        app.secureApiKeyStore.read()?.let { key ->
            if (key.length >= 8) value = value.replace(key, "***REDACTED***")
        }
        value = value.take(500)
        return value.ifBlank { "操作失败，请稍后重试" }
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 1024 * 1024
        private const val MAX_GOAL_CHARS = 4_000
        private const val APPROVAL_TTL_MILLIS = 15 * 60 * 1000L

        fun factory(app: AgentPadApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AgentPadViewModel(app) as T
                }
            }
    }
}
