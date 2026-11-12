package com.yonizilber.flownics.domain.library

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FamiliesTest {

    private fun e(id: Long, name: String, of: Long? = null) = Exercise(id = id, name = name, category = ExerciseCategory.STRENGTH, variationOf = of)

    @Test
    fun stylesGroupUnderTheirDefault() {
        val lib = listOf(e(1, "Face pull"), e(2, "Rope face pull", of = 1), e(3, "Band face pull", of = 1), e(4, "Squat"))
        val families = Families.group(lib)
        assertEquals(listOf("Face pull", "Squat"), families.map { it.base.name })
        assertEquals(listOf("Band face pull", "Rope face pull"), families[0].styles.map { it.name })
        assertEquals(1L, Families.familyOf(lib[2], lib).base.id)
    }

    @Test
    fun aChainEndsAtTheTopAndALoopDoesNotHang() {
        val chain = listOf(e(1, "A"), e(2, "B", of = 1), e(3, "C", of = 2))
        assertEquals(1L, Families.baseOf(chain[2], chain.associateBy { it.id }).id)
        val loop = listOf(e(1, "A", of = 2), e(2, "B", of = 1))
        Families.group(loop) // must return
        // A missing base: the exercise stands on its own.
        assertEquals(5L, Families.baseOf(e(5, "Orphan", of = 99), emptyMap()).id)
    }
}
