package com.wangzan101.mika.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class CallNotificationRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun callsLinkOpensOnColdAndWarmLaunchAndReturnsFromAbout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(callsIntent(context)).use { scenario ->
            compose.onNodeWithTag("call-settings").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithTag("call-settings").assertIsDisplayed()

            scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("about-version")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("call-settings").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithTag("about-version").assertIsDisplayed()

            scenario.onActivity { it.startActivity(callsIntent(it)) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("call-settings")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("call-settings").assertIsDisplayed()
            compose.onNodeWithTag("about-version").assertDoesNotExist()
        }
    }

    private fun callsIntent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(MainActivity.EXTRA_OPEN_CALLS, true)
}
