package com.yonizilber.flownics.data.di

import com.yonizilber.flownics.data.repository.ExerciseRepositoryImpl
import com.yonizilber.flownics.data.repository.SessionRepositoryImpl
import com.yonizilber.flownics.data.repository.VariationRepositoryImpl
import com.yonizilber.flownics.data.repository.WorkoutRepositoryImpl
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.VariationRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindVariationRepository(impl: VariationRepositoryImpl): VariationRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}
