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

/** 오늘 루틴 widget: today's progress and the routines, tap one to check it. */
class RoutineWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx, mgr, ids)

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RoutineWidget::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val today = LocalDate.now()
            val (done, total) = try { WidgetData.dayScore(ctx, today) } catch (e: Exception) { 0 to 0 }
            val open = PendingIntent.getActivity(
                ctx, 31,
                Intent(ctx, MainActivity::class.java).putExtra(CalendarWidget.EXTRA_DATE, today.toString())
                    .putExtra(MainActivity.EXTRA_VIEW, "routine")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val template = PendingIntent.getActivity(
                ctx, 32, Intent(ctx, WidgetActionActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            for (id in ids) {
                val rv = RemoteViews(ctx.packageName, R.layout.widget_routine)
                rv.setTextViewText(R.id.title, if (total > 0 && done == total) "오늘 루틴 완료 🎉" else "오늘 루틴")
                rv.setTextViewText(R.id.count, if (total > 0) "$done/$total" else "")
                rv.setProgressBar(R.id.bar, 100, if (total > 0) done * 100 / total else 0, false)
                rv.setOnClickPendingIntent(R.id.head, open)
                rv.setOnClickPendingIntent(R.id.empty, open)
                val svc = Intent(ctx, WidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(WidgetService.EXTRA_KIND, "routine")
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
