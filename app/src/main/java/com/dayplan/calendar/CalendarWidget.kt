package com.dayplan.calendar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** 달력형 widget: 4×4 shows titles in the days, 4×3 colored dots, 4×2 just this week. */
class CalendarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx, mgr, ids)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        update(ctx, mgr, intArrayOf(id))

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        when (intent.action) {
            ACTION_TOGGLE -> { intent.getStringExtra(EXTRA_ID)?.let { WidgetData.toggle(ctx, it) }; refresh(ctx) }
            ACTION_REFRESH -> refresh(ctx)
            ACTION_SELECT -> {
                intent.getStringExtra(EXTRA_DATE)?.let { setSel(ctx, id, it) }
                update(ctx, AppWidgetManager.getInstance(ctx), intArrayOf(id))
            }
            ACTION_MONTH -> {
                val off = monthOff(ctx, id) + intent.getIntExtra(EXTRA_DELTA, 0)
                prefs(ctx).edit().putInt("off_$id", off).apply()
                setSel(ctx, id, if (off == 0) "" else YearMonth.now().plusMonths(off.toLong()).atDay(1).toString())
                update(ctx, AppWidgetManager.getInstance(ctx), intArrayOf(id))
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.dayplan.calendar.TOGGLE"
        const val ACTION_REFRESH = "com.dayplan.calendar.REFRESH"
        const val ACTION_SELECT = "com.dayplan.calendar.SELECT"
        const val ACTION_MONTH = "com.dayplan.calendar.MONTH"
        const val ACTION_OPEN = "com.dayplan.calendar.OPEN"
        const val EXTRA_ID = "id"
        const val EXTRA_DATE = "date"
        const val EXTRA_DELTA = "delta"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)
        private fun monthOff(ctx: Context, id: Int) = prefs(ctx).getInt("off_$id", 0)

        /** The picked day, kept with the date it was picked so it falls back to today the next day. */
        private fun setSel(ctx: Context, id: Int, date: String) {
            prefs(ctx).edit().putString("sel_$id", date).putString("selOn_$id", LocalDate.now().toString()).apply()
        }

        fun selected(ctx: Context, id: Int): LocalDate {
            val p = prefs(ctx)
            val today = LocalDate.now()
            if (p.getString("selOn_$id", "") != today.toString()) {
                p.edit().putInt("off_$id", 0).apply()
                return today
            }
            return runCatching { LocalDate.parse(p.getString("sel_$id", "")) }.getOrNull() ?: today
        }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CalendarWidget::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        private fun broadcast(ctx: Context, code: Int, action: String, id: Int, extra: (Intent) -> Unit = {}) =
            PendingIntent.getBroadcast(
                ctx, code,
                Intent(ctx, CalendarWidget::class.java).setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).also(extra),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val template = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, WidgetActionActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            for (id in ids) {
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260)
                val size = when { h < 200 -> 's'; h < 330 -> 'm'; else -> 'l' }
                val rv = RemoteViews(ctx.packageName, when (size) { 's' -> R.layout.widget_s; 'm' -> R.layout.widget_m; else -> R.layout.widget_l })
                val sel = selected(ctx, id)
                val month = YearMonth.now().plusMonths(monthOff(ctx, id).toLong())

                if (size == 's') {
                    rv.setTextViewText(R.id.title, "${today.monthValue}월 ${today.dayOfMonth}일 ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}")
                    rv.setViewVisibility(R.id.prev, View.GONE)
                    rv.setViewVisibility(R.id.next, View.GONE)
                } else {
                    rv.setTextViewText(R.id.title, "${month.monthValue}월  ${month.year}")
                    rv.setOnClickPendingIntent(R.id.prev, broadcast(ctx, id * 1000 + 901, ACTION_MONTH, id) { it.putExtra(EXTRA_DELTA, -1) })
                    rv.setOnClickPendingIntent(R.id.next, broadcast(ctx, id * 1000 + 902, ACTION_MONTH, id) { it.putExtra(EXTRA_DELTA, 1) })
                }
                rv.setOnClickPendingIntent(R.id.title, open)
                rv.setOnClickPendingIntent(R.id.add, open)
                rv.setOnClickPendingIntent(R.id.empty, open)

                // Days
                val first = if (size == 's') today else month.atDay(1)
                val start = first.minusDays((first.dayOfWeek.value % 7).toLong())   // back to Sunday
                val weeks = if (size == 's') 1 else if (start.plusDays(35).month == month.month) 6 else 5
                val events = try { WidgetData.eventsByDay(ctx, start, weeks * 7) } catch (e: Exception) { emptyMap() }
                rv.removeAllViews(R.id.weeks)
                for (w in 0 until weeks) {
                    val row = RemoteViews(ctx.packageName, R.layout.widget_week)
                    for (i in 0 until 7) {
                        val d = start.plusDays((w * 7 + i).toLong())
                        row.addView(R.id.week, cell(ctx, id, d, today, sel, size == 's' || d.month == month.month, events[d].orEmpty(), size == 'l'))
                    }
                    rv.addView(R.id.weeks, row)
                }

                // List
                val svc = Intent(ctx, WidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                @Suppress("DEPRECATION")
                rv.setRemoteAdapter(R.id.list, svc)
                rv.setEmptyView(R.id.list, R.id.empty)
                rv.setPendingIntentTemplate(R.id.list, template)
                mgr.updateAppWidget(id, rv)
            }
            @Suppress("DEPRECATION")
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        private fun cell(
            ctx: Context, id: Int, d: LocalDate, today: LocalDate, sel: LocalDate,
            inMonth: Boolean, items: List<DayItem>, chips: Boolean
        ): RemoteViews {
            val c = RemoteViews(ctx.packageName, R.layout.widget_cell)
            c.setTextViewText(R.id.num, d.dayOfMonth.toString())
            val color = when {
                d == today -> R.color.surface
                !inMonth -> R.color.muted
                d.dayOfWeek == DayOfWeek.SUNDAY -> R.color.sun
                d.dayOfWeek == DayOfWeek.SATURDAY -> R.color.sat
                else -> R.color.ink
            }
            c.setTextColor(R.id.num, ctx.getColor(color))
            c.setInt(R.id.num, "setBackgroundResource", when {
                d == today -> R.drawable.day_today
                d == sel -> R.drawable.day_selected
                else -> R.drawable.day_none
            })
            if (chips) {
                c.setViewVisibility(R.id.dots, View.GONE)
                listOf(R.id.chip1, R.id.chip2).forEachIndexed { i, v ->
                    val it = items.getOrNull(i) ?: return@forEachIndexed
                    c.setViewVisibility(v, View.VISIBLE)
                    c.setTextViewText(v, it.title)
                    c.setInt(v, "setBackgroundColor", it.color)
                }
                if (items.size > 2) {
                    c.setViewVisibility(R.id.more, View.VISIBLE)
                    c.setTextViewText(R.id.more, "+${items.size - 2}")
                }
            } else {
                val dots = SpannableStringBuilder()
                items.take(3).forEach {
                    val s = dots.length
                    dots.append("●")
                    dots.setSpan(ForegroundColorSpan(it.color), s, dots.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                c.setTextViewText(R.id.dots, if (dots.isEmpty()) " " else dots)
            }
            val code = id * 1000 + (d.dayOfYear % 900)
            c.setOnClickPendingIntent(R.id.cell, PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, WidgetActionActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_DATE, d.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            return c
        }
    }
}
