package com.dayplan.calendar

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Invisible target for taps inside the widget: checks off a goal, or opens the app on a day. */
class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            CalendarWidget.ACTION_TOGGLE -> {
                intent.getStringExtra(CalendarWidget.EXTRA_ID)?.let { WidgetData.toggle(this, it) }
                CalendarWidget.refresh(this)
            }
            else -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(CalendarWidget.EXTRA_DATE, intent.getStringExtra(CalendarWidget.EXTRA_DATE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
