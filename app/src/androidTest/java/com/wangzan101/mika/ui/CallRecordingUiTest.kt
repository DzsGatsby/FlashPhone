package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallDirection
import com.wangzan101.mika.calls.CallRecord
import com.wangzan101.mika.calls.CallRecordingInfo
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Emulator-only native UI acceptance with test-generated audio. No phone runtime, model, real call,
 * microphone capture, business history store, saved preference or service override is used.
 *
 * Opt in explicitly with `-e flashPhoneUiEmulator true` and target this class only.
 * The outer rule checks QEMU before the empty host Activity is launched. The Android runner itself
 * necessarily initializes the target Application, as with every Android instrumentation test.
 */
@RunWith(AndroidJUnit4::class)
class CallRecordingUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(EmulatorOnlyRule()).around(compose)

    private lateinit var fixtures: File
    private lateinit var original: File
    private val opened = AtomicInteger()
    private val closed = CopyOnWriteArrayList<File>()
    private val leases = CopyOnWriteArrayList<File>()
    private val marked = CopyOnWriteArrayList<String>()
    private val deleted = CopyOnWriteArrayList<String>()
    private val cleared = AtomicInteger()
    private val records = mutableStateOf<List<CallRecord>>(emptyList())
    private val visible = mutableStateOf(true)
    private val playbackEnabled = mutableStateOf(true)
    private val missingFile = mutableStateOf(false)
    private val audioManager get() = checkNotNull(compose.activity.getSystemService(AudioManager::class.java))

    @Before fun createSyntheticFixture() {
        fixtures = File(compose.activity.cacheDir, "call-recording-ui-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        original = File(fixtures, "synthetic-stereo.wav")
        writeStereoWav(original)
        records.value = listOf(record("1", partial = true), record("2", handled = true))
        assertFalse("Dedicated emulator must not play unrelated media", audioManager.isMusicActive)
    }

    @After fun removeSyntheticFixture() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        }
        if (::fixtures.isInitialized) fixtures.deleteRecursively()
    }

    @Test fun expandedHistoryDoesNotAutoplayAndNativeControlsAdvancePauseSeekStop() {
        showHistory()
        compose.onNodeWithTag("call-recording-player").assertDoesNotExist()
        expand("1")
        compose.onNodeWithTag("call-recording-partial").assertIsDisplayed()
        assertEquals("Expanding a record must not load its audio", 0, opened.get())
        assertFalse(audioManager.isMusicActive)
        screenshot("recording-expanded.png")

        play()
        compose.waitUntil(5_000) { progress() >= 500f && audioManager.isMusicActive }
        compose.onNodeWithTag("call-recording-play-pause").performClick()
        compose.onNodeWithTag("call-recording-play-pause").assertTextContains(text(R.string.calls_recording_play))
        compose.waitUntil(3_000) { !audioManager.isMusicActive }
        val paused = progress()
        SystemClock.sleep(450)
        assertEquals("Paused playback must not advance", paused, progress(), 80f)

        compose.onNodeWithTag("call-recording-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(6_000f) }
        compose.waitUntil(3_000) { progress() in 5_900f..6_100f }
        play()
        compose.waitUntil(5_000) { progress() > 6_200f && audioManager.isMusicActive }
        compose.onNodeWithTag("call-recording-stop").performClick()
        compose.waitUntil(3_000) { closed.size == 1 && !audioManager.isMusicActive }
        assertEquals(0f, progress(), 1f)
        assertFalse("Stopped player's temporary file must be removed", leases.single().exists())
        compose.onNodeWithTag("call-recording-error").assertDoesNotExist()
        screenshot("recording-stopped.png")
    }

    @Test fun backgroundCollapseAndDisposeReleaseWithoutAutoplayWhenReturning() {
        showHistory()
        expand("1")
        play()
        compose.waitUntil(5_000) { audioManager.isMusicActive }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(3_000) { closed.size == 1 && !audioManager.isMusicActive }
        assertFalse(leases[0].exists())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("call-recording-play-pause").performScrollTo()
            .assertTextContains(text(R.string.calls_recording_play))
        SystemClock.sleep(250)
        assertEquals("Returning to foreground must not reopen the recording", 1, opened.get())

        play()
        historyRow("1").performScrollTo().performClick()
        compose.waitUntil(3_000) { closed.size == 2 && !audioManager.isMusicActive }
        compose.onNodeWithTag("call-recording-player").assertDoesNotExist()
        expand("1")
        assertEquals("Expanding again must not autoplay", 2, opened.get())
        play()
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(3_000) { closed.size == 3 && !audioManager.isMusicActive }
        assertTrue("Every released lease must be removed", leases.all { !it.exists() })
    }

    @Test fun callGateAndMissingFileAreVisibleAndRecoverableWithoutChangingTheRecord() {
        showHistory()
        expand("1")
        compose.runOnIdle { missingFile.value = true }
        compose.onNodeWithTag("call-recording-play-pause").performScrollTo().performClick()
        compose.waitUntil(5_000) { hasNode("call-recording-error") }
        compose.onNodeWithTag("call-recording-error").performScrollTo().assertIsDisplayed()
        assertEquals(2, records.value.size)
        screenshot("recording-unavailable.png")

        compose.runOnIdle { missingFile.value = false }
        play()
        compose.waitUntil(5_000) { audioManager.isMusicActive }
        compose.runOnIdle { playbackEnabled.value = false }
        compose.waitUntil(3_000) { closed.size == 1 && !audioManager.isMusicActive }
        compose.onNodeWithTag("call-recording-play-pause").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.calls_recording_call_active)).assertIsDisplayed()
        compose.runOnIdle { playbackEnabled.value = true }
        compose.onNodeWithTag("call-recording-play-pause").assertTextContains(text(R.string.calls_recording_play))
        assertEquals(1, leases.size)
    }

    @Test fun recordingKeepsHistoryMarkSearchFilterAndDeleteActionsAvailable() {
        showHistory()
        compose.onNodeWithTag("call-history-filter-recorded").performScrollTo().performClick()
        historyRow("1").assertIsDisplayed()
        compose.onNodeWithTag("call-history-clear").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("call-history-clear-confirm").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calls_cancel)).performClick()
        assertEquals("Cancelling clear-all must preserve every synthetic record", 2, records.value.size)
        assertEquals(0, cleared.get())
        expand("1")
        compose.onNodeWithTag("call-history-mark").performScrollTo().performClick()
        compose.waitUntil(3_000) { marked.toList() == listOf("record-1:true") }
        compose.onNodeWithTag("call-history-filter-done").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextReplacement(number("2"))
        compose.onNodeWithTag("call-recording-player").assertDoesNotExist()
        expand("2")
        compose.onNodeWithTag("call-history-delete").performScrollTo().performClick()
        compose.onNodeWithTag("call-history-delete-confirm").assertIsDisplayed().performClick()
        compose.waitUntil(3_000) { deleted.toList() == listOf("record-2") }
        compose.onNodeWithTag("call-history-search").performScrollTo().performTextClearance()
        historyRow("1").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("record-1"), records.value.map { it.id })
        assertEquals("History actions must not autoplay a recording", 0, opened.get())
        screenshot("recording-history-actions.png")
    }

    @Test fun savingRecordingBecomesPlayableWithoutShowingMissingOrAutoplaying() {
        val completed = record("1")
        records.value = listOf(completed.copy(recording = null, recordingPending = true))
        showHistory()
        historyRow("1").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.calls_recording_saving)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calls_recording_missing)).assertDoesNotExist()
        compose.onNodeWithTag("call-recording-player").assertDoesNotExist()
        assertEquals(0, opened.get())
        screenshot("recording-saving.png")

        compose.runOnIdle { records.value = listOf(completed.copy(recordingPending = false)) }
        compose.onNodeWithTag("call-recording-player").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calls_recording_saving)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.calls_recording_missing)).assertDoesNotExist()
        SystemClock.sleep(250)
        assertEquals("Saving completion must not autoplay the new recording", 0, opened.get())
        assertFalse(audioManager.isMusicActive)
        screenshot("recording-ready.png")
    }

    private fun showHistory() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (visible.value) CallHistorySection(
                        records.value, historyWriteFailed = false,
                        onMark = { id, handled ->
                            marked += "$id:$handled"
                            records.value = records.value.map { if (it.id == id) it.copy(handled = handled) else it }
                            CallActionResult(true, "Updated synthetic record")
                        },
                        onDelete = { id ->
                            deleted += id
                            records.value = records.value.filterNot { it.id == id }
                            CallActionResult(true, "Deleted synthetic record")
                        },
                        onOpenRecording = {
                            opened.incrementAndGet()
                            if (missingFile.value) null else {
                                val lease = File(fixtures, "lease-${UUID.randomUUID()}.wav")
                                original.copyTo(lease)
                                leases += lease
                                lease
                            }
                        },
                        onCloseRecording = { lease ->
                            check(lease.canonicalFile.parentFile == fixtures.canonicalFile)
                            closed += lease
                            check(lease.delete()) { "Synthetic recording lease was not deleted" }
                        },
                        playbackEnabled = playbackEnabled.value,
                        onClearAll = {
                            cleared.incrementAndGet()
                            records.value = emptyList()
                            CallActionResult(true, "Cleared synthetic records")
                        },
                    )
                }
            }
        }
    }

    private fun expand(index: String) {
        historyRow(index).performScrollTo().performClick()
        compose.onNodeWithTag("call-recording-player").performScrollTo().assertIsDisplayed()
    }

    private fun play() {
        compose.onNodeWithTag("call-recording-play-pause").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("call-recording-play-pause").fetchSemanticsNode().config[SemanticsProperties.Text]
                .any { it.text == text(R.string.calls_recording_pause) }
        }
    }

    private fun progress(): Float = compose.onNodeWithTag("call-recording-seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun hasNode(tag: String): Boolean = runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false)
    private fun text(id: Int): String = compose.activity.getString(id)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.cacheDir, "call-recording-ui-screenshots").apply { mkdirs() }
        File(folder, name).outputStream().use {
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun record(index: String, handled: Boolean = false, partial: Boolean = false) = CallRecord(
        id = "record-$index", number = number(index), direction = CallDirection.INCOMING,
        startedAtMs = 1_800_000_000_000L, endedAtMs = 1_800_000_012_000L,
        result = "Synthetic recording test", transcript = listOf(CallTranscriptLine("user", "Synthetic caller")),
        handled = handled,
        recording = CallRecordingInfo(UUID.randomUUID().toString(), 12_000L, partial, original.length()),
    )

    private fun number(index: String) = "555010$index"

    private fun historyRow(index: String) = compose.onNode(
        hasText(number(index), substring = true) and !hasTestTag("call-history-search"),
    )

    private fun writeStereoWav(file: File) {
        val rate = 16_000
        val frames = rate * 12
        val size = frames * 4
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + size)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        wav.putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII)).putInt(size)
        for (frame in 0 until frames) {
            wav.putShort((1_400 * sin(2 * PI * 440 * frame / rate)).toInt().toShort())
            wav.putShort((1_400 * sin(2 * PI * 660 * frame / rate)).toInt().toShort())
        }
        file.writeBytes(wav.array())
    }
}

private class EmulatorOnlyRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assumeTrue("Pass -e flashPhoneUiEmulator true for this isolated test",
                InstrumentationRegistry.getArguments().getString("flashPhoneUiEmulator") == "true")
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
            val qemu = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
            assumeTrue("This test is forbidden on physical phones", qemu == "1")
            base.evaluate()
        }
    }
}
