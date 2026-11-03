package com.dayplan.calendar

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)
}

private class Factory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}

    override fun onDataSetChanged() {
        val token = Binder.clearCallingIdentity()
        rows = try { WidgetData.build(ctx) } catch (e: Exception) { listOf(Row.Empty("불러오지 못했어요")) }
        finally { Binder.restoreCallingIdentity(token) }
    }

    override fun getCount() = rows.size
    override fun getViewTypeCount() = 4
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val pkg = ctx.packageName
        return when (val r = rows.getOrNull(position) ?: Row.Empty("")) {
            is Row.Head -> RemoteViews(pkg, R.layout.row_head).apply { setTextViewText(R.id.text, r.text) }
            is Row.Empty -> RemoteViews(pkg, R.layout.row_empty).apply { setTextViewText(R.id.text, r.text) }
            is Row.Event -> RemoteViews(pkg, R.layout.row_event).apply {
                setTextViewText(R.id.day, r.day)
                setTextViewText(R.id.time, r.time)
                setTextViewText(R.id.title, r.title)
                setTextColor(R.id.dot, r.color)
            }
            is Row.Goal -> RemoteViews(pkg, R.layout.row_goal).apply {
                setTextViewText(R.id.check, if (r.done) "☑" else "☐")
                setTextViewText(R.id.title, r.title)
                setTextViewText(R.id.sub, r.sub)
                setTextColor(R.id.title, ctx.getColor(if (r.done) R.color.muted else R.color.ink))
                setTextColor(R.id.sub, ctx.getColor(if (r.late) R.color.late else R.color.muted))
                setOnClickFillInIntent(
                    R.id.row,
                    Intent().setAction(CalendarWidget.ACTION_TOGGLE).putExtra(CalendarWidget.EXTRA_ID, r.id)
                )
            }
        }
    }
}
