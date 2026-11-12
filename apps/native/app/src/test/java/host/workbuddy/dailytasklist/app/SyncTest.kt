package host.workbuddy.dailytasklist.app

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端同步端到端测试。
 *
 * 不是 mock：测试启动一份**真的**同步服务（server/sync_server.py，独立临时库），
 * 然后用真的 AppStore + 真的 HTTP 请求跑完整流程。所以它能抓到
 * 「JSON 字段名对不上」「游标用错」「删除没打墓碑」这类只有联调才会暴露的问题。
 *
 * 两台设备用「清空 SharedPreferences 后重建 AppStore」来模拟——
 * 同一份 prefs 只能有一台设备，所以按顺序切换。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncTest {

    companion object {
        private const val PORT = 18171
        private const val TOKEN = "test-token-abc123"
        private lateinit var server: Process
        private lateinit var workDir: File

        @BeforeClass
        @JvmStatic
        fun startServer() {
            val script = findServerScript()
            workDir = File(System.getProperty("java.io.tmpdir"), "todo-sync-test-${System.nanoTime()}")
            workDir.mkdirs()
            File(workDir, "token").writeText(TOKEN)

            server = ProcessBuilder("python", script.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(workDir, "server.log"))
                .apply {
                    environment()["TODO_DB"] = File(workDir, "t.db").absolutePath
                    environment()["TODO_TOKEN"] = File(workDir, "token").absolutePath
                    environment()["TODO_BIND"] = "127.0.0.1"
                    environment()["TODO_PORT"] = PORT.toString()
                }
                .start()

            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline) {
                if (runCatching { get("/health") }.isSuccess) return
                Thread.sleep(150)
            }
            throw IllegalStateException("同步服务没能启动，日志见 " + File(workDir, "server.log"))
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            if (::server.isInitialized) server.destroy()
        }

        private fun findServerScript(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val f = File(dir, "server/sync_server.py")
                if (f.isFile) return f
                dir = dir.parentFile
            }
            throw IllegalStateException("找不到 server/sync_server.py")
        }

        private fun get(path: String): String {
            val conn = URL("http://127.0.0.1:$PORT$path").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        private fun post(payload: JSONObject): JSONObject {
            val conn = URL("http://127.0.0.1:$PORT/sync").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            return JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        }

        private fun allRecords(): List<JSONObject> {
            val arr = JSONObject(get("/sync?since=0")).optJSONArray("changes") ?: JSONArray()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }

        private fun liveTasks(): List<JSONObject> =
            allRecords().filter { it.optString("kind") == "task" && it.optInt("deleted", 0) == 0 }
    }

    /* ---------------- 设备辅助 ---------------- */

    /** 清掉两份 prefs：等价于「这台设备从来没连过」 */
    private fun wipe(): Context {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("daily_task_native", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("todo_sync_native", Context.MODE_PRIVATE).edit().clear().commit()
        return ctx
    }

    private fun device(): AppStore = AppStore(wipe())

    /** Robolectric 的主线程不会自己跑，得手动推；同时等后台线程把请求做完 */
    private fun waitSync(bootstrap: Boolean = false, timeoutMs: Long = 25_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!Sync.syncing && Sync.pendingCount() == 0 && (!bootstrap || Sync.cfg.bootDone)) return
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        fail(
            "同步超时：syncing=${Sync.syncing} pending=${Sync.pendingCount()} " +
                "bootDone=${Sync.cfg.bootDone} err=${Sync.cfg.lastError} " +
                "队列=${Sync.pendingDebug()}"
        )
    }

    /**
     * 切到另一台设备：把它自己的游标写回配置再挂接。
     *
     * 真机上每台设备有独立的 SharedPreferences，游标天然各存各的；
     * 测试里只有一个进程，所以得手动把这台设备「离开时的游标」还原回去，
     * 否则新设备刚推上去的 seq 会被旧设备当成「已经看过」而漏拉。
     */
    private fun switchTo(store: AppStore, cursor: Long) {
        setCursor(cursor)
        Sync.attach(RuntimeEnvironment.getApplication(), store)
        Sync.syncNow()
        waitSync()
    }

    private fun setCursor(seq: Long) {
        val p = RuntimeEnvironment.getApplication()
            .getSharedPreferences("todo_sync_native", Context.MODE_PRIVATE)
        val raw = p.getString("cfg", null) ?: return
        val o = JSONObject(raw)
        o.put("lastSeq", seq)
        p.edit().putString("cfg", o.toString()).commit()
    }

    private fun connectAndSync(store: AppStore) {
        Sync.baseUrl = "http://127.0.0.1:$PORT"
        Sync.connect(TOKEN)
        Sync.syncNow()
        waitSync(bootstrap = true)
        assertEquals("同步不该报错", "", Sync.cfg.lastError)
        assertTrue("应该标记为已完成首次同步", Sync.cfg.bootDone)
    }

    /* ---------------- 主流程 ---------------- */

    @Test
    fun end_to_end() {
        val today = Dates.today()

        /* ---- 1. 设备 A 首次连接：示例任务不能上云 ---- */
        val a = device()
        assertEquals("新设备有 3 条示例任务", 3, a.tasksOf(today).size)
        connectAndSync(a)
        assertEquals("示例任务不该被推到云端", 0, liveTasks().size)
        assertEquals("本地示例也该被清掉", 0, a.tasksOf(today).size)

        /* ---- 2. A 新增任务 → 云端应出现 ---- */
        a.addTask(today, "买牛奶", "other", "", false)
        Sync.syncNow()
        waitSync()
        assertEquals(1, liveTasks().size)
        assertEquals("买牛奶", liveTasks()[0].getJSONObject("body").getJSONObject("task").getString("title"))
        val cursorA = Sync.cfg.lastSeq

        /* ---- 3. 设备 B（全新安装）连接 → 应该拉到 A 的数据 ---- */
        val b = device()
        assertEquals(3, b.tasksOf(today).size)      /* 又是示例任务 */
        connectAndSync(b)
        val fromCloud = b.tasksOf(today)
        assertEquals("B 应该只剩云端那一条", 1, fromCloud.size)
        assertEquals("买牛奶", fromCloud[0].title)
        assertEquals("示例任务不能留在 B 上，也不能上云", 1, liveTasks().size)
        val cursorB = Sync.cfg.lastSeq

        /* ---- 4. B 勾选完成 → A 拉下来应看到 ---- */
        val taskId = fromCloud[0].id
        b.toggleTask(today, taskId)
        Sync.syncNow()
        waitSync()
        assertTrue("云端应记为已完成", liveTasks()[0].getJSONObject("body").getJSONObject("task").getBoolean("done"))

        switchTo(a, cursorA)      /* 相当于切回 A 这台设备 */
        val onA = a.tasksOf(today).firstOrNull { it.id == taskId }
        assertTrue("A 上应该已经显示完成", onA?.done == true)

        /* ---- 5. 别处改了标题 → A 拉到新版本（后写赢） ---- */
        val remoteEdit = JSONObject().apply {
            put("kind", "task")
            put("id", "$today|$taskId")
            put("body", JSONObject().apply {
                put("date", today)
                put("order", 0)
                put("task", JSONObject().apply {
                    put("id", taskId)
                    put("title", "买牛奶（改了）")
                    put("subject", "other")
                    put("note", "")
                    put("done", true)
                    put("createdAt", onA?.createdAt ?: 0L)
                })
            })
            put("updatedAt", System.currentTimeMillis() + 800)
            put("deleted", 0)
        }
        post(JSONObject().put("since", 0).put("changes", JSONArray().put(remoteEdit)))
        Sync.syncNow()
        waitSync()
        assertEquals("买牛奶（改了）", a.tasksOf(today).first { it.id == taskId }.title)

        /* ---- 6. A 删除 → 云端打墓碑，B 拉下来不会复活 ---- */
        /* 上一步那条改动的时间戳比现在略晚，等它「过去」再删，
           否则删除会被后写覆盖挡下来（这正是服务端按时间比较的效果） */
        Thread.sleep(1200)
        a.deleteTask(today, taskId)
        Sync.syncNow()
        waitSync()
        assertEquals("云端不该再有有效任务", 0, liveTasks().size)
        assertTrue(
            "删除应该留下墓碑",
            allRecords().any { it.optString("kind") == "task" && it.optInt("deleted", 0) == 1 }
        )

        switchTo(b, cursorB)
        assertEquals("B 上的这条应该被同步删除", 0, b.tasksOf(today).count { it.id == taskId })

        /* ---- 7. 收尾：本地队列干净、游标在推进 ---- */
        assertFalse(Sync.syncing)
        assertEquals(0, Sync.pendingCount())
        assertTrue("seq 应该在推进", Sync.cfg.lastSeq > 0)
    }
}
