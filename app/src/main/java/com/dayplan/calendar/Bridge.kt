package com.dayplan.calendar

import android.Manifest
import android.net.Uri
import android.view.WindowManager
import java.io.File
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** window.Android inside the page. */
class Bridge(private val activity: MainActivity) {
    private val ctx = activity.applicationContext

    @JavascriptInterface fun load(key: String): String? = Store.get(ctx, key)

    @JavascriptInterface fun save(key: String, value: String) {
        Store.put(ctx, key, value)
        CalendarWidget.refresh(ctx)
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

    // 일기 tab: entries and the password hash live in a private file, never in the widget's store.
    private val diaryFile get() = File(ctx.filesDir, "diary.json")

    @JavascriptInterface fun diaryLoad(): String = diaryFile.let { if (it.exists()) it.readText() else "" }

    @JavascriptInterface fun diarySave(json: String) {
        val tmp = File(ctx.filesDir, "diary.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(diaryFile)) { diaryFile.writeText(json); tmp.delete() }
    }

    /** Reads the separate 내 일기 app's data (same signing key) so it can be moved into this tab. */
    @JavascriptInterface fun diaryImport(): String = try {
        ctx.contentResolver.query(Uri.parse("content://com.dayplan.diary.export/all"), null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (e: Exception) { "" }

    /** While the diary is open, keep it out of screenshots and the recent-apps preview. */
    @JavascriptInterface fun setSecure(on: Boolean) {
        activity.runOnUiThread {
            if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @JavascriptInterface fun deleteEvent(id: String): String = safe {
        CalendarRepo.delete(ctx, id); CalendarWidget.refresh(ctx); "{\"ok\":true}"
    }
}
