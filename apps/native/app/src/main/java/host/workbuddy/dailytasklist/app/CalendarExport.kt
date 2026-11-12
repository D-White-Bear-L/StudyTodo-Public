package host.workbuddy.dailytasklist.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/* ---------------- 日历图片导出 ---------------- */

/** 用相对亮度判断一个颜色是「深色」还是「浅色」，据此决定上面放白字还是深字 */
private fun isDarkColor(c: Int): Boolean {
    val r = AColor.red(c)
    val g = AColor.green(c)
    val b = AColor.blue(c)
    /* W3C 相对亮度近似（感知亮度），阈值 0.5 处切换 */
    val lum = 0.299f * r + 0.587f * g + 0.114f * b
    return lum < 128f
}

/**
 * 把某个月的日历 + 完成度渲染成一张 PNG。
 * 用原生 Canvas 手绘，尺寸固定 1080 宽，保证任何设备导出的图都一样。
 */
fun buildCalendarBitmap(store: AppStore, year: Int, month: Int): Bitmap {
    val w = 1080
    val pad = 56f
    val titleH = 190f
    val weekH = 74f
    val footerH = 170f

    val lead = Dates.firstWeekday(year, month)
    val total = Dates.daysInMonth(year, month)
    val rows = ((lead + total) + 6) / 7
    val cell = (w - pad * 2) / 7f
    val gridH = cell * rows
    val h = (pad + titleH + weekH + gridH + footerH + pad).toInt()

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)

    /* 背景：和 App 一致的浅蓝灰渐变 */
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            AColor.parseColor("#E8EDF3"), AColor.parseColor("#DDE5EE"),
            Shader.TileMode.CLAMP
        )
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

    /* 白色卡片 */
    val card = RectF(24f, 24f, w - 24f, h - 24f)
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE }
    c.drawRoundRect(card, 44f, 44f, cardPaint)

    val theme = AColor.rgb(
        ((store.settings.theme shr 16) and 0xFF).toInt(),
        ((store.settings.theme shr 8) and 0xFF).toInt(),
        (store.settings.theme and 0xFF).toInt()
    )

    /* 标题 */
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.parseColor("#1A1D21")
        textSize = 62f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    c.drawText("${year}年${month + 1}月", w / 2f, pad + 84f, titlePaint)

    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.parseColor("#8A94A3")
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    /* 本月统计 */
    var monthTotal = 0
    var monthDone = 0
    for (d in 1..total) {
        val (t, done, _) = store.progressOf(Dates.ymd(year, month, d))
        monthTotal += t
        monthDone += done
    }
    val rate = if (monthTotal == 0) 0 else monthDone * 100 / monthTotal
    c.drawText("完成 $monthDone / $monthTotal 项 · 完成率 $rate%", w / 2f, pad + 136f, subPaint)

    /* 星期表头 */
    val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.parseColor("#8A94A3")
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val gridTop = pad + titleH + weekH
    Dates.WEEK_CN.forEachIndexed { i, wd ->
        val cx = pad + cell * i + cell / 2f
        c.drawText(wd, cx, gridTop - 22f, weekPaint)
    }

    /* 日期网格 */
    val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val today = Dates.today()

    for (r in 0 until rows) {
        for (col in 0 until 7) {
            val idx = r * 7 + col
            val dayNum = idx - lead + 1
            if (dayNum !in 1..total) continue

            val ds = Dates.ymd(year, month, dayNum)
            val (t, _, dayRate) = store.progressOf(ds)
            val has = t > 0

            val left = pad + cell * col + cell * 0.10f
            val top = gridTop + cell * r + cell * 0.10f
            val size = cell * 0.80f
            val box = RectF(left, top, left + size, top + size)

            if (has && store.settings.showCompletion) {
                /* 主题色与白色混色：有任务就有底色，0% 也不会整格消失；
                   完成度越高颜色越浓（0.20 → 0.60） */
                val f = 0.20f + 0.40f * dayRate
                val mixed = AColor.rgb(
                    (255 + (AColor.red(theme) - 255) * f).toInt().coerceIn(0, 255),
                    (255 + (AColor.green(theme) - 255) * f).toInt().coerceIn(0, 255),
                    (255 + (AColor.blue(theme) - 255) * f).toInt().coerceIn(0, 255)
                )
                cellPaint.color = mixed
                c.drawRoundRect(box, 20f, 20f, cellPaint)

                /* 底部完成度条：颜色深浅之外给一个能直接读的刻度 */
                val barInset = size * 0.10f
                val barH = size * 0.05f
                val barTop = box.bottom - barH - size * 0.07f
                val barLeft = box.left + barInset
                val barRight = box.right - barInset
                cellPaint.color = AColor.argb(36, 30, 40, 60)
                c.drawRoundRect(
                    RectF(barLeft, barTop, barRight, barTop + barH),
                    barH / 2, barH / 2, cellPaint
                )
                if (dayRate > 0f) {
                    cellPaint.color = theme
                    c.drawRoundRect(
                        RectF(barLeft, barTop, barLeft + (barRight - barLeft) * dayRate, barTop + barH),
                        barH / 2, barH / 2, cellPaint
                    )
                }
                /* 用色块的实际亮度决定文字颜色，避免「浅色块上放白字」看不清 */
                dayPaint.color = if (isDarkColor(mixed)) AColor.WHITE else AColor.parseColor("#2C3036")
            } else {
                dayPaint.color = AColor.parseColor("#2C3036")
            }

            /* 今天：右上角一个小圆点，不再把整格描一圈主题色（会和完成度色块撞色） */
            if (ds == today) {
                val r = size * 0.075f
                val cx = box.right - r - size * 0.02f
                val cy = box.top + r + size * 0.02f
                cellPaint.color = AColor.WHITE
                c.drawCircle(cx, cy, r + 3f, cellPaint)
                cellPaint.color = theme
                c.drawCircle(cx, cy, r, cellPaint)
            }

            val dayBaseline = box.centerY() - 10f
            c.drawText("$dayNum", box.centerX(), dayBaseline, dayPaint)

            /* 完成度百分比：有任务的日子都显示 */
            if (has) {
                val ratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 22f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    color = dayPaint.color
                }
                val pct = (dayRate * 100).toInt()
                c.drawText("$pct%", box.centerX(), dayBaseline + 30f, ratePaint)
            }
        }
    }

    /* 底部说明 */
    val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.parseColor("#A8B0BC")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    c.drawText(
        "颜色越浓、底部横条越长，表示当天完成度越高 · 每日任务清单",
        w / 2f,
        h - pad - 44f,
        footPaint
    )

    return bmp
}

