package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.ui.theme.FlashPhoneTheme
import com.wangzan101.mika.ui.theme.FlashPhoneSheetSystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Production sheet layout with a synthetic device action; never changes device settings. */
@RunWith(AndroidJUnit4::class)
class FlashSettingsFeedbackUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun resultRemainsVisibleInTheSheetAfterActionAtBottomOfLongSettings() {
        val feedback = mutableStateOf<CallActionResult?>(null)
        val failed = "合成失败：系统配置未完成，请重试。"
        val success = "合成结果：准备完成。"
        compose.setContent {
            FlashPhoneTheme {
                ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    FlashPhoneSheetSystemBars()
                    FlashSettingsPanelContent(FlashSettingsPanel.Device, feedback.value, onDismiss = {}) {
                        repeat(24) { Text("合成设置项 $it", Modifier.height(56.dp)) }
                        Button(onClick = { feedback.value = CallActionResult(false, failed) }, modifier = Modifier.testTag("review-device-fail")) { Text("合成失败操作") }
                        Button(onClick = { feedback.value = CallActionResult(true, success) }, modifier = Modifier.testTag("review-device-retry")) { Text("合成重试") }
                    }
                }
            }
        }
        compose.onNodeWithTag("review-device-fail").performScrollTo().performClick()
        compose.onNodeWithText(failed).assertIsDisplayed()
        compose.onNodeWithTag("call-settings-panel-close").assertIsDisplayed()
        captureWorkspaceScreenshot("settings-sheet-failure-visible.png")
        compose.onNodeWithTag("review-device-retry").performScrollTo().performClick()
        compose.onNodeWithText(success).assertIsDisplayed()
        compose.onNodeWithText(failed).assertDoesNotExist()
        compose.onNodeWithTag("call-settings-panel-close").assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun currentStandbyBlockerStaysVisibleAndClearsWhenReadinessReturns() {
        val blocker = mutableStateOf<String?>("Flash phone 内置助手未连接")
        compose.setContent {
            FlashPhoneTheme {
                ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    FlashSettingsPanelContent(FlashSettingsPanel.Device, feedback = null, onDismiss = {}, standbyBlockedReason = blocker.value) {
                        repeat(24) { Text("合成设置项 $it", Modifier.height(56.dp)) }
                        Text("合成的底部设置", Modifier.testTag("review-device-bottom"))
                    }
                }
            }
        }
        val reason = compose.onNodeWithTag("call-standby-blocked-reason")
        reason.assertTextEquals("Flash phone 内置助手未连接").assertIsDisplayed()
        compose.onNodeWithTag("review-device-bottom").performScrollTo().assertIsDisplayed()
        reason.assertIsDisplayed()
        compose.runOnIdle { blocker.value = "请允许 AI 电话通知，以便取消或接管" }
        reason.assertTextEquals("请允许 AI 电话通知，以便取消或接管").assertIsDisplayed()
        compose.runOnIdle { blocker.value = null }
        reason.assertDoesNotExist()
    }
}
