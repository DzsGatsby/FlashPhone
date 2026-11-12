package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.calls.CallSummarySettingsStore
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.theme.MikaTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Actual settings/store round trips in a temporary folder; synthetic keys and a local fake lister only. */
@RunWith(AndroidJUnit4::class)
class CallSummarySettingsUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val temporary = TemporaryFolder()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule())
        .around(temporary).around(ChineseUi()).around(compose)
    private val cipher = AesGcmKeyCipher { key }
    private val key by lazy { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    private val requests = AtomicInteger()
    private val providerState = mutableStateOf<ProviderStore?>(null)
    private val settingsState = mutableStateOf<CallSummarySettingsStore?>(null)

    @org.junit.Before fun foregroundWindowIsActuallyFocused() {
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun addTwoProvidersEditSelectAndReopenPersistedSettingsWithoutChatRoleChanges() {
        show()
        val originalRoles = checkNotNull(providerState.value).roles.value
        add("Alpha provider", "https://alpha.example/v1", "alpha-chat")
        selectModel("alpha-chat")
        compose.onNodeWithTag("call-summary-model-selection").assertTextContains("alpha-chat")
        add("Beta provider", "https://beta.example/v1", "beta-chat")
        selectModel("beta-chat")
        compose.onNodeWithTag("call-summary-model-selection").assertTextContains("beta-chat")
        compose.onNodeWithTag("call-summary-auto").performScrollTo().performClick()
        compose.waitUntil(5_000) { settingsState.value?.state?.value?.autoGenerate == false }
        assertEquals(0, requests.get())
        assertEquals(originalRoles, providerState.value?.roles?.value)
        assertNoOldBrand()
        captureWorkspaceScreenshot("native-summary-provider-selection.png")

        compose.onNodeWithTag("provider-Alpha provider").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-provider-edit").performScrollTo().performClick()
        compose.onNodeWithTag("setup-base-url").performScrollTo().performTextReplacement("https://alpha.example/v2")
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        compose.waitUntil(5_000) { providerState.value?.providers?.value?.any { it.name == "Alpha provider" && it.baseUrl.endsWith("/v2") } == true }
        awaitProviderDetail("Alpha provider")
        selectModel("alpha-chat")
        val selectedAlpha = checkNotNull(settingsState.value).state.value
        assertEquals(originalRoles, providerState.value?.roles?.value)

        // Recreate both file-backed stores, as on a new app process; no in-memory state is reused.
        compose.runOnIdle { reopenStores() }
        awaitSelected("alpha-chat")
        compose.onNodeWithTag("call-summary-model-selection").assertTextContains("alpha-chat")
        assertEquals(selectedAlpha, settingsState.value?.state?.value)
        assertFalse(checkNotNull(settingsState.value).state.value.autoGenerate)
        assertEquals(2, providerState.value?.providers?.value?.size)
        assertEquals(originalRoles, providerState.value?.roles?.value)
        compose.onNodeWithTag("provider-Alpha provider").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-provider-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-provider-delete-confirm").performClick()
        compose.waitUntil(5_000) {
            providerState.value?.providers?.value?.size == 1 &&
                compose.onAllNodesWithTag("call-summary-model-selection").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("call-summary-model-selection").assertTextContains("当前模型已不可用", substring = true)
        assertEquals("Deletion must retain an invalid explicit selection rather than silently use another provider",
            selectedAlpha.providerId, settingsState.value?.state?.value?.providerId)
        assertEquals(0, requests.get())
        assertNoOldBrand()
    }

    @Test fun missingKeyDisablesSaveAndListFailureCanRecoverWithManualModelWithoutSendingContent() {
        show()
        compose.onNodeWithTag("call-summary-provider-add").performScrollTo().performClick()
        compose.onNodeWithTag("setup-save-untested").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("setup-test").assertIsNotEnabled()
        assertNoOldBrand()
        customForm("Failure provider", "https://failure.example/v1", "manual-chat")
        compose.onNodeWithTag("setup-test").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("setup-test-failed").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("setup-test-failed").performScrollTo().assertTextContains("无法获取模型列表", substring = true)
        assertEquals(1, requests.get())
        assertEquals(0, providerState.value?.providers?.value?.size)
        assertNoOldBrand()
        captureWorkspaceScreenshot("native-summary-provider-list-error.png")
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        awaitProviderDetail("Failure provider")
        selectModel("manual-chat")
        compose.onNodeWithTag("call-summary-model-selection").assertTextContains("manual-chat")
        assertEquals(1, requests.get())
        assertNoOldBrand()
    }

    @Test fun restoringDuringEditClosesFormWithoutPairingDefaultEndpointWithStoredKey() {
        reopenStores()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CallSummarySettingsScreen(onBack = {}, providerStore = providerState.value, summarySettings = settingsState.value)
            }
        }
        add("Alpha provider", "https://alpha.example/v1", "alpha-chat")
        selectModel("alpha-chat")
        compose.onNodeWithTag("provider-Alpha provider").performScrollTo().performClick()
        compose.onNodeWithTag("call-summary-provider-edit").performScrollTo().performClick()
        compose.onNodeWithTag("setup-base-url").performScrollTo().assertTextContains("https://alpha.example/v1")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("setup-base-url").assertDoesNotExist()
        compose.onNodeWithTag("call-summary-provider-edit").performScrollTo().assertIsDisplayed()
        assertEquals("https://alpha.example/v1", providerState.value?.providers?.value?.single()?.baseUrl)
        assertEquals(0, requests.get())
        assertNoOldBrand()
    }

    private fun show() {
        reopenStores()
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CallSummarySettingsScreen(onBack = {}, providerStore = providerState.value, summarySettings = settingsState.value)
            }
        }
    }
    private fun reopenStores() {
        providerState.value = ProviderStore(File(temporary.root, "providers"), cipher, userAgent = "FlashPhone/fixture",
            modelLister = { _, url, _, _ ->
                requests.incrementAndGet()
                if (url.contains("failure")) throw IOException("fixture connection failure")
                ModelListResult.Ok(listOf(ListedModel("listed-fixture")))
            })
        settingsState.value = CallSummarySettingsStore(File(temporary.root, "summary"))
    }
    private fun add(name: String, url: String, model: String) {
        compose.onNodeWithTag("call-summary-provider-add").performScrollTo().performClick()
        customForm(name, url, model)
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        awaitProviderDetail(name)
    }
    private fun selectModel(model: String) {
        compose.onNodeWithText(model, substring = false).performScrollTo().performClick()
        awaitSelected(model)
    }
    private fun awaitSelected(model: String) {
        compose.waitUntil(5_000) {
            settingsState.value?.state?.value?.model == model &&
                compose.onAllNodesWithTag("call-summary-model-selection").fetchSemanticsNodes().size == 1
        }
    }
    private fun awaitProviderDetail(name: String) {
        compose.waitUntil(5_000) {
            providerState.value?.providers?.value?.any { it.name == name } == true &&
                compose.onAllNodesWithTag("call-summary-provider-edit").fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("setup-save-untested").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun customForm(name: String, url: String, model: String) {
        compose.onNodeWithTag("preset-${ProviderPresets.custom.label}").performScrollTo().performClick()
        compose.onNodeWithTag("setup-base-url").performScrollTo().performTextReplacement(url)
        compose.onNodeWithTag("setup-name").performScrollTo().performTextReplacement(name)
        compose.onNodeWithTag("setup-api-key").performScrollTo().performTextReplacement("fixture-key-only")
        compose.onNodeWithTag("setup-manual-model").performScrollTo().performClick()
        compose.onNodeWithTag("setup-model").performScrollTo().performTextReplacement(model)
    }
    private fun assertNoOldBrand() {
        val tree = compose.onRoot(useUnmergedTree = true).printToString().lowercase()
        assertFalse("Phone summary settings must not expose the upstream app brand", "mika" in tree || "米卡" in tree)
    }
}
