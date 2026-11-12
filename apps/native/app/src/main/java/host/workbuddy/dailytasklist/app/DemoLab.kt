package host.workbuddy.dailytasklist.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

/* ===========================================================================
   悬浮导航栏 Demo（v1.32）
   ---------------------------------------------------------------------------
   这个页面的唯一目的：把「新组件」和「旧做法」放在同一块屏幕上对比。

   结构（只有三层，顺序很重要）：

      ① 页面层  Box(prismalGlassLayer(pageLayer))
                  └─ 背景场景（再录一层 bgLayer，供「仅壁纸」对照组用）
                  └─ 可滚动内容
      ② 控制面板（悬浮，采样 pageLayer）
      ③ 悬浮导航栏（悬浮，采样 pageLayer 或 bgLayer）

   ①②③ 是同级兄弟：所以导航栏不会被内容顶开（真悬浮），
   同时它采样的是「页面层」——也就是当前真正压在它身下的那些像素。

   把「采样源」切到「仅壁纸」，就能看到旧组件的样子：
   玻璃里永远是背景，内容滑到栏底下会整条消失。
   =========================================================================== */

private val DEMO_NAV = listOf(
    FloatingNavItem("首页", Icons.Outlined.Home),
    FloatingNavItem("发现", Icons.Outlined.Explore),
    FloatingNavItem("收藏", Icons.Outlined.FavoriteBorder),
    FloatingNavItem("我的", Icons.Outlined.Person)
)

private val SCENE_NAMES = listOf("极光", "色块", "海报", "风景", "网格", "纯色")

@Composable
fun FloatingNavDemo() {
    val pageLayer = rememberPrismalGlassLayer()
    val bgLayer = rememberPrismalGlassLayer()

    var scene by remember { mutableIntStateOf(0) }
    var samplePage by remember { mutableStateOf(true) }
    var strength by remember { mutableIntStateOf(1) }
    var panelOpen by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }

    /* 内容变化探针：滚动时把 snapshot state 读一遍。
       玻璃节点的 effects 在 observeReads 里求值，读到它就会重新取样，
       保证「页面在动 → 玻璃里的内容也在动」不依赖具体机型的缓存行为。 */
    val listState = rememberLazyListState()
    val contentTick: () -> Unit = {
        listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset
    }

    val blur = listOf(6.dp, 13.dp, 22.dp)[strength]
    val refractH = listOf(16.dp, 26.dp, 38.dp)[strength]
    val refractA = listOf(11.dp, 20.dp, 30.dp)[strength]

    LibraryThemed {
        Box(Modifier.fillMaxSize().background(Color(0xFF06090F))) {

            /* ① 页面层：背景场景 + 滚动内容，一起录进 pageLayer */
            Box(Modifier.fillMaxSize().prismalGlassLayer(pageLayer)) {
                Box(Modifier.fillMaxSize().prismalGlassLayer(bgLayer)) {
                    DemoScene(scene)
                }
                DemoContent(listState)
            }

            /* ② ③ 悬浮层：只有这两块会采样页面层，所以给它们提供 backdrop */
            CompositionLocalProvider(LocalPrismalBackdrop provides pageLayer) {
                DemoPanel(
                    scene = scene,
                    onScene = { scene = it },
                    samplePage = samplePage,
                    onSamplePage = { samplePage = it },
                    strength = strength,
                    onStrength = { strength = it },
                    open = panelOpen,
                    onToggle = { panelOpen = !panelOpen },
                    contentTick = contentTick,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )

                FloatingPageNavBar(
                    backdrop = if (samplePage) pageLayer else bgLayer,
                    items = DEMO_NAV,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    blur = blur,
                    refractionHeight = refractH,
                    refractionAmount = refractA,
                    chroma = 0.45f,
                    scrim = if (darkText()) 0.12f else 0.14f,
                    dropletScrim = 0.26f,
                    contentTick = contentTick
                )
            }
        }
    }
}

