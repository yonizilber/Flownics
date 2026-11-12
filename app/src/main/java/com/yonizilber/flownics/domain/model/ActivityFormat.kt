package com.yonizilber.flownics.domain.model

import java.util.Locale
import kotlin.math.roundToInt

/** Reading and writing the numbers people type for activities. Pure, so it is unit-tested. */
object ActivityFormat {

    /**
     * "45" (minutes), "45:30" (minutes:seconds) or "1:05:30" (hours:minutes:seconds) to seconds.
     * Null for blank or anything else, so a typo is reported instead of guessed.
     */
    fun parseDuration(text: String, bareNumberIsMinutes: Boolean = true): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
        val n = parts.map { it.toInt() }
        return when (n.size) {
            1 -> if (bareNumberIsMinutes) n[0] * 60 else n[0]
            2 -> if (n[1] < 60) n[0] * 60 + n[1] else null
            3 -> if (n[1] < 60 && n[2] < 60) n[0] * 3600 + n[1] * 60 + n[2] else null
            else -> null
        }?.takeIf { it > 0 }
    }

    /** 2730 → "45:30", 3930 → "1:05:30". */
    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** "8.2" or "8,2" kilometres to metres. */
    fun parseKm(text: String): Int? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it > 0 && it < 1000 }?.let { (it * 1000).roundToInt() }
    }

    /** 8200 → "8.2", 400 → "0.4", 10000 → "10". */
    fun formatKm(meters: Int): String = String.format(Locale.ROOT, "%.2f", meters / 1000.0).trimEnd('0').trimEnd('.')

    /** 308 → "5:08" (per km). */
    fun formatPace(secondsPerKm: Int): String = String.format(Locale.ROOT, "%d:%02d", secondsPerKm / 60, secondsPerKm % 60)

    /** A whole number in a sensible range, or null (blank or out of range). */
    fun parseInt(text: String, range: IntRange): Int? = text.trim().toIntOrNull()?.takeIf { it in range }
}
