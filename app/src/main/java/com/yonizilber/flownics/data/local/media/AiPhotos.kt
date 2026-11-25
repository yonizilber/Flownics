package com.yonizilber.flownics.data.local.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.yonizilber.flownics.domain.ai.AiImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos the user sends to the AI, made ready on the phone: turned upright, shrunk so the longer
 * side is at most [MAX_EDGE] pixels, and re-encoded as JPEG. Re-encoding keeps only the pixels, so
 * the location and every other detail the camera wrote into the file never leave the phone.
 *
 * Size is also cost: Claude bills a photo by its area (one token per 28×28 pixels, about 1,600
 * tokens at this size, against up to ~4,800 for a full phone photo); Gemini bills a fixed amount.
 */
@Singleton
class AiPhotos @Inject constructor(@ApplicationContext private val context: Context) {

    /** Photos picked for a request not sent yet; cleared at each app start. */
    private val pendingDir: File get() = File(context.cacheDir, PENDING_DIR).apply { mkdirs() }

    /** Photos kept with the coach conversation, to show in it. */
    private val coachDir: File get() = File(context.filesDir, COACH_DIR).apply { mkdirs() }

    /** Shrinks and re-encodes the picked photo into a new file; throws [IOException] when it can't be read. */
    suspend fun prepare(uri: Uri): File = withContext(Dispatchers.IO) {
        val bitmap = decode(uri) ?: throw IOException("The photo could not be read")
        val scaled = fit(bitmap)
        val file = File(pendingDir, UUID.randomUUID().toString() + ".jpg")
        file.outputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IOException("The photo could not be encoded")
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        file
    }

    /** A prepared photo as the AI gets it. */
    suspend fun load(file: File): AiImage = withContext(Dispatchers.IO) {
        if (!file.isFile) throw IOException("The photo is no longer on the phone")
        AiImage("image/jpeg", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    }

    /** Moves a prepared photo into the coach's folder; returns its name there. */
    suspend fun keepForCoach(file: File): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString() + ".jpg"
        val target = File(coachDir, name)
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = false)
            file.delete()
        }
        name
    }

    /** A sent photo taken back (the message was withdrawn): picked again, not yet sent. */
    suspend fun backToPending(name: String): File = withContext(Dispatchers.IO) {
        val from = coachFile(name)
        val target = File(pendingDir, name)
        if (!from.renameTo(target)) {
            from.copyTo(target, overwrite = true)
            from.delete()
        }
        target
    }

    /** A coach photo by its name; names are made here, so anything that could leave the folder is refused. */
    fun coachFile(name: String): File {
        require(NAME.matches(name)) { "Invalid photo name: $name" }
        return File(coachDir, name)
    }

    fun deleteCoachPhotos(names: Collection<String>) {
        names.filter(NAME::matches).forEach { File(coachDir, it).delete() }
    }

    fun discardPending(files: Collection<File>) {
        files.filter { it.parentFile == pendingDir }.forEach { it.delete() }
    }

    /** Leftovers of requests never sent (the app was closed with photos picked), after a day. */
    fun clearPending() {
        val before = System.currentTimeMillis() - DAY_MS
        pendingDir.listFiles()?.filter { it.lastModified() < before }?.forEach { it.delete() }
    }

    private fun decode(uri: Uri): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Turns the photo upright by itself, and decodes it already near the size needed.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val s = info.size
            val ratio = MAX_EDGE.toFloat() / max(s.width, s.height)
            if (ratio < 1f) decoder.setTargetSize((s.width * ratio).roundToInt().coerceAtLeast(1), (s.height * ratio).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        val degrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (bitmap == null || degrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun fit(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true)
    }

    companion object {
        const val MAX_EDGE = 1280
        private const val QUALITY = 85
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val PENDING_DIR = "ai-photos"
        private const val COACH_DIR = "coach-photos"
        private val NAME = Regex("[0-9a-f-]{36}\\.jpg")

        /** At most this many photos in one request. */
        const val MAX_PER_REQUEST = 3
    }
}
