package com.wangzan101.mika.calls

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.remote.CallDeviceSettingsScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Disposable emulator only; edits an unsubmitted join draft, never pairs, dials or starts AI. */
class CallDeviceJoinEmulatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun joinFormRequiresAnExplicitCodeAndKeepsRemoteExecutionSeparate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteJoinEmulator") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        assumeTrue(app.packageName.endsWith(".dev"))
        assertFalse("Disposable emulator must not have a real device account", app.callDeviceAgent.state.value.enrolled)
        var returned = false
        compose.setContent { MaterialTheme { CallDeviceSettingsScreen { returned = true } } }
        compose.onNodeWithText("设备账号与远程电话任务").assertIsDisplayed()
        compose.onNodeWithText("打开账号控制台 / 获取一次性连接码").assertIsDisplayed()
        compose.onNodeWithText("连接设备账号").performScrollTo().assertIsNotEnabled()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(app.filesDir, "remote-join-emulator.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNode(hasSetTextAction() and hasText("设备名称")).performTextReplacement("隔离模拟器")
        compose.onNodeWithText("连接设备账号").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("一次性设备连接码")).performTextReplacement("SYNTHETIC")
        compose.onNodeWithText("连接设备账号").assertIsEnabled()
        assertFalse("Unsubmitted draft must not enroll or start transport", app.callDeviceAgent.state.value.enrolled)
        assertFalse(app.callDeviceAgent.state.value.enabled)
        compose.onNodeWithText("返回电话设置").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(returned) }
    }
}
