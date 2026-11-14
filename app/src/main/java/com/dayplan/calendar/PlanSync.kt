package com.dayplan.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.UUID

/**
 * Mirrors goals and the bucket list (cal.plans) to a Google calendar named "DayPlan 목표",
 * so tools outside the phone (n8n, Claude) can add, edit, check off and delete them.
 *
 * Each plan is one all-day event in that calendar:
 * - title: the plan's title, with "✓ " in front when it is done
 * - description: the plan's note, then a "#dayplan {...}" line holding the rest of the plan
 * An event added from outside without that line becomes a bucket-list item, or a goal when its
 * description says "목표" (years like "2027" or "2027-2029" in the description set its span).
 *
 * The person creates the calendar once in Google Calendar; until then nothing syncs.
 */
object PlanSync {
    const val CALENDAR_NAME = "DayPlan 목표"
    private const val PLANS = "cal.plans"
    private const val MAP = "plans.gcal"
    private const val TAG = "#dayplan "
    private const val DONE = "✓ "
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val lock = Any()

    /** Fields that live in the title or note, or change on every save, and so stay out of the #dayplan line. */
    private val OWN = setOf("title", "note", "done", "doneAt", "updatedAt", "kind")

    /** The "DayPlan 목표" calendar's id, if the phone has it and it can be written. */
    fun calendarId(ctx: Context): Long? {
        if (!CalendarRepo.hasPermission(ctx)) return null
        return try {
            ctx.contentResolver.query(
                Calendars.CONTENT_URI, arrayOf(Calendars._ID),
                "${Calendars.CALENDAR_DISPLAY_NAME}=? AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=${Calendars.CAL_ACCESS_CONTRIBUTOR}",
                arrayOf(CALENDAR_NAME), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) { null }
    }

    /** Runs sync off the main thread; [onLocalChange] runs (on that thread) when plans changed from outside. */
    fun syncAsync(ctx: Context, onLocalChange: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread { if (sync(app)) onLocalChange?.invoke() }.start()
    }

    /** Two-way sync. Returns true when the phone's plans changed because of edits made in the calendar. */
    fun sync(ctx: Context): Boolean = synchronized(lock) {
        try { syncLocked(ctx) } catch (_: Exception) { false }
    }

    private class Remote(val eventId: Long, val title: String, val description: String, val start: Long)

    private fun syncLocked(ctx: Context): Boolean {
        val cal = calendarId(ctx) ?: return false
        val plans = LinkedHashMap<String, JSONObject>()
        val arr = try { JSONArray(Store.get(ctx, PLANS) ?: "[]") } catch (_: Exception) { return false }
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { p -> p.optString("id").takeIf { it.isNotEmpty() }?.let { plans[it] = p } }
        val map = try { JSONObject(Store.get(ctx, MAP) ?: "{}") } catch (_: Exception) { JSONObject() }
        val remotes = readEvents(ctx, cal)
        val seen = HashSet<Long>()
        var localChanged = false

        // Plans on the phone.
        for (id in plans.keys.toList()) {
            val p = plans[id]!!
            val lh = hash(p)
            val m = map.optJSONObject(id)
            val ev = m?.let { remotes[it.optLong("e")] }
            when {
                m != null && ev != null -> {
                    seen += ev.eventId
                    val r = fromEvent(ev, id, p)
                    val rh = hash(r)
                    val last = m.optString("h")
                    val h = when {
                        rh != last && rh != lh -> { plans[id] = merge(p, r); localChanged = true; rh }
                        lh != last -> { writeEvent(ctx, cal, p, ev.eventId); lh }
                        else -> lh
                    }
                    map.put(id, JSONObject().put("e", ev.eventId).put("h", h))
                }
                m != null && lh == m.optString("h") -> {
                    // Deleted in the calendar and unchanged here since: delete here too.
                    plans.remove(id); map.remove(id); localChanged = true
                }
                else -> {
                    val e = writeEvent(ctx, cal, p, null)
                    if (e != null) { seen += e; map.put(id, JSONObject().put("e", e).put("h", lh)) }
                }
            }
        }
        // Plans deleted on the phone: delete their events.
        for (id in map.keys().asSequence().toList()) {
            if (plans.containsKey(id)) continue
            val e = map.optJSONObject(id)?.optLong("e") ?: 0L
            if (e > 0 && remotes.containsKey(e)) {
                seen += e
                try { ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, e), null, null) } catch (_: Exception) { }
            }
            map.remove(id)
        }
        // Events added in the calendar: new plans.
        for (ev in remotes.values) {
            if (ev.eventId in seen) continue
            val metaId = meta(ev.description)?.optString("id")?.takeIf { it.isNotEmpty() && !plans.containsKey(it) }
            val id = metaId ?: "p_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val now = System.currentTimeMillis()
            val p = fromEvent(ev, id, null).put("kind", "plan").put("createdAt", now).put("updatedAt", now)
            if (p.optBoolean("done")) p.put("doneAt", now)
            plans[id] = p
            map.put(id, JSONObject().put("e", ev.eventId).put("h", hash(p)))
            localChanged = true
        }

