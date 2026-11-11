package com.wangzan101.mika.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Actual full-app visual/navigation acceptance on the disposable emulator only. ChineseUi temporarily
 * selects this app's language and restores it; system locale, permissions and saved call settings are
 * not changed. Only an unsent task draft is entered. No call, model, helper bootstrap or audio test is
 * initiated by this test. Target Application initialization remains normal Android runner behavior.
 */
@RunWith(AndroidJUnit4::class)
class CallWorkspaceVisualTest {
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @Test fun chineseWorkspaceKeepsNavigationDraftAndKeyboardFlowUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MikaApp
        assumeTrue("Visual fixtures require an idle, unconfigured disposable emulator",
            app.calls.state.value.phase == CallPhase.IDLE &&
                !app.calls.settings.state.value.credentialsConfigured && app.calls.history.value.isEmpty())
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_CALLS, true)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { assertEquals("zh-Hans", it.getString(R.string.ui_locale)) }
            assertNavigation()
            screenshot("receive.png")
            scrollPage("receive")
            assertNavigation()
            screenshot("receive-scrolled.png")

            selectTab("task")
            compose.onNodeWithTag("call-number").performScrollTo().performTextReplacement(NUMBER)
            compose.onNodeWithTag("call-task-goal").performScrollTo().performClick()
            compose.waitUntil(5_000) { imeVisible(scenario) }
            compose.onNodeWithTag("call-task-goal").performTextReplacement(GOAL).assertIsDisplayed()
            screenshot("task-keyboard.png")
            compose.onNodeWithTag("call-task-questions").performScrollTo().performTextReplacement(QUESTION)
            hideKeyboard(scenario)
            compose.onNodeWithTag("call-number").performScrollTo().assertTextContains(NUMBER)
            assertNavigation()
            screenshot("task.png")

            selectTab("history")
            assertNavigation()
            screenshot("history.png")
            scrollPage("history")
            assertNavigation()

            selectTab("settings")
            assertNavigation()
            screenshot("settings.png")
            scrollPage("settings")
            assertNavigation()
            screenshot("settings-scrolled.png")

            selectTab("task")
            assertDraft()
            scenario.recreate()
            assertNavigation()
            assertDraft()
            hideKeyboard(scenario)
            compose.onNodeWithTag("call-task-review").performScrollTo().performClick()
            compose.onNodeWithTag("call-task-review-dialog").assertIsDisplayed()
            compose.onNode(hasText(GOAL) and hasAnyAncestor(hasTestTag("call-task-review-dialog")))
                .performScrollTo().assertIsDisplayed()
            screenshot("task-review.png")
            compose.onNodeWithTag("call-task-review-cancel").performClick()
            assertDraft()
            hideKeyboard(scenario)
            selectTab("receive")
            assertNavigation()
            compose.runOnIdle { assertEquals("Visual review must never start a call", CallPhase.IDLE, app.calls.state.value.phase) }
        }
    }

    private fun selectTab(tab: String) {
        compose.onNodeWithTag("call-tab-$tab").assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-page-$tab").assertIsDisplayed()
    }

    private fun assertNavigation() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (tab in listOf("receive", "task", "history", "settings")) {
            val node = compose.onNodeWithTag("call-tab-$tab").assertIsDisplayed().fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("$tab navigation must remain completely reachable after scrolling",
                bounds.width > 0 && bounds.height > 0 &&
                    bounds.left >= root.left - 1f && bounds.right <= root.right + 1f &&
                    bounds.top >= root.top - 1f && bounds.bottom <= root.bottom + 1f)
        }
    }

    private fun scrollPage(tab: String) {
        repeat(2) {
            compose.onNodeWithTag("call-page-$tab").performTouchInput { swipeUp(durationMillis = 300) }
            compose.waitForIdle()
        }
    }

    private fun assertDraft() {
        compose.onNodeWithTag("call-number").performScrollTo().assertTextContains(NUMBER)
        compose.onNodeWithTag("call-task-goal").performScrollTo().assertTextContains(GOAL)
        compose.onNodeWithTag("call-task-questions").performScrollTo().assertTextContains(QUESTION)
    }

    private fun imeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return visible
    }

    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.currentFocus?.clearFocus()
            it.window.decorView.windowInsetsController?.hide(WindowInsets.Type.ime())
        }
        compose.waitUntil(5_000) { !imeVisible(scenario) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "call-workspace-ui-screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot capture failed" }
        try {
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val NUMBER = "15555550123"
        const val GOAL = "只确认营业时间，不预约，不承诺购买。"
        const val QUESTION = "今天几点关门？"
    }
}

/** Reject physical phones before changing even the per-app test language or opening MainActivity. */
private class WorkspaceEmulatorOnlyRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue("Pass -e flashPhoneUiEmulator true on the disposable development emulator",
                InstrumentationRegistry.getArguments().getString("flashPhoneUiEmulator") == "true" &&
                    instrumentation.targetContext.packageName == "com.wangzan101.mika.dev")
            val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
            val qemu = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            assumeTrue("Visual review is forbidden on a physical phone", qemu == "1")
            base.evaluate()
        }
    }
}
