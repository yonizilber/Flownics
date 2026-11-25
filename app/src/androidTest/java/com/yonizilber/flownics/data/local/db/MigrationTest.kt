package com.yonizilber.flownics.data.local.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FlownicsDatabase::class.java)

    @Test
    fun migrate1To2KeepsExercises() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO exercises (name, category, primaryMuscles, secondaryMuscles, equipment, cuesJson, " +
                    "stagesJson, mediaJson, measureMode, unilateral, notes, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('Squat', 'STRENGTH', ',QUADS,', '', '', '[]', '[]', '[]', 'REPS', 0, '', 1, 1)",
            )
        }
        // Validates the migrated schema against schemas/2.json and fails on any mismatch.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT name FROM exercises").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Squat", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate2To3KeepsWorkoutsAndAddsSessions() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO workout_templates (name, description, blocksJson, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('Legs', '', '[]', 1, 1)",
            )
        }
        // Validates against schemas/3.json, including the set_logs foreign key and indices.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT name FROM workout_templates").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Legs", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM workout_sessions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate3To4KeepsSessionsAndAddsAiTables() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO workout_sessions (templateId, workoutName, planJson, status, startedAtMillis, endedAtMillis, " +
                    "currentIndex, notes) VALUES (NULL, 'Legs', '{}', 'COMPLETED', 1, 2, 0, '')",
            )
        }
        // Validates against schemas/4.json, including the exercise_ai_notes foreign key.
        helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4).use { db ->
            db.query("SELECT workoutName FROM workout_sessions").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Legs", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM ai_usage").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate4To5GivesOldExercisesEmptyInstructions() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO exercises (name, category, primaryMuscles, secondaryMuscles, equipment, cuesJson, " +
                    "stagesJson, mediaJson, measureMode, unilateral, notes, createdAtMillis, updatedAtMillis) " +
                    "VALUES ('Squat', 'STRENGTH', '', '', '', '[]', '[]', '[]', 'REPS', 0, '', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO ai_usage (atMillis, feature, modelId, inputTokens, outputTokens, webSearches, costUsd) " +
                    "VALUES (1, 'CHAT', 'gemini-3.6-flash', 10, 20, 0, 0.001)",
            )
        }
        // Validates against schemas/5.json, including the new columns' defaults.
        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { db ->
            db.query("SELECT instructionsJson, level, variationOf FROM exercises").use { cursor ->
                cursor.moveToFirst()
                assertEquals("[]", cursor.getString(0))
                // Not rated and not a variation until the user says so.
                assertEquals(true, cursor.isNull(1))
                assertEquals(true, cursor.isNull(2))
            }
            db.query("SELECT COUNT(*) FROM exercise_variations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            db.query("SELECT outcome, subject FROM ai_usage").use { cursor ->
                cursor.moveToFirst()
                assertEquals("OK", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate18To19KeepsSetsWithNothingContinued() {
        helper.createDatabase(TEST_DB, 18).use { db ->
            db.execSQL(
                "INSERT INTO workout_sessions (id, templateId, workoutName, planJson, status, startedAtMillis, endedAtMillis, currentIndex, notes) " +
                    "VALUES (1, NULL, 'Legs', '{\"blockTitles\":[],\"steps\":[]}', 'COMPLETED', 1, 2, 0, '')",
            )
            db.execSQL(
                "INSERT INTO set_logs (sessionId, stepIndex, exerciseId, exerciseName, blockIndex, setNumber, round, side, reps, seconds, weightKg, distanceMeters, skipped, completedAtMillis, startedAtMillis) " +
                    "VALUES (1, 0, 5, 'Squat', 0, 1, 1, NULL, 8, NULL, 60.0, NULL, 0, 2, NULL)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 19, true, MIGRATION_18_19).use { db ->
            db.query("SELECT reps, weightKg, moreJson FROM set_logs").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8, cursor.getInt(0))
                assertEquals(60.0, cursor.getDouble(1), 0.0)
                assertEquals("", cursor.getString(2))
            }
        }
    }

    @Test
    fun migrate19To20AddsWeighInsThatKeepAScaleReadingOnce() {
        helper.createDatabase(TEST_DB, 19).close()
        helper.runMigrationsAndValidate(TEST_DB, 20, true, MIGRATION_19_20).use { db ->
            db.execSQL("INSERT INTO body_weights (atMillis, kg, source, externalKey) VALUES (1, 72.35, 'SCALE', 'mi:a:1')")
            db.execSQL("INSERT OR IGNORE INTO body_weights (atMillis, kg, source, externalKey) VALUES (1, 72.35, 'SCALE', 'mi:a:1')")
            db.execSQL("INSERT INTO body_weights (atMillis, kg, source, externalKey) VALUES (2, 72.0, 'MANUAL', NULL)")
            db.execSQL("INSERT INTO body_weights (atMillis, kg, source, externalKey) VALUES (3, 71.9, 'MANUAL', NULL)")
            db.query("SELECT COUNT(*) FROM body_weights").use { cursor ->
                cursor.moveToFirst()
                assertEquals(3, cursor.getInt(0))
            }
        }
    }

    @Test
    fun allMigrationsOpenTheLatestSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FlownicsDatabase::class.java, TEST_DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
            .apply { openHelper.writableDatabase.close() }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
