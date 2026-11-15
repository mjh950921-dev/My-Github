package com.dayplan.calendar

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

sealed class Row {
    data class Head(val text: String, val date: String? = null) : Row()
    data class Empty(val text: String) : Row()
    data class Event(val time: String, val title: String, val color: Int, val date: String) : Row()
    data class Goal(val id: String, val title: String, val sub: String, val done: Boolean, val late: Boolean, val key: String = "cal.plans") : Row()
}

data class DayItem(val allDay: Boolean, val start: String, val title: String, val color: Int)

/** What the widget shows: a month grid with events, then the picked day's events and this month's goals. */
object WidgetData {
    private val APP_COLORS = mapOf(
        "c1" to "#0d7558", "c2" to "#d4552f", "c3" to "#4a5bd0", "c4" to "#b97c06", "c5" to "#9a3fa8"
    )

    fun arr(ctx: Context, key: String): List<JSONObject> = try {
        val a = JSONArray(Store.get(ctx, key) ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    private fun nth(d: LocalDate) = (d.dayOfMonth + 6) / 7
    private fun lastWeek(d: LocalDate) = d.dayOfMonth + 7 > d.lengthOfMonth()

    /** Same rules as occursOn() in the page. */
    fun occursOn(ev: JSONObject, d: LocalDate): Boolean {
        val b = runCatching { LocalDate.parse(ev.optString("date")) }.getOrNull() ?: return false
        if (d.isBefore(b)) return false
        if (d == b) return true
        val wd = d.dayOfWeek == b.dayOfWeek
        return when (ev.optString("repeat")) {
            "weekly" -> wd
            "monthly" -> d.dayOfMonth == b.dayOfMonth
            "yearly" -> d.dayOfMonth == b.dayOfMonth && d.month == b.month
            "monthnth" -> wd && nth(d) == nth(b)
            "monthlast" -> wd && lastWeek(d)
            "yearnth" -> d.month == b.month && wd && nth(d) == nth(b)
            "yearlast" -> d.month == b.month && wd && lastWeek(d)
            else -> false
        }
    }

    private fun todoEnd(t: JSONObject): String {
        val d = t.optString("date"); val u = t.optString("until")
        return if (u.isNotEmpty() && u > d) u else d
    }

    /** Same rules as todosOn() in the page: the to-do's days, plus unfinished overdue ones on today. */
    fun todosOn(ctx: Context, day: LocalDate): List<JSONObject> {
        val ds = day.toString(); val today = LocalDate.now().toString()
        return arr(ctx, "cal.todos").filter {
            val d = it.optString("date"); val end = todoEnd(it)
            d.isNotEmpty() && ((d <= ds && ds <= end) || (ds == today && !it.optBoolean("done") && end < today))
        }.sortedWith(compareBy({ it.optBoolean("done") }, { it.optString("date") }, { it.optLong("createdAt") }))
    }

    // Routines: same rules as rOn()/rCount() in the page. days: 0=Sun … 6=Sat; log: {"YYYY-MM-DD": times}.
    private fun routineGoal(r: JSONObject) = r.optInt("goal", 1).coerceIn(1, 30)
    fun routineCount(r: JSONObject, ds: String) = (r.optJSONObject("log")?.optInt(ds, 0) ?: 0).coerceIn(0, routineGoal(r))
    fun routineDone(r: JSONObject, ds: String) = routineCount(r, ds) >= routineGoal(r)
    /** Settings the page keeps in cal.rset: 루틴 모드, summary reminder times, … */
    fun rset(ctx: Context): JSONObject = try { JSONObject(Store.get(ctx, "cal.rset") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Same as modeOf() in the page: the mode a past day ran in, the current mode from today on ("" = 기본). */
    fun modeOf(set: JSONObject, day: LocalDate): String {
        val ids = set.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("id").orEmpty() } }.orEmpty()
        val log = set.optJSONObject("modeLog")
        val ds = day.toString()
        val m = if (log != null && log.has(ds)) log.optString(ds) else if (day >= LocalDate.now()) set.optString("active") else ""
        return if (m in ids) m else ""
    }

    fun routineRest(r: JSONObject, ds: String) = r.optJSONObject("rest")?.optInt(ds, 0) == 1

    /** Same as rOn() in the page: not paused, started, on that weekday, and shown in that day's mode. */
    fun routineOn(r: JSONObject, day: LocalDate, mode: String = ""): Boolean {
        if (r.optBoolean("paused") || day.toString() < r.optString("start")) return false
        val days = r.optJSONArray("days") ?: return false
        val dow = day.dayOfWeek.value % 7
        if (!(0 until days.length()).any { days.optInt(it, -1) == dow }) return false
        val modes = r.optJSONArray("modes")
        return modes == null || modes.length() == 0 || (0 until modes.length()).any { modes.optString(it) == mode }
    }
    private val SLOT = listOf("morning", "day", "evening", "any")
    fun routinesOn(ctx: Context, day: LocalDate, set: JSONObject = rset(ctx)): List<JSONObject> {
        val mode = modeOf(set, day)
        return arr(ctx, "cal.routines").filter { routineOn(it, day, mode) }
            .sortedWith(compareBy({ SLOT.indexOf(it.optString("slot", "any")) }, { it.optString("time").ifEmpty { "99" } }, { it.optLong("createdAt") }))
    }

    /** Routines to do that day (rest days left out), and how many are done. */
    fun dayScore(ctx: Context, day: LocalDate): Pair<Int, Int> {
        val l = routinesOn(ctx, day).filter { !routineRest(it, day.toString()) }
        return l.count { routineDone(it, day.toString()) } to l.size
    }

    /** Rows for the routine widget and the calendar widget's day list. */
    fun routineRows(ctx: Context, day: LocalDate): List<Row.Goal> {
        val ds = day.toString()
        return routinesOn(ctx, day).map { r ->
            val g = routineGoal(r); val c = routineCount(r, ds); val rest = routineRest(r, ds)
            val alt = r.optJSONObject("alog")?.optString(ds).orEmpty()
            val sub = when {
                rest -> "오늘은 쉬어요"
                g > 1 -> "$c/$g${r.optString("unit").ifEmpty { "회" }}"
                alt.isNotEmpty() && c >= g -> "${r.optString("title")} 대신"
                r.optString("time").isNotEmpty() -> r.optString("time")
                else -> mapOf("morning" to "아침", "day" to "낮", "evening" to "저녁").getOrDefault(r.optString("slot"), "언제든")
            }
            val title = listOf(r.optString("emoji"), if (alt.isNotEmpty() && c >= g) alt else r.optString("title")).filter { it.isNotEmpty() }.joinToString(" ")
            Row.Goal(r.optString("id") + "|" + ds, title, sub, c >= g || rest, false, "cal.routines")
        }
    }

    /** Events per day from [from] for [days] days: this app's own and the phone's calendars, then unfinished to-dos. */
    fun eventsByDay(ctx: Context, from: LocalDate, days: Int, withTodos: Boolean = true): Map<LocalDate, List<DayItem>> {
        val out = HashMap<LocalDate, MutableList<DayItem>>()
        val own = arr(ctx, "cal.events")
        for (i in 0 until days) {
            val day = from.plusDays(i.toLong())
            for (e in own) {
                if (!occursOn(e, day)) continue
                val all = e.optBoolean("allDay")
                out.getOrPut(day) { mutableListOf() } += DayItem(
                    all, if (all) "" else e.optString("start"), e.optString("title"),
                    Color.parseColor(APP_COLORS[e.optString("color")] ?: "#0d7558")
                )
            }
        }
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
        for (inst in CalendarRepo.instances(ctx, start, end)) {
            val color = if (inst.color == 0) Color.parseColor("#1a62d6") else (inst.color or 0xFF000000.toInt())
            for (ds in CalendarRepo.days(inst)) {
                val day = runCatching { LocalDate.parse(ds) }.getOrNull() ?: continue
                out.getOrPut(day) { mutableListOf() } += DayItem(
                    inst.allDay, if (inst.allDay) "" else timeFmt.format(inst.begin), inst.title.ifBlank { "(제목 없음)" }, color
                )
            }
        }
        out.values.forEach { it.sortWith(compareBy<DayItem>({ !it.allDay }, { it.start }, { it.title })) }
        if (withTodos) {
            val accent = Color.parseColor("#0d7558")
            for (i in 0 until days) {
                val day = from.plusDays(i.toLong())
                for (t in todosOn(ctx, day)) {
                    if (t.optBoolean("done")) continue
                    out.getOrPut(day) { mutableListOf() } += DayItem(false, "~", "☐ " + t.optString("title"), accent)
                }
            }
        }
        return out
    }

    /** The list under the grid: the picked day, then this month's goals. */
    fun listRows(ctx: Context, sel: LocalDate): List<Row> {
        val rows = ArrayList<Row>()
        val today = LocalDate.now()
        val dow = sel.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        rows += Row.Head("${if (sel == today) "오늘 · " else ""}${sel.monthValue}월 ${sel.dayOfMonth}일 $dow", sel.toString())
        val list = eventsByDay(ctx, sel, 1, withTodos = false)[sel].orEmpty()
        val todos = todosOn(ctx, sel)
        if (list.isEmpty() && todos.isEmpty() && (sel > today || routinesOn(ctx, sel).isEmpty())) rows += Row.Empty(if (CalendarRepo.hasPermission(ctx)) "일정이 없어요" else "앱을 열어 캘린더 접근을 허용해 주세요")
        val todayStr = today.toString()
        for (t in todos) {
            val end = todoEnd(t); val done = t.optBoolean("done")
            val late = !done && end < todayStr
            val sub = when {
                late -> "${end.substring(5).replace("-", ".")}까지 · 지남"
                end != t.optString("date") -> "${end.substring(5).replace("-", ".")}까지"
                else -> "할 일"
            }
            rows += Row.Goal(t.optString("id"), t.optString("title"), sub, done, late, "cal.todos")
        }
        // Today's (or a past day's) routines, checked off from the widget one tap at a time.
        if (sel <= today) routineRows(ctx, sel).forEach { r -> rows += r.copy(sub = "루틴 · " + r.sub) }
        list.forEach { rows += Row.Event(if (it.allDay) "종일" else it.start, it.title, it.color, sel.toString()) }

        val plans = arr(ctx, "cal.plans")
        val month = YearMonth.from(today).toString()
        val goalTitle = plans.filter { it.optString("level") == "goal" }.associate { it.optString("id") to it.optString("title") }
        val subs = plans.filter {
            it.optString("level") == "sub" && it.optString("dueType") == "month" &&
                (it.optString("due") == month || (it.optString("due") < month && !it.optBoolean("done")))
        }.sortedWith(compareBy({ it.optBoolean("done") }, { it.optString("due") }))
        val done = subs.count { it.optBoolean("done") }
        rows += Row.Head("${today.monthValue}월 목표 $done/${subs.size}")
        if (subs.isEmpty()) rows += Row.Empty(if (plans.isEmpty()) "앱의 계획 탭에서 목표를 추가해 보세요" else "이번 달 기한인 세부 목표가 없어요")
        for (s in subs) {
            val due = s.optString("due")
            val late = !s.optBoolean("done") && due < month
            val parent = goalTitle[s.optString("goalId")].orEmpty()
            val sub = listOf(if (late) "${due.replace("-", ".")}까지 · 지남" else "", parent).filter { it.isNotEmpty() }.joinToString(" · ")
            rows += Row.Goal(s.optString("id"), s.optString("title"), sub, s.optBoolean("done"), late)
        }
        return rows
    }

    fun toggle(ctx: Context, id: String, key: String = "cal.plans") {
        if (key == "cal.routines") { tapRoutine(ctx, id); return }
        if (key != "cal.plans" && key != "cal.todos") return
        val a = try { JSONArray(Store.get(ctx, key) ?: "[]") } catch (e: Exception) { return }
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            if (p.optString("id") != id) continue
            val done = !p.optBoolean("done")
            val now = System.currentTimeMillis()
            p.put("done", done).put("doneAt", if (done) now else JSONObject.NULL).put("updatedAt", now)
        }
        Store.put(ctx, key, a.toString())
        Store.bumpOutside(ctx)
        if (key == "cal.plans") PlanSync.syncAsync(ctx)
    }

    /** One tap on a routine row ("id|date"): one more time that day; a finished one goes back to zero. */
    fun tapRoutine(ctx: Context, key: String, oneMore: Boolean = false) {
        val id = key.substringBefore("|"); val ds = key.substringAfter("|", "")
        if (ds.isEmpty() || ds > LocalDate.now().toString()) return
        val a = try { JSONArray(Store.get(ctx, "cal.routines") ?: "[]") } catch (e: Exception) { return }
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            if (r.optString("id") != id) continue
            val g = routineGoal(r); val c = routineCount(r, ds)
            val next = if (c >= g) (if (oneMore) g else 0) else c + 1
            r.optJSONObject("rest")?.remove(ds)
            val log = r.optJSONObject("log") ?: JSONObject()
            if (next == 0) log.remove(ds) else log.put(ds, next)
            r.put("log", log).put("updatedAt", System.currentTimeMillis())
        }
        Store.put(ctx, "cal.routines", a.toString())
        Store.bumpOutside(ctx)
        EventReminders.schedule(ctx)
    }
}
