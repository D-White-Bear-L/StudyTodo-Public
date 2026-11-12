package host.workbuddy.dailytasklist.app

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.styropyr0.prismal.components.LocalPrismalParentGlassLayer
import com.styropyr0.prismal.depth.PrismalDepthShadow
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.interactive.PrismalSpringMotion
import com.styropyr0.prismal.shapes.PrismalCapsule
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/* ===========================================================================
   液滴文字条（日期 / 时间共用）
   ---------------------------------------------------------------------------
   为什么不用库里的 PrismalRulerSelector / PrismalHorizontalSelector：

   这两个组件的选中项都由内部 `rememberScrollState()` 保存，并且都有一段

       LaunchedEffect(scrollState) {
           snapshotFlow { scrollState.isScrollInProgress }
               .distinctUntilChanged()
               .collect { if (!it) snapToIndex(indexForScroll(scrollState.value)) }
       }

   `isScrollInProgress` 的初值是 false，所以这段 flow 在**第一次组合时就会 emit
   一次**，立刻带着 scrollState.value（新建的滚动状态 = 0）去 snapToIndex(0)，
   而 snapToIndex 里又有 `if (index != selectedIndex) onSelected(index)`。

   结果：只要这个组件被重新组合一次（放在 LazyColumn 里滚出屏幕、item 被回收后
   再回来，scrollState 就重建为 0），选中项就会被强行拉回第 0 项。
   日期条上表现为「回到首页看到的日期既不是上次选的、也不是今天」。
   上一版「一碰就一直滑」也是同一段逻辑在范围联动下自激。

   所以这里不用它们的滚动容器，只用库的**交互原语**（PrismalSpringMotion 提供
   按压放大 / 拖动弹簧 / 速度，drawPrismalGlass + prismalLens 提供折射和液滴）：
   选中状态由外部 date 单向驱动，内部不做任何回写。

   另一个刻意的差别：液滴画在**文字下层**。
   库把文字放在被液滴采样的图层里，于是文字会被折射 + 色散掰弯，这就是
   「加了液滴之后字看不清」。这里液滴只折射它下面的卡片，文字永远在上层，
   锐度不受影响，玻璃感来自液滴的边缘折射和高光。

   ---- 关于「阻尼太强」（v1.29） ----
   上一版把每一帧的手指位移交给库的 `updateValue()`，它内部是
   `spring(dampingRatio = 1, stiffness = 1000)`，而且每次 MOVE 都从**当前动画值**
   重新起一次动画。值永远追不上手指（稳态滞后约半个格宽），而且因为下一帧的
   位移又是拿「滞后后的值」算的，误差会一路累积——手指划过去半屏，内容只挪了两格。
   这就是「阻尼太强、很难滑动」。

   现在位置由本组件自己的 Animatable 持有：
     · 拖动中：直接 snapTo 到「按下时的值 + 累计位移」，1:1 跟手，没有滞后；
     · 松手：按甩动速度补一段惯性（FlingSeconds），再轻弹簧吸附到整格。
   PrismalSpringMotion 只负责按压形变（pressProgress / scaleX / scaleY）。

   ---- 关于「操作很怪」（v1.31） ----
   库的手势（inspectPrismalDragGestures）在**按下的瞬间**就开始接管，
   而且一个 change 都**不消费**。把它放进 verticalScroll / LazyColumn 里时，
   手指稍微斜着划一下，页面在上下滚、刻度条同时在左右滑——两件事一起发生，
   就是你感觉「怪」的根源；手指从刻度条上起手时页面还经常滚不动。

   所以手势也自己写（见下面的 dragGesture）：
     · 先判方向：横向位移超过 touch slop 且明显大于纵向，才认定「在拨刻度」；
     · 纵向先超过 slop → 立刻放弃，一个事件都不消费，页面照常滚动；
     · 认定横向之后才 consume，父级滚动容器不会再来抢，也不会边滚边改值。

   ---- 关于「完全滑不动」（v1.35，真 bug） ----
   v1.31 引入 dragGain（内容位移增益）时把单位算错了：

       raw = anchor - accPx * dragGain / itemPx          // 错
       dragGain = dragTravelPerItem / itemWidth          // 本身已是无量纲

   等于把灵敏度按格宽又缩小了一遍（日期条 ÷1.55²、时间条 ÷1.48²）。
   日期条要拖半屏才走一天、时间条要拖大半屏才走 5 分钟，
   实际体验就是「手指划了没反应」。现在统一只用 dragTravelPx：

       raw = anchor - 手指总位移px / dragTravelPx        // 对

   顺带补回「点一下就选它」——v1.28 自研之后只剩拖动，点空白没有反应。
   =========================================================================== */

