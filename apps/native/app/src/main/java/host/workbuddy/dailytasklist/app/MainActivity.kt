package host.workbuddy.dailytasklist.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoveUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.styropyr0.prismal.components.PrismalGlassButton
import com.styropyr0.prismal.components.LocalPrismalParentGlassLayer
import com.styropyr0.prismal.components.PrismalRulerSelector
import com.styropyr0.prismal.components.PrismalSegmentedControl
import com.styropyr0.prismal.components.PrismalGlassMenu
import com.styropyr0.prismal.components.PrismalGlassMenuItem
import com.styropyr0.prismal.components.PrismalGlassMenuDivider
import com.styropyr0.prismal.components.prismalMenuAnchor
import com.styropyr0.prismal.specular.PrismalSpecular
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------------- 页面 ---------------- */

sealed interface Screen {
    val depth: Int
    data object ListPage : Screen { override val depth = 0 }
    data class EditPage(val taskId: String?) : Screen { override val depth = 1 }
    data object StudyPage : Screen { override val depth = 0 }
    data object CalendarPage : Screen { override val depth = 0 }
    data object GlassSettingsPage : Screen { override val depth = 0 }
}

/**
 * 页面转场的缓动。
 *
 * 默认的 FastOutSlowIn 起步偏慢，Android 上看着「黏」；这条曲线前 22% 就走完
 * 一大半位移、尾巴拖得很长，是 iOS 页面切换的味道——快进、缓停。
 */
private val IosEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/* ---------------- 入口 ---------------- */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val store = remember { AppStore(applicationContext) }
            AppRoot(store)
        }
    }

    /* 从后台切回来补一次同步：锁屏期间别的设备改了东西，回来就能看到 */
    override fun onResume() {
        super.onResume()
        Sync.onForeground()
    }
}

@Composable
fun AppRoot(store: AppStore) {
    var date by rememberSaveable { mutableStateOf(Dates.today()) }
    var screen by remember { mutableStateOf<Screen>(Screen.ListPage) }
    var filter by rememberSaveable { mutableStateOf("all") }
    /* 排序方式持久化在 store 里，关掉 App 再打开还是你上次选的那种 */
    val sortMode = store.settings.sort
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    var exportTitle by remember { mutableStateOf<String?>(null) }
    var exportBody by remember { mutableStateOf("") }
    var showMigrate by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    val ctx = androidx.compose.ui.platform.LocalContext.current

    /* 全局玻璃参数：用户在设置页实时调，这里统一记住并透传给所有玻璃组件 */
    val glassTuning = rememberGlassTuning()

    /* 相册选图（换背景）启动器：拿到 URI 后落盘成文件，持久化文件路径，玻璃立即用它采样折射 */
    val wallpaperLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val saved = persistWallpaper(ctx, uri)
            store.setWallpaper(saved ?: "")
            if (saved == null) toast(ctx, "壁纸读取失败，请换一张试试")
        }
    }

    GlassBackground(wallpaperPath = store.settings.wallpaperPath) {
        CompositionLocalProvider(LocalGlassTuning provides glassTuning) {
            AppContent(
                store = store,
                glassTuning = glassTuning,
                date = date, onDateChange = { date = it },
                screen = screen, onScreenChange = { screen = it },
                filter = filter, onFilterChange = { filter = it },
                sortMode = sortMode,
                selectMode = selectMode, selected = selected,
                onToggleSelect = { id -> selected = if (selected.contains(id)) selected - id else selected + id },
                onToggleTask = { id -> store.toggleTask(date, id) },
                onSort = { mode ->
                    store.setSort(mode)
                    toast(ctx, when (mode) {
                        "undone" -> "未完成优先"
                        "subject" -> "按学科分组"
                        else -> "手动排序（长按任务可上下拖动）"
                    })
                },
                onMoveTask = { from, to -> store.moveTask(date, from, to) },
                onWallpaper = { wallpaperLauncher.launch("image/*") },
                onMigrate = { showMigrate = true },
                onExportSummary = {
                    exportTitle = "每日总结"
                    exportBody = buildSummaryText(store, date)
                },
                onExportPlan = {
                    exportTitle = "每日计划"
                    exportBody = buildPlanText(store, date)
                },
                onMultiToggle = { selectMode = !selectMode; selected = emptySet() },
                onBatchDone = {
                    selected.forEach { id ->
                        val t = store.tasksOf(date).firstOrNull { it.id == id }
                        if (t != null && !t.done) store.updateTask(date, t.copy(done = true))
                    }
                    selected = emptySet(); selectMode = false
                },
                onBatchDelete = { if (selected.isNotEmpty()) confirmBatchDelete = true },
                onSelectAll = {
                    val ids = store.tasksOf(date).map { it.id }.toSet()
                    selected = if (ids.isNotEmpty() && selected.size == ids.size) emptySet() else ids
                },
                onExitSelect = { selectMode = false; selected = emptySet() }
            )
        }
    }

        /* ---- 文本导出 ---- */
        exportTitle?.let { title ->
            TextExportDialog(
                title = title,
                text = exportBody,
                subtitle = "${Dates.cn(date)} · ${store.tasksOf(date).size} 条任务",
                onDismiss = { exportTitle = null }
            )
        }

        /* ---- 迁移 ---- */
        if (showMigrate) {
            MigrateDialog(
                store = store,
                date = date,
                onDismiss = { showMigrate = false },
                onConfirm = { target, ids, keep ->
                    val n = store.migrate(date, target, ids, keep)
                    showMigrate = false
                    when {
                        n == 0 -> toast(ctx, "没有可迁移的任务")
                        keep -> toast(ctx, "已复制 $n 项到 $target，这一天保持原样")
                        else -> toast(ctx, "已迁移 $n 项到 $target")
                    }
                }
            )
        }

        /* ---- 批量删除确认 ---- */
        if (confirmBatchDelete) {
            ConfirmDialog(
                title = "删除任务",
                message = "将删除选中的 ${selected.size} 项任务，不可恢复。",
                confirmText = "删除",
                onDismiss = { confirmBatchDelete = false },
                onConfirm = {
                    selected.forEach { store.deleteTask(date, it) }
                    selected = emptySet(); selectMode = false
                    confirmBatchDelete = false
                }
            )
        }
    }

fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 页面切换容器。抽出来是为了让 GlassBackground 只负责「背景 + 玻璃参数」，
 * 页面树（含 AnimatedContent 转场）作为它的 content，玻璃参数从 CompositionLocal 透传进来。
 */
@Composable
private fun AppContent(
    store: AppStore,
    glassTuning: GlassTuning,
    date: String,
    onDateChange: (String) -> Unit,
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    sortMode: String,
    selectMode: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onToggleTask: (String) -> Unit,
    onSort: (String) -> Unit,
    onMoveTask: (from: Int, to: Int) -> Unit,
    onWallpaper: () -> Unit,
    onMigrate: () -> Unit,
    onExportSummary: () -> Unit,
    onExportPlan: () -> Unit,
    onMultiToggle: () -> Unit,
    onBatchDone: () -> Unit,
    onBatchDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onExitSelect: () -> Unit
) {
    /* 底部 Tab 只在主页面（depth == 0）显示，EditPage 覆盖式打开时不显示 */
    val showTabs = screen.depth == 0
    val tabIndex = when (screen) {
        Screen.ListPage -> 0
        Screen.CalendarPage -> 1
        Screen.StudyPage -> 2
        Screen.GlassSettingsPage -> 3
        else -> 0
    }

    /* 整个页面用 Column 包裹：内容页占满剩余空间，底部 Tab 作为布局流里的
       最后一个兄弟节点在底部占位（不是悬浮覆盖层）。
       这样页面内容的可滚动区域会精确地「到 Tab 上方为止」，滚动到底时
       最后一项天然落在 Tab 上方，不会被盖住；同时 Tab 也只在它自己那块
       区域接收触摸，不会拦截内容区的点击/滑动。

       之前用 Box + align(BottomCenter) 把 Tab 做成悬浮覆盖层：
       1) 视觉上盖住页面底部内容；
       2) 它 fillMaxWidth 的透明触摸区会拦截下面按钮/列表的点击；
       3) 页面没有为它预留滚动空间，滚到底也露不出最后一项。 */
    LibraryThemed {
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                /* 三种转场分开做。
                   之前只有一个 if/else，判断的只是「层级变深还是变浅」，
                   而四个底部 Tab 都是 depth = 0 —— 于是点 Tab 也会整页横向推过去，
                   看起来像从另一个页面压进来，这就是「跳转动画怪」的来源。
                   现在：
                     · 同层（Tab 之间）→ 只淡入淡出 + 极轻的放大，横向完全不动；
                     · 进入下级页面 → 新页从右滑入，旧页以 1/4 速度让位（视差）；
                     · 返回 → 反向，旧页退到右边。 */
                val depthDelta = targetState.depth - initialState.depth
                when {
                    depthDelta == 0 ->
                        (fadeIn(tween(240, easing = IosEase)) +
                            scaleIn(
                                tween(300, easing = IosEase),
                                initialScale = 0.985f
                            ))
                            .togetherWith(fadeOut(tween(160, easing = IosEase)))

                    depthDelta > 0 ->
                        (slideInHorizontally(tween(340, easing = IosEase)) { it } +
                            fadeIn(tween(180, easing = IosEase)))
                            .togetherWith(
                                slideOutHorizontally(tween(340, easing = IosEase)) { -it / 4 } +
                                    fadeOut(tween(240, easing = IosEase))
                            )

                    else ->
                        (slideInHorizontally(tween(340, easing = IosEase)) { -it / 4 } +
                            fadeIn(tween(240, easing = IosEase)))
                            .togetherWith(
                                slideOutHorizontally(tween(340, easing = IosEase)) { it } +
                                    fadeOut(tween(200, easing = IosEase))
                            )
                }
            },
            label = "page"
        ) { target ->
            when (target) {
                Screen.ListPage -> ListScreen(
                    store = store,
                    date = date,
                    onDateChange = onDateChange,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    sortMode = sortMode,
                    selectMode = selectMode,
                    selected = selected,
                    onToggleSelect = onToggleSelect,
                    onToggleTask = onToggleTask,
                    onOpenTask = { onScreenChange(Screen.EditPage(it)) },
                    onStudy = { onScreenChange(Screen.StudyPage) },
                    onCalendar = { onScreenChange(Screen.CalendarPage) },
                    onAdd = { onScreenChange(Screen.EditPage(null)) },
                    onMigrate = onMigrate,
                    onExportSummary = onExportSummary,
                    onExportPlan = onExportPlan,
                    onMultiToggle = onMultiToggle,
                    onBatchDone = onBatchDone,
                    onBatchDelete = onBatchDelete,
                    onSelectAll = onSelectAll,
                    onExitSelect = onExitSelect,
                    onSort = onSort,
                    onMoveTask = onMoveTask
                )

                is Screen.EditPage -> EditScreen(
                    store = store,
                    date = date,
                    taskId = target.taskId,
                    onBack = { onScreenChange(Screen.ListPage) },
                    onSaved = { onScreenChange(Screen.ListPage) }
                )

                Screen.StudyPage -> StudyScreen(
                    store = store,
                    date = date,
                    onBack = { onScreenChange(Screen.ListPage) }
                )

                Screen.CalendarPage -> CalendarScreen(
                    store = store,
                    selectedDate = date,
                    onPick = {
                        onDateChange(it)
                        onScreenChange(Screen.ListPage)
                    },
                    onBack = { onScreenChange(Screen.ListPage) }
                )

                Screen.GlassSettingsPage -> GlassSettingsScreen(
                    store = store,
                    tuning = glassTuning,
                    onBack = { onScreenChange(Screen.ListPage) },
                    onReset = { },
                    hasWallpaper = store.settings.wallpaperPath.isNotBlank(),
                    onPickWallpaper = onWallpaper,
                    onClearWallpaper = { store.setWallpaper("") }
                )
            }
        }

        /* 底部 Tab 导航：官方 PrismalGlassBottomTabs（液滴透镜 + 左右滑动），
           现在是 Column 布局流里的正常占位兄弟节点，只在自身区域接收交互，
           不会遮挡或拦截上方内容。

           ⚠️ 这里必须用 AnimatedVisibility，不能再写 `if (showTabs)`：
           进「新增任务 / 编辑任务」时 showTabs 变 false，Tab 栏会**在同一帧**
           从布局里消失，AnimatedContent 的 weight(1f) 立刻多出 80dp 高，
           于是正在滑进来的新页面一边横移一边被纵向拉高一截，底部「取消 / 保存」
           凭空往下掉——这就是「新建页面的跳转怪怪的」的真正原因。
           用 shrinkVertically 之后，Tab 栏的高度在 300ms 内平滑收成 0，
           页面高度和转场同步变化，视觉上就是「新页面把它顶掉了」。 */
        AnimatedVisibility(
            visible = showTabs,
            enter = expandVertically(
                animationSpec = tween(320, easing = IosEase),
                expandFrom = Alignment.Bottom
            ) + fadeIn(tween(220, easing = IosEase)),
            exit = shrinkVertically(
                animationSpec = tween(320, easing = IosEase),
                shrinkTowards = Alignment.Bottom
            ) + fadeOut(tween(140, easing = IosEase))
        ) {
            AppBottomTabs(
                selectedIndex = tabIndex,
                onTabSelected = { idx ->
                    onScreenChange(
                        when (idx) {
                            0 -> Screen.ListPage
                            1 -> Screen.CalendarPage
                            2 -> Screen.StudyPage
                            else -> Screen.GlassSettingsPage
                        }
                    )
                }
            )
        }
    }
    }
}

