package com.dayplan.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The phone's own calendars (Google, Samsung, …) through the system calendar provider. */
object CalendarRepo {
    data class Inst(
        val id: String, val title: String, val begin: Long, val end: Long,
        val allDay: Boolean, val location: String, val color: Int
    )

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun fmt(tz: TimeZone = TimeZone.getDefault()) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = tz }

    private fun dayFmt(tz: TimeZone) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun instances(ctx: Context, from: Long, to: Long): List<Inst> {
        if (!hasPermission(ctx)) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.ALL_DAY, Instances.EVENT_LOCATION, Instances.DISPLAY_COLOR, Instances.STATUS
        )
        val out = ArrayList<Inst>()
        ctx.contentResolver.query(uri, proj, "${Instances.VISIBLE}=1", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(7) && c.getInt(7) == Events.STATUS_CANCELED) continue
                val begin = c.getLong(2)
                out += Inst(
                    id = "${c.getLong(0)}_$begin",
                    title = c.getString(1) ?: "",
                    begin = begin,
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    location = c.getString(5) ?: "",
                    color = if (c.isNull(6)) 0 else c.getInt(6)
                )
            }
        }
        return out
    }

    /** Local date strings (yyyy-MM-dd) an instance covers; all-day events are stored in UTC. */
    fun days(i: Inst): List<String> {
        if (!i.allDay) return listOf(dayFmt(TimeZone.getDefault()).format(i.begin))
        val f = dayFmt(UTC)
        val out = ArrayList<String>()
        var t = i.begin
        while (t < i.end && out.size < 62) { out += f.format(t); t += 86_400_000L }
        if (out.isEmpty()) out += f.format(i.begin)
        return out
    }

    /** Same shape as the Google Calendar list_events payload the page already reads. */
    fun listJson(ctx: Context, start: String, end: String): String {
        val f = fmt()
        val from = f.parse(start)!!.time - 86_400_000L
        val to = f.parse(end)!!.time + 86_400_000L
        val items = JSONArray()
        val utcDay = dayFmt(UTC)
        for (i in instances(ctx, from, to)) {
            val s = JSONObject(); val e = JSONObject()
            if (i.allDay) { s.put("date", utcDay.format(i.begin)); e.put("date", utcDay.format(i.end)) }
            else { s.put("dateTime", f.format(i.begin)); e.put("dateTime", f.format(i.end)) }
            items.put(JSONObject().put("id", i.id).put("summary", i.title).put("location", i.location)
                .put("start", s).put("end", e))
        }
        return JSONObject().put("items", items).toString()
    }

    private fun pickCalendar(ctx: Context): Long? {
        val proj = arrayOf(Calendars._ID, Calendars.ACCOUNT_TYPE, Calendars.IS_PRIMARY)
        var best: Long? = null; var bestScore = -1
        ctx.contentResolver.query(
            Calendars.CONTENT_URI, proj,
            "${Calendars.CALENDAR_ACCESS_LEVEL}>=${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE}=1",
            null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val google = c.getString(1) == "com.google"
                val primary = !c.isNull(2) && c.getInt(2) == 1
                val score = (if (google) 2 else 0) + (if (primary) 1 else 0)
                if (score > bestScore) { bestScore = score; best = c.getLong(0) }
            }
        }
        return best
    }

    fun create(ctx: Context, json: String) {
        val o = JSONObject(json)
        val cal = pickCalendar(ctx) ?: throw IllegalStateException("no calendar")
        val allDay = o.optBoolean("allDay")
        val tz = if (allDay) UTC else TimeZone.getDefault()
        val f = fmt(tz)
        val start = f.parse(o.getString("startTime"))!!.time
        val end = f.parse(o.getString("endTime"))!!.time
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal)
            put(Events.TITLE, o.optString("summary"))
            if (o.has("description")) put(Events.DESCRIPTION, o.optString("description"))
            put(Events.EVENT_TIMEZONE, tz.id)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.DTSTART, start)
        }
        val rule = o.optJSONArray("recurrenceData")?.optString(0)?.removePrefix("RRULE:")
        if (!rule.isNullOrEmpty()) {
            v.put(Events.RRULE, rule)
            v.put(Events.DURATION, if (allDay) "P1D" else "P${maxOf(60L, (end - start) / 1000)}S")
        } else {
            v.put(Events.DTEND, end)
        }
        val uri = ctx.contentResolver.insert(Events.CONTENT_URI, v) ?: throw IllegalStateException("insert failed")
        if (!allDay) {
            ctx.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, ContentUris.parseId(uri))
                put(Reminders.MINUTES, 10)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
    }

    fun delete(ctx: Context, id: String) {
        val eventId = id.substringBefore("_").toLong()
        ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }
}
