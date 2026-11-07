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

/**
 * Pop-up notifications for events saved in the app. (Events saved to Google Calendar are
 * announced by the phone's calendar app with the reminder set when they were created.)
 * Only the next alarm is kept; each alarm shows what is due and schedules the one after.
 */
object EventReminders {
    private const val CHANNEL = "events"
    private const val ALL_DAY_HOUR = 9

    private data class Due(val at: Long, val id: String, val title: String, val day: LocalDate, val label: String)

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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(d.title.ifBlank { "일정" })
            .setContentText(d.label)
            .setCategory(android.app.Notification.CATEGORY_EVENT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify((d.id + d.day).hashCode(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                if (intent.action == null) EventReminders.fire(ctx) else EventReminders.schedule(ctx)
            } finally { result.finish() }
        }.start()
    }
}