/**
 * 把用户从相册选中的图片复制到应用私有目录，返回一个长期可用的绝对文件路径。
 * 相册返回的 content:// URI 权限是临时的，直接持久化 URI 会在重启后失效，
 * 所以这里落盘到 filesDir/wallpaper.jpg，保证背景图跨启动稳定。
 */
private fun persistWallpaper(ctx: android.content.Context, uri: Uri): String? {
    return runCatching {
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        /* 每次都用带时间戳的新文件名：如果固定写同一个 wallpaper.jpg，
           GlassBackground 里 remember(wallpaperPath) 的 key 不会变，缓存不失效，
           导致「换壁纸」第二次起图片不刷新。文件名带唯一后缀就能强制重新解码。 */
        val outFile = java.io.File(ctx.filesDir, "wallpaper-${System.currentTimeMillis()}.jpg")
        input.use { ins ->
            java.io.FileOutputStream(outFile).use { outs ->
                ins.copyTo(outs)
            }
        }
        /* 顺手清理旧壁纸文件，避免 filesDir 越攒越多 */
        ctx.filesDir.listFiles { f -> f.name.startsWith("wallpaper-") && f.name != outFile.name }
            ?.forEach { it.delete() }
        outFile.absolutePath
    }.getOrNull()
}

/* ---------------- 清单页 ---------------- */

