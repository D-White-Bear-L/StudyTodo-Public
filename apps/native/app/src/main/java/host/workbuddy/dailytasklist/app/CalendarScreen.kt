package host.workbuddy.dailytasklist.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.components.PrismalGlassToggle
import kotlin.math.roundToInt

/* ---------------- 日历页 ---------------- */

@Composable
fun CalendarScreen(
    store: AppStore,
    selectedDate: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit
) {
    var year by remember { mutableStateOf(Dates.year(selectedDate)) }
    var month by remember { mutableStateOf(Dates.month(selectedDate)) }
    var showPicker by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    val theme = Color(store.settings.theme)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            /* 顶栏 */
            AppTopBar(title = "选择日期", onBack = onBack) {
                Pill("导出图片", bg = Color(0x1F34C759), fg = AccentGreen) { showExport = true }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                /* 月历 */
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleIconButton({
                            if (month == 0) { month = 11; year-- } else month--
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                null,
                                tint = TextSub,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            "${year}年${month + 1}月",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        CircleIconButton({
                            if (month == 11) { month = 0; year++ } else month++
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                null,
                                tint = TextSub,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    /* 星期表头 */
                    Row(Modifier.fillMaxWidth()) {
                        Dates.WEEK_CN.forEach { w ->
                            Text(
                                w,
                                fontSize = 12.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    /* 日期网格 */
                    val lead = Dates.firstWeekday(year, month)
                    val total = Dates.daysInMonth(year, month)
                    val rows = ((lead + total) + 6) / 7

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in 0 until rows) {
                            Row(Modifier.fillMaxWidth()) {
                                for (c in 0 until 7) {
                                    val index = r * 7 + c
                                    val dayNum = index - lead + 1
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (dayNum in 1..total) {
                                            val ds = Dates.ymd(year, month, dayNum)
                                            val (t, _, rate) = store.progressOf(ds)
                                            val has = t > 0
                                            val isSel = ds == selectedDate
                                            val isToday = ds == Dates.today()

                                            /*
                                             * 完成度的表达拆成两层，解决「颜色很淡就看不见」：
                                             *  1) 底色：只要有任务，最低也有 0.20 的主题色混色，
                                             *     0% 的格子也不会消失；完成度越高颜色越浓（0.20→0.60）。
                                             *  2) 底部一条进度条：宽度 = 完成度，任何时候都看得清
                                             *     今天到底做了多少，不依赖颜色深浅去猜。
                                             */
                                            /* 开关关掉时整片完成度着色都不画，保持原来的语义 */
                                            val showRate = has && store.settings.showCompletion
                                            /* 深色模式下混色起点要用深色，否则 0% 的格子会变成一整片发亮的小白块。
                                               用 darkText() 而不是系统深浅色，跟随设置页的「文字颜色」。 */
                                            val mixBase =
                                                if (darkText()) Color(0xFF1B2432) else Color.White
                                            val fill = if (showRate) lerp(mixBase, theme, 0.20f + 0.40f * rate) else null
                                            /* 字色按底色对比度选：颜色很浓的格子上白字会糊，
                                               换成深墨才读得出。 */
                                            val fg = if (fill != null) {
                                                readableInk(fill, Color.White, 4.5f)
                                            } else {
                                                TextPrimary
                                            }
                                            val cellShape = RoundedCornerShape(11.dp)

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f)
                                                    .clip(cellShape)
                                                    /* 渐变而不是平涂：整页都是带层次的玻璃，
                                                       纯色小方块看着像另一个 app 的日历。 */
                                                    .then(
                                                        if (fill == null) Modifier
                                                        else Modifier.background(
                                                            Brush.verticalGradient(
                                                                listOf(
                                                                    fill.copy(alpha = 0.96f),
                                                                    fill.copy(alpha = 0.80f)
                                                                )
                                                            )
                                                        )
                                                    )
                                                    /* 选中改成「描边」而不是整块填充：
                                                       整块填满会把当天的完成度颜色盖掉。 */
                                                    .then(
                                                        if (isSel) Modifier.border(
                                                            BorderStroke(2.dp, theme),
                                                            cellShape
                                                        ) else Modifier
                                                    )
                                                    .clickable { onPick(ds) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                    modifier = Modifier.padding(bottom = if (showRate) 5.dp else 0.dp)
                                                ) {
                                                    Text(
                                                        "$dayNum",
                                                        fontSize = if (showRate) 13.5.sp else 14.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = fg,
                                                        lineHeight = 15.sp
                                                    )
                                                    if (showRate) {
                                                        Text(
                                                            "${(rate * 100).roundToInt()}%",
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            letterSpacing = (-0.2).sp,
                                                            color = fg,
                                                            lineHeight = 9.sp
                                                        )
                                                    }
                                                }

                                                /* 完成度进度条：贴底，宽度按比例 */
                                                if (showRate) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomStart)
                                                            .padding(start = 5.dp, end = 5.dp, bottom = 3.5.dp)
                                                            .fillMaxWidth()
                                                            .height(2.5.dp)
                                                            .clip(RoundedCornerShape(50))
                                                            .background(fg.copy(alpha = 0.14f)),
                                                        contentAlignment = Alignment.CenterStart
                                                    ) {
                                                        if (rate > 0f) {
                                                            Box(
                                                                Modifier
                                                                    .fillMaxWidth(rate.coerceIn(0.06f, 1f))
                                                                    .fillMaxHeight()
                                                                    .clip(RoundedCornerShape(50))
                                                                    .background(theme)
                                                            )
                                                        }
                                                    }
                                                }

                                                /* 今天：右上角一个小圆点。
                                                   原来是把整格描一圈主题色，和完成度色块撞色、
                                                   等于把当天做了多少给盖住了。 */
                                                if (isToday) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(4.dp)
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(theme)
                                                            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    /* 完成度开关：左下角说明，右侧直接用库的玻璃开关（PrismalGlassToggle），
                       而不是自己画一个开关外框 + 一个白圆点。 */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "在日期下显示完成度",
                                fontSize = 13.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "每个日期数字下面标出当天完成百分比",
                                fontSize = 11.5.sp,
                                color = TextMuted
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        val calBackdrop = glassBackdropForChild()
                        if (calBackdrop != null) {
                            PrismalGlassToggle(
                                selected = { store.settings.showCompletion },
                                onSelect = { store.toggleCompletion() },
                                backdrop = calBackdrop
                            )
                        } else {
                            /* 没有背景层（测试/无壁纸环境）时退回一个干净的系统风格开关 */
                            Box(
                                modifier = Modifier
                                    .width(46.dp)
                                    .height(26.dp)
                                    .clip(CircleShape)
                                    .background(if (store.settings.showCompletion) AccentGreen else Color(0xFFCCD2DA))
                                    .clickable { store.toggleCompletion() },
                                contentAlignment = if (store.settings.showCompletion) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(3.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.94f))
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                /* 主题色 */
                GlassCard {
                    val custom = THEME_COLORS.none { it == store.settings.theme }
                    val curHex = argbToHex(store.settings.theme)

                    /* 顶部：当前颜色大预览 + 标题 */
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ThemeBead(
                            color = Color(store.settings.theme),
                            size = 44.dp,
                            selected = true,
                            check = custom
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "日历主题色",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (custom) "自定义 $curHex" else "预设颜色",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    /* 3×3 紧凑色盘：选中态用内部 ✓，不缩小圆点 */
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        THEME_COLORS.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                row.forEach { c ->
                                    val on = store.settings.theme == c
                                    Box(
                                        modifier = Modifier.size(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ThemeBead(
                                            color = Color(c),
                                            size = 34.dp,
                                            selected = on,
                                            check = on,
                                            onClick = { store.setTheme(c) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    /* 自定义入口 */
                    val rowSource = remember { MutableInteractionSource() }
                    val rowTint = if (custom) Color(store.settings.theme) else null
                    val rowInk = readableInk(
                        tintedSurface(rowTint, 0.45f),
                        if (custom) Color(store.settings.theme) else TextSub
                    )
                    LiquidSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pressable(interactionSource = rowSource) { showPicker = true },
                        level = GlassLevel.Control,
                        radius = 12.dp,
                        tint = rowTint,
                        tintK = 0.35f,
                        interactive = true,
                        interactionSource = rowSource,
                        pressScaleFactor = 0.012f
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Outlined.Palette,
                                null,
                                tint = rowInk,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (custom) "调整自定义颜色" else "自定义主题色",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = rowInk
                            )
                        }
                    }
                }
            }
        }

    if (showExport) {
        CalendarExportDialog(store = store, year = year, month = month) { showExport = false }
    }

    if (showPicker) {
        ColorPickerDialog(
            title = "日历主题色",
            initial = store.settings.theme,
            onDismiss = { showPicker = false },
            onConfirm = {
                store.setTheme(it)
                showPicker = false
            }
        )
    }
}
}

/* ---------------- 主题色玻璃珠 ---------------- */

/**
 * 主题色圆点。
 *
 * 原来这里是「纯色圆 + 白描边」：一页都是带折射、有高光的液态玻璃，
 * 中间摆着九颗平涂色块，画风就是从这里断掉的。
 *
 * 现在颜色是**染进玻璃里**的（tint + tintK），底层走的是和卡片、按钮同一套
 * glassMaterial()：边缘有真折射（珠子会把卡片背景折弯）、上沿有高光、
 * 按下去会胀一点。选中时外面加一圈亮环。
 *
 * 直径 34dp 的圆不需要把折射关掉——PrismalGlass 的折射带会被
 * MaxRefractRatio 自动封顶到短边的 42%，中线不会落进折射区，
 * 所以小圆珠既不会有中间那条线，又能真的折射。
 */
@Composable
private fun ThemeBead(
    color: Color,
    size: Dp,
    selected: Boolean = false,
    check: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    val ringColor = if (selected) Color.White.copy(alpha = 0.92f)
    else Color.White.copy(alpha = if (darkText()) 0.16f else 0.30f)

    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (onClick != null) Modifier.pressable(interactionSource = source, onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        LiquidSurface(
            modifier = Modifier.matchParentSize(),
            level = GlassLevel.Control,
            radius = size / 2,
            shape = CircleShape,
            tint = color,
            tintK = 1f,
            rimScale = 1.1f,
            interactive = onClick != null,
            interactionSource = source,
            pressScaleFactor = 0.07f
        ) {}

        /* 亮环画在玻璃之上：压在玻璃下面的环会被折射糊掉，看起来像没画 */
        Box(
            Modifier
                .matchParentSize()
                .padding(1.dp)
                .border(if (selected) 2.dp else 0.9.dp, ringColor, CircleShape)
        )

        if (check) {
            Text(
                "✓",
                fontSize = (size.value * 0.36f).sp,
                color = readableInk(color, Color.White, 3f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
