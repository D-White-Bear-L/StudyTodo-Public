package host.workbuddy.dailytasklist.app

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/* ===========================================================================
   液态玻璃材质引擎
   ---------------------------------------------------------------------------
   之前的"玻璃"只是半透明白色渐变 + 一圈描边，从来没有看过它身后的画面，
   所以本质上是磨砂塑料。真正的液态玻璃要做四件事：

     1. 吃掉背景 —— 玻璃必须真的采样到自己身后的颜色
     2. 折射   —— 边缘像一块有厚度的圆角透镜，把背景往里"折"进去
     3. 色散   —— 折射率随波长变化，边缘出现极细的暖/冷分光
     4. Fresnel —— 掠射角处反射变强，形成贴着边缘的一圈亮光

   实现方式：背景不是图片，而是我们自己在 [SceneField] 里解析描述的色场，
   所以玻璃可以在 CPU 上按像素解析采样它 —— 不需要截屏、不需要 RenderEffect，
   在任何 Canvas 上（包括离屏截图）都能出真实结果。

   采样网格大约是元素尺寸的 1/46，再双线性放大回去，这一步天然就是模糊：
   等于对身后画面做了一次 ~12px 的高斯。折射位移只在边缘带内生效，
   内部区域几乎不动，所以内容区不会被搅浑。
   =========================================================================== */

/**
 * 背景画布尺寸。写在布局阶段、读在绘制阶段 —— 布局一定先于绘制，
 * 所以第一帧就能拿到正确值，不需要"先给个 0、等一次重组再画"，
 * 也就不会出现首帧玻璃是白的那一下。
 */
internal object Backdrop {
    var w = 0
    var h = 0
}

/* ---------------- 1. 场景色场 ---------------- */

/**
 * 背景色场：一层竖直底渐变 + 若干柔和的椭圆色块 + 几条极光带。
 * 色块用 alpha 混合（不是加法），颜色不会过曝；远处色块直接跳过。
 *
 * 之所以要做这么"花"的背景：玻璃如果压在一片均匀灰上，折射和模糊都看不出来。
 * 这些低对比的色带正好给玻璃提供可被弯曲的结构。
 */
internal object SceneField {

    private fun lin(c: Int): Float = c / 255f

    private fun hex(h: Int) = floatArrayOf(
        lin(h shr 16 and 0xFF),
        lin(h shr 8 and 0xFF),
        lin(h and 0xFF)
    )

    private class Blob(
        val cx: Float, val cy: Float,
        val rl: Float, val rs: Float,
        val rot: Float,
        val light: FloatArray, val dark: FloatArray,
        val kl: Float, val kd: Float
    ) {
        val cos = kotlin.math.cos(rot)
        val sin = kotlin.math.sin(rot)
    }

    private fun b(
        cx: Float, cy: Float, rl: Float, rs: Float, rotDeg: Float,
        hexColor: Int, kl: Float, kd: Float
    ) = Blob(cx, cy, rl, rs, Math.toRadians(rotDeg.toDouble()).toFloat(),
        hex(hexColor), hex(hexColor), kl, kd)

    private val BASE_L_TOP = hex(0xFFF4F7FD.toInt())
    private val BASE_L_BOT = hex(0xFFD5DFEE.toInt())
    private val BASE_D_TOP = hex(0xFF0A1018.toInt())
    private val BASE_D_BOT = hex(0xFF131C2B.toInt())