@Composable
fun ListScreen(
    store: AppStore,
    date: String,
    onDateChange: (String) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    sortMode: String,
    selectMode: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onToggleTask: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onStudy: () -> Unit,
    onCalendar: () -> Unit,
    onAdd: () -> Unit,
    onMigrate: () -> Unit,
    onExportSummary: () -> Unit,
    onExportPlan: () -> Unit,
    onMultiToggle: () -> Unit,
    onBatchDone: () -> Unit,
    onBatchDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onExitSelect: () -> Unit,
    onSort: (String) -> Unit,
    onMoveTask: (from: Int, to: Int) -> Unit
) {
    val all = remember(store.tasks, store.subjects, date, sortMode) {
        val base = store.tasksOf(date)
        when (sortMode) {
            "undone" -> base.sortedWith(compareBy({ it.done }, { it.createdAt }))
            "subject" -> base.sortedWith(
                compareBy(
                    { task ->
                        store.subjects.indexOfFirst { s -> s.key == task.subject }
                            .let { i -> if (i < 0) 99 else i }
                    },
                    { it.createdAt }
                )
            )
            /* manual：直接用存储顺序，也就是用户自己拖出来的顺序 */
            else -> base
        }
    }
    val (total, done, rate) = store.progressOf(date)
    val isToday = date == Dates.today()
    val st = store.studyOf(date)

    val visible = when (filter) {
        "undone" -> all.filterNot { it.done }
        "done" -> all.filter { it.done }
        else -> all
    }

    val listState = rememberLazyListState()
    /*
     * 任务行前面有几个 item：概览卡 / 日期带 / 状态分段 / 分组标题。
     * 头部被拆成四段之后这里是 4（多选时只留 1 条提示）。
     */
    val headerCount = if (selectMode) 1 else 4
    /* 只有「手动排序 + 非多选 + 未筛选」时才能拖动，否则拖完顺序和视图对不上 */
    val canDrag = sortMode == "manual" && !selectMode && filter == "all"

    var dragAbs by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }

    /* 排序下拉菜单：用官方 PrismalGlassMenu（液滴展开的面板） */
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sortAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    /* 「更多」菜单：收纳导出、迁移等次要功能，让顶栏精简，导出入口也更好找 */
    var moreMenuOpen by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    /*
     * 拖动位移处理。
     * 手势挂在任务行自己身上（挂在列表外层会被 LazyColumn 的滚动在 Main 阶段先吃掉事件），
     * 而任务行会因为换位被移动到别的位置，所以行内的下标 / 回调都要用 rememberUpdatedState
     * 取最新值，pointerInput 才不会拿着过期闭包算错。
     */
    val handleDrag: (Float) -> Unit = { dy ->
        if (dragAbs >= 0) {
            dragDy += dy
            val info = listState.layoutInfo
            val cur = info.visibleItemsInfo.firstOrNull { it.index == dragAbs }
            if (cur != null) {
                /* 被拖行的中心点，落到谁身上就跟谁换位 */
                val center = cur.offset + dragDy + cur.size / 2f
                val target = info.visibleItemsInfo.firstOrNull { vi ->
                    vi.index >= headerCount &&
                        center >= vi.offset &&
                        center < vi.offset + vi.size
                }
                if (target != null && target.index != dragAbs) {
                    val from = dragAbs - headerCount
                    val to = target.index - headerCount
                    if (from in visible.indices && to in visible.indices) {
                        onMoveTask(from, to)
                        /* 换位后修正位移，让被拖的行继续停在手指下 */
                        dragDy += cur.offset - target.offset
                        dragAbs = target.index
                    }
                }
            }
        }
    }
    val currentHandleDrag by rememberUpdatedState(handleDrag)

    /*
     * 拖到列表上下边缘时自动滚动，否则任务一多就拖不到屏幕外的位置。
     * 滚动后要同步补偿 dragDy，被拖的行才会继续停在手指下面。
     */
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 78.dp.toPx() }
    LaunchedEffect(dragAbs >= 0) {
        while (dragAbs >= 0) {
            val info = listState.layoutInfo
            val cur = info.visibleItemsInfo.firstOrNull { it.index == dragAbs }
            if (cur != null) {
                val center = cur.offset + dragDy + cur.size / 2f
                val delta = when {
                    center < info.viewportStartOffset + edgePx -> -12f
                    center > info.viewportEndOffset - edgePx -> 12f
                    else -> 0f
                }
                if (delta != 0f) {
                    val scrolled = listState.scrollBy(delta)
                    dragDy += scrolled
                }
            }
            withFrameNanos { }
        }
    }

    val scrollProgress by remember {
        derivedStateOf {
            val offset = listState.firstVisibleItemScrollOffset
            val index = listState.firstVisibleItemIndex
            if (index == 0) (offset / 120f).coerceIn(0f, 1f) else 1f
        }
    }

    /* 顶栏 / 内容 / 底坞 三段式，底坞在布局流里占位，不会盖住内容 */
    Column(Modifier.fillMaxSize()) {

        /* 顶栏：静止时透明，滚动后才浮出雾面 */
        AppTopBar(title = "每日任务清单", scrollProgress = scrollProgress) {
            Pill(
                when (sortMode) {
                    "undone" -> "未完成优先"
                    "subject" -> "按学科"
                    else -> "手动排序"
                },
                onClick = { sortMenuOpen = true },
                modifier = Modifier.prismalMenuAnchor { sortAnchor = it }
            )
            Spacer(Modifier.width(6.dp))
            /* 「更多」：导出、迁移都收在这里，顶栏只留排序 + 多选两个高频操作 */
            CircleIconButton(onClick = { moreMenuOpen = true }, size = 32.dp) {
                Icon(
                    Icons.Filled.MoreVert,
                    null,
                    tint = TextSub,
                    modifier = Modifier.size(18.dp)
                        .prismalMenuAnchor { moreAnchor = it }
                )
            }
            Spacer(Modifier.width(6.dp))
            Pill(
                if (selectMode) "取消" else "多选",
                bg = AccentOrange,
                fg = if (darkText()) Color(0xFFFFB25C) else Color(0xFFB46500),
                onClick = if (selectMode) onExitSelect else onMultiToggle
            )
        }

        /* 内容 */
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            /* 底部只留一点呼吸感：新增按钮和底部 Tab 都在布局流里占位，
               列表的可视区域天然止于按钮上方，不需要再垫一大块「安全区」空白
               ——之前留 76dp，滚到底会多出一大片空白，看着像被什么盖住了。 */
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
            /* 每条任务是一张独立卡片，行之间留 8dp 呼吸感，不再是「被刀割开的连续薄片」 */
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            /* 多选时把概览全部收起，直接露出任务 */
            if (!selectMode) {
                item {
                    /* ① 概览卡：只放「还有几项 / 完成率 / 进度」。
                       和下面所有卡片用同一层级（Control）和同一份玻璃材质——
                       之前特意抬到 Floating 又叠了一层彩色光晕，结果它成了整页
                       第三种「通透度」，调设置时只有它看起来没反应。 */
                    LiquidGlass(
                        modifier = Modifier.fillMaxWidth(),
                        level = GlassLevel.Control
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
                        ) {
                            HeroBlock(isToday, date, total, done, rate, onCalendar)
                        }
                    }
                }

                item {
                    /* ② 日期带：一条薄玻璃 + 库的刻度尺（PrismalRulerSelector）。
                       nested = true：刻度尺的水滴采样「这张卡片自己的表面」，
                       所以它是叠在卡片上的一颗水滴，而不是卡片上挖开的一个洞。 */
                    LiquidGlass(
                        modifier = Modifier.fillMaxWidth(),
                        level = GlassLevel.Control,
                        radius = 22.dp,
                        nested = true
                    ) {
                        DateStrip(date, onDateChange)
                    }
                }

                item {
                    /* ③ 状态分段：**也放在一条玻璃里**，层级、模糊、薄底和上下卡片一致。
                       之前它光着落在壁纸上，所以成了页面上「第三种通透度」：
                       卡片有底、它没有，调模糊时只有它看起来没反应。
                       放进卡片之后，液滴采样父卡片表面（嵌套玻璃），标签也落在卡片上，
                       既统一，又不会出现「标签采壁纸、液滴采卡片」的错位。 */
                    LiquidGlass(
                        modifier = Modifier.fillMaxWidth(),
                        level = GlassLevel.Control,
                        radius = 22.dp,
                        nested = true
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            FilterSegment(filter, onFilterChange)
                        }
                    }
                }

                item { SectionHeader(visible.size, filter) }
            } else {
                item {
                    Box(Modifier.padding(bottom = 10.dp)) {
                        SelectHint(selected.size, total)
                    }
                }
            }

            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        if (total == 0) "这一天还没有任务\n点下方「新增任务」开始记录"
                        else "当前筛选下没有任务"
                    )
                }
            } else {
                itemsIndexed(visible, key = { _, t -> t.id }) { i, task ->
                    val dragging = dragAbs == headerCount + i
                    val itemAbs by rememberUpdatedState(headerCount + i)

                    Box(
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    translationY = dragDy
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                            }
                            .then(
                                if (dragging) {
                                    Modifier.shadow(
                                        elevation = 18.dp,
                                        shape = RoundedCornerShape(18.dp),
                                        ambientColor = Color.Black.copy(alpha = 0.22f),
                                        spotColor = Color.Black.copy(alpha = 0.28f)
                                    )
                                } else Modifier
                            )
                            .then(
                                if (canDrag) Modifier.pointerInput(canDrag) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragAbs = itemAbs
                                            dragDy = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            currentHandleDrag(amount.y)
                                        },
                                        onDragEnd = { dragAbs = -1; dragDy = 0f },
                                        onDragCancel = { dragAbs = -1; dragDy = 0f }
                                    )
                                } else Modifier
                            )
                    ) {
                        TaskRow(
                            task = task,
                            subject = store.subjectOf(task.subject),
                            selectMode = selectMode,
                            selected = selected.contains(task.id),
                            dragging = dragging,
                            canDrag = canDrag,
                            first = i == 0,
                            last = i == visible.lastIndex,
                            onClick = {
                                if (selectMode) onToggleSelect(task.id) else onOpenTask(task.id)
                            },
                            onToggle = { onToggleTask(task.id) }
                        )
                    }
                }
            }

            /* 学习详情 + 打卡合并成一组，放在任务之后：一屏之内先看到任务 */
            if (!selectMode) {
                item {
                    Box(Modifier.padding(top = 14.dp)) {
                        JournalPanel(store, st, date, onStudy)
                    }
                }
            }
        }

        /* 多选模式：底部浮出一排批量操作，替换正常的新增按钮 */
        if (selectMode) {
            BottomBar {
                GhostButton("全选", Modifier.weight(0.85f), onClick = onSelectAll)
                PrimaryButton("标记完成", Modifier.weight(1.25f), AccentBlue, onClick = onBatchDone)
                PrimaryButton("删除", Modifier.weight(0.9f), AccentRed, onClick = onBatchDelete)
            }
        } else {
            /* 「新增任务」主按钮。底部 Tab 现在在 AppContent 的 Column 里占位，
               不再是覆盖在本页之上的悬浮层，所以按钮只需普通底部间距，
               不必再 bottom=118dp 去避开 Tab 栏。 */
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                PrimaryButton("＋ 新增任务", Modifier.fillMaxWidth(), AccentBlue, onClick = onAdd)
            }
        }
    }

    /* 排序下拉菜单：液滴展开的玻璃面板 */
    val sortBackdrop = LocalPrismalBackdrop.current
    if (sortMenuOpen && sortBackdrop != null) {
        val sortOptions = listOf(
            "manual" to "手动排序",
            "undone" to "未完成优先",
            "subject" to "按学科分组"
        )
        val currentIdx = sortOptions.indexOfFirst { it.first == sortMode }.coerceAtLeast(0)
        PrismalGlassMenu(
            expanded = sortMenuOpen,
            onDismissRequest = { sortMenuOpen = false },
            anchorBounds = sortAnchor,
            backdrop = sortBackdrop,
            specular = { null }
        ) {
            sortOptions.forEachIndexed { index, (mode, label) ->
                if (index > 0) PrismalGlassMenuDivider()
                PrismalGlassMenuItem(
                    text = label,
                    selected = index == currentIdx,
                    onClick = {
                        sortMenuOpen = false
                        onSort(mode)
                    }
                )
            }
        }
    }

    /* 「更多」菜单：导出总结 / 导出计划 / 迁移未完成，集中在一个入口 */
    val moreBackdrop = LocalPrismalBackdrop.current
    if (moreMenuOpen && moreBackdrop != null) {
        PrismalGlassMenu(
            expanded = moreMenuOpen,
            onDismissRequest = { moreMenuOpen = false },
            anchorBounds = moreAnchor,
            backdrop = moreBackdrop,
            specular = { null }
        ) {
            PrismalGlassMenuItem(
                text = "导出每日总结",
                onClick = {
                    moreMenuOpen = false
                    onExportSummary()
                }
            )
            PrismalGlassMenuDivider()
            PrismalGlassMenuItem(
                text = "导出每日计划",
                onClick = {
                    moreMenuOpen = false
                    onExportPlan()
                }
            )
            PrismalGlassMenuDivider()
            PrismalGlassMenuItem(
                text = "迁移未完成任务",
                onClick = {
                    moreMenuOpen = false
                    onMigrate()
                }
            )
        }
    }
}

