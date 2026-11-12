package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

internal enum class CallWorkspaceTab { Home, History, Task, Receive, Settings }

@Composable
internal fun CallWorkspaceTab.title(): String = when (this) {
    CallWorkspaceTab.Home -> stringResource(R.string.flash_workspace_workspace)
    CallWorkspaceTab.History -> stringResource(R.string.flash_workspace_call_history)
    CallWorkspaceTab.Task -> stringResource(R.string.flash_workspace_new_call)
    CallWorkspaceTab.Receive -> stringResource(R.string.flash_workspace_answering)
    CallWorkspaceTab.Settings -> stringResource(R.string.flash_workspace_settings)
}

@Composable
internal fun FlashWorkspaceHeader(
    title: String,
    standalone: Boolean,
    onBack: () -> Unit,
    updateAvailable: Boolean,
    onAbout: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!standalone) BarIconButton(MikaIcons.Back, stringResource(R.string.flash_workspace_back), onBack)
            Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 13.sp, color = c.ink2)
            BarIconButton(MikaIcons.Info, stringResource(R.string.flash_workspace_about_updates), onAbout, badge = updateAvailable)
        }
        HorizontalDivider(color = c.edge, thickness = 0.5.dp)
    }
}

/** One stable task navigation, below the phone viewport or beside a wide viewport. */
@Composable
internal fun FlashWorkspaceNavigation(
    selected: CallWorkspaceTab,
    pendingCount: Int,
    taskHasDraft: Boolean,
    needsSetup: Boolean,
    rail: Boolean,
    onSelect: (CallWorkspaceTab) -> Unit,
) {
    val c = MikaTheme.colors
    val compactRail = rail && LocalConfiguration.current.screenHeightDp < 500
    val content: @Composable (Modifier) -> Unit = { itemModifier ->
        CallWorkspaceTab.entries.forEach { tab ->
            val active = tab == selected
            val isTask = tab == CallWorkspaceTab.Task
            val shortTitle = when (tab) {
                CallWorkspaceTab.Home -> stringResource(R.string.flash_workspace_home)
                CallWorkspaceTab.History -> stringResource(R.string.flash_workspace_history)
                CallWorkspaceTab.Task -> stringResource(R.string.flash_workspace_call)
                CallWorkspaceTab.Receive -> stringResource(R.string.flash_workspace_answer)
                CallWorkspaceTab.Settings -> stringResource(R.string.flash_workspace_setup)
            }
            val accessibleTitle = tab.title()
            val icon = when (tab) {
                CallWorkspaceTab.Home -> MikaIcons.Home
                CallWorkspaceTab.History -> MikaIcons.Doc
                CallWorkspaceTab.Task -> MikaIcons.Plus
                CallWorkspaceTab.Receive -> MikaIcons.Shield
                CallWorkspaceTab.Settings -> MikaIcons.Gear
            }
            val badge = when {
                tab == CallWorkspaceTab.History && pendingCount > 0 -> pendingCount.coerceAtMost(99).toString()
                isTask && taskHasDraft -> "•"
                tab == CallWorkspaceTab.Settings && needsSetup -> "•"
                else -> null
            }
            Column(
                Modifier
                    .then(itemModifier)
                    .heightIn(min = if (compactRail) 56.dp else 64.dp)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(tab) })
                    .testTag("call-tab-" + tab.name.lowercase())
                    .semantics { contentDescription = accessibleTitle }
                    .padding(horizontal = 3.dp, vertical = if (compactRail) 3.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(if (isTask && !compactRail) 40.dp else 28.dp)
                            .background(if (isTask) c.ink else c.surface, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, Modifier.size(21.dp), tint = if (isTask) c.surface else if (active) c.ink else c.ink3)
                    }
                    if (badge != null) {
                        if (badge == "•") {
                            Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-2).dp)
                                .size(6.dp).background(c.ink2, CircleShape))
                        } else {
                            Text(badge, Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-3).dp)
                                .background(c.fill, RoundedCornerShape(10.dp)).widthIn(min = 24.dp)
                                .padding(horizontal = 3.dp),
                                fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, color = c.ink2)
                        }
                    }
                }
                if (!isTask || rail) {
                    Text(shortTitle, modifier = Modifier.fillMaxWidth(),
                        color = if (active) c.ink else c.ink3, fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        softWrap = true)
                }
            }
        }
    }
    if (rail) {
        Column(
            Modifier.width(92.dp).fillMaxHeight().background(c.surface).statusBarsPadding()
                .border(0.5.dp, c.edge).verticalScroll(rememberScrollState()).padding(top = if (compactRail) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactRail) 0.dp else 8.dp),
        ) { content(Modifier.fillMaxWidth()) }
    } else {
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            HorizontalDivider(color = c.edge, thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically) { content(Modifier.weight(1f)) }
        }
    }
}
