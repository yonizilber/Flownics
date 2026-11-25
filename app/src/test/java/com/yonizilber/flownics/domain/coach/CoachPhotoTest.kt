package com.yonizilber.flownics.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Test

class CoachPhotoTest {
    @Test
    fun theCoachReadsTheTextThenEachPhotosDescription() {
        val user = CoachEntry.User(1, 0, "Is this right?", listOf(CoachPhoto("a.jpg", "Deadlift, rounded back"), CoachPhoto("b.jpg", "Bar at mid-shin")))
        assertEquals(
            "Is this right?\n[Photo 1 the user sent, as described when it was sent: Deadlift, rounded back]\n[Photo 2 the user sent, as described when it was sent: Bar at mid-shin]",
            user.forCoach(),
        )
        // A photo alone, with no words.
        assertEquals("[Photo 1 the user sent, as described when it was sent: A plan]", CoachEntry.User(2, 0, "", listOf(CoachPhoto("c.jpg", "A plan"))).forCoach())
        assertEquals("Hi", CoachEntry.User(3, 0, "Hi").forCoach())
    }
}