/**
 * 日期 + 未完成数 + 完成率 + 进度条。
 *
 * 这一版把「日期」从大标题降级成一行小字，点它进日历；主体只保留
 * 「还有几项没做完」和完成率两个数字，密度上来了，整块玻璃也就不空了。
 */
@Composable
private fun HeroBlock(
    isToday: Boolean,
    date: String,
    total: Int,
    done: Int,
    rate: Float,
    onCalendar: () -> Unit
) {
    val undone = (total - done).coerceAtLeast(0)
    val pct = (rate * 100).roundToInt()
    val ratio by animateFloatAsState(
        targetValue = if (total == 0) 0f else done.toFloat() / total,
        animationSpec = tween(420),
        label = "progress"
    )
    /* 用 darkText() 而不是 isSystemInDarkTheme()：用户可以在设置页强制文字深浅，
       界面里的黑/白元素必须和文字站在同一边，否则会出现「浅色字配深色底」。 */
    val allDone = total > 0 && undone == 0
    val barColor = if (allDone) AccentGreen else AccentBlue

    Column(Modifier.fillMaxWidth()) {

        /* 日期行：整行可点，直接打开日历。右侧给一个日历图标 + 「日历」，
           比原来那句「点按打开日历」更短，也不会和下面的日期带重复。 */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onCalendar() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                buildString {
                    if (isToday) append("今天 · ")
                    append(Dates.month(date) + 1).append("月").append(Dates.day(date)).append("日 ")
                    append(Dates.WEEK_FULL[Dates.weekday(date)])
                },
                fontSize = 12.5.sp,
                color = TextSub,
                letterSpacing = 0.3.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.CalendarMonth,
                null,
                tint = TextMuted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("日历", fontSize = 11.5.sp, color = TextMuted, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(9.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$undone",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (allDone) AccentGreen else TextPrimary,
                letterSpacing = (-1.4).sp
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (allDone) "全部完成" else "项未完成",
                fontSize = 13.sp,
                color = TextSub,
                modifier = Modifier.padding(bottom = 7.dp)
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$pct%",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = barColor,
                    letterSpacing = (-0.5).sp
                )
                Text("完成率", fontSize = 11.sp, color = TextMuted)
            }
        }

        Spacer(Modifier.height(11.dp))

        GlassProgressBar(
            progress = ratio,
            modifier = Modifier.fillMaxWidth(),
            height = 7.dp,
            color = barColor
        )

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth()) {
            Text("已完成 $done 项", fontSize = 11.5.sp, color = TextMuted)
            Spacer(Modifier.weight(1f))
            Text("共 $total 项", fontSize = 11.5.sp, color = TextMuted)
        }
    }
}

