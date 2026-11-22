package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.ui.theme.FlashPhoneColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

/** The result stays in the active sheet while its device controls can scroll. */
@Composable
internal fun ColumnScope.FlashSettingsPanelContent(
    panel: FlashSettingsPanel,
    feedback: CallActionResult?,
    onDismiss: () -> Unit,
    standbyBlockedReason: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp).imePadding()) {
        Surface(color = FlashPhoneColors.Forest, contentColor = FlashPhoneColors.Paper,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (panel) {
                    FlashSettingsPanel.Device -> R.string.flash_design_device
                    FlashSettingsPanel.Voice -> R.string.flash_design_voice
                    FlashSettingsPanel.Sim -> R.string.calls_default_sim_settings
                }), Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("call-settings-panel-close")) {
                    Icon(MikaIcons.Close, stringResource(R.string.calls_cancel), tint = FlashPhoneColors.Lime)
                }
            }
        }
        Column(Modifier.fillMaxWidth().testTag("call-settings-panel-feedback")) {
            standbyBlockedReason?.let {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).background(MikaTheme.colors.warnSoft, RoundedCornerShape(10.dp))
                    .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(MikaIcons.Info, null, Modifier.size(18.dp), tint = MikaTheme.colors.warn)
                    Text(it, Modifier.weight(1f).testTag("call-standby-blocked-reason"),
                        color = MikaTheme.colors.ink, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            feedback?.takeUnless { it.message == standbyBlockedReason }?.let { CallFeedback(it) }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp).testTag("call-settings-panel-controls"), content = content)
    }
}