    /* 浅色：整体仍是浅底，只在几个角落压入明显的彩色，玻璃压上去才有戏 */
    private val BLOBS = listOf(
        /* 大色块：定整体色调，压低强度，避免整页一片紫 */
        b(-0.10f, 0.02f, 0.85f, 0.72f, 0f, 0xFF6E88FF.toInt(), 0.34f, 0.26f),
        b(1.10f, 0.66f, 0.80f, 0.72f, 0f, 0xFFFFA985.toInt(), 0.34f, 0.18f),
        /* 中尺度色块：让背景有"景"的结构，玻璃后面才有东西可被折射 */
        b(0.52f, 0.16f, 0.38f, 0.34f, 0f, 0xFF7FB6FF.toInt(), 0.40f, 0.24f),
        b(0.78f, 0.40f, 0.34f, 0.30f, 0f, 0xFFB49BFF.toInt(), 0.36f, 0.22f),
        b(-0.04f, 0.60f, 0.40f, 0.34f, 0f, 0xFF76E2C8.toInt(), 0.36f, 0.20f),
        b(0.34f, 0.72f, 0.36f, 0.30f, 0f, 0xFFC9A8FF.toInt(), 0.26f, 0.16f),
        b(0.50f, -0.08f, 1.20f, 0.55f, 0f, 0xFFFFFFFF.toInt(), 0.22f, 0.05f),
        /* 小尺度光斑（bokeh）：半径只有几十像素，玻璃边缘一折射就能看出图像被"推"开 */
        b(0.24f, 0.08f, 0.070f, 0.062f, 0f, 0xFFFFFFFF.toInt(), 0.26f, 0.10f),
        b(0.68f, 0.11f, 0.055f, 0.050f, 0f, 0xFFE8F1FF.toInt(), 0.24f, 0.10f),
        b(0.86f, 0.22f, 0.048f, 0.044f, 0f, 0xFFFFFFFF.toInt(), 0.22f, 0.08f),
        b(0.10f, 0.44f, 0.052f, 0.048f, 0f, 0xFFD9FFF4.toInt(), 0.20f, 0.09f),
        b(0.52f, 0.50f, 0.060f, 0.055f, 0f, 0xFFFFF3E6.toInt(), 0.20f, 0.08f),
        b(0.78f, 0.62f, 0.045f, 0.042f, 0f, 0xFFFFFFFF.toInt(), 0.18f, 0.07f),
        b(0.30f, 0.66f, 0.050f, 0.046f, 0f, 0xFFE4ECFF.toInt(), 0.18f, 0.07f),
        /* 极光带：细长的斜向椭圆，玻璃边缘"折弯"的就是它们 */
        b(0.18f, 0.26f, 1.30f, 0.038f, -26f, 0xFFFFFFFF.toInt(), 0.42f, 0.030f),
        b(0.88f, 0.44f, 1.15f, 0.030f, 22f, 0xFFC6DBFF.toInt(), 0.40f, 0.050f),
        b(0.42f, 0.84f, 1.25f, 0.034f, -14f, 0xFFFFE4D6.toInt(), 0.36f, 0.030f)
    )

    /** u = x / 屏宽，v = y / 屏宽（保持等比，椭圆才是圆的），vh = 屏高 / 屏宽 */
    fun sample(u: Float, v: Float, vh: Float, dark: Boolean, out: FloatArray) {
        val top = if (dark) BASE_D_TOP else BASE_L_TOP
        val bot = if (dark) BASE_D_BOT else BASE_L_BOT
        val t = (v / vh).coerceIn(0f, 1f)
        out[0] = top[0] + (bot[0] - top[0]) * t
        out[1] = top[1] + (bot[1] - top[1]) * t
        out[2] = top[2] + (bot[2] - top[2]) * t

        for (bl in BLOBS) {
            val dy = v - bl.cy * vh
            val dx = u - bl.cx
            val a = dx * bl.cos + dy * bl.sin
            val c = -dx * bl.sin + dy * bl.cos
            val q = (a / bl.rl) * (a / bl.rl) + (c / bl.rs) * (c / bl.rs)
            if (q > 6.5f) continue
            val k = (if (dark) bl.kd else bl.kl) * exp(-q)
            if (k < 0.002f) continue
            val col = if (dark) bl.dark else bl.light
            out[0] += (col[0] - out[0]) * k
            out[1] += (col[1] - out[1]) * k
            out[2] += (col[2] - out[2]) * k
        }
    }

    /** 0~1 → 0~255 */
    fun toSrgb(l: Float): Int {
        val v = l.coerceIn(0f, 1f) * 255f + 0.5f
        return if (v >= 255f) 255 else v.toInt()
    }
}

/* ---------------- 2. 圆角矩形 SDF 与法线 ---------------- */

private fun sdfRoundRect(px: Float, py: Float, w: Float, h: Float, r: Float): Float {
    val qx = abs(px - w * 0.5f) - (w * 0.5f - r)
    val qy = abs(py - h * 0.5f) - (h * 0.5f - r)
    val ax = max(qx, 0f)
    val ay = max(qy, 0f)
    return sqrt(ax * ax + ay * ay) + min(max(qx, qy), 0f) - r
}