private val StripHeight = 76.dp
private val ItemWidth = 112.dp

/**
 * 手指划多少 dp 才走一格。
 *
 * 这是「灵敏度」的唯一旋钮，而且必须**按格算**、不能按像素算。
 * 之前给的是「内容位移增益」（日期 1.35 / 时长 1.5 / 时刻 2.4），
 * 结果格宽越大、档位越细的控件反而越灵敏：时刻一格只有 5 分钟、格宽 96dp，
 * 增益 2.4 时手指划 40dp 就跳 5 分钟，一碰就飞出去；而时长一格 30 分钟、
 * 增益 1.5 时划一屏才走半小时，粗得没法精调。
 *
 * 改成「一格固定多少手指行程」之后，所有刻度条的手感一致：
 * 5 分钟一档和 30 分钟一档，都是划 72dp 走一格 —— 细的不再过敏，粗的也能挪动。
 */
private val DefaultDragTravelPerItem = 72.dp

/** 视口两侧各多渲染几项，够铺满一屏即可。 */
private const val WindowRadius = 6

/** 甩动惯性折算成「还能多走几秒」。0.18s 大约是 iOS 选择器的量级。 */
private const val FlingSeconds = 0.18f

/** 惯性最多跨越的格数：防止一次猛甩飞出几十格。 */
private const val MaxFlingItems = 10f

/** 松手吸附：略带回弹但不过冲，250ms 内落定。 */
private val SnapSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 520f)

/** 外部改值（点箭头 / 日历跳转 / 回到今天）：不弹，直接平稳滑过去。 */
private val FollowSpec = spring<Float>(dampingRatio = 0.95f, stiffness = 420f)

