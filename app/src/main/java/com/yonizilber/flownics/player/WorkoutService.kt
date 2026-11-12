package com.yonizilber.flownics.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yonizilber.flownics.MainActivity
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.player.Phase
import com.yonizilber.flownics.domain.player.WorkoutPlayer
import com.yonizilber.flownics.domain.workout.WorkStep
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs only while a workout is active: keeps the process alive with the screen off, shows the
 * timer and controls in a notification (also on the lock screen), and holds a partial wake lock
 * during countdowns only, so rest timers fire on time without draining the battery between sets.
 *
 * Declared as foregroundServiceType="specialUse" (interval timer) rather than "health": "health"
 * requires a body-sensor or activity-recognition permission that this app has no other use for.
 */
@AndroidEntryPoint
class WorkoutService : Service() {

    @Inject lateinit var controller: PlayerController

    private val scope = MainScope()
    private var observing: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller.serviceRunning = true
        createChannel(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flownics:workout-timer")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must follow startForegroundService promptly, even when about to stop.
        val notification = buildNotification(this, controller.state.value, controller.library.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (controller.state.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_NEXT -> controller.done()
        }
        if (observing == null) {
            observing = scope.launch {
                controller.state.collect { workout ->
                    if (workout == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        updateNotification(buildNotification(this@WorkoutService, workout, controller.library.value))
                        holdWakeLock(workout)
                    }
                }
            }
        }
        // Not sticky: if Android kills the process, the workout is restored (paused) the next time
        // the app opens, rather than a restarted service guessing at a timer it lost.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.serviceRunning = false
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun updateNotification(notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun holdWakeLock(workout: ActiveWorkout) {
        val lock = wakeLock ?: return
        val phase = workout.player.phase
        val remaining = WorkoutPlayer.remainingMs(phase, SystemClock.elapsedRealtime())
        if (WorkoutPlayer.nextDeadline(phase) != null && remaining != null) {
            // Re-acquiring a non-counted lock just moves its timeout; the margin covers the
            // transition into the next step, which acquires again.
            lock.acquire(remaining + WAKE_LOCK_MARGIN_MS)
        } else if (lock.isHeld) {
            lock.release()
        }
    }

    companion object {
        private const val CHANNEL_ID = "workout_player"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_MARGIN_MS = 30_000L
        private const val ACTION_PAUSE = "com.yonizilber.flownics.player.PAUSE"
        private const val ACTION_RESUME = "com.yonizilber.flownics.player.RESUME"
        private const val ACTION_NEXT = "com.yonizilber.flownics.player.NEXT"

        private fun createChannel(context: Context) {
            // Low importance: the notification itself stays silent; the cues make the sounds.
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, workout: ActiveWorkout?, library: Map<Long, Exercise>): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_PLAYER)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            if (workout == null) return builder.setContentTitle(context.getString(R.string.app_name)).build()

            val texts = PlayerTexts(context.resources)
            builder
                .setContentTitle(texts.title(workout, library))
                .setContentText(texts.detail(workout, library))
                .setSubText(workout.session.workoutName)

            // The system chronometer animates the clock, so the notification only changes when the
            // step does, not every second.
            val phase = workout.player.phase
            val now = SystemClock.elapsedRealtime()
            val wallNow = System.currentTimeMillis()
            if (phase is Phase.Paused || phase is Phase.Finished) {
                builder.setShowWhen(false)
            } else {
                val remaining = WorkoutPlayer.remainingMs(phase, now)
                if (remaining != null) {
                    builder.setWhen(wallNow + remaining).setChronometerCountDown(true)
                } else {
                    builder.setWhen(wallNow - WorkoutPlayer.elapsedMs(phase, now))
                }
                builder.setShowWhen(true).setUsesChronometer(true)
            }

            if (phase !is Phase.Finished) {
                if (phase is Phase.Paused) {
                    builder.addAction(0, context.getString(R.string.player_resume), serviceIntent(context, ACTION_RESUME))
                } else {
                    builder.addAction(0, context.getString(R.string.player_pause), serviceIntent(context, ACTION_PAUSE))
                }
                val nextLabel = if (workout.step is WorkStep) R.string.notif_done else R.string.player_skip_rest
                builder.addAction(0, context.getString(nextLabel), serviceIntent(context, ACTION_NEXT))
            }
            return builder.build()
        }

        private fun serviceIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, WorkoutService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
