package com.wangzan101.mika.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * Acceptance of the current OperatorDeck journeys, not a page-count or navigation-position contract.
 * Runs only on an explicitly opted-in disposable emulator. ChineseUi restores the prior per-app
 * language. This test enters only unsent drafts and cancels the editor; no permissions, call
 * settings, instructions, credentials or records are saved and no call/model/audio check is started.
 */
@RunWith(AndroidJUnit4::class)
class CallWorkspaceVisualTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @Test fun readinessTaskAuditAndInlinePlaybookJourneysAreUsableInChinese() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue("Visual review requires an idle, unconfigured disposable emulator",
            app.calls.state.value.phase == CallPhase.IDLE &&
                !app.calls.settings.state.value.credentialsConfigured && app.calls.history.value.isEmpty())
        val settingsBefore = app.calls.settings.state.value

        ActivityScenario.launch<MainActivity>(workspaceIntent(app)).use { scenario ->
            scenario.onActivity { assertEquals("zh-Hans", it.getString(R.string.ui_locale)) }
            compose.onNodeWithTag("call-open-system").assertIsDisplayed()
            screenshot("operator-deck.png")
            assertWorkspaceLabelsFullyVisible()

            // Follow a real setup concern into the system center and the phone-local recovery screen.
            compose.onNodeWithTag("call-open-system").performClick()
            compose.onNodeWithText(workspaceText(R.string.flash_system_center_title)).assertIsDisplayed()
            compose.onNodeWithTag("call-recovery-section").performScrollTo().assertIsDisplayed()
            screenshot("system-recovery.png")
            compose.onNodeWithText(workspaceText(R.string.calls_helper_name)).performScrollTo().performClick()
            compose.onNodeWithText(workspaceText(R.string.calls_helper_setup_title)).assertIsDisplayed()
            compose.onNodeWithTag("call-recovery-section").performScrollTo().assertIsDisplayed()
            screenshot("helper-recovery.png")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(workspaceText(R.string.flash_system_center_title)).assertIsDisplayed()
            compose.onNodeWithTag("call-open-system").assertIsDisplayed().performClick()

            // The task is a private draft; visiting the audit route must not consume it.
            selectWorkspace(R.string.flash_hub_dial)
            compose.onNodeWithTag("call-number").performScrollTo().performTextReplacement(NUMBER)
            compose.onNodeWithTag("call-task-goal").performScrollTo().performClick()
            compose.waitUntil(5_000) { workspaceImeVisible(scenario) }
            compose.onNodeWithTag("call-task-goal").performTextReplacement(GOAL)
            compose.onNodeWithTag("call-task-goal").assertIsDisplayed().assertIsFocused()
            screenshot("task-keyboard.png")
            compose.onNodeWithTag("call-task-questions").performScrollTo().performTextReplacement(QUESTION)
            hideWorkspaceKeyboard(scenario)
            compose.onNodeWithTag("call-number").performScrollTo().assertTextContains(NUMBER)
            screenshot("task-draft.png")

            selectWorkspace(R.string.flash_hub_audit)
            compose.onNodeWithText(workspaceText(R.string.calls_history_empty)).assertIsDisplayed()
            screenshot("recording-audit-route.png")
            compose.onNodeWithTag("call-open-system").assertIsDisplayed()
            selectWorkspace(R.string.flash_hub_dial)
            compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
            compose.onNodeWithTag("call-task-questions").performScrollTo().assertTextContains(QUESTION)

            // Situational instructions now live in the guard workspace, not a mandatory extra page.
            selectWorkspace(R.string.flash_hub_guard)
            val instructionsBefore = app.calls.instructions.items.value
            compose.onNodeWithTag("call-instruction-add").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-open-system").assertIsDisplayed()
            screenshot("inline-playbook.png")
            compose.onNodeWithTag("call-instruction-add").performClick()
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("call-instruction-title-input").fetchSemanticsNode()
                    .config[SemanticsProperties.Focused]
            }
            compose.onNodeWithTag("call-instruction-title-input").assertIsDisplayed().assertIsFocused()
            compose.waitUntil(5_000) { workspaceImeVisible(scenario) }
            compose.onNodeWithTag("call-instruction-title-input").performTextReplacement("仅用于界面验收，取消不保存")
            compose.onNodeWithTag("call-instruction-title-input").assertTextContains("仅用于界面验收，取消不保存")
            screenshot("instruction-editor-keyboard.png")
            compose.onNodeWithText(workspaceText(R.string.call_instructions_cancel)).performClick()
            compose.onNodeWithTag("call-instruction-title-input").assertDoesNotExist()
            hideWorkspaceKeyboard(scenario)
            compose.onNodeWithTag("call-instruction-add").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(instructionsBefore, app.calls.instructions.items.value)
                assertEquals(settingsBefore, app.calls.settings.state.value)
                assertEquals(CallPhase.IDLE, app.calls.state.value.phase)
            }

            // Real native scrolling must leave the global route back to readiness reachable.
            compose.onNodeWithTag("call-page-receive").performTouchInput { swipeUp(durationMillis = 300) }
            compose.onNodeWithTag("call-open-system").assertIsDisplayed()
            assertWorkspaceLabelsFullyVisible()
            screenshot("operator-deck-scrolled.png")
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        captureWorkspaceScreenshot(name)
    }

    private fun selectWorkspace(@StringRes label: Int) {
        compose.onNode(
            hasText(workspaceText(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).assertIsDisplayed().performClick()
    }

    private fun assertWorkspaceLabelsFullyVisible() {
        for (id in listOf(R.string.flash_hub_guard, R.string.flash_hub_dial, R.string.flash_hub_audit)) {
            val label = workspaceText(id)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(
                hasText(label) and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)),
                useUnmergedTree = true,
            ).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("The full navigation label must have a measured text layout: $label", layouts.isNotEmpty())
            for (layout in layouts) {
                assertTrue("Navigation label must fit without overflow: $label; " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "size=${layout.size}, lines=${layout.lineCount}, " +
                    "ellipsized=${(0 until layout.lineCount).map { layout.isLineEllipsized(it) }}",
                    !layout.hasVisualOverflow)
                assertTrue("Navigation label must not be ellipsized: $label",
                    (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            }
        }
    }

    private companion object {
        const val NUMBER = "15555550123"
        const val GOAL = "只确认营业时间，不预约，不承诺购买。"
        const val QUESTION = "今天几点关门？"
    }
}

internal fun workspaceIntent(app: MikaApp): Intent = Intent(app, MainActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    .putExtra(MainActivity.EXTRA_OPEN_CALLS, true)

internal fun workspaceText(@StringRes id: Int): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

internal fun workspaceImeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
    var visible = false
    scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
    if (visible) return true
    // A dialog has its own window: the Activity's insets can remain unchanged while its IME is shown.
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
    val state = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    return Regex("\\bmInputShown=true\\b|\\bmIsInputViewShown=true\\b").containsMatchIn(state)
}

internal fun hideWorkspaceKeyboard(scenario: ActivityScenario<MainActivity>) {
    scenario.onActivity {
        it.currentFocus?.clearFocus()
        it.window.decorView.windowInsetsController?.hide(WindowInsets.Type.ime())
    }
}

internal fun captureWorkspaceScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    // Main-thread idleness can precede the rendered Surface/IME frame and launch fade completion.
    // This bounded capture-only wait does not drive focus, keyboard visibility or test assertions.
    android.os.SystemClock.sleep(300)
    val directory = File(instrumentation.targetContext.cacheDir, "call-workspace-ui-screenshots").apply { mkdirs() }
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot capture failed" }
    try {
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally { bitmap.recycle() }
}

/** Reject physical phones before changing the per-app test language or opening MainActivity. */
internal class WorkspaceEmulatorOnlyRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue("Pass -e flashPhoneUiEmulator true on the disposable development emulator",
                InstrumentationRegistry.getArguments().getString("flashPhoneUiEmulator") == "true" &&
                    instrumentation.targetContext.packageName == "com.wangzan101.mika.dev")
            val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
            val qemu = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            assumeTrue("Journey review is forbidden on a physical phone", qemu == "1")
            base.evaluate()
        }
    }
}
