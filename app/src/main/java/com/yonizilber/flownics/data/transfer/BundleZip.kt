package com.yonizilber.flownics.data.transfer

import com.yonizilber.flownics.data.local.media.MediaStorage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Why a file can't be used. Each case has its own message for the user; nothing is half-imported. */
sealed class BundleException(message: String) : IOException(message) {
    class NotABundle(val detail: String) : BundleException("Not a Flownics file: $detail")
    class NewerVersion(val version: Int) : BundleException("Made by a newer version of the app (format $version)")
    class TooLarge(val what: String, val limitBytes: Long) : BundleException("$what is over $limitBytes bytes")
    class BadMedia(val name: String, val detail: String) : BundleException("Media file $name: $detail")
    class Invalid(val detail: String) : BundleException("The file's contents don't add up: $detail")
}

/** Caps checked while reading, by counting bytes rather than trusting what the zip claims. */
data class BundleLimits(
    val maxManifestBytes: Long = 64L * 1024,
    val maxDataBytes: Long = 50L * 1024 * 1024,
    val maxImageBytes: Long = 20L * 1024 * 1024,
    val maxVideoBytes: Long = MediaStorage.MAX_VIDEO_BYTES,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntries: Int = 10_000,
)

/** A file that passed every check. [media] maps each name the data uses to its copy in the staging folder. */
class ReadBundle(val manifest: BundleManifest, val data: BundleData, val media: Map<String, File>)

private const val MANIFEST = "manifest.json"
private const val DATA = "data.json"
private const val MEDIA_PREFIX = "media/"

// Media names the app itself writes are UUIDs with an extension; anything else (a path, "..", a
// hidden file) is refused. Entry names are only ever compared, never used as paths on disk.
private val SAFE_MEDIA_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}(\\.[A-Za-z0-9]{1,5})?")

fun isSafeMediaName(name: String) = SAFE_MEDIA_NAME.matches(name)

class BundleWriter(private val json: Json) {

