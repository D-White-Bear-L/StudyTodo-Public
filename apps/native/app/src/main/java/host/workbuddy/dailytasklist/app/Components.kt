package host.workbuddy.dailytasklist.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.drawPlainPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.components.PrismalGlassButton
import com.styropyr0.prismal.components.PrismalGlassBottomTab
import com.styropyr0.prismal.components.PrismalGlassBottomTabs
import com.styropyr0.prismal.components.LocalPrismalBottomTabHighlightedIndex
import com.styropyr0.prismal.shapes.PrismalCapsule
import com.styropyr0.prismal.specular.PrismalSpecular

/* ===========================================================================
   液态玻璃材质系统
   ---------------------------------------------------------------------------
   核心原则：玻璃是"重点交互材质"，不是所有容器的默认背景。
   所以这里只分三层，每层的材质参数都不一样：

     Floating  浮在内容之上、需要脱离页面的表面   —— 底部操作坞
     Control   可点击的控件                      —— 筛选分段、日期条、胶囊/圆形按钮
     Panel     承载内容的薄面板                  —— 任务行、学习&打卡组、表单卡

   只有前两层有描边高光和投影；Panel 几乎没有边界，靠一点底色差异区分，
   靠排版和留白分层。这样页面不会被切成一块块"磨砂塑料"。
   =========================================================================== */

@Composable
private fun dark() = darkText()

/** 把 hex + alpha 写得短一点，避免整份文件都是 Color(0x...).copy(alpha = ...) */
private fun ink(hex: Long, alpha: Float): Color = Color(hex).copy(alpha = alpha)

/* ---------------- 配色（自动适配深浅模式）---------------- */

/** 背景渐变起始色 */
val BgStart @Composable get() = if (dark()) Color(0xFF05080E) else Color(0xFFDEE6F4)
/** 背景渐变结束色 */
val BgEnd @Composable get() = if (dark()) Color(0xFF0A111D) else Color(0xFFC2CEDF)

/** 主要文字色 */
val TextPrimary @Composable get() = if (darkText()) Color(0xFFF4F7FB) else Color(0xFF10141A)
/** 次要文字色 */
val TextSub @Composable get() = if (darkText()) Color(0xFFDCE4EF) else Color(0xFF1E262F)

/**
 * 弱文字色（说明、单位、占位符）。
 *
 * 这次的做法是「不再把它当灰字」：
 *   浅色 #3A4451 → 与浅背景（≈#F0F4FA）对比 ≈ 9.4:1
 *   深色 #C2CCDA → 与深背景（≈#0A1018）对比 ≈ 11.6:1
 * 都远高于 WCAG AAA 的 7:1。它和 [TextSub] 的差距只有一点点，
 * 层级关系改由字号 / 字重 / 是否加粗来表达，而不是靠「压暗颜色」——
 * 前两版都是把灰调深一点点，在玻璃和壁纸上根本看不出来。
 *
 * 关键：**不要再对文字色叠 alpha**。散落的 `TextMuted.copy(alpha = 0.5f)`
 * 等于把对比度又乘回去，正是「怎么调都看不清」的直接原因。
 * 需要更弱时请改字号 / 字重，不要压透明度。
 */
val TextMuted @Composable get() = if (darkText()) Color(0xFFC2CCDA) else Color(0xFF3A4451)

/**
 * 文字用深色还是浅色。默认跟随系统深浅色；用户可在设置页「文字颜色」
 * 手动强制深色/浅色——解决跟随系统判断和实际背景不匹配导致文字看不清的问题。
 */
@Composable
internal fun darkText(): Boolean = when (LocalGlassTuning.current.textMode) {
    1 -> false   // 强制深色文字（浅色模式外观）
    2 -> true    // 强制浅色文字（深色模式外观）
    else -> isSystemInDarkTheme()
}

/** 分隔线：深色模式用亮线，浅色模式用暗线——到处画白线正是"灰白描边"的来源 */
val Hairline @Composable get() =
    if (dark()) Color.White.copy(alpha = 0.07f) else Color(0xFF1B2A44).copy(alpha = 0.09f)

/** 弹窗内部的浅底小卡片：深色模式下不该出现纯白块 */
@Composable
fun dialogTile(strong: Boolean = false): Color =
    if (darkText()) Color.White.copy(alpha = if (strong) 0.13f else 0.075f)
    else Color.White.copy(alpha = if (strong) 0.95f else 0.80f)

