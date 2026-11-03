package com.dayplan.calendar

import android.content.Context

/** App data shared by the page (through Bridge) and the widget. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cal", Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)
    fun put(ctx: Context, key: String, value: String) { prefs(ctx).edit().putString(key, value).apply() }

    /** Bumped when data changes outside the page (widget taps), so the app reloads it. */
    fun outsideVersion(ctx: Context): Long = prefs(ctx).getLong("outside", 0)
    fun bumpOutside(ctx: Context) { prefs(ctx).edit().putLong("outside", outsideVersion(ctx) + 1).commit() }
}
