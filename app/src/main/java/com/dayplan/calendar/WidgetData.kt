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
    data class Goal(val id: String, val title: String, val sub: String, val done: Boolean, val late: Boolean) : Row()
}

data class DayItem(val allDay: Boolean, val start: String, val title: String, val color: Int)

/** What the widget shows: a month grid with events, then the picked day's events and this month's goals. */
object WidgetData {
    private val APP_COLORS = mapOf(
        "c1" to "#0d7558", "c2" to "#d4552f", "c3" to "#4a5bd0", "c4" to "#b97c06", "c5" to "#9a3fa8"
    )

    private fun arr(ctx: Context, key: String): List<JSONObject> = try {
        val a = JSONArray(Store.get(ctx, key) ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    private fun nth(d: LocalDate) = (d.dayOfMonth + 6) / 7
    private fun lastWeek(d: LocalDate) = d.dayOfMonth + 7 > d.lengthOfMonth()

    /** Same rules as occursOn() in the page. */
    private fun occursOn(ev: JSONObject, d: LocalDate): Boolean {
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

    /** Events per day from [from] for [days] days: this app's own and the phone's calendars. */
    fun eventsByDay(ctx: Context, from: LocalDate, days: Int): Map<LocalDate, List<DayItem>> {
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
        return out
    }

    /** The list under the grid: the picked day, then this month's goals. */
    fun listRows(ctx: Context, sel: LocalDate): List<Row> {
        val rows = ArrayList<Row>()
        val today = LocalDate.now()
        val dow = sel.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        rows += Row.Head("${if (sel == today) "오늘 · " else ""}${sel.monthValue}월 ${sel.dayOfMonth}일 $dow", sel.toString())
        val list = eventsByDay(ctx, sel, 1)[sel].orEmpty()
        if (list.isEmpty()) rows += Row.Empty(if (CalendarRepo.hasPermission(ctx)) "일정이 없어요" else "앱을 열어 캘린더 접근을 허용해 주세요")
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

    fun toggle(ctx: Context, id: String) {
        val a = try { JSONArray(Store.get(ctx, "cal.plans") ?: "[]") } catch (e: Exception) { return }
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            if (p.optString("id") != id) continue
            val done = !p.optBoolean("done")
            val now = System.currentTimeMillis()
            p.put("done", done).put("doneAt", if (done) now else JSONObject.NULL).put("updatedAt", now)
        }
        Store.put(ctx, "cal.plans", a.toString())
        Store.bumpOutside(ctx)
    }
}