val AccentBlue   = Color(0xFF2B7FFF)
val AccentGreen  = Color(0xFF34C759)
val AccentOrange = Color(0xFFFF9500)
val AccentRed    = Color(0xFFFF3B30)

/** 兼容旧引用 */
val GlassBorder = Color.White.copy(alpha = 0.32f)

/* ===========================================================================
   文字色 × 底色：按对比度自动选字色
   ---------------------------------------------------------------------------
   问题：着色玻璃按钮的字色是调用方手写的（「多选」= 橙色底 + 深橙字，
   「导出图片」= 浅绿底 + 绿字，「编辑」= 淡蓝底 + 蓝字）。着色强度一旦拉满，
   底色变成实心橙/绿/蓝，字和底就同色了，字直接消失。

   解法不是「一律白字」——那在浅色玻璃上又会糊。这里按 WCAG 对比度算：
     1. 先估出「用户实际会看到的那层底色」（玻璃底色按着色透明度混入 tint）；
     2. 拿品牌色当起点，对比度不够就朝黑/白推，推到够读为止；
     3. 推到头还不行就直接用纯黑/纯白（自动退化，不会出现同色）。
   好处是「编辑」这种淡蓝底会自动从亮蓝变成深蓝（保住色相、读出层次），
   而实心橙底会被推成近黑（保不住色相时优先保证可读）。
   =========================================================================== */

/** WCAG 相对对比度（1:1 ~ 21:1）。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private val InkDark = Color(0xFF0B0F14)

/**
 * 在 [background] 上返回一个可读的文字色：尽量保住 [accent] 的色相，
 * 对比度不足时朝黑/白推；推不动就退化成纯黑/纯白。
 */
internal fun readableInk(
    background: Color,
    accent: Color,
    minContrast: Float = 4.5f
): Color {
    if (contrastRatio(accent, background) >= minContrast) return accent
    /* 往「离底色更远」的那一头推：黑字的对比更高就往黑推，反之往白推 */
    val towardDark = contrastRatio(InkDark, background) >= contrastRatio(Color.White, background)
    var c = accent
    var guard = 0
    while (contrastRatio(c, background) < minContrast && guard < 14) {
        c = lerp(c, if (towardDark) InkDark else Color.White, 0.16f)
        guard++
    }
    if (contrastRatio(c, background) >= minContrast) return c
    return if (towardDark) InkDark else Color.White
}

/**
 * 估算「一层着色玻璃实际呈现的底色」。
 *
 * 无法真的采样壁纸（那需要 GPU 回读），所以用玻璃的典型底色做基准：
 * 浅色接近雾白，深色接近墨蓝。
 *
 * 关键：库的 `drawPrismalGlassTint` 是这么画的
 *
 *     drawRect(tint, blendMode = BlendMode.Hue)      // ① 换色相，强度 = tint 自带 alpha
 *     drawRect(tint.copy(alpha = tintAlpha))         // ② 铺一层色
 *
 * 第 ② 句把 tint 自带的 alpha **整个替换**成 tintAlpha。也就是说调用方写的
 * `Color(0x1F34C759)`（12% 淡绿）并不淡，真正铺上去的浓度是设置页的着色强度
 * （默认 0.82）——底色其实是接近实心的绿。
 *
 * 之前这里按 `tintAlpha * tint.alpha`（0.74 × 0.12 ≈ 0.09）估算，以为底色是
 * 淡绿，于是留给字的还是那支亮绿 —— 字和底同色消失。这就是「导出图片 / 编辑 /
 * 沿用上一日的记录」三个按钮改不掉的真正原因：它们的 tint 都自带很低的 alpha，
 * 全部没进「实心色块」那条分支。
 *
 * 现在按真实浓度算（tintAlpha 为主，tint 自带 alpha 只影响 ① 那圈的色相偏移，
 * 折成一点点权重），字色才对得上屏幕上真正看到的底色。
 */
@Composable
internal fun tintedSurface(tint: Color?, tintAlpha: Float): Color {
    val base = if (darkText()) Color(0xFF16202C) else Color(0xFFE9EFF8)
    if (tint == null) return base
    /* ① 的 Hue 覆盖只在 tint 自身不透明时有分量，可以忽略；
       决定底色深浅的是 ② 的 tintAlpha。 */
    val a = tintAlpha.coerceIn(0f, 1f)
    return lerp(base, tint.copy(alpha = 1f), a)
}

