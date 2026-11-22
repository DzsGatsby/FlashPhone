package com.wangzan101.mika.ui

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPhase
import com.wangzan101.mika.calls.CallSummarySettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.security.MessageDigest

/** Follows the real settings destinations. It never saves, enrolls, connects, records or calls. */
@RunWith(AndroidJUnit4::class)
class FlashSettingsNavigationUiTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(SettingsNavigationEmulatorOnlyRule())
        .around(ChineseUi()).around(compose)

    @Test fun everySettingsDestinationAndRuleEditorReturnsWithoutChangingSavedData() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue("Navigation review requires an idle, unconfigured disposable emulator",
            app.calls.state.value.phase == CallPhase.IDLE &&
                !app.calls.settings.state.value.credentialsConfigured &&
                app.calls.history.value.isEmpty() && app.providers.providers.value.isEmpty() &&
                !app.callDeviceAgent.state.value.enrolled)
        val savedBefore = savedFingerprint(app)

        ActivityScenario.launch<MainActivity>(workspaceIntent(app)).use { scenario ->
            scenario.onActivity { assertEquals("zh-Hans", it.getString(R.string.ui_locale)) }
            compose.onNodeWithTag("call-tab-settings").assertIsDisplayed().performClick()
            val currentDeviceSummary = app.calls.state.value.backgroundStandbyBlockedReason ?: workspaceText(
                if (app.calls.settings.state.value.enabled) R.string.calls_auto_off else R.string.calls_system_default,
            )
            compose.onNodeWithTag("call-open-device-panel").assertIsDisplayed()
                .assertTextContains(workspaceText(R.string.flash_design_device))
                .assertTextContains(currentDeviceSummary)
            assertSettingsHome()
            captureWorkspaceScreenshot("native-settings-all-entries.png")

            open("call-open-voice")
            compose.onNodeWithTag("call-settings-panel").assertIsDisplayed()
            compose.onNodeWithTag("call-settings-voice").performScrollTo().assertIsDisplayed()
            captureWorkspaceScreenshot("native-settings-voice-sheet.png")
            compose.onNodeWithText(workspaceText(R.string.calls_credentials)).performScrollTo().performClick()
            compose.onNodeWithText(workspaceText(R.string.calls_credentials_note)).performScrollTo().assertIsDisplayed()
            cancelDialog()
            compose.onNodeWithTag("call-settings-voice").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.calls_profile)).performScrollTo().performClick()
            compose.onNode(hasText(workspaceText(R.string.calls_greeting)) and hasSetTextAction()).assertIsDisplayed()
            cancelDialog()
            closePanel()

            open("call-open-device-panel")
            compose.onNodeWithTag("call-settings-device").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-recovery-section").performScrollTo().assertIsDisplayed()
            closePanel()

            open("call-open-sim-panel")
            compose.onNodeWithTag("call-settings-sim").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(workspaceText(R.string.calls_sim_ask_each_time)) and hasAnyAncestor(hasTestTag("call-settings-sim"))).assertIsDisplayed()
            // Viewing the options must not pick a SIM or change the saved preference.
            closePanel()

            open("call-open-discussion-model")
            compose.onNodeWithTag("call-instructions").assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.call_instructions_model)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.call_instructions_api_key)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.call_instructions_connect)).performScrollTo().assertIsNotEnabled()
            captureWorkspaceScreenshot("native-settings-discussion-connection.png")
            back(scenario)
            assertSettingsHome()

            open("call-open-summary-settings")
            compose.onNodeWithText(workspaceText(R.string.call_summary_settings_title)).assertIsDisplayed()
            compose.onNodeWithTag("call-summary-provider-add").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("setup-save-untested").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("setup-test").performScrollTo().assertIsNotEnabled()
            back(scenario) // Cancel the unsaved provider form.
            compose.onNodeWithTag("call-summary-provider-add").performScrollTo().assertIsDisplayed()
            back(scenario)
            assertSettingsHome()

            open("call-open-voice-clone")
            compose.onNodeWithTag("voice-clone-screen").assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.voice_clone_title)).assertIsDisplayed()
            back(scenario)
            assertSettingsHome()

            open("call-open-remote")
            compose.onNodeWithText("设备账号与远程电话任务").assertIsDisplayed()
            compose.onNodeWithText("一次性设备连接码").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("连接设备账号").performScrollTo().assertIsNotEnabled()
            back(scenario)
            assertSettingsHome()

            open("call-open-about")
            compose.onNodeWithTag("about-version").assertIsDisplayed()
            compose.onNodeWithText(AboutCopy.TITLE).assertIsDisplayed()
            back(scenario)
            assertSettingsHome()

            compose.onNodeWithTag("call-tab-receive").assertIsDisplayed().performClick()
            compose.onNodeWithTag("call-page-receive").assertIsDisplayed()
            open("call-rules-scope")
            compose.onNodeWithTag("call-allowlist").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-answer-delay").performScrollTo().assertIsDisplayed()
            cancelDialog()
            open("call-rules-important")
            compose.onNodeWithTag("call-bypass-numbers").performScrollTo().assertIsDisplayed()
            cancelDialog()
            open("call-open-profile")
            compose.onNode(hasText(workspaceText(R.string.calls_greeting)) and hasSetTextAction()).assertIsDisplayed()
            cancelDialog()
            compose.onNodeWithTag("call-page-receive").assertIsDisplayed()
            assertWorkspaceNavigationVisible(compose)

            compose.runOnIdle {
                assertEquals("Opening and cancelling settings must preserve all saved phone data", savedBefore, savedFingerprint(app))
                assertEquals(CallPhase.IDLE, app.calls.state.value.phase)
            }
        }
    }

    private fun open(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun cancelDialog() {
        compose.onNodeWithText(workspaceText(R.string.calls_cancel)).performClick()
        compose.waitForIdle()
    }

    private fun closePanel() {
        compose.onNodeWithTag("call-settings-panel-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-settings-panel").assertDoesNotExist()
        assertSettingsHome()
    }

    private fun assertSettingsHome() {
        compose.onNodeWithTag("call-tab-settings").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("call-open-voice").performScrollTo().assertIsDisplayed()
        assertWorkspaceNavigationVisible(compose)
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Failure output contains only a hash, never credential fields or historical content. */
    private fun savedFingerprint(app: MikaApp): String {
        val stored = listOf(app.calls.settings.state.value, app.calls.history.value,
            app.calls.instructions.items.value, app.providers.providers.value, app.providers.configs.value,
            CallSummarySettingsStore.get(app).state.value)
        return MessageDigest.getInstance("SHA-256").digest(stored.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Checks isolation before ChineseUi changes locale; it does not clear any saved data or drafts. */
private class SettingsNavigationEmulatorOnlyRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue("Pass -e flashPhoneUiEmulator true only on the disposable development emulator",
                InstrumentationRegistry.getArguments().getString("flashPhoneUiEmulator") == "true" &&
                    instrumentation.targetContext.packageName == "com.wangzan101.mika.dev")
            val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
            val qemu = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            assumeTrue("Settings navigation review is forbidden on a physical phone", qemu == "1")
            base.evaluate()
        }
    }
}