/** 保存到系统相册；API 29+ 不需要权限 */
fun saveBitmapToGallery(ctx: Context, bitmap: Bitmap, name: String): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/每日任务清单")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    true
}.getOrDefault(false)

/** 分享：写到缓存目录再用 FileProvider 暴露 */
fun shareBitmap(ctx: Context, bitmap: Bitmap, name: String) {
    runCatching {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享日历").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/* ---------------- 导出弹窗 ---------------- */

@Composable
fun CalendarExportDialog(
    store: AppStore,
    year: Int,
    month: Int,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val name = "calendar-${year}-${String.format(Locale.US, "%02d", month + 1)}"

    val bitmap = remember(year, month, store.settings.showCompletion, store.settings.theme, store.tasks) {
        runCatching { buildCalendarBitmap(store, year, month) }.getOrNull()
    }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val bmp = bitmap
            if (bmp != null && saveBitmapToGallery(ctx, bmp, name)) toast(ctx, "已保存到相册")
            else toast(ctx, "保存失败")
        } else {
            toast(ctx, "没有存储权限，无法保存")
        }
    }

    SheetDialog(onDismiss) {
        CalendarExportContent(
            year = year,
            month = month,
            bitmap = bitmap,
            onDismiss = onDismiss,
            onShare = { bitmap?.let { shareBitmap(ctx, it, name) } },
            onSave = {
                val bmp = bitmap ?: return@CalendarExportContent
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (saveBitmapToGallery(ctx, bmp, name)) toast(ctx, "已保存到相册")
                    else toast(ctx, "保存失败")
                } else {
                    permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        )
    }
}

/** 导出弹窗内容单独抽出来，方便离屏截图预览 */
@Composable
fun CalendarExportContent(
    year: Int,
    month: Int,
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    Text("导出日历", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
    Spacer(Modifier.height(4.dp))
    Text(
        "生成 ${year}年${month + 1}月 的日历图片，含每天完成度",
        fontSize = 12.sp,
        color = TextMuted
    )

    Spacer(Modifier.height(14.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0F1A1D21))
            .border(1.dp, Color(0x141A1D21), RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "日历预览",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Text(
                "生成失败",
                fontSize = 13.sp,
                color = TextMuted,
                modifier = Modifier.padding(40.dp)
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("关闭", Modifier.weight(1f), onClick = onDismiss)
        PrimaryButton("分享", Modifier.weight(1.1f), AccentGreen, onClick = onShare)
        PrimaryButton("保存到相册", Modifier.weight(1.5f), AccentBlue, onClick = onSave)
    }
}
