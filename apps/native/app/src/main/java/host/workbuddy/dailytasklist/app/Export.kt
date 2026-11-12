package host.workbuddy.dailytasklist.app

/* 导出文本生成：与网页版格式保持一致 */

/** 每日计划：【11月1日学习任务】... 按学科分组列出任务 */
fun buildPlanText(store: AppStore, date: String): String {
    val tasks = store.tasksOf(date)
    val st = store.studyOf(date)

    val sb = StringBuilder()
    sb.append("【").append(Dates.cn(date)).append("学习任务】\n\n")
    sb.append("学习总时长：").append(
        if (st.hours.isBlank()) "未填写"
        else parseDurationHours(st.hours).let { (h, m) -> prettyDuration(h, m) }
    ).append("\n")
    sb.append("开始学习时间：").append(st.start.ifBlank { "未填写" }).append("\n")
    sb.append("学习地点：").append(st.place.ifBlank { "未填写" }).append("\n")
    sb.append("复盘时间：").append(st.review.ifBlank { "未填写" }).append("\n")
    sb.append("学习内容\n")

    grouped(store, tasks).forEach { (subject, list) ->
        sb.append("[").append(subject.name).append("]：\n")
        list.forEachIndexed { i, t -> sb.append(i + 1).append(". ").append(t.title).append("\n") }
    }
    return sb.toString().trimEnd()
}

/** 每日总结：按学科显示 ✅/❌ + 完成率 */
fun buildSummaryText(store: AppStore, date: String): String {
    val tasks = store.tasksOf(date)
    val st = store.studyOf(date)
    val (total, done, rate) = store.progressOf(date)

    val sb = StringBuilder()
    sb.append("▤ 每日任务总结 - ").append(date).append("\n\n")
    sb.append("学习总时长：").append(
        if (st.hours.isBlank()) "未填写"
        else parseDurationHours(st.hours).let { (h, m) -> prettyDuration(h, m) }
    ).append("\n")
    sb.append("开始学习时间：").append(st.start.ifBlank { "未填写" }).append("\n")
    sb.append("学习地点：").append(st.place.ifBlank { "未填写" }).append("\n")
    sb.append("复盘时间：").append(st.review.ifBlank { "未填写" }).append("\n")
    sb.append("---------------\n")

    grouped(store, tasks).forEach { (subject, list) ->
        val d = list.count { it.done }
        sb.append("【").append(subject.name).append("】 ").append(d).append("/").append(list.size).append("\n")
        list.forEach { t ->
            sb.append(if (t.done) "✅ " else "❌ ").append(t.title).append("\n")
        }
        sb.append("───\n")
    }

    sb.append("━━ 总览 ━━\n")
    sb.append("完成率：").append((rate * 100).toInt()).append("%（已完成 ")
        .append(done).append(" 项 / 总 ").append(total).append(" 项）")

    return sb.toString()
}

/** 按学科分组，保持用户在设置里的标签顺序 */
private fun grouped(store: AppStore, tasks: List<Task>): List<Pair<Subject, List<Task>>> {
    val map = linkedMapOf<String, MutableList<Task>>()
    store.subjects.forEach { map[it.key] = mutableListOf() }
    tasks.forEach { t ->
        map.getOrPut(t.subject) { mutableListOf() }.add(t)
    }
    return map
        .filter { it.value.isNotEmpty() }
        .map { store.subjectOf(it.key) to it.value.toList() }
}
