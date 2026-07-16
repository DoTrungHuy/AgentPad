package com.agentpad.app

import android.app.Application
import com.agentpad.app.data.AgentPadRepository
import com.agentpad.app.data.ProviderProfileStore
import com.agentpad.app.data.SettingsStore
import com.agentpad.app.data.local.AgentPadDatabase
import com.agentpad.app.diagnostics.CrashReporter
import com.agentpad.app.policy.ApprovalPolicy
import com.agentpad.app.provider.OpenAiCompatibleClient
import com.agentpad.app.security.SecureApiKeyStore
import com.agentpad.app.security.SecureSecretStore
import com.agentpad.app.tool.AndroidToolExecutor
import com.agentpad.app.tool.ToolRegistry

class AgentPadApplication : Application() {
    val crashReporter by lazy { CrashReporter(this) }
    val database by lazy { AgentPadDatabase.get(this) }
    val settingsStore by lazy { SettingsStore(this) }
    val secureApiKeyStore by lazy { SecureApiKeyStore(this) }
    val secureSecretStore by lazy { SecureSecretStore(this) }
    val toolRegistry by lazy { ToolRegistry() }
    val approvalPolicy by lazy { ApprovalPolicy(toolRegistry) }
    val providerClient by lazy { OpenAiCompatibleClient(approvalPolicy) }
    val toolExecutor by lazy { AndroidToolExecutor(this, toolRegistry) }
    val providerProfileStore by lazy {
        ProviderProfileStore(
            context = this,
            secretStore = secureSecretStore,
            legacyKeyStore = secureApiKeyStore,
            settingsStore = settingsStore
        )
    }
    val repository by lazy {
        AgentPadRepository(
            threadDao = database.threadDao(),
            auditDao = database.auditDao()
        )
    }

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
    }
}
