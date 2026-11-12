package com.yonizilber.flownics.domain.fit

/**
 * Reads a .FIT file (the format Garmin and most sport watches record in) into its messages.
 * Written from the published FIT protocol so the app needs no third-party SDK: a 12/14-byte
 * header, then records that are either definitions (the layout of a local message type) or data
 * (fields laid out as defined), with compressed-timestamp headers and developer fields handled.
 *
 * Only what the app uses is decoded into values; every other field is skipped by its size. Values
 * the protocol marks as "invalid" (all bits set, etc.) come back as null. A broken file throws
 * [FitException] with what was wrong, never a half-read result.
 */
class FitException(message: String) : Exception(message)

/** One data message: its global number (e.g. 20 = record) and its fields by field number, raw. */
data class FitMessage(val global: Int, val fields: Map<Int, Long>, val timestamp: Long?)

object FitDecoder {

    fun decode(bytes: ByteArray): List<FitMessage> {
        if (bytes.size < 12) throw FitException("too short to be a FIT file")
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) throw FitException("unexpected header size $headerSize")
        if (String(bytes, 8, 4, Charsets.US_ASCII) != ".FIT") throw FitException("not a FIT file")
        val dataSize = u32le(bytes, 4).toInt()
        val end = headerSize + dataSize
        if (dataSize < 0 || end > bytes.size) throw FitException("the file is cut off (needs ${end} bytes, has ${bytes.size})")

        val definitions = arrayOfNulls<Definition>(16)
        val out = mutableListOf<FitMessage>()
        var lastTimestamp: Long? = null
        var pos = headerSize
        while (pos < end) {
            val header = bytes[pos].toInt() and 0xFF
            pos++
            if (header and 0x80 != 0) {
                // Compressed timestamp header: a data message with a 5-bit time offset.
                val local = (header shr 5) and 0x03
                val offset = (header and 0x1F).toLong()
                val def = definitions[local] ?: throw FitException("data for undefined local type $local")
                val (fields, next) = readFields(bytes, pos, def)
                pos = next
                val base = lastTimestamp ?: throw FitException("compressed timestamp before any timestamp")
                var ts = (base and 0x1FL.inv()) + offset
                if (offset < (base and 0x1F)) ts += 0x20
                lastTimestamp = ts
                out += FitMessage(def.global, fields, ts)
                continue
            }
            val local = header and 0x0F
            if (header and 0x40 != 0) {
                // Definition message.
                val hasDev = header and 0x20 != 0
                if (pos + 5 > end) throw FitException("definition cut off")
                val bigEndian = bytes[pos + 1].toInt() == 1
                val global = if (bigEndian) u16be(bytes, pos + 2) else u16le(bytes, pos + 2)
                val count = bytes[pos + 4].toInt() and 0xFF
                pos += 5
                val fieldDefs = (0 until count).map {
                    if (pos + 3 > end) throw FitException("field definition cut off")
                    FieldDef(bytes[pos].toInt() and 0xFF, bytes[pos + 1].toInt() and 0xFF, bytes[pos + 2].toInt() and 0xFF).also { pos += 3 }
                }
                var devBytes = 0
                if (hasDev) {
                    val devCount = bytes[pos].toInt() and 0xFF
                    pos++
                    repeat(devCount) {
                        devBytes += bytes[pos + 1].toInt() and 0xFF
                        pos += 3
                    }
                }
                definitions[local] = Definition(global, bigEndian, fieldDefs, devBytes)
            } else {
                val def = definitions[local] ?: throw FitException("data for undefined local type $local")
                val (fields, next) = readFields(bytes, pos, def)
                pos = next
                val ts = fields[TIMESTAMP_FIELD]
                if (ts != null) lastTimestamp = ts
                out += FitMessage(def.global, fields, ts)
            }
        }
        return out
    }

    private class FieldDef(val number: Int, val size: Int, val baseType: Int)
    private class Definition(val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devBytes: Int)

    private fun readFields(bytes: ByteArray, start: Int, def: Definition): Pair<Map<Int, Long>, Int> {
        var pos = start
        val values = HashMap<Int, Long>()
        def.fields.forEach { f ->
            if (pos + f.size > bytes.size) throw FitException("data message cut off")
            readValue(bytes, pos, f, def.bigEndian)?.let { values[f.number] = it }
            pos += f.size
        }
        return values to pos + def.devBytes
    }

    /** The first value of a field (arrays and strings aren't needed), or null when invalid. */
    private fun readValue(b: ByteArray, pos: Int, f: FieldDef, be: Boolean): Long? {
        val type = f.baseType and 0x1F
        return when (type) {
            0x00, 0x02, 0x0A, 0x0D -> // enum, uint8, uint8z, byte
                if (f.size < 1) null else (b[pos].toInt() and 0xFF).toLong().takeIf { if (type == 0x0A) it != 0L else it != 0xFFL }
            0x01 -> if (f.size < 1) null else b[pos].toLong().takeIf { it != 0x7FL } // sint8
            0x03 -> if (f.size < 2) null else (if (be) u16be(b, pos) else u16le(b, pos)).toShort().toLong().takeIf { it != 0x7FFFL }
            0x04, 0x0B -> if (f.size < 2) null else (if (be) u16be(b, pos) else u16le(b, pos)).toLong().takeIf { if (type == 0x0B) it != 0L else it != 0xFFFFL }
            0x05 -> if (f.size < 4) null else (if (be) u32be(b, pos) else u32le(b, pos)).toInt().toLong().takeIf { it != 0x7FFFFFFFL }
            0x06, 0x0C -> if (f.size < 4) null else (if (be) u32be(b, pos) else u32le(b, pos)).takeIf { if (type == 0x0C) it != 0L else it != 0xFFFFFFFFL }
            else -> null // strings, floats, 64-bit: not used by the app
        }
    }

    private fun u16le(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u16be(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32le(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
    private fun u32be(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    const val TIMESTAMP_FIELD = 253

    /** Seconds between 1970-01-01 and the FIT epoch, 1989-12-31 00:00 UTC. */
    const val FIT_EPOCH_SECONDS = 631_065_600L
}
