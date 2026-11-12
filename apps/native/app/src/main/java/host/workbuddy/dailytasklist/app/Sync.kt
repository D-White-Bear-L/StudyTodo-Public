package host.workbuddy.dailytasklist.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/* ---------------- 数据模型 ---------------- */

data class SyncCfg(
    val token: String = "",
    val enabled: Boolean = true,
    /* 服务端游标：只拉 seq 大于它的变更。用游标而不是时间戳，手机时钟不准也不会漏同步 */
    val lastSeq: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastError: String = "",
    /* 是否完成过首次合并（首次要「先拉全量再把本地补上去」，和之后的增量不同） */
    val bootDone: Boolean = false
) {
    val connected: Boolean get() = token.isNotBlank()
}

/** 一条待同步记录，key 里 `kind|id` 的 id 对任务来说是 `日期|任务id` */
private data class Rec(val kind: String, val id: String, val body: JSONObject)

/**
 * 云端同步（原生版）
 *
 * 设计目标只有一个：**改动量最小的接入方式**。
 * AppStore 所有写操作最后都会落到 `save()` 一处，所以只要在那里做一次
 * 「当前数据 vs 上次基线」的差量，就能推导出「哪些记录变了、哪些被删了」，
 * 业务代码（增删改、迁移、排序、导入）一行都不用动。
 *
 * 三条规则与网页版完全一致：
 *   1. 每条记录带 updatedAt，服务端同 id 取时间大的（后写赢）
 *   2. 删除打墓碑（deleted=1）而不是物理消失，否则别的设备会把它「复活」
 *   3. 用服务端自增 seq 当游标，客户端只记 lastSeq
 *
 * 设置项（主题色/排序/壁纸）**不参与同步**：两端颜色格式不同（网页用 #RRGGBB，
 * 原生用 ARGB Long），壁纸是本机文件路径，同步过去没有意义还容易互相覆盖。
 */
object Sync {

    /** 服务端地址由构建配置注入（项目根 .env 的 APP_BASE_URL），仓库里不写死域名 */
    val DEFAULT_BASE: String = BuildConfig.SYNC_BASE_URL

    /** 服务端地址。正常使用固定不变；测试里可以指向本机起的实例 */
    var baseUrl: String = DEFAULT_BASE

    private const val PREFS = "todo_sync_native"
    private const val K_CFG = "cfg"
    private const val K_PENDING = "pending"

    var cfg by mutableStateOf(SyncCfg())
        private set

    var syncing by mutableStateOf(false)
        private set

    private var store: AppStore? = null
    private var prefs: SharedPreferences? = null

    /** 上次同步完成时的数据快照：key -> 规范化后的 body，用来算差量 */
    private var base: Map<String, String> = emptyMap()
    private var ready = false

