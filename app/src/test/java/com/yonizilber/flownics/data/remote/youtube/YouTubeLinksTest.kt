package com.yonizilber.flownics.data.remote.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {

    @Test
    fun recognisesEveryCommonShapeOfVideoLink() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?feature=share&v=$id&t=30",
            "https://m.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
        ).forEach { assertEquals(it, id, YouTubeLinks.videoId(it)) }
    }

    @Test
    fun rejectsAnythingElse() {
        listOf(
            "https://www.youtube.com/@channel",
            "https://www.youtube.com/watch?v=short",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "javascript:alert(1)",
        ).forEach { assertNull(it, YouTubeLinks.videoId(it)) }
    }
}
