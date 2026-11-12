package host.workbuddy.dailytasklist.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- 数据备份（JSON 导入 / 导出） ----------------

   为什么要有这个东西：APP 的数据只存在本机（SharedPreferences），
   一换手机、一卸载、或者更新时出点意外，历史记录就整没了。
   所以把全部数据序列化成一份 JSON：可以存到文件、发给别人、
   或者拿去网页版直接吃（三端共用同一份格式）。

   格式（schema = 1）：
   {
     "app": "daily-task-list",
     "schema": 1,
     "exportedAt": "2026-11-05T01:30:00+08:00",
     "tasks":    { "2026-08-26": [ { id, title, subject, note, done, createdAt } ] },
     "study":    { "2026-08-26": { hours, start, place, review } },
     "checkins": [ "2026-08-26", ... ],
     "subjects": [ { "key": "math", "name": "数学", "color": "#4C8DFF" } ],
     "settings": { "theme": "#4BC85C", "showCompletion": true, "sort": "manual" }
   }

   颜色统一写成 "#RRGGBB" 字符串：网页版本来就吃这个，原生版读的时候
   自己补上不透明的 alpha；同时也能读旧的 0xAARRGGBB 数字。
*/

object Backup {

    const val APP_ID = "daily-task-list"
    const val SCHEMA = 1

    private val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /* ---- 导出 ---- */

    fun encode(store: AppStore): String {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("schema", SCHEMA)
        root.put("exportedAt", isoNow())

        val tasks = JSONObject()
        store.tasks.keys.sorted().forEach { date ->
            val arr = JSONArray()
            store.tasks[date].orEmpty().forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("subject", t.subject)
                    put("note", t.note)
                    put("done", t.done)
                    put("createdAt", t.createdAt)
                })
            }
            tasks.put(date, arr)
        }
        root.put("tasks", tasks)

        val study = JSONObject()
        store.study.keys.sorted().forEach { date ->
            val s = store.study[date] ?: return@forEach
            study.put(date, JSONObject().apply {
                put("hours", s.hours)
                put("start", s.start)
                put("place", s.place)
                put("review", s.review)
            })
        }
        root.put("study", study)

        val checkins = JSONArray()
        store.checkins.sorted().forEach { checkins.put(it) }
        root.put("checkins", checkins)

        val subjects = JSONArray()
        store.subjects.forEach { s ->
            subjects.put(JSONObject().apply {
                put("key", s.key)
                put("name", s.name)
                put("color", colorToHex(s.color))
            })
        }
        root.put("subjects", subjects)

        root.put("settings", JSONObject().apply {
            put("theme", colorToHex(store.settings.theme))
            put("showCompletion", store.settings.showCompletion)
            put("sort", store.settings.sort)
        })

        return root.toString(1)
    }

    /* ---- 导入 ---- */

    /** 解析备份文本；格式不对时抛 IllegalStateException（消息可直接给用户看）。 */
    fun decode(raw: String): BackupData {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw IllegalStateException("文件是空的")
        if (!text.startsWith("{")) throw IllegalStateException("这不是 JSON 备份文件（可能选成了图片或 PDF）")

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("JSON 格式不完整，文件可能被截断了")
        }

        val app = root.optString("app")
        if (app.isNotEmpty() && app != APP_ID) {
            throw IllegalStateException("这是「$app」的备份，不是每日任务清单的")
        }
        val tasksObj = root.optJSONObject("tasks")
            ?: throw IllegalStateException("备份里没有任务数据（缺少 tasks 字段）")

        val tasks = linkedMapOf<String, List<Task>>()
        tasksObj.keys().forEach { date ->
            if (!DATE_RE.matches(date)) return@forEach
            val arr = tasksObj.optJSONArray(date) ?: return@forEach
            val list = ArrayList<Task>(arr.length())
            val base = dayStartMs(date)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                val id = o.optString("id").ifBlank { "imp-" + date.replace("-", "") + "-" + i }
                list.add(
                    Task(
                        id = id,
                        title = title,
                        subject = o.optString("subject").ifBlank { "other" },
                        note = o.optString("note"),
                        done = o.optBoolean("done"),
                        createdAt = o.optLong("createdAt").takeIf { it > 0 } ?: (base + i * 1000L)
                    )
                )
            }
            if (list.isNotEmpty()) tasks[date] = list
        }
        if (tasks.isEmpty()) throw IllegalStateException("备份里没有任何有效任务")

        val study = linkedMapOf<String, Study>()
        root.optJSONObject("study")?.let { obj ->
            obj.keys().forEach { date ->
                val o = obj.optJSONObject(date) ?: return@forEach
                val s = Study(
                    hours = o.optString("hours"),
                    start = o.optString("start"),
                    place = o.optString("place"),
                    review = o.optString("review")
                )
                if (!s.isEmpty) study[date] = s
            }
        }

        val checkins = linkedSetOf<String>()
        when (val ck = root.opt("checkins")) {
            is JSONArray -> for (i in 0 until ck.length()) {
                val d = ck.optString(i)
                if (DATE_RE.matches(d)) checkins.add(d)
            }
            /* 网页版早期把打卡存成 { "2026-11-01": { time } } 的对象 */
            is JSONObject -> ck.keys().forEach { d -> if (DATE_RE.matches(d)) checkins.add(d) }
        }

        val subjects = root.optJSONArray("subjects")?.let { arr ->
            val list = ArrayList<Subject>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key")
                val name = o.optString("name")
                if (key.isBlank() || name.isBlank()) continue
                list.add(Subject(key, name, parseColor(o.opt("color")) ?: 0xFF8A8F99))
            }
            list.ifEmpty { null }
        }

        val settings = root.optJSONObject("settings")

        return BackupData(
            tasks = tasks,
            study = study,
            checkins = checkins,
            subjects = subjects,
            theme = parseColor(settings?.opt("theme")),
            sort = settings?.optString("sort")?.ifBlank { null },
            source = root.optString("source").ifBlank { "app" }
        )
    }

    /* ---- 颜色 ---- */

    fun colorToHex(c: Long): String = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    fun parseColor(v: Any?): Long? {
        val raw = when (v) {
            null -> return null
            is Number -> return normalize(v.toLong())
            else -> v.toString().trim()
        }
        if (raw.isEmpty()) return null
        val n = when {
            raw.startsWith("#") -> raw.drop(1).toLongOrNull(16)
            raw.startsWith("0x", true) -> raw.drop(2).toLongOrNull(16)
            else -> raw.toLongOrNull()
        } ?: return null
        return normalize(n)
    }

    private fun normalize(c: Long): Long = if (c <= 0xFFFFFFL) c or 0xFF000000L else c

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun dayStartMs(date: String): Long =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse("$date 08:00")?.time
        }.getOrNull() ?: System.currentTimeMillis()
}

