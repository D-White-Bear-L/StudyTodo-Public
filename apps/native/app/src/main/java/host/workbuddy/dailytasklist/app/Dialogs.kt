package host.workbuddy.dailytasklist.app

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/* ---------------- 通用弹窗外壳 ---------------- */

/**
 * 统一的玻璃弹窗外壳。
 *
 * 已改为组件库那套仿 iOS 的实现（IosSheetDialog）：真玻璃表面 + 「先压扁再弹开」
 * 的 squish 入场动效 + 暗色遮罩。以前这里是一块手写的渐变卡片，虽然也是半透明，
 * 但没有动效、也不折射背景，看起来就是一张贴图。
 */
@Composable
fun SheetDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    IosSheetDialog(onDismiss = onDismiss, content = content)
}

/* ---------------- 确认框 ---------------- */

/**
 * 破坏性确认（删除任务 / 删除标签）用仿 iOS 原生警告框。
 *
 * 用户反馈：这类操作应该用组件库里那套 iOS 警告弹窗——居中的小卡片、
 * 分隔线、两行按钮（取消在上、删除在下且标红）、配 squish 弹性入场，
 * 而不是 Material 那种左右并排的按钮，也不是自绘的玻璃卡片。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    IosAlertDialog(
        title = title,
        message = message,
        confirmText = confirmText,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        destructive = true
    )
}

/* ---------------- 导出菜单 ---------------- */

/**
 * 导出菜单改成 iOS 底部 sheet：从底部滑上来、顶部有拖拽指示条，
 * 比居中的小卡片更像系统原生的「操作面板」。
 */
@Composable
fun ExportMenuDialog(
    date: String,
    onDismiss: () -> Unit,
    onSummary: () -> Unit,
    onPlan: () -> Unit
) {
    IosBottomSheet(onDismiss = onDismiss, title = "导出") {
        Text(
            date,
            fontSize = 12.5.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))

        ExportItem(Icons.Outlined.Summarize, "导出每日总结", "按学科分组显示完成情况与完成率", onSummary)
        Spacer(Modifier.height(8.dp))
        ExportItem(Icons.Outlined.CalendarViewMonth, "导出每日计划", "列出学习详情与各学科任务清单", onPlan)

        Spacer(Modifier.height(16.dp))
        GhostButton("关闭", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun ExportItem(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(dialogTile())
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(dialogTile(strong = true)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(desc, fontSize = 12.sp, color = TextMuted)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = Color(0xFFA8B0BC),
            modifier = Modifier.size(20.dp)
        )
    }
}

/* ---------------- 文本导出 ---------------- */

@Composable
fun TextExportDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    subtitle: String = ""
) {
    val ctx = LocalContext.current

    /* 原来是一个居中的白色卡片 + 等宽字体，和导出菜单（底部 sheet）、
       迁移弹窗完全不是一套语言。现在统一成底部 sheet：拖拽条 + 居中标题 +
       圆角内容块 + 两个等宽按钮。正文也用常规字体——导出的都是中文，
       等宽体只会让行距更挤。 */
    IosBottomSheet(onDismiss = onDismiss, title = title) {
        Text(
            subtitle.ifBlank { "共 ${text.length} 字" },
            fontSize = 12.5.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(dialogTile())
                .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text,
                fontSize = 13.5.sp,
                lineHeight = 22.sp,
                color = TextPrimary
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("分享", Modifier.weight(1f)) {
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "分享到"))
                }.onFailure { toast(ctx, "分享失败") }
            }
            PrimaryButton("复制全文", Modifier.weight(1.5f), AccentBlue) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(title, text))
                toast(ctx, "已复制到剪贴板")
            }
        }
    }
}

/* ---------------- 迁移未完成 ---------------- */