@Composable
fun DateGlassStrip(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemWidth: Dp = ItemWidth,
    height: Dp = StripHeight,
    /* 字号：日期用 15sp，时间用 17sp（学习页的时间是主要信息）。 */
    fontSize: TextUnit = 15.sp,
    /* 手指划过多少 dp 走一格。
       这是唯一的灵敏度旋钮，内部直接用它做 px → 格 的换算（不再乘除格宽），
       所以格宽 92dp 的时刻条和 112dp 的日期条，手感是一致的。 */
    dragTravelPerItem: Dp = DefaultDragTravelPerItem
) {
    if (labels.isEmpty()) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val material = glassMaterial(GlassLevel.Control)
    val tuning = LocalGlassTuning.current
    val lastIndex = labels.lastIndex
    val safeIndex = selectedIndex.coerceIn(0, lastIndex)
    val itemPx = with(density) { itemWidth.toPx() }

    /* 手指划多少 px 走一格。这是**唯一**的换算基准。
       注意别再乘除格宽：`dragGain` 那种「内容位移增益」是无量纲比值，
       再除以一次 itemPx 会把灵敏度按格宽再缩小一遍——
       日期条要拖半屏才走一天、时间条要拖大半屏才走 5 分钟，
       表现就是「完全滑不动」（v1.34 的 bug，见文件头）。 */
    val dragTravelPx = with(density) { dragTravelPerItem.toPx() }

    /* 液滴比行高矮一档：行高 76 → 53，行高 54 → 38。
       之前写死 50dp，放进半高的小刻度条里几乎顶满整行，看着就是一个「圈」。 */
    val dropletHeight = height * 0.70f

    /* 采样父卡片的玻璃成品：液滴是「叠在卡片上的一颗水珠」，
       而不是在卡片上挖开一个通向壁纸的洞。 */
    val backdrop = LocalPrismalParentGlassLayer.current ?: LocalPrismalBackdrop.current

    val darkSurface = when (tuning.textMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    val onSelectedState = rememberUpdatedState(onSelected)
    val currentSelected by rememberUpdatedState(safeIndex)

    /* 唯一的「位置真相」：单位是「第几格」。 */
    val pos = remember(lastIndex) { Animatable(safeIndex.toFloat()) }

    /* 拖动时液滴被「拉长」的程度：0 = 静止的球，1 = 全速甩动。
       松手后回弹到 0，所以液滴是「活着」的——这是用户说的
       「按压会变大、滑动会有形变」里缺的那一半。 */
    val stretch = remember { Animatable(0f) }

    val motion = remember(lastIndex, itemPx) {
        PrismalSpringMotion(
            animationScope = scope,
            /* 库自己的 value 与手势都不用（见文件头说明），
               这里只借它的按压形变动画。 */
            initialValue = 0f,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.07f,
            onDragStarted = { },
            onDragStopped = { },
            onDrag = { _, _ -> }
        )
    }

    /* 外部改日期（点箭头、日历跳转、回到今天）时只让液滴滑过去，绝不回调，
       所以不存在「回调 → 外部值变 → 再回调」的循环。 */
    LaunchedEffect(safeIndex) {
        if (pos.targetValue.roundToInt() != safeIndex) {
            pos.animateTo(safeIndex.toFloat(), FollowSpec)
        }
    }

    /* 方向锁手势，见文件头「关于操作很怪」。 */
    val dragGesture = Modifier.pointerInput(lastIndex, itemPx, dragTravelPx) {
        val slop = viewConfiguration.touchSlop
        val velocity = VelocityTracker()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            var anchor = pos.value      // 认定横向的那一刻的位置
            var lockX = 0f              // 认定横向的那一刻的手指 x（px）
            var prevX = 0f
            var lastX = 0f
            var axis = 0                // 0 = 未定，1 = 横向拨动，-1 = 交给页面滚动
            var dragged = false

            velocity.resetTracking()
            velocity.addPosition(SystemClock.uptimeMillis(), Offset.Zero)

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) break

                if (axis == 0) {
                    val totalX = change.position.x - down.position.x
                    val totalY = change.position.y - down.position.y
                    when {
                        abs(totalX) > slop && abs(totalX) >= abs(totalY) -> {
                            axis = 1
                            dragged = true
                            /* 过 slop 之前那段位移丢掉：否则手指刚动一下，
                               内容已经被拖走小半格，感觉「一碰就跳」。 */
                            anchor = pos.value
                            lockX = change.position.x
                            prevX = lockX
                            lastX = lockX
                            motion.press()
                        }
                        abs(totalY) > slop && abs(totalY) > abs(totalX) -> {
                            axis = -1
                            break
                        }
                    }
                }

                if (axis == 1) {
                    /* 消费掉：父级滚动容器看不到这个事件，就不会边滚边改值 */
                    change.consume()
                    lastX = change.position.x
                    val d = lastX - prevX
                    prevX = lastX

                    /* 手指划过 dragTravelPx 就走一格。
                       用「锁定时的手指位置」算总位移，不累加逐帧增量——
                       事件被丢一两帧也不会让手感漂移。 */
                    val raw = (anchor - (lastX - lockX) / dragTravelPx)
                        .coerceIn(0f, lastIndex.toFloat())
                    /* ⚠️ Animatable.snapTo 是「受限挂起函数」，不能在
                       awaitPointerEvent 的受限协程里直接调用（编译期就报错），
                       所以仍旧投递到外部 scope。它只是写一次状态、不等动画，
                       投递开销远小于一帧，跟手性不受影响。 */
                    scope.launch { pos.snapTo(raw) }

                    if (d != 0f) {
                        velocity.addPosition(
                            SystemClock.uptimeMillis(),
                            Offset(lastX - lockX, 0f)
                        )
                        /* 单帧位移越大 → 液滴被拉得越长 */
                        val stretchTarget =
                            (abs(d) / dragTravelPx * 1.6f).coerceIn(0f, 1f)
                        scope.launch { stretch.snapTo(stretchTarget) }
                    }
                }
            }

            if (dragged) {
                motion.release()
                /* 松手：按甩动速度补一段惯性，再吸附到整格。
                   用「锁定时的位置 + 总位移」算落点，而不是读 pos.value：
                   最后一两个 snapTo 可能还在协程队列里没结算。 */
                val vx = velocity.calculateVelocity().x              // px/s，向右为正
                val projected = (-vx / dragTravelPx * FlingSeconds)
                    .coerceIn(-MaxFlingItems, MaxFlingItems)
                val live = (anchor - (lastX - lockX) / dragTravelPx)
                    .coerceIn(0f, lastIndex.toFloat())
                val target = (live + projected).roundToInt().coerceIn(0, lastIndex)
                scope.launch { pos.animateTo(target.toFloat(), SnapSpec) }
                scope.launch { stretch.animateTo(0f, tween(260)) }
                if (target != currentSelected) onSelectedState.value(target)
            }
        }
    }

    /* 虚拟化：范围可能有 300+ 天，全都组合出来会让布局每帧测量几百个 Text。
       只渲染视口附近的几项，窗口每翻一格才移动一次（不是每帧）。 */
    var windowCenter by remember { mutableIntStateOf(safeIndex) }
    LaunchedEffect(pos) {
        snapshotFlow { pos.value.roundToInt() }
            .distinctUntilChanged()
            .collect { windowCenter = it }
    }
    val windowStart = (windowCenter - WindowRadius).coerceIn(0, lastIndex)
    val windowEnd = (windowCenter + WindowRadius).coerceIn(0, lastIndex)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .then(dragGesture)
    ) {
        val viewportPx = constraints.maxWidth.toFloat()
        val current = pos.value
        /* 让第 0 项的中心落在视口中心：整体平移量 = 中心偏移 - 当前值 * 项宽 */
        val startOffset = viewportPx / 2f - itemPx / 2f

        /* ① 液滴：画在文字下面，只折射卡片背景。
           ⚠️ 位置**必须**是常量：视口正中。
           之前写成 `startOffset - current * itemPx`，那正好是「第 0 项此刻在屏幕上的 x」——
           分钟那条的第 0 项就是 "00"，于是液滴一路跟着 "00" 跑，看着像贴在数字上、
           而不是一个固定的取景框。数字要从它底下滑过去，它自己不能动。 */
        if (backdrop != null) {
            val dropletPx = with(density) { (itemWidth - 14.dp).toPx() }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset((startOffset + (itemPx - dropletPx) / 2f).roundToInt(), 0)
                    }
                    .width(itemWidth - 14.dp)
                    .height(dropletHeight)
                    .graphicsLayer {
                        /* 按住时横向撑开、纵向略压：这就是「挤压」。
                           拖动时再按速度拉长一点（stretch 0→1），松手回弹。 */
                        val press = motion.pressProgress
                        val s = stretch.value
                        scaleX = motion.scaleX * (1f + 0.02f * press + 0.06f * s)
                        scaleY = motion.scaleY * (1f - 0.015f * press - 0.035f * s)
                    }
            ) {
                /* 玻璃本体：折射 / 明度 / 饱和度 / 薄底全部来自 glassMaterial()。
                   投一层淡淡的影子，液滴才「浮」在卡片上，而不是印上去的。 */
                Box(
                    Modifier
                        .matchParentSize()
                        .drawPrismalGlass(
                            backdrop = backdrop,
                            shape = { PrismalCapsule() },
                            effects = {
                                /* 折射带收窄到半径的 42% / 30%：液滴里要容纳一整行日期，
                                   折射带越宽，字形被掰弯的部分越多。 */
                                val r = size.minDimension / 2f
                                material.applyDropletLens(this, density, 0.5f, r * 0.42f, r * 0.30f)
                            },
                            specular = material.specularProvider,
                            depthShadow = {
                                PrismalDepthShadow(
                                    radius = 9.dp,
                                    offset = DpOffset(0.dp, 2.dp),
                                    color = Color.Black.copy(alpha = 0.14f),
                                    alpha = 0.9f
                                )
                            },
                            onDrawSurface = { material.drawSurface(this, dark = darkSurface) }
                        )
                )

                /* 水滴自己的光影，见下面的 drawDropletShading */
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(percent = 50))
                        .drawBehind { drawDropletShading(motion.pressProgress, darkSurface) }
                )
            }
        }

        /* ② 文字层：永远在最上层，任何情况下都不被折射、不被模糊 */
        for (index in windowStart..windowEnd) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (startOffset + index * itemPx - current * itemPx).roundToInt(),
                            0
                        )
                    }
                    .width(itemWidth)
                    .height(height)
                    /* 点一下就选它（v1.34 之前只能拖，用户说的「选不了」）。
                       点选和拖动共用同一块区域：横向过 slop 后本节点的 clickable
                       会因为事件被消费而自动取消，不会互相打架。 */
                    .clickable(
                        interactionSource = remember(index) { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (index != currentSelected) onSelectedState.value(index)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = labels[index],
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer {
                        /* 距视口中心越远越淡，形成 iOS 那种「两边退到后面」的层次。
                           中间那项保持满亮度——它就在液滴正上方，必须清楚。 */
                        val screenCenter =
                            startOffset + index * itemPx + itemPx / 2f - current * itemPx
                        val distance = abs(screenCenter - viewportPx / 2f)
                        val focus = (1f - distance / (viewportPx / 2f)).coerceIn(0f, 1f)
                        alpha = lerp(0.28f, 1f, focus)
                    }
                )
            }
        }
    }
}

