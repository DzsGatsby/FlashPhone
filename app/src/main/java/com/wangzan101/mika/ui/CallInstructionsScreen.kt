package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.codex.*
import com.wangzan101.mika.calls.instructions.CallInstruction
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun CallInstructionsScreen(runtime: CallRuntime, onBack: () -> Unit) {
    KeyboardDismissArea {
        PageScaffold(
            title = stringResource(R.string.call_instructions_title),
            onBack = onBack,
            modifier = Modifier.testTag("call-instructions").imePadding(),
        ) {
            CallPlaybookSection(
                runtime = runtime,
                standalone = true,
                onBack = onBack,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CallPlaybookSection(
    runtime: CallRuntime,
    modifier: Modifier = Modifier,
    standalone: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val store = runtime.instructions
    val rules by store.items.collectAsState()
    val storageError by store.error.collectAsState()
    val providers = remember(context) { ProviderStore.get(context) }
    val owner = remember(context, providers) { CodexOwnerRuntime(context, providers, ownerMemory = store::promptSnapshot) }
    val engine by owner.state.collectAsState()
    val messages by owner.messages.collectAsState()
    val configuredProviders by providers.providers.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<CallInstruction?>(null) }
    var deleting by remember { mutableStateOf<CallInstruction?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var savingConnection by remember { mutableStateOf(false) }
    val busy = savingConnection || engine.phase in setOf(
        CodexOwnerPhase.STARTING,
        CodexOwnerPhase.RESTORING,
        CodexOwnerPhase.RESPONDING,
        CodexOwnerPhase.CANCELLING,
    )
    val savedLabel = stringResource(R.string.call_instructions_saved)
    val failedLabel = stringResource(R.string.call_instructions_failed)
    val deliveryTitle = stringResource(R.string.call_instructions_delivery)
    val deliveryWhen = stringResource(R.string.call_instructions_delivery_when)
    val deliveryReply = stringResource(R.string.call_instructions_delivery_reply)
    val businessTitle = stringResource(R.string.call_instructions_business)
    val businessWhen = stringResource(R.string.call_instructions_business_when)
    val businessReply = stringResource(R.string.call_instructions_business_reply)

    fun mutate(action: () -> Unit) {
        scope.launch {
            feedback = withContext(Dispatchers.IO) {
                runCatching { action(); savedLabel }.getOrElse { failedLabel }
            }
        }
    }
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    LaunchedEffect(owner, standalone, tab) {
        if (standalone || tab == 1) owner.resume()
    }
    LaunchedEffect(engine.model) { if (model.isBlank()) model = engine.model.orEmpty() }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    if (standalone && onBack != null) {
        BackHandler { if (draft != null) draft = null else onBack() }
    } else if (draft != null) {
        BackHandler { draft = null }
    }

    val activeCount = remember(rules) {
        val now = System.currentTimeMillis()
        rules.count { it.enabled && (it.expiresAtMs == null || it.expiresAtMs > now) }
    }

    Column(
        modifier = modifier.fillMaxWidth().then(if (!standalone) Modifier.testTag("call-instructions") else Modifier),
    ) {
        if (!standalone) {
            CallSectionHeader(
                title = stringResource(R.string.flash_guard_playbook_header),
                badge = if (rules.isNotEmpty()) stringResource(R.string.flash_guard_playbook_active_count, activeCount, rules.size)
                else null,
                badgeTone = if (activeCount > 0) TileTone.Ok else TileTone.Neutral,
            )
        }

        SegmentedControl(
            options = listOf(
                if (rules.isEmpty()) stringResource(R.string.call_instructions_rules)
                else "${stringResource(R.string.call_instructions_rules)} (${rules.size})",
                stringResource(R.string.call_instructions_chat),
            ),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(bottom = 12.dp),
        )

        (storageError ?: feedback)?.let { msg ->
            CallFeedback(CallActionResult(ok = storageError == null && msg == savedLabel, message = msg))
        }

        if (tab == 0) {
            CallSurfaceCard(
                modifier = Modifier.padding(bottom = 12.dp),
                tone = if (activeCount > 0) TileTone.Ok else TileTone.Accent,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconTile(MikaIcons.Doc, if (activeCount > 0) TileTone.Ok else TileTone.Accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.call_instructions_rules), style = MikaType.BodyStrong)
                            Text(
                                text = stringResource(R.string.call_instructions_intro),
                                style = MikaType.Caption,
                                color = c.ink2,
                            )
                        }
                    }
                    MikaButton(
                        text = stringResource(R.string.call_instructions_add),
                        onClick = { draft = CallInstruction("", "", "", "") },
                        modifier = Modifier.fillMaxWidth().testTag("call-instruction-add"),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.call_instructions_presets),
                            style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold),
                            color = c.ink3,
                        )
                        PresetRuleRow(
                            title = deliveryTitle,
                            subtitle = deliveryWhen,
                            onClick = { draft = CallInstruction("", deliveryTitle, deliveryWhen, deliveryReply) },
                        )
                        PresetRuleRow(
                            title = businessTitle,
                            subtitle = businessWhen,
                            onClick = { draft = CallInstruction("", businessTitle, businessWhen, businessReply) },
                        )
                    }
                }
            }

            if (rules.isEmpty()) {
                CallSurfaceCard(Modifier.padding(bottom = 12.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.call_instructions_empty),
                            style = MikaType.Body,
                            color = c.ink2,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rules.forEach { rule ->
                    val expired = rule.expiresAtMs?.let { it <= System.currentTimeMillis() } == true
                    val cardTone = when {
                        expired -> TileTone.Warn
                        rule.enabled -> TileTone.Ok
                        else -> TileTone.Neutral
                    }
                    CallSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = cardTone,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rule.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (expired) {
                                            Text(
                                                text = stringResource(R.string.call_instructions_expired),
                                                style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        } else if (rule.expiresAtMs != null) {
                                            val formatted = remember(rule.expiresAtMs) {
                                                java.text.DateFormat.getDateTimeInstance(
                                                    java.text.DateFormat.SHORT,
                                                    java.text.DateFormat.SHORT,
                                                ).format(java.util.Date(rule.expiresAtMs))
                                            }
                                            CallMetaChip(text = formatted, icon = MikaIcons.Clock)
                                        } else {
                                            CallMetaChip(text = stringResource(R.string.call_instructions_forever))
                                        }
                                    }
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { value -> mutate { store.setEnabled(rule.id, value) } },
                                )
                            }

                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(c.fill, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.call_instructions_situation),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.ink3,
                                )
                                Text(
                                    text = rule.situation,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = c.ink,
                                )
                                CallCardDivider()
                                Text(
                                    text = stringResource(R.string.call_instructions_response),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.ink3,
                                )
                                SelectionContainer {
                                    Text(
                                        text = rule.response,
                                        style = MikaType.Body,
                                        color = c.ink,
                                    )
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                OutlinedButton(
                                    onClick = { draft = rule },
                                    shape = MikaShapes.Button,
                                ) {
                                    Text(stringResource(R.string.call_instructions_edit))
                                }
                                TextButton(
                                    onClick = { deleting = rule },
                                    shape = MikaShapes.Button,
                                ) {
                                    Text(
                                        text = stringResource(R.string.call_instructions_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Tab 1: Discuss with Codex
            CallSurfaceCard(
                modifier = Modifier.padding(bottom = 12.dp),
                tone = if (engine.phase == CodexOwnerPhase.UNAVAILABLE) TileTone.Warn else TileTone.Accent,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconTile(MikaIcons.Spark, TileTone.Accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.call_instructions_chat), style = MikaType.BodyStrong)
                            Text(
                                text = stringResource(R.string.call_instructions_codex_intro),
                                style = MikaType.Caption,
                                color = c.ink2,
                            )
                        }
                    }
                    if (engine.phase == CodexOwnerPhase.UNAVAILABLE) {
                        Text(
                            text = stringResource(R.string.call_instructions_unavailable),
                            style = MikaType.Caption,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    engine.error?.let {
                        Text(it, style = MikaType.Caption, color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showConnection = !showConnection },
                            shape = MikaShapes.Button,
                        ) {
                            Text(stringResource(R.string.call_instructions_provider))
                        }
                        TextButton(
                            onClick = { scope.launch { owner.resume() } },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.call_instructions_continue))
                        }
                        TextButton(
                            onClick = { showNewChat = true },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.call_instructions_new_chat))
                        }
                    }
                }
            }

            if (showConnection || engine.phase == CodexOwnerPhase.NEEDS_PROVIDER) {
                CallSurfaceCard(
                    modifier = Modifier.padding(bottom = 12.dp),
                    tone = TileTone.Warn,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val existing = configuredProviders.filter {
                            CodexOwnerRuntime.isOfficialProvider(it)
                        }
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it.take(160) },
                            label = { Text(stringResource(R.string.call_instructions_model)) },
                            supportingText = { Text(stringResource(R.string.call_instructions_model_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().keepsKeyboard(),
                            enabled = !busy,
                        )
                        existing.forEach { provider ->
                            OutlinedButton(
                                onClick = { scope.launch { owner.configureProvider(provider.id, model) } },
                                enabled = !busy && model.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = MikaShapes.Button,
                            ) {
                                Text(stringResource(R.string.call_instructions_use_existing) + " · " + provider.name)
                            }
                        }
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text(stringResource(R.string.call_instructions_api_key)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().keepsKeyboard(),
                            enabled = !busy,
                        )
                        MikaButton(
                            text = stringResource(R.string.call_instructions_connect),
                            onClick = {
                                val entered = key.trim()
                                val requestedModel = model.trim()
                                if (engine.threadId == null && CodexOwnerRuntime.modelName.matches(requestedModel) &&
                                    entered.length in 1..2_048 && entered.all { it.code in 33..126 }
                                ) {
                                    savingConnection = true
                                    scope.launch {
                                        try {
                                            val provider = withContext(Dispatchers.IO) {
                                                // Dedicated identity: never replace another Mika connection or credential.
                                                runCatching {
                                                    providers.connect(
                                                        "Codex · " + java.util.UUID.randomUUID().toString(),
                                                        ProviderConfig.Kind.OpenAiResponses,
                                                        "https://api.openai.com/v1",
                                                        entered,
                                                        emptyList(),
                                                        untested = true,
                                                    )
                                                }.getOrNull()
                                            }
                                            key = ""
                                            if (provider == null || !owner.configureProvider(provider.id, requestedModel)) {
                                                feedback = failedLabel
                                            } else {
                                                showConnection = false
                                            }
                                        } finally {
                                            savingConnection = false
                                        }
                                    }
                                } else {
                                    feedback = failedLabel
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && key.isNotBlank() &&
                                CodexOwnerRuntime.modelName.matches(model.trim()) && engine.threadId == null,
                        )
                        if (engine.threadId != null) {
                            Text(
                                text = stringResource(R.string.call_instructions_new_chat_confirm),
                                style = MikaType.Hint,
                                color = c.ink3,
                            )
                        }
                        Text(
                            text = stringResource(R.string.call_instructions_api_note),
                            style = MikaType.Hint,
                            color = c.ink3,
                        )
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                messages.forEach { message ->
                    val isUser = message.role == CodexOwnerRole.USER
                    CallSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        tone = if (isUser) TileTone.Accent else TileTone.Neutral,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CallStatusBadge(
                                text = stringResource(
                                    if (isUser) R.string.call_instructions_owner else R.string.call_instructions_assistant,
                                ),
                                tone = if (isUser) TileTone.Accent else TileTone.Neutral,
                            )
                            SelectionContainer {
                                Text(message.text, style = MikaType.Body, color = c.ink)
                            }
                            if (message.role == CodexOwnerRole.ASSISTANT && message.complete && message.text.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { draft = CallInstruction("", "", "", message.text) },
                                    shape = MikaShapes.Button,
                                ) {
                                    Text(stringResource(R.string.call_instructions_save_reply))
                                }
                            }
                        }
                    }
                }
            }

            CallSurfaceCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (busy) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.call_instructions_busy), style = MikaType.Body)
                            }
                            TextButton(onClick = { scope.launch { owner.cancel() } }) {
                                Text(stringResource(R.string.call_instructions_stop))
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(CodexOwnerRuntime.MAX_INPUT_CHARS) },
                            label = { Text(stringResource(R.string.call_instructions_message)) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-owner-input"),
                            minLines = 2,
                            maxLines = 8,
                        )
                        MikaButton(
                            text = stringResource(R.string.call_instructions_send),
                            onClick = {
                                val text = input
                                scope.launch { if (owner.send(text)) input = "" }
                            },
                            enabled = input.isNotBlank() && engine.providerId != null &&
                                engine.phase != CodexOwnerPhase.UNAVAILABLE,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    draft?.let { row ->
        InstructionEditor(
            row = row,
            saveError = feedback.takeIf { it == failedLabel },
            onDismiss = { draft = null },
            onSave = { edited ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { store.save(edited) }.isSuccess }
                    if (ok) {
                        draft = null
                        tab = 0
                        feedback = savedLabel
                    } else {
                        feedback = failedLabel
                    }
                }
            },
        )
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.call_instructions_delete)) },
            text = { Text(stringResource(R.string.call_instructions_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleting!!.id
                        deleting = null
                        mutate { store.remove(id) }
                    },
                ) {
                    Text(stringResource(R.string.call_instructions_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.call_instructions_cancel)) }
            },
        )
    }
    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text(stringResource(R.string.call_instructions_new_chat)) },
            text = { Text(stringResource(R.string.call_instructions_new_chat_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewChat = false
                        scope.launch { owner.newChat() }
                    },
                ) {
                    Text(stringResource(R.string.call_instructions_new_chat))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChat = false }) { Text(stringResource(R.string.call_instructions_cancel)) }
            },
        )
    }
}

