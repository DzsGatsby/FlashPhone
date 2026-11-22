package com.wangzan101.mika.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaTheme

/** Local tokens for the owner's selected white iPhone keypad; shared app navigation is unaffected. */
internal data class DialerPalette(
    val background: Color, val keyFill: Color, val keyBorder: Color,
    val callGreen: Color, val onGreen: Color, val blue: Color,
)

@Composable
internal fun dialerPalette(): DialerPalette {
    val c = MikaTheme.colors
    return DialerPalette(c.bg, c.fill, c.edge,
        if (c.isDark) Color(0xFF30C85A) else Color(0xFF178248), Color.White,
        if (c.isDark) Color(0xFF63AEFF) else c.accent)
}

@Composable
internal fun dialerKeySize(width: Dp): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val preferred = when {
        screenHeight < 700 -> 48.dp
        screenHeight < 850 -> 64.dp
        screenHeight < 940 -> 74.dp
        else -> 84.dp
    }
    return minOf(preferred, ((width - 48.dp) / 3).coerceAtLeast(48.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialerKeypad(enabled: Boolean, onKey: (String) -> Unit, onPlus: () -> Unit) {
    val palette = dialerPalette()
    val c = MikaTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val keySize = dialerKeySize(maxWidth)
        // Key legends behave like icon labels: cap their visual scale to keep each complete
        // circular key reachable. TalkBack announces the full digit independently.
        val legendScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val numberSize = (if (keySize < 56.dp) 26f else if (keySize < 68.dp) 30f else 37f) / legendScale
        val numberLine = (if (keySize < 56.dp) 28f else if (keySize < 68.dp) 32f else 39f) / legendScale
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            listOf(
                listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                listOf("*" to "", "0" to "+", "#" to ""),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { (digit, letters) ->
                        Box(
                            Modifier.size(keySize).clip(CircleShape).background(palette.keyFill)
                                .border(0.7.dp, palette.keyBorder, CircleShape)
                                .testTag("call-key-$digit")
                                .combinedClickable(
                                    enabled = enabled, role = Role.Button,
                                    onLongClickLabel = if (digit == "0") "输入加号" else null,
                                    onLongClick = if (digit == "0") onPlus else null,
                                    onClick = { onKey(digit) },
                                ).semantics { contentDescription = digit },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(digit, color = if (enabled) c.ink else c.ink3, fontSize = numberSize.sp,
                                    fontWeight = FontWeight.Normal, lineHeight = numberLine.sp,
                                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                                if (letters.isNotEmpty()) Text(letters, color = c.ink2, fontSize = ((if (keySize < 56.dp) 8f else 10f) / legendScale).sp,
                                    fontWeight = FontWeight.SemiBold, letterSpacing = ((if (keySize < 56.dp) 1.4f else 2f) / legendScale).sp,
                                    lineHeight = ((if (keySize < 56.dp) 10f else 12f) / legendScale).sp,
                                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object DialerIcons {
    val Handset = ImageVector.Builder("dialer-handset", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M6.6 2.6 C5.9 2 4.8 2 4.1 2.7 L2.6 4.2 C1.6 5.2 2.3 9.6 6.8 14.1 C11.3 18.6 15.8 21.6 18.2 21.4 C19.1 21.3 20.4 19.7 21.1 18.8 C21.7 18 21.4 17.2 20.7 16.7 L17.1 14.3 C16.4 13.8 15.6 13.9 15.1 14.5 L13.7 16 C11.2 14.9 8.8 12.5 7.7 10 L9.2 8.6 C9.8 8 9.9 7.2 9.4 6.5 Z"), fill = SolidColor(Color.Black))
    }.build()
    val Delete = ImageVector.Builder("dialer-delete", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M8 5 H21 V19 H8 L2 12 Z M11 9 L17 15 M17 9 L11 15"),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f)
    }.build()
}
