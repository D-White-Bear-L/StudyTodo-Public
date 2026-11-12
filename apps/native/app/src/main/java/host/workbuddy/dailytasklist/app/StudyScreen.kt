package host.workbuddy.dailytasklist.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ===========================================================================
   学习详情页（v1.31 重做）
   ---------------------------------------------------------------------------
   两个状态，默认「只读」：

     只读  —— 四行摘要（时长 / 开始 / 地点 / 复盘），摆在一张卡片里。
              翻页、上下滑动都不会改到数据。
     编辑  —— 点右上角「编辑」，或**直接点某一行**（点哪改哪）才进入。

   为什么重做（上一版的问题）：
     · 一进编辑态，四组刻度全都铺开：两块半宽的小刻度挤成左右两条、
       三块整宽的刻度上下叠着，页面像一张被拉长的仪表盘，
       而且大半个屏幕都被「刻度」占住，看着就没有主次。
     · 半宽刻度只有半屏宽，一屏只能看到三项，指针还容易偏；
       同行两条刻度靠得太近，手指在哪儿起手都可能拨错。
     · 四条刻度都吃横向手势，而库的手势在**按下瞬间**就开始接管、
       还不消费事件——于是页面在上下滚、刻度同时在左右滑，
       这就是「操作上怪怪的」。
     · 水滴被做成了一圈等亮的描边，像贴纸上画了个圆圈；
       再加上一排刻度全是同一个水滴，看着就是「定死在那」。

   现在的做法：
     · **一次只展开一项**（手风琴）。收起时就是一张干净的四行设置表：
       左标题、右数值、末尾一个会翻面的小箭头。
     · 展开的那一项占整宽，单位（小时 / 分钟）退到左边一列，
       刻度在右边整宽铺开，格宽统一、读数对齐。
     · 点哪一行就展开哪一行，再点一次收起；只展开一项，
       屏幕上最多只有一条（时长为两条）可拖的手势区，其余面积都能正常滚动。
     · 水滴改成「上亮下暗 + 只在中段出现的顶部弧光 + 球形反光点」，
       不再是整圈描边，也不再有四条一模一样的水滴并排。
   =========================================================================== */

/** 编辑态里可以展开的四个字段 */
private enum class StudyField { Duration, Start, Place, Review }

