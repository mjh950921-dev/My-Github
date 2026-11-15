package com.dayplan.calendar

import android.Manifest
import android.content.Intent
import android.util.Base64
import androidx.core.content.FileProvider
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
        if (key == "cal.events" || key == "cal.routines" || key == "cal.todos" || key == "cal.rset" || key == "cal.journal") EventReminders.schedule(ctx)
        if (key == "cal.plans") PlanSync.syncAsync(ctx) { activity.outsideChanged() }
    }

    /** 공유 코드 and other text: the phone's share sheet (카카오톡, 메시지, …). */
    @JavascriptInterface fun shareText(text: String) {
        activity.runOnUiThread {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "루틴 보내기"))
        }
    }

    /** 리포트 이미지: written to the cache and offered to the share sheet (갤러리 저장 included). */
    @JavascriptInterface fun shareImage(base64: String, name: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "routine.png" }
        val f = File(dir, safe)
        f.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        activity.runOnUiThread {
            activity.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "리포트 저장·공유"
            ))
        }
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
