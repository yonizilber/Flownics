package com.yonizilber.flownics.calendar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import com.yonizilber.flownics.R
import java.util.concurrent.TimeUnit

/**
 * Adds workouts to the user's calendar through the calendar app's own "new event" screen. The
 * user sees and confirms every field there, which is why Flownics needs no calendar permission
 * and never reads or writes calendar data itself.
 */
object CalendarEvents {

    /** A workout to do: starts at the next full hour; the user picks the real time in the calendar. */
    fun planned(context: Context, title: String, description: String, estimatedSeconds: Int, nowMillis: Long = System.currentTimeMillis()) {
        val hour = TimeUnit.HOURS.toMillis(1)
        val begin = (nowMillis / hour + 1) * hour
        val length = TimeUnit.SECONDS.toMillis(estimatedSeconds.toLong()).coerceAtLeast(TimeUnit.MINUTES.toMillis(MIN_MINUTES))
        open(context, title, description, begin, begin + length)
    }

    /** A workout already done, at the time it was actually done. */
    fun done(context: Context, title: String, description: String, beginMillis: Long, endMillis: Long) {
        open(context, title, description, beginMillis, endMillis)
    }

    private fun open(context: Context, title: String, description: String, beginMillis: Long, endMillis: Long) {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private const val MIN_MINUTES = 15L
}
