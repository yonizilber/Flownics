package com.yonizilber.flownics.demo

import java.io.ByteArrayOutputStream

/**
 * Debug builds only: writes a small FIT file (the published FIT protocol, little-endian), so the
 * demo data can go through the app's real import path. Only the base types the demo needs.
 */
class FitWriter {
    class Field(val number: Int, val size: Int, val baseType: Int)

    private val body = ByteArrayOutputStream()
    private val layouts = arrayOfNulls<List<Field>>(16)

    fun define(local: Int, global: Int, fields: List<Field>) {
        body.write(0x40 or local)
        body.write(0) // reserved
        body.write(0) // little-endian
        u16(global)
        body.write(fields.size)
        fields.forEach { body.write(it.number); body.write(it.size); body.write(it.baseType) }
        layouts[local] = fields
    }

    /** One value per defined field, in order; null writes the type's "invalid" value. */
    fun write(local: Int, vararg values: Long?) {
        val fields = layouts[local] ?: error("local $local not defined")
        require(values.size == fields.size) { "expected ${fields.size} values, got ${values.size}" }
        body.write(local)
        fields.forEachIndexed { i, f ->
            val v = values[i] ?: when (f.size) {
                1 -> 0xFFL
                2 -> 0xFFFFL
                else -> 0xFFFFFFFFL
            }
            repeat(f.size) { b -> body.write(((v shr (8 * b)) and 0xFF).toInt()) }
        }
    }

    fun bytes(): ByteArray {
        val data = body.toByteArray()
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x20 // protocol 2.0
        header[2] = (2140 and 0xFF).toByte()
        header[3] = (2140 shr 8).toByte()
        for (b in 0..3) header[4 + b] = ((data.size shr (8 * b)) and 0xFF).toByte()
        ".FIT".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        val headerCrc = crc(header, 12)
        header[12] = (headerCrc and 0xFF).toByte()
        header[13] = (headerCrc shr 8).toByte()
        val all = header + data
        val fileCrc = crc(all, all.size)
        return all + byteArrayOf((fileCrc and 0xFF).toByte(), (fileCrc shr 8).toByte())
    }

    private fun u16(v: Int) {
        body.write(v and 0xFF)
        body.write((v shr 8) and 0xFF)
    }

    private fun crc(bytes: ByteArray, n: Int): Int {
        var crc = 0
        for (i in 0 until n) {
            val byte = bytes[i].toInt() and 0xFF
            var tmp = TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor TABLE[byte and 0xF]
            tmp = TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }

    companion object {
        const val ENUM = 0x00
        const val UINT8 = 0x02
        const val UINT16 = 0x84
        const val UINT32 = 0x86
        const val UINT32Z = 0x8C

        /** Seconds between 1970-01-01 and the FIT epoch. */
        const val FIT_EPOCH = 631_065_600L

        private val TABLE = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
        )
    }
}
