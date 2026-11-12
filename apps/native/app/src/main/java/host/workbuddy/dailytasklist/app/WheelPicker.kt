package host.workbuddy.dailytasklist.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ===========================================================================
   时间刻度（学习详情编辑页用）
   ---------------------------------------------------------------------------
   为什么不用库里的 PrismalRulerSelector / PrismalHorizontalSelector：
   它们的选中项由内部 scrollState 保存，第一次组合就会 emit 一次
   snapToIndex(0) → onSelected(0)，把已经填好的时间清零（详见 DateGlassStrip.kt
   开头的说明）。所以这里统一走自研的 DateGlassStrip：
     · 借库的 PrismalSpringMotion（按压放大 / 挤压）和 prismalLens（折射）；
     · 选中项由外部单向驱动，组合时不回写；
     · 液滴画在文字下层，文字永远清晰。

   两种字段：
     · 时长 → **两条**（小时 / 分钟），一格 5 分钟。
       一条 0..16 小时的 5 分钟刻度有 192 格，一屏根本划不完；
       拆成「小时 + 分钟」两条之后，想调到 3 小时 05 分就是拨两下，
       既快又准——这才是「能精细调节」。
     · 时刻 → 一条 0:00..23:55，一格 5 分钟。
   =========================================================================== */

private val RulerRowHeight = 56.dp
private val DurationItemWidth = 78.dp
private val ClockItemWidth = 92.dp
private val RulerFont = 19.sp

/** 一条刻度左边那列单位说明的宽度。两行（小时/分钟）对齐靠它。 */
private val CaptionWidth = 36.dp

/**
 * 时长：小时 + 分钟，两行上下排（不再是左右各占半屏）。
 *
 * 左右并排时每边只有半屏宽，一屏只能看到三项，指针还老跑偏；
 * 上下排成两行之后每行都是整宽，格宽一致、单位在左，读起来像一张表。
 */
@Composable
fun DurationRulers(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    hourRange: IntRange = 0..16,
    minuteStep: Int = 5,
    modifier: Modifier = Modifier
) {
    val step = minuteStep.coerceAtLeast(1)
    val hLabels = remember(hourRange) { (hourRange.first..hourRange.last).map { "$it" } }
    val mLabels = remember(step) { (0..55 step step).map { "%02d".format(it) } }

    val hIdx = (hour.coerceIn(hourRange.first, hourRange.last) - hourRange.first)
        .coerceIn(0, hLabels.lastIndex)
    val mIdx = (minute.coerceIn(0, 59) / step).coerceIn(0, mLabels.lastIndex)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RulerLine(
            caption = "小时",
            labels = hLabels,
            selectedIndex = hIdx,
            onSelected = { onHour(hourRange.first + it) },
            itemWidth = DurationItemWidth
        )
        RulerLine(
            caption = "分钟",
            labels = mLabels,
            selectedIndex = mIdx,
            onSelected = { onMinute(it * step) },
            itemWidth = DurationItemWidth
        )
    }
}

/** 时刻：一条 0:00..23:55、一格 5 分钟的刻度。 */
@Composable
fun ClockRuler(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
    minuteStep: Int = 5,
    modifier: Modifier = Modifier
) {
    val step = minuteStep.coerceAtLeast(1)
    val maxValue = 23 * 60 + 55
    val value = (hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)).coerceIn(0, maxValue)
    val labels = remember(step) { (0..maxValue step step).map { formatClock(it / 60, it % 60) } }
    val index = (value / step).coerceIn(0, labels.lastIndex)

    RulerLine(
        caption = null,
        labels = labels,
        selectedIndex = index,
        onSelected = { i ->
            val minutes = i * step
            onHour(minutes / 60)
            onMinute(minutes % 60)
        },
        itemWidth = ClockItemWidth,
        modifier = modifier
    )
}

/** 一行刻度：左边单位说明（可空）+ 右边整宽的液滴刻度条。 */
@Composable
private fun RulerLine(
    caption: String?,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    itemWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (caption != null) {
            Text(
                caption,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted,
                modifier = Modifier.width(CaptionWidth)
            )
        }
        DateGlassStrip(
            labels = labels,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = Modifier.weight(1f),
            itemWidth = itemWidth,
            height = RulerRowHeight,
            fontSize = RulerFont,
            /* 一格 5 分钟、划 62dp 走一格：一屏（≈300dp）走 25 分钟，
               想跳两小时用甩动几下就到。比日期条轻一点，因为档位更细。 */
            dragTravelPerItem = 62.dp
        )
    }
}

/* ---------------- 已有字符串 ⇄ 输入状态 ---------------- */

/**
 * "10" / "10.5" / "10.5小时" → (10, 30)
 *
 * v1.32 起时长可以按 5 分钟调，所以存盘精度也放到 5 分钟：
 * 分钟先四舍五入到最近的 5 分钟档（老数据里 10.5 这类半小时值是 5 的整数倍，不受影响）。
 */
internal fun parseDurationHours(raw: String): Pair<Int, Int> {
    val num = Regex("""(\d+(?:\.\d+)?)""").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
        ?: return 0 to 0
    val h = num.toInt().coerceIn(0, 16)
    val m = ((num - h) * 60f).toInt().coerceIn(0, 59)
    /* 归到最近的 5 分钟档：58 分进到下一小时的整点 */
    val snapped = ((m + 2) / 5) * 5
    return if (snapped >= 60) (h + 1).coerceAtMost(16) to 0 else h to snapped
}

/** "08:30" → (8, 30)；空串给 fallbackHour 整点，非法值同样回落 */
internal fun parseClock(raw: String, fallbackHour: Int): Pair<Int, Int> {
    val s = raw.trim()
    if (s.isEmpty()) return fallbackHour to 0
    val parts = s.split(":", "：")
    if (parts.size < 2) return fallbackHour to 0
    val h = parts[0].toIntOrNull() ?: return fallbackHour to 0
    val m = parts[1].toIntOrNull() ?: 0
    return h.coerceIn(0, 23) to m.coerceIn(0, 59)
}

/**
 * (10, 30) → "10.5"；(10, 5) → "10.08"。
 *
 * 保留两位小数是刻意的：一位小数只能表达 6 分钟的台阶，5 分钟一档会被
 * 抹成 10.0 / 10.1，存盘再读回来就跳档。两位小数（0.01h = 0.6min）
 * 足以无损表达任意 5 分钟，读回时再四舍五入到 5 分钟档即可。
 */
internal fun formatDurationHours(hour: Int, minute: Int): String {
    if (minute == 0) return "$hour"
    val v = hour + minute / 60f
    return "%.2f".format(v).trimEnd('0').trimEnd('.')
}

/** (8, 30) → "08:30" */
internal fun formatClock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** 时长 → 给人看的一行文字（只读摘要 / 刻度尺标签） */
internal fun prettyDuration(hour: Int, minute: Int): String =
    if (minute == 0) "$hour 小时" else "$hour 小时 $minute 分"
