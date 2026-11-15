package com.dayplan.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject

/**
 * Pop-up notifications for events saved in the app. (Events saved to Google Calendar are
 * announced by the phone's calendar app with the reminder set when they were created.)
 * Only the next alarm is kept; each alarm shows what is due and schedules the one after.
 */
object EventReminders {
    private const val CHANNEL = "events"
    private const val ALL_DAY_HOUR = 9

    /** kind: "event", "routine" or "todo" (these get a 완료 button), or "summary". */
    private data class Due(val at: Long, val id: String, val title: String, val day: LocalDate, val label: String, val kind: String = "event") {
        val routine get() = kind == "routine" || kind == "summary"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** Reminders that fire after [after], over the next ~13 months. */
    private fun upcoming(ctx: Context, after: Long, limitDays: Long = 400): List<Due> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val out = ArrayList<Due>()
        for (e in WidgetData.arr(ctx, "cal.events")) {
            val remind = if (e.has("remind")) e.optInt("remind", 10) else 10
            if (remind < 0) continue
            val allDay = e.optBoolean("allDay")
            val time = if (allDay) LocalTime.of(ALL_DAY_HOUR, 0)
                else runCatching { LocalTime.parse(e.optString("start")) }.getOrNull() ?: continue
            // An all-day event is announced at 9:00 that day, or 9:00 the day before for "하루 전".
            val before = if (allDay) (if (remind >= 1440) 1440L else 0L) else remind.toLong()
            for (i in -1L..limitDays) {
                val day = today.plusDays(i)
                if (!WidgetData.occursOn(e, day)) continue
                val at = LocalDateTime.of(day, time).minusMinutes(before).atZone(zone).toInstant().toEpochMilli()
                if (at <= after) continue
                val label = when {
                    allDay -> if (before > 0) "내일 · 종일" else "오늘 · 종일"
                    before == 0L -> "지금 · ${e.optString("start")}"
                    before >= 1440 -> "내일 ${e.optString("start")}"
                    before >= 60 -> "${before / 60}시간 후 · ${e.optString("start")}"
                    else -> "${before}분 후 · ${e.optString("start")}"
                }
                out += Due(at, e.optString("id"), e.optString("title"), day, label)
                break
            }
        }
        // Routines with a reminder time: announced on their days at that time, unless already done or resting.
        val set = WidgetData.rset(ctx)
        val routines = WidgetData.arr(ctx, "cal.routines")
        val soon = minOf(limitDays, 14L)
        for (r in routines) {
            val time = runCatching { LocalTime.parse(r.optString("time")) }.getOrNull() ?: continue
            for (i in -1L..soon) {
                val day = today.plusDays(i); val ds = day.toString()
                if (!WidgetData.routineOn(r, day, WidgetData.modeOf(set, day)) || WidgetData.routineDone(r, ds) || WidgetData.routineRest(r, ds)) continue
                val at = LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
                if (at <= after) continue
                val title = listOf(r.optString("emoji"), r.optString("title")).filter { it.isNotEmpty() }.joinToString(" ")
                val g = r.optInt("goal", 1)
                val label = if (g > 1) "루틴 · 오늘 ${WidgetData.routineCount(r, ds)}/$g${r.optString("unit").ifEmpty { "회" }}" else "루틴 · 아직 체크하지 않았어요"
                out += Due(at, r.optString("id"), title, day, label, "routine")
                break
            }
        }
        // To-dos with a time, on their first day, until done.
        for (t in WidgetData.arr(ctx, "cal.todos")) {
            if (t.optBoolean("done")) continue
            val day = runCatching { LocalDate.parse(t.optString("date")) }.getOrNull() ?: continue
            val time = runCatching { LocalTime.parse(t.optString("time")) }.getOrNull() ?: continue
            val at = LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
            if (at <= after || day.isAfter(today.plusDays(limitDays))) continue
            out += Due(at, t.optString("id"), "☐ " + t.optString("title"), day, "할 일 · ${t.optString("time")}", "todo")
        }
        // 아침/저녁 요약: what's left of the day's routines (and a nudge for the day's 기록 in the evening).
        for ((key, morning) in listOf("morning" to true, "evening" to false)) {
            val time = runCatching { LocalTime.parse(set.optString(key)) }.getOrNull() ?: continue
            for (i in 0L..soon) {
                val day = today.plusDays(i); val ds = day.toString()
                val at = LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
                if (at <= after) continue
                val mode = WidgetData.modeOf(set, day)
                val left = routines.filter {
                    WidgetData.routineOn(it, day, mode) && !WidgetData.routineDone(it, ds) && !WidgetData.routineRest(it, ds) &&
                        (!morning || it.optString("slot", "any") in listOf("morning", "any"))
                }
                val noJournal = !morning && (try { JSONObject(Store.get(ctx, "cal.journal") ?: "{}") } catch (e: Exception) { JSONObject() }).optJSONObject(ds)?.let {
                    it.optInt("mood", 0) == 0 && it.optString("note").isEmpty() } ?: true
                if (left.isEmpty() && !noJournal) continue
                if (left.isEmpty() && morning) continue
                val names = left.take(4).joinToString(", ") { it.optString("title") } + if (left.size > 4) " 외 ${left.size - 4}개" else ""
                val title = when {
                    morning -> "아침 루틴 ${left.size}개가 기다려요"
                    left.isEmpty() -> "오늘 루틴을 모두 끝냈어요 🎉"
                    else -> "오늘 루틴 ${left.size}개 남았어요"
                }
                val label = when {
                    left.isNotEmpty() && noJournal && !morning -> "$names · 한 줄 일기도 남겨 보세요"
                    left.isNotEmpty() -> names
                    else -> "오늘 하루는 어땠나요? 한 줄 남겨 보세요"
                }
                out += Due(at, "summary-$key", title, day, label, "summary")
                break
            }
        }
        return out.sortedBy { it.at }
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Call whenever events change, at boot and when the clock changes. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val handled = maxOf(prefs(ctx).getLong("handled", 0L), System.currentTimeMillis())
        val next = upcoming(ctx, handled).firstOrNull()
        if (next == null) { am.cancel(pending(ctx)); return }
        val pi = pending(ctx)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        }
    }

    fun fire(ctx: Context) {
        val p = prefs(ctx)
        val from = p.getLong("handled", System.currentTimeMillis() - 120_000L)
        val until = System.currentTimeMillis() + 60_000L
        for (d in upcoming(ctx, from, 2).filter { it.at <= until }) notify(ctx, d)
        p.edit().putLong("handled", until).apply()
        schedule(ctx)
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "일정 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "앱에 저장한 일정이 다가오면 알려줘요"
                enableVibration(true)
            })
        }
    }

    private fun notify(ctx: Context, d: Due) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, d.id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(CalendarWidget.EXTRA_DATE, d.day.toString())
                .putExtra(MainActivity.EXTRA_VIEW, when { d.kind == "summary" && d.title.startsWith("오늘 루틴을 모두") -> "journal"; d.routine -> "routine"; else -> "" })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nid = (d.id + d.day).hashCode()
        @Suppress("DEPRECATION")
        val b = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(d.title.ifBlank { "일정" })
            .setContentText(d.label)
            .setCategory(if (d.routine) android.app.Notification.CATEGORY_REMINDER else android.app.Notification.CATEGORY_EVENT)
            .setContentIntent(open)
            .setAutoCancel(true)
        // 완료 right on the notification (and on a paired Galaxy Watch, which mirrors it).
        if (d.kind == "routine" || d.kind == "todo") {
            val done = PendingIntent.getBroadcast(
                ctx, nid,
                Intent(ctx, DoneReceiver::class.java).putExtra("kind", d.kind).putExtra("id", d.id)
                    .putExtra("date", d.day.toString()).putExtra("nid", nid),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            @Suppress("DEPRECATION")
            b.addAction(android.app.Notification.Action.Builder(null, if (d.kind == "todo") "완료" else "완료 체크", done).build())
        }
        ctx.getSystemService(NotificationManager::class.java)?.notify(nid, b.build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                if (intent.action == null) EventReminders.fire(ctx)
                else { EventReminders.schedule(ctx); CalendarWatch.schedule(ctx); CalendarWidget.refresh(ctx) }
            } finally { result.finish() }
        }.start()
    }
}

/** The 완료 button on a routine or to-do notification. */
class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                val id = intent.getStringExtra("id") ?: return@Thread
                when (intent.getStringExtra("kind")) {
                    "routine" -> WidgetData.tapRoutine(ctx, id + "|" + (intent.getStringExtra("date") ?: ""), oneMore = true)
                    "todo" -> if (WidgetData.arr(ctx, "cal.todos").any { it.optString("id") == id && !it.optBoolean("done") }) WidgetData.toggle(ctx, id, "cal.todos")
                }
                ctx.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("nid", 0))
                EventReminders.schedule(ctx)
                CalendarWidget.refresh(ctx); RoutineWidget.refresh(ctx)
                MainActivity.current?.outsideChanged()
            } finally { result.finish() }
        }.start()
    }
}
