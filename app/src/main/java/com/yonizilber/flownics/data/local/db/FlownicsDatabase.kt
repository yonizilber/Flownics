package com.yonizilber.flownics.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yonizilber.flownics.data.local.db.dao.ActivityDao
import com.yonizilber.flownics.data.local.db.dao.PlanDao
import com.yonizilber.flownics.data.local.db.dao.AiDao
import com.yonizilber.flownics.data.local.db.dao.CoachDao
import com.yonizilber.flownics.data.local.db.dao.ExerciseDao
import com.yonizilber.flownics.data.local.db.dao.ExerciseVariationDao
import com.yonizilber.flownics.data.local.db.dao.SessionDao
import com.yonizilber.flownics.data.local.db.dao.TransferDao
import com.yonizilber.flownics.data.local.db.dao.WorkoutTemplateDao
import com.yonizilber.flownics.data.local.db.dao.WellnessDao
import com.yonizilber.flownics.data.local.db.dao.RunStructureDao
import com.yonizilber.flownics.data.local.db.entity.RunStructureEntity
import com.yonizilber.flownics.data.local.db.entity.GarminRawEntity
import com.yonizilber.flownics.data.local.db.entity.WellnessDayEntity
import com.yonizilber.flownics.data.local.db.entity.ActivityEntity
import com.yonizilber.flownics.data.local.db.entity.CalendarLinkEntity
import com.yonizilber.flownics.data.local.db.entity.PlannedWorkoutEntity
import com.yonizilber.flownics.data.local.db.entity.AiUsageEntity
import com.yonizilber.flownics.data.local.db.entity.CoachConversationEntity
import com.yonizilber.flownics.data.local.db.entity.CoachMessageEntity
import com.yonizilber.flownics.data.local.db.entity.CoachNoteEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseAiNoteEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseVariationEntity
import com.yonizilber.flownics.data.local.db.entity.SetLogEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutSessionEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutTemplateEntity

@Database(
    entities = [
        ExerciseEntity::class,
        WorkoutTemplateEntity::class,
        WorkoutSessionEntity::class,
        SetLogEntity::class,
        AiUsageEntity::class,
        ExerciseAiNoteEntity::class,
        ExerciseVariationEntity::class,
        CoachConversationEntity::class,
        CoachMessageEntity::class,
        CoachNoteEntity::class,
        ActivityEntity::class,
        PlannedWorkoutEntity::class,
        CalendarLinkEntity::class,
        GarminRawEntity::class,
        WellnessDayEntity::class,
        RunStructureEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class FlownicsDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao

    abstract fun workoutTemplateDao(): WorkoutTemplateDao

    abstract fun sessionDao(): SessionDao

    abstract fun aiDao(): AiDao

    abstract fun exerciseVariationDao(): ExerciseVariationDao

    abstract fun transferDao(): TransferDao

    abstract fun coachDao(): CoachDao

    abstract fun activityDao(): ActivityDao

    abstract fun planDao(): PlanDao

    abstract fun wellnessDao(): WellnessDao

    abstract fun runStructureDao(): RunStructureDao
}

// v1 → v2: workout templates. The SQL must match app/schemas/…/2.json exactly; the migration test
// in androidTest verifies that on a real device.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_templates` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`blocksJson` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, " +
                "`updatedAtMillis` INTEGER NOT NULL)",
        )
    }
}

// v2 → v3: workout sessions and their set logs. Same rule: SQL copied from schemas/…/3.json.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`templateId` INTEGER, " +
                "`workoutName` TEXT NOT NULL, " +
                "`planJson` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`startedAtMillis` INTEGER NOT NULL, " +
                "`endedAtMillis` INTEGER, " +
                "`currentIndex` INTEGER NOT NULL, " +
                "`notes` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_status` ON `workout_sessions` (`status`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `set_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`stepIndex` INTEGER NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, " +
                "`exerciseName` TEXT NOT NULL, " +
                "`blockIndex` INTEGER NOT NULL, " +
                "`setNumber` INTEGER NOT NULL, " +
                "`round` INTEGER NOT NULL, " +
                "`side` TEXT, " +
                "`reps` INTEGER, " +
                "`seconds` INTEGER, " +
                "`weightKg` REAL, " +
                "`distanceMeters` INTEGER, " +
                "`skipped` INTEGER NOT NULL, " +
                "`completedAtMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `workout_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_set_logs_sessionId_stepIndex` ON `set_logs` (`sessionId`, `stepIndex`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_set_logs_exerciseId` ON `set_logs` (`exerciseId`)")
    }
}

// v3 → v4: the AI spend log and where AI-suggested exercise details came from. SQL from schemas/…/4.json.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_usage` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`atMillis` INTEGER NOT NULL, " +
                "`feature` TEXT NOT NULL, " +
                "`modelId` TEXT NOT NULL, " +
                "`inputTokens` INTEGER NOT NULL, " +
                "`outputTokens` INTEGER NOT NULL, " +
                "`webSearches` INTEGER NOT NULL, " +
                "`costUsd` REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_usage_atMillis` ON `ai_usage` (`atMillis`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_ai_notes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, " +
                "`atMillis` INTEGER NOT NULL, " +
                "`modelId` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`appliedJson` TEXT NOT NULL, " +
                "`sourcesJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_ai_notes_exerciseId` ON `exercise_ai_notes` (`exerciseId`)")
    }
}