    /** 打卡记录只在本机保存「日期」，云端 body 里还带 time（网页版记的），推送时原样带回避免覆盖 */
    private val checkinTimes = HashMap<String, String>()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "todo-sync").apply { isDaemon = true }
    }

    private val tick = Runnable { launch(bootstrap = false) }

    /* ---------------- 生命周期 ---------------- */

    /** AppStore 初始化完成后调用：读配置、建立基线、排一次开机同步 */
    fun attach(context: Context, s: AppStore) {
        store = s
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        cfg = readCfg(p)
        base = canonMap(snapshot(s))
        ready = true
        if (cfg.bootDone && cfg.enabled && cfg.connected) main.postDelayed(tick, 800)
    }

    /** 切回前台补一次，手机锁屏期间别的设备改的东西回来就能看到 */
    fun onForeground() {
        if (!ready) return
        if (!cfg.bootDone || !cfg.enabled || !cfg.connected) return
        main.removeCallbacks(tick)
        main.postDelayed(tick, 600)
    }

    /* ---------------- 配置 ---------------- */

    /**
     * 填入同步码。返回 true 表示这次是首次连接（需要跑一遍全量合并）。
     * 同步码没变就什么都不做，避免重复全量拉取。
     */
    fun connect(token: String): Boolean {
        val t = token.trim()
        if (t == cfg.token && cfg.bootDone) return false
        cfg = cfg.copy(token = t, bootDone = false, lastSeq = 0L, lastError = "")
        saveCfg()
        return true
    }

    fun setEnabled(on: Boolean) {
        cfg = cfg.copy(enabled = on)
        saveCfg()
        if (on && cfg.bootDone) schedule(300)
    }

    fun disconnect() {
        main.removeCallbacks(tick)
        cfg = cfg.copy(token = "", bootDone = false, lastSeq = 0L, lastError = "", lastSyncAt = 0L)
        saveCfg()
        clearPending()
    }

    /** 手动点「立即同步」：有未完成首次合并就走合并，否则走增量 */
    fun syncNow() {
        if (!cfg.connected) return
        main.removeCallbacks(tick)
        if (!cfg.bootDone) launch(bootstrap = true) else launch(bootstrap = false)
    }

    /** 还有多少条本地改动没传上去 */
    fun pendingCount(): Int = readPending().size

    /** 排查用：待传队列的内容摘要 */
    fun pendingDebug(): String = readPending().joinToString("; ") {
        it.optString("kind") + ":" + it.optString("id") + ":" + it.optInt("deleted", 0)
    }

    fun schedule(delayMs: Long) {
        if (!ready || !cfg.enabled || !cfg.connected || !cfg.bootDone) return
        main.removeCallbacks(tick)
        main.postDelayed(tick, delayMs)
    }

    /** 给设置页显示的一行状态 */
    fun statusText(): String {
        if (!cfg.connected) return "未连接"
        if (syncing) return "正在同步…"
        if (cfg.lastError.isNotBlank()) return "同步失败：" + cfg.lastError
        if (!cfg.bootDone) return "等待首次同步"
        if (cfg.lastSyncAt <= 0L) return "已连接，还没同步过"
        val count = pendingCount()
        val when_ = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(cfg.lastSyncAt))
        return if (count > 0) "上次 $when_ · 还有 $count 条待上传" else "上次 $when_ · 已是最新"
    }

    /* ---------------- 本地改动 → 差量 ---------------- */

    /**
     * 由 AppStore.save() 调用。和上次基线对比，把变了的记录塞进待传队列。
     *
     * 没连云端时不排队：首次连接会把本地数据整份「以最旧时间戳」补上去，
     * 排了也没用，反而会在设置同步码之前堆一堆无意义的记录。
     */
    fun onLocalSave(s: AppStore) {
        /* 只认当前这台设备。构造 AppStore 时是先 load/播种、后 attach，
           这中间如果还有上一个实例在，它的写入不该被算成本机改动。 */
        if (s !== store) return
        if (!ready || !cfg.connected || !cfg.bootDone) return

        val recs = snapshot(s)
        val cur = canonMap(recs)
        val now = System.currentTimeMillis()
        val changes = ArrayList<JSONObject>()

        recs.forEach { (key, rec) ->
            if (base[key] != cur[key]) changes.add(changeJson(rec, now, false))
        }
        base.keys.forEach { key ->
            if (!cur.containsKey(key)) {
                val (kind, id) = splitKey(key)
                changes.add(
                    JSONObject().apply {
                        put("kind", kind); put("id", id)
                        put("body", JSONObject())
                        put("updatedAt", now); put("deleted", 1)
                    }
                )
            }
        }

        base = cur
        if (changes.isEmpty()) return
        mergePending(changes)
        schedule(1200)
    }

    /* ---------------- 同步主流程 ---------------- */

    private fun launch(bootstrap: Boolean) {
        val s = store ?: return
        val c = cfg
        if (c.token.isBlank()) return
        if (!bootstrap && (!c.bootDone || !c.enabled)) return
        if (syncing) return

        syncing = true
        /* 首次合并前把「示例任务」扔掉，否则新装设备一连接就把三条假记录塞进云端 */
        if (bootstrap) s.dropDemoSeed()
        val token = c.token
        val since = if (bootstrap) 0L else c.lastSeq
        /* 首次合并：把本地全部记录以 updatedAt=0 推上去。
           服务端只在「这条不存在」时插入，已存在的保留云端版本 —— 于是历史数据不会被本机旧副本冲掉。 */
        val sent = if (bootstrap) allLocalChanges(s) else takePending()

        worker.execute {
            val result = runCatching { post(token, since, sent) }
            main.post {
                result.onSuccess { finish(s, it, bootstrap) }
                    .onFailure { fail(it, sent, bootstrap) }
            }
        }
    }

    private fun finish(s: AppStore, res: JSONObject, bootstrap: Boolean) {
        val changes = res.optJSONArray("changes")

        /* 先记下云端的打卡时间，等下推送时原样带回 */
        if (changes != null) {
            for (i in 0 until changes.length()) {
                val c = changes.optJSONObject(i) ?: continue
                if (c.optString("kind") == "checkin" && !isDeleted(c)) {
                    val id = c.optString("id")
                    val t = c.optJSONObject("body")?.optString("time") ?: ""
                    if (id.isNotBlank() && t.isNotBlank()) checkinTimes[id] = t
                }
            }
        }

        s.applyRemote(changes)

        cfg = cfg.copy(
            lastSeq = if (res.has("seq")) res.optLong("seq") else cfg.lastSeq,
            lastSyncAt = if (res.has("now")) res.optLong("now") else System.currentTimeMillis(),
            lastError = "",
            bootDone = true
        )
        saveCfg()
        base = canonMap(snapshot(s))
        syncing = false

        /* 一次拉不完（服务端单次上限）就接着拉 */
        if (res.optBoolean("hasMore", false)) {
            main.postDelayed(tick, 150)
            return
        }
        /* 同步途中用户又改了东西，队列里还有就再排一次 */
        if (bootstrap && readPending().isNotEmpty()) {
            schedule(800)
        } else if (!bootstrap && readPending().isNotEmpty()) {
            schedule(1200)
        }
    }

    private fun fail(err: Throwable, sent: List<JSONObject>, bootstrap: Boolean) {
        if (!bootstrap) restorePending(sent)
        syncing = false
        val msg = (err.message ?: "").ifBlank { "网络不可用" }
        cfg = cfg.copy(lastError = msg)
        saveCfg()
    }

    /* ---------------- 网络 ---------------- */

    /**
     * 发一次同步请求。
     *
     * 只用 [baseUrl] 这一个地址，不做 IP 兜底 —— 客户端里一旦写死了服务器 IP，
     * 这个包发出去谁都能从里面读出入口地址，等于把门牌号贴在门上。
     */
    private fun post(token: String, since: Long, changes: List<JSONObject>): JSONObject =
        postTo(baseUrl.trimEnd('/'), token, since, changes)

    private fun postTo(base: String, token: String, since: Long, changes: List<JSONObject>): JSONObject {
        val url = URL(base + "/sync")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        val payload = JSONObject().apply {
            put("since", since)
            put("changes", JSONArray(changes))
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code == 401) throw IllegalStateException("同步码不正确")
            if (code !in 200..299) throw IllegalStateException("服务器返回 $code")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /* ---------------- 首次合并用：本地全量 ---------------- */

    private fun allLocalChanges(s: AppStore): List<JSONObject> =
        snapshot(s).values.map { changeJson(it, 0L, false) }

    private fun changeJson(rec: Rec, at: Long, deleted: Boolean): JSONObject =
        JSONObject().apply {
            put("kind", rec.kind)
            put("id", rec.id)
            put("body", if (deleted) JSONObject() else rec.body)
            put("updatedAt", at)
            put("deleted", if (deleted) 1 else 0)
        }

    /* ---------------- 数据快照 ---------------- */

    /**
     * 把 AppStore 摊平成「记录表」，key 与网页版/服务端完全一致：
     *   task|2026-11-04|mw0zo4oo166 / study|2026-11-04 / checkin|2026-11-04 / subject|math
     */
    private fun snapshot(s: AppStore): LinkedHashMap<String, Rec> {
        val out = LinkedHashMap<String, Rec>()

        s.tasks.forEach { (date, list) ->
            list.forEachIndexed { index, t ->
                val body = JSONObject().apply {
                    put("date", date)
                    put("order", index)
                    put(
                        "task",
                        JSONObject().apply {
                            put("id", t.id)
                            put("title", t.title)
                            put("subject", t.subject)
                            put("note", t.note)
                            put("done", t.done)
                            put("createdAt", t.createdAt)
                        }
                    )
                }
                out["task|$date|${t.id}"] = Rec("task", "$date|${t.id}", body)
            }
        }

        s.study.forEach { (date, st) ->
            if (st.isEmpty) return@forEach
            val body = JSONObject().apply {
                put("date", date)
                put(
                    "study",
                    JSONObject().apply {
                        put("hours", st.hours)
                        put("start", st.start)
                        put("place", st.place)
                        put("review", st.review)
                    }
                )
            }
            out["study|$date"] = Rec("study", date, body)
        }

        s.checkins.forEach { date ->
            val body = JSONObject().apply {
                put("date", date)
                put("time", checkinTimes[date] ?: "")
            }
            out["checkin|$date"] = Rec("checkin", date, body)
        }

        s.subjects.forEachIndexed { index, sub ->
            val body = JSONObject().apply {
                put("key", sub.key)
                put("name", sub.name)
                put("color", Backup.colorToHex(sub.color))
                put("order", index)
            }
            out["subject|${sub.key}"] = Rec("subject", sub.key, body)
        }

        return out
    }

    /**
     * 规范化：同一份内容不论 key 顺序都要算出同一个串，否则会把「没变」误判成「变了」。
     * 打卡记录只比「有没有这一条」，不比时间（时间由网页版维护，本机只是捎带）。
     */
    private fun canonMap(recs: Map<String, Rec>): Map<String, String> =
        recs.mapValues { (key, rec) ->
            if (rec.kind == "checkin") "1" else canonical(rec.body)
        }

    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { k -> k + ":" + canonical(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }

    private fun splitKey(key: String): Pair<String, String> =
        key.substringBefore('|') to key.substringAfter('|')

    private fun isDeleted(o: JSONObject): Boolean =
        o.optBoolean("deleted", false) || o.optInt("deleted", 0) == 1

    /* ---------------- 待传队列（落盘，离线改动不丢） ---------------- */

    private fun readPending(): List<JSONObject> {
        val p = prefs ?: return emptyList()
        val raw = p.getString(K_PENDING, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writePending(list: List<JSONObject>) {
        val p = prefs ?: return
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        p.edit().putString(K_PENDING, arr.toString()).apply()
    }

    /** 同一条记录只留最新那次改动 */
    private fun mergePending(changes: List<JSONObject>) {
        val map = LinkedHashMap<String, JSONObject>()
        readPending().forEach { map[keyOf(it)] = it }
        changes.forEach { c ->
            val k = keyOf(c)
            val old = map[k]
            if (old == null || c.optLong("updatedAt") >= old.optLong("updatedAt")) map[k] = c
        }
        writePending(map.values.toList())
    }

    private fun takePending(): List<JSONObject> {
        val list = readPending()
        writePending(emptyList())
        return list
    }

    private fun restorePending(changes: List<JSONObject>) {
        if (changes.isEmpty()) return
        mergePending(changes)
    }

    private fun clearPending() = writePending(emptyList())

    private fun keyOf(o: JSONObject): String = o.optString("kind") + "|" + o.optString("id")

    /* ---------------- 配置读写 ---------------- */

    private fun readCfg(p: SharedPreferences): SyncCfg {
        val raw = p.getString(K_CFG, null) ?: return SyncCfg()
        return runCatching {
            val o = JSONObject(raw)
            SyncCfg(
                token = o.optString("token"),
                enabled = o.optBoolean("enabled", true),
                lastSeq = o.optLong("lastSeq"),
                lastSyncAt = o.optLong("lastSyncAt"),
                lastError = o.optString("lastError"),
                bootDone = o.optBoolean("bootDone")
            )
        }.getOrDefault(SyncCfg())
    }

    private fun saveCfg() {
        val p = prefs ?: return
        val o = JSONObject().apply {
            put("token", cfg.token)
            put("enabled", cfg.enabled)
            put("lastSeq", cfg.lastSeq)
            put("lastSyncAt", cfg.lastSyncAt)
            put("lastError", cfg.lastError)
            put("bootDone", cfg.bootDone)
        }
        p.edit().putString(K_CFG, o.toString()).apply()
    }
}
