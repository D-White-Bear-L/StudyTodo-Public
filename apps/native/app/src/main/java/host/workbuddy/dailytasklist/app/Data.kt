package host.workbuddy.dailytasklist.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ---------------- 数据模型 ---------------- */

data class Task(
    val id: String,
    val title: String,
    val subject: String,
    val note: String = "",
    val done: Boolean = false,
    val createdAt: Long = 0L
)

data class Study(
    val hours: String = "",
    val start: String = "",
    val place: String = "",
    val review: String = ""
) {
    val isEmpty: Boolean get() = hours.isBlank() && start.isBlank() && place.isBlank() && review.isBlank()
}

data class Settings(
    val theme: Long = 0xFF34C759,
    val showCompletion: Boolean = true,
    /* manual = 用户手动拖出来的顺序（默认）；undone = 未完成优先；subject = 按学科分组 */
    val sort: String = "manual",
    /* 背景图：用户自选壁纸的本地文件路径；空串 = 用内置色场背景 */
    val wallpaperPath: String = "",
    /* 迁移时是否保留原来的任务（= 复制一份过去）。用户勾过一次就一直记住 */
    val migrateKeep: Boolean = false,
    /* 自动把整份数据写进「下载/每日任务清单备份」，卸载也带不走。默认开 */
    val autoBackup: Boolean = true
)

data class Subject(val key: String, val name: String, val color: Long)

/* 首次启动写进去的示例任务。三处逻辑（写入 / 合并导入 / 连接云端前清理）
   都要认同一份清单，所以集中在这里定义，避免改了一处漏另一处。 */
val DEMO_SEED_IDS = setOf("s1", "s2", "s3")
val DEMO_SEED_TITLES = setOf("把今天最要紧的一件事挑出来", "看书半小时", "出门走一走")
val DEMO_SEED_STUDY = Study("2", "20:00", "", "")
/* 老版本装过的机器上还留着这批示例任务，清理时要一并认出来 */
val LEGACY_SEED_TITLES = setOf("英语真题阅读 2 篇 + 复盘", "数学真题套卷做 + 订正", "政治分析题背诵")

/* 首次启动的默认学科；之后完全由用户自己维护，存在本地 */
val DEFAULT_SUBJECTS = listOf(
    Subject("math", "数学", 0xFF4C8DFF),
    Subject("english", "英语", 0xFF1677FF),
    Subject("cs408", "408", 0xFFFF9F2E),
    Subject("politics", "政治", 0xFFFF5B6E),
    Subject("major", "专业课", 0xFFA855F7),
    Subject("other", "其他", 0xFF8A8F99)
)

/* 新增 / 编辑学科时可选的配色 */
val SUBJECT_COLORS = listOf(
    0xFF4C8DFF, 0xFF1677FF, 0xFF00B8D9, 0xFF34C759, 0xFF639922,
    0xFFFF9F2E, 0xFFFF9500, 0xFFFF5B6E, 0xFFFF3B30, 0xFFA855F7,
    0xFFAF52DE, 0xFF8A8F99
)

val THEME_COLORS = listOf(
    0xFF34C759, 0xFFAF52DE, 0xFF2B7FFF, 0xFFFF3B30, 0xFFFF9500,
    0xFF639922, 0xFFA855F7, 0xFFFF5B6E, 0xFF7EC8F5
)

/* ---------------- 日期工具 ---------------- */

object Dates {
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val cnFmt = SimpleDateFormat("M月d日", Locale.CHINA)

    fun today(): String = fmt.format(Date())

    fun parse(s: String): Date? = runCatching { fmt.parse(s) }.getOrNull()

    fun addDays(s: String, n: Int): String {
        val c = Calendar.getInstance()
        c.time = parse(s) ?: Date()
        c.add(Calendar.DAY_OF_MONTH, n)
        return fmt.format(c.time)
    }

    fun cn(s: String): String = parse(s)?.let { cnFmt.format(it) } ?: s

    /** 两个日期相差的天数（to - from），用于日期刻度尺把「第几天」换算成日期。 */
    fun dayOffset(from: String, to: String): Int {
        val a = parse(from)?.time ?: return 0
        val b = parse(to)?.time ?: return 0
        return ((b - a) / 86_400_000L).toInt()
    }

