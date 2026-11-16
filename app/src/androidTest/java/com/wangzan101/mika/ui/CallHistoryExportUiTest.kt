package com.wangzan101.mika.ui

import android.net.Uri
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.ui.theme.FlashPhoneTheme
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Real history controls and result contracts, synthetic local files, emulator only; no phone runtime. */
@RunWith(AndroidJUnit4::class)
class CallHistoryExportUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(WorkspaceEmulatorOnlyRule()).around(ChineseUi()).around(compose)
    private val alias = "history-export-test-${UUID.randomUUID()}"
    private lateinit var directory: File
    private lateinit var destination: File
    private val store = mutableStateOf<HistoryExportStore?>(null)
    private lateinit var saved: SaveableStateRegistry
    private val launches = AtomicInteger()
    private var requestCode = -1
    private val results = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is androidx.activity.result.contract.ActivityResultContracts.CreateDocument)
            this@CallHistoryExportUiTest.requestCode = requestCode
            launches.incrementAndGet()
        }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = results }

    @Before fun preparePrivateSyntheticStorage() {
        directory = File(compose.activity.noBackupFilesDir, alias).apply { check(mkdirs()) }
        destination = File(compose.activity.cacheDir, "$alias.txt")
        store.value = newStore()
    }

    @After fun removeOnlySyntheticData() {
        directory.deleteRecursively()
        destination.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun fullExportKeepsActualSavedStateSmallAndRestoresExactEncryptedSnapshot() {
        // Same legal maxima that serialized the old payload alone to 1,157,636 bytes in review.
        val brief = CallTaskBrief("目".repeat(400), List(8) { "问".repeat(200) }, "背".repeat(1000), "限".repeat(600)).validatedSnapshot()
        val records = List(50) { record("review-$it").copy(task = brief,
            transcript = List(32) { CallTranscriptLine("user", "文".repeat(240)) }) }
        val expected = records.joinToString("\n\n────────\n\n") { historyExport(compose.activity, it) }
        val restore = showHistory(records)
        exportAll()
        awaitLaunch(1)
        assertTrue(directory.listFiles().orEmpty().any { it.extension == "data" })
        compose.runOnIdle {
            val parcel = Parcel.obtain()
            try {
                parcel.writeMap(saved.performSave())
                assertTrue("Actual production Compose saved state must contain references only: ${parcel.dataSize()}", parcel.dataSize() < 64 * 1024)
            } finally { parcel.recycle() }
            store.value = newStore() // No in-memory payload may be required for restoration.
        }
        restore.emulateSavedInstanceStateRestore()
        assertEquals("Restoring must not launch a second picker", 1, launches.get())
        deliver(Uri.fromFile(destination))
        awaitCleanExport(expected)
        assertEquals(expected, destination.readText())
    }

    @Test fun singleRecordExportWritesCompleteSnapshotAndCancellationCleansOnlyItsCopy() {
        val records = listOf(record("single"), record("other"))
        showHistory(records)
        compose.onNodeWithTag("call-history-row-single").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-export").performScrollTo().performClick()
        awaitLaunch(1)
        deliver(Uri.fromFile(destination))
        awaitCleanExport(historyExport(compose.activity, records.first()))
        assertFalse(destination.readText().contains("other-synthetic-transcript"))
        compose.onNodeWithTag("call-history-export").performScrollTo().performClick()
        awaitLaunch(2)
        deliver(null)
        compose.waitUntil(5_000) { directory.listFiles().orEmpty().isEmpty() }
        assertEquals(historyExport(compose.activity, records.first()), destination.readText())
    }

    @Test fun missingSnapshotShowsExportFailureAndLeavesHistoryAvailable() {
        val records = listOf(record("missing"))
        showHistory(records)
        exportAll()
        awaitLaunch(1)
        directory.listFiles().orEmpty().forEach { check(it.delete()) }
        deliver(Uri.fromFile(destination))
        val failure = compose.activity.getString(R.string.flash_history_export_failed)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(failure).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("call-history-row-missing").performScrollTo().assertIsDisplayed()
        assertFalse("Missing speech must not create an empty successful document", destination.exists())
        compose.waitUntil(5_000) { directory.listFiles().orEmpty().isEmpty() }
    }

    private fun newStore() = HistoryExportStore(directory, KeystoreKeyCipher(alias))

    private fun showHistory(records: List<CallRecord>): StateRestorationTester {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                saved = checkNotNull(LocalSaveableStateRegistry.current)
                FlashPhoneTheme(reduceMotion = true) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        CallHistorySection(records, historyWriteFailed = false,
                            onMark = { _, _ -> error("Export must not mark a call") },
                            onDelete = { error("Export must not delete a call") }, exportStore = store.value)
                    }
                }
            }
        }
        return restore
    }

    private fun exportAll() {
        compose.onNodeWithTag("call-history-manage").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-export-all").performClick()
    }

    private fun awaitLaunch(count: Int) = compose.waitUntil(10_000) { launches.get() == count }

    private fun deliver(uri: Uri?) { compose.runOnIdle { results.dispatchResult(requestCode, uri) } }

    private fun awaitCleanExport(expected: String) {
        val size = expected.toByteArray().size.toLong()
        compose.waitUntil(10_000) { destination.isFile && destination.length() == size && directory.listFiles().orEmpty().isEmpty() }
        assertEquals(expected, destination.readText())
    }

    private fun record(id: String) = CallRecord(id, "5550100", CallDirection.INCOMING, 1L, 2L, "合成记录",
        listOf(CallTranscriptLine("user", "$id-synthetic-transcript")))
}
