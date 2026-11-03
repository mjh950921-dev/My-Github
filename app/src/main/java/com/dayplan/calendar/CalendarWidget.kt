package com.dayplan.calendar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class CalendarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx, mgr, ids)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                intent.getStringExtra(EXTRA_ID)?.let { WidgetData.toggle(ctx, it) }
                refresh(ctx)
            }
            ACTION_REFRESH -> refresh(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.dayplan.calendar.TOGGLE"
        const val ACTION_REFRESH = "com.dayplan.calendar.REFRESH"
        const val EXTRA_ID = "id"

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CalendarWidget::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            val title = "${today.monthValue}월 ${today.dayOfMonth}일 ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}"
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val refresh = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, CalendarWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Rows fill in the action and goal id.
            val template = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, CalendarWidget::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            for (id in ids) {
                val rv = RemoteViews(ctx.packageName, R.layout.widget)
                rv.setTextViewText(R.id.title, title)
                rv.setOnClickPendingIntent(R.id.title, open)
                rv.setOnClickPendingIntent(R.id.add, open)
                rv.setOnClickPendingIntent(R.id.empty, open)
                rv.setOnClickPendingIntent(R.id.refresh, refresh)
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
    }
}