    fun year(s: String): Int = parse(s)?.let {
        Calendar.getInstance().apply { time = it }.get(Calendar.YEAR)
    } ?: 2026

    fun month(s: String): Int = parse(s)?.let {
        Calendar.getInstance().apply { time = it }.get(Calendar.MONTH)
    } ?: 0

    fun day(s: String): Int = parse(s)?.let {
        Calendar.getInstance().apply { time = it }.get(Calendar.DAY_OF_MONTH)
    } ?: 1

    fun weekday(s: String): Int = parse(s)?.let {
        Calendar.getInstance().apply { time = it }.get(Calendar.DAY_OF_WEEK) - 1
    } ?: 0

    /** 某月天数 */
    fun daysInMonth(year: Int, month: Int): Int {
        val c = Calendar.getInstance()
        c.set(year, month, 1)
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** 某月 1 号是周几（0=周日） */
    fun firstWeekday(year: Int, month: Int): Int {
        val c = Calendar.getInstance()
        c.set(year, month, 1)
        return c.get(Calendar.DAY_OF_WEEK) - 1
    }

    fun ymd(year: Int, month: Int, day: Int): String =
        String.format(Locale.CHINA, "%04d-%02d-%02d", year, month + 1, day)

    val WEEK_CN = listOf("日", "一", "二", "三", "四", "五", "六")
    val WEEK_FULL = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 一次性生成刻度尺要用的「日期串 + 标签」两条平行数组。
     *
     * 刻度范围固定后一次要算 300~400 格，如果每格都走 [addDays]（parse + format）
     * 会白白解析几百次日期；这里共用同一个 Calendar 逐日递推。
     *
     * @param today   锚点日期（yyyy-MM-dd），下标 [back] 处对应「今天」
     * @param back    向过去覆盖的天数
     * @param forward 向未来覆盖的天数
     */
    fun strip(today: String, back: Int, forward: Int): Pair<List<String>, List<String>> {
        val count = back + forward + 1
        val dates = ArrayList<String>(count)
        val labels = ArrayList<String>(count)
        val c = Calendar.getInstance()
        c.time = parse(today) ?: Date()
        c.add(Calendar.DAY_OF_MONTH, -back)
        for (i in 0 until count) {
            val off = i - back
            val m = c.get(Calendar.MONTH)
            val d = c.get(Calendar.DAY_OF_MONTH)
            dates.add(String.format(Locale.CHINA, "%04d-%02d-%02d", c.get(Calendar.YEAR), m + 1, d))
            val head = when (off) {
                0 -> "今天"
                -1 -> "昨天"
                1 -> "明天"
                else -> WEEK_FULL[c.get(Calendar.DAY_OF_WEEK) - 1]
            }
            labels.add("$head ${m + 1}/$d")
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return dates to labels
    }
}

/* ---------------- 存储 ---------------- */

class AppStore(context: Context) {

    /* 自动备份要往外写文件，得留着 applicationContext */
    private val app: Context = context.applicationContext

    private val prefs = context.getSharedPreferences("daily_task_native", Context.MODE_PRIVATE)

    var tasks by mutableStateOf<Map<String, List<Task>>>(emptyMap())
        private set
    var study by mutableStateOf<Map<String, Study>>(emptyMap())
        private set
    var checkins by mutableStateOf<Set<String>>(emptySet())
        private set
    var settings by mutableStateOf(Settings())
        private set
    var subjects by mutableStateOf(DEFAULT_SUBJECTS)
        private set

    init {
        load()
        if (tasks.isEmpty() && !prefs.getBoolean("seeded", false)) seed()
        /* 每次启动兜底写一次：万一上次写完就被强杀，公共目录那份也不会太旧 */
        if (tasks.isNotEmpty()) AutoBackup.runNow(app, this)
        /* 云端同步最后挂：此时本地数据已经就位，可以直接拿它当差量基线 */
        Sync.attach(context, this)
    }

    /* ---- 查询 ---- */

    fun tasksOf(date: String): List<Task> = tasks[date] ?: emptyList()

    fun studyOf(date: String): Study = study[date] ?: Study()

    fun subjectOf(key: String): Subject =
        subjects.firstOrNull { it.key == key } ?: subjects.lastOrNull() ?: DEFAULT_SUBJECTS.last()

    fun isCheckedIn(date: String): Boolean = checkins.contains(date)

    fun progressOf(date: String): Triple<Int, Int, Float> {
        val list = tasksOf(date)
        val done = list.count { it.done }
        val rate = if (list.isEmpty()) 0f else done.toFloat() / list.size
        return Triple(list.size, done, rate)
    }

    fun streakCount(): Int {
        var count = 0
        var d = Dates.today()
        while (checkins.contains(d)) {
            count++
            d = Dates.addDays(d, -1)
        }
        return count
    }

    /* ---- 修改 ---- */

    fun addTask(date: String, title: String, subject: String, note: String, done: Boolean) {
        val list = (tasks[date] ?: emptyList()) + Task(
            id = System.currentTimeMillis().toString(36) + (0..999).random(),
            title = title,
            subject = subject,
            note = note,
            done = done,
            createdAt = System.currentTimeMillis()
        )
        tasks = tasks + (date to list)
        save()
    }

    fun updateTask(date: String, task: Task) {
        val list = (tasks[date] ?: emptyList()).map { if (it.id == task.id) task else it }
        tasks = tasks + (date to list)
        save()
    }

    fun deleteTask(date: String, id: String) {
        val list = (tasks[date] ?: emptyList()).filterNot { it.id == id }
        tasks = if (list.isEmpty()) tasks - date else tasks + (date to list)
        save()
    }

    fun toggleTask(date: String, id: String) {
        val list = (tasks[date] ?: emptyList()).map {
            if (it.id == id) it.copy(done = !it.done) else it
        }
        tasks = tasks + (date to list)
        save()
    }

    fun setStudy(date: String, s: Study) {
        study = study + (date to s)
        save()
    }

    fun toggleCheckin(date: String) {
        checkins = if (checkins.contains(date)) checkins - date else checkins + date
        save()
    }

    fun setTheme(color: Long) {
        settings = settings.copy(theme = color)
        save()
    }

    fun toggleCompletion() {
        settings = settings.copy(showCompletion = !settings.showCompletion)
        save()
    }

    fun setSort(mode: String) {
        settings = settings.copy(sort = mode)
        save()
    }

    /** 开关自动备份；打开时立刻补写一份，别等到用户下次改数据 */
    fun setAutoBackup(on: Boolean) {
        if (settings.autoBackup == on) return
        settings = settings.copy(autoBackup = on)
        save()
        if (on) AutoBackup.runNow(app, this)
    }

    /** 设置背景图；传空串清除背景图，回到内置色场 */
    fun setWallpaper(path: String) {
        settings = settings.copy(wallpaperPath = path)
        save()
    }

    /**
     * 手动排序：把 [date] 当天第 [from] 条挪到第 [to] 条的位置。
     * 直接操作存储顺序（list 里的下标），也就是「手动排序」模式下看到的顺序。
     */
    fun moveTask(date: String, from: Int, to: Int) {
        val list = tasksOf(date).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        tasks = tasks + (date to list)
        save()
    }

    /* ---- 学科标签 ---- */

    /** 新增学科；重名或空名返回 false */
    fun addSubject(name: String, color: Long): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 8) return false
        if (subjects.any { it.name == n }) return false
        val key = "s" + System.currentTimeMillis().toString(36)
        subjects = subjects + Subject(key, n, color)
        save()
        return true
    }

