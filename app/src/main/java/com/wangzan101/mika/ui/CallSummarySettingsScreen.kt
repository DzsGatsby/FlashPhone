package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallSummaryModel
import com.wangzan101.mika.calls.CallSummarySettingsStore
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.settings.*
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.*

/** Independent call-summary purpose; never assigns a chat Main/Light role or sends call contents. */
@Composable
internal fun CallSummarySettingsScreen(
    onBack: () -> Unit,
    providerStore: ProviderStore? = null,
    summarySettings: CallSummarySettingsStore? = null,
) {
    val context = LocalContext.current
    val store = remember(providerStore) { runCatching { providerStore ?: ProviderStore.get(context) }.getOrNull() }
    val settings = remember(summarySettings) { runCatching { summarySettings ?: CallSummarySettingsStore.get(context) }.getOrNull() }
    if (store == null || settings == null) {
        BackHandler(onBack = onBack)
        Page(stringResource(R.string.call_summary_settings_title), onBack) {
            FooterNote(stringResource(R.string.call_summary_settings_failed), Modifier.testTag("call-summary-settings-error"))
        }
        return
    }
    val providers by store.providers.collectAsState()
    val configs by store.configs.collectAsState()
    val selection by settings.state.collectAsState()
    val settingsError by settings.error.collectAsState()
    val scope = rememberCoroutineScope()
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    // API keys never enter SavedStateHandle/rememberSaveable or messages.
    var form by remember { mutableStateOf(AddForm.of(ProviderPresets.all.first())) }
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var listing by remember { mutableStateOf<Map<String, ListState>>(emptyMap()) }
    var failure by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val saveError = stringResource(R.string.call_summary_settings_failed)
    val listError = stringResource(R.string.call_summary_list_failed)
    val supported = providers.filter(CallSummaryModel::supportedProvider)
    val opened = supported.firstOrNull { it.id == openedId }
    val editing = providers.firstOrNull { it.id == editingId }
    val busy = form.test == ListState.Running

    fun closeForm() {
        connectionJob?.cancel()
        connectionJob = null
        form = AddForm.of(ProviderPresets.all.first())
        showForm = false
        editingId = null
    }
    fun goBack() {
        when {
            showForm -> closeForm()
            openedId != null -> openedId = null
            else -> onBack()
        }
    }
    fun editForm(change: (AddForm) -> AddForm) {
        connectionJob?.cancel()
        connectionJob = null
        form = change(form).copy(test = ListState.Idle)
        failure = false
    }
    fun select(providerId: String, model: String) {
        if (!Regex("[A-Za-z0-9_./:@+\\-]{1,256}").matches(model.trim())) {
            failure = true
            return
        }
        failure = false
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    store.markTyped(providerId, model.trim())
                    settings.select(providerId, model.trim())
                }
                failure = !ok
                if (ok) openedId = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failure = true }
        }
    }
    fun save(loadModels: Boolean) {
        if (busy) return
        val snapshot = form
        val edited = editing
        form = form.copy(test = ListState.Running)
        failure = false
        connectionJob = scope.launch {
            try {
                val saved = withTimeout(35_000) {
                    withContext(Dispatchers.IO) {
                        val models = if (loadModels) {
                            val key = snapshot.apiKey.takeIf { it.isNotBlank() } ?: edited?.let { store.providerKey(it.id) }
                            val result = try {
                                store.listModels(snapshot.kind, snapshot.baseUrl.trim(), key, snapshot.sessionHeader)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { throw SummaryListUnavailable() }
                            when (result) {
                                is ModelListResult.Ok -> result.models
                                is ModelListResult.Failed -> throw SummaryListUnavailable()
                            }
                        } else null
                        currentCoroutineContext().ensureActive()
                        val typed = edited?.typedModels.orEmpty() + listOfNotNull(snapshot.typedModel.takeIf { it.isNotBlank() })
                        val provider = if (edited != null) {
                            checkNotNull(store.updateProviderConnection(edited.id, snapshot.providerName,
                                snapshot.kind, snapshot.baseUrl.trim(), snapshot.apiKey.takeIf { it.isNotBlank() }, typed))
                        } else store.connect(snapshot.providerName, snapshot.kind, snapshot.baseUrl.trim(),
                            snapshot.apiKey.takeIf { it.isNotBlank() }, models.orEmpty(),
                            sessionHeader = snapshot.sessionHeader, typed = typed, untested = !loadModels)
                        if (edited != null && models != null) store.rememberModels(provider.id, models)
                        provider.id
                    }
                }
                connectionJob = null
                closeForm()
                openedId = saved
            } catch (_: SummaryListUnavailable) {
                form = form.copy(test = ListState.Failed(listError))
            } catch (_: TimeoutCancellationException) {
                form = form.copy(test = ListState.Failed(listError))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { form = form.copy(test = ListState.Failed(saveError)) }
        }
    }
    fun refresh(provider: Provider) {
        if (listing[provider.id] == ListState.Running) return
        listing = listing + (provider.id to ListState.Running)
        scope.launch {
            try {
                val success = withTimeout(35_000) {
                    withContext(Dispatchers.IO) {
                        when (val result = store.listModels(provider)) {
                            is ModelListResult.Ok -> { store.rememberModels(provider.id, result.models); true }
                            is ModelListResult.Failed -> false
                        }
                    }
                }
                listing = listing + (provider.id to if (success) ListState.Idle else ListState.Failed(listError))
            } catch (_: TimeoutCancellationException) { listing = listing + (provider.id to ListState.Failed(listError)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { listing = listing + (provider.id to ListState.Failed(listError)) }
        }
    }
    BackHandler(onBack = ::goBack)
    Page(stringResource(R.string.call_summary_settings_title), ::goBack) {
        if (failure || settingsError) FooterNote(saveError, Modifier.testTag("call-summary-settings-error"), color = com.wangzan101.mika.ui.theme.MikaTheme.colors.bad)
        when {
            showForm -> {
                if (editing != null) FooterNote(stringResource(R.string.call_summary_shared_connection_note))
                AddProviderForm(
                    ModelSetupUi(form = form, providers = supported, formOpen = true),
                    ModelSetupActions(
                        selectPreset = { label -> editForm { AddForm.of(AddForm.presetFor(label)).copy(apiKey = it.apiKey) } },
                        setKind = { kind -> editForm { it.copy(kind = kind, noList = false) } },
                        setBaseUrl = { value -> editForm { it.copy(baseUrl = value, noList = false) } },
                        setApiKey = { value -> editForm { it.copy(apiKey = value) } },
                        setModel = { value -> editForm { it.copy(model = value) } },
                        setManual = { value -> editForm { it.copy(manual = value) } },
                        setName = { value -> editForm { it.copy(name = value.take(120)) } },
                        closeForm = ::closeForm,
                        testAndSave = { save(true) },
                        saveUntested = { save(false) },
                    ),
                    phoneSummary = true, editing = editing != null,
                    hasSavedKey = editing?.let { store.hasKey(it.id) } == true,
                )
            }
            opened != null -> {
                val options = ModelOptions.ofProvider(opened, configs).filterNot(ModelOptions::looksNonChat)
                var modelFilter by rememberSaveable(opened.id) { mutableStateOf("") }
                val shown = options.filter { modelFilter.isBlank() || it.id.contains(modelFilter.trim(), ignoreCase = true) }
                GroupLabel(ModelsCopy.providerName(opened))
                FooterNote(opened.baseUrl)
                FooterNote(stringResource(R.string.call_summary_choose_note))
                val listingState = listing[opened.id]
                if (listingState == ListState.Running) FooterNote(ModelsCopy.LISTING)
                if (listingState is ListState.Failed) FooterNote(listingState.message, Modifier.testTag("call-summary-list-error"))
                if (options.size > 8) SearchField(modelFilter, { modelFilter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("call-summary-model-filter"))
                if (shown.isNotEmpty()) GroupCard {
                    shown.take(80).forEachIndexed { index, option ->
                        RadioRow(option.id, selected = selection.providerId == opened.id && selection.model == option.id,
                            first = index == 0, onClick = { select(opened.id, option.id) })
                    }
                }
                if (shown.size > 80) FooterNote(stringResource(R.string.call_summary_manual_filter_note))
                GroupCard(Modifier.padding(top = 8.dp)) {
                    ManualModel(opened, null, first = true) { select(opened.id, it) }
                }
                if (options.isEmpty()) FooterNote(stringResource(R.string.call_summary_manual_note))
                TextButton(onClick = { refresh(opened) }, enabled = listingState != ListState.Running,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-provider-refresh")) { Text(ModelsCopy.REFRESH) }
                if (opened.signIn == null) TextButton(onClick = {
                    editingId = opened.id
                    form = AddForm(ProviderPresets.custom.label, opened.kind, opened.baseUrl, "",
                        name = opened.name, model = selection.model.takeIf { selection.providerId == opened.id }.orEmpty(), manual = true)
                    showForm = true
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-provider-edit")) {
                    Text(stringResource(R.string.call_summary_edit_provider))
                }
                TextButton(onClick = { deleteId = opened.id }, modifier = Modifier.heightIn(min = 48.dp).testTag("call-summary-provider-delete")) {
                    Text(ModelsCopy.DELETE, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                Text(stringResource(R.string.call_summary_settings_lead), style = MikaType.Caption)
                GroupLabel(stringResource(R.string.call_summary_selected_model))
                GroupCard {
                    val selectedProvider = supported.firstOrNull { it.id == selection.providerId }
                    ListRow(MikaIcons.Doc, if (selection.providerId != null && selectedProvider == null) stringResource(R.string.call_summary_model_unavailable) else selection.model.takeIf { selectedProvider != null }.orEmpty().ifBlank { stringResource(R.string.call_summary_model_unselected) },
                        subtitle = selectedProvider?.let { ModelsCopy.providerName(it) }, first = true,
                        onClick = { if (selectedProvider != null) openedId = selectedProvider.id else if (supported.isNotEmpty()) openedId = supported.first().id else showForm = true },
                        modifier = Modifier.testTag("call-summary-model-selection")) { RowChevron() }
                    ListRow(MikaIcons.Clock, stringResource(R.string.call_summary_auto_title),
                        subtitle = stringResource(R.string.call_summary_auto_note)) {
                        MikaSwitch(selection.autoGenerate, { enabled ->
                            scope.launch { failure = !withContext(Dispatchers.IO) { settings.setAutoGenerate(enabled) } }
                        }, Modifier.testTag("call-summary-auto"))
                    }
                }
                GroupLabel(ModelsCopy.PROVIDERS)
                if (providers.isNotEmpty()) GroupCard {
                    providers.forEachIndexed { index, provider ->
                        if (CallSummaryModel.supportedProvider(provider)) {
                            ProviderRow(provider, stringResource(R.string.call_summary_provider_model_count,
                                ModelOptions.ofProvider(provider, configs).size), first = index == 0) { openedId = provider.id }
                        } else ListRow(MikaIcons.Doc, ModelsCopy.providerName(provider),
                            subtitle = stringResource(R.string.call_summary_provider_unsupported), first = index == 0,
                            modifier = Modifier.testTag("call-summary-provider-unsupported"))
                    }
                }
                GroupCard(Modifier.padding(top = 8.dp)) {
                    ListRow(MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, first = true, onClick = {
                        form = AddForm.of(ProviderPresets.all.first()); editingId = null; showForm = true
                    }, modifier = Modifier.testTag("call-summary-provider-add")) { RowChevron() }
                }
                FooterNote(ModelsCopy.KEY_NOTE)
            }
        }
    }
    val deleting = providers.firstOrNull { it.id == deleteId }
    if (deleting != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text(stringResource(R.string.models_delete_title, deleting.name)) },
        text = { Text(stringResource(R.string.call_summary_delete_note)) },
        confirmButton = { TextButton(onClick = {
            deleteId = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        store.removeProvider(deleting.id)
                    }
                    openedId = null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failure = true }
            }
        }, modifier = Modifier.testTag("call-summary-provider-delete-confirm")) { Text(ModelsCopy.DELETE) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

private class SummaryListUnavailable : Exception()
