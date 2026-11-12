package host.workbuddy.dailytasklist.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.Calendar
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/* ---------------- 自动备份 ----------------

   应用的数据全在私有目录（SharedPreferences），这个目录只有应用自己能读，
   系统也不允许任何外部工具动它 —— 好处是安全，代价是**一卸载就整片删掉**。
   2026-11-12 就是这么丢的：更新包的签名和已装的不一致，系统要求先卸载，
   卸载即清空，历史记录连同打卡一起没了，而且没有任何回收站。

   所以这里做一份「应用外」的副本：每隔一小段时间，把整份数据写进公共下载目录
   `下载/每日任务清单备份/`。公共目录不归应用管，卸载、清数据、换包都动不到它，
   真出事了从「下载」里挑最近那份导回来就行。

   写两份：
     - 自动备份.json              永远是最新的一份（勾一次任务就更新）
     - 自动备份-2026-11-12.json   当天第一次写时建，之后覆盖，保留最近 N 天

   写入走 MediaStore（minSdk 33，不需要任何存储权限）。
*/

object AutoBackup {

    const val FOLDER = "每日任务清单备份"
    private const val LATEST = "自动备份.json"
    private const val PREFS = "auto_backup"
    private const val KEY_AT = "last_at"

    /** 只留最近这么多天的每日快照，再老的自动删掉，免得越攒越多 */
    private const val KEEP_DAYS = 10

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "auto-backup").apply { isDaemon = true }
    }

    private var pending: ScheduledFuture<*>? = null

    /** 上次写成的时间（毫秒），0 = 还没写过 */
    fun lastAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)

    /**
     * 每次改完数据调一下。延迟 2 秒合并写入 ——
     * 连续勾十个任务不会往磁盘怼十份 80KB 的文件。
     */
    fun schedule(ctx: Context, store: AppStore) {
        if (!store.settings.autoBackup) return
        val app = ctx.applicationContext
        synchronized(this) {
            pending?.cancel(false)
            runCatching {
                pending = exec.schedule(
                    { runCatching { write(app, store) } },
                    2,
                    TimeUnit.SECONDS
                )
            }
        }
    }

    /** 立刻写一次（设置页的「现在备份一次」、以及打开应用时兜底） */
    fun runNow(ctx: Context, store: AppStore) {
        if (!store.settings.autoBackup) return
        val app = ctx.applicationContext
        runCatching { exec.execute { runCatching { write(app, store) } } }
    }

    private fun write(ctx: Context, store: AppStore) {
        /* 一条任务都没有就别去覆盖旧备份 —— 新装/清空后第一次启动会走到这里，
           真把「空的」写进去，等于把用户唯一的那份遗产抹了。 */
        if (store.tasks.values.all { it.isEmpty() }) return
        val json = Backup.encode(store)
        if (json.length < 16) return

        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"

        putText(resolver, collection, rel, LATEST, json)
        putText(resolver, collection, rel, "自动备份-${Dates.today()}.json", json)

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_AT, System.currentTimeMillis()).apply()

        prune(resolver, collection, rel)
    }

    private fun putText(
        resolver: ContentResolver,
        collection: Uri,
        rel: String,
        name: String,
        text: String
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val existing = find(resolver, collection, rel, name)
        if (existing != null) {
            /* "wt" = 截断重写，否则内容变短时尾部会留着上一次的旧字节 */
            resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) }
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        } finally {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    private fun find(
        resolver: ContentResolver,
        collection: Uri,
        rel: String,
        name: String
    ): Uri? {
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            sel,
            arrayOf(name, rel),
            null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    /** 删掉过期的每日快照。文件名是我们自己定的，只认「自动备份-yyyy-MM-dd.json」 */
    private fun prune(resolver: ContentResolver, collection: Uri, rel: String) {
        val cutoff = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -KEEP_DAYS)
        }.timeInMillis

        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        resolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME
            ),
            sel,
            arrayOf(rel),
            null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val date = SNAPSHOT_RE.find(name)?.groupValues?.get(1) ?: continue
                val at = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .parse(date)?.time
                }.getOrNull() ?: continue
                if (at < cutoff) {
                    runCatching {
                        resolver.delete(
                            ContentUris.withAppendedId(collection, c.getLong(idCol)),
                            null, null
                        )
                    }
                }
            }
        }
    }

    private val SNAPSHOT_RE = Regex("""^自动备份-(\d{4}-\d{2}-\d{2})\.json$""")
}
