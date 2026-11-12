package com.yonizilber.flownics.domain.coach

import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.SegmentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CoachProposalsTest {

    private val facePull = Exercise(id = 4, name = "Face pull", category = ExerciseCategory.STRENGTH, cues = listOf("Elbows high"))
    private val bandPull = Exercise(id = 5, name = "Band face pull", category = ExerciseCategory.STRENGTH)
    private val library = mapOf(4L to facePull, 5L to bandPull)
    private val zone = ZoneId.of("Asia/Jerusalem")

    private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun change(exerciseId: Long, fields: String, extra: String = "") = args(
        """{"exercise_id":$exerciseId,"change":[$fields],"name":"","categories":[],"primary_muscles":[],"secondary_muscles":[],
           "equipment":"","instructions":[],"cues":[],"notes":"","level":0,"measure_mode":"REPS","unilateral":false,
           "style_of_exercise_id":0,"reason":"Keeps the cues"$extra}""".replace("\n", ""),
    )

    @Test
    fun onlyTheListedFieldsChange() {
        val a = change(4, "\"CUES\"").toMutableMap().apply { put("cues", Json.parseToJsonElement("""["Elbows high","Pull to the forehead"]""")) }
        val c = CoachProposals.exerciseChange(JsonObject(a), library)
        assertEquals(listOf(ExerciseField.CUES), c.fields)
        assertEquals(listOf("Elbows high", "Pull to the forehead"), c.proposed.cues)
        // Everything not listed is as it was, though the arguments held other (empty) values.
        assertEquals("Face pull", c.proposed.name)
        assertEquals(facePull.copy(cues = c.proposed.cues), c.applied(setOf(ExerciseField.CUES)))
        assertEquals(facePull, c.applied(emptySet()))
    }

    @Test
    fun aStyleJoinsAFamily() {
        val a = change(5, "\"STYLE_OF\"").toMutableMap().apply { put("style_of_exercise_id", Json.parseToJsonElement("4")) }
        val c = CoachProposals.exerciseChange(JsonObject(a), library)
        assertEquals(4L, c.applied(setOf(ExerciseField.STYLE_OF)).variationOf)
    }

    @Test
    fun badProposalsSayWhatToFix() {
        val unknown = runCatching { CoachProposals.exerciseChange(change(99, "\"CUES\""), library) }.exceptionOrNull()
        assertTrue(unknown is ProposalException && "exercises_search" in unknown.message!!)
        val same = runCatching { CoachProposals.exerciseChange(change(4, "\"NOTES\""), library) }.exceptionOrNull()
        assertTrue(same is ProposalException && "Nothing would change" in same.message!!)
        val self = change(4, "\"STYLE_OF\"").toMutableMap().apply { put("style_of_exercise_id", Json.parseToJsonElement("4")) }
        assertTrue(runCatching { CoachProposals.exerciseChange(JsonObject(self), library) }.exceptionOrNull() is ProposalException)
    }

    @Test
    fun aNewExerciseTakesEverythingGiven() {
        val a = change(0, "", ""","x":1""").toMutableMap().apply {
            put("name", Json.parseToJsonElement("\"Single-leg RDL\""))
            put("categories", Json.parseToJsonElement("""["STRENGTH","BALANCE"]"""))
        }
        val c = CoachProposals.exerciseChange(JsonObject(a), library)
        assertTrue(c.isNew)
        assertEquals(listOf(ExerciseCategory.STRENGTH, ExerciseCategory.BALANCE), c.proposed.categories)
    }

    @Test
    fun anIntervalRunKeepsItsParts() {
        val run = CoachProposals.activity(
            args(
                """{"type":"RUN","title":"","date":"2026-11-10","start_time":"18:00","duration_seconds":0,"distance_km":0,
                   "avg_heart_rate":0,"max_heart_rate":0,"elevation_gain_m":0,"rpe":7,"notes":"",
                   "segments":[{"kind":"WARMUP","duration_seconds":600,"distance_km":1.8,"avg_heart_rate":140,"note":""},
                               {"kind":"WORK","duration_seconds":270,"distance_km":1,"avg_heart_rate":168,"note":""},
                               {"kind":"RECOVERY","duration_seconds":120,"distance_km":0,"avg_heart_rate":0,"note":""}]}""".replace("\n", ""),
            ),
            zone,
        )
        assertEquals(ActivityType.RUN, run.type)
        assertEquals(990, run.durationSeconds)
        assertEquals(2800, run.distanceMeters)
        assertEquals(listOf(SegmentKind.WARMUP, SegmentKind.WORK, SegmentKind.RECOVERY), run.segments.map { it.kind })
        assertEquals(270, run.segments[1].paceSecondsPerKm)
        assertNull(run.segments[2].distanceMeters)
        assertEquals("2026-11-10T16:00:00Z", Instant.ofEpochMilli(run.startedAtMillis).toString())
    }

    @Test
    fun aPlanProposalAddsMovesAndDrops() {
        val existing = mapOf(
            5L to com.yonizilber.flownics.domain.model.PlannedWorkout(id = 5, date = java.time.LocalDate.parse("2026-11-12"), kind = com.yonizilber.flownics.domain.model.PlanKind.WORKOUT, workoutId = 7),
            6L to com.yonizilber.flownics.domain.model.PlannedWorkout(id = 6, date = java.time.LocalDate.parse("2026-11-13"), kind = com.yonizilber.flownics.domain.model.PlanKind.WORKOUT, workoutId = 7),
        )
        val change = CoachProposals.planChange(
            args(
                """{"add":[{"date":"2026-11-11","start_time":"18:00","kind":"ACTIVITY","workout_id":0,"activity_type":"RUN","title":"Intervals",
                    "duration_minutes":0,"notes":"Steady repeats",
                    "steps":[{"kind":"WARMUP","duration_seconds":900,"distance_km":0,"pace_fast":"","pace_slow":"","max_heart_rate":0,"note":""},
                             {"kind":"WORK","duration_seconds":0,"distance_km":1,"pace_fast":"4:30","pace_slow":"4:20","max_heart_rate":0,"note":""}]}],
                   "move":[{"plan_id":5,"date":"2026-11-14","start_time":""}],"remove":[6],"reason":"Recover before the long run"}""".replace("\n", ""),
            ),
            existing,
            setOf(7L),
        )
        val run = change.adds.single()
        assertEquals("18:00", run.time.toString())
        assertEquals(260, run.steps[1].paceFastSecondsPerKm)
        assertEquals(270, run.steps[1].paceSlowSecondsPerKm)
        // 15 min warm-up + 1 km at about 4:25.
        assertEquals(20, run.durationMinutes)
        assertEquals("2026-11-14", change.moves.single().second.date.toString())
        assertNull(change.moves.single().second.time)
        assertEquals(listOf("add:0", "move:5", "remove:6"), change.keys)
    }

    @Test
    fun aPlanProposalWithUnknownIdsIsRefused() {
        val unknownWorkout = runCatching {
            CoachProposals.planChange(
                args("""{"add":[{"date":"2026-11-11","start_time":"","kind":"WORKOUT","workout_id":99,"activity_type":"RUN","title":"","duration_minutes":0,"notes":"","steps":[]}],"move":[],"remove":[],"reason":""}"""),
                emptyMap(),
                setOf(7L),
            )
        }.exceptionOrNull()
        assertTrue(unknownWorkout is ProposalException)
        val unknownPlan = runCatching { CoachProposals.planChange(args("""{"add":[],"move":[],"remove":[42],"reason":""}"""), emptyMap(), emptySet()) }.exceptionOrNull()
        assertTrue(unknownPlan is ProposalException && "plan_list" in unknownPlan.message!!)
    }

    @Test
    fun anActivityWithoutATimeIsRefused() {
        val e = runCatching {
            CoachProposals.activity(
                args("""{"type":"RUN","title":"","date":"2026-11-10","start_time":"18:00","duration_seconds":0,"distance_km":8,"avg_heart_rate":0,"max_heart_rate":0,"elevation_gain_m":0,"rpe":0,"notes":"","segments":[]}"""),
                zone,
            )
        }.exceptionOrNull()
        assertTrue(e is ProposalException)
    }
}
