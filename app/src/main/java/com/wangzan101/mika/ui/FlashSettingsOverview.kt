package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.FlashPhoneColors
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
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val emphasized = prominent || warning
    val shape = RoundedCornerShape(14.dp)
    val background = when {
        prominent -> FlashPhoneColors.Forest
        warning -> c.warnSoft
        else -> Color.Transparent
    }
    val titleColor = if (prominent) FlashPhoneColors.Paper else c.ink
    val subtitleColor = if (prominent) FlashPhoneColors.OnForest else c.ink2
    val iconColor = when {
        prominent -> FlashPhoneColors.Lime
        warning -> c.warn
        else -> c.accent
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(background).testTag(tag)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = if (prominent) 78.dp else 62.dp)
                .padding(vertical = if (emphasized) 15.dp else 10.dp, horizontal = if (emphasized) 14.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(if (prominent) 26.dp else 20.dp), tint = iconColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = if (prominent) 19.sp else 16.sp, lineHeight = if (prominent) 25.sp else 22.sp,
                    fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (!enabled && !prominent) c.ink3 else titleColor)
                Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = subtitleColor)
            }
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = if (emphasized) iconColor else c.ink3)
        }
        if (!emphasized) HorizontalDivider(Modifier.padding(start = 41.dp), color = c.edge, thickness = 0.5.dp)
    }
}

@Composable
internal fun FlashSettingsOverview(
    showTitle: Boolean,
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
    if (showTitle) Text(stringResource(R.string.flash_workspace_settings), fontSize = 28.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
    FlashSettingsGroup(stringResource(R.string.flash_design_device_section))
    FlashSettingsRow(MikaIcons.Phone, stringResource(R.string.flash_design_device),
        deviceSummary, "call-open-device-panel", canConfigure, prominent = true) { onPanel(FlashSettingsPanel.Device) }
    Spacer(Modifier.height(5.dp))
    FlashSettingsRow(MikaIcons.Route, stringResource(R.string.calls_default_sim_settings),
        simSummary, "call-open-sim-panel", canConfigure) { onPanel(FlashSettingsPanel.Sim) }
    FlashSettingsRow(MikaIcons.Shield, stringResource(R.string.flash_workspace_devices_remote_tasks),
        stringResource(R.string.flash_workspace_account_device_connection_and_phone_access), "call-open-remote", canConfigure, onClick = onRemote)
    Spacer(Modifier.height(25.dp))
    FlashSettingsGroup(stringResource(R.string.flash_design_voice_section))
    FlashSettingsRow(MikaIcons.Mic, stringResource(R.string.flash_design_voice),
        stringResource(R.string.flash_design_voice_note), "call-open-voice", canConfigure) { onPanel(FlashSettingsPanel.Voice) }
    FlashSettingsRow(MikaIcons.Doc, stringResource(R.string.call_summary_title),
        stringResource(R.string.call_summary_configure), "call-open-summary-settings", canConfigure, onClick = onSummary)
    FlashSettingsRow(MikaIcons.Spark, stringResource(R.string.flash_design_discussion),
        stringResource(R.string.flash_design_discussion_note), "call-open-discussion-model", canConfigure, onClick = onDiscussionModel)
    FlashSettingsRow(MikaIcons.Sound, stringResource(R.string.voice_clone_title),
        voiceSummary, "call-open-voice-clone", canConfigure, onClick = onVoiceClone)
    Spacer(Modifier.height(21.dp))
    FlashSettingsRow(MikaIcons.Info, stringResource(R.string.flash_workspace_about_updates),
        if (updateAvailable) stringResource(R.string.flash_workspace_an_update_is_available) else "Flash phone",
        "call-open-about", true, onClick = onAbout)
}

@Composable
private fun FlashSettingsGroup(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
            color = FlashPhoneColors.Forest, modifier = Modifier.semantics { heading() })
        HorizontalDivider(Modifier.weight(1f), color = MikaTheme.colors.edge)
    }
}
