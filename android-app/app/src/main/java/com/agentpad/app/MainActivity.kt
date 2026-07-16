package com.agentpad.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentpad.app.ui.AgentPadRoot
import com.agentpad.app.ui.AgentPadViewModel

class MainActivity : ComponentActivity() {
    private val app: AgentPadApplication
        get() = application as AgentPadApplication

    private val viewModel: AgentPadViewModel by viewModels {
        AgentPadViewModel.factory(app)
    }

    private val requestMediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMediaPermissionResult(granted)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            }.getOrDefault(false)
            if (!persisted) {
                val canRead = runCatching {
                    contentResolver.openInputStream(uri)?.close()
                    true
                }.getOrDefault(false)
                if (!canRead) {
                    viewModel.onDocumentPermissionFailed(uri)
                    return@registerForActivityResult
                }
            }
            viewModel.selectDocument(uri)
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            }.getOrDefault(false)
            if (!persisted) {
                val canRead = runCatching {
                    contentResolver.openInputStream(uri)?.close()
                    true
                }.getOrDefault(false)
                if (!canRead) {
                    viewModel.onDocumentPermissionFailed(uri)
                    return@registerForActivityResult
                }
            }
            viewModel.selectDocument(uri)
        }
    }

    private val createDiagnosticFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching { app.crashReporter.export(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.needsMediaPermission) {
                if (state.needsMediaPermission) {
                    val permission = if (Build.VERSION.SDK_INT >= 33) {
                        android.Manifest.permission.READ_MEDIA_IMAGES
                    } else {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    requestMediaPermission.launch(permission)
                }
            }
            AgentPadRoot(
                viewModel = viewModel,
                onChooseDocument = {
                    openDocument.launch(
                        arrayOf(
                            "text/*",
                            "application/json",
                            "application/xml",
                            "text/markdown"
                        )
                    )
                },
                onChooseImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPrivacyModeChanged = ::applyPrivacyMode,
                onExportDiagnostics = {
                    createDiagnosticFile.launch(
                        "AgentPad-diagnostics-${BuildConfig.VERSION_NAME}.json"
                    )
                }
            )
        }
    }

    private fun applyPrivacyMode(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
