package com.yonizilber.flownics.data.transfer

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonizilber.flownics.data.coach.CoachStore
import com.yonizilber.flownics.data.local.db.FlownicsDatabase
import com.yonizilber.flownics.data.local.db.entity.ActiveRestJson
import com.yonizilber.flownics.data.local.db.entity.BlockJson
import com.yonizilber.flownics.data.local.db.entity.ExerciseEntity
import com.yonizilber.flownics.data.local.db.entity.ItemJson
import com.yonizilber.flownics.data.local.db.entity.SessionPlanJson
import com.yonizilber.flownics.data.local.db.entity.SetLogEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutSessionEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutTemplateEntity
import com.yonizilber.flownics.data.local.media.MediaStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataTransferTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var db: FlownicsDatabase
    private lateinit var media: MediaStorage
    private lateinit var transfer: DataTransfer
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FlownicsDatabase::class.java).build()
        media = MediaStorage(context)
        transfer = DataTransfer(context, db, db.transferDao(), media, json, CoachStore(context, db.coachDao(), json, com.yonizilber.flownics.data.local.media.AiPhotos(context)))
    }

    @After
    fun tearDown() {
        db.close()
        files.forEach { it.delete() }
    }

    private fun exercise(name: String, mediaJson: String = "[]") = ExerciseEntity(
        name = name, category = "STRENGTH", primaryMuscles = ",QUADS,", secondaryMuscles = "", equipment = "",
        cuesJson = "[]", instructionsJson = "[]", stagesJson = "[]", mediaJson = mediaJson, measureMode = "REPS",
        unilateral = false, level = 3, variationOf = null, notes = "", createdAtMillis = 1, updatedAtMillis = 1,
    )

    private fun blocks(vararg ids: Long) = json.encodeToString(
        ListSerializer(BlockJson.serializer()),
        listOf(BlockJson(structure = "STRAIGHT_SETS", items = ids.map { ItemJson(exerciseId = it, activeRest = ActiveRestJson.None) })),
    )

    private fun tempFile(name: String) = File(context.cacheDir, name).also { files += it }

    /** A library with a photo, a workout and one finished session. */
    private fun seed(): Long = runBlocking {
        val photo = media.file("seed-photo.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(20)) }
        files += photo
        val dao = db.transferDao()
        val squat = dao.insertExercise(exercise("Squat", """[{"kind":"IMAGE","location":"seed-photo.jpg"}]"""))
        dao.insertWorkout(WorkoutTemplateEntity(name = "Legs", description = "", blocksJson = blocks(squat), createdAtMillis = 1, updatedAtMillis = 1))
        val plan = json.encodeToString(SessionPlanJson.serializer(), SessionPlanJson(listOf("Main"), emptyList()))
        db.sessionDao().insert(WorkoutSessionEntity(templateId = null, workoutName = "Legs", planJson = plan, status = "COMPLETED", startedAtMillis = 5, endedAtMillis = 6, currentIndex = 0, notes = "felt good"))
        db.sessionDao().upsertLog(SetLogEntity(sessionId = 1, stepIndex = 0, exerciseId = squat, exerciseName = "Squat", blockIndex = 0, setNumber = 1, round = 1, side = null, reps = 5, seconds = null, weightKg = 60.0, distanceMeters = null, skipped = false, completedAtMillis = 6))
        squat
    }

    @Test
    fun aBackupRestoresEverythingAndReplacesWhatWasThere() = runBlocking {
        seed()
        val file = tempFile("backup.zip")
        val written = file.outputStream().use { transfer.exportBackup(it) }
        assertEquals(1, written.mediaFiles)

        // Change things after the backup: these must be gone after restoring.
        db.transferDao().insertExercise(exercise("Added later"))
        val preview = transfer.read(Uri.fromFile(file))
        assertEquals(BundleKind.BACKUP, preview.kind)
        assertEquals(2, preview.current.exercises)
        transfer.restore(preview)

        val dao = db.transferDao()
        assertEquals(listOf("Squat"), dao.exercises().map { it.name })
        assertEquals(1, dao.completedSessions().size)
        assertEquals("felt good", dao.completedSessions().single().notes)
        assertEquals(60.0, dao.completedLogs().single().weightKg!!, 0.0)
        // The photo came back under a new name, and the file is really there.
        val restored = dao.exercises().single().mediaJson
        assertTrue(!restored.contains("seed-photo.jpg"))
        val name = Regex("\"location\":\"([^\"]+)\"").find(restored)!!.groupValues[1]
        assertTrue(media.file(name).isFile)
        files += media.file(name)
    }

    @Test
    fun aSharedWorkoutComesInWithNewIdsAndReusesSameNamedExercises() = runBlocking {
        val squat = seed()
        val file = tempFile("share.zip")
        val workoutId = db.transferDao().workouts().single().id
        file.outputStream().use { transfer.exportShare(ShareSelection(exerciseIds = emptySet(), workoutIds = setOf(workoutId)), it) }

        // Into the same library, reusing: the workout is added, pointing at the existing squat.
        val reused = transfer.importShare(transfer.read(Uri.fromFile(file)), reuseExisting = true)
        assertEquals(0, reused.exercisesAdded)
        assertEquals(1, reused.exercisesReused)
        val workouts = db.transferDao().workouts()
        assertEquals(listOf("Legs", "Legs (2)"), workouts.map { it.name })
        assertTrue(workouts[1].blocksJson.contains("\"exerciseId\":$squat"))

        // As copies: a new "Squat (2)" that the newest workout uses, and no history comes along.
        val copied = transfer.importShare(transfer.read(Uri.fromFile(file)), reuseExisting = false)
        assertEquals(1, copied.exercisesAdded)
        val copy = db.transferDao().exercises().single { it.name == "Squat (2)" }
        assertTrue(db.transferDao().workouts().last().blocksJson.contains("\"exerciseId\":${copy.id}"))
        assertEquals(1, db.transferDao().completedSessions().size)
        Regex("\"location\":\"([^\"]+)\"").findAll(copy.mediaJson).forEach { files += media.file(it.groupValues[1]) }
    }
}