/* ---------------- 控制面板 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(
    scene: Int,
    onScene: (Int) -> Unit,
    samplePage: Boolean,
    onSamplePage: (Boolean) -> Unit,
    strength: Int,
    onStrength: (Int) -> Unit,
    open: Boolean,
    onToggle: () -> Unit,
    contentTick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LiquidGlass(
        modifier = modifier.fillMaxWidth(),
        level = GlassLevel.Panel,
        nested = true,
        contentTick = contentTick
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "悬浮导航 · 实时内容映射",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        if (samplePage) "采样源：页面内容（新）" else "采样源：仅壁纸（旧做法）",
                        fontSize = 11.sp,
                        color = if (samplePage) AccentBlue else TextMuted
                    )
                }
                Pill(
                    text = if (open) "收起" else "设置",
                    bg = Color(0x1FFFFFFF),
                    fg = TextSub
                ) { onToggle() }
            }

            if (open) {
                Spacer(Modifier.height(12.dp))
                Text("背景", fontSize = 11.sp, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SCENE_NAMES.forEachIndexed { i, name ->
                        val sel = i == scene
                        Pill(
                            text = name,
                            bg = if (sel) Color(0x332B7FFF) else null,
                            fg = if (sel) AccentBlue else TextSub
                        ) { onScene(i) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("采样源", fontSize = 11.sp, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        text = "页面内容",
                        bg = if (samplePage) Color(0x332B7FFF) else null,
                        fg = if (samplePage) AccentBlue else TextSub
                    ) { onSamplePage(true) }
                    Pill(
                        text = "仅壁纸",
                        bg = if (!samplePage) Color(0x332B7FFF) else null,
                        fg = if (!samplePage) AccentBlue else TextSub
                    ) { onSamplePage(false) }
                }

                Spacer(Modifier.height(12.dp))
                Text("玻璃强度", fontSize = 11.sp, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("轻", "中", "重").forEachIndexed { i, name ->
                        val sel = i == strength
                        Pill(
                            text = name,
                            bg = if (sel) Color(0x332B7FFF) else null,
                            fg = if (sel) AccentBlue else TextSub
                        ) { onStrength(i) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "上下滑动页面：导航栏里的折射内容应当跟着一起动。" +
                        "切到「仅壁纸」，玻璃里就只剩背景，内容会从栏底下消失。\n" +
                        "底部那一条：按住任意位置会整体放大回弹，按住横向拖动液滴会跟手，松手按甩动速度吸附到最近一格。",
                    fontSize = 11.5.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

/* ---------------- 页面内容（被采样的那一层） ---------------- */

private val SWATCHES = listOf(
    Color(0xFFFF6B6B), Color(0xFFFFA94D), Color(0xFFFFD43B), Color(0xFF51CF66),
    Color(0xFF20C997), Color(0xFF4DABF7), Color(0xFF5C7CFA), Color(0xFFCC5DE8)
)

@Composable
private fun DemoContent(listState: LazyListState) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 230.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("实时映射测试", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(
                "这一层被录进了 pageLayer。下面的导航栏折的就是这些像素。",
                fontSize = 12.5.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
        }

        item { PhotoCard(0) }

        item {
            /* 色带：横向 8 格纯色，滑动时能清楚看到色散把边缘拆开 */
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                SWATCHES.forEach { c ->
                    Box(Modifier.weight(1f).height(56.dp).background(c))
                }
            }
        }

        item { StripeCard() }

        item { PhotoCard(1) }

        item {
            GlassInfoCard()
        }

        items((1..24).toList()) { i ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "%02d".format(i),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SWATCHES[i % SWATCHES.size]
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "用于测试折射的一行内容 $i",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    Text(
                        "滑到导航栏下面时，这里的字应该被折弯、被模糊",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SWATCHES[i % SWATCHES.size])
                )
            }
        }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2B7FFF), Color(0xFF8B5CF6), Color(0xFFFF6BCB))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "底部大色块 · 列表结束",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PhotoCard(variant: Int) {
    val palettes = listOf(
        listOf(Color(0xFF0B1E3D), Color(0xFF1E5F8C), Color(0xFFE8A33D)),
        listOf(Color(0xFF131A2B), Color(0xFF3B2E6E), Color(0xFFE05A5A))
    )
    val p = palettes[variant % palettes.size]
    Box(
        Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(RoundedCornerShape(18.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(colors = listOf(p[0], p[1], p[2])))
            /* 太阳 */
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.95f), Color.Transparent),
                    center = Offset(w * 0.72f, h * 0.32f),
                    radius = h * 0.34f
                ),
                radius = h * 0.34f,
                center = Offset(w * 0.72f, h * 0.32f)
            )
            /* 两层山脊 */
            val back = Path().apply {
                moveTo(0f, h * 0.78f)
                lineTo(w * 0.22f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.74f)
                lineTo(w * 0.62f, h * 0.46f)
                lineTo(w, h * 0.80f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(back, Color.Black.copy(alpha = 0.34f))
            val front = Path().apply {
                moveTo(0f, h * 0.92f)
                lineTo(w * 0.30f, h * 0.68f)
                lineTo(w * 0.55f, h * 0.90f)
                lineTo(w * 0.80f, h * 0.70f)
                lineTo(w, h * 0.88f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(front, Color.Black.copy(alpha = 0.55f))
            /* 水面反光 */
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    startY = h * 0.72f,
                    endY = h
                ),
                topLeft = Offset(0f, h * 0.72f),
                size = Size(w, h * 0.28f)
            )
        }
        Text(
            if (variant == 0) "渐变 + 山脊" else "高对比山脊",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
        )
    }
}

