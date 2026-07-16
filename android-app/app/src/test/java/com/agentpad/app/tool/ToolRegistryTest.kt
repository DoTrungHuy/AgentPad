package com.agentpad.app.tool

import com.agentpad.app.domain.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolRegistry()

    @Test
    fun availableToolsAreExactlyTheNativeCoreSet() {
        assertEquals(
            setOf(
                "inspect_task",
                "read_document_metadata",
                "read_document",
                "upload_document_for_summary",
                "analyze_image",
                "open_url",
                "launch_app",
                "share_preview"
            ),
            registry.availableToolNames()
        )
    }

    @Test
    fun plannedAndForbiddenToolsAreNotAvailableOrPlannable() {
        val blocked = listOf(
            "write_document",
            "delete_document",
            "send_text",
            "capture_screen",
            "accessibility_input",
            "install_package",
            "run_command",
            "payment",
            "read_password",
            "read_otp",
            "bypass_lock_screen",
            "silent_install",
            "download_and_execute"
        )

        blocked.forEach { tool ->
            assertFalse(tool, registry.isAvailable(tool))
            assertFalse(tool, registry.isPlannable(tool))
        }
    }

    @Test
    fun riskForUnknownToolIsForbidden() {
        assertEquals(RiskLevel.FORBIDDEN, registry.riskFor("download_and_execute"))
    }

    @Test
    fun openUrlRequiresUrlArgument() {
        assertEquals(setOf("url"), registry.requiredArguments("open_url"))
        assertEquals(setOf("package"), registry.requiredArguments("launch_app"))
        assertEquals(setOf("text"), registry.requiredArguments("share_preview"))
        assertTrue(registry.requiredArguments("inspect_task").isEmpty())
    }

    @Test
    fun sensitiveDocumentUploadIsActionApproval() {
        assertEquals(RiskLevel.ACTION_APPROVAL, registry.riskFor("upload_document_for_summary"))
    }

    @Test
    fun analyzeImageIsActionApproval() {
        assertEquals(RiskLevel.ACTION_APPROVAL, registry.riskFor("analyze_image"))
        assertTrue(registry.isAvailable("analyze_image"))
    }

    @Test
    fun knownNamesIncludePlannedAndForbiddenForDiagnosticsOnly() {
        assertTrue(registry.knownToolNames().contains("install_package"))
        assertTrue(registry.knownToolNames().contains("payment"))
        assertFalse(registry.availableToolNames().contains("install_package"))
        assertFalse(registry.availableToolNames().contains("payment"))
    }
}
