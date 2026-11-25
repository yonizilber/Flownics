package com.yonizilber.flownics.domain.body

import java.time.LocalDateTime
import java.util.UUID

/**
 * What the Xiaomi Mi Smart Scale 2 (XMTZC04HM, shown as "MI SCALE2") says over Bluetooth, and what
 * it is asked. Xiaomi publishes none of this; it comes from the reverse engineering others shared
 * (openScale, and the projects reading the scale in home automation). Written here from those
 * facts, not copied, and kept pure so it is tested without a scale.
 *
 * A reading is 10 bytes, the same in the scale's advertisement (its live weight) and in its stored
 * history: a status byte, the weight (16 bits, little-endian), then the date and time by the
 * scale's own clock (year in 16 bits, month, day, hour, minute, second).
 */
object MiScaleProtocol {

    private fun uuid16(n: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(n))

    val WEIGHT_SERVICE: UUID = uuid16(0x181D)
    val CURRENT_TIME: UUID = uuid16(0x2A2B)
    val CCCD: UUID = uuid16(0x2902)

    /** Xiaomi's own characteristic for the stored weigh-ins: commands are written to it, records come back as notifications. */
    val HISTORY: UUID = UUID.fromString("00002a2f-0000-3512-2118-0009af100700")

    /** The names the scale advertises. */
    fun isScaleName(name: String?): Boolean = name != null && (name.equals("MI SCALE2", ignoreCase = true) || name.uppercase().startsWith("MI_SCALE"))

    data class Reading(
        val kg: Double,
        /** By the scale's clock; null when the bytes are no date at all. */
        val time: LocalDateTime?,
        /** The weight has settled (not still moving). */
        val stable: Boolean,
        /** The person stepped off: the number shown is the last one, not a new weighing. */
        val removed: Boolean,
    ) {
        /** A finished weighing worth keeping. */
        val final: Boolean get() = stable && !removed && kg in Weights.RANGE
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** A 10-byte reading; null when it isn't one. */
    fun reading(b: ByteArray): Reading? {
        if (b.size != 10) return null
        val status = u8(b, 0)
        val raw = u16(b, 1)
        // The unit is the one shown on the scale: kg is in 5 g steps, pounds and catties in 1/100.
        val kg = when {
            status and 0x01 != 0 -> raw / 100.0 * 0.45359237
            status and 0x10 != 0 -> raw / 100.0 * 0.5
            else -> raw / 200.0
        }
        val time = runCatching { LocalDateTime.of(u16(b, 3), u8(b, 5), u8(b, 6), u8(b, 7), u8(b, 8), u8(b, 9)) }.getOrNull()
        return Reading(kg, time, stable = status and 0x20 != 0, removed = status and 0x80 != 0)
    }

    /** Sets the scale's clock (the Current Time characteristic), so the weigh-ins it stores are dated right. */
    fun currentTime(now: LocalDateTime): ByteArray = byteArrayOf(
        (now.year and 0xFF).toByte(), (now.year shr 8).toByte(),
        now.monthValue.toByte(), now.dayOfMonth.toByte(),
        now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
        0, 0, 1,
    )

    /** Turns history reading on. */
    val ENABLE_HISTORY = byteArrayOf(0x01, 0x96.toByte(), 0x8A.toByte(), 0xBD.toByte(), 0x62)

    /** Asks for the weigh-ins not yet confirmed by [userId] (this app's own id on the scale). */
    fun requestNew(userId: Int) = byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), (userId shr 8).toByte(), userId.toByte())

    val START = byteArrayOf(0x02)
    val STOP = byteArrayOf(0x03)

    /** Confirms the weigh-ins were received: the scale won't send them to [userId] again. */
    fun confirm(userId: Int) = byteArrayOf(0x04, 0xFF.toByte(), 0xFF.toByte(), (userId shr 8).toByte(), userId.toByte())

    /**
     * Collects the history as it arrives: the count the scale announces, then the records (several
     * may come in one notification, and one may be split over two), then the end. Anything else
     * is kept as not understood, to be shown, never dropped silently.
     */
    class History {
        var announced: Int? = null
            private set
        var done = false
            private set
        val readings = mutableListOf<Reading>()
        val notUnderstood = mutableListOf<ByteArray>()
        private var buffer = ByteArray(0)

        fun feed(d: ByteArray) {
            when {
                d.isEmpty() -> Unit
                d.size == 1 && d[0] == STOP[0] -> {
                    if (buffer.isNotEmpty()) notUnderstood += buffer
                    buffer = ByteArray(0)
                    done = true
                }
                // The count answering the request: 0x01, the count (one or two bytes), then the request echoed.
                (d.size == 6 || d.size == 7) && d[0] == 0x01.toByte() -> {
                    announced = if (d.size == 7) u16(d, 1) else u8(d, 1)
                }
                else -> {
                    buffer += d
                    while (buffer.size >= 10) {
                        val record = buffer.copyOfRange(0, 10)
                        buffer = buffer.copyOfRange(10, buffer.size)
                        reading(record)?.let { readings += it } ?: run { notUnderstood += record }
                    }
                }
            }
        }
    }
}
