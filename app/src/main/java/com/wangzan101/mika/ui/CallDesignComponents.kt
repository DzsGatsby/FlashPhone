package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallActionResult
import com.wangzan101.mika.calls.CallTranscriptLine
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Elevated surface card for Flash phone workspaces.
 * Supports subtle tonal border/background emphasis for live call, active standby, or warnings
 * without heavy blur or decorative clutter.
 */
@Composable
internal fun CallSurfaceCard(
    modifier: Modifier = Modifier,
    tone: TileTone = TileTone.Neutral,
    shape: Shape = MikaShapes.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val borderColor = when (tone) {
        TileTone.Accent -> c.accent.copy(alpha = if (c.isDark) 0.45f else 0.28f)
        TileTone.Ok -> c.ok.copy(alpha = if (c.isDark) 0.45f else 0.28f)
        TileTone.Warn -> c.warnSolid.copy(alpha = if (c.isDark) 0.45f else 0.32f)
        TileTone.Bad -> c.bad.copy(alpha = if (c.isDark) 0.45f else 0.32f)
        TileTone.Neutral -> c.edge
    }
    Column(
        modifier
            .fillMaxWidth()
            .cardSurface(c, shape)
            .border(if (tone == TileTone.Neutral) 0.5.dp else 1.dp, borderColor, shape)
            .clip(shape),
        content = content,
    )
}

/**
 * Status badge with a colored indicator dot and single-line text, suitable for narrow screens.
 */
@Composable
internal fun CallStatusBadge(
    text: String,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val (bg, fg) = when (tone) {
        TileTone.Ok -> c.okSoft to c.ok
        TileTone.Warn -> c.warnSoft to c.warn
        TileTone.Accent -> c.accentSoft to c.accent
        TileTone.Bad -> c.badSoft to c.bad
        TileTone.Neutral -> c.greySoft to c.ink2
    }
    val dotColor = when (tone) {
        TileTone.Ok -> c.ok
        TileTone.Warn -> c.warnSolid
        TileTone.Accent -> c.accent
        TileTone.Bad -> c.bad
        TileTone.Neutral -> c.ink3
    }
    Row(
        modifier
            .background(bg, MikaShapes.Pill)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(dotColor, CircleShape))
        Text(
            text = text,
            style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact metadata pill used in standby overview, task brief headers, and history summaries.
 */
@Composable
internal fun CallMetaChip(
    text: String,
    icon: ImageVector? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val c = MikaTheme.colors
    val (bg, fg) = when (tone) {
        TileTone.Ok -> c.okSoft to c.ok
        TileTone.Warn -> c.warnSoft to c.warn
        TileTone.Accent -> c.accentSoft to c.accent
        TileTone.Bad -> c.badSoft to c.bad
        TileTone.Neutral -> c.fill to c.ink2
    }
    Row(
        modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(13.dp), tint = fg)
        }
        Text(
            text = text,
            style = MikaType.Hint.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Section header with optional trailing badge or count.
 */
@Composable
internal fun CallSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeTone: TileTone = TileTone.Neutral,
) {
    val c = MikaTheme.colors
    Row(
        modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink2,
        )
        if (!badge.isNullOrEmpty()) {
            CallMetaChip(text = badge, tone = badgeTone)
        }
    }
}

/**
 * Notice / feedback banner that replaces plain footer notes for action feedback.
 */
@Composable
internal fun CallFeedback(result: CallActionResult) {
    val c = MikaTheme.colors
    val bg = if (result.ok) c.fill else c.badSoft
    val border = if (result.ok) c.edge else c.bad.copy(alpha = 0.35f)
    val fg = if (result.ok) c.ink2 else MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(bg, shape)
            .border(0.5.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (result.ok) MikaIcons.Check else MikaIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (result.ok) c.ok else fg,
        )
        Text(
            text = result.message,
            style = MikaType.Caption,
            color = fg,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Expandable fold row with icon tile, title, optional subtitle, and clear chevron indicator.
 */
@Composable
internal fun CallFoldRow(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    testTag: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val expansion = stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = expansion }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, if (expanded && tone == TileTone.Neutral) TileTone.Accent else tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MikaType.Body.copy(fontWeight = FontWeight.Medium),
                color = c.ink,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MikaType.Caption,
                    color = when (tone) {
                        TileTone.Warn -> c.warn
                        TileTone.Bad -> c.bad
                        else -> c.ink3
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .size(28.dp)
                .background(if (expanded) c.accentSoft else c.fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CallExpandIcon(expanded)
        }
    }
}

@Composable
internal fun CallExpandIcon(expanded: Boolean) {
    val c = MikaTheme.colors
    Icon(
        MikaIcons.Chevron,
        contentDescription = null,
        modifier = Modifier.size(15.dp).rotate(if (expanded) -90f else 90f),
        tint = if (expanded) c.accent else c.ink3,
    )
}

/**
 * Structured conversation turn bubble for both live transcripts and history review.
 */
@Composable
internal fun CallTranscriptText(line: CallTranscriptLine, turnNumber: Int? = null) {
    val c = MikaTheme.colors
    val isAssistant = line.role == "assistant"
    val speakerLabel = stringResource(if (isAssistant) R.string.app_name else R.string.calls_caller)
    val bubbleBg = if (isAssistant) c.accentSoft.copy(alpha = if (c.isDark) 0.35f else 0.45f) else c.fill
    val speakerColor = if (isAssistant) c.accent else c.ink2
    val shape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (isAssistant) 12.dp else 4.dp,
        bottomEnd = if (isAssistant) 4.dp else 12.dp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(bubbleBg, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (isAssistant) c.accent else c.ink3, CircleShape),
                )
                Text(
                    text = speakerLabel,
                    style = TextStyleLabel,
                    color = speakerColor,
                )
            }
            if (turnNumber != null) {
                Text(
                    text = "#$turnNumber",
                    style = MikaType.Hint,
                    color = c.ink3,
                )
            }
        }
        Text(
            text = line.text,
            style = MikaType.Body,
            color = c.ink,
        )
    }
}

private val TextStyleLabel = MikaType.Hint.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

@Composable
internal fun CallCardDivider() {
    HorizontalDivider(color = MikaTheme.colors.hair, thickness = 0.5.dp)
}