    /** 改名 / 换色；重名或空名返回 false */
    fun updateSubject(key: String, name: String, color: Long): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > 8) return false
        if (subjects.any { it.key != key && it.name == n }) return false
        subjects = subjects.map { if (it.key == key) it.copy(name = n, color = color) else it }
        save()
        return true
    }

    /** 该学科下有多少条任务（删除前提示用） */
    fun tasksUsingSubject(key: String): Int =
        tasks.values.sumOf { list -> list.count { it.subject == key } }

    /**
     * 删除学科。至少保留一个。
     *
     * 被删标签下的任务必须改挂到另一个标签，否则会留下指向不存在标签的悬空引用
     * （界面回落成最后一项、导出也分组错乱）。[into] 就是这个「改挂到哪儿」：
     * 由用户在删除确认里自己挑，不传时才退回剩下的第一个（老调用方/测试用）。
     */
    fun deleteSubject(key: String, into: String = ""): Boolean {
        if (subjects.size <= 1) return false
        val rest = subjects.filterNot { it.key == key }
        if (rest.isEmpty()) return false
        val target = rest.firstOrNull { it.key == into } ?: rest.first()
        subjects = rest
        tasks = tasks.mapValues { (_, list) ->
            list.map { if (it.subject == key) it.copy(subject = target.key) else it }
        }
        save()
        return true
    }

    /** 沿用上一日：复制学习详情 + 未完成任务 */
    fun carryOver(date: String): Boolean {
        val y = Dates.addDays(date, -1)
        val yStudy = studyOf(y)
        val yTasks = tasksOf(y).filterNot { it.done }
        if (yStudy.isEmpty && yTasks.isEmpty()) return false

        if (!yStudy.isEmpty) {
            study = study + (date to yStudy)
        }
        if (yTasks.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val copies = yTasks.mapIndexed { i, t ->
                t.copy(id = now.toString(36) + i, done = false, createdAt = now + i)
            }
            tasks = tasks + (date to (tasksOf(date) + copies))
        }
        save()
        return true
    }

    /**
     * 把 [date] 的未完成任务搬到 [to]。
     *
     * @param ids  只搬这些任务（用户勾选的那些）；传空集合表示「全部未完成」
     * @param keep true = 保留原来的（相当于复制一份过去，源日期一条不少）；
     *             false = 搬走，源日期只留已完成的任务
     * @return 实际处理的任务条数
     */
    fun migrate(date: String, to: String, ids: Set<String> = emptySet(), keep: Boolean = false): Int {
        if (to == date) return 0
        val undone = tasksOf(date).filterNot { it.done }
        val moving = if (ids.isEmpty()) undone else undone.filter { it.id in ids }
        if (moving.isEmpty()) return 0

        val now = System.currentTimeMillis()

        if (keep) {
            /* 复制：新 id + 新的 createdAt，源日期完全不动 */
            val copies = moving.mapIndexed { i, t ->
                t.copy(id = now.toString(36) + i, createdAt = now + i)
            }
            tasks = tasks + (to to (tasksOf(to) + copies))
        } else {
            val movingIds = moving.map { it.id }.toSet()
            val remain = tasksOf(date).filterNot { it.id in movingIds }
            tasks = if (remain.isEmpty()) tasks - date else tasks + (date to remain)
            val moved = moving.mapIndexed { i, t -> t.copy(createdAt = now + i) }
            tasks = tasks + (to to (tasksOf(to) + moved))
        }
        save()
        return moving.size
    }

    /** 记住「迁移时保留原来的」这个勾选，下次打开弹窗还是这个状态 */
    fun setMigrateKeep(keep: Boolean) {
        if (settings.migrateKeep == keep) return
        settings = settings.copy(migrateKeep = keep)
        save()
    }

    /* ---- 备份导入 ---- */

    /** 备份概况，给设置页显示「当前：N 条任务 · M 天」 */
    fun stats(): Triple<Int, Int, Int> {
        val total = tasks.values.sumOf { it.size }
        val days = tasks.count { it.value.isNotEmpty() }
        return Triple(total, days, checkins.size)
    }

    /**
     * 把一份解析好的备份写进本地存储。
     *
     * @param merge true = 只补上还没有的任务（同 id、或同一天里同名同学科的视为已有），
     *              false = 先清空再写入
     * @return 实际新增的任务条数
     */
    fun installBackup(data: BackupData, merge: Boolean): Int {
        var added = 0

        /* 首次启动会写三条示例任务（s1/s2/s3）。用户真要合并导入时，
           这几条假数据不该留下来跟真实记录混在一起——只在标题也和示例完全一致时才删。 */
        val today = Dates.today()
        val seedTitles = DEMO_SEED_TITLES + LEGACY_SEED_TITLES
        val todays = tasks[today]
        if (todays != null && todays.isNotEmpty() &&
            todays.all { it.id in DEMO_SEED_IDS && it.title in seedTitles }
        ) {
            tasks = tasks - today
        }

        if (merge) {
            val merged = tasks.toMutableMap()
            data.tasks.forEach { (date, incoming) ->
                val list = (merged[date] ?: emptyList()).toMutableList()
                val ids = list.map { it.id }.toMutableSet()
                val sigs = list.map { it.title to it.subject }.toMutableSet()
                incoming.forEach { t ->
                    val sig = t.title to t.subject
                    if (t.id !in ids && sig !in sigs) {
                        list.add(t)
                        ids.add(t.id)
                        sigs.add(sig)
                        added++
                    }
                }
                if (list.isNotEmpty()) merged[date] = list
            }
            tasks = merged
            /* 学习详情按天独一份，已经填过的那天不覆盖 */
            study = study + data.study.filterKeys { !study.containsKey(it) }
            checkins = checkins + data.checkins
            data.subjects?.let { list ->
                val keys = subjects.map { it.key }.toSet()
                val names = subjects.map { it.name }.toSet()
                val extra = list.filter { it.key !in keys && it.name !in names }
                if (extra.isNotEmpty()) subjects = subjects + extra
            }
        } else {
            tasks = data.tasks.filterValues { it.isNotEmpty() }
            study = data.study
            checkins = data.checkins
            data.subjects?.let { if (it.isNotEmpty()) subjects = it }
            data.theme?.let { settings = settings.copy(theme = it) }
            data.sort?.let { settings = settings.copy(sort = it) }
            added = tasks.values.sumOf { it.size }
        }

        /* 导入过的用户不该再看到「示例任务」，否则会混进真实记录里 */
        prefs.edit().putBoolean("seeded", true).apply()
        save()
        return added
    }

    /* ---- 云端下发 ---- */

    /**
     * 首次连接云端前调用：把首次启动那三条示例任务清掉。
     *
     * 不做这件事的后果很具体——新装设备一填同步码，云端就会多出
     * 「英语真题阅读 2 篇 + 复盘」这类假记录，然后在每台设备上真实显示。
     * 只在 id 和标题都对得上时才删，用户动过的一律保留。
     */
    fun dropDemoSeed() {
        val today = Dates.today()
        val list = tasks[today] ?: return
        val titles = DEMO_SEED_TITLES + LEGACY_SEED_TITLES
        val keep = list.filterNot { it.id in DEMO_SEED_IDS && it.title in titles }
        if (keep.size == list.size) return

        tasks = if (keep.isEmpty()) tasks - today else tasks + (today to keep)
        val st = study[today]
        if (st != null && (st == DEMO_SEED_STUDY || st == Study("8", "08:30", "图书馆", "22:00"))) {
            study = study - today
        }
        save()
    }

    /**
     * 应用服务端下发的变更。
     *
     * 只落盘、**不走 [save]**：远端改的东西不该被当成"本地新改动"再推回去，
     * 否则两台设备会来回弹同一批记录，seq 一直涨。
     *
     * 顺序（task 的 order）也要跟着云端走，否则另一台设备上拖出来的排序在本机看不到。
     *
     * @return 是否真的改动了本地数据（界面据此重绘）
     */
    fun applyRemote(changes: JSONArray?): Boolean {
        if (changes == null || changes.length() == 0) return false

        var touched = false
        var newTasks = tasks
        var newStudy = study
        var newCheckins = checkins
        var newSubjects = subjects

        val taskOrder = HashMap<String, HashMap<String, Int>>()   /* 日期 -> 任务id -> 顺序 */
        val subjOrder = HashMap<String, Int>()
        val touchedDates = HashSet<String>()

        for (i in 0 until changes.length()) {
            val c = changes.optJSONObject(i) ?: continue
            val kind = c.optString("kind")
            val id = c.optString("id")
            val body = c.optJSONObject("body") ?: JSONObject()
            val deleted = c.optBoolean("deleted", false) || c.optInt("deleted", 0) == 1

            when (kind) {
                "task" -> {
                    val date = body.optString("date").ifBlank { id.substringBefore('|') }
                    val obj = body.optJSONObject("task")
                    val tid = obj?.optString("id").orEmpty().ifBlank { id.substringAfter('|') }
                    if (date.isBlank() || tid.isBlank()) continue

                    val list = (newTasks[date] ?: emptyList()).toMutableList()
                    val at = list.indexOfFirst { it.id == tid }

                    if (deleted) {
                        if (at >= 0) {
                            list.removeAt(at)
                            touched = true
                        }
                    } else if (obj != null) {
                        val t = Task(
                            id = tid,
                            title = obj.optString("title"),
                            subject = obj.optString("subject").ifBlank { "other" },
                            note = obj.optString("note"),
                            done = obj.optBoolean("done"),
                            createdAt = obj.optLong("createdAt")
                        )
                        if (at >= 0) {
                            if (list[at] != t) {
                                list[at] = t
                                touched = true
                            }
                        } else {
                            list.add(t)
                            touched = true
                        }
                        taskOrder.getOrPut(date) { HashMap() }[tid] = body.optInt("order", Int.MAX_VALUE)
                    }

                    newTasks = if (list.isEmpty()) newTasks - date else newTasks + (date to list)
                    touchedDates.add(date)
                }

                "study" -> {
                    if (deleted) {
                        if (newStudy.containsKey(id)) {
                            newStudy = newStudy - id
                            touched = true
                        }
                    } else {
                        val s = body.optJSONObject("study") ?: continue
                        val st = Study(
                            hours = s.optString("hours"),
                            start = s.optString("start"),
                            place = s.optString("place"),
                            review = s.optString("review")
                        )
                        if (newStudy[id] != st) {
                            newStudy = newStudy + (id to st)
                            touched = true
                        }
                    }
                }

                "checkin" -> {
                    if (deleted) {
                        if (newCheckins.contains(id)) {
                            newCheckins = newCheckins - id
                            touched = true
                        }
                    } else if (id.isNotBlank() && !newCheckins.contains(id)) {
                        newCheckins = newCheckins + id
                        touched = true
                    }
                }

                "subject" -> {
                    if (deleted) {
                        if (newSubjects.size > 1 && newSubjects.any { it.key == id }) {
                            newSubjects = newSubjects.filterNot { it.key == id }
                            touched = true
                        }
                    } else {
                        val name = body.optString("name").ifBlank { id }
                        val color = Backup.parseColor(body.opt("color")) ?: 0xFF8A8F99
                        val at = newSubjects.indexOfFirst { it.key == id }
                        if (at >= 0) {
                            val old = newSubjects[at]
                            if (old.name != name || old.color != color) {
                                newSubjects = newSubjects.toMutableList().also { it[at] = Subject(id, name, color) }
                                touched = true
                            }
                        } else {
                            newSubjects = newSubjects + Subject(id, name, color)
                            touched = true
                        }
                        subjOrder[id] = body.optInt("order", Int.MAX_VALUE)
                    }
                }

                /* 设置项不跨端同步：颜色格式两端不同，壁纸是本机路径 */
                else -> Unit
            }
        }

        /* 按云端给的 order 重排受影响的那几天 */
        if (taskOrder.isNotEmpty()) {
            taskOrder.forEach { (date, om) ->
                val arr = newTasks[date] ?: return@forEach
                val sorted = arr.sortedBy { om[it.id] ?: Int.MAX_VALUE }
                if (sorted != arr) {
                    newTasks = newTasks + (date to sorted)
                    touched = true
                }
            }
        }

        if (subjOrder.isNotEmpty()) {
            val sorted = newSubjects.sortedBy { subjOrder[it.key] ?: Int.MAX_VALUE }
            if (sorted != newSubjects) {
                newSubjects = sorted
                touched = true
            }
        }

        if (!touched) return false
        tasks = newTasks
        study = newStudy
        checkins = newCheckins
        subjects = newSubjects
        persist()
        return true
    }

    /* ---- 序列化 ---- */

    /**
     * 所有写操作的唯一出口。
     *
     * 先落盘，再交给同步模块做一次「和上次基线比，哪些变了」的差量。
     * 业务代码（增删改/迁移/排序/导入）因此不需要知道自己被同步了。
     */
    private fun save() {
        persist()
        Sync.onLocalSave(this)
    }

    /** 只落盘、不产生同步差量。远端下发的变更走这里，否则会来回推同一个改动。 */
    private fun persist() {
        val root = JSONObject()

        val t = JSONObject()
        tasks.forEach { (date, list) ->
            val arr = JSONArray()
            list.forEach { task ->
                arr.put(JSONObject().apply {
                    put("id", task.id)
                    put("title", task.title)
                    put("subject", task.subject)
                    put("note", task.note)
                    put("done", task.done)
                    put("createdAt", task.createdAt)
                })
            }
            t.put(date, arr)
        }
        root.put("tasks", t)

        val s = JSONObject()
        study.forEach { (date, st) ->
            s.put(date, JSONObject().apply {
                put("hours", st.hours)
                put("start", st.start)
                put("place", st.place)
                put("review", st.review)
            })
        }
        root.put("study", s)

        root.put("checkins", JSONArray(checkins.toList()))
        root.put("settings", JSONObject().apply {
            put("theme", settings.theme)
            put("showCompletion", settings.showCompletion)
            put("sort", settings.sort)
            put("wallpaperPath", settings.wallpaperPath)
            put("migrateKeep", settings.migrateKeep)
            put("autoBackup", settings.autoBackup)
        })

        val sj = JSONArray()
        subjects.forEach { sub ->
            sj.put(JSONObject().apply {
                put("key", sub.key)
                put("name", sub.name)
                put("color", sub.color)
            })
        }
        root.put("subjects", sj)

        prefs.edit().putString("data", root.toString()).apply()

        /* 私有目录这份是「正在用的数据」，公共目录那份是「万一被卸载后的遗产」。
           每次落盘都顺手排一次自动备份（内部 2 秒防抖）。 */
        AutoBackup.schedule(app, this)
    }

    private fun load() {
        val raw = prefs.getString("data", null) ?: return
        runCatching {
            val root = JSONObject(raw)

            val t = root.optJSONObject("tasks") ?: JSONObject()
            val taskMap = mutableMapOf<String, List<Task>>()
            t.keys().forEach { date ->
                val arr = t.optJSONArray(date) ?: return@forEach
                val list = mutableListOf<Task>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Task(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            subject = o.optString("subject", "other"),
                            note = o.optString("note"),
                            done = o.optBoolean("done"),
                            createdAt = o.optLong("createdAt")
                        )
                    )
                }
                taskMap[date] = list
            }
            tasks = taskMap

            val s = root.optJSONObject("study") ?: JSONObject()
            val studyMap = mutableMapOf<String, Study>()
            s.keys().forEach { date ->
                val o = s.optJSONObject(date) ?: return@forEach
                studyMap[date] = Study(
                    hours = o.optString("hours"),
                    start = o.optString("start"),
                    place = o.optString("place"),
                    review = o.optString("review")
                )
            }
            study = studyMap

            val ck = root.optJSONArray("checkins") ?: JSONArray()
            val set = mutableSetOf<String>()
            for (i in 0 until ck.length()) set.add(ck.getString(i))
            checkins = set

            val st = root.optJSONObject("settings")
            if (st != null) {
                settings = Settings(
                    theme = st.optLong("theme", 0xFF34C759),
                    showCompletion = st.optBoolean("showCompletion", true),
                    sort = st.optString("sort", "manual").ifBlank { "manual" },
                    migrateKeep = st.optBoolean("migrateKeep", false),
                    autoBackup = st.optBoolean("autoBackup", true),
                    wallpaperPath = st.optString("wallpaperPath", "").ifBlank {
                        st.optString("wallpaperUri", "")
                    }
                )
            }

            /* 旧版本没有 subjects 字段，保持默认即可 */
            val sj = root.optJSONArray("subjects")
            if (sj != null && sj.length() > 0) {
                val list = mutableListOf<Subject>()
                for (i in 0 until sj.length()) {
                    val o = sj.optJSONObject(i) ?: continue
                    val key = o.optString("key")
                    val name = o.optString("name")
                    if (key.isBlank() || name.isBlank()) continue
                    list.add(Subject(key, name, o.optLong("color", 0xFF8A8F99)))
                }
                if (list.isNotEmpty()) subjects = list
            }
        }
    }

    private fun seed() {
        val today = Dates.today()
        val now = System.currentTimeMillis()
        tasks = mapOf(
            today to listOf(
                Task("s1", "把今天最要紧的一件事挑出来", "other", "先做它", true, now),
                Task("s2", "看书半小时", "other", "", false, now + 1),
                Task("s3", "出门走一走", "other", "", false, now + 2)
            )
        )
        study = mapOf(today to DEMO_SEED_STUDY)
        prefs.edit().putBoolean("seeded", true).apply()
        save()
    }
}