/* ---------------- 多选模式说明条 ---------------- */

@Composable
private fun SelectHint(count: Int, total: Int) {
    LiquidGlass(Modifier.fillMaxWidth(), GlassLevel.Control, radius = Dp.Unspecified) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("多选模式", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text("点任务即可勾选", fontSize = 11.5.sp, color = TextMuted)
            Spacer(Modifier.weight(1f))
            Text(
                "$count / $total",
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AccentBlue
            )
        }
    }
}

/* ---------------- 筛选分段（官方 PrismalSegmentedControl：液滴放大镜） ---------------- */

private val FILTER_TABS = listOf("all" to "全部", "undone" to "未完成", "done" to "已完成")

@Composable
private fun FilterSegment(filter: String, onChange: (String) -> Unit) {
    /* 采样父卡片的玻璃成品（外面那层 LiquidGlass 声明了 nested），
       所以液滴和标签都「叠在卡片上」，而不是在卡片上开洞。 */
    val backdrop = glassBackdropForChild() ?: return
    val idx = FILTER_TABS.indexOfFirst { it.first == filter }.coerceAtLeast(0)
    val material = glassMaterial(GlassLevel.Control)
    val density = LocalDensity.current

    PrismalSegmentedControl(
        labels = FILTER_TABS.map { it.second },
        selectedIndex = idx,
        onSelected = { newIdx ->
            onChange(FILTER_TABS[newIdx].first)
        },
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth(),
        /* 42dp：液滴高度 = 高度 - 4dp，比 40dp 时明显更饱满。
           水滴太小时，折射带（半径 45%）会占掉整块面积，字全在折弯区里。 */
        height = 42.dp,
        /* 库会把「未选中」的标签压到 55% 透明度。55% 的常规字重在小屏上就是一片灰，
           所以这里把字重提到 Bold 补偿——加粗比加亮更不破坏层级。 */
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        ),
        /* 关键：库默认按「系统深浅色」给标签上黑/白，完全绕过了我们的「文字颜色」设置。
           系统是深色主题时标签就变成白色，压在浅色壁纸上一片糊——就是用户说的
           「完成/未完成还是白的」。这里显式传 TextPrimary，跟随用户选择的深浅。 */
        textColor = TextPrimary,
        /* 液滴的模糊 / 折射 / 明度 / 饱和度 / 薄底全部改用全局材质。
           之前用的是库默认参数（固定模糊、固定色散），所以它成了页面上
           唯一「不受设置影响」的那块玻璃。 */
        chromaticAberration = material.chroma,
        /* 1.18 保留：放大本身不会糊——把字放大 18% 反而更清楚。
           真正糊字的是模糊和色散，已经分别归零 / 封顶了。 */
        magnification = 1.18f,
        specular = material.specularProvider,
        dropletEffects = { luminance ->
            /* 补回真透镜：库在被传 dropletEffects 后不会再加 prismalLens，
               少了这一步液滴就只是「糊掉的玻璃」，文字不会被折弯。
               折射量取半径的 40% / 28%（库默认是 45% / 45~65%），
               折射带收窄后字的中间部分落在平坦区，边缘才被折弯。 */
            val r = size.minDimension / 2f
            material.applyDropletLens(this, density, luminance, r * 0.40f, r * 0.28f)
        }
    )
}

