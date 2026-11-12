package host.workbuddy.dailytasklist.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/* ---------------- 自定义取色盘 ---------------- */

/** 色相环的 13 个采样点，首尾同为红，保证 sweepGradient 平滑闭合 */
private val HUE_STOPS: List<Color> = (0..12).map { Color.hsv((it * 30f) % 360f, 1f, 1f) }

/** 把 ARGB Long 拆成 HSV，用于初始化色盘位置 */
private fun longToHsv(argb: Long): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), hsv)
    return hsv
}

/** HSV → ARGB Long（0xFFRRGGBB） */
fun hsvToLong(hue: Float, sat: Float, value: Float): Long =
    (Color.hsv(hue, sat, value).toArgb().toLong() and 0xFFFFFFFFL)

/** ARGB Long → "#RRGGBB" */
fun argbToHex(argb: Long): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

/**
 * 自定义取色盘弹窗。
 * 圆形色盘：角度选色相，半径选饱和度；下方滑条单独调明度。
 * 这样任意颜色都能调出来，不再局限于预设色板。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    SheetDialog(onDismiss) {
        ColorPickerContent(title, initial, onDismiss, onConfirm)
    }
}

/** 弹窗内容单独抽出来，方便离屏截图预览（Dialog 本身不在截图范围内） */
@Composable
fun ColorPickerContent(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val start = remember(initial) { longToHsv(initial) }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var sat by remember(initial) { mutableFloatStateOf(start[1]) }
    var value by remember(initial) { mutableFloatStateOf(start[2].coerceAtLeast(0.15f)) }

    val current = hsvToLong(hue, sat, value)

    Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
    Spacer(Modifier.height(4.dp))
    Text("拖动色盘选色相和饱和度，下方滑条调明暗", fontSize = 12.sp, color = TextMuted)

    Spacer(Modifier.height(16.dp))

    /* 当前颜色预览 */
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(current))
                .border(1.dp, Color(0x1F000000), RoundedCornerShape(13.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("当前颜色", fontSize = 11.5.sp, color = TextMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                argbToHex(current),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    /* 色盘本体 */
    HsvWheel(
        hue = hue,
        sat = sat,
        value = value,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(horizontal = 8.dp)
    ) { h, s ->
        hue = h
        sat = s
    }

    Spacer(Modifier.height(14.dp))

    /* 明度滑条 */
    Text("明度", fontSize = 12.sp, color = TextMuted)
    Spacer(Modifier.height(6.dp))
    ValueBar(
        hue = hue,
        sat = sat,
        value = value,
        onValue = { value = it }
    )

    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("取消", Modifier.weight(1f), onClick = onDismiss)
        PrimaryButton("使用这个颜色", Modifier.weight(1.6f), AccentBlue) {
            onConfirm(current)
        }
    }
}

/** 圆形 HSV 色盘：角度 = 色相，半径 = 饱和度，整体叠加黑色表示明度 */
@Composable
private fun HsvWheel(
    hue: Float,
    sat: Float,
    value: Float,
    modifier: Modifier = Modifier,
    onChange: (hue: Float, sat: Float) -> Unit
) {
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun pick(pos: Offset) {
        if (box.width == 0 || box.height == 0) return
        val cx = box.width / 2f
        val cy = box.height / 2f
        val radius = min(box.width, box.height) / 2f
        if (radius <= 0f) return
        val dx = pos.x - cx
        val dy = pos.y - cy
        val dist = hypot(dx, dy)
        val s = (dist / radius).coerceIn(0f, 1f)
        /* 只有离圆心足够远才更新色相，避免中心区域色相乱跳 */
        if (dist > radius * 0.06f) {
            var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            if (deg < 0f) deg += 360f
            onChange(deg, s)
        } else {
            onChange(hue, s)
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures { pick(it) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pick(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        pick(change.position)
                    }
                )
            }
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        /* 1. 色相：整圆扫过 360° */
        drawCircle(
            brush = Brush.sweepGradient(HUE_STOPS, center = center),
            radius = radius,
            center = center
        )
        /* 2. 饱和度：圆心白 → 边缘透明 */
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        /* 3. 明度：整体压暗 */
        if (value < 0.999f) {
            drawCircle(
                color = Color.Black.copy(alpha = 1f - value),
                radius = radius,
                center = center
            )
        }
        drawCircle(
            color = Color(0x14000000),
            radius = radius,
            center = center,
            style = Stroke(1.dp.toPx())
        )

        /* 4. 指示器 */
        val rad = Math.toRadians(hue.toDouble())
        val ix = center.x + cos(rad).toFloat() * sat * radius
        val iy = center.y + sin(rad).toFloat() * sat * radius
        val pos = Offset(ix, iy)
        drawCircle(
            color = Color.Black.copy(alpha = 0.28f),
            radius = 10.dp.toPx(),
            center = pos,
            style = Stroke(5.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = 10.dp.toPx(),
            center = pos,
            style = Stroke(3.dp.toPx())
        )
    }
}

/** 明度滑条：黑 → 当前色相/饱和度的最亮色 */
@Composable
private fun ValueBar(
    hue: Float,
    sat: Float,
    value: Float,
    onValue: (Float) -> Unit
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val bright = Color.hsv(hue, sat, 1f)
    val h = 34.dp

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(h)
            .onSizeChanged { box = it }
            .pointerInput(hue, sat) {
                detectTapGestures { p ->
                    if (box.width > 0) onValue((p.x / box.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(hue, sat) {
                detectDragGestures(
                    onDragStart = { p ->
                        if (box.width > 0) onValue((p.x / box.width).coerceIn(0f, 1f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (box.width > 0) onValue((change.position.x / box.width).coerceIn(0f, 1f))
                    }
                )
            }
    ) {
        val r = size.height / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, bright)),
            size = Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        val x = (value * size.width).coerceIn(r, size.width - r)
        drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.82f, Offset(x, r), style = Stroke(5.dp.toPx()))
        drawCircle(Color.White, r * 0.82f, Offset(x, r), style = Stroke(3.dp.toPx()))
    }
}