@Composable
private fun StripeCard() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(18.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF10141C))
            val step = 18.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = Color.White.copy(alpha = 0.14f),
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = step * 0.5f
                )
                x += step
            }
        }
        Column(Modifier.padding(14.dp)) {
            Text("斜纹", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(
                "高频条纹最适合看折射是否真的在折",
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun GlassInfoCard() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
                )
            )
            .padding(14.dp)
    ) {
        Column {
            Text(
                "这一张卡没有任何玻璃",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "页面上所有东西都是普通绘制，只有底部那条栏是液态玻璃——" +
                    "这样就不会出现「玻璃套玻璃」的采样自引用。",
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.66f),
                lineHeight = 17.sp
            )
        }
    }
}

/* ---------------- 背景场景（可切换） ---------------- */

@Composable
private fun DemoScene(kind: Int) {
    Box(Modifier.fillMaxSize()) {
        when (kind) {
            0 -> Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF060B16), Color(0xFF0B1B33), Color(0xFF07121F))
                    )
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF2B7FFF).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.24f),
                        radius = size.width * 0.55f
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.2f, size.height * 0.24f)
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF34C759).copy(alpha = 0.42f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.42f),
                        radius = size.width * 0.5f
                    ),
                    radius = size.width * 0.5f,
                    center = Offset(size.width * 0.85f, size.height * 0.42f)
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFCC5DE8).copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(size.width * 0.55f, size.height * 0.86f),
                        radius = size.width * 0.6f
                    ),
                    radius = size.width * 0.6f,
                    center = Offset(size.width * 0.55f, size.height * 0.86f)
                )
            }

            1 -> Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF0D0F14))
                val cols = listOf(
                    Color(0xFFFF6B6B), Color(0xFFFFD43B), Color(0xFF51CF66), Color(0xFF4DABF7),
                    Color(0xFFCC5DE8), Color(0xFF20C997)
                )
                val cellW = size.width / 2f
                val cellH = size.height / 6f
                for (r in 0 until 6) {
                    for (c in 0 until 2) {
                        drawRect(
                            color = cols[(r * 2 + c) % cols.size].copy(alpha = 0.85f),
                            topLeft = Offset(c * cellW, r * cellH),
                            size = Size(cellW, cellH)
                        )
                    }
                }
            }

            2 -> DemoPosterScene()

            3 -> Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val w = size.width
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A1A2F), Color(0xFF27506E), Color(0xFFD98F4A))
                    )
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFE8B0), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.42f),
                        radius = h * 0.2f
                    ),
                    radius = h * 0.2f,
                    center = Offset(w * 0.5f, h * 0.42f)
                )
                val ridge = Path().apply {
                    moveTo(0f, h * 0.62f)
                    lineTo(w * 0.18f, h * 0.46f)
                    lineTo(w * 0.34f, h * 0.60f)
                    lineTo(w * 0.52f, h * 0.40f)
                    lineTo(w * 0.72f, h * 0.58f)
                    lineTo(w, h * 0.44f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(ridge, Color(0xFF121A24))
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF121A24).copy(alpha = 0.9f), Color(0xFF05080D))
                    ),
                    topLeft = Offset(0f, h * 0.72f),
                    size = Size(w, h * 0.28f)
                )
            }

            4 -> Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFF0A0E15))
                val step = 28.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        Color.White.copy(alpha = 0.12f),
                        Offset(x, 0f), Offset(x, size.height), 1f
                    )
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        Color.White.copy(alpha = 0.12f),
                        Offset(0f, y), Offset(size.width, y), 1f
                    )
                    y += step
                }
            }

            else -> Box(Modifier.fillMaxSize().background(Color(0xFF1A1E26)))
        }
    }
}

/** 「海报」场景：密集文字，用来测玻璃里的字会不会被折成重影 */
@Composable
private fun DemoPosterScene() {
    val lines = listOf(
        "LIQUID GLASS" to 30,
        "液态玻璃 · 实时折射测试" to 16,
        "下面的导航栏应该折射这一整块内容" to 13,
        "0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ" to 12,
        "高密度小字最容易看出色散是否过强" to 12
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF101018), Color(0xFF241030), Color(0xFF0D1420))
                )
            )
            .padding(18.dp)
    ) {
        lines.forEach { (text, size) ->
            Text(
                text,
                fontSize = size.sp,
                fontWeight = if (size >= 16) FontWeight.ExtraBold else FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(10.dp))
        }
        repeat(6) {
            Text(
                "CONFIDENTIAL · 实时映射 · 折弯 · 色散 · 模糊 · 高光",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
