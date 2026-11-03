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
    data class Head(val text: String) : Row()
    data class Empty(val text: String) : Row()
    data class Event(val day: String, val time: String, val title: String, val color: Int) : Row()
    data class Goal(val id: String, val title: String, val sub: String, val done: Boolean, val late: Boolean) : Row()
}

/** What the widget shows: the next 7 days of events, then this month's and this year's goals. */
object WidgetData {
    private val APP_COLORS = mapOf(
        "c1" to "#0d7558", "c2" to "#d4552f", "c3" to "#4a5bd0", "c4" to "#b97c06", "c5" to "#9a3fa8"
    )

    private fun arr(ctx: Context, key: String): List<JSONObject> = try {
        val a = JSONArray(Store.get(ctx, key) ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    private fun occursOn(ev: JSONObject, d: LocalDate): Boolean {
        val base = runCatching { LocalDate.parse(ev.optString("date")) }.getOrNull() ?: return false
        if (d.isBefore(base)) return false
        if (d == base) return true
        return when (ev.optString("repeat")) {
            "weekly" -> d.dayOfWeek == base.dayOfWeek
            "monthly" -> d.dayOfMonth == base.dayOfMonth
            "yearly" -> d.dayOfMonth == base.dayOfMonth && d.month == base.month
            else -> false
        }
    }

    private fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "오늘"
        today.plusDays(1) -> "내일"
        else -> "${d.monthValue}/${d.dayOfMonth} ${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}"
    }

    private data class Item(val allDay: Boolean, val start: String, val title: String, val color: Int)

    fun build(ctx: Context): List<Row> {
        val rows = ArrayList<Row>()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        // Events: this app's own + the phone's calendars.
        val byDay = HashMap<String, MutableList<Item>>()
        for (d in 0L until 7L) {
            val day = today.plusDays(d)
            for (e in arr(ctx, "cal.events")) {
                if (!occursOn(e, day)) continue
                val all = e.optBoolean("allDay")
                byDay.getOrPut(day.toString()) { mutableListOf() } += Item(
                    all, if (all) "" else e.optString("start"), e.optString("title"),
                    Color.parseColor(APP_COLORS[e.optString("color")] ?: "#0d7558")
                )
            }
        }
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
        for (i in CalendarRepo.instances(ctx, from, to)) {
            val color = if (i.color == 0) Color.parseColor("#1a62d6") else (i.color or 0xFF000000.toInt())
            for (ds in CalendarRepo.days(i)) {
                byDay.getOrPut(ds) { mutableListOf() } += Item(
                    i.allDay, if (i.allDay) "" else timeFmt.format(i.begin), i.title.ifBlank { "(제목 없음)" }, color
                )
            }
        }
        rows += Row.Head("일정 · 7일")
        var any = false
        for (d in 0L until 7L) {
            val day = today.plusDays(d)
            val list = byDay[day.toString()] ?: continue
            list.sortWith(compareBy<Item>({ !it.allDay }, { it.start }, { it.title }))
            list.forEachIndexed { idx, it ->
                rows += Row.Event(if (idx == 0) dayLabel(day, today) else "", if (it.allDay) "종일" else it.start, it.title, it.color)
            }
            any = true
        }
        if (!any) rows += Row.Empty(if (CalendarRepo.hasPermission(ctx)) "7일 동안 일정이 없어요" else "앱을 열어 캘린더 접근을 허용해 주세요")

        // Goals.
        val plans = arr(ctx, "cal.plans").map(::norm)
        val year = today.year
        val month = YearMonth.from(today).toString()
        val goalTitle = plans.filter { it.optString("level") == "goal" }.associate { it.optString("id") to it.optString("title") }

        val subs = plans.filter {
            it.optString("level") == "sub" && it.optString("dueType") == "month" &&
                (it.optString("due") == month || (it.optString("due") < month && !it.optBoolean("done")))
        }.sortedWith(compareBy({ it.optBoolean("done") }, { it.optString("due") }))
        rows += Row.Head("${today.monthValue}월 목표")
        if (subs.isEmpty()) rows += Row.Empty("이번 달 기한인 세부 목표가 없어요")
        for (s in subs) {
            val due = s.optString("due")
            val late = !s.optBoolean("done") && due < month
            val parent = goalTitle[s.optString("goalId")].orEmpty()
            val sub = listOf(if (late) "${due.replace("-", ".")}까지 · 지남" else "", parent).filter { it.isNotEmpty() }.joinToString(" · ")
            rows += Row.Goal(s.optString("id"), s.optString("title"), sub, s.optBoolean("done"), late)
        }

        val goals = plans.filter {
            it.optString("level") == "goal" && it.optInt("startYear") <= year && year <= it.optInt("endYear")
        }.sortedWith(compareBy({ it.optBoolean("done") }, { it.optLong("createdAt") }))
        rows += Row.Head("${year}년 목표")
        if (goals.isEmpty()) rows += Row.Empty(if (plans.isEmpty()) "앱의 계획 탭에서 목표를 추가해 보세요" else "올해 목표가 없어요")
        for (g in goals) {
            val a = g.optInt("startYear"); val b = g.optInt("endYear")
            val yearsSub = plans.count { it.optString("level") == "sub" && it.optString("goalId") == g.optString("id") }
            val doneSub = plans.count { it.optString("level") == "sub" && it.optString("goalId") == g.optString("id") && it.optBoolean("done") }
            val sub = listOf(if (a == b) "${a}년" else "$a–$b", if (yearsSub > 0) "세부 $doneSub/$yearsSub" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            rows += Row.Goal(g.optString("id"), g.optString("title"), sub, g.optBoolean("done"), false)
        }
        return rows
    }

    /** Older 장기/중기/단기 plans, read the way the page reads them. */
    private fun norm(p: JSONObject): JSONObject {
        val level = p.optString("level")
        if (level == "goal" || level == "sub" || level == "bucket") return p
        val c = JSONObject(p.toString())
        val per = p.optString("period")
        val now = LocalDate.now().year
        when (level) {
            "long" -> { val y = per.drop(1).toIntOrNull() ?: now; c.put("level", "goal").put("startYear", y).put("endYear", y + 4) }
            "mid" -> { val y = per.drop(1).toIntOrNull() ?: now; c.put("level", "goal").put("startYear", y).put("endYear", y) }
            "short" -> {
                val m = Regex("^Q(\\d+)-(\\d)$").find(per)
                val y = m?.groupValues?.get(1)?.toIntOrNull() ?: now
                c.put("level", "goal").put("startYear", y).put("endYear", y)
            }
        }
        return c
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
