package com.dayplan.calendar

import android.Manifest
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** window.Android inside the page. */
class Bridge(private val activity: MainActivity) {
    private val ctx = activity.applicationContext

    @JavascriptInterface fun load(key: String): String? = Store.get(ctx, key)

    @JavascriptInterface fun save(key: String, value: String) {
        Store.put(ctx, key, value)
        CalendarWidget.refresh(ctx)
        if (key == "cal.events") EventReminders.schedule(ctx)
    }

    @JavascriptInterface fun calPermission(): Boolean = CalendarRepo.hasPermission(ctx)

    @JavascriptInterface fun requestCal() {
        activity.runOnUiThread {
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), MainActivity.REQ_CAL
            )
        }
    }

    private fun safe(block: () -> String): String =
        try { block() } catch (e: Exception) { JSONObject().put("error", "error").put("message", e.message ?: "").toString() }

    @JavascriptInterface fun listEvents(start: String, end: String): String = safe { CalendarRepo.listJson(ctx, start, end) }

    @JavascriptInterface fun createEvent(json: String): String = safe {
        CalendarRepo.create(ctx, json); CalendarWidget.refresh(ctx); "{\"ok\":true}"
    }

    @JavascriptInterface fun updateEvent(json: String): String = safe {
        val repeating = CalendarRepo.update(ctx, json); CalendarWidget.refresh(ctx)
        JSONObject().put("ok", true).put("recurring", repeating).toString()
    }

    @JavascriptInterface fun deleteEvent(id: String): String = safe {
        CalendarRepo.delete(ctx, id); CalendarWidget.refresh(ctx); "{\"ok\":true}"
    }
}
