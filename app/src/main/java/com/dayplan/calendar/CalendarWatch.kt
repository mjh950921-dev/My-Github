package com.dayplan.calendar

import android.accounts.Account
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars

/**
 * Keeps the widget and app current when the phone's calendars change from outside the app,
 * for example an event that n8n or Claude added to Google Calendar arriving through Google's sync.
 */
class CalendarWatch : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Thread {
            try {
                // Goals or bucket-list items changed in the "DayPlan 목표" calendar: pull them in.
                val plansChanged = PlanSync.sync(ctx)
                Store.bumpOutside(ctx)
                CalendarWidget.refresh(ctx)
                // Reload an open app only for that; the app's own saves also land here.
                if (plansChanged) MainActivity.current?.outsideChanged()
            } finally {
                schedule(ctx) // a content trigger fires once, so watch again
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB_ID = 4201

        /** Watches the calendar provider; runs a few seconds after changes settle. */
        fun schedule(ctx: Context) {
            if (!CalendarRepo.hasPermission(ctx)) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, CalendarWatch::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(
                    CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3_000)
                .setTriggerContentMaxDelay(30_000)
                .build()
            try { ctx.getSystemService(JobScheduler::class.java).schedule(job) } catch (_: Exception) { }
        }

        /** Asks Google's calendar sync to run now, so changes made elsewhere show up when the app opens. */
        fun syncNow(ctx: Context) {
            if (!CalendarRepo.hasPermission(ctx)) return
            val accounts = HashSet<Account>()
            try {
                ctx.contentResolver.query(
                    Calendars.CONTENT_URI, arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
                    "${Calendars.SYNC_EVENTS}=1", null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0); val type = c.getString(1)
                        if (name != null && type != null && type != CalendarContract.ACCOUNT_TYPE_LOCAL) accounts += Account(name, type)
                    }
                }
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                for (a in accounts) ContentResolver.requestSync(a, CalendarContract.AUTHORITY, extras)
            } catch (_: Exception) { }
        }
    }
}
