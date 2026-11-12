package com.yonizilber.flownics.data.garmin

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Syncs Garmin in the background every few hours, when there's a network, so a run shows up in
 * the journal (and joins its workout or plan) without opening the app. Android decides the exact
 * time; it may wait longer to save battery. A failure is kept and shown in the Garmin settings.
 */
class GarminSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun garminSync(): GarminSync
    }

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).garminSync()
        if (!sync.settings.value.hasKey) return Result.success()
        return try {
            sync.sync()
            Result.success()
        } catch (e: GarminException.Network) {
            Result.retry()
        } catch (e: GarminException) {
            // A refused key or an odd answer won't fix itself by retrying; it is shown in the settings.
            Result.success()
        }
    }

    companion object {
        private const val NAME = "garmin-sync"
        private const val EVERY_HOURS = 3L

        /** Keeps the periodic sync scheduled while there's a key, and off when there isn't. */
        fun schedule(context: Context, hasKey: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!hasKey) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<GarminSyncWorker>(EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