@Composable
fun StudyScreen(
    store: AppStore,
    date: String,
    onBack: () -> Unit
) {
    val current = store.studyOf(date)
    val ctx = LocalContext.current

    var editing by remember { mutableStateOf(false) }
    /** 当前展开的那一项；null = 全部收起 */
    var open by remember { mutableStateOf<StudyField?>(StudyField.Duration) }

    /* 草稿：只在进入编辑时从已存数据同步一次。取消就直接丢弃。 */
    val (dh0, dm0) = parseDurationHours(current.hours)
    val (sh0, sm0) = parseClock(current.start, 8)
    val (rh0, rm0) = parseClock(current.review, 22)

    var durH by remember { mutableIntStateOf(dh0) }
    var durM by remember { mutableIntStateOf(dm0) }
    var startH by remember { mutableIntStateOf(sh0) }
    var startM by remember { mutableIntStateOf(sm0) }
    var reviewH by remember { mutableIntStateOf(rh0) }
    var reviewM by remember { mutableIntStateOf(rm0) }
    var place by remember { mutableStateOf(current.place) }

    /*
     * 三个刻度各自记一个「用户动过没有」。
     * 刻度永远有值（总得停在某一格），如果无脑保存，用户只想填地点也会被
     * 顺带写入 0 小时 / 08:00，把「未填写」变成假数据。动过才写。
     */
    var durTouched by remember { mutableStateOf(current.hours.isNotBlank()) }
    var startTouched by remember { mutableStateOf(current.start.isNotBlank()) }
    var reviewTouched by remember { mutableStateOf(current.review.isNotBlank()) }

    /* 进入编辑：把草稿重置成「当前已存的值」，避免上次取消留下的残留 */
    fun beginEdit(field: StudyField) {
        val s = store.studyOf(date)
        val (h1, m1) = parseDurationHours(s.hours)
        val (h2, m2) = parseClock(s.start, 8)
        val (h3, m3) = parseClock(s.review, 22)
        durH = h1; durM = m1
        startH = h2; startM = m2
        reviewH = h3; reviewM = m3
        place = s.place
        durTouched = s.hours.isNotBlank()
        startTouched = s.start.isNotBlank()
        reviewTouched = s.review.isNotBlank()
        open = field
        editing = true
    }

    /* 只读时点行 = 进入编辑并展开这一行；编辑时点行 = 展开 / 收起 */
    fun onRowClick(field: StudyField) {
        if (!editing) beginEdit(field) else open = if (open == field) null else field
    }

    /* 显示值：只读用存盘值，编辑用草稿 —— 取消之后不会残留一份「改了一半」的数字 */
    val durText = if (editing) {
        if (durTouched) prettyDuration(durH, durM) else "未设置"
    } else {
        if (current.hours.isBlank()) "未设置" else prettyDuration(dh0, dm0)
    }
    val startText = if (editing) {
        if (startTouched) formatClock(startH, startM) else "未设置"
    } else {
        if (current.start.isBlank()) "未设置" else formatClock(sh0, sm0)
    }
    val placeText = (if (editing) place else current.place).ifBlank { "未设置" }
    val reviewText = if (editing) {
        if (reviewTouched) formatClock(reviewH, reviewM) else "未设置"
    } else {
        if (current.review.isBlank()) "未设置" else formatClock(rh0, rm0)
    }

    val yesterday = Dates.addDays(date, -1)
    val hasYesterday = !store.studyOf(yesterday).isEmpty || store.tasksOf(yesterday).any { !it.done }

    Column(Modifier.fillMaxSize()) {

        AppTopBar(
            title = "学习详情",
            subtitle = if (editing) "编辑中 · 保存后生效" else date,
            /* 编辑态下返回键先退出编辑，不会把改了一半的东西直接带出页面 */
            onBack = { if (editing) editing = false else onBack() }
        ) {
            if (!editing) {
                Pill(
                    "编辑",
                    bg = Color(0x242B7FFF),
                    fg = AccentBlue,
                    onClick = { beginEdit(StudyField.Duration) }
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassCard(padding = 16.dp) {

                FieldRow(
                    label = "学习总时长",
                    value = durText,
                    empty = durText == "未设置",
                    expanded = editing && open == StudyField.Duration,
                    onClick = { onRowClick(StudyField.Duration) }
                ) {
                    DurationRulers(
                        hour = durH,
                        minute = durM,
                        onHour = { durH = it; durTouched = true },
                        onMinute = { durM = it; durTouched = true },
                        hourRange = 0..16,
                        /* 5 分钟一档：拆成「小时 + 分钟」两条之后，
                           拨两下就能精确到 5 分钟，不再只能调半小时。 */
                        minuteStep = 5
                    )
                }

                FieldDivider()

                FieldRow(
                    label = "开始学习时间",
                    value = startText,
                    empty = startText == "未设置",
                    expanded = editing && open == StudyField.Start,
                    onClick = { onRowClick(StudyField.Start) }
                ) {
                    ClockRuler(
                        hour = startH,
                        minute = startM,
                        onHour = { startH = it; startTouched = true },
                        onMinute = { startM = it; startTouched = true }
                    )
                }

                FieldDivider()

                FieldRow(
                    label = "学习地点",
                    value = placeText,
                    empty = placeText == "未设置",
                    expanded = editing && open == StudyField.Place,
                    onClick = { onRowClick(StudyField.Place) }
                ) {
                    InputBox(place, { place = it }, "例如 图书馆 / 自习室")
                    Spacer(Modifier.height(2.dp))
                }

                FieldDivider()

                FieldRow(
                    label = "复盘时间",
                    value = reviewText,
                    empty = reviewText == "未设置",
                    expanded = editing && open == StudyField.Review,
                    onClick = { onRowClick(StudyField.Review) }
                ) {
                    ClockRuler(
                        hour = reviewH,
                        minute = reviewM,
                        onHour = { reviewH = it; reviewTouched = true },
                        onMinute = { reviewM = it; reviewTouched = true }
                    )
                }
            }

            if (editing) {
                Text(
                    "刻度可以直接左右拖动，松手自动吸附到最近一格；" +
                        "只展开当前这一项，页面其他位置照常上下滚动。",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else if (hasYesterday) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Pill("沿用上一日的记录", bg = Color(0x242B7FFF), fg = AccentBlue) {
                        val ok = store.carryOver(date)
                        if (ok) toast(ctx, "已沿用昨日数据") else toast(ctx, "昨天没有可沿用的数据")
                    }
                }
            }
        }

        /* 底部「取消 / 保存」只在编辑态出现 —— 这是本页最重要的状态提示 */
        if (editing) {
            BottomBar {
                GhostButton("取消", Modifier.weight(1f), onClick = { editing = false })
                PrimaryButton("保存", Modifier.weight(1.6f), AccentBlue) {
                    store.setStudy(
                        date,
                        Study(
                            hours = if (durTouched) formatDurationHours(durH, durM) else "",
                            start = if (startTouched) formatClock(startH, startM) else "",
                            place = place.trim(),
                            review = if (reviewTouched) formatClock(reviewH, reviewM) else ""
                        )
                    )
                    toast(ctx, "已保存")
                    editing = false
                }
            }
        }
    }
}

/**
 * 一行字段：左边标题、右边当前值、末尾一个会随展开翻面的小箭头。
 *
 * 收起 = 一行 44dp 的设置项；展开 = 这一行下面接上它自己的编辑区。
 * 页面上永远只有一项展开，所以同一时刻只有一块区域吃横向手势。
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    empty: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (expanded) TextPrimary else TextSub
            )
            Spacer(Modifier.weight(1f))
            Text(
                value,
                fontSize = 16.5.sp,
                fontWeight = if (empty) FontWeight.Medium else FontWeight.Bold,
                color = if (empty) TextMuted else TextPrimary,
                maxLines = 1
            )
            Spacer(Modifier.width(2.dp))
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(200),
                label = "fieldChevron"
            )
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = if (expanded) TextSub else TextMuted,
                modifier = Modifier.size(20.dp).rotate(rotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(260), expandFrom = Alignment.Top) +
                fadeIn(tween(200)),
            exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) +
                fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 6.dp),
                content = body
            )
        }
    }
}

/** 字段之间的细分割线：比整块留白更轻，但足够把四段分开 */
@Composable
private fun FieldDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.7.dp)
            .background(Hairline)
    )
}