/* ===========================================================================
   水滴的「形状」
   ---------------------------------------------------------------------------
   库的液滴是把身后那层玻璃采样进来做折射 + 放大。这在 PrismalSegmentedControl
   里成立，是因为它的标签画在**被采样层里面**——折射把字折弯，眼睛立刻能看到
   一颗液体。而我们的刻度条把文字画在液滴上层（为了字永远清晰），液滴背后就只剩
   父卡片那张**已经模糊过**的平滑渐变，折它和没折它没有区别。

   所以形状必须靠光自己画出来。上一版画的是「一圈描边 + 顶部一块径向高光」，
   描边在整圈上亮度一样——那在视觉上就是一个**画上去的圆圈**（用户说的
   「水滴定死在那」，指的就是它像贴纸而不像水）。
   水的读法其实只有四件事：
     · 上半部分比下半部分亮（凸面，光从上面来）
     · 顶上一条只在中段出现、往两侧自然消失的镜面弧 ← 关键：不能闭合成圈
     · 上偏左一颗小而锐的高光点（球形反光）
     · 底沿一条短短的反光（液体贴着玻璃的接触线）
   =========================================================================== */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDropletShading(
    press: Float,
    dark: Boolean
) {
    val w = size.width
    val h = size.height
    val r = h / 2f
    val hair = 0.8.dp.toPx()

    /* ① 体积：上亮下暗。水是凸的，光从上方来。 */
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = 0.13f + 0.06f * press),
            0.34f to Color.White.copy(alpha = 0.05f),
            0.56f to Color.Transparent,
            1.00f to Color.Black.copy(alpha = if (dark) 0.20f else 0.13f)
        )
    )

    /* ② 顶部镜面弧：只有上半圈亮，到 55% 高度就完全透明。
       整圈等亮的描边是「贴纸感」的来源，这里必须断开。 */
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.00f to Color.White.copy(alpha = 0.60f + 0.25f * press),
            0.28f to Color.White.copy(alpha = 0.18f),
            0.52f to Color.Transparent,
            startY = 0f,
            endY = h
        ),
        topLeft = Offset(hair, hair),
        size = Size(w - hair * 2f, h - hair * 2f),
        cornerRadius = CornerRadius(r),
        style = Stroke(1.5.dp.toPx())
    )

    /* ③ 球形反光：上偏左一颗柔光圈 + 一颗更亮的小核。
       位置锚在左侧圆头（min(半宽, 半径×1.15)），扁胶囊上也不会跑到正中间去。 */
    val cx = minOf(w * 0.5f, r * 1.15f)
    val cy = h * 0.34f
    drawOval(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.26f + 0.10f * press),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = r * 0.78f
        ),
        topLeft = Offset(cx - r * 0.78f, cy - r * 0.78f),
        size = Size(r * 1.56f, r * 1.56f)
    )
    drawOval(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.50f + 0.20f * press),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = r * 0.20f
        ),
        topLeft = Offset(cx - r * 0.20f, cy - r * 0.20f),
        size = Size(r * 0.40f, r * 0.40f)
    )

    /* ④ 底沿接触反光：一条很短的亮弧，只在最底部出现 */
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.55f to Color.Transparent,
            1.00f to Color.White.copy(alpha = 0.22f + 0.10f * press),
            startY = 0f,
            endY = h
        ),
        topLeft = Offset(1.6.dp.toPx(), 1.6.dp.toPx()),
        size = Size(w - 3.2.dp.toPx(), h - 3.2.dp.toPx()),
        cornerRadius = CornerRadius(r - 1.6.dp.toPx()),
        style = Stroke(1.3.dp.toPx())
    )
}
