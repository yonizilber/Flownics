package com.yonizilber.flownics

import com.yonizilber.flownics.calendar.PlanReminders
import com.yonizilber.flownics.data.garmin.GarminSync
import com.yonizilber.flownics.data.garmin.GarminSyncWorker
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.data.garmin.RecordingLinker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.yonizilber.flownics.calendar.CalendarSync
import javax.inject.Inject
import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FlownicsApplication : Application() {
    @Inject
    lateinit var calendarSync: CalendarSync

    @Inject
    lateinit var linker: RecordingLinker

    @Inject
    lateinit var garmin: GarminSync

    @Inject
    lateinit var reminders: PlanReminders

    @Inject
    lateinit var wellness: com.yonizilber.flownics.data.garmin.WellnessSync

    @Inject
    lateinit var runStructures: com.yonizilber.flownics.data.run.RunStructures

    @Inject
    lateinit var activities: com.yonizilber.flownics.data.repository.ActivityRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Keeps the chosen calendar in step with the plan; does nothing until the user turns it on.
        calendarSync.start()
        reminders.start()
        // Recordings imported before workouts could be joined, or whose workout is gone, are sorted out.
        scope.launch { linker.link() }
        // Days kept from Garmin are read again when this version reads them differently.
        scope.launch { wellness.rebuildIfReaderChanged() }
        // Runs imported before structures existed (or made by an older version) get theirs.
        scope.launch {
            runStructures.backfill(activities.observeAll().first() + activities.observeAttached().first().values)
        }
        GarminSyncWorker.schedule(this, garmin.settings.value.hasKey)
    }
}
