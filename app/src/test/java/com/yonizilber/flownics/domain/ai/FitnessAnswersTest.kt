package com.yonizilber.flownics.domain.ai

import org.junit.Assert.assertThrows
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.SetTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessAnswersTest {

    @Test
    fun fillKeepsKnownValuesAndReportsTheRest() {
        val s = FitnessAnswers.exerciseFill(
            """
            {"summary":" A squat. ","category":"strength","measureMode":"REPS_WEIGHT","unilateral":false,"equipment":"barbell",
             "primaryMuscles":["QUADS","GLUTES","QUADRATUS_LUMBORUM"],"secondaryMuscles":["GLUTES","HAMSTRINGS"],
             "instructions":["- Stand tall","Sit back and down",""],"cues":["Brace"," ",""],"stages":[{"name":"Down","description":"slow","seconds":3},{"name":"","description":"","seconds":0}],
             "confidence":"HIGH","level":9,"caveats":""}
            """.trimIndent(),
        )
        assertEquals("A squat.", s.summary)
        assertEquals(ExerciseCategory.STRENGTH, s.category)
        assertEquals(setOf(Muscle.QUADS, Muscle.GLUTES), s.primaryMuscles)
        // A muscle in both lists stays primary only.
        assertEquals(setOf(Muscle.HAMSTRINGS), s.secondaryMuscles)
        assertEquals(listOf("QUADRATUS_LUMBORUM"), s.unknownValues)
        assertEquals(listOf("Stand tall", "Sit back and down"), s.instructions)
        assertEquals(listOf("Brace"), s.cues)
        // Off the 1-5 scale: not rated, rather than clamped into a level the AI didn't give.
        assertNull(s.level)
        assertEquals(1, s.stages.size)
        assertEquals(3, s.stages.single().durationSeconds)
    }

    @Test
    fun refiningListsItsChanges() {
        val s = FitnessAnswers.exerciseFill(
            """
            {"summary":"","category":"STRENGTH","measureMode":"REPS","unilateral":false,"equipment":"","primaryMuscles":[],"secondaryMuscles":[],
             "instructions":[],"cues":[],"stages":[],"confidence":"MEDIUM","level":2,"caveats":"",
             "changes":[" Step 2 split in two: it held two actions. ",""]}
            """.trimIndent(),
        )
        assertEquals(listOf("Step 2 split in two: it held two actions."), s.changes)
        // An older answer without the field still reads.
        assertEquals(emptyList<String>(), FitnessAnswers.exerciseFill("""{"category":"STRENGTH","measureMode":"REPS"}""").changes)
    }

    @Test(expected = AiException.BadResponse::class)
    fun aWrongShapeFailsLoudly() {
        FitnessAnswers.exerciseFill("not json")
    }

    @Test
    fun aGeneratedWorkoutOnlyUsesLibraryExercisesAndTheirMeasures() {
        val library = mapOf(
            1L to Exercise(id = 1, name = "Squat", category = ExerciseCategory.STRENGTH, measureMode = MeasureMode.REPS_WEIGHT),
            2L to Exercise(id = 2, name = "Plank", category = ExerciseCategory.CORE, measureMode = MeasureMode.TIME),
        )
        val w = FitnessAnswers.workout(
            """
            {"name":"Legs","description":"","explanation":"why","missingExercises":[{"name":"Leg press","why":"volume"}],
             "blocks":[
               {"title":"Main","structure":"STRAIGHT_SETS","rounds":1,"roundRestSeconds":0,"items":[
                 {"exerciseId":1,"sets":99,"reps":5,"seconds":30,"weightKg":60,"distanceMeters":0,"restSeconds":120},
                 {"exerciseId":7,"sets":3,"reps":10,"seconds":0,"weightKg":0,"distanceMeters":0,"restSeconds":60}]},
               {"title":"Core","structure":"CIRCUIT","rounds":3,"roundRestSeconds":30,"items":[
                 {"exerciseId":2,"sets":1,"reps":12,"seconds":45,"weightKg":0,"distanceMeters":0,"restSeconds":15}]},
               {"title":"Ghost","structure":"STRAIGHT_SETS","rounds":1,"roundRestSeconds":0,"items":[
                 {"exerciseId":9,"sets":3,"reps":10,"seconds":0,"weightKg":0,"distanceMeters":0,"restSeconds":60}]}
             ]}
            """.trimIndent(),
            library,
        )
        assertEquals(2, w.skippedItems)
        // The block whose only exercise doesn't exist is dropped rather than left empty.
        assertEquals(listOf("Main", "Core"), w.template.blocks.map { it.title })
        val squat = w.template.blocks[0].items.single()
        assertEquals(20, squat.sets)
        assertEquals(SetTarget(reps = 5, weightKg = 60.0), squat.target)
        val plank = w.template.blocks[1]
        assertEquals(BlockStructure.CIRCUIT, plank.structure)
        assertEquals(SetTarget(seconds = 45), plank.items.single().target)
        assertEquals(0L, w.template.id)
        assertEquals("Leg press", w.missingExercises.single().name)
    }

    @Test
    fun reviewDefaultsUnknownSeverityToInfo() {
        val r = FitnessAnswers.review(
            """{"rating":"NEEDS_WORK","verdict":"v","balance":"b","issues":[{"severity":"??","text":"t"},{"severity":"PROBLEM","text":" "}],"suggestions":["s"]}""",
        )
        assertEquals(ReviewRating.NEEDS_WORK, r.rating)
        assertEquals(listOf(ReviewIssue(IssueSeverity.INFO, "t")), r.issues)
    }

    @Test
    fun costAndModelLookup() {
        val usage = AiUsage(inputTokens = 1_000_000, outputTokens = 100_000, webSearches = 3)
        assertEquals(4.0 + 2.0 + 0.03, AiModel.CLAUDE_OPUS.costUsd(usage), 1e-9)
        // Gemini's search fee is unknown here, so it is not guessed.
        assertEquals(0.75 + 0.375, AiModel.GEMINI_FLASH.costUsd(usage), 1e-9)
        assertEquals(AiModel.CLAUDE_OPUS, AiModel.byId("claude-opus-5-5"))
        assertNull(AiModel.byId("claude-opus-4-8"))
        assertTrue(AiModel.forProvider(AiProvider.CLAUDE).all { it.provider == AiProvider.CLAUDE })
    }

    @Test
    fun variationsCarryLevelsOnTheSameScale() {
        val v = FitnessAnswers.variations(
            """{"baseLevel":3,"notes":"","variations":[
                {"name":"Band face pull","kind":"EASIER","difference":"d","equipment":"band","category":"STRENGTH","why":"w","level":2},
                {"name":" ","kind":"HARDER","difference":"","equipment":"","category":"","why":"","level":4},
                {"name":"Face pull with external rotation","kind":"HARDER","difference":"d","equipment":"cable","category":"??","why":"w","level":0}]}""",
        )
        assertEquals(3, v.baseLevel)
        assertEquals(listOf("Band face pull", "Face pull with external rotation"), v.variations.map { it.name })
        assertEquals(listOf(2, null), v.variations.map { it.level })
        assertNull(v.variations[1].category)
    }

    @Test
    fun photoDescriptionsComeOnePerPhotoOrFail() {
        val two = """{"photos":[{"description":"Squat, knees caving in"},{"description":"Watch: 12:04 for 3 km"}]}"""
        assertEquals(listOf("Squat, knees caving in", "Watch: 12:04 for 3 km"), FitnessAnswers.photoDescriptions(two, 2))
        // One short, or one empty: the coach would be blind to a photo, so it fails.
        assertThrows(AiException.BadResponse::class.java) { FitnessAnswers.photoDescriptions(two, 3) }
        assertThrows(AiException.BadResponse::class.java) { FitnessAnswers.photoDescriptions("""{"photos":[{"description":" "}]}""", 1) }
    }
}
