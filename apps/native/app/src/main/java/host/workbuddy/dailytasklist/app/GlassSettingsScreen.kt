package host.workbuddy.dailytasklist.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.components.PrismalGlassSlider
import com.styropyr0.prismal.components.PrismalGlassToggle
import kotlin.math.roundToInt

/* ---------------- 玻璃效果设置页 ----------------
   让用户自己调节液态玻璃的通透度 / 折射 / 色散等参数，
   所有参数实时生效（全局 GlassTuning），并持久化到本地。
   调节用的滑块和开关本身就是 Prismal 的玻璃组件，自己也是「活示例」。 */

@Composable
fun GlassSettingsScreen(
    store: AppStore,
    tuning: GlassTuning,
    onBack: () -> Unit,
    onReset: () -> Unit,
    hasWallpaper: Boolean = false,
    onPickWallpaper: () -> Unit = {},
    onClearWallpaper: () -> Unit = {}
) {
    val backdrop = LocalPrismalBackdrop.current
    val context = LocalContext.current

    /* ---- 数据备份：导出走系统「保存到…」，导入走系统文件选择器 ---- */
    var pendingImport by remember { mutableStateOf<BackupData?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(Backup.encode(store).toByteArray(Charsets.UTF_8))
                } ?: error("无法写入")
            }.isSuccess
            toast(context, if (ok) "备份已保存，建议再复制一份到网盘" else "保存失败，换个目录再试一次")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (text == null) {
                toast(context, "读不到这个文件，换一个试试")
            } else {
                runCatching { Backup.decode(text) }
                    .onSuccess { pendingImport = it }
                    .onFailure { toast(context, it.message ?: "备份解析失败") }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = "玻璃效果", subtitle = "实时调节液态玻璃", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            /* ---- 云端同步 ---- */
            SectionTitle("云端同步")
            GlassCard {
                Text(
                    "把记录同时存在服务器上，几台设备共用一份数据：" +
                        "这边改完那边自动能看到，不用再导出导入。断网时照常记录，联网后自动补传。",
                    fontSize = 11.5.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))

                var tokenInput by remember { mutableStateOf(Sync.cfg.token) }
                val connected = Sync.cfg.connected && Sync.cfg.bootDone
                Text(
                    if (connected) "已连接 · " + Sync.statusText()
                    else if (Sync.cfg.connected) "已填同步码，等待首次同步"
                    else "未连接",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (Sync.cfg.lastError.isNotBlank() && !Sync.syncing) AccentRed else TextPrimary,
                    lineHeight = 19.sp
                )

                Spacer(Modifier.height(10.dp))
                InputBox(
                    value = tokenInput,
                    onChange = { tokenInput = it.trim() },
                    placeholder = "在这里粘贴同步码"
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "同步码只在这台设备和服务器之间用，填一次就好。",
                    fontSize = 11.sp,
                    color = TextMuted
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        if (connected) "立即同步" else "连接",
                        Modifier.weight(1f),
                        color = AccentBlue
                    ) {
                        if (tokenInput.isBlank()) {
                            toast(context, "先把同步码粘进来")
                        } else {
                            val first = Sync.connect(tokenInput)
                            Sync.syncNow()
                            toast(context, if (first) "正在拉取云端数据…" else "正在同步…")
                        }
                    }
                    if (connected) {
                        GhostButton("断开", Modifier.weight(1f), color = AccentRed) {
                            Sync.disconnect()
                            tokenInput = ""
                            toast(context, "已断开，本地数据不受影响")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                TuningToggle(
                    label = "自动同步",
                    desc = "改完自动上传；切回 App 时自动拉取别人的改动",
                    checked = Sync.cfg.enabled,
                    backdrop = backdrop
                ) { on -> Sync.setEnabled(on) }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 数据备份 ---- */
            val (taskCount, dayCount, checkinCount) = store.stats()
            SectionTitle("数据备份")
            GlassCard {
                Text(
                    "记录只存在应用私有目录里，卸载会把它们一起删掉，没有回收站。" +
                        "下面这个自动备份会一直往「下载/每日任务清单备份」写副本，那个目录卸载动不到。",
                    fontSize = 11.5.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "当前：$taskCount 条任务 · $dayCount 天记录 · $checkinCount 次打卡",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))

                var lastAt by remember { mutableStateOf(AutoBackup.lastAt(context)) }
                TuningToggle(
                    label = "自动备份到「下载」",
                    desc = if (lastAt == 0L) {
                        "改一次数据就写一份，卸载也带不走"
                    } else {
                        "上次 ${backupTimeText(lastAt)} · 下载/每日任务清单备份"
                    },
                    checked = store.settings.autoBackup,
                    backdrop = backdrop
                ) { on ->
                    store.setAutoBackup(on)
                    lastAt = if (on) System.currentTimeMillis() else 0L
                }

                Spacer(Modifier.height(10.dp))
                GhostButton("立即备份一次", Modifier.fillMaxWidth()) {
                    store.setAutoBackup(true)
                    AutoBackup.runNow(context, store)
                    lastAt = System.currentTimeMillis()
                    toast(context, "已写入 下载/每日任务清单备份")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("导出备份", Modifier.weight(1f)) {
                        exportLauncher.launch("每日任务清单备份-${Dates.today()}.json")
                    }
                    GhostButton("导入备份", Modifier.weight(1f), color = AccentBlue) {
                        /* 有些文件管理器把 json 报成 octet-stream，所以不限 mime，全类型都能选 */
                        importLauncher.launch(arrayOf("*/*"))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 背景 ---- */
            SectionTitle("背景")
            GlassCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (hasWallpaper) "自定义壁纸" else "内置色场背景",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            "深色/浅色各有一套内置背景，也可从相册选图",
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("从相册选", Modifier.weight(1f), onClick = onPickWallpaper)
                    if (hasWallpaper) {
                        GhostButton("恢复默认背景", Modifier.weight(1f), color = AccentRed, onClick = onClearWallpaper)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 通透度（模糊） ---- */
            SectionTitle("通透度")
            GlassCard {
                TuningSlider(
                    label = "模糊半径",
                    value = tuning.blurRadiusDp,
                    range = 0f..16f,
                    valueText = "%.0f pt".format(tuning.blurRadiusDp),
                    backdrop = backdrop,
                    hint = "越小越通透，越大越像毛玻璃"
                ) { tuning.blurRadiusDp = it; GlassTuningStorage.save(context, tuning) }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 折射 ---- */
            SectionTitle("折射")
            GlassCard {
                TuningSlider(
                    label = "折射高度",
                    value = tuning.refractionHeightDp,
                    range = 0f..32f,
                    valueText = "%.0f pt".format(tuning.refractionHeightDp),
                    backdrop = backdrop,
                    hint = "边缘折射影响的高度带"
                ) { tuning.refractionHeightDp = it; GlassTuningStorage.save(context, tuning) }

                Spacer(Modifier.height(4.dp))

                TuningSlider(
                    label = "折射幅度",
                    value = tuning.refractionAmountDp,
                    range = 0f..60f,
                    valueText = "%.0f pt".format(tuning.refractionAmountDp),
                    backdrop = backdrop,
                    hint = "边缘折弯背景的像素幅度，越大折得越狠"
                ) { tuning.refractionAmountDp = it; GlassTuningStorage.save(context, tuning) }

                Spacer(Modifier.height(4.dp))

                TuningSlider(
                    label = "色散（色差）",
                    value = tuning.chromaticAberration,
                    /* 上限放到 1.0 = 100%。库的 chromaticAberration 本身就是 0~1 的归一化参数，
                       之前卡在 0.6 是我自己保守，用户想拉满却没有空间。 */
                    range = 0f..1f,
                    valueText = "%.0f%%".format(tuning.chromaticAberration * 100),
                    backdrop = backdrop,
                    hint = "边缘红蓝分离强度，拉满时边缘会明显分光"
                ) { tuning.chromaticAberration = it; GlassTuningStorage.save(context, tuning) }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 明度与色彩 ---- */
            SectionTitle("明度与色彩")
            GlassCard {
                TuningSlider(
                    label = "明度",
                    value = tuning.brightness,
                    range = -0.3f..0.5f,
                    valueText = "%+.0f%%".format(tuning.brightness * 100),
                    backdrop = backdrop,
                    hint = "玻璃背后的明暗，正数更亮、负数更暗"
                ) {
                    tuning.brightness = it
                    /* 拖动明度时自动关闭 vibrancy，切到手动 colorControls，明度才会真正生效 */
                    if (tuning.useVibrancy) tuning.useVibrancy = false
                    GlassTuningStorage.save(context, tuning)
                }

                Spacer(Modifier.height(4.dp))

                TuningSlider(
                    label = "饱和度",
                    value = tuning.saturation,
                    range = 0f..2.5f,
                    valueText = "%.0f%%".format(tuning.saturation * 100),
                    backdrop = backdrop,
                    hint = "玻璃折射后的色彩浓度，越高越鲜艳、越低越灰"
                ) {
                    tuning.saturation = it
                    if (tuning.useVibrancy) tuning.useVibrancy = false
                    GlassTuningStorage.save(context, tuning)
                }

                Spacer(Modifier.height(4.dp))

                TuningSlider(
                    label = "表面染色（Surface Tint）",
                    value = tuning.surfaceTintAlpha,
                    range = 0f..0.6f,
                    valueText = "%.0f%%".format(tuning.surfaceTintAlpha * 100),
                    backdrop = backdrop,
                    hint = "在玻璃表面叠一层白色，让底色变白变亮"
                ) { tuning.surfaceTintAlpha = it; GlassTuningStorage.save(context, tuning) }

                Spacer(Modifier.height(4.dp))

                TuningToggle(
                    label = "鲜艳滤镜（Vibrancy）",
                    desc = "关闭后明度/饱和度滑块才生效，改为手动色彩控制",
                    checked = tuning.useVibrancy,
                    backdrop = backdrop
                ) {
                    tuning.useVibrancy = it
                    GlassTuningStorage.save(context, tuning)
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 形状（圆角）与可读性 ---- */
            SectionTitle("形状与可读性")
            GlassCard {
                TuningSlider(
                    label = "圆角半径",
                    value = tuning.cornerRadiusDp,
                    range = 0f..48f,
                    valueText = "%.0f pt".format(tuning.cornerRadiusDp),
                    backdrop = backdrop,
                    hint = "所有玻璃面板的默认圆角，0 为直角"
                ) { tuning.cornerRadiusDp = it; GlassTuningStorage.save(context, tuning) }

                Spacer(Modifier.height(6.dp))

                TuningSlider(
                    label = "可读性（文字对比）",
                    value = tuning.readability,
                    range = 0f..2f,
                    valueText = "%.0f%%".format(tuning.readability * 100),
                    backdrop = backdrop,
                    hint = "给玻璃垫一层薄底：越高文字越清楚、玻璃越像磨砂面板；0 = 最通透但字容易糊"
                ) { tuning.readability = it; GlassTuningStorage.save(context, tuning) }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 文字颜色 ---- */
            SectionTitle("文字颜色")
            GlassCard {
                Text(
                    "跟随系统深浅色判断，有时和你的壁纸/背景不匹配会导致文字看不清。可手动强制深色或浅色文字。",
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                TextModeSelector(
                    mode = tuning.textMode,
                    onSelect = { tuning.textMode = it; GlassTuningStorage.save(context, tuning) }
                )
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 染色与高光 ---- */
            SectionTitle("染色与高光")
            GlassCard {
                TuningSlider(
                    label = "着色强度",
                    value = tuning.tintAlpha,
                    range = 0f..1f,
                    valueText = "%.0f%%".format(tuning.tintAlpha * 100),
                    backdrop = backdrop,
                    hint = "主按钮/着色玻璃的染色强度：0 = 几乎全透（文字自动转深色），100% = 接近实心色块"
                ) { tuning.tintAlpha = it; GlassTuningStorage.save(context, tuning) }

                Spacer(Modifier.height(4.dp))

                TuningToggle(
                    label = "边缘高光描边",
                    desc = "关闭后边缘只保留真实折射折光",
                    checked = tuning.specularEnabled,
                    backdrop = backdrop
                ) {
                    tuning.specularEnabled = it
                    GlassTuningStorage.save(context, tuning)
                }

                Spacer(Modifier.height(4.dp))

                TuningToggle(
                    label = "浮层投影",
                    desc = "悬浮玻璃（如底部操作坞）的投影，关掉更通透",
                    checked = tuning.depthShadowEnabled,
                    backdrop = backdrop
                ) {
                    tuning.depthShadowEnabled = it
                    GlassTuningStorage.save(context, tuning)
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 实验室 ---- */
            SectionTitle("实验室")
            GlassCard {
                Text(
                    "自研的悬浮液态玻璃导航栏：真悬浮不占页面高度，玻璃折的是身下页面此刻的真实内容（不是壁纸）。在这里进演示页，换背景、切采样源、调强度、拖着玩。",
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentBlue.copy(alpha = 0.16f))
                        .clickable {
                            context.startActivity(
                                android.content.Intent(context, DemoActivity::class.java)
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "打开悬浮导航栏 Demo",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            "六种背景 · 采样源 A/B · 强度三档",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                    Text("›", fontSize = 20.sp, color = TextSub)
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- 重置 ---- */
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        tuning.reset()
                        GlassTuningStorage.save(context, tuning)
                        onReset()
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "恢复默认参数",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentRed
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    pendingImport?.let { data ->
        BackupImportDialog(
            data = data,
            onDismiss = { pendingImport = null },
            onReplace = {
                val n = store.installBackup(data, merge = false)
                pendingImport = null
                toast(context, "已覆盖导入 $n 条任务")
            },
            onMerge = {
                val n = store.installBackup(data, merge = true)
                pendingImport = null
                toast(context, if (n > 0) "已合并 $n 条新任务" else "没有新任务，这些记录都已经在 APP 里")
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = TextSub,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** 文字颜色三选一：跟随系统 / 强制深色 / 强制浅色 */
@Composable
private fun TextModeSelector(mode: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        0 to "跟随系统",
        1 to "深色文字",
        2 to "浅色文字"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = mode == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (on) Color(0x242B7FFF)
                        else Color.White.copy(alpha = if (isSystemInDarkTheme()) 0.08f else 0.6f)
                    )
                    .border(
                        1.dp,
                        if (on) AccentBlue.copy(alpha = 0.4f) else Color(0x1A000000),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) AccentBlue else TextSub
                )
            }
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    backdrop: com.styropyr0.prismal.PrismalBackdrop?,
    hint: String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = AccentBlue, fontWeight = FontWeight.Bold)
        }
        Text(hint, fontSize = 11.5.sp, color = TextMuted)
        Spacer(Modifier.height(4.dp))

        /* 用回库里的官方 PrismalGlassSlider（玻璃滑钮 + 液滴透镜 + 阻尼动画）。
           之前拖不动不是它的问题，是我们自己把整张卡片连滑块一起重建了——
           见 LiquidSurface 里关于 key(圆角) 的说明。
           外面套一个 28dp 高的盒子：滑钮本体 24dp，只给 6dp 轨道排版会顶到上面的说明文字。 */
        if (backdrop != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                PrismalGlassSlider(
                    value = { value },
                    onValueChange = onChange,
                    valueRange = range,
                    visibilityThreshold = (range.endInclusive - range.start) * 0.004f,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TuningToggle(
    label: String,
    desc: String,
    checked: Boolean,
    backdrop: com.styropyr0.prismal.PrismalBackdrop?,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(desc, fontSize = 11.5.sp, color = TextMuted)
        }
        if (backdrop != null) {
            PrismalGlassToggle(
                selected = { checked },
                onSelect = onChange,
                backdrop = backdrop
            )
        }
    }
}

/** 「上次备份」写成人话：今天 09:12 / 昨天 21:40 / 11-09 08:30 */
private fun backupTimeText(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        .format(java.util.Date(at))
    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    if (sameYear && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)) {
        return "今天 $hm"
    }
    now.add(java.util.Calendar.DAY_OF_MONTH, -1)
    if (sameYear && now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        return "昨天 $hm"
    }
    return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))
}