@Composable
private fun PresetRuleRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.fill, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(MikaIcons.Plus, TileTone.Accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MikaType.BodyStrong, color = c.ink)
            Text(subtitle, style = MikaType.Caption, color = c.ink3)
        }
        RowChevron()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstructionEditor(
    row: CallInstruction,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (CallInstruction) -> Unit,
) {
    var title by remember(row) { mutableStateOf(row.title) }
    var situation by remember(row) { mutableStateOf(row.situation) }
    var response by remember(row) { mutableStateOf(row.response) }
    var expiry by remember(row) { mutableStateOf(row.expiresAtMs) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (row.id.isBlank()) R.string.call_instructions_add else R.string.call_instructions_edit))
        },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        text = {
            val titleFocus = remember { FocusRequester() }
            val window = LocalWindowInfo.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(row, window) {
                // A text field can take focus before the dialog owns the IME window.
                snapshotFlow { window.isWindowFocused }.first { it }
                titleFocus.requestFocus()
                withFrameNanos { }
                keyboard?.show()
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(64) },
                    label = { Text(stringResource(R.string.call_instructions_heading)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("call-instruction-title-input"),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = situation,
                    onValueChange = { situation = it.take(200) },
                    label = { Text(stringResource(R.string.call_instructions_situation)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = response,
                    onValueChange = { response = it },
                    label = { Text(stringResource(R.string.call_instructions_response)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${response.length} / 800") },
                    isError = response.length > 800,
                    minLines = 3,
                    maxLines = 7,
                )
                Text(stringResource(R.string.call_instructions_expiry), style = MaterialTheme.typography.labelLarge)
                val todayEnd = remember {
                    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = expiry == null,
                        onClick = { expiry = null },
                        label = { Text(stringResource(R.string.call_instructions_forever)) },
                    )
                    FilterChip(
                        selected = expiry == todayEnd,
                        onClick = { expiry = todayEnd },
                        label = { Text(stringResource(R.string.call_instructions_today)) },
                    )
                    TextButton(onClick = { expiry = System.currentTimeMillis() + 2 * 60 * 60 * 1_000L }) {
                        Text(stringResource(R.string.call_instructions_two_hours))
                    }
                }
                expiry?.let {
                    Text(
                        text = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MikaTheme.colors.ink3,
                    )
                }
                if (response.length > 800) {
                    Text(stringResource(R.string.call_instructions_review_limit), color = MaterialTheme.colorScheme.error)
                }
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(row.copy(title = title, situation = situation, response = response, expiresAtMs = expiry)) },
                enabled = title.isNotBlank() && situation.isNotBlank() && response.isNotBlank() && response.length <= 800,
                shape = MikaShapes.Button,
            ) {
                Text(stringResource(R.string.call_instructions_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_instructions_cancel)) }
        },
    )
}