@Composable
fun MigrateDialog(
    store: AppStore,
    date: String,
    onDismiss: () -> Unit,
    onConfirm: (target: String, ids: Set<String>, keep: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(Dates.addDays(date, 1)) }

    /* 只列未完成的：已完成的任务本来就不参与迁移 */
    val undone = remember(store.tasks, date) { store.tasksOf(date).filterNot { it.done } }
    var picked by remember(undone) { mutableStateOf(undone.map { it.id }.toSet()) }

    /* 勾选状态存在 store 里：用户勾过一次，下次打开还是勾着的 */
    val keep = store.settings.migrateKeep

    val options = listOf(
        "明天" to Dates.addDays(date, 1),
        "后天" to Dates.addDays(date, 2),
        "一周后" to Dates.addDays(date, 7)
    )

    IosBottomSheet(onDismiss = onDismiss, title = "迁移任务") {
        Text(
            "$date  →  $target",
            fontSize = 12.5.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))

        /* ---- 目标日期 ---- */
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, d) ->
                val on = target == d
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (on) AccentBlue.copy(alpha = 0.16f) else dialogTile()
                        )
                        .border(
                            1.dp,
                            if (on) AccentBlue.copy(alpha = 0.45f) else Hairline,
                            RoundedCornerShape(13.dp)
                        )
                        .clickable { target = d },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) AccentBlue else TextSub
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        /* 自定义日期 */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(dialogTile())
                .border(1.dp, Hairline, RoundedCornerShape(13.dp))
                .clickable {
                    DatePickerDialog(
                        ctx,
                        { _, yy, mm, dd -> target = Dates.ymd(yy, mm, dd) },
                        Dates.year(target), Dates.month(target), Dates.day(target)
                    ).show()
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自定义日期", fontSize = 14.sp, color = TextSub, modifier = Modifier.weight(1f))
            Text(target, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        /* ---- 迁移内容 ---- */
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "迁移内容",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (undone.isNotEmpty()) {
                val allPicked = picked.size == undone.size
                Text(
                    if (allPicked) "全不选" else "全选",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            picked = if (allPicked) emptySet() else undone.map { it.id }.toSet()
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (undone.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(dialogTile())
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("这一天没有未完成的任务", fontSize = 13.sp, color = TextMuted)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 176.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(dialogTile())
                    .verticalScroll(rememberScrollState())
            ) {
                undone.forEachIndexed { i, task ->
                    if (i > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 46.dp)
                                .height(1.dp)
                                .background(Hairline)
                        )
                    }
                    MigratePickRow(
                        title = task.title,
                        subject = store.subjectOf(task.subject),
                        checked = task.id in picked
                    ) {
                        picked = if (task.id in picked) picked - task.id else picked + task.id
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---- 保留原来的 ---- */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(dialogTile())
                .clickable { store.setMigrateKeep(!keep) }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckDot(checked = keep)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "保留原来的任务",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    if (keep) "等于复制一份到目标日期，这一天不动"
                    else "搬走，这一天只留下已完成的任务",
                    fontSize = 11.5.sp,
                    color = TextMuted
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("取消", Modifier.weight(1f), onClick = onDismiss)
            PrimaryButton(
                when {
                    undone.isEmpty() -> "无可迁移任务"
                    picked.isEmpty() -> "请选择任务"
                    else -> "${if (keep) "复制" else "迁移"} ${picked.size} 项"
                },
                Modifier.weight(1.4f),
                AccentBlue
            ) {
                when {
                    undone.isEmpty() -> toast(ctx, "这一天没有未完成的任务")
                    target == date -> toast(ctx, "目标日期不能是当天")
                    picked.isEmpty() -> toast(ctx, "先勾选要迁移的任务")
                    else -> onConfirm(target, picked, keep)
                }
            }
        }
    }
}

/**
 * 迁移清单里的一行：左边一个自绘的勾选圆点，右边任务名 + 学科点。
 * 不用 Material 的 Checkbox——那个方块圆角和外圈的灰色在这套玻璃弹窗里很突兀。
 */
@Composable
private fun MigratePickRow(
    title: String,
    subject: Subject,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckDot(checked = checked)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 13.5.sp,
            color = if (checked) TextPrimary else TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(subject.color))
        )
        Spacer(Modifier.width(5.dp))
        Text(subject.name, fontSize = 11.sp, color = TextMuted)
    }
}