val CardShape   = RoundedCornerShape(24.dp)
val FieldShape  = RoundedCornerShape(16.dp)
val PanelShape  = RoundedCornerShape(26.dp)
val RowShape    = RoundedCornerShape(18.dp)
val DockShape   = RoundedCornerShape(30.dp)

/* ---------------- 三层材质的参数 ---------------- */

enum class GlassLevel { Floating, Control, Panel, Row }

/** 玻璃本体：上沿偏白、下沿转向冷色，像透过玻璃看到天色 */
@Composable
private fun glassFill(level: GlassLevel): Brush {
    val d = dark()
    return when (level) {
        GlassLevel.Floating -> if (d) Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.170f), ink(0xFF9FB6DA, 0.060f), Color.White.copy(alpha = 0.025f))
        ) else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.84f), ink(0xFFF2F6FD, 0.64f), ink(0xFFE6EEFA, 0.52f))
        )

        GlassLevel.Control -> if (d) Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.125f), Color.White.copy(alpha = 0.048f))
        ) else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.58f), ink(0xFFE9F0FB, 0.34f))
        )

        GlassLevel.Panel -> if (d) Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.090f), Color.White.copy(alpha = 0.032f))
        ) else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.56f), ink(0xFFEDF3FC, 0.36f))
        )

        /* 列表行：整组任务共用一块连续的薄表面，所以填充是平的，不做渐变，
           否则每一行都会重新亮一次，看起来就是"一叠卡片" */
        GlassLevel.Row -> if (d) Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.078f), Color.White.copy(alpha = 0.078f))
        ) else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.46f), Color.White.copy(alpha = 0.46f))
        )
    }
}

/** 边缘高光：只在上沿亮、下沿收，模拟真实玻璃对光源的反射 */
@Composable
private fun glassRing(level: GlassLevel, scale: Float = 1f): Brush {
    val d = dark()
    val top: Float
    val bottom: Float
    when (level) {
        GlassLevel.Floating -> if (d) { top = 0.22f; bottom = 0.05f } else { top = 0.95f; bottom = 0.30f }
        GlassLevel.Control  -> if (d) { top = 0.15f; bottom = 0.035f } else { top = 0.85f; bottom = 0.20f }
        GlassLevel.Panel    -> if (d) { top = 0.10f; bottom = 0.022f } else { top = 0.62f; bottom = 0.14f }
        GlassLevel.Row      -> if (d) { top = 0.07f; bottom = 0.015f } else { top = 0.46f; bottom = 0.10f }
    }
    return Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = (top * scale).coerceIn(0f, 1f)),
            Color.White.copy(alpha = (bottom * scale).coerceIn(0f, 1f))
        )
    )
}

@Composable
private fun ringWidth(level: GlassLevel): Dp = when (level) {
    GlassLevel.Floating -> 1.dp
    GlassLevel.Control -> 0.8.dp
    GlassLevel.Panel -> 0.7.dp
    GlassLevel.Row -> 0.6.dp
}

/** 内侧顶部柔光强度 */
@Composable
private fun sheenAlpha(level: GlassLevel): Float {
    val d = dark()
    return when (level) {
        GlassLevel.Floating -> if (d) 0.12f else 0.55f
        GlassLevel.Control -> if (d) 0.09f else 0.40f
        GlassLevel.Panel -> if (d) 0.045f else 0.18f
        GlassLevel.Row -> 0f
    }
}

/** 只有浮层和控件层才有投影，内容面板不投影——否则整页又会变成一堆卡片 */
@Composable
private fun Modifier.glassShadow(level: GlassLevel, shape: Shape): Modifier {
    if (level == GlassLevel.Panel || level == GlassLevel.Row) return this
    val d = dark()
    val elevation = if (level == GlassLevel.Floating) 14.dp else 7.dp
    /* 浅色用冷色投影而不是灰黑，画面不会被投影弄脏 */
    val color = if (d) Color.Black.copy(alpha = 0.50f) else Color(0xFF2A4A78).copy(alpha = 0.14f)
    return this.shadow(elevation, shape, ambientColor = color, spotColor = color)
}

