package com.yonizilber.flownics.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Dates in the app's current language and the phone's time zone. */
class DateTexts(private val locale: Locale, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    private fun at(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    /** "November 2026" — history section headers. */
    fun monthYear(millis: Long): String = monthYear.format(at(millis))

    /** "7 Nov" — chart axis. */
    fun dayMonth(millis: Long): String = dayMonth.format(at(millis))

    /** "Saturday, Nov 7, 2026". */
    fun fullDate(millis: Long): String = "${weekday.format(at(millis))}, ${date.format(at(millis))}"

    /** "Nov 7, 2026". */
    fun date(millis: Long): String = date.format(at(millis))

    /** "18:05", or "6:05 PM" where that is the custom. */
    fun time(millis: Long): String = time.format(at(millis))

    /** Same calendar month: one header covers both. */
    fun sameMonth(a: Long, b: Long): Boolean {
        val x = at(a)
        val y = at(b)
        return x.year == y.year && x.month == y.month
    }
}

@Composable
fun rememberDateTexts(): DateTexts {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTexts(locale) }
}
