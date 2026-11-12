package host.workbuddy.dailytasklist.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 数据层逻辑测试：排序换位、学科增删、颜色工具。
 * 这些是最容易算错下标 / 丢数据的地方，用测试守住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreTest {

    private fun store(): AppStore = AppStore(RuntimeEnvironment.getApplication())

    /** 备份相关用例要断言精确条数，先把 SharedPreferences 清空，免得被别的用例留下来的数据干扰 */
    private fun freshStore(): AppStore {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("daily_task_native", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        return AppStore(ctx)
    }

    private fun titles(s: AppStore, date: String) = s.tasksOf(date).map { it.title }

    /* ---------------- 手动排序 ---------------- */

    @Test
    fun moveTask_down() {
        val s = store()
        val d = Dates.today()
        s.addTask(d, "A", "math", "", false)
        s.addTask(d, "B", "math", "", false)
        s.addTask(d, "C", "math", "", false)
        /* 初始还有 seed 出来的 3 条，这里只关心末尾三条的相对顺序 */
        val before = titles(s, d)
        val i = before.indexOf("A")

        s.moveTask(d, i, i + 1)

        val after = titles(s, d)
        assertEquals(listOf("B", "A", "C"), after.subList(i, i + 3))
        assertEquals(before.size, after.size)
    }

    @Test
    fun moveTask_up() {
        val s = store()
        val d = Dates.today()
        s.addTask(d, "A", "math", "", false)
        s.addTask(d, "B", "math", "", false)
        s.addTask(d, "C", "math", "", false)
        val i = titles(s, d).indexOf("A")

        s.moveTask(d, i + 2, i)

        val after = titles(s, d)
        assertEquals(listOf("C", "A", "B"), after.subList(i, i + 3))
    }

    @Test
    fun moveTask_ignores_out_of_range() {
        val s = store()
        val d = Dates.today()
        val before = titles(s, d)
        s.moveTask(d, 0, 99)
        s.moveTask(d, -1, 0)
        s.moveTask(d, 0, 0)
        assertEquals(before, titles(s, d))
    }

    /* ---------------- 学科标签 ---------------- */

    @Test
    fun addSubject_rejects_duplicate_and_blank() {
        val s = store()
        assertFalse(s.addSubject("数学", 0xFF000000))
        assertFalse(s.addSubject("   ", 0xFF000000))
        assertTrue(s.addSubject("数据结构", 0xFF00B8D9))
        assertEquals("数据结构", s.subjects.last().name)
    }

    @Test
    fun deleteSubject_moves_tasks_to_fallback() {
        val s = store()
        val d = Dates.today()
        s.addSubject("数据结构", 0xFF00B8D9)
        val key = s.subjects.last().key
        s.addTask(d, "写代码", key, "", false)

        assertTrue(s.deleteSubject(key))

        val moved = s.tasksOf(d).first { it.title == "写代码" }
        assertFalse(moved.subject == key)
        assertTrue(s.subjects.any { it.key == moved.subject })
    }

    @Test
    fun deleteSubject_keeps_at_least_one() {
        val s = store()
        while (s.subjects.size > 1) s.deleteSubject(s.subjects.first().key)
        assertFalse(s.deleteSubject(s.subjects.first().key))
        assertEquals(1, s.subjects.size)
    }

    /* ---------------- 颜色工具 ---------------- */

    @Test
    fun hsv_round_trip_is_stable() {
        val purple = 0xFFA855F7
        val hex = argbToHex(purple)
        assertEquals("#A855F7", hex)

        /* 用色盘算回同一个颜色，允许 1 个色阶的取整误差 */
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(purple.toInt(), hsv)
        val back = hsvToLong(hsv[0], hsv[1], hsv[2])
        for (shift in listOf(16, 8, 0)) {
            val a = ((purple shr shift) and 0xFF).toInt()
            val b = ((back shr shift) and 0xFF).toInt()
            assertTrue("channel $shift: $a vs $b", kotlin.math.abs(a - b) <= 1)
        }
    }

    @Test
    fun argbToHex_pads_short_channels() {
        assertEquals("#0000FF", argbToHex(0xFF0000FF))
        assertEquals("#0A0B0C", argbToHex(0xFF0A0B0C))
    }

    /* ---------------- 排序模式持久化 ---------------- */

    @Test
    fun sortMode_defaults_to_manual_and_persists() {
        val s = store()
        assertEquals("manual", s.settings.sort)
        s.setSort("subject")
        assertEquals("subject", s.settings.sort)
    }

    /* ---------------- 备份导入导出 ---------------- */

    /** 备份 JSON 的字段名必须和网页版一致，这里直接钉死格式 */
    @Test
    fun backup_encode_roundtrip_keeps_everything() {
        val s = freshStore()
        val d = "2026-09-01"
        s.addTask(d, "积分不等式", "math", "两种题型都要会", true)
        s.addTask(d, "单词200", "english", "", false)
        s.setStudy(d, Study(hours = "9", start = "08:00", place = "图书馆", review = "22:00"))
        s.toggleCheckin(d)

        val json = Backup.encode(s)
        assertTrue(json.contains("\"app\": \"daily-task-list\""))
        assertTrue(json.contains("\"schema\": 1"))

        val back = Backup.decode(json)
        val list = back.tasks[d]!!
        assertEquals(2, list.size)
        assertEquals("积分不等式", list[0].title)
        assertEquals("两种题型都要会", list[0].note)
        assertTrue(list[0].done)
        assertEquals("9", back.study[d]!!.hours)
        assertTrue(back.checkins.contains(d))
        /* 那天只有这两条；示例任务落在今天，不影响这里的判断 */
        assertEquals(1, list.count { it.done })
    }

    /** 覆盖导入：先清空再写入，而且不能再冒出示例任务 */
    @Test
    fun installBackup_replace_clears_previous() {
        val s = freshStore()
        s.addTask("2026-10-01", "旧任务", "math", "", false)

        val src = Backup.decode(
            """{"app":"daily-task-list","schema":1,
               "tasks":{"2026-08-26":[{"title":"q","subject":"uncat","done":true,"createdAt":1}]},
               "checkins":["2026-08-26"],
               "subjects":[{"key":"uncat","name":"未分类","color":"#B0B6C0"}]}"""
        )
        val n = s.installBackup(src, merge = false)

        assertEquals(1, n)
        assertEquals(0, s.tasksOf("2026-10-01").size)
        assertEquals(1, s.tasksOf("2026-08-26").size)
        assertTrue(s.isCheckedIn("2026-08-26"))
        assertEquals("未分类", s.subjectOf("uncat").name)
    }

    /** 合并导入：同样的内容进来两次，不能变成双份 */
    @Test
    fun installBackup_merge_is_idempotent() {
        val s = freshStore()
        val json = """{"app":"daily-task-list","tasks":{"2026-12-11":[
            {"id":"a1","title":"线代1.2","subject":"math","done":true,"createdAt":1},
            {"id":"a2","title":"单词","subject":"english","done":false,"createdAt":2}]}}"""
        val src = Backup.decode(json)

        assertEquals(2, s.installBackup(src, merge = true))
        assertEquals(0, s.installBackup(src, merge = true))
        assertEquals(2, s.tasksOf("2026-12-11").size)
    }

    /** 合并时同一天同名的任务算重复（旧数据里 id 可能是重新生成的） */
    @Test
    fun installBackup_merge_dedupes_by_title() {
        val s = freshStore()
        s.addTask("2026-12-12", "刷题1200", "math", "", true)
        val src = Backup.decode(
            """{"app":"daily-task-list","tasks":{"2026-12-12":[
               {"id":"other","title":"刷题1200","subject":"math","done":true,"createdAt":9}]}}"""
        )
        assertEquals(0, s.installBackup(src, merge = true))
        assertEquals(1, s.tasksOf("2026-12-12").size)
    }

    @Test
    fun backup_decode_rejects_foreign_files() {
        val bad = listOf(
            "not json at all",
            """{"app":"other-app","tasks":{}}""",
            """{"app":"daily-task-list"}""",
            """{"tasks":{}}"""
        )
        bad.forEach { text ->
            val failed = runCatching { Backup.decode(text) }.isFailure
            assertTrue("应该拒绝: $text", failed)
        }
    }

    /** 颜色：网页版写 "#RRGGBB"，原生版写 0xAARRGGBB，两边都要认得 */
    @Test
    fun backup_color_formats_are_interchangeable() {
        assertEquals(0xFF4C8DFFL, Backup.parseColor("#4C8DFF"))
        assertEquals(0xFF4C8DFFL, Backup.parseColor("0xFF4C8DFF"))
        assertEquals(0xFF4C8DFF, Backup.parseColor(0xFF4C8DFFL))
        assertEquals(0xFF4C8DFFL, Backup.parseColor("4283207167"))
        assertEquals("#4C8DFF", Backup.colorToHex(0xFF4C8DFFL))
        assertNull(Backup.parseColor("nope"))
    }

    /* ---------------- 迁移（全部 / 部分 / 保留原件） ---------------- */

    private fun seedMigrate(s: AppStore): String {
        val d = "2026-12-20"
        s.addTask(d, "A", "math", "", false)
        s.addTask(d, "B", "english", "", false)
        s.addTask(d, "C", "math", "", true)   // 已完成，永远不参与迁移
        return d
    }

    @Test
    fun migrate_all_moves_only_undone() {
        val s = freshStore()
        val d = seedMigrate(s)
        val to = "2026-12-21"

        val n = s.migrate(d, to)

        assertEquals(2, n)
        /* 源日期只剩已完成的那条 */
        assertEquals(listOf("C"), titles(s, d))
        assertEquals(listOf("A", "B"), titles(s, to))
    }

    @Test
    fun migrate_selected_only_moves_picked() {
        val s = freshStore()
        val d = seedMigrate(s)
        val to = "2026-12-22"
        val b = s.tasksOf(d).first { it.title == "B" }

        val n = s.migrate(d, to, ids = setOf(b.id))

        assertEquals(1, n)
        assertEquals(listOf("A", "C"), titles(s, d))
        assertEquals(listOf("B"), titles(s, to))
    }

    @Test
    fun migrate_with_keep_copies_and_leaves_source_untouched() {
        val s = freshStore()
        val d = seedMigrate(s)
        val to = "2026-12-23"

        val n = s.migrate(d, to, keep = true)

        assertEquals(2, n)
        /* 原件一条不少 */
        assertEquals(listOf("A", "B", "C"), titles(s, d))
        /* 目标日期拿到两份副本，而且是新的 id（否则以后删除会连坐） */
        val copies = s.tasksOf(to)
        assertEquals(listOf("A", "B"), copies.map { it.title })
        val sourceIds = s.tasksOf(d).map { it.id }.toSet()
        assertTrue(copies.none { it.id in sourceIds })
    }

    @Test
    fun migrate_keep_flag_is_remembered() {
        val s = freshStore()
        assertFalse(s.settings.migrateKeep)
        s.setMigrateKeep(true)
        assertTrue(s.settings.migrateKeep)
        /* 重新从本地读一遍，设置要还在 */
        val again = AppStore(RuntimeEnvironment.getApplication())
        assertTrue(again.settings.migrateKeep)
    }

    @Test
    fun migrate_into_same_day_is_noop() {
        val s = freshStore()
        val d = seedMigrate(s)
        assertEquals(0, s.migrate(d, d))
        assertEquals(3, s.tasksOf(d).size)
    }

    /* ---------------- 自动备份 ---------------- */

    @Test
    fun autoBackup_defaults_on_and_persists() {
        val s = freshStore()
        assertTrue(s.settings.autoBackup)
        s.setAutoBackup(false)
        assertFalse(s.settings.autoBackup)
        val again = AppStore(RuntimeEnvironment.getApplication())
        assertFalse(again.settings.autoBackup)
    }

    /* ---------------- 删除学科标签 ---------------- */

    @Test
    fun deleteSubject_moves_tasks_to_chosen_tag() {
        val s = freshStore()
        val d = "2026-12-25"
        s.addTask(d, "线代", "math", "", false)
        s.addTask(d, "阅读", "english", "", false)

        /* 删掉数学，明确要求改到「政治」，而不是悄悄并到第一个标签 */
        assertTrue(s.deleteSubject("math", into = "politics"))

        val subj = s.tasksOf(d).associate { it.title to it.subject }
        assertEquals("politics", subj["线代"])
        assertEquals("english", subj["阅读"])
        assertTrue(s.subjects.none { it.key == "math" })
    }

    @Test
    fun deleteSubject_without_target_falls_back_to_first_remaining() {
        val s = freshStore()
        val d = "2026-12-26"
        s.addTask(d, "刷题", "english", "", false)

        /* 老调用方式（不传目标）仍然可用，行为是并入剩下第一个标签 */
        assertTrue(s.deleteSubject("english"))

        assertEquals("math", s.tasksOf(d).first().subject)
    }

    @Test
    fun deleteSubject_can_empty_a_tag_without_losing_count() {
        val s = freshStore()
        val d = "2026-12-27"
        s.addSubject("数据结构", 0xFF00B8D9)
        val key = s.subjects.last().key
        repeat(3) { i -> s.addTask(d, "题 $i", key, "", false) }

        assertTrue(s.deleteSubject(key, into = "cs408"))

        assertEquals(3, s.tasksOf(d).count { it.subject == "cs408" })
        assertEquals(0, s.tasksUsingSubject(key))
    }
}
