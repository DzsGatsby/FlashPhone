package com.wangzan101.mika.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** One encrypted, unsent draft. This store cannot enqueue or place a call. */
internal class CallTaskDraftPersistence(
    private val read: () -> String?,
    private val write: (String?) -> Boolean,
    private val cipher: KeyCipher,
) {
    fun load(): List<String>? = synchronized(lock) {
        val stored = read() ?: return@synchronized null
        require(stored.length <= 100_000)
        val plain = cipher.decrypt(Base64.getDecoder().decode(stored))
        require(plain.size <= MAX_BYTES)
        Json.decodeFromString(serializer, plain.decodeToString()).also { require(it.size == 6) }
    }

    fun save(fields: List<String>, revision: Long): Boolean = synchronized(lock) {
        if (revision < lastRevision) return@synchronized true
        require(fields.size == 6)
        val plain = Json.encodeToString(serializer, fields).encodeToByteArray()
        require(plain.size <= MAX_BYTES)
        // Encrypt before touching the previous draft. A failed write must not erase it.
        val stored = if (fields.all(String::isBlank)) null else
            Base64.getEncoder().encodeToString(cipher.encrypt(plain))
        check(write(stored)) { "Draft storage unavailable" }
        lastRevision = revision
        true
    }

    companion object {
        private val lock = Any()
        private val counter = AtomicLong()
        private var lastRevision = 0L
        private const val MAX_BYTES = 65_536
        private val serializer = ListSerializer(String.serializer())
        fun nextRevision(): Long = counter.incrementAndGet()
        fun create(context: Context): CallTaskDraftPersistence {
            val prefs = context.getSharedPreferences("flash_phone_task_draft", Context.MODE_PRIVATE)
            return CallTaskDraftPersistence(
                read = { prefs.getString("encrypted_draft_v1", null) },
                write = { value ->
                    val edit = prefs.edit()
                    if (value == null) edit.remove("encrypted_draft_v1") else edit.putString("encrypted_draft_v1", value)
                    edit.commit()
                },
                cipher = KeystoreKeyCipher("flash-phone-task-draft"),
            )
        }
    }
}

@Composable
internal fun rememberPersistentCallTaskDraft(): CallTaskDraft {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CallTaskDraftPersistence.create(context) }
    val draft = rememberSaveable(saver = CallTaskDraft.Saver) {
        CallTaskDraft().apply { persistenceLoaded = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun saveOnLeave() {
        if (!draft.persistenceLoaded) return
        val fields = draft.snapshotFields()
        if (!draft.needsPersistence(fields)) return
        val revision = CallTaskDraftPersistence.nextRevision()
        draft.beginPersistenceWrite(revision)
        draft.completePersistenceWrite(fields, revision, runCatching { store.save(fields, revision) }.getOrDefault(false))
    }

    LaunchedEffect(draft, store) {
        if (!draft.persistenceLoaded) {
            val result = withContext(Dispatchers.IO) { runCatching { store.load() } }
            result.onSuccess { saved ->
                saved?.let(draft::restoreFields)
                draft.persistedFields = draft.snapshotFields()
            }.onFailure {
                draft.persistenceFailed = true
                draft.blockedInitialFields = draft.snapshotFields()
            }
            draft.persistenceLoaded = true
        }
        snapshotFlow { draft.snapshotFields() }.collectLatest { fields ->
            if (fields == draft.persistedFields) {
                draft.persistenceFailed = false
                return@collectLatest
            }
            if (fields == draft.blockedInitialFields) return@collectLatest
            val revision = CallTaskDraftPersistence.nextRevision()
            draft.persistenceGeneration = revision
            delay(350)
            if (draft.persistenceGeneration != revision) return@collectLatest
            draft.beginPersistenceWrite(revision)
            val success = withContext(Dispatchers.IO) {
                runCatching { store.save(fields, revision) }.getOrDefault(false)
            }
            draft.completePersistenceWrite(fields, revision, success)
        }
    }
    DisposableEffect(draft, lifecycle, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) saveOnLeave()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            saveOnLeave()
        }
    }
    return draft
}