        Store.put(ctx, MAP, map.toString())
        if (localChanged) {
            Store.put(ctx, PLANS, JSONArray(plans.values.toList()).toString())
            Store.bumpOutside(ctx)
            CalendarWidget.refresh(ctx)
        }
        return localChanged
    }

    private fun readEvents(ctx: Context, cal: Long): Map<Long, Remote> {
        val out = LinkedHashMap<Long, Remote>()
        ctx.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE, Events.DESCRIPTION, Events.DTSTART),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(cal.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = Remote(c.getLong(0), c.getString(1) ?: "", plain(c.getString(2) ?: ""), if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return out
    }

    /** Google Calendar on the web saves descriptions as HTML; turn that back into plain lines. */
    private fun plain(s: String): String {
        if (!s.contains('<') && !s.contains('&')) return s
        return s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

    private fun meta(description: String): JSONObject? {
        val line = description.lines().lastOrNull { it.trim().startsWith(TAG.trim()) } ?: return null
        return try { JSONObject(line.trim().removePrefix(TAG.trim()).trim()) } catch (_: Exception) { null }
    }

    private fun noteOf(description: String) =
        description.lines().filterNot { it.trim().startsWith(TAG.trim()) }.joinToString("\n").trim()

    private fun utcYear(t: Long): Int = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = UTC }.format(t).toInt()

    /** The plan an event describes. [base] supplies fields the event does not carry. */
    private fun fromEvent(ev: Remote, id: String, base: JSONObject?): JSONObject {
        val p = JSONObject()
        val m = meta(ev.description)
        if (m != null) {
            for (k in m.keys()) if (k !in OWN) p.put(k, m.get(k))
        } else if (base != null) {
            for (k in base.keys()) if (k !in OWN) p.put(k, base.get(k))
        } else {
            val text = ev.description
            if (text.contains("목표") || text.contains("goal", ignoreCase = true)) {
                val years = Regex("(19|20)\\d{2}").findAll(text).map { it.value.toInt() }.toList()
                val y1 = years.firstOrNull() ?: utcYear(ev.start)
                val y2 = years.getOrNull(1) ?: y1
                p.put("level", "goal").put("startYear", minOf(y1, y2)).put("endYear", maxOf(y1, y2)).put("parent", JSONObject.NULL)
            } else {
                p.put("level", "bucket").put("period", "B").put("parent", JSONObject.NULL)
            }
        }
        p.put("id", id)
        val title = ev.title.trim()
        val done = title.startsWith(DONE.trim())
        p.put("title", if (done) title.removePrefix(DONE.trim()).trim() else title)
        p.put("done", done)
        p.put("note", noteOf(ev.description))
        return p
    }

    /** The phone's plan with the calendar's edits applied. */
    private fun merge(local: JSONObject, remote: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in local.keys()) if (k in OWN) out.put(k, local.get(k))
        for (k in remote.keys()) out.put(k, remote.get(k))
        val now = System.currentTimeMillis()
        out.put("kind", "plan").put("updatedAt", now)
        val done = remote.optBoolean("done")
        out.put("doneAt", if (done) (local.opt("doneAt").takeIf { it is Number } ?: now) else JSONObject.NULL)
        return out
    }

    /** What sync compares: everything the event carries, in a fixed order. */
    private fun hash(p: JSONObject): String {
        val t = TreeMap<String, Any?>()
        for (k in p.keys()) if (k !in OWN || k == "title" || k == "note" || k == "done") t[k] = p.opt(k)?.let { if (it == JSONObject.NULL) null else it.toString() }
        t["title"] = p.optString("title").trim()
        t["note"] = p.optString("note").trim()
        t["done"] = p.optBoolean("done").toString()
        return t.toString()
    }

    /** All-day date the plan sits on: a goal's first year, a sub-goal's due month, a bucket item's creation day. */
    private fun dayOf(p: JSONObject): Long {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = UTC }
        val created = p.optLong("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis()
        val s = when (p.optString("level")) {
            "goal" -> "${p.optInt("startYear", utcYear(created))}-01-01"
            "sub" -> p.optString("due").let { if (Regex("^\\d{4}-\\d{2}$").matches(it)) "$it-01" else if (Regex("^\\d{4}$").matches(it)) "$it-01-01" else null }
            else -> null
        } ?: f.format(created)
        return try { f.parse(s)!!.time } catch (_: Exception) { f.parse(f.format(created))!!.time }
    }

    /** Creates or rewrites the plan's event. Returns the event id. */
    private fun writeEvent(ctx: Context, cal: Long, p: JSONObject, eventId: Long?): Long? {
        val m = JSONObject()
        for (k in p.keys()) if (k !in OWN) m.put(k, p.get(k))
        val note = p.optString("note").trim()
        val description = (if (note.isNotEmpty()) "$note\n\n" else "") + TAG + m.toString()
        val title = (if (p.optBoolean("done")) DONE else "") + p.optString("title").trim()
        val day = dayOf(p)
        val v = ContentValues().apply {
            put(Events.TITLE, title)
            put(Events.DESCRIPTION, description)
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, UTC.id)
            put(Events.DTSTART, day)
            put(Events.DTEND, day + 86_400_000L)
        }
        return try {
            if (eventId != null) {
                ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), v, null, null); eventId
            } else {
                v.put(Events.CALENDAR_ID, cal)
                ctx.contentResolver.insert(Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
            }
        } catch (_: Exception) { null }
    }
}
