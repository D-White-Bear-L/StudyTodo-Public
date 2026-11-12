package host.workbuddy.dailytasklist.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---------------- 任务编辑页 ---------------- */

@Composable
fun EditScreen(
    store: AppStore,
    date: String,
    taskId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val existing = remember(taskId) { store.tasksOf(date).firstOrNull { it.id == taskId } }

    /* 默认学科取用户的第一个标签，不写死某个 key */
    val defaultSubject = store.subjects.firstOrNull()?.key ?: "other"
    var subject by remember { mutableStateOf(existing?.subject ?: defaultSubject) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var done by remember { mutableStateOf(existing?.done ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var manageSubjects by remember { mutableStateOf(false) }

    /* 标签被删掉后，选中项自动回落到第一个，避免出现空选中 */
    LaunchedEffect(store.subjects) {
        if (store.subjects.none { it.key == subject }) {
            store.subjects.firstOrNull()?.let { subject = it.key }
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize()) {

            /* 顶栏 */
            AppTopBar(
                title = if (taskId == null) "新增任务" else "编辑任务",
                onBack = onBack
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassCard {
                    /* 任务内容 */
                    FormLabel("任务内容", required = true)
                    InputBox(
                        value = title,
                        onChange = { if (it.length <= 40) title = it },
                        placeholder = "例如：英语阅读 text2 复盘"
                    )
                    Hint("简洁描述要完成的事项，便于快速勾选")

                    Spacer(Modifier.height(18.dp))

                    /* 学科标签：自适应换行，数量由用户自己维护 */
                    FormLabel("学科标签", optional = true)
                    SubjectPicker(
                        subjects = store.subjects,
                        selected = subject,
                        onPick = { subject = it },
                        onManage = { manageSubjects = true }
                    )

                    Spacer(Modifier.height(18.dp))

                    /* 备注 */
                    FormLabel("备注", optional = true)
                    InputBox(
                        value = note,
                        onChange = { if (it.length <= 200) note = it },
                        placeholder = "添加备注说明...",
                        minHeight = 110.dp,
                        singleLine = false
                    )
                    Text(
                        "${note.length}/200 字",
                        fontSize = 11.5.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(Modifier.height(18.dp))

                    /* 完成状态 */
                    FormLabel("完成状态")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(FieldShape)
                            .background(
                                if (done) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0x1A34C759),
                                            Color(0x0D34C759)
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.10f),
                                            Color.White.copy(alpha = 0.03f)
                                        )
                                    )
                                }
                            )
                            .border(
                                BorderStroke(
                                    0.8.dp,
                                    if (done) androidx.compose.ui.graphics.SolidColor(Color(0x4734C759))
                                    else Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.45f),
                                            Color.White.copy(alpha = 0.10f)
                                        )
                                    )
                                ),
                                FieldShape
                            )
                            .clickable { done = !done }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (done) AccentGreen else Color.Transparent)
                                .border(
                                    BorderStroke(
                                        1.6.dp,
                                        when {
                                            done -> AccentGreen
                                            androidx.compose.foundation.isSystemInDarkTheme() ->
                                                Color.White.copy(alpha = 0.24f)
                                            else -> Color(0xFFB7C2D0)
                                        }
                                    ),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (done) "已完成" else "未完成",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (done) Color(0xFF2EA043) else TextSub
                        )
                    }
                }

                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0x552B7FFF),
                                            Color(0x331A5FD9)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(0.5.dp, Color.White.copy(alpha = 0.30f)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("i", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("个人记录", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextSub)
                            Text(
                                "本应用仅用于个人任务与心得记录，所有数据只保存在你的设备本地，不会上传服务器。",
                                fontSize = 12.5.sp,
                                color = TextMuted,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            /* 底部按钮：在布局流里占位，不会盖住上面的卡片 */
            BottomBar {
                if (taskId != null) {
                    GhostButton("删除", Modifier.weight(1f), AccentRed) { confirmDelete = true }
                } else {
                    GhostButton("取消", Modifier.weight(1f), onClick = onBack)
                }
                PrimaryButton(
                    "保存",
                    Modifier.weight(1.6f),
                    AccentBlue
                ) {
                    val t = title.trim()
                    if (t.isEmpty()) {
                        toast(ctx, "请填写任务内容")
                    } else if (taskId == null) {
                        store.addTask(date, t, subject, note, done)
                        toast(ctx, "已添加")
                        onSaved()
                    } else {
                        existing?.let {
                            store.updateTask(date, it.copy(title = t, subject = subject, note = note, done = done))
                        }
                        toast(ctx, "已保存")
                        onSaved()
                    }
                }
            }
    }

    if (manageSubjects) {
        SubjectManageDialog(store = store, onDismiss = { manageSubjects = false })
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除任务",
            message = "删除后不可恢复，确定删除这条任务吗？",
            confirmText = "删除",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                taskId?.let { store.deleteTask(date, it) }
                confirmDelete = false
                toast(ctx, "已删除")
                onSaved()
            }
        )
    }
}

/* ---------------- 表单小组件 ---------------- */

@Composable
fun FormLabel(text: String, required: Boolean = false, optional: Boolean = false) {
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (required) {
            Text(" *", fontSize = 14.5.sp, color = AccentRed, fontWeight = FontWeight.Bold)
        }
        if (optional) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(
                        BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text("选填", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    minHeight: androidx.compose.ui.unit.Dp = 50.dp,
    singleLine: Boolean = true
) {
    val shape = FieldShape
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.03f)
                    )
                )
            )
            .border(
                BorderStroke(
                    0.8.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.40f),
                            Color.White.copy(alpha = 0.10f)
                        )
                    )
                ),
                shape
            )
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 15.sp, color = TextMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun SubjectChip(
    subject: Subject,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val color = Color(subject.color)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) color else color.copy(alpha = 0.14f))
            .border(
                BorderStroke(1.dp, if (selected) Color.Transparent else color.copy(alpha = 0.24f)),
                shape
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            subject.name,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else color,
            maxLines = 1
        )
    }
}

/**
 * 学科标签选择器。
 * 标签的数量、名称、颜色都由用户自己维护，所以这里按内容宽度自适应换行，
 * 不写死列数；末尾固定一个「管理」入口，用于增删改标签。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SubjectPicker(
    subjects: List<Subject>,
    selected: String,
    onPick: (String) -> Unit,
    onManage: () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        subjects.forEach { s ->
            SubjectChip(
                subject = s,
                selected = selected == s.key,
                onClick = { onPick(s.key) }
            )
        }
        ManageChip(onManage)
    }
}

/** 标签云末尾的「管理」入口 */
@Composable
private fun ManageChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.03f)
                    )
                )
            )
            .border(
                BorderStroke(
                    0.8.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.10f)
                        )
                    )
                ),
                shape
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("＋", fontSize = 14.sp, color = TextSub, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(3.dp))
        Text("管理", fontSize = 13.5.sp, color = TextSub, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
