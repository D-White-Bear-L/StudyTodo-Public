package host.workbuddy.dailytasklist.app

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.depth.PrismalDepthShadow
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.shapes.PrismalCapsule
import com.styropyr0.prismal.specular.PrismalSpecular
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/* ===========================================================================
   悬浮液态玻璃导航栏（自研 v1.33）
   ---------------------------------------------------------------------------
   三层结构，从下往上：
     ① 底板玻璃  —— 采样「页面内容层」，实时折身下的像素（这次的核心）
     ② 液滴      —— **有体积**的一小块玻璃，叠在底板上（不是洞）
     ③ 图标文案  —— 在液滴之上，选中项自己会放大

   交互内核照搬库组件 PrismalSpringMotion 的做法（不是我瞎调参数）：
     · 按下 → pressProgress 用 spring(0.45, 900) 弹到 1，图标 +20%、液滴 +12%，
       松手用 spring(0.55, 700) 带一点点过冲回到 0 —— 这就是「Q 弹」的来源；
     · 拖动 → 目标值每帧用 spring(0.78, 1500) 追手，有轻微滞后但不粘手；
       同时按单帧位移给液滴做横向拉伸（快甩才拉长，慢推是圆的）；
     · 松手 → 按速度投影 0.16 * vx（格/秒）算出落点，spring(0.62, 520) 吸附，
       轻微过冲再回稳；落点变化时回调 + 一次轻震动；
     · 纵向手势一个事件都不消费，整个让给外层页面滚动。
   =========================================================================== */

/** 一项导航（图标 + 文案） */
@Immutable
data class FloatingNavItem(
    val label: String,
    val icon: ImageVector
)

/* 四个弹簧，和库里的取向一致：按下进去快、回来稍慢，吸附带过冲 */
private val NavPressIn = spring<Float>(dampingRatio = 0.45f, stiffness = 900f)
private val NavPressOut = spring<Float>(dampingRatio = 0.55f, stiffness = 700f)
private val NavSnap = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
private val NavDrag = spring<Float>(dampingRatio = 0.78f, stiffness = 1500f)

/**
 * 悬浮液态玻璃导航栏。
 *
 * @param backdrop         采样源。传「页面内容层」才是本组件的用法；
 *                         传「背景层」就退化成传统做法（可用于 A/B 对比）。
 * @param items            导航项
 * @param selected         当前选中下标
 * @param onSelect         选中回调
 * @param blur             玻璃自身的模糊半径（只糊被采样的内容，不糊自己的图标文案）
 * @param refractionHeight 边缘折射带高度
 * @param refractionAmount 边缘折射位移量
 * @param chroma           边缘色散
 * @param scrim            底板可读性薄底（0 = 完全通透）
 * @param dropletScrim     液滴自身体积感：越大越像「一块叠上去的玻璃」，越小越透
 * @param height           栏高
 */