/* ---------------- 日期：切换 + 最近 7 天，合成一块 ---------------- */

/* 刻度尺的固定覆盖窗口（相对今天的天数）。写死是刻意的：
   动态范围会让库内部的 snap 逻辑自激，见 DateStrip 里的说明。 */
private const val RULER_BACK_DAYS = 182
private const val RULER_FORWARD_DAYS = 182

@Composable
private fun DateStrip(date: String, onChange: (String) -> Unit) {
    val today = Dates.today()
    val isToday = date == today
    val offset = Dates.dayOffset(today, date)

    /* 范围相对今天固定（±半年），日期串和标签一次性算好（共用 Calendar 递推）。
       固定而不是跟着选中日期伸缩：范围一变，内部索引就会重算，
       这也是上一版「一碰就一直滑」的成因之一。 */
    val rangeStart = -RULER_BACK_DAYS
    val rangeEnd = RULER_FORWARD_DAYS
    /* 日历能无限翻月，翻到半年外时日期条覆盖不到，退化成等高的文字条。 */
    val inRange = offset in rangeStart..rangeEnd

    val (dates, labels) = remember(today) {
        Dates.strip(today, RULER_BACK_DAYS, RULER_FORWARD_DAYS)
    }

    Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton({ onChange(Dates.addDays(date, -1)) }, size = 30.dp) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    null,
                    tint = TextSub,
                    modifier = Modifier.size(19.dp)
                )
            }

            if (inRange) {
                /* 滑到哪一天就显示哪一天，**不显示刻度**。
                   DateGlassStrip 是自研的：它只借库里「按压放大 / 拖动弹簧 / 液滴折射」
                   这套交互机制，不用库的滚动容器（那两个容器重建时会把选中项拉回第 0 项，
                   见 DateGlassStrip.kt 开头的说明）。 */
                DateGlassStrip(
                    labels = labels,
                    selectedIndex = offset - rangeStart,
                    onSelected = { i -> onChange(dates[i]) },
                    modifier = Modifier.weight(1f),
                    /* 一格一天、格宽 112dp。手指划 80dp 走一天：一屏（≈330dp）
                       能走四天左右，加上松手惯性，一次自然划动大约跨五到七天。
                       日期的档位是一天，不需要更细，所以比时间条稍沉一点。 */
                    dragTravelPerItem = 80.dp
                )
            } else {
                /* 超出 ±半年：给一条等高的日期文字，左右箭头走回来就自动恢复刻度尺。 */
                Box(
                    Modifier.weight(1f).height(70.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = remember(date, today) {
                            val head = when (offset) {
                                0 -> "今天"
                                -1 -> "昨天"
                                1 -> "明天"
                                else -> Dates.WEEK_FULL[Dates.weekday(date)]
                            }
                            "$head ${Dates.month(date) + 1}/${Dates.day(date)}"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            CircleIconButton({ onChange(Dates.addDays(date, 1)) }, size = 30.dp) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = TextSub,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        if (!isToday) {
            /* 间距从 2dp 提到 8dp，并把按钮自身点击区从 4dp 加到 7dp：
               日期条的手势区整个 76dp 高都在响应水平拖动，
               原来两者只隔 2dp，点「回到今天」时手指边缘很容易落进日期条里。 */
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.14f))
                        .clickable { onChange(today) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Refresh,
                            null,
                            tint = AccentBlue,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "回到今天",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentBlue
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 分组标题 ---------------- */

@Composable
private fun SectionHeader(count: Int, filter: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "任务",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSub,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (filter) {
                "undone" -> "$count 项未完成"
                "done" -> "$count 项已完成"
                else -> "$count 项"
            },
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

/* ---------------- 学习详情 + 打卡（合并成一组） ---------------- */

@Composable
private fun JournalPanel(store: AppStore, st: Study, date: String, onStudy: () -> Unit) {
    LiquidGlass(Modifier.fillMaxWidth(), GlassLevel.Panel, radius = Dp.Unspecified) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStudy() }
                    .padding(start = 15.dp, end = 12.dp, top = 13.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "每日学习详情",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(1.dp))
                    Text("时长 · 地点 · 复盘，点开填写", fontSize = 11.5.sp, color = TextMuted)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            /* 2×2 只是文字排版，没有四个小玻璃盒子 */
            Column(Modifier.padding(horizontal = 15.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldCell(
                        "学习总时长",
                        /* 存的是十进制小时（"10.5"），直接拼「小时」既不准也不好看，
                           统一走 prettyDuration → 「10 小时 30 分」。 */
                        if (st.hours.isBlank()) "未填写"
                        else parseDurationHours(st.hours).let { (h, m) -> prettyDuration(h, m) },
                        Modifier.weight(1f)
                    )
                    FieldCell("开始学习时间", st.start.ifBlank { "未填写" }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldCell("学习地点", st.place.ifBlank { "未填写" }, Modifier.weight(1f))
                    FieldCell("复盘时间", st.review.ifBlank { "未填写" }, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(11.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp)
                    .height(0.7.dp)
                    .background(Hairline)
            )

            CheckinRow(store, date)
        }
    }
}

@Composable
private fun FieldCell(label: String, value: String, modifier: Modifier = Modifier) {
    val empty = value == "未填写"
    Column(modifier) {
        Text(label, fontSize = 11.5.sp, color = TextMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (empty) TextMuted else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CheckinRow(store: AppStore, date: String) {
    val checked = store.isCheckedIn(date)
    /* 用 darkText() 而不是 isSystemInDarkTheme()：用户可以在设置页强制文字深浅，
       界面里的黑/白元素必须和文字站在同一边，否则会出现「浅色字配深色底」。 */
    val night = darkText()
    val btnBg by animateColorAsState(
        targetValue = if (checked) Color.Transparent else AccentBlue.copy(alpha = 0.15f),
        animationSpec = tween(200),
        label = "checkin_bg"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 12.dp, top = 9.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) AccentGreen else Color.Transparent)
                .border(
                    width = 1.6.dp,
                    color = if (checked) AccentGreen
                    else if (night) Color.White.copy(alpha = 0.22f) else Color(0xFFB7C2D0),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (checked) "今日已打卡" else "今日未打卡",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                "连续 ${store.streakCount()} 天 · 累计 ${store.checkins.size} 次",
                fontSize = 11.5.sp,
                color = TextMuted
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(btnBg)
                .border(
                    width = 0.8.dp,
                    color = if (checked) Hairline else AccentBlue.copy(alpha = 0.26f),
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable { store.toggleCheckin(date) }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                if (checked) "取消打卡" else "打卡",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (checked) TextSub else AccentBlue
            )
        }
    }
}

/* ---------------- 任务行 ---------------- */

/**
 * 整组任务共用一块连续的薄玻璃：只有第一行有上圆角、最后一行有下圆角，
 * 中间的行之间用一条极细的分隔线，而不是各自一张卡片。
 */
@Composable
private fun TaskRow(
    task: Task,
    subject: Subject,
    selectMode: Boolean,
    selected: Boolean,
    dragging: Boolean,
    canDrag: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !dragging) 0.995f else 1f,
        animationSpec = tween(110),
        label = "task_scale"
    )
    /* 用 darkText() 而不是 isSystemInDarkTheme()：用户可以在设置页强制文字深浅，
       界面里的黑/白元素必须和文字站在同一边，否则会出现「浅色字配深色底」。 */
    val night = darkText()
    val doneAlpha by animateFloatAsState(
        targetValue = if (task.done) 0.72f else 1f,
        animationSpec = tween(200),
        label = "task_alpha"
    )

    /* 圆角跟随全局 cornerRadiusDp（设置页「圆角半径」）。
       以前这里写死 16dp，所以用户调圆角时任务列表纹丝不动——「不是所有组件都调得了」
       就是指这些写死圆角的地方。 */
    val radius = LocalGlassTuning.current.cornerRadiusDp.dp
    val shape = RoundedCornerShape(radius)

    LiquidGlass(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick
            ),
        /* 列表行用最轻的一层材质（Row）：页面里十几二十条任务，如果每条都做
           满强度的折射 + 边缘高光，整页就会「到处都是玻璃」、又吵又费性能。
           Row 层级把折射/模糊都缩到 0.6~0.7，只保留一点点折射和稳定的底色。 */
        level = GlassLevel.Row,
        customShape = shape,
        ringScale = 0f,
        /* 行自己已经有「按下去略微缩」的手势，所以这里缩放幅度传 0，
           只让玻璃的折射随按压变强（interactionSource 必须传进去，
           否则内部新建的源永远进不了按下态，等于白设）。 */
        interactive = true,
        interactionSource = source,
        pressScaleFactor = 0f
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp)
        ) {
            /* 多选时左侧圆圈不再响应点击，避免误改完成状态 */
            Box(
                modifier = Modifier
                    .size(23.dp)
                    .clip(CircleShape)
                    .background(if (task.done) AccentGreen else Color.Transparent)
                    .border(
                        width = 1.6.dp,
                        color = if (task.done) AccentGreen
                        else if (night) Color.White.copy(alpha = 0.24f) else Color(0xFFB7C2D0),
                        shape = CircleShape
                    )
                    .then(
                        if (selectMode) Modifier else Modifier.clickable { onToggle() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (task.done) {
                    Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f).graphicsLayer { alpha = doneAlpha }) {
                Text(
                    task.title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (task.done) TextMuted else TextPrimary,
                    lineHeight = 20.sp,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null
                )
                if (task.note.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        task.note,
                        fontSize = 11.5.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(7.dp))
                SubjectTag(subject)
            }

            if (selectMode) {
                Box(
                    modifier = Modifier
                        .size(21.dp)
                        .align(Alignment.CenterVertically)
                        .clip(CircleShape)
                        .background(if (selected) AccentBlue else Color.Transparent)
                        .border(
                            width = 1.6.dp,
                            color = if (selected) AccentBlue
                            else if (night) Color.White.copy(alpha = 0.24f) else Color(0xFFB7C2D0),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Text("✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    /* 手动排序模式下给个抓手提示，长按即可上下拖 */
                    if (canDrag) {
                        DragHandle(Modifier.padding(end = 7.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        /* 独立卡片不需要行间分隔线，也不需要整组顶部高光 */
    }
}

/** 手绘三横线抓手，比字体里的 ⠿ 更清晰、也不依赖字体 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    /* 用 darkText() 而不是 isSystemInDarkTheme()：用户可以在设置页强制文字深浅，
       界面里的黑/白元素必须和文字站在同一边，否则会出现「浅色字配深色底」。 */
    val night = darkText()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) {
            Box(
                Modifier
                    .width(13.dp)
                    .height(1.6.dp)
                    .clip(CircleShape)
                    .background(
                        if (night) Color.White.copy(alpha = 0.22f) else Color(0xFFC0CAD6)
                    )
            )
        }
    }
}
