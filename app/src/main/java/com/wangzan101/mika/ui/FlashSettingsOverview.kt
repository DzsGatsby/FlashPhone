package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

internal enum class FlashSettingsPanel { Device, Voice, Sim }

/** Flat, descriptive entries keep the same destinations as the full native phone workspace. */
@Composable
internal fun FlashSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    enabled: Boolean = true,
    prominent: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().background(if (prominent) c.fill else c.surface, RoundedCornerShape(12.dp)).testTag(tag)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 64.dp).padding(vertical = 11.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (prominent) c.accent else c.ink2)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = if (enabled) c.ink else c.ink3)
                Text(subtitle, fontSize = 14.sp, lineHeight = 18.sp, color = c.ink3)
            }
            Icon(MikaIcons.Chevron, null, Modifier.size(14.dp), tint = c.ink3)
        }
        if (!prominent) HorizontalDivider(Modifier.padding(start = 46.dp), color = c.edge, thickness = 0.5.dp)
    }
}

@Composable
internal fun FlashSettingsOverview(
    canConfigure: Boolean,
    updateAvailable: Boolean,
    simSummary: String,
    deviceSummary: String,
    voiceSummary: String,
    onPanel: (FlashSettingsPanel) -> Unit,
    onSummary: () -> Unit,
    onDiscussionModel: () -> Unit,
    onVoiceClone: () -> Unit,
    onRemote: () -> Unit,
    onAbout: () -> Unit,
) {
    val c = MikaTheme.colors
    Text(stringResource(R.string.flash_workspace_settings), fontSize = 28.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
    FlashSettingsGroup(stringResource(R.string.flash_design_device_section))
    FlashSettingsRow(MikaIcons.Phone, stringResource(R.string.flash_design_device),
        deviceSummary, "call-open-device-panel", canConfigure, prominent = true) { onPanel(FlashSettingsPanel.Device) }
    FlashSettingsRow(MikaIcons.Route, stringResource(R.string.calls_default_sim_settings),
        simSummary, "call-open-sim-panel", canConfigure) { onPanel(FlashSettingsPanel.Sim) }
    FlashSettingsRow(MikaIcons.Shield, stringResource(R.string.flash_workspace_devices_remote_tasks),
        stringResource(R.string.flash_workspace_account_device_connection_and_phone_access), "call-open-remote", canConfigure, onClick = onRemote)
    Spacer(Modifier.height(20.dp))
    FlashSettingsGroup(stringResource(R.string.flash_design_voice_section))
    FlashSettingsRow(MikaIcons.Mic, stringResource(R.string.flash_design_voice),
        stringResource(R.string.flash_design_voice_note), "call-open-voice", canConfigure) { onPanel(FlashSettingsPanel.Voice) }
    FlashSettingsRow(MikaIcons.Doc, stringResource(R.string.call_summary_title),
        stringResource(R.string.call_summary_configure), "call-open-summary-settings", canConfigure, onClick = onSummary)
    FlashSettingsRow(MikaIcons.Spark, stringResource(R.string.flash_design_discussion),
        stringResource(R.string.flash_design_discussion_note), "call-open-discussion-model", canConfigure, onClick = onDiscussionModel)
    FlashSettingsRow(MikaIcons.Sound, stringResource(R.string.voice_clone_title),
        voiceSummary, "call-open-voice-clone", canConfigure, onClick = onVoiceClone)
    Spacer(Modifier.height(20.dp))
    FlashSettingsRow(MikaIcons.Info, stringResource(R.string.flash_workspace_about_updates),
        if (updateAvailable) stringResource(R.string.flash_workspace_an_update_is_available) else "Flash phone",
        "call-open-about", true, onClick = onAbout)
}

@Composable
private fun FlashSettingsGroup(title: String) {
    Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.ink2,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
}
