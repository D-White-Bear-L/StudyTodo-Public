package host.workbuddy.dailytasklist.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 离屏截图：没有真机 / 模拟器时，在 JVM 里用 Robolectric 的 layoutlib 真实渲染 Compose，
 * 把每个页面导出成 PNG，方便用眼睛检查排版。
 *
 * 运行： ./gradlew.bat :app:testDebugUnitTest --tests "*ScreenshotTest*"
 * 产物： app/build/reports/screenshots 目录下的 PNG
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val outDir = File("build/reports/screenshots").apply { mkdirs() }

    private fun store(): AppStore = AppStore(RuntimeEnvironment.getApplication())

    /*
     * Robolectric 的 layoutlib 无法编译 PrismalAGSL 的折射着色器（会抛
     * "cannot swizzle value of type 'shader'"），所以这里强制关闭 AGSL 渲染，
     * 让玻璃走 Legacy 雾面降级，截图只用来核对排版与布局不崩。
     * 真机（Android 13+）会自动走 AGSL 折射，效果以真机为准。
     */
    init {
        disableAgslForTesting()
    }

    /** 把一段 Composable 塞进 Activity，走完 measure/layout，再用软件画布导成 PNG */
    private fun shoot(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        /*
         * PrismalAGSL 的 AGSL 着色器（assets 下的 agsl 目录）在真机上由 App Startup
         * 的 PrismalAgslInitializer 在启动时 bind。Robolectric 不会自动跑 App Startup，
         * 这里用反射手动 bind 一次，等价于真机的初始化，否则玻璃绘制会抛
         * "Prismal AGSL assets are not initialized"。
         */
        PrismalAgslBootstrap.bind(activity.applicationContext.assets)

        activity.setContent {
            GlassBackground {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }

        /* 让 Compose 把首帧排完 */
        repeat(4) { shadowOf(Looper.getMainLooper()).idle() }

        val decor = activity.window.decorView
        if (decor.width == 0 || decor.height == 0) {
            val dm = activity.resources.displayMetrics
            val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                dm.widthPixels, android.view.View.MeasureSpec.EXACTLY
            )
            val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                dm.heightPixels, android.view.View.MeasureSpec.EXACTLY
            )
            decor.measure(wSpec, hSpec)
            decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
            repeat(2) { shadowOf(Looper.getMainLooper()).idle() }
        }

        val w = decor.width.coerceAtLeast(1)
        val h = decor.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))

        val out = File(outDir, "$name.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("[shot] ${out.absolutePath}  ${w}x$h")

        activity.finish()
    }

    private fun noop() {}
    @Composable private fun AccentOrangeDeep() = Color(0xFFB46500)

    /** 模拟 SheetDialog 的外观，用来预览弹窗内容（Dialog 本身不在截图范围内） */
    @Composable
    private fun DialogChrome(content: @Composable () -> Unit) {
        val night = androidx.compose.foundation.isSystemInDarkTheme()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (night) Color(0xFF0B101A) else Color(0xFFDDE5EE))
                .padding(20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (night) Color(0xFF171F2C) else Color(0xFFF7F9FB))
                    .padding(18.dp)
            ) {
                content()
            }
        }
    }

    @Test
    fun shot_edit_many_subjects() {
        val s = store()
        /* 模拟用户自己加了很多标签，验证标签云换行是否还整齐 */
        s.addSubject("数据结构", 0xFF00B8D9)
        s.addSubject("操作系统", 0xFF34C759)
        s.addSubject("计算机网络", 0xFF639922)
        s.addSubject("组成原理", 0xFFFF9500)
        s.addSubject("真题", 0xFFAF52DE)
        shoot("09-edit-many-subjects") {
            EditScreen(
                store = s,
                date = Dates.today(),
                taskId = null,
                onBack = ::noop,
                onSaved = ::noop
            )
        }
    }

    @Test
    fun shot_subject_manage() {
        val s = store()
        shoot("07-subject-manage") {
            DialogChrome {
                SubjectManageContent(store = s, onDismiss = {})
            }
        }
    }

    @Test
    fun shot_subject_editor() {
        shoot("08-subject-editor") {
            DialogChrome {
                SubjectEditor(initial = null, onCancel = {}, onSave = { _, _ -> })
            }
        }
    }

    @Test
    fun shot_list() {
        val s = store()
        shoot("01-list") {
            ListScreen(
                store = s,
                date = Dates.today(),
                onDateChange = {},
                filter = "all",
                onFilterChange = {},
                sortMode = "manual",
                selectMode = false,
                selected = emptySet(),
                onToggleSelect = {},
                onToggleTask = {},
                onOpenTask = {},
                onStudy = {},
                onCalendar = {},
                onAdd = {},
                onMigrate = {},
                onExportSummary = {}, onExportPlan = {},
                onMultiToggle = {},
                onBatchDone = {},
                onBatchDelete = {},
                onSelectAll = {},
                onExitSelect = {},
                onSort = {},
                onMoveTask = { _, _ -> }
            )
        }
    }

    @Test
    fun shot_list_select_mode() {
        val s = store()
        val ids = s.tasksOf(Dates.today()).map { it.id }.toSet()
        shoot("02-list-select") {
            ListScreen(
                store = s,
                date = Dates.today(),
                onDateChange = {},
                filter = "all",
                onFilterChange = {},
                sortMode = "manual",
                selectMode = true,
                selected = ids,
                onToggleSelect = {},
                onToggleTask = {},
                onOpenTask = {},
                onStudy = {},
                onCalendar = {},
                onAdd = {},
                onMigrate = {},
                onExportSummary = {}, onExportPlan = {},
                onMultiToggle = {},
                onBatchDone = {},
                onBatchDelete = {},
                onSelectAll = {},
                onExitSelect = {},
                onSort = {},
                onMoveTask = { _, _ -> }
            )
        }
    }

    /** 非今天的日期：检查「回到今天」按钮出现后的排版 */
    @Test
    fun shot_list_other_day() {
        val s = store()
        val d = Dates.addDays(Dates.today(), -1)
        shoot("06-list-other-day") {
            ListScreen(
                store = s,
                date = d,
                onDateChange = {},
                filter = "all",
                onFilterChange = {},
                sortMode = "manual",
                selectMode = false,
                selected = emptySet(),
                onToggleSelect = {},
                onToggleTask = {},
                onOpenTask = {},
                onStudy = {},
                onCalendar = {},
                onAdd = {},
                onMigrate = {},
                onExportSummary = {}, onExportPlan = {},
                onMultiToggle = {},
                onBatchDone = {},
                onBatchDelete = {},
                onSelectAll = {},
                onExitSelect = {},
                onSort = {},
                onMoveTask = { _, _ -> }
            )
        }
    }

    @Test
    fun shot_study() {
        val s = store()
        shoot("03-study") {
            StudyScreen(store = s, date = Dates.today(), onBack = ::noop)
        }
    }

    /** 设置页顶部的「数据备份」区块 */
    @Test
    fun shot_settings_backup() {
        val s = store()
        val d = Dates.today()
        repeat(6) { i -> s.addTask(d, "任务 ${i + 1}", "math", "", i < 3) }
        shoot("30-settings-backup") {
            GlassSettingsScreen(
                store = s,
                tuning = rememberGlassTuning(),
                onBack = ::noop,
                onReset = {}
            )
        }
    }

    /** 导入确认弹窗：统计 + 合并/覆盖/取消 三个按钮 */
    @Test
    fun shot_backup_import_sheet() {
        val data = BackupData(
            tasks = mapOf(
                "2026-08-26" to listOf(Task("1", "q", "uncat", "", true, 1)),
                "2026-11-01" to listOf(
                    Task("2", "12套卷", "math", "", false, 2),
                    Task("3", "作文默写", "english", "", false, 3)
                )
            ),
            study = emptyMap(),
            checkins = setOf("2026-08-26"),
            subjects = null,
            theme = null,
            sort = null,
            source = "test"
        )
        shoot("31-backup-import") {
            BackupImportDialog(data = data, onDismiss = ::noop, onReplace = {}, onMerge = {})
        }
    }

    @Test
    fun shot_calendar() {
        val s = store()
        /* 造本月 1~20 号不同完成度的数据，专门用来核对「完成度色块是否看得见」 */
        val today = Dates.today()
        val y = Dates.year(today)
        val m = Dates.month(today)
        for (d in 1..20) {
            val ds = Dates.ymd(y, m, d)
            repeat(4) { k -> s.addTask(ds, "任务 ${k + 1}", "math", "", k < (d % 5)) }
        }
        shoot("04-calendar") {
            CalendarScreen(
                store = s,
                selectedDate = today,
                onPick = {},
                onBack = ::noop
            )
        }
    }

    /**
     * 日历导出图：这条路不经过 Compose 玻璃渲染（纯 Canvas），
     * 所以它能真实反映「完成度色块 + 底部进度条 + 今天的点」画出来是什么样。
     */
    @Test
    fun shot_calendar_export_bitmap() {
        val s = store()
        val y = Dates.year(Dates.today())
        val m = Dates.month(Dates.today())
        val today = Dates.day(Dates.today())
        /* 造 0% / 25% / 50% / 75% / 100% 都有的一天，外加今天 */
        for (d in 1..today) {
            val ds = Dates.ymd(y, m, d)
            val total = 4
            val done = when (d % 5) {
                0 -> 4; 1 -> 0; 2 -> 1; 3 -> 2; else -> 3
            }
            repeat(total) { k -> s.addTask(ds, "任务 ${k + 1}", "math", "", k < done) }
        }
        val bmp = buildCalendarBitmap(s, y, m)
        val f = File(outDir, "40-calendar-export.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("calendar export -> " + f.absolutePath)
    }

    @Test
    fun shot_edit() {
        val s = store()
        shoot("05-edit") {
            EditScreen(
                store = s,
                date = Dates.today(),
                taskId = null,
                onBack = ::noop,
                onSaved = ::noop
            )
        }
    }

    /** 色盘取色器 */
    @Test
    fun shot_color_picker() {
        shoot("10-color-picker") {
            DialogChrome {
                ColorPickerContent(
                    title = "自定义标签颜色",
                    initial = 0xFFA855F7,
                    onDismiss = {},
                    onConfirm = {}
                )
            }
        }
    }

    /** 日历图片导出弹窗 */
    @Test
    fun shot_calendar_export() {
        val s = store()
        /* 造点数据：本月 1~14 号，每天完成度不同，方便看渐变效果 */
        val today = Dates.today()
        val y = Dates.year(today)
        val m = Dates.month(today)
        for (d in 1..14) {
            val ds = Dates.ymd(y, m, d)
            repeat(4) { k -> s.addTask(ds, "任务 ${k + 1}", "math", "", k < (d % 5)) }
        }
        val bmp = buildCalendarBitmap(s, y, m)
        /* 顺手把导出的原图存一份，直接看成品 */
        val raw = File(outDir, "11-calendar-export-image.png")
        FileOutputStream(raw).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("[shot] ${raw.absolutePath}  ${bmp.width}x${bmp.height}")

        shoot("12-calendar-export-dialog") {
            DialogChrome {
                CalendarExportContent(
                    year = y,
                    month = m,
                    bitmap = bmp,
                    onDismiss = {},
                    onShare = {},
                    onSave = {}
                )
            }
        }
    }

    /** 手动排序模式：多条任务时看抓手提示（用更长的视口，让任务列表一次露出来） */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun shot_list_manual_many() {
        val s = store()
        val d = Dates.today()
        listOf("英语真题阅读 2 篇 + 复盘" to "english", "数学真题套卷做 + 订正" to "math",
            "政治分析题背诵" to "politics", "408 数据结构树与图" to "cs408",
            "专业课论文提纲" to "major", "背单词 100 个" to "english").forEach {
            s.addTask(d, it.first, it.second, "", false)
        }
        shoot("13-list-manual-many") {
            ListScreen(
                store = s,
                date = d,
                onDateChange = {},
                filter = "all",
                onFilterChange = {},
                sortMode = "manual",
                selectMode = false,
                selected = emptySet(),
                onToggleSelect = {},
                onToggleTask = {},
                onOpenTask = {},
                onStudy = {},
                onCalendar = {},
                onAdd = {},
                onMigrate = {},
                onExportSummary = {}, onExportPlan = {},
                onMultiToggle = {},
                onBatchDone = {},
                onBatchDelete = {},
                onSelectAll = {},
                onExitSelect = {},
                onSort = {},
                onMoveTask = { _, _ -> }
            )
        }
    }

    /** 造一份"内容饱满"的场景：混合完成状态、备注、学习记录、打卡 */
    private fun seedFull(s: AppStore): String {
        val d = Dates.today()
        listOf(
            Triple("英语真题阅读 2 篇 + 逐句复盘", "english", "做完把错题和长难句抄到本子上"),
            Triple("数学真题套卷（第 3 套）", "math", "计时 180 分钟，做完立刻订正"),
            Triple("408 数据结构：树与图", "cs408", ""),
            Triple("政治分析题背诵 3 道", "politics", "先默写框架再看答案"),
            Triple("专业课论文提纲", "major", "")
        ).forEachIndexed { i, (title, subject, note) ->
            s.addTask(d, title, subject, note, i == 2)
        }
        s.setStudy(d, Study(hours = "8.5", start = "07:40", place = "市图书馆 3 楼", review = "21:30"))
        return d
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun shot_list_full_light() {
        val s = store()
        val d = seedFull(s)
        shoot("20-list-full-light") {
            ListScreen(
                store = s, date = d, onDateChange = {}, filter = "all", onFilterChange = {},
                sortMode = "manual", selectMode = false, selected = emptySet(),
                onToggleSelect = {}, onToggleTask = {}, onOpenTask = {}, onStudy = {},
                onCalendar = {}, onAdd = {}, onMigrate = {}, onExportSummary = {}, onExportPlan = {}, onMultiToggle = {},
                onBatchDone = {}, onBatchDelete = {}, onSelectAll = {}, onExitSelect = {},
                onSort = {}, onMoveTask = { _, _ -> }
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun shot_list_full_dark() {
        val s = store()
        val d = seedFull(s)
        shoot("21-list-full-dark") {
            ListScreen(
                store = s, date = d, onDateChange = {}, filter = "all", onFilterChange = {},
                sortMode = "manual", selectMode = false, selected = emptySet(),
                onToggleSelect = {}, onToggleTask = {}, onOpenTask = {}, onStudy = {},
                onCalendar = {}, onAdd = {}, onMigrate = {}, onExportSummary = {}, onExportPlan = {}, onMultiToggle = {},
                onBatchDone = {}, onBatchDelete = {}, onSelectAll = {}, onExitSelect = {},
                onSort = {}, onMoveTask = { _, _ -> }
            )
        }
    }

    /** 空清单 + 非今天：检查「回到今天」出现时的排版 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun shot_list_other_day_dark() {
        val s = store()
        val d = Dates.addDays(Dates.today(), -2)
        shoot("22-list-other-day-dark") {
            ListScreen(
                store = s, date = d, onDateChange = {}, filter = "all", onFilterChange = {},
                sortMode = "manual", selectMode = false, selected = emptySet(),
                onToggleSelect = {}, onToggleTask = {}, onOpenTask = {}, onStudy = {},
                onCalendar = {}, onAdd = {}, onMigrate = {}, onExportSummary = {}, onExportPlan = {}, onMultiToggle = {},
                onBatchDone = {}, onBatchDelete = {}, onSelectAll = {}, onExitSelect = {},
                onSort = {}, onMoveTask = { _, _ -> }
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun shot_list_full_long_light() {
        val s = store()
        val d = seedFull(s)
        shoot("23-list-full-long-light") {
            ListScreen(
                store = s, date = d, onDateChange = {}, filter = "all", onFilterChange = {},
                sortMode = "manual", selectMode = false, selected = emptySet(),
                onToggleSelect = {}, onToggleTask = {}, onOpenTask = {}, onStudy = {},
                onCalendar = {}, onAdd = {}, onMigrate = {}, onExportSummary = {}, onExportPlan = {}, onMultiToggle = {},
                onBatchDone = {}, onBatchDelete = {}, onSelectAll = {}, onExitSelect = {},
                onSort = {}, onMoveTask = { _, _ -> }
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-night-xxhdpi")
    fun shot_list_full_long_dark() {
        val s = store()
        val d = seedFull(s)
        shoot("24-list-full-long-dark") {
            ListScreen(
                store = s, date = d, onDateChange = {}, filter = "all", onFilterChange = {},
                sortMode = "manual", selectMode = false, selected = emptySet(),
                onToggleSelect = {}, onToggleTask = {}, onOpenTask = {}, onStudy = {},
                onCalendar = {}, onAdd = {}, onMigrate = {}, onExportSummary = {}, onExportPlan = {}, onMultiToggle = {},
                onBatchDone = {}, onBatchDelete = {}, onSelectAll = {}, onExitSelect = {},
                onSort = {}, onMoveTask = { _, _ -> }
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun shot_study_dark() {
        val s = store()
        s.setStudy(Dates.today(), Study(hours = "8", start = "08:00", place = "图书馆", review = "22:00"))
        shoot("25-study-dark") {
            StudyScreen(store = s, date = Dates.today(), onBack = ::noop)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun shot_edit_dark() {
        val s = store()
        shoot("26-edit-dark") {
            EditScreen(store = s, date = Dates.today(), taskId = null, onBack = ::noop, onSaved = ::noop)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun shot_calendar_dark() {
        val s = store()
        val today = Dates.today()
        val y = Dates.year(today)
        val m = Dates.month(today)
        for (d in 1..20) {
            val ds = Dates.ymd(y, m, d)
            repeat(4) { k -> s.addTask(ds, "任务 ${k + 1}", "math", "", k < (d % 5)) }
        }
        shoot("27-calendar-dark") {
            CalendarScreen(store = s, selectedDate = today, onPick = {}, onBack = ::noop)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun shot_subject_manage_dark() {
        val s = store()
        shoot("28-subject-manage-dark") {
            DialogChrome {
                SubjectManageContent(store = s, onDismiss = {})
            }
        }
    }

    /** 滚动后的顶栏：浮出一层会折射的玻璃条 */
    @Test
    fun shot_topbar_scrolled() {
        val s = store()
        val d = Dates.today()
        repeat(6) { s.addTask(d, "任务 ${it + 1}", "math", "", it < 2) }
        shoot("29-topbar-scrolled") {
            Column {
                AppTopBar(title = "每日任务清单", scrollProgress = 1f) {
                    Pill("手动排序", onClick = ::noop)
                    Spacer(Modifier.width(6.dp))
                    Pill("导出", onClick = ::noop)
                    Spacer(Modifier.width(6.dp))
                    Pill("多选", bg = AccentOrange, fg = AccentOrangeDeep(), onClick = ::noop)
                }
                ListScreen(
                    store = s, date = d, onDateChange = {}, filter = "all", onFilterChange = {},
                    sortMode = "manual", selectMode = false, selected = emptySet(), onToggleSelect = {},
                    onToggleTask = {}, onOpenTask = {}, onStudy = {}, onCalendar = {}, onAdd = {},
                    onMigrate = {}, onExportSummary = {}, onExportPlan = {}, onMultiToggle = {}, onBatchDone = {},
                    onBatchDelete = {}, onSelectAll = {}, onExitSelect = {}, onSort = {},
                    onMoveTask = { _, _ -> }
                )
            }
        }
    }
}

/**
 * 用反射触发 PrismalAGSL 的 AGSL 着色器初始化，等价于真机上 App Startup 做的事。
 * 库里的 PrismalAgslAssets / PrismalAgslInitializer 都是 internal，app 模块无法直接调用，
 * 只能反射进 com.styropyr0.prismal.internal 包手动 bind。
 */
private object PrismalAgslBootstrap {

    private var bound = false

    fun bind(assets: android.content.res.AssetManager) {
        if (bound) return
        try {
            val clazz = Class.forName("com.styropyr0.prismal.internal.PrismalAgslAssets")
            val instance = clazz.getField("INSTANCE").get(null)
            clazz.getMethod("bind", android.content.res.AssetManager::class.java)
                .invoke(instance, assets)
            bound = true
        } catch (t: Throwable) {
            /* 反射失败就让它按原样抛「assets 未初始化」，便于定位而不是静默吞掉 */
            t.printStackTrace()
        }
    }
}
