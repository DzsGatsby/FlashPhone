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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.ComposeTestRule
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

/** Native workspace acceptance. No model, call, permission grant or saved business data changes. */
@RunWith(AndroidJUnit4::class)
class CallWorkspaceVisualTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @Test fun homeHistoryReceiveEditorsAndRecoveryAreUsableInChinese() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue("Visual review requires an idle, unconfigured disposable emulator",
            app.calls.state.value.phase == CallPhase.IDLE &&
                !app.calls.settings.state.value.credentialsConfigured && app.calls.history.value.isEmpty())
        val settingsBefore = app.calls.settings.state.value
        val instructionsBefore = app.calls.instructions.items.value
        val historyBefore = app.calls.history.value

        ActivityScenario.launch<MainActivity>(workspaceIntent(app)).use { scenario ->
            scenario.onActivity { assertEquals("zh-Hans", it.getString(R.string.ui_locale)) }
            compose.onNodeWithTag("call-page-home").assertIsDisplayed()
            assertWorkspaceNavigationVisible(compose)
            screenshot("native-home.png")

            // This is the actual dashboard-to-records entry. Empty history must remain honest.
            compose.onNodeWithTag("call-home-history").performScrollTo().performClick()
            compose.onNodeWithText(workspaceText(R.string.calls_history_empty)).performScrollTo().assertIsDisplayed()
            assertWorkspaceNavigationVisible(compose)
            screenshot("native-history-empty.png")

            selectWorkspace("receive")
            compose.onNodeWithTag("call-rules-scope").performScrollTo().performClick()
            compose.onNodeWithTag("call-allowlist").performScrollTo().performClick()
            compose.onNodeWithTag("call-allowlist").performTextReplacement(NUMBER)
            compose.waitUntil(5_000) { workspaceImeVisible(scenario) }
            compose.onNodeWithTag("call-allowlist").assertIsDisplayed().assertIsFocused()
            compose.onNodeWithText(workspaceText(R.string.calls_cancel)).assertIsDisplayed()
            screenshot("native-receive-rules-keyboard.png")
            compose.onNodeWithText(workspaceText(R.string.calls_cancel)).performClick()
            compose.onNodeWithTag("call-allowlist").assertDoesNotExist()
            hideWorkspaceKeyboard(scenario)
            compose.runOnIdle { assertEquals(settingsBefore, app.calls.settings.state.value) }

            compose.onNodeWithTag("call-open-instructions").performScrollTo().performClick()
            compose.onNodeWithTag("call-instruction-add").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("call-instruction-title-input").fetchSemanticsNode()
                    .config[SemanticsProperties.Focused]
            }
            compose.onNodeWithTag("call-instruction-title-input").performTextReplacement("仅用于界面验收，取消不保存")
            compose.waitUntil(5_000) { workspaceImeVisible(scenario) }
            screenshot("native-instruction-keyboard.png")
            compose.onNodeWithTag("call-instruction-title-input").assertIsDisplayed().assertIsFocused()
            compose.onNodeWithText(workspaceText(R.string.call_instructions_cancel)).assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.call_instructions_save)).assertIsDisplayed()
            compose.onNodeWithText(workspaceText(R.string.call_instructions_cancel)).performClick()
            compose.onNodeWithTag("call-instruction-title-input").assertDoesNotExist()
            hideWorkspaceKeyboard(scenario)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("call-page-receive").assertIsDisplayed()

            // Scroll through a real long page; navigation must remain visible without scrolling to it.
            compose.onNodeWithTag("call-page-receive").performTouchInput { swipeUp(durationMillis = 300) }
            assertWorkspaceNavigationVisible(compose)
            screenshot("native-receive-scrolled.png")

            selectWorkspace("settings")
            compose.onNodeWithTag("call-recovery-section").performScrollTo().assertIsDisplayed()
            assertWorkspaceNavigationVisible(compose)
            screenshot("native-settings-recovery.png")
            compose.onNodeWithText(workspaceText(R.string.calls_helper_name)).performScrollTo().performClick()
            compose.onNodeWithText(workspaceText(R.string.calls_helper_setup_title)).assertIsDisplayed()
            compose.onNodeWithTag("call-recovery-section").performScrollTo().assertIsDisplayed()
            screenshot("native-helper-recovery.png")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("call-tab-settings").assertIsDisplayed()
            assertWorkspaceNavigationVisible(compose)
            selectWorkspace("home")
            compose.onNodeWithTag("call-page-home").assertIsDisplayed()

            compose.runOnIdle {
                assertEquals(instructionsBefore, app.calls.instructions.items.value)
                assertEquals(settingsBefore, app.calls.settings.state.value)
                assertEquals(historyBefore, app.calls.history.value)
                assertEquals(CallPhase.IDLE, app.calls.state.value.phase)
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        captureWorkspaceScreenshot(name)
    }

    private fun selectWorkspace(route: String) {
        compose.onNodeWithTag("call-tab-$route").assertIsDisplayed().performClick()
    }

    private companion object {
        const val NUMBER = "15555550123"
    }

}

/** Validate the navigation that is currently rendered, including its measured text (not semantics alone). */
internal fun assertWorkspaceNavigationVisible(compose: ComposeTestRule) {
    val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    val count = tabs.fetchSemanticsNodes().size
    assertTrue("The workspace must expose its navigation", count > 0)
    for (index in 0 until count) tabs[index].assertIsDisplayed()
    val labels = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)),
        useUnmergedTree = true,
    )
    val labelCount = labels.fetchSemanticsNodes().size
    assertTrue("The rendered navigation labels must be available for layout inspection", labelCount > 0)
    for (index in 0 until labelCount) {
        val node = labels[index]
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Navigation label must have a measured text layout", layouts.isNotEmpty())
        for (layout in layouts) {
            assertTrue("Navigation text must fit: widthOverflow=${layout.didOverflowWidth}, " +
                "heightOverflow=${layout.didOverflowHeight}, size=${layout.size}", !layout.hasVisualOverflow)
            assertTrue("Navigation label must not be ellipsized",
                (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        }
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
            // UI drafts persist across normal launches; isolate only this disposable emulator's draft.
            val preferences = instrumentation.targetContext.getSharedPreferences(
                "flash_phone_task_draft", android.content.Context.MODE_PRIVATE,
            )
            val draftBefore = preferences.all.toMap()
            check(preferences.edit().clear().commit()) { "Could not isolate emulator task draft" }
            try {
                base.evaluate()
            } finally {
                val restore = preferences.edit().clear()
                for ((key, value) in draftBefore) when (value) {
                    is String -> restore.putString(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    is Long -> restore.putLong(key, value)
                    is Float -> restore.putFloat(key, value)
                    is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported emulator task draft preference type")
                }
                check(restore.commit()) { "Could not restore emulator task draft" }
            }
        }
    }
}
