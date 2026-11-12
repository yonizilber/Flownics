package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Test

class MuscleSelectionTest {

    @Test
    fun tapCyclesNonePrimarySecondaryNone() {
        val start = MuscleSelection()
        val once = start.cycle(Muscle.CHEST)
        assertEquals(MuscleSelection(primary = setOf(Muscle.CHEST)), once)
        val twice = once.cycle(Muscle.CHEST)
        assertEquals(MuscleSelection(secondary = setOf(Muscle.CHEST)), twice)
        assertEquals(start, twice.cycle(Muscle.CHEST))
    }

    @Test
    fun cyclingOneMuscleLeavesOthersAlone() {
        val selection = MuscleSelection(primary = setOf(Muscle.QUADS), secondary = setOf(Muscle.CALVES))
        assertEquals(
            MuscleSelection(primary = setOf(Muscle.QUADS, Muscle.GLUTES), secondary = setOf(Muscle.CALVES)),
            selection.cycle(Muscle.GLUTES),
        )
    }

    @Test
    fun moveReordersAndIgnoresOutOfRange() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), list.move(0, 1))
        assertEquals(listOf("a", "c", "b"), list.move(2, 1))
        assertEquals(list, list.move(0, -1))
        assertEquals(list, list.move(2, 3))
    }
}