/** 自绘勾选圆点：选中是实心 + ✓，未选中是一圈淡描边 */
@Composable
private fun CheckDot(checked: Boolean, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(if (checked) AccentBlue else Color.Transparent)
            .border(
                if (checked) 1.dp else 1.5.dp,
                if (checked) AccentBlue else TextMuted.copy(alpha = 0.35f),
                RoundedCornerShape(50)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text(
                "✓",
                fontSize = (size.value * 0.6f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/** 删除标签时选「任务改到哪个标签」用的小胶囊 */
@Composable
private fun SubjectPickChip(
    subject: Subject,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(subject.color).copy(alpha = 0.18f) else dialogTile())
            .border(
                1.dp,
                if (selected) Color(subject.color).copy(alpha = 0.55f) else Color(0x141A1D21),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(subject.color))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            subject.name,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) TextPrimary else TextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ---------------- 学科标签管理 ---------------- */

/**
 * 管理自定义学科：列表 → 编辑 / 新增 → 删除确认，三种状态共用一个弹窗。
 * 标签数量不写死，用户可以任意增删改。
 */
@Composable
fun SubjectManageDialog(store: AppStore, onDismiss: () -> Unit) {
    SheetDialog(onDismiss) {
        SubjectManageContent(store = store, onDismiss = onDismiss)
    }
}

/** 弹窗内容单独抽出，方便离屏截图核对排版 */
@Composable
fun SubjectManageContent(store: AppStore, onDismiss: () -> Unit) {
    val ctx = LocalContext.current

    var isEditing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Subject?>(null) }
    var deleteTarget by remember { mutableStateOf<Subject?>(null) }

    when {
        deleteTarget != null -> {
                val t = deleteTarget!!
                val rest = store.subjects.filterNot { it.key == t.key }
                val used = store.tasksUsingSubject(t.key)

                /*
                 * 以前这里是把任务并到「剩下的第一个标签」，也就是数学 —— 用户删掉任何
                 * 一个标签，底下的任务都会变成数学，很不合理。现在由用户自己挑：
                 * 只剩一个标签时没得选，直接预选；有多个就要求先选一个。
                 */
                var into by remember(t.key, rest.size) {
                    mutableStateOf(if (rest.size == 1) rest.first().key else "")
                }
                val targetName = rest.firstOrNull { it.key == into }?.name

                Text("删除标签", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (used > 0)
                        "「${t.name}」下有 $used 条任务，删掉后这些任务改到下面哪一个标签？"
                    else
                        "确定删除「${t.name}」吗？这个标签下没有任务。",
                    fontSize = 13.sp,
                    color = TextSub,
                    lineHeight = 20.sp
                )

                if (used > 0) {
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rest.chunked(3).forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                line.forEach { opt ->
                                    SubjectPickChip(
                                        subject = opt,
                                        selected = into == opt.key,
                                        modifier = Modifier.weight(1f)
                                    ) { into = opt.key }
                                }
                                /* 最后一行不满三个时用空位补齐，宽度才和上面几行对齐 */
                                repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                /* 结果的说明放这里，而不是塞进按钮：标签名最多 8 个字，
                   按钮上写「改到『一二三四五六七八』并删除」会被挤断 */
                if (used > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (targetName == null) "↑ 先选一个标签，才能确定这些任务去哪儿"
                        else "这 $used 条任务会变成「$targetName」",
                        fontSize = 12.sp,
                        fontWeight = if (targetName == null) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (targetName == null) TextMuted else AccentBlue
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("取消", Modifier.weight(1f)) { deleteTarget = null }
                    PrimaryButton("确认删除", Modifier.weight(1f), AccentRed) {
                        when {
                            used == 0 -> {
                                if (!store.deleteSubject(t.key)) toast(ctx, "至少要保留一个标签")
                                else deleteTarget = null
                            }
                            targetName == null -> toast(ctx, "先选一个标签")
                            else -> {
                                val moved = store.deleteSubject(t.key, into)
                                deleteTarget = null
                                if (moved) toast(ctx, "已删除「${t.name}」，$used 条任务改到「$targetName」")
                                else toast(ctx, "至少要保留一个标签")
                            }
                        }
                    }
                }
            }

            isEditing -> SubjectEditor(
                initial = editTarget,
                onCancel = { isEditing = false; editTarget = null },
                onSave = { name, color ->
                    val ok = if (editTarget == null) store.addSubject(name, color)
                    else store.updateSubject(editTarget!!.key, name, color)
                    if (!ok) toast(ctx, "名称不能为空、最多 8 个字，且不能重名")
                    else { isEditing = false; editTarget = null }
                }
            )

            else -> {
                Text("管理学科标签", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "标签用于任务分类和导出分组，最多 8 个字",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    store.subjects.forEach { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(dialogTile())
                                .border(1.dp, Color(0x141A1D21), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(s.color))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                s.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Text(
                                "编辑",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { editTarget = s; isEditing = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            if (store.subjects.size > 1) {
                                Text(
                                    "删除",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentRed,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { deleteTarget = s }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("关闭", Modifier.weight(1f), onClick = onDismiss)
                    PrimaryButton("＋ 新增标签", Modifier.weight(1.4f), AccentBlue) {
                        editTarget = null
                        isEditing = true
                    }
                }
            }
    }
}

/** 新增 / 编辑单个标签：名称 + 配色 */
@Composable
fun SubjectEditor(
    initial: Subject?,
    onCancel: () -> Unit,
    onSave: (String, Long) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: SUBJECT_COLORS.first()) }
    var showPicker by remember { mutableStateOf(false) }

    Text(
        if (initial == null) "新增标签" else "编辑标签",
        fontSize = 17.sp,
        fontWeight = FontWeight.ExtraBold,
        color = TextPrimary
    )
    Spacer(Modifier.height(14.dp))

    FormLabel("名称", required = true)
    InputBox(
        value = name,
        onChange = { if (it.length <= 8) name = it },
        placeholder = "例如 数据结构"
    )
    Text(
        "${name.length}/8",
        fontSize = 11.5.sp,
        color = TextMuted,
        modifier = Modifier.padding(top = 6.dp)
    )

    Spacer(Modifier.height(16.dp))

    FormLabel("颜色")
    /* 12 个预设色分两行，每行 6 个，不写死总宽度 */
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SUBJECT_COLORS.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val on = color == c
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .then(
                                if (on) Modifier
                                    .background(Color(c).copy(alpha = 0.16f))
                                    .border(2.dp, Color(c), RoundedCornerShape(11.dp))
                                else Modifier
                            )
                            .clickable { color = c },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (on) 20.dp else 26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(c))
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    /* 预设之外想要什么颜色都行，用取色盘自己调 */
    val isCustom = SUBJECT_COLORS.none { it == color }
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (isCustom) Color(color).copy(alpha = 0.16f) else Color.White.copy(alpha = 0.6f))
            .border(
                1.dp,
                if (isCustom) Color(color) else Color(0x1F1A1D21),
                RoundedCornerShape(11.dp)
            )
            .clickable { showPicker = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Palette,
            null,
            tint = if (isCustom) Color(color) else TextSub,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (isCustom) "自定义 ${argbToHex(color)}" else "自定义颜色",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isCustom) Color(color) else TextSub
        )
    }

    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("取消", Modifier.weight(1f), onClick = onCancel)
        PrimaryButton("保存", Modifier.weight(1.4f), AccentBlue) { onSave(name, color) }
    }

    if (showPicker) {
        ColorPickerDialog(
            title = "标签颜色",
            initial = color,
            onDismiss = { showPicker = false },
            onConfirm = {
                color = it
                showPicker = false
            }
        )
    }
}