/** 解析后的备份，顺带算好统计信息给确认弹窗用 */
data class BackupData(
    val tasks: Map<String, List<Task>>,
    val study: Map<String, Study>,
    val checkins: Set<String>,
    val subjects: List<Subject>?,
    val theme: Long?,
    val sort: String?,
    val source: String
) {
    val total: Int get() = tasks.values.sumOf { it.size }
    val done: Int get() = tasks.values.sumOf { list -> list.count { it.done } }
    val days: Int get() = tasks.size
    val from: String get() = tasks.keys.minOrNull() ?: ""
    val to: String get() = tasks.keys.maxOrNull() ?: ""
}

/**
 * 导入确认：先把「这份文件里有什么」摆给用户看，再让他选覆盖还是合并。
 * 不做静默导入——覆盖会清掉本机现有记录，必须让用户知道自己按的是什么。
 */
@Composable
fun BackupImportDialog(
    data: BackupData,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onMerge: () -> Unit
) {
    IosBottomSheet(onDismiss = onDismiss, title = "导入备份") {
        Text(
            "${data.days} 天 · ${data.total} 条任务 · 已完成 ${data.done} 条",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${data.from} ~ ${data.to}",
            fontSize = 12.5.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "「合并」只会补上 APP 里还没有的任务，不动现有记录；" +
                "「覆盖」会先清空当前全部数据再写入。",
            fontSize = 11.5.sp,
            color = TextMuted,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(16.dp))

        PrimaryButton("合并导入（推荐）", Modifier.fillMaxWidth(), onClick = onMerge)
        Spacer(Modifier.height(8.dp))
        GhostButton("覆盖导入（清空后写入）", Modifier.fillMaxWidth(), color = AccentRed, onClick = onReplace)
        Spacer(Modifier.height(8.dp))
        GhostButton("取消", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}
