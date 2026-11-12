package com.yonizilber.flownics.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yonizilber.flownics.data.local.db.entity.ActivityEntity
import com.yonizilber.flownics.data.local.db.entity.AiUsageEntity
import com.yonizilber.flownics.data.local.db.entity.PlannedWorkoutEntity
import com.yonizilber.flownics.data.local.db.entity.CoachConversationEntity
import com.yonizilber.flownics.data.local.db.entity.CoachMessageEntity
import com.yonizilber.flownics.data.local.db.entity.CoachNoteEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseAiNoteEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseEntity
import com.yonizilber.flownics.data.local.db.entity.ExerciseVariationEntity
import com.yonizilber.flownics.data.local.db.entity.SetLogEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutSessionEntity
import com.yonizilber.flownics.data.local.db.entity.WorkoutTemplateEntity

/** Whole-table reads and writes for export, import and restore. Callers wrap writes in a transaction. */
@Dao
interface TransferDao {
    @Query("SELECT * FROM exercises ORDER BY id")
    suspend fun exercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercise_variations ORDER BY id")
    suspend fun variations(): List<ExerciseVariationEntity>

    @Query("SELECT * FROM exercise_ai_notes ORDER BY id")
    suspend fun aiNotes(): List<ExerciseAiNoteEntity>

    @Query("SELECT * FROM workout_templates ORDER BY id")
    suspend fun workouts(): List<WorkoutTemplateEntity>

    @Query("SELECT * FROM workout_sessions WHERE status = 'COMPLETED' ORDER BY id")
    suspend fun completedSessions(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM set_logs WHERE sessionId IN (SELECT id FROM workout_sessions WHERE status = 'COMPLETED') ORDER BY sessionId, stepIndex")
    suspend fun completedLogs(): List<SetLogEntity>

    @Query("SELECT * FROM ai_usage ORDER BY id")
    suspend fun aiUsage(): List<AiUsageEntity>

    @Query("SELECT * FROM activities ORDER BY id")
    suspend fun activities(): List<ActivityEntity>

    @Query("SELECT * FROM coach_conversations ORDER BY id")
    suspend fun coachConversations(): List<CoachConversationEntity>

    @Query("SELECT * FROM coach_messages ORDER BY id")
    suspend fun coachMessages(): List<CoachMessageEntity>

    @Query("SELECT * FROM coach_notes ORDER BY id")
    suspend fun coachNotes(): List<CoachNoteEntity>

    @Insert
    suspend fun insertActivities(entities: List<ActivityEntity>)

    @Insert
    suspend fun insertCoachConversations(entities: List<CoachConversationEntity>)

    @Insert
    suspend fun insertCoachMessages(entities: List<CoachMessageEntity>)

    @Insert
    suspend fun insertCoachNotes(entities: List<CoachNoteEntity>)

    @Query("SELECT * FROM planned_workouts ORDER BY id")
    suspend fun plans(): List<PlannedWorkoutEntity>

    @Insert
    suspend fun insertPlans(entities: List<PlannedWorkoutEntity>)

    @Query("DELETE FROM planned_workouts")
    suspend fun deletePlans()

    @Query("DELETE FROM activities")
    suspend fun deleteActivities()

    // Messages go with their conversations (cascade).
    @Query("DELETE FROM coach_conversations")
    suspend fun deleteCoachConversations()

    @Query("DELETE FROM coach_notes")
    suspend fun deleteCoachNotes()

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE status = 'IN_PROGRESS'")
    suspend fun inProgressCount(): Int

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE status = 'COMPLETED'")
    suspend fun completedCount(): Int

    @Insert
    suspend fun insertExercise(entity: ExerciseEntity): Long

    @Query("UPDATE exercises SET variationOf = :variationOf WHERE id = :id")
    suspend fun setVariationOf(id: Long, variationOf: Long?)

    @Insert
    suspend fun insertExercises(entities: List<ExerciseEntity>)

    @Insert
    suspend fun insertVariations(entities: List<ExerciseVariationEntity>)

    @Insert
    suspend fun insertNotes(entities: List<ExerciseAiNoteEntity>)

    @Insert
    suspend fun insertWorkout(entity: WorkoutTemplateEntity): Long

    @Insert
    suspend fun insertWorkouts(entities: List<WorkoutTemplateEntity>)

    @Query("UPDATE workout_templates SET restFillerWorkoutId = :filler WHERE id = :id")
    suspend fun setRestFiller(id: Long, filler: Long)

    @Insert
    suspend fun insertSessions(entities: List<WorkoutSessionEntity>)

    @Insert
    suspend fun insertLogs(entities: List<SetLogEntity>)

    @Insert
    suspend fun insertUsage(entities: List<AiUsageEntity>)

    // Variations and AI notes go with their exercises, set logs with their sessions (cascade).
    @Query("DELETE FROM exercises")
    suspend fun deleteExercises()

    @Query("DELETE FROM workout_templates")
    suspend fun deleteWorkouts()

    @Query("DELETE FROM workout_sessions")
    suspend fun deleteSessions()

    @Query("DELETE FROM ai_usage")
    suspend fun deleteUsage()
}
