package com.yonizilber.flownics

import com.yonizilber.flownics.calendar.PlanReminders
import com.yonizilber.flownics.data.garmin.GarminSync
import com.yonizilber.flownics.data.garmin.GarminSyncWorker
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.data.garmin.RecordingLinker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Keeps the chosen calendar in step with the plan; does nothing until the user turns it on.
        calendarSync.start()
        reminders.start()
        // Recordings imported before workouts could be joined, or whose workout is gone, are sorted out.
        scope.launch { linker.link() }
        GarminSyncWorker.schedule(this, garmin.settings.value.hasKey)
    }
}
