package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.theme.MikaIcons

/** The result stays in the active sheet while its device controls can scroll. */
@Composable
internal fun ColumnScope.FlashSettingsPanelContent(
    panel: FlashSettingsPanel,
    feedback: CallActionResult?,
    onDismiss: () -> Unit,
    standbyBlockedReason: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 23.dp).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(when (panel) {
                FlashSettingsPanel.Device -> R.string.flash_design_device
                FlashSettingsPanel.Voice -> R.string.flash_design_voice
                FlashSettingsPanel.Sim -> R.string.calls_default_sim_settings
            }), Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("call-settings-panel-close")) {
                Icon(MikaIcons.Close, stringResource(R.string.calls_cancel))
            }
        }
        Column(Modifier.fillMaxWidth().testTag("call-settings-panel-feedback")) {
            standbyBlockedReason?.let { FooterNote(it, Modifier.testTag("call-standby-blocked-reason")) }
            feedback?.takeUnless { it.message == standbyBlockedReason }?.let { CallFeedback(it) }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp).testTag("call-settings-panel-controls"), content = content)
    }
}
