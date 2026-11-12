package com.wangzan101.mika.ui

import androidx.compose.runtime.saveable.SaverScope
import com.wangzan101.mika.settings.AesGcmKeyCipher
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class CallTaskDraftPersistenceTest {
    private val cipher = AesGcmKeyCipher { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    private val fields = listOf("15555550123", "确认营业时间", "几点关门？", "没有预约", "不能付款", "sim1")
    private fun revision() = CallTaskDraftPersistence.nextRevision()

    @Test fun encryptedDraftSurvivesANewStoreWithoutPlaintextAtRest() {
        var stored: String? = null
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        assertNull(store.load())
        store.save(fields, revision())
        assertFalse(checkNotNull(stored).contains("15555550123"))
        assertFalse(Base64.getDecoder().decode(stored).decodeToString().contains("确认营业时间"))
        assertEquals(fields, CallTaskDraftPersistence({ stored }, { false }, cipher).load())
    }

    @Test fun clearingADraftRemovesItAndAnOlderDelayedSaveCannotBringItBack() {
        var stored: String? = null
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        val old = revision()
        store.save(fields, old)
        store.save(List(6) { "" }, revision())
        store.save(fields, old)
        assertNull(stored)
        assertNull(store.load())
    }

    @Test fun rejectedWriteAndOversizedDraftLeaveThePreviousDraftIntact() {
        var stored: String? = null
        var writable = true
        val store = CallTaskDraftPersistence({ stored }, { if (writable) { stored = it; true } else false }, cipher)
        store.save(fields, revision())
        val before = stored
        writable = false
        assertTrue(runCatching { store.save(fields.map { it + " changed" }, revision()) }.isFailure)
        assertEquals(before, stored)
        writable = true
        assertTrue(runCatching { store.save(listOf("x".repeat(70_000)) + List(5) { "" }, revision()) }.isFailure)
        assertEquals(before, stored)
        assertEquals(fields, store.load())
    }

    @Test fun tamperedCiphertextIsRejectedWithoutOverwritingIt() {
        var stored: String? = null
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        store.save(fields, revision())
        val bytes = Base64.getDecoder().decode(stored)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        stored = Base64.getEncoder().encodeToString(bytes)
        val corrupt = stored
        assertTrue(runCatching { store.load() }.isFailure)
        assertEquals(corrupt, stored)
    }

    private fun recreate(draft: CallTaskDraft): CallTaskDraft {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(CallTaskDraft.Saver) { checkNotNull(scope.save(draft)) }
        return checkNotNull(CallTaskDraft.Saver.restore(saved))
    }

    @Test fun recreationDuringInitialLoadDoesNotTurnEmptyPlaceholdersIntoAnAuthoritativeDraft() {
        var stored: String? = null
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        store.save(fields, revision())
        val initialCiphertext = stored
        val restored = recreate(CallTaskDraft().apply { persistenceLoaded = false })

        // The production load branch must run again after Activity/process state restoration.
        assertFalse(restored.persistenceLoaded)
        assertEquals(initialCiphertext, stored)
        if (!restored.persistenceLoaded) {
            store.load()?.let(restored::restoreFields)
            restored.persistedFields = restored.snapshotFields()
            restored.persistenceLoaded = true
        }
        assertEquals(fields, restored.snapshotFields())
        assertFalse(restored.needsPersistence())
        assertEquals(fields, store.load())
    }

    @Test fun failedInitialReadGuardSurvivesRecreationAndDoesNotEraseUnreadableCiphertext() {
        var stored: String? = "unreadable-existing-draft"
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        assertTrue(runCatching { store.load() }.isFailure)
        val failed = CallTaskDraft().apply {
            persistenceFailed = true
            blockedInitialFields = snapshotFields()
        }
        val restored = recreate(failed)
        assertTrue(restored.persistenceLoaded)
        assertTrue(restored.persistenceFailed)
        assertFalse(restored.needsPersistence())
        // This is the same guard used by the lifecycle flush: untouched placeholders never write.
        if (restored.needsPersistence()) store.save(restored.snapshotFields(), revision())
        assertEquals("unreadable-existing-draft", stored)

        restored.restoreFields(fields)
        assertTrue(restored.needsPersistence())
        val writeRevision = revision()
        restored.beginPersistenceWrite(writeRevision)
        val ok = store.save(restored.snapshotFields(), writeRevision)
        restored.completePersistenceWrite(restored.snapshotFields(), writeRevision, ok)
        assertEquals(fields, store.load())
        assertFalse(restored.persistenceFailed)
    }

    @Test fun cancelledIoWriteCannotMakeARevertedEditorSkipItsRequiredRewrite() {
        var stored: String? = null
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        store.save(fields, revision())
        val draft = CallTaskDraft().apply { restoreFields(fields); persistedFields = fields }
        val changed = fields.toMutableList().also { it[1] = "changed goal" }
        draft.restoreFields(changed)
        val cancelledRevision = revision()
        draft.beginPersistenceWrite(cancelledRevision)
        store.save(changed, cancelledRevision)
        // collectLatest cancelled while non-suspending IO committed. Its completion never applies.
        draft.restoreFields(fields)
        assertEquals(changed, store.load())
        assertTrue(draft.needsPersistence())
        val latestRevision = revision()
        draft.beginPersistenceWrite(latestRevision)
        val ok = store.save(fields, latestRevision)
        draft.completePersistenceWrite(fields, latestRevision, ok)
        draft.completePersistenceWrite(changed, cancelledRevision, true)
        assertEquals(fields, store.load())
        assertEquals(fields, draft.persistedFields)
        assertFalse(draft.needsPersistence())
    }

    @Test fun recreationKeepsUnsavedUserFieldsInsteadOfLoadingAnOlderDiskDraft() {
        var stored: String? = null
        val store = CallTaskDraftPersistence({ stored }, { stored = it; true }, cipher)
        store.save(fields, revision())
        val edited = fields.toMutableList().also { it[1] = "latest unsaved goal" }
        val restored = recreate(CallTaskDraft().apply { restoreFields(edited) })
        assertTrue(restored.persistenceLoaded)
        assertEquals(edited, restored.snapshotFields())
        assertTrue(restored.needsPersistence())
        val latestRevision = revision()
        restored.beginPersistenceWrite(latestRevision)
        store.save(restored.snapshotFields(), latestRevision)
        assertEquals(edited, store.load())
    }

}
