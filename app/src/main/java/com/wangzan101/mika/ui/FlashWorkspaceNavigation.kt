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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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
import com.wangzan101.mika.ui.theme.FlashPhoneColors

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
    showPageTitle: Boolean,
    standalone: Boolean,
    onBack: () -> Unit,
    updateAvailable: Boolean,
    onAbout: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 53.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!standalone) BarIconButton(MikaIcons.Back, stringResource(R.string.flash_workspace_back), onBack)
            if (showPageTitle) {
                Text(title, Modifier.weight(1f), fontSize = 28.sp, lineHeight = 36.sp,
                    fontWeight = FontWeight.SemiBold, color = c.ink)
            } else Row(Modifier.weight(1f).semantics { contentDescription = "flash phone · $title" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("flash phone", modifier = Modifier.weight(1f, fill = false), fontSize = 21.sp, lineHeight = 27.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp, color = c.ink)
                Box(Modifier.padding(top = 4.dp).size(6.dp).background(FlashPhoneColors.CallGreen, CircleShape))
            }
            BarIconButton(MikaIcons.Info, stringResource(R.string.flash_workspace_about_updates), onAbout, badge = updateAvailable)
        }
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
    val configuration = LocalConfiguration.current
    val compactRail = rail && configuration.screenHeightDp < 500
    val selectedColor = c.accent
    val selectedFill = c.accentSoft
    val itemShape = RoundedCornerShape(14.dp)
    val content: @Composable (Modifier) -> Unit = { itemModifier ->
        listOf(CallWorkspaceTab.Task, CallWorkspaceTab.History, CallWorkspaceTab.Receive, CallWorkspaceTab.Settings).forEach { tab ->
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
                CallWorkspaceTab.History -> MikaIcons.Clock
                CallWorkspaceTab.Task -> FlashOutboundIcon
                CallWorkspaceTab.Receive -> MikaIcons.Shield
                CallWorkspaceTab.Settings -> FlashSettingsIcon
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
                    .heightIn(min = if (compactRail) 52.dp else 56.dp)
                    .clip(itemShape)
                    .background(if (active) selectedFill else Color.Transparent)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(tab) })
                    .testTag("call-tab-" + tab.name.lowercase())
                    .semantics {
                        contentDescription = if (badge != null && badge != "•") "$accessibleTitle $badge" else accessibleTitle
                    }
                    .padding(horizontal = 3.dp, vertical = if (compactRail) 4.dp else 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(if (compactRail) 20.dp else 23.dp),
                        tint = if (active) selectedColor else c.ink3)
                    if (badge != null) {
                        if (badge == "•") {
                            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp)
                                .size(6.dp).background(c.accent, CircleShape))
                        } else {
                            Text(badge, Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-5).dp)
                                .background(c.accent, RoundedCornerShape(10.dp)).widthIn(min = 19.dp)
                                .padding(horizontal = 4.dp),
                                fontSize = 10.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, color = c.onAccent)
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(shortTitle, modifier = Modifier.fillMaxWidth(),
                    color = if (active) selectedColor else c.ink3,
                    fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    softWrap = true)
            }
        }
    }
    if (rail) {
        Column(
            Modifier.width(if (configuration.fontScale > 1.3f) 112.dp else 92.dp)
                .fillMaxHeight().background(c.surface).statusBarsPadding()
                .border(0.5.dp, c.edge).verticalScroll(rememberScrollState())
                .padding(horizontal = 7.dp, vertical = if (compactRail) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactRail) 0.dp else 8.dp),
        ) { content(Modifier.fillMaxWidth()) }
    } else {
        val dockShape = RoundedCornerShape(19.dp)
        val narrow = configuration.screenWidthDp < 360 || configuration.fontScale > 1.3f
        Box(Modifier.fillMaxWidth().background(c.bg).padding(
            start = if (narrow) 12.dp else 16.dp,
            end = if (narrow) 12.dp else 16.dp,
            top = 8.dp, bottom = if (configuration.screenHeightDp < 700) 8.dp else 12.dp,
        )) {
            Row(
                Modifier.fillMaxWidth()
                    .background(FlashPhoneColors.Dock, dockShape)
                    .border(1.dp, c.edge, dockShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) { content(Modifier.weight(1f)) }
        }
    }
}

private fun flashNavIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

private val FlashOutboundIcon = flashNavIcon("flash-outbound", "M4 16v4h4M20 8V4h-4M4 20l7-7M20 4l-7 7")
private val FlashSettingsIcon = flashNavIcon("flash-settings", "M4 7h16M4 17h16",
    "M12 7a3 3 0 1 0 -6 0a3 3 0 1 0 6 0M18 17a3 3 0 1 0 -6 0a3 3 0 1 0 6 0")