/**
 * 兼容旧调用的卡片。新代码请直接用 [LiquidGlass] 并明确层级。
 *
 * 这里默认 `nested = true`：卡片把自己的玻璃成品导出给内部控件采样，
 * 于是卡片里的分段控件 / 开关 / 滑块看起来是「压在这张卡片上的玻璃」，
 * 而不是卡片上开的一个通向壁纸的洞。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    strong: Boolean = false,
    interactive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    LiquidGlass(
        modifier = modifier,
        level = if (strong) GlassLevel.Floating else GlassLevel.Panel,
        /* 不再写死 22/26dp，改为跟随全局 cornerRadiusDp（设置页「圆角半径」可调）。
           否则用户调圆角半径对这些卡片完全无效。 */
        radius = Dp.Unspecified,
        interactive = interactive,
        nested = true
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/* ---------------- 内嵌凹槽（inset well） ---------------- */

/**
 * 内嵌凹槽：玻璃面板上「往下挖出来的一块」。
 *
 * 为什么需要它：一整块大玻璃（首页顶部那块）如果只是把内容铺在上面，
 * 无论怎么调模糊度都是一块平板贴在页面上，看着死板。真实材质是有层次的——
 * 面板是凸的，面板里的控件区是凹的。这里用「上沿压暗 + 下沿提亮」的渐变
 * 把凹槽画出来：光从上方来，凹槽的上沿落进阴影、下沿接住反光，
 * 于是即使整块玻璃被调得很糊，卡片内部依然有清晰的结构线。
 *
 * 注意它是纯背景层（不加玻璃、不加折射），所以不会再多一层采样开销。
 */
@Composable
fun InsetWell(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val night = darkText()
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(
                if (night) Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.26f), Color.White.copy(alpha = 0.045f))
                ) else Brush.verticalGradient(
                    listOf(Color(0xFF40506B).copy(alpha = 0.13f), Color.White.copy(alpha = 0.50f))
                )
            )
            .border(
                0.8.dp,
                if (night) Color.White.copy(alpha = 0.055f) else Color.White.copy(alpha = 0.45f),
                shape
            )
            .padding(contentPadding),
        content = content
    )
}

/* ---------------- 库组件的深浅色统一 ---------------- */

/**
 * 把 Prismal 组件库的深浅色判断挂到 App 自己的「文字颜色」上。
 *
 * 库内部（底部 Tab、滑块、分段控件、开关、进度条…）到处直接调
 * `isSystemInDarkTheme()`，而这个 App 的外观由设置页的「文字颜色」决定。
 * 两者一旦不一致，就会出现：整页卡片是浅色的、底部 Tab 却被压成一块深灰、
 * 分段控件的玻璃液滴按深色模式画成一声暗块——不是组件坏了，是它在按系统主题画。
 *
 * `isSystemInDarkTheme()` 读的正是 LocalConfiguration 的 uiMode，
 * 所以这里复制一份 Configuration 把 night 位改掉再往下传，库就会跟着 App 走。
 */
@Composable
fun LibraryThemed(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val night = darkText()
    val forced = remember(config, night) {
        android.content.res.Configuration(config).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES
                else android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
    }
    CompositionLocalProvider(LocalConfiguration provides forced, content = content)
}

/* ---------------- 完成度进度条（真玻璃轨道） ---------------- */

/**
 * 完成度进度条。
 *
 * 有一版把它做成了「玻璃轨道 + 白色高光带」，结果是一条几 dp 高的模糊条上再压一道
 * 白线，既不像玻璃也不像进度条，用户直接说是「诡异的假液态玻璃」。
 * 进度条本来就是信息图形，不是材质：它需要的是干净的轨道、清晰的填充、
 * 明确的端点，而不是折射。所以这里退回最本分的画法，只把颜色和圆头打磨好。
 */
@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    color: Color = AccentBlue
) {
    val night = darkText()
    val frac = progress.coerceIn(0f, 1f)

    /* 轨道：浅色下一层冷灰，深色下一层压暗的黑。都压得很轻，不抢内容。
       外面加一道极细的亮边，让它像「刻在玻璃上的一条槽」，而不是贴上去的色带。 */
    val trackColor = if (night) Color.Black.copy(alpha = 0.26f)
    else Color(0xFF1B2A44).copy(alpha = 0.13f)
    val rimColor = if (night) Color.White.copy(alpha = 0.09f)
    else Color.White.copy(alpha = 0.55f)

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(frac)
                .clip(CircleShape)
                .background(
                    /* 从左到右略微提亮：给一条平涂的色带一点方向感，不至于像色块 */
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.80f), color)
                    )
                )
        )

        /* 槽口高光：只描一圈，不参与填充，避免又被看成「假玻璃」 */
        Box(
            Modifier
                .matchParentSize()
                .border(0.6.dp, rimColor, CircleShape)
        )
    }
}

