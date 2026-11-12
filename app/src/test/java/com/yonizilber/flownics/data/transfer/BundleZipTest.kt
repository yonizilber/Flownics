package com.yonizilber.flownics.data.transfer

import com.yonizilber.flownics.data.local.db.entity.MediaJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundleZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val manifest = BundleManifest(kind = BundleKind.SHARE, createdAtMillis = 1, appVersion = "test")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(40) { 1 }

    private fun exercise(vararg media: MediaJson) =
        ExerciseDto(id = 7, name = "Squat", category = "STRENGTH", measureMode = "REPS", media = media.toList())

    private fun dataWith(vararg media: MediaJson) = BundleData(exercises = listOf(exercise(*media)))

    private fun write(data: BundleData, media: Map<String, ByteArray> = emptyMap(), m: BundleManifest = manifest): ByteArray {
        val files = media.mapValues { (name, bytes) -> File(tmp.newFolder(), name).apply { writeBytes(bytes) } }
        return ByteArrayOutputStream().also { BundleWriter(json).write(it, m, data, files) }.toByteArray()
    }

    /** A zip built by hand, entry by entry, for files the app's own writer would never make. */
    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun manifestBytes(m: BundleManifest = manifest) = json.encodeToString(BundleManifest.serializer(), m).toByteArray()
    private fun dataBytes(d: BundleData) = json.encodeToString(BundleData.serializer(), d).toByteArray()

    private fun read(bytes: ByteArray, staging: File = tmp.newFolder("staging"), limits: BundleLimits = BundleLimits()) =
        BundleReader(json, limits).read(ByteArrayInputStream(bytes), staging)

    private inline fun <reified T : BundleException> assertRejected(staging: File = tmp.newFolder(), block: (File) -> Unit) {
        try {
            block(staging)
        } catch (e: BundleException) {
            assertTrue("expected ${T::class.simpleName}, got $e", e is T)
            // Nothing half-read is left behind.
            assertEquals(emptyList<String>(), staging.list()?.toList().orEmpty())
            return
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    @Test
    fun aWrittenFileReadsBackWithItsMedia() {
        val data = dataWith(MediaJson("IMAGE", "a1.jpg"), MediaJson("LINK", "https://youtu.be/x"))
        val bundle = read(write(data, mapOf("a1.jpg" to jpeg)))
        assertEquals(data, bundle.data)
        assertEquals(BundleKind.SHARE, bundle.manifest.kind)
        assertArrayEquals(jpeg, bundle.media.getValue("a1.jpg").readBytes())
        // Staged under a name of the reader's own choosing.
        assertTrue(bundle.media.getValue("a1.jpg").name != "a1.jpg")
    }

    @Test
    fun aPathInAMediaNameIsRefused() {
        val data = dataWith(MediaJson("IMAGE", "a1.jpg"))
        val zip = rawZip("manifest.json" to manifestBytes(), "data.json" to dataBytes(data), "media/../a1.jpg" to jpeg)
        assertRejected<BundleException.NotABundle> { read(zip, it) }
    }

    @Test
    fun aMediaNameInTheDataThatIsAPathIsRefused() {
        val zip = rawZip("manifest.json" to manifestBytes(), "data.json" to dataBytes(dataWith(MediaJson("IMAGE", "../../shared_prefs/x.xml"))))
        assertRejected<BundleException.Invalid> { read(zip, it) }
    }

    @Test
    fun aFileThatIsNotWhatItsNameSaysIsRefused() {
        val zip = write(dataWith(MediaJson("IMAGE", "a1.jpg")), mapOf("a1.jpg" to "<html>not a photo</html>".toByteArray()))
        assertRejected<BundleException.BadMedia> { read(zip, it) }
    }

    @Test
    fun aMissingMediaFileIsRefused() {
        val zip = rawZip("manifest.json" to manifestBytes(), "data.json" to dataBytes(dataWith(MediaJson("VIDEO", "v.mp4"))))
        assertRejected<BundleException.BadMedia> { read(zip, it) }
    }

    @Test
    fun aNewerFormatIsRefusedWithItsOwnReason() {
        val zip = write(BundleData(), m = manifest.copy(formatVersion = BUNDLE_FORMAT_VERSION + 1))
        assertRejected<BundleException.NewerVersion> { read(zip, it) }
    }

    @Test
    fun sizeIsCountedWhileReadingNotTakenFromTheZip() {
        val zip = write(dataWith(MediaJson("IMAGE", "a1.jpg")), mapOf("a1.jpg" to jpeg + ByteArray(5_000)))
        assertRejected<BundleException.TooLarge> { read(zip, it, BundleLimits(maxImageBytes = 1_000)) }
    }

    @Test
    fun entriesOutOfOrderOrUnknownAreHandled() {
        // Data before the manifest: not ours.
        assertRejected<BundleException.NotABundle> { read(rawZip("data.json" to dataBytes(BundleData()), "manifest.json" to manifestBytes()), it) }
        // Extra files and unused media are skipped, never extracted.
        val staging = tmp.newFolder("extra")
        val bundle = read(
            rawZip(
                "manifest.json" to manifestBytes(),
                "data.json" to dataBytes(BundleData()),
                "media/unused.jpg" to jpeg,
                "readme.txt" to "hi".toByteArray(),
            ),
            staging,
        )
        assertTrue(bundle.media.isEmpty())
        assertEquals(emptyList<String>(), staging.list()?.toList().orEmpty())
    }

    @Test
    fun somethingThatIsNotAZipIsRefused() {
        assertRejected<BundleException.NotABundle> { read("just text".toByteArray(), it) }
    }
}
