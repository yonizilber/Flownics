package com.yonizilber.flownics.feature.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.garmin.WellnessSync
import com.yonizilber.flownics.data.garmin.Zones
import com.yonizilber.flownics.domain.fit.TrainingLoad
import com.yonizilber.flownics.domain.me.BodyTrends
import com.yonizilber.flownics.domain.me.Trend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Everything known about the user over time: Garmin's days, and the load from their recordings. */
data class MeState(
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
    val trends: List<Trend>,
    /** Days in the range with any Garmin data at all. */
    val garminDays: Int,
    /** The heart-rate zones the load is counted in; null without a max heart rate. */
    val zonesKnown: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MeViewModel @Inject constructor(
    wellness: WellnessSync,
    zones: Zones,
) : ViewModel() {

    private val range = MutableStateFlow(DEFAULT_DAYS)

    val state: StateFlow<MeState?> = range.flatMapLatest { n ->
        val zone = ZoneId.systemDefault()
        val to = LocalDate.now(zone)
        val from = to.minusDays(n - 1L)
        combine(wellness.observe(from, to), zones.info) { days, z ->
            val load = z?.let { TrainingLoad.summary(it.recordings, it.zones, to, zone, days = n).daily.toMap() }.orEmpty()
            MeState(n, from, to, BodyTrends.of(days, load, from, to), days.count { it.date in from..to }, z != null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val days: StateFlow<Int> = range

    fun setDays(n: Int) {
        range.value = n
    }

    companion object {
        const val DEFAULT_DAYS = 30
        val RANGES = listOf(14, 30, 90, 180)
    }
}