/* ---------------- 可按压的修饰符 ---------------- */

@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    /* 传进来的 interactionSource 会同时喂给玻璃表面读「按下」状态，
       这样按压放大和点击事件走的是同一个源，不会各算各的。 */
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/* ---------------- 主按钮（着色玻璃，直接用官方 PrismalGlassButton） ---------------- */

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AccentBlue,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    /* 嵌在卡片里时采样父卡片的玻璃成品，避免「按钮在卡片上挖了个洞」 */
    val backdrop = glassBackdropForChild()
    val tuning = LocalGlassTuning.current

    /* 弹窗等独立窗口里没有 backdrop（不在 GlassBackground 作用域内），
       此时降级为普通实心按钮，保证「确认/取消」等弹窗按钮一定显示 */
    if (backdrop == null) {
        Box(
            modifier = modifier
                .height(50.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (enabled) 1f else 0.5f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
        }
        return
    }

    /* 着色强度直接由设置页「着色强度」滑块控制，不再偷偷设下限——
       之前为了保白字可读写了 coerceAtLeast(0.78)，结果滑块怎么拖都没反应。
       字色：实心色块上仍然用白字（这是主按钮的既有语言），只有底色本身亮到
       白字会糊掉（浅绿、黄这类中间亮度色）时才换深墨。
       阈值 1.9:1 是「白字还能看出字」的下限，不是可读性标准——
       小胶囊（Pill）用 3:1，因为它字号更小，需要更硬的对比。 */
    val material = glassMaterial(GlassLevel.Floating)
    val density = LocalDensity.current
    val tintStrength = material.tintAlpha
    val labelColor = if (tintStrength >= 0.45f) {
        if (contrastRatio(Color.White, tintedSurface(color, tintStrength)) >= 1.9f) {
            Color.White
        } else {
            Color(0xFF0B0F14)
        }
    } else {
        TextPrimary
    }

    PrismalGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        /* clip(CircleShape)：库的玻璃为了做模糊会把采样层放大一圈画到形状外面，
           那圈雾边没有任何裁剪，几个按钮挨着放就会糊成一片。裁一下干净。 */
        modifier = modifier.alpha(if (enabled) 1f else 0.5f).clip(CircleShape),
        isInteractive = enabled,
        height = 50.dp,
        tint = color,
        tintAlpha = tintStrength,
        /* 模糊 / 折射 / 色散 / 明度 / 饱和度都走全局材质，和卡片、分段控件同一套参数。
           之前按钮用的是库默认值（固定 8dp 模糊、固定饱和），用户调设置时按钮不跟着变。 */
        effects = { luminance -> material.applyEffects(this, density, luminance) },
        specular = material.specularProvider,
        /* 关掉默认的投影 + 内嵌阴影：它们会在按钮四周形成一圈「被盒子包住」的暗边，
           让按钮看起来不通透、浮不起来。玻璃按钮要的是直接折射身后背景。 */
        depthShadow = null,
        depthInset = null,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Text(
            text = text,
            /* 玻璃染色够实 → 白字；调到很通透（玻璃发白）→ 自动换深色字，保证读得清 */
            color = labelColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp
        )
    }
}

