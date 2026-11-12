package com.yonizilber.flownics.calendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yonizilber.flownics.MainActivity
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.data.repository.PlanRepository
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlanStatus
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Minutes before a planned workout to be reminded; null for no reminders. */
data class ReminderSettings(val minutesBefore: Int? = null)

/**
 * A notification before each planned workout (at its time minus the chosen minutes; a plan with
 * no time, at [UNTIMED_AT] that day). Kept in step with the plan: moving or deleting a plan moves
 * or cancels its reminder. Nothing is sent anywhere; it is a local notification.
 */
@Singleton
class PlanReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val plans: PlanRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(ReminderSettings(prefs.getInt(KEY_MINUTES, -1).takeIf { it >= 0 }))
    val settings: StateFlow<ReminderSettings> = _settings.asStateFlow()

    fun set(minutesBefore: Int?) {
        prefs.edit().putInt(KEY_MINUTES, minutesBefore ?: -1).apply()
        _settings.value = ReminderSettings(minutesBefore)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(plans.observeAll(), _settings) { p, s -> p to s }
                .debounce(1_000)
                .collect { (all, s) -> schedule(all, s) }
        }
    }

    private fun schedule(all: List<PlannedWorkout>, s: ReminderSettings) {
        val work = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        val wanted = if (s.minutesBefore == null) emptyMap() else all.filter { it.status == PlanStatus.PLANNED }.mapNotNull { p ->
            val at = p.date.atTime(p.time ?: UNTIMED_AT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
                if (p.time != null) TimeUnit.MINUTES.toMillis(s.minutesBefore.toLong()) else 0
            if (at <= now) null else p.id to at
        }.toMap()
        val before = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()
        (before - wanted.keys.map { it.toString() }.toSet()).forEach { work.cancelUniqueWork(name(it.toLong())) }
        wanted.forEach { (id, at) ->
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_PLAN to id))
                .build()
            work.enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
        }
        prefs.edit().putStringSet(KEY_SCHEDULED, wanted.keys.map { it.toString() }.toSet()).apply()
    }

    companion object {
        private const val PREFS = "reminder_prefs"
        private const val KEY_MINUTES = "minutes_before"
        private const val KEY_SCHEDULED = "scheduled"
        const val KEY_PLAN = "plan_id"
        const val CHANNEL_ID = "plan_reminders"
        val UNTIMED_AT: LocalTime = LocalTime.of(8, 0)
        private fun name(planId: Long) = "plan-reminder-$planId"
    }
}

/** Shows one plan's reminder, unless it was dropped, skipped or moved since. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun plans(): PlanRepository
        fun workouts(): WorkoutRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val plan = deps.plans().get(inputData.getLong(PlanReminders.KEY_PLAN, 0)) ?: return Result.success()
        if (plan.status != PlanStatus.PLANNED) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        val title = plan.title.ifBlank {
            when (plan.kind) {
                PlanKind.WORKOUT -> plan.workoutId?.let { deps.workouts().observeAll().first().firstOrNull { w -> w.id == it }?.name }.orEmpty()
                PlanKind.ACTIVITY -> applicationContext.getString(CalendarSync.typeLabel(plan.activityType))
            }
        }.ifBlank { applicationContext.getString(R.string.plan_untitled) }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PlanReminders.CHANNEL_ID, applicationContext.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            applicationContext,
            plan.id.toInt(),
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOfNotNull(
            plan.time?.let { applicationContext.getString(R.string.reminder_at, it.toString()) },
            plan.notes.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        manager.notify(
            NOTIFICATION_BASE + plan.id.toInt(),
            NotificationCompat.Builder(applicationContext, PlanReminders.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(applicationContext.getString(R.string.reminder_title, title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    private companion object {
        const val NOTIFICATION_BASE = 50_000
    }
}