@Composable
fun FloatingPageNavBar(
    backdrop: PrismalBackdrop,
    items: List<FloatingNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 66.dp,
    blur: Dp = 12.dp,
    refractionHeight: Dp = 26.dp,
    refractionAmount: Dp = 20.dp,
    chroma: Float = 0.45f,
    scrim: Float = 0.10f,
    dropletScrim: Float = 0.26f,
    accent: Color = AccentBlue,
    showSpecular: Boolean = true,
    showShadow: Boolean = true,
    /* 可选的「内容变了」钩子。
       传一个读取滚动 / 内容状态的 lambda：玻璃节点的 effects 是在 observeReads 里
       求值的，读到的 snapshot state 一旦变化，这个节点就会重新取样并重画。 */
    contentTick: (() -> Unit)? = null
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val night = darkText()
    val n = items.size
    val idx = selected.coerceIn(0, n - 1)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    /* 液滴位置（单位：第几格，允许小数）——和库一样用 Animatable 而不是滚动容器 */
    val pos = remember(n) { Animatable(idx.toFloat(), 0.0005f) }
    /* 按压进度 0..1 */
    val press = remember { Animatable(0f, 0.0005f) }
    /* 拖动拉伸量 0..1：只跟单帧位移有关，松手回 0 */
    val stretch = remember { Animatable(0f, 0.0005f) }

    /* 外部改选中 → 只让液滴滑过去，绝不回调（避免 v1.27 那种自滑死循环） */
    LaunchedEffect(idx) {
        if (pos.targetValue != idx.toFloat()) pos.animateTo(idx.toFloat(), NavSnap)
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(height)
            .pointerInput(n) {
                val slop = viewConfiguration.touchSlop
                val vt = VelocityTracker()
                fun tabWidth() = size.width.toFloat() / n
                fun clampIdx(v: Float) = v.coerceIn(0f, (n - 1).toFloat())

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { press.animateTo(1f, NavPressIn) }

                    var axis = 0
                    var anchor = pos.value
                    var acc = 0f

                    vt.resetTracking()
                    vt.addPosition(SystemClock.uptimeMillis(), Offset.Zero)

                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.changedToUpIgnoreConsumed()) break

                        if (axis == 0) {
                            val dx = c.position.x - down.position.x
                            val dy = c.position.y - down.position.y
                            when {
                                /* 横向明确大于纵向 → 接管，之后消费事件 */
                                abs(dx) > slop && abs(dx) > abs(dy) -> {
                                    axis = 1
                                    anchor = pos.value
                                    acc = 0f
                                }
                                /* 纵向先过 slop → 整个让给页面，一个事件都不消费 */
                                abs(dy) > slop -> {
                                    axis = -1
                                    break
                                }
                            }
                        }

                        if (axis == 1) {
                            c.consume()
                            val d = c.positionChange().x
                            if (d != 0f) {
                                acc += d
                                vt.addPosition(SystemClock.uptimeMillis(), Offset(acc, 0f))
                                val target = clampIdx(anchor + acc / tabWidth())
                                /* 弹簧追手：有一点点滞后，不是硬贴 */
                                scope.launch { pos.animateTo(target, NavDrag) }
                                /* 单帧位移越大越拉长（走满 1/3 格即满值） */
                                scope.launch {
                                    stretch.snapTo((abs(d) / (tabWidth() / 3f)).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }

                    scope.launch { press.animateTo(0f, NavPressOut) }
                    scope.launch { stretch.animateTo(0f, NavPressOut) }

                    if (axis == 1) {
                        val vx = vt.calculateVelocity().x
                        /* 速度投影：快甩多走一格，慢放几乎不投影 */
                        val projected = (vx / tabWidth() * 0.16f).coerceIn(-1.8f, 1.8f)
                        val target = (clampIdx(pos.value) + projected).roundToInt().coerceIn(0, n - 1)
                        scope.launch {
                            pos.animateTo(target.toFloat(), NavSnap)
                            if (target != idx) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(target)
                            }
                        }
                    }
                }
            }
    ) {
        val tabPx = constraints.maxWidth.toFloat() / n
        val dropletW = with(density) { (tabPx * 0.74f).toDp() }
        val dropletH = height - 10.dp

        /* 拖动过程中跟着液滴走的高亮格（松手后等于选中格）。
           derivedStateOf：只有「落在第几格」真的变了才重组，不是每帧都重组。 */
        val activeIndexState = remember(n) {
            derivedStateOf { pos.value.roundToInt().coerceIn(0, n - 1) }
        }
        val activeIndex = activeIndexState.value

        /* ① 底板玻璃 ------------------------------------------------------- */
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    /* 按压：宽一点点、扁一点点，松手弹回来 */
                    scaleX = 1f + 0.010f * press.value
                    scaleY = 1f - 0.022f * press.value
                }
                .drawPrismalGlass(
                    backdrop = backdrop,
                    shape = { PrismalCapsule() },
                    effects = {
                        contentTick?.invoke()
                        applyPrismalGlassEffects(
                            density = density,
                            adaptiveLuminance = false,
                            luminance = 0.5f,
                            blurRadiusPx = with(density) { blur.toPx() },
                            refractionHeightPx = with(density) { refractionHeight.toPx() } *
                                (1f + 0.18f * press.value),
                            refractionAmountPx = with(density) { refractionAmount.toPx() } *
                                (1f + 0.35f * press.value),
                            saturation = 1.35f,
                            chromaticAberration = chroma,
                            useVibrancy = true
                        )
                    },
                    specular = if (showSpecular) {
                        { PrismalSpecular.Default.copy(alpha = 0.80f + 0.20f * press.value) }
                    } else null,
                    depthShadow = if (showShadow) {
                        {
                            PrismalDepthShadow(
                                radius = 20.dp,
                                offset = DpOffset(0.dp, 10.dp),
                                color = Color.Black.copy(alpha = 0.24f),
                                alpha = 0.85f
                            )
                        }
                    } else null,
                    onDrawSurface = {
                        if (scrim > 0f) {
                            drawRect(
                                if (night) Color.Black.copy(alpha = scrim)
                                else Color.White.copy(alpha = scrim)
                            )
                        }
                    }
                )
        )

        /* ② 液滴：有体积的一块，叠在底板上（关键：不再是「洞」） -------------
           形状、阴影、表面色都让它像从底板上凸起来的一层：
             · onDrawSurface 压真实底色（深色主题压黑、浅色压白），不是 0.1 的花瓶；
             · 折射只在边缘一圈（prismalLens），中间不动，所以内容不会被拆成重影；
             · 底部一道深色内阴影 + 顶部一道亮弧 = 厚度。 */
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (pos.value * tabPx + (tabPx - with(density) { dropletW.toPx() }) / 2f)
                            .roundToInt(),
                        0
                    )
                }
                .width(dropletW)
                .height(dropletH)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    /* 按下鼓起来；横向快甩时被拉长、压扁，松手弹回 */
                    val p = press.value
                    val s = stretch.value
                    scaleX = (1f + 0.12f * p) * (1f + 0.16f * s)
                    scaleY = (1f + 0.08f * p) * (1f - 0.07f * s)
                }
                .drawPrismalGlass(
                    backdrop = backdrop,
                    shape = { PrismalCapsule() },
                    effects = {
                        contentTick?.invoke()
                        /* 只有边缘一圈折射：中间是平的，图标不会重影 */
                        prismalLens(
                            refractionHeight = with(density) { 9.dp.toPx() },
                            refractionAmount = with(density) { 13.dp.toPx() } *
                                (0.55f + 0.55f * press.value),
                            chromaticAberration = chroma * 0.55f
                        )
                    },
                    specular = if (showSpecular) {
                        { PrismalSpecular.Default.copy(alpha = 0.62f + 0.30f * press.value) }
                    } else null,
                    depthShadow = if (showShadow) {
                        {
                            PrismalDepthShadow(
                                radius = 9.dp,
                                offset = DpOffset(0.dp, 2.dp),
                                color = Color.Black.copy(alpha = 0.30f),
                                alpha = 0.9f
                            )
                        }
                    } else null,
                    onDrawSurface = {
                        /* 体积本体：先压一层和主题同向的底，再叠一点点强调色。
                           「透过」的观感就是从这个 0.10 改到 0.26 + 色相来的。 */
                        drawRect(
                            if (night) Color.Black.copy(alpha = dropletScrim)
                            else Color.White.copy(alpha = dropletScrim * 0.85f)
                        )
                        drawRect(accent.copy(alpha = 0.10f + 0.10f * press.value))
                        /* 下沿一道深色内阴影：玻璃的厚度 */
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.62f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.16f)
                            )
                        )
                    }
                )
        )

        /* ③ 图标 / 文案：画在液滴之上，选中项自己放大 ------------------------- */
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, item ->
                val active = i == activeIndex
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (i != idx) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            }
                        }
                        /* 按下放大：选中项 1.22、其它 1.08；松手带过冲弹回 */
                        .graphicsLayer {
                            val p = press.value
                            val k = if (active) 1f + 0.22f * p else 1f + 0.08f * p
                            scaleX = k
                            scaleY = k
                            /* 选中项略微上浮，看起来是从栏里「长出来」的 */
                            translationY = if (active) -2.dp.toPx() * p else 0f
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (active) accent else TextSub,
                        modifier = Modifier.size(if (active) 24.dp else 23.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) accent else TextMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