/* ---------------- 次级按钮（一层很薄的玻璃） ---------------- */

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextSub,
    onClick: () -> Unit
) {
    /* 嵌在卡片里时采样父卡片的玻璃成品，避免「按钮在卡片上挖了个洞」 */
    val backdrop = glassBackdropForChild()
    val material = glassMaterial(GlassLevel.Control)
    val density = LocalDensity.current

    /* 弹窗里没有 backdrop，降级为普通描边按钮 */
    if (backdrop == null) {
        val night = darkText()
        Box(
            modifier = modifier
                .height(50.dp)
                .clip(CircleShape)
                .background(if (night) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.7f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        return
    }

    PrismalGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.clip(CircleShape),
        height = 50.dp,
        tint = Color.Transparent,
        tintAlpha = 0f,
        effects = { luminance -> material.applyEffects(this, density, luminance) },
        specular = material.specularProvider,
        depthShadow = null,
        depthInset = null,
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        /* 同样过一遍对比度：「删除」这类红色次级按钮在浅色玻璃上原本只有 3:1，
           深一点才读得清，色相不变。 */
        Text(
            text,
            color = readableInk(tintedSurface(null, 0f), color),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/* ---------------- 小胶囊 ---------------- */

/**
 * 控件层玻璃胶囊。着色版（多选按钮）不是刷一层不透明色，
 * 而是把颜色混进玻璃本体里，所以边缘依然会折射背景。
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color? = null,
    fg: Color = TextSub,
    onClick: () -> Unit
) {
    val backdrop = glassBackdropForChild() ?: return
    val night = darkText()
    val material = glassMaterial(GlassLevel.Control)

    /* 着色强度：浅色下略收、深色下略放，让同一档在两种主题里观感一致 */
    val tintAlpha = if (bg == null) 0f
    else material.tintAlpha * if (night) 1.1f else 0.9f

    /* 字色不再由调用方一锤定音：
       先估出这层玻璃真正呈现的底色（库会把 tint 自带 alpha 换成设置页的着色强度，
       所以「淡绿胶囊」在着色 82% 时其实是接近实心的绿），再看白字够不够读。
       够 → 白字；不够 → 沿品牌色往深墨推（保住色相，推出深绿 / 深棕 / 深蓝），
       绝不会留下「橙底橙字」那种同色同亮的东西。 */
    val label = if (bg == null) {
        readableInk(tintedSurface(null, 0f), fg)
    } else {
        val surface = tintedSurface(bg, tintAlpha)
        if (contrastRatio(Color.White, surface) >= 3.2f) Color.White
        else readableInk(surface, fg)
    }

    PrismalGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.clip(CircleShape),
        height = 32.dp,
        tint = bg ?: Color.Unspecified,
        /* 同样交给全局滑块控制，不再强制 ≥0.3（那会让「着色强度」滑块下半段失灵） */
        tintAlpha = tintAlpha,
        /* 模糊 / 色散 / 明度 / 饱和度统一走全局材质；32dp 高的小胶囊把折射带压扁，
           否则上下两条带在中间碰上，会留下一条横向接缝（和圆按钮中间那条线同一成因）。 */
        blurRadius = material.blurDp,
        brightness = material.brightness,
        saturation = material.saturation,
        useVibrancy = material.useVibrancy,
        chromaticAberration = material.chroma,
        refractionHeight = 6.dp,
        refractionAmount = (material.refractAmountDp * 0.45f).coerceAtMost(10.dp),
        specular = material.specularProvider,
        depthShadow = null,
        depthInset = null,
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(text, color = label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/* ---------------- 圆形图标按钮 ---------------- */

@Composable
fun CircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    content: @Composable BoxScope.() -> Unit
) {
    /* 为什么不再直接用 PrismalGlassButton：
       它是一个「胶囊形玻璃按钮」，内部折射带宽度是按整块玻璃的 0.6 倍算的。
       34dp 的圆按钮上，上下两条折射带几乎在正中间碰上，中间就会留下一条淡淡的
       横向接缝（用户看到的「圆圈按钮中间有一条线」）。设置页的返回键、首页的
       更多 / 日期箭头都是这个尺寸，所以到处都有。

       这里改成自己组合：
         · 圆形 LiquidSurface，折射强度归零 —— 圆钮太小，折射在这个尺度上看不出
           体积，只会在中间留接缝；只保留背景模糊 + 边缘高光 + 按压反馈。
         · 外面再 clip(CircleShape)，保证任何绘制都不会溢出圆外。
         · interactive + interactionSource：按下时玻璃整体放大 3.5%，
           和点击共用同一个交互源。之前这里只写了 pressable（就是 clickable），
           注释里说的「自己按压缩放」实际并不存在，所以圆钮按下去没有任何反应。 */
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressable(interactionSource = source) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        LiquidSurface(
            modifier = Modifier.fillMaxSize(),
            level = GlassLevel.Control,
            radius = size / 2,
            shape = CircleShape,
            refract = 0f,
            rimScale = 0.85f,
            veilScale = 1.05f,
            interactive = true,
            interactionSource = source
        ) {}
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/* ---------------- 学科标签 ---------------- */

@Composable
fun SubjectTag(subject: Subject) {
    val color = Color(subject.color)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(subject.name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/* ---------------- 空状态 ---------------- */
/* 纯内容，不再套一层玻璃卡片 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp).padding(bottom = 8.dp)
        )
        Text(
            text,
            color = TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )
    }
}

/* ---------------- 顶栏 ---------------- */

/**
 * 顶栏：纯文字 + 操作按钮，不再随滚动浮出任何玻璃卡片。
 * 之前这里有一块「滚动后才浮出的玻璃层」，用户明确要求删掉——
 * 它和顶部按钮之间会形成一层多余的面，既不必要又挡住了通透度。
 */
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollProgress: Float = 0f,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(58.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            CircleIconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSub,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = if (onBack == null) 20.sp else 17.sp,
                fontWeight = if (onBack == null) FontWeight.ExtraBold else FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        actions()
    }
}

/* ---------------- 底部悬浮操作坞 ---------------- */

/**
 * 不再是贴底的一整条栏，而是一块悬浮在页面之上的玻璃：
 * 四周留白、圆角更大、带冷色投影，和内容之间是"浮起来"的关系。
 * 它仍然在布局流里占位，所以不会遮住列表最后一条。
 */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
    ) {
        /* 底部操作坞：不再是「贴底一条实心保护栏」，而是一块悬浮、通透、真折射的玻璃。
           tint 归零、不叠加投影，只靠模糊 + 边缘折射 + 极淡的容器透底，
           底部按钮背后的背景（墙纸/色场）能清楚透出来。 */
        LiquidGlass(
            modifier = Modifier.fillMaxWidth(),
            level = GlassLevel.Floating,
            radius = Dp.Unspecified
        ) {
            Row(
                modifier = Modifier.padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

/* ---------------- 底部 Tab 导航（官方 PrismalGlassBottomTabs） ---------------- */

/** 4 个主 Tab 的图标（选中/未选中）与文案 */
private data class TabSpec(
    val label: String,
    val selected: ImageVector,
    val unselected: ImageVector
)

private val MAIN_TABS = listOf(
    TabSpec("清单", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle),
    TabSpec("日历", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    TabSpec("学习", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    TabSpec("设置", Icons.Filled.Tune, Icons.Outlined.Tune)
)

/**
 * 底部标签导航：官方 PrismalGlassBottomTabs（液滴透镜 + 左右滑动 + 点击回弹）。
 *
 * 关键点：把外部传进来的 selectedIndex 包进一个 MutableState 再喂给
 * PrismalGlassBottomTabs。因为库内部用 snapshotFlow { selectedTabIndex() }
 * 监听选中变化——只有 lambda 里读取的是 snapshot state（而非普通 Int 参数），
 * 点击 Tab 后页面切换、selectedIndex 变化时，液滴才会跟着动画滑过去。
 * 否则会出现「页面切了、玻璃高亮没跟过去」的脱节。
 */
@Composable
fun AppBottomTabs(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = glassBackdropForChild() ?: return
    val night = darkText()

    /* 把选中索引包装成可被 snapshotFlow 跟踪的 state，并随外部值同步 */
    val selectedState = remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) { selectedState.intValue = selectedIndex }

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        PrismalGlassBottomTabs(
            selectedTabIndex = { selectedState.intValue },
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            tabsCount = MAIN_TABS.size,
            specular = { null },
            /* 选中液滴的着色用我们自己的主题蓝，跟主按钮/进度条统一，
               别让它变成库默认的那支 iOS 蓝 */
            dropletContentTint = AccentBlue
        ) {
            MAIN_TABS.forEachIndexed { index, spec ->
                val highlighted = LocalPrismalBottomTabHighlightedIndex.current() == index
                PrismalGlassBottomTab(
                    onClick = { onTabSelected(index) }
                ) {
                    Icon(
                        imageVector = if (highlighted) spec.selected else spec.unselected,
                        contentDescription = spec.label,
                        modifier = Modifier.size(23.dp),
                        tint = if (night) Color.White else Color.Black
                    )
                    Text(
                        spec.label,
                        fontSize = 11.sp,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                        color = if (night) Color.White else Color.Black
                    )
                }
            }
        }
    }
}