// v4 → v5: exercise instructions, level and variation family; saved variation suggestions; and the
// AI log records what each call was about, how long it took and how it ended. SQL from schemas/…/5.json.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exercises` ADD COLUMN `instructionsJson` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `exercises` ADD COLUMN `level` INTEGER")
        db.execSQL("ALTER TABLE `exercises` ADD COLUMN `variationOf` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_variations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`difference` TEXT NOT NULL, " +
                "`equipment` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`why` TEXT NOT NULL, " +
                "`level` INTEGER, " +
                "`modelId` TEXT NOT NULL, " +
                "`atMillis` INTEGER NOT NULL, " +
                "`addedExerciseId` INTEGER, " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_variations_exerciseId` ON `exercise_variations` (`exerciseId`)")
        db.execSQL("ALTER TABLE `ai_usage` ADD COLUMN `subject` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `ai_usage` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
        // Calls logged before v5 all returned an answer: failures weren't logged then.
        db.execSQL("ALTER TABLE `ai_usage` ADD COLUMN `outcome` TEXT NOT NULL DEFAULT 'OK'")
    }
}

// v5 → v6: the reason a logged AI call failed.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ai_usage` ADD COLUMN `detail` TEXT NOT NULL DEFAULT ''")
    }
}

// v6 → v7: the coach's conversations and what it remembers.
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `coach_conversations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, " +
                "`updatedAtMillis` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coach_conversations_updatedAtMillis` ON `coach_conversations` (`updatedAtMillis`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `coach_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`conversationId` INTEGER NOT NULL, " +
                "`atMillis` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`dataJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`conversationId`) REFERENCES `coach_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coach_messages_conversationId` ON `coach_messages` (`conversationId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `coach_notes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL)",
        )
    }
}

// v7 → v8: an exercise can be of several kinds; activities logged outside the player.
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exercises` ADD COLUMN `extraCategories` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activities` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`startedAtMillis` INTEGER NOT NULL, " +
                "`durationSeconds` INTEGER NOT NULL, " +
                "`distanceMeters` INTEGER, " +
                "`avgHeartRate` INTEGER, " +
                "`maxHeartRate` INTEGER, " +
                "`elevationGainMeters` INTEGER, " +
                "`rpe` INTEGER, " +
                "`notes` TEXT NOT NULL, " +
                "`segmentsJson` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, " +
                "`updatedAtMillis` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_startedAtMillis` ON `activities` (`startedAtMillis`)")
    }
}

// v8 → v9: the training plan, and which calendar events show it.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planned_workouts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`date` TEXT NOT NULL, " +
                "`time` TEXT, " +
                "`kind` TEXT NOT NULL, " +
                "`workoutId` INTEGER, " +
                "`activityType` TEXT, " +
                "`title` TEXT NOT NULL, " +
                "`durationMinutes` INTEGER, " +
                "`notes` TEXT NOT NULL, " +
                "`stepsJson` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, " +
                "`updatedAtMillis` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_workouts_date` ON `planned_workouts` (`date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_links` (" +
                "`itemKey` TEXT NOT NULL, " +
                "`calendarId` INTEGER NOT NULL, " +
                "`eventId` INTEGER NOT NULL, " +
                "`contentHash` INTEGER NOT NULL, " +
                "PRIMARY KEY(`itemKey`))",
        )
    }
}

// v9 → v10: activities read from watch recordings (FIT files, Garmin).
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `activities` ADD COLUMN `externalId` TEXT")
        db.execSQL("ALTER TABLE `activities` ADD COLUMN `metricsJson` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_externalId` ON `activities` (`externalId`)")
    }
}

// v10 → v11: when each set began; a watch recording made during a workout belongs to it.
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `set_logs` ADD COLUMN `startedAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `activities` ADD COLUMN `attachedSessionId` INTEGER")
        db.execSQL("ALTER TABLE `activities` ADD COLUMN `keepSeparate` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_attachedSessionId` ON `activities` (`attachedSessionId`)")
    }
}

// v11 → v12: a workout can fill its rests from another workout.
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout_templates` ADD COLUMN `restFillerWorkoutId` INTEGER")
        db.execSQL("ALTER TABLE `workout_templates` ADD COLUMN `restFillerLeftovers` INTEGER NOT NULL DEFAULT 1")
    }
}

// v12 → v13: a rest-filler workout's exercises can follow its sets.
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout_templates` ADD COLUMN `restFillerRepeatSets` INTEGER NOT NULL DEFAULT 0")
    }
}

// v13 → v14: active rests can wait for the user at zero.
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout_templates` ADD COLUMN `confirmActiveRest` INTEGER NOT NULL DEFAULT 1")
    }
}

// v14 → v15: a superset of the filler workout can go into a rest as a whole round.
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout_templates` ADD COLUMN `restFillerRoundsTogether` INTEGER NOT NULL DEFAULT 1")
    }
}

// v15 → v16: Garmin's all-day data (sleep, HRV, Body Battery…): its answers as they came, and the days built from them.
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `garmin_raw` (`endpoint` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL, `fetchedAtMillis` INTEGER NOT NULL, `gzip` BLOB NOT NULL, PRIMARY KEY(`endpoint`, `startDate`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `wellness_days` (`date` TEXT NOT NULL, `dataJson` TEXT NOT NULL, PRIMARY KEY(`date`))")
    }
}

// v16 → v17: each recorded run's structure (segments, repeats), made from its FIT file.
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `run_structures` (`activityId` INTEGER NOT NULL, `externalId` TEXT NOT NULL, `version` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`activityId`))")
    }
}

// Every schema change gets a real Migration here. See DatabaseModule.
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
)
