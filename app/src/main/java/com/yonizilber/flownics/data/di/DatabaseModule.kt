package com.yonizilber.flownics.data.di

import android.content.Context
import androidx.room.Room
import com.yonizilber.flownics.data.local.db.ALL_MIGRATIONS
import com.yonizilber.flownics.data.local.db.FlownicsDatabase
import com.yonizilber.flownics.data.local.db.dao.ActivityDao
import com.yonizilber.flownics.data.local.db.dao.PlanDao
import com.yonizilber.flownics.data.local.db.dao.AiDao
import com.yonizilber.flownics.data.local.db.dao.CoachDao
import com.yonizilber.flownics.data.local.db.dao.ExerciseDao
import com.yonizilber.flownics.data.local.db.dao.ExerciseVariationDao
import com.yonizilber.flownics.data.local.db.dao.SessionDao
import com.yonizilber.flownics.data.local.db.dao.TransferDao
import com.yonizilber.flownics.data.local.db.dao.WorkoutTemplateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // No fallbackToDestructiveMigration(): the DB holds the user's own exercises and workout
    // history. Bumping the version without a Migration crashes on open (a loud failure) instead of
    // silently wiping their data — fix the migration, don't work around it.
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FlownicsDatabase =
        Room.databaseBuilder(context, FlownicsDatabase::class.java, "flownics.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideExerciseDao(database: FlownicsDatabase): ExerciseDao = database.exerciseDao()

    @Provides
    fun provideWorkoutTemplateDao(database: FlownicsDatabase): WorkoutTemplateDao = database.workoutTemplateDao()

    @Provides
    fun provideSessionDao(database: FlownicsDatabase): SessionDao = database.sessionDao()

    @Provides
    fun provideAiDao(database: FlownicsDatabase): AiDao = database.aiDao()

    @Provides
    fun provideExerciseVariationDao(database: FlownicsDatabase): ExerciseVariationDao = database.exerciseVariationDao()

    @Provides
    fun provideTransferDao(database: FlownicsDatabase): TransferDao = database.transferDao()

    @Provides
    fun provideCoachDao(database: FlownicsDatabase): CoachDao = database.coachDao()

    @Provides
    fun provideActivityDao(database: FlownicsDatabase): ActivityDao = database.activityDao()

    @Provides
    fun providePlanDao(database: FlownicsDatabase): PlanDao = database.planDao()
}
