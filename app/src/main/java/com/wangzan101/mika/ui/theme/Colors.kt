package com.wangzan101.mika.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 界面用到的全部颜色。页面一律从 [MikaTheme.colors] 取,不写死色值。
 * 实色只用在强调(主按钮、选中、开关、用户消息)和状态(成功、需要你、出错);图标块一律中性。
 */
@Immutable
class MikaColors(
    /** 页面背景。 */
    val bg: Color,
    /** 卡片、分组。 */
    val surface: Color,
    /** 浅填充:次按钮、分段控件底、代码块底。 */
    val fill: Color,
    /** 文字三级:正文、次要、辅助。 */
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** 分隔线。 */
    val hair: Color,
    /** 卡片描边。 */
    val edge: Color,
    val accent: Color,
    val accentSoft: Color,
    /** 强调色上的文字和图标。 */
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    /** 「需要你」:[warn] 用于文字,[warnSolid] 用于圆点、描边。 */
    val warn: Color,
    val warnSolid: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** 图标块的底。 */
    val neutralSoft: Color,
    /** Mika 回答的底:比页面背景深一点点的灰,和用户的蓝色气泡区分开。 */
    val answer: Color,
    /** 灰色胶囊、关着的开关。 */
    val greySoft: Color,
    /** 悬浮玻璃(输入栏、浮在内容上的按钮)的底和描边。 */
    val glass: Color,
    val glassEdge: Color,
    /** 卡片阴影;深色下卡片只靠描边,为透明。 */
    val cardShadow: Color,
    /** 悬浮元素的阴影。 */
    val floatShadow: Color,
    /** 开关的圆钮。 */
    val thumb: Color,
    /**
     * 图表里分类的颜色(例如每家服务商一种),按固定的先后取;超出的归到 [seriesOther]。
     * 只给图形上色,文字一律用 ink 三级。
     */
    val series: List<Color>,
    val seriesOther: Color,
    /** 图表的网格线。 */
    val grid: Color,
    val isDark: Boolean,
)

val LightColors = MikaColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF8F8F6),
    fill = Color(0xFFF1F1EE),
    ink = Color(0xFF292925),
    ink2 = Color(0xFF575751),
    ink3 = Color(0xFF73736B),
    hair = Color(0xFFE7E7E2),
    edge = Color(0xFFE1E1DB),
    accent = Color(0xFF292925),
    accentSoft = Color(0xFFECECE8),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF267456),
    okSoft = Color(0xFFEAF4EF),
    warn = Color(0xFF946116),
    warnSolid = Color(0xFFB5761B),
    warnSoft = Color(0xFFF8F0E2),
    bad = Color(0xFFB42318),
    badSoft = Color(0xFFFCECE9),
    neutralSoft = Color(0xFFF0F0EC),
    answer = Color(0xFFF5F5F2),
    greySoft = Color(0xFFE8E8E3),
    glass = Color(0xFFFFFFFF),
    glassEdge = Color(0xFFE1E1DB),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    seriesOther = Color(0xFFB4B5BC),
    grid = Color(0x0F000000),
    isDark = false,
)

val DarkColors = MikaColors(
    bg = Color(0xFF191918),
    surface = Color(0xFF222220),
    fill = Color(0xFF2B2B28),
    ink = Color(0xFFF0F0EC),
    ink2 = Color(0xFFC7C7BF),
    ink3 = Color(0xFFA5A59B),
    hair = Color(0xFF383834),
    edge = Color(0xFF40403A),
    accent = Color(0xFFE8E8E2),
    accentSoft = Color(0xFF343430),
    onAccent = Color(0xFF222220),
    ok = Color(0xFF79C5A4),
    okSoft = Color(0xFF223D32),
    warn = Color(0xFFE9BB70),
    warnSolid = Color(0xFFD9A248),
    warnSoft = Color(0xFF453621),
    bad = Color(0xFFFF9B91),
    badSoft = Color(0xFF472D2A),
    neutralSoft = Color(0xFF30302C),
    answer = Color(0xFF262623),
    greySoft = Color(0xFF41413B),
    glass = Color(0xFF222220),
    glassEdge = Color(0xFF40403A),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFF0F0EC),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    seriesOther = Color(0xFF5A5B62),
    grid = Color(0x12FFFFFF),
    isDark = true,
)

/** 没有包在 [MikaTheme] 里时(比如界面测试)按浅色。 */
val LocalMikaColors = staticCompositionLocalOf { LightColors }