    /** Writes manifest, data and then [media] (name in the data → file), in the order [BundleReader] requires. */
    fun write(out: OutputStream, manifest: BundleManifest, data: BundleData, media: Map<String, File>) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(BundleManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA))
            zip.write(json.encodeToString(BundleData.serializer(), data).toByteArray())
            zip.closeEntry()
            // Photos and videos are already compressed; deflating them again only costs time.
            zip.setLevel(Deflater.NO_COMPRESSION)
            media.forEach { (name, file) ->
                require(isSafeMediaName(name)) { "Unsafe media name $name" }
                zip.putNextEntry(ZipEntry(MEDIA_PREFIX + name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

class BundleReader(private val json: Json, private val limits: BundleLimits = BundleLimits()) {

    /**
     * Reads and checks a whole file, copying the media it uses into [staging] under fresh names.
     * Throws [BundleException] on anything unexpected, leaving [staging] as it was.
     */
    fun read(input: InputStream, staging: File): ReadBundle {
        staging.mkdirs()
        val staged = mutableMapOf<String, File>()
        try {
            return readEntries(input, staging, staged)
        } catch (e: Exception) {
            staged.values.forEach { it.delete() }
            throw when (e) {
                is BundleException -> e
                is ZipException -> BundleException.NotABundle("not a readable zip (${e.message})")
                is SerializationException -> BundleException.NotABundle("unreadable data (${e.message})")
                is IllegalArgumentException -> BundleException.NotABundle(e.message ?: "unreadable data")
                else -> e
            }
        }
    }

    private fun readEntries(input: InputStream, staging: File, staged: MutableMap<String, File>): ReadBundle {
        var manifest: BundleManifest? = null
        var data: BundleData? = null
        var referenced: Map<String, String> = emptyMap()
        var total = 0L
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > limits.maxEntries) throw BundleException.TooLarge("The number of files", limits.maxEntries.toLong())
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == MANIFEST -> {
                        if (manifest != null || data != null) throw BundleException.NotABundle("manifest out of place")
                        val bytes = readLimited(zip, limits.maxManifestBytes, "The manifest")
                        total += bytes.size
                        manifest = json.decodeFromString(BundleManifest.serializer(), bytes.decodeToString()).also(::checkManifest)
                    }
                    name == DATA -> {
                        if (manifest == null || data != null) throw BundleException.NotABundle("data out of place")
                        val bytes = readLimited(zip, limits.maxDataBytes, "The data")
                        total += bytes.size
                        data = json.decodeFromString(BundleData.serializer(), bytes.decodeToString()).also {
                            referenced = referencedMedia(it)
                        }
                    }
                    name.startsWith(MEDIA_PREFIX) -> {
                        if (data == null) throw BundleException.NotABundle("media before data")
                        val mediaName = name.removePrefix(MEDIA_PREFIX)
                        if (!isSafeMediaName(mediaName)) throw BundleException.NotABundle("unsafe file name in the zip")
                        if (mediaName in staged) throw BundleException.NotABundle("the same file twice")
                        // Files the data doesn't use are skipped, never written anywhere.
                        val kind = referenced[mediaName]
                        if (kind != null) {
                            val limit = if (kind == KIND_VIDEO) limits.maxVideoBytes else limits.maxImageBytes
                            val target = File(staging, UUID.randomUUID().toString())
                            staged[mediaName] = target
                            total += copyLimited(zip, target, limit, mediaName, limits.maxTotalBytes - total)
                            checkMediaType(target, kind, mediaName)
                        }
                    }
                    // Anything else (a future addition, or junk) is ignored and never extracted.
                    else -> Unit
                }
                if (total > limits.maxTotalBytes) throw BundleException.TooLarge("The file", limits.maxTotalBytes)
            }
        }
        val m = manifest ?: throw BundleException.NotABundle("no manifest")
        val d = data ?: throw BundleException.NotABundle("no data")
        referenced.keys.firstOrNull { it !in staged }?.let { throw BundleException.BadMedia(it, "missing from the file") }
        return ReadBundle(m, d, staged.toMap())
    }

    private fun checkManifest(manifest: BundleManifest) {
        if (manifest.format != BUNDLE_FORMAT) throw BundleException.NotABundle("format '${manifest.format}'")
        if (manifest.formatVersion > BUNDLE_FORMAT_VERSION) throw BundleException.NewerVersion(manifest.formatVersion)
        if (manifest.formatVersion < 1) throw BundleException.NotABundle("format version ${manifest.formatVersion}")
    }

    /** Media file name → kind, for every photo and video the exercises point to. */
    private fun referencedMedia(data: BundleData): Map<String, String> {
        val out = mutableMapOf<String, String>()
        data.exercises.flatMap { it.media }.forEach { media ->
            when (media.kind) {
                KIND_IMAGE, KIND_VIDEO -> {
                    if (!isSafeMediaName(media.location)) throw BundleException.Invalid("media name '${media.location}'")
                    val earlier = out.put(media.location, media.kind)
                    if (earlier != null && earlier != media.kind) throw BundleException.Invalid("${media.location} is both a photo and a video")
                }
                KIND_LINK -> Unit
                else -> throw BundleException.Invalid("media kind '${media.kind}'")
            }
        }
        return out
    }

    private fun readLimited(input: InputStream, limit: Long, what: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            if (size > limit) throw BundleException.TooLarge(what, limit)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, target: File, limit: Long, name: String, roomLeft: Long): Long {
        val buffer = ByteArray(BUFFER)
        var size = 0L
        target.outputStream().use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                if (size > limit) throw BundleException.TooLarge(name, limit)
                if (size > roomLeft) throw BundleException.TooLarge("The file", limits.maxTotalBytes)
                out.write(buffer, 0, read)
            }
        }
        return size
    }

    /** By the first bytes, not the name: a photo must be a JPEG, PNG or WebP, a video MP4/3GP or WebM. */
    private fun checkMediaType(file: File, kind: String, name: String) {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < head.size) throw BundleException.BadMedia(name, "too short")
        fun at(offset: Int, vararg bytes: Int) = bytes.withIndex().all { (i, b) -> head[offset + i] == b.toByte() }
        val ok = when (kind) {
            KIND_IMAGE -> at(0, 0xFF, 0xD8, 0xFF) ||
                at(0, 0x89, 0x50, 0x4E, 0x47) ||
                (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50))
            else -> at(4, 0x66, 0x74, 0x79, 0x70) || at(0, 0x1A, 0x45, 0xDF, 0xA3)
        }
        if (!ok) throw BundleException.BadMedia(name, if (kind == KIND_IMAGE) "not a photo" else "not a video")
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

// MediaKind names, as stored in MediaJson.kind.
internal const val KIND_IMAGE = "IMAGE"
internal const val KIND_VIDEO = "VIDEO"
internal const val KIND_LINK = "LINK"