/* ---------------- 3. 折射背景采样 ---------------- */

/**
 * 把一个元素的"身后"渲染成一张小图：
 * 每像素先算圆角矩形的 SDF，越靠边折射位移越大，采样点被推到玻璃外面，
 * 于是边缘看见的是外面的画面被压进来的一段 —— 这就是透镜边缘。
 * 三个通道用略微不同的位移量采样，得到色散。
 */
private fun buildGlassBitmap(
    wPx: Int,
    hPx: Int,
    originX: Float,
    originY: Float,
    screenW: Float,
    screenH: Float,
    cornerPx: Float,
    dark: Boolean,
    veil: FloatArray,
    veilK: Float,
    tint: FloatArray?,
    tintK: Float,
    refract: Float
): ImageBitmap {
    /* 采样网格：短边约 1/44。放大的时候双线性插值就是模糊 */
    val cell = max(3.2f, min(wPx, hPx) / 44f)
    val cols = max(6, (wPx / cell).roundToInt())
    val rows = max(5, (hPx / cell).roundToInt())
    val cw = wPx.toFloat() / cols
    val ch = hPx.toFloat() / rows

    val w = wPx.toFloat()
    val h = hPx.toFloat()
    val vh = screenH / screenW
    val band = min(w, h) * 0.22f
    val dispMax = band * 2.10f * refract
    val rimK = 0.09f

    val buf = FloatArray(cols * rows * 3)
    val c = FloatArray(3)
    val r = FloatArray(3)
    val g = FloatArray(3)
    val bl = FloatArray(3)

    for (y in 0 until rows) {
        val py = (y + 0.5f) * ch
        for (x in 0 until cols) {
            val px = (x + 0.5f) * cw
            val d = sdfRoundRect(px, py, w, h, cornerPx)
            val inside = -d
            val t = (1f - inside / band).coerceIn(0f, 1f)
            val s = t * t

            /* 法线用 SDF 的有限差分，够准且便宜 */
            val e = 1.5f
            val nx0 = sdfRoundRect(px + e, py, w, h, cornerPx) - sdfRoundRect(px - e, py, w, h, cornerPx)
            val ny0 = sdfRoundRect(px, py + e, w, h, cornerPx) - sdfRoundRect(px, py - e, w, h, cornerPx)
            val nl = sqrt(nx0 * nx0 + ny0 * ny0).takeIf { it > 1e-4f } ?: 1f
            val nx = nx0 / nl
            val ny = ny0 / nl

            val disp = dispMax * s
            val bx = originX + px + nx * disp
            val by = originY + py + ny * disp
            val bu = bx / screenW
            val bv = by / screenW

            val idx = (y * cols + x) * 3

            if (s < 0.035f) {
                /* 内部：没有分光，单次采样就够 */
                SceneField.sample(bu, bv, vh, dark, c)
                var cr = c[0]
                var cg = c[1]
                var cb = c[2]
                if (tint != null && tintK > 0f) {
                    cr += (tint[0] - cr) * tintK
                    cg += (tint[1] - cg) * tintK
                    cb += (tint[2] - cb) * tintK
                }
                val lum = cr * 0.299f + cg * 0.587f + cb * 0.114f
                cr = lum + (cr - lum) * 1.16f
                cg = lum + (cg - lum) * 1.16f
                cb = lum + (cb - lum) * 1.16f
                cr += (veil[0] - cr) * veilK
                cg += (veil[1] - cg) * veilK
                cb += (veil[2] - cb) * veilK
                buf[idx] = cr
                buf[idx + 1] = cg
                buf[idx + 2] = cb
            } else {
                /* 边缘带：三通道不同位移 → 色散；再压一层 Fresnel 内辉光 */
                val dR = disp * (1f - 0.16f * s)
                val dB = disp * (1f + 0.20f * s)
                SceneField.sample((originX + px + nx * dR) / screenW, (originY + py + ny * dR) / screenW, vh, dark, r)
                SceneField.sample(bu, bv, vh, dark, g)
                SceneField.sample((originX + px + nx * dB) / screenW, (originY + py + ny * dB) / screenW, vh, dark, bl)

                var cr = r[0]
                var cg = g[1]
                var cb = bl[2]
                if (tint != null && tintK > 0f) {
                    cr += (tint[0] - cr) * tintK
                    cg += (tint[1] - cg) * tintK
                    cb += (tint[2] - cb) * tintK
                }
                val fres = exp(-inside / (band * 0.30f))
                val glow = rimK * fres
                cr += (veil[0] - cr) * veilK + glow
                cg += (veil[1] - cg) * veilK + glow
                cb += (veil[2] - cb) * veilK + glow
                buf[idx] = cr
                buf[idx + 1] = cg
                buf[idx + 2] = cb
            }
        }
    }

    blur121(buf, cols, rows)

    val pixels = IntArray(cols * rows)
    for (i in 0 until cols * rows) {
        val i3 = i * 3
        pixels[i] = (0xFF shl 24) or
            (SceneField.toSrgb(buf[i3]) shl 16) or
            (SceneField.toSrgb(buf[i3 + 1]) shl 8) or
            SceneField.toSrgb(buf[i3 + 2])
    }
    return Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 3x3 分离式 [1,2,1] 模糊，抹掉网格台阶 */
private fun blur121(buf: FloatArray, cols: Int, rows: Int) {
    val tmp = FloatArray(buf.size)
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val i = (y * cols + x) * 3
            for (k in 0 until 3) {
                val l = if (x > 0) buf[(y * cols + x - 1) * 3 + k] else buf[i + k]
                val rr = if (x < cols - 1) buf[(y * cols + x + 1) * 3 + k] else buf[i + k]
                tmp[i + k] = (l + buf[i + k] * 2f + rr) * 0.25f
            }
        }
    }
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val i = (y * cols + x) * 3
            for (k in 0 until 3) {
                val u = if (y > 0) tmp[((y - 1) * cols + x) * 3 + k] else tmp[i + k]
                val dn = if (y < rows - 1) tmp[((y + 1) * cols + x) * 3 + k] else tmp[i + k]
                buf[i + k] = (u + tmp[i + k] * 2f + dn) * 0.25f
            }
        }
    }
}

