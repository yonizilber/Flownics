package com.yonizilber.flownics.data.remote.youtube

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Recognising YouTube video links. Pure, so it can be unit-tested. */
object YouTubeLinks {

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    /** The 11-character video id of a watch, short, embed or youtu.be link; null for anything else. */
    fun videoId(url: String): String? {
        val match = Regex("^https?://([^/?#]+)([^?#]*)(\\?[^#]*)?").find(url.trim()) ?: return null
        val host = match.groupValues[1].lowercase()
        val path = match.groupValues[2]
        val query = match.groupValues[3].removePrefix("?")
        val id = when {
            host == "youtu.be" -> path.removePrefix("/").substringBefore('/')
            host in HOSTS && path == "/watch" ->
                query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            host in HOSTS && (path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/")) ->
                path.split('/').getOrNull(2)
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    /** Opens YouTube's own search: free, and no AI involved. */
    fun searchUrl(query: String) = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
}

data class YouTubeVideo(val id: String, val title: String, val channel: String, val thumbnailUrl: String?) {
    val url: String get() = YouTubeLinks.watchUrl(id)
}

/**
 * Asks YouTube itself whether a video exists and what it is really called (its public oEmbed
 * endpoint, no key needed), so a link an AI wrote is never shown under a title the AI made up.
 * Only the video's URL is sent.
 */
@Singleton
class YouTubeVerifier @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /** null: no such public video (or its owner doesn't allow embedding, so it can't be checked). */
    suspend fun lookUp(videoId: String): YouTubeVideo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.youtube.com/oembed?format=json&url=" + Uri.encode(YouTubeLinks.watchUrl(videoId)))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val body = json.decodeFromString(OEmbed.serializer(), response.body?.string().orEmpty())
                    YouTubeVideo(videoId, body.title, body.authorName, body.thumbnailUrl)
                }
                response.code in 400..499 -> null
                else -> throw IOException("YouTube returned ${response.code}")
            }
        }
    }

    @Serializable
    private data class OEmbed(
        val title: String = "",
        @SerialName("author_name") val authorName: String = "",
        @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    )
}
