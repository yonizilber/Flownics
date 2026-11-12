package com.yonizilber.flownics.data.garmin

import com.yonizilber.flownics.data.coach.CoachStore
import com.yonizilber.flownics.data.di.ApplicationScope
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.domain.fit.HeartZones
import com.yonizilber.flownics.domain.model.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** The zones in use, and whether the maximum was given by the user or taken from the recordings. */
data class ZonesInfo(val zones: HeartZones, val maxFromRecordings: Boolean, val recordings: List<Activity>)

/**
 * Heart-rate zones for everything that shows them: from the maximum and resting heart rate in the
 * user's profile, or (until they give one) the highest any recording measured, said as such.
 */
@Singleton
class Zones @Inject constructor(
    @ApplicationScope scope: CoroutineScope,
    store: CoachStore,
    activities: ActivityRepository,
) {
    val info: StateFlow<ZonesInfo?> = combine(store.profile, activities.observeAll(), activities.observeAttached()) { profile, standalone, attached ->
        val recordings = standalone + attached.values
        val max = profile.maxHeartRate ?: recordings.mapNotNull { it.maxHeartRate }.maxOrNull()
        max?.let { ZonesInfo(HeartZones(it, profile.restingHeartRate), maxFromRecordings = profile.maxHeartRate == null, recordings = recordings) }
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