/* ---------------- 4. 动态高光：手指 + 重力 ---------------- */

/**
 * 全局只有一个传感器监听：设备倾斜时，所有玻璃的边缘高光一起轻微移动。
 * 这是液态玻璃"活着"的关键，比任何弹跳动画都自然。
 */
@Stable
object TiltLight {
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null

    fun attach(ctx: Context) {
        if (manager != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = try {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } catch (t: Throwable) {
            null
        } ?: return
        var sx = 0f
        var sy = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                /* 手机竖直握持时重力在 y 轴上，倾斜量用它归一化 */
                val ax = e.values[0] / 9.81f
                val ay = e.values[1] / 9.81f
                val tx = (-ax).coerceIn(-1f, 1f)
                val ty = (-ay).coerceIn(-1f, 1f)
                sx += (tx - sx) * 0.25f
                sy += (ty - sy) * 0.25f
                offset = Offset(sx, sy)
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val ok = try {
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            manager = sm
            listener = l
        }
    }

    fun detach() {
        val m = manager ?: return
        listener?.let { runCatching { m.unregisterListener(it) } }
        manager = null
        listener = null
        offset = Offset.Zero
    }
}

/* ---------------- 5. 材质参数 ---------------- */

/**
 * 每一层玻璃的参数都不同：浮层最厚最亮，控件中等，内容面板几乎没有玻璃感。
 * 参数里没有"灰"，全是白色高光 —— 灰来自底色，不该来自描边。
 */
internal class LiquidStyle(
    val veilTop: Float,
    val veilBottom: Float,
    val rimTop: Float,
    val rimBottom: Float,
    val rimWidth: Dp,
    val specular: Float,
    val refract: Float,
    val elevation: Dp,
    val chroma: Float = 0.10f
)

private val STYLE_FLOATING_LIGHT = LiquidStyle(0.34f, 0.14f, 1.00f, 0.30f, 1.2.dp, 0.26f, 1.00f, 18.dp, 0.16f)
private val STYLE_FLOATING_DARK = LiquidStyle(0.075f, 0.030f, 0.50f, 0.10f, 1.1.dp, 0.18f, 1.05f, 16.dp, 0.14f)
private val STYLE_CONTROL_LIGHT = LiquidStyle(0.17f, 0.06f, 0.96f, 0.24f, 1.0.dp, 0.15f, 1.00f, 8.dp, 0.14f)
private val STYLE_CONTROL_DARK = LiquidStyle(0.060f, 0.022f, 0.42f, 0.07f, 0.9.dp, 0.12f, 1.05f, 8.dp, 0.12f)
private val STYLE_PANEL_LIGHT = LiquidStyle(0.34f, 0.24f, 0.62f, 0.12f, 0.7.dp, 0.08f, 0.60f, 0.dp)
private val STYLE_PANEL_DARK = LiquidStyle(0.055f, 0.030f, 0.22f, 0.05f, 0.7.dp, 0.08f, 0.60f, 0.dp)

internal fun styleOf(level: GlassLevel, dark: Boolean): LiquidStyle = when (level) {
    GlassLevel.Floating -> if (dark) STYLE_FLOATING_DARK else STYLE_FLOATING_LIGHT
    GlassLevel.Control -> if (dark) STYLE_CONTROL_DARK else STYLE_CONTROL_LIGHT
    GlassLevel.Panel, GlassLevel.Row -> if (dark) STYLE_PANEL_DARK else STYLE_PANEL_LIGHT
}

/* ---------------- 6. 颗粒 ---------------- */

/** 极细颗粒，铺在玻璃上模拟光学粗糙度，同时消掉渐变的色带 */
private var grainCache: ShaderBrush? = null

private fun grainBrush(): ShaderBrush? {
    grainCache?.let { return it }
    val n = 96
    val px = IntArray(n * n)
    var seed = 0x9E3779B9.toInt()
    for (i in 0 until n * n) {
        seed = seed * 1664525 + 1013904223
        val v = ((seed ushr 16) and 0xFF)
        px[i] = (if (v > 128) 0x0DFFFFFF else 0x0C000000)
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    val brush = ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
    grainCache = brush
    return brush
}

/* ---------------- 7. 玻璃表面 ---------------- */

/** 绘制阶段的小缓存：尺寸/主题/着色不变就不重新生成位图 */
private class GlassCache {
    var key: Int = Int.MIN_VALUE
    var bmp: ImageBitmap? = null
}

/**
 * 液态玻璃表面。内容画在最上层，其余全在 [Modifier.drawBehind] 里：
 *
 *   折射背景（含色散、烘焙柔光）
 *   → 薄雾（把背后压亮/压暗一点，保证文字可读）
 *   → 顶部镜面高光（跟着设备倾斜和手指走）
 *   → 内壁辉光 + 底部内阴影（玻璃的厚度）
 *   → Fresnel 细边
 *   → 颗粒
 *
 * 位图在绘制阶段生成（此时尺寸与屏幕坐标一定是准的），按尺寸做缓存，
 * 所以既不会首帧空白，也不会在滚动/动画时反复重算。
 */
/* 旧版自研液态玻璃引擎，已不再被 UI 引用（改用 PrismalAGSL），保留供以后参考。
   为避免与新封装重名，公开入口加了 Legacy 前缀，其余实现原样保留。 */
@Composable
fun LegacyLiquidSurface(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Control,
    radius: Dp = 22.dp,
    shape: Shape = RoundedCornerShape(radius),
    refract: Float? = null,
    rimScale: Float = 1f,
    rimTopAlpha: Float? = null,
    rimBottomAlpha: Float? = null,
    showRim: Boolean = true,
    shadowColor: Color? = null,
    grain: Boolean = true,
    tint: Color? = null,
    tintK: Float = 0f,
    veilScale: Float = 1f,
    interactive: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    val style = styleOf(level, dark)
    val density = LocalDensity.current
    val cornerPx = with(density) { radius.toPx() }

    /* 位置用普通数组：布局阶段写入、绘制阶段读取，不参与快照订阅 */
    val origin = remember { FloatArray(2) }
    val cache = remember { GlassCache() }

    val veil = if (dark) floatArrayOf(0.74f, 0.78f, 0.88f) else floatArrayOf(1f, 1f, 1f)
    val veilK = style.veilTop * veilScale
    val tintLin = tint?.let { floatArrayOf(it.red, it.green, it.blue) }
    val refractValue = refract ?: style.refract
    val grainShader = remember(grain) { if (grain) grainBrush() else null }

    var pressAt by remember { mutableStateOf<Offset?>(null) }
    val touch by animateFloatAsState(
        targetValue = if (pressAt != null) 1f else 0f,
        animationSpec = tween(260),
        label = "glass_touch"
    )

    Box(
        modifier = modifier
            .then(
                if (style.elevation > 0.dp) {
                    val c = shadowColor ?: if (dark) Color.Black.copy(alpha = 0.55f)
                    else Color(0xFF2A4A78).copy(alpha = 0.18f)
                    Modifier.shadow(style.elevation, shape, ambientColor = c, spotColor = c)
                } else Modifier
            )
            .clip(shape)
            .onGloballyPositioned {
                val p = it.positionInRoot()
                origin[0] = p.x
                origin[1] = p.y
            }
            .drawBehind {
                val w = size.width
                val h = size.height
                val screenW = Backdrop.w
                val screenH = Backdrop.h

                var bmp = cache.bmp
                if (screenW > 8 && screenH > 8 && w > 4f && h > 4f) {
                    val key = (((w.toInt() shl 16) xor h.toInt()) * 31 +
                        (tint?.hashCode() ?: 0)) * 31 +
                        (if (dark) 1 else 0) + level.ordinal * 7919 + (veilK * 1000).toInt()
                    if (cache.key != key) {
                        cache.bmp = buildGlassBitmap(
                            w.toInt(), h.toInt(),
                            origin[0], origin[1],
                            screenW.toFloat(), screenH.toFloat(),
                            cornerPx,
                            dark,
                            veil,
                            veilK * 0.8f,
                            tintLin,
                            tintK,
                            refractValue
                        )
                        cache.key = key
                    }
                    bmp = cache.bmp
                }

                if (bmp != null) {
                    drawImage(
                        bmp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                } else {
                    /* 没有背景信息（比如独立窗口里的弹窗）：退化成一层很淡的玻璃色 */
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.10f else 0.62f),
                                Color.White.copy(alpha = if (dark) 0.04f else 0.40f)
                            )
                        )
                    )
                }

                /* 薄雾：上薄下厚，让玻璃有一点亮度梯度 */
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = style.veilBottom * 0.55f),
                            Color.Transparent,
                            if (dark) Color.Black.copy(alpha = 0.10f) else Color(0xFF2B4A75).copy(alpha = 0.05f)
                        ),
                        startY = 0f,
                        endY = h
                    )
                )

                /* 镜面高光：一个压在上沿的扁椭圆，只把下半部分留在玻璃里，
                   所以看到的是"边缘被照亮"，而不是中间糊了一团白。
                   位置由设备倾斜决定 —— 手一歪，高光就滑一点，玻璃是"活"的。 */
                val light = TiltLight.offset
                val hx = w * 0.44f - light.x * w * 0.13f
                val hy = h * 0.06f - light.y * h * 0.05f
                val ow = w * 0.66f
                val oh = h * 0.62f
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = style.specular * 1.15f),
                            Color.White.copy(alpha = style.specular * 0.30f),
                            Color.Transparent
                        ),
                        center = Offset(hx, hy),
                        radius = max(ow, oh) * 0.52f
                    ),
                    topLeft = Offset(hx - ow * 0.5f, hy - oh * 0.5f),
                    size = Size(ow, oh)
                )

                /* 手指位置的局部高光 */
                val p = pressAt
                if (p != null && touch > 0.01f) {
                    val tr = max(w * 0.30f, h * 0.9f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.20f * touch), Color.Transparent),
                            center = p,
                            radius = tr
                        ),
                        radius = tr,
                        center = p
                    )
                }

                /* 色散边：真实玻璃边缘因为色散会分出一道冷一道暖，
                   用一条极淡的横向渐变描边暗示它，比硬画彩虹自然得多 */
                val chroma = style.chroma
                if (chroma > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color(0xFFFF9E6A).copy(alpha = chroma),
                                Color(0x00FF9E6A),
                                Color(0x004F9BFF),
                                Color(0xFF4F9BFF).copy(alpha = chroma)
                            )
                        ),
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(width = max(1.2f, min(w, h) * 0.045f))
                    )
                }

                /* 内壁辉光：越靠边越亮，玻璃才有厚度 */
                val glowW = min(w, h) * 0.22f
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = glowW)
                )

                /* 底部内阴影：玻璃有厚度，下沿会暗一档 */
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.20f else 0.07f)),
                        startY = h * 0.62f,
                        endY = h
                    )
                )

                if (grainShader != null) drawRect(brush = grainShader)
            }
            .then(
                if (showRim) {
                    Modifier.borderRim(
                        shape = shape,
                        width = style.rimWidth,
                        top = (rimTopAlpha ?: style.rimTop) * rimScale,
                        bottom = (rimBottomAlpha ?: style.rimBottom) * rimScale
                    )
                } else Modifier
            )
            .then(
                if (interactive) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val ch2 = event.changes.firstOrNull() ?: continue
                                pressAt = if (ch2.pressed && !ch2.isConsumed) ch2.position else null
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * Fresnel 边缘。用斜向渐变（左上亮、右下几乎消失）而不是上下渐变：
 * 高光椭圆同样偏左上，整块玻璃的光源方向一致，看起来才像真的被一盏灯照着。
 */
private fun Modifier.borderRim(shape: Shape, width: Dp, top: Float, bottom: Float): Modifier =
    this.border(
        width = width,
        brush = Brush.linearGradient(
            0.0f to Color.White.copy(alpha = top.coerceIn(0f, 1f)),
            0.34f to Color.White.copy(alpha = (top * 0.55f + bottom * 0.45f).coerceIn(0f, 1f)),
            0.74f to Color.White.copy(alpha = (bottom * 0.55f).coerceIn(0f, 1f)),
            1.0f to Color.White.copy(alpha = (bottom * 0.25f).coerceIn(0f, 1f)),
            start = Offset.Zero,
            end = Offset.Infinite
        ),
        shape = shape
    )

/* ---------------- 8. 背景 ---------------- */

/**
 * 页面背景 = 解析色场渲染成的小图 + 暗角 + 颗粒。
 * 和玻璃采样的是同一个函数，所以"透过玻璃看到的"和"玻璃旁边看到的"永远一致。
 */
@Composable
fun LegacyLiquidBackdrop(content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val cache = remember { GlassCache() }
    val grainShader = remember { grainBrush() }

    val ctx = LocalContext.current
    DisposableEffect(ctx) {
        TiltLight.attach(ctx)
        onDispose { TiltLight.detach() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                /* 布局阶段写入，子节点绘制时一定能读到 */
                Backdrop.w = it.size.width
                Backdrop.h = it.size.height
            }
            .drawBehind {
                val w = size.width
                val h = size.height
                val key = (w.toInt() shl 16) xor h.toInt() + (if (dark) 1 else 0) * 31
                if (cache.key != key) {
                    cache.bmp = buildBackdropBitmap(w.toInt(), h.toInt(), dark)
                    cache.key = key
                }
                cache.bmp?.let {
                    drawImage(
                        it,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(it.width, it.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                }
                /* 暗角：把注意力收到中间，也让四角的玻璃有一点对比 */
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.34f else 0.06f)),
                        center = Offset(w * 0.5f, h * 0.42f),
                        radius = size.maxDimension * 0.85f
                    )
                )
                if (grainShader != null) drawRect(brush = grainShader)
            }
    ) {
        content()
    }
}

internal fun buildBackdropBitmap(wPx: Int, hPx: Int, dark: Boolean): ImageBitmap {
    val cols = 168
    val rows = max(24, (cols * hPx / wPx.toFloat()).roundToInt())
    val vh = hPx / wPx.toFloat()
    val buf = FloatArray(cols * rows * 3)
    val c = FloatArray(3)
    for (y in 0 until rows) {
        val v = (y + 0.5f) / rows * vh
        for (x in 0 until cols) {
            val u = (x + 0.5f) / cols
            SceneField.sample(u, v, vh, dark, c)
            val i = (y * cols + x) * 3
            buf[i] = c[0]
            buf[i + 1] = c[1]
            buf[i + 2] = c[2]
        }
    }
    val pixels = IntArray(cols * rows)
    for (i in 0 until cols * rows) {
        val i3 = i * 3
        pixels[i] = (0xFF shl 24) or
            (SceneField.toSrgb(buf[i3]) shl 16) or
            (SceneField.toSrgb(buf[i3 + 1]) shl 8) or
            SceneField.toSrgb(buf[i3 + 2])
    }
    return Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}
