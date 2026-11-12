package com.yonizilber.flownics.data.transfer

import com.yonizilber.flownics.data.local.db.entity.ActiveRestJson
import com.yonizilber.flownics.data.local.db.entity.BlockJson
import com.yonizilber.flownics.data.local.db.entity.ItemJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BundleMergeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun exercise(id: Long, name: String, muscles: List<String> = emptyList()) =
        ExerciseDto(id = id, name = name, category = "STRENGTH", measureMode = "REPS", primaryMuscles = muscles)

    private val blocks = listOf(
        BlockJson(
            structure = "STRAIGHT_SETS",
            roundActiveRest = ActiveRestJson.Specific(3),
            items = listOf(ItemJson(exerciseId = 1, activeRest = ActiveRestJson.Specific(2)), ItemJson(exerciseId = 2)),
        ),
    )
    private val data = BundleData(
        exercises = listOf(exercise(1, "Squat"), exercise(2, "Hip stretch"), exercise(3, "Breathing")),
        workouts = listOf(WorkoutDto(id = 10, name = "Legs", blocks = blocks)),
    )

    @Test
    fun sameNamedExercisesAreReusedOrAddedAsNumberedCopies() {
        val mine = listOf(100L to " squat ", 101L to "Legs day")
        val reuse = BundleMerge.planShare(data, mine, listOf("Legs"), reuseExisting = true)
        assertEquals(mapOf(1L to 100L), reuse.reuse)
        assertEquals(listOf("Hip stretch", "Breathing"), reuse.insert.map { it.name })
        assertEquals("Legs (2)", reuse.workouts.single().name)

        val copies = BundleMerge.planShare(data, mine, emptyList(), reuseExisting = false)
        assertEquals(emptyMap<Long, Long>(), copies.reuse)
        assertEquals(listOf("Squat (2)", "Hip stretch", "Breathing"), copies.insert.map { it.name })
    }

    @Test
    fun remappingReachesItemsAndBothKindsOfActiveRest() {
        val out = BundleMerge.remap(blocks, mapOf(1L to 11L, 2L to 12L, 3L to 13L))
        assertEquals(ActiveRestJson.Specific(13), out[0].roundActiveRest)
        assertEquals(listOf(11L, 12L), out[0].items.map { it.exerciseId })
        assertEquals(ActiveRestJson.Specific(12), out[0].items[0].activeRest)
        assertEquals(setOf(1L, 2L, 3L), BundleMerge.referencedExercises(blocks))
    }

    @Test(expected = BundleException.Invalid::class)
    fun aSharedWorkoutMustBringItsExercises() {
        BundleMerge.validate(data.copy(exercises = data.exercises.take(2)), BundleKind.SHARE, json)
    }

    @Test
    fun aBackupMayKeepAWorkoutThatUsesADeletedExercise() {
        BundleMerge.validate(data.copy(exercises = data.exercises.take(2)), BundleKind.BACKUP, json)
    }

    @Test(expected = BundleException.Invalid::class)
    fun anUnknownMuscleFailsTheWholeFile() {
        BundleMerge.validate(data.copy(exercises = listOf(exercise(1, "Squat", listOf("QUADRATUS_LUMBORUM")))), BundleKind.BACKUP, json)
    }

    @Test(expected = BundleException.Invalid::class)
    fun historyNeverComesInAShare() {
        val session = SessionDto(id = 1, workoutName = "Legs", plan = com.yonizilber.flownics.data.local.db.entity.SessionPlanJson(emptyList(), emptyList()), startedAtMillis = 1)
        BundleMerge.validate(data.copy(sessions = listOf(session)), BundleKind.SHARE, json)
    }

    @Test
    fun uniqueNamesCountUp() {
        val taken = mutableSetOf("plank", "plank (2)")
        assertEquals("Plank (3)", BundleMerge.uniqueName("Plank", taken))
        assertEquals("Plank (4)", BundleMerge.uniqueName("Plank", taken))
    }
}
