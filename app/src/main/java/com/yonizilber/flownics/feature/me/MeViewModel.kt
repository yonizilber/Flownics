package com.yonizilber.flownics.feature.me

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import com.yonizilber.flownics.sensors.ScaleEvent
import com.yonizilber.flownics.sensors.MiScale
import com.yonizilber.flownics.domain.body.Weights
import com.yonizilber.flownics.domain.body.WeightSource
import com.yonizilber.flownics.domain.body.WeightEntry
import com.yonizilber.flownics.data.repository.WeightRepository
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
    /** Every weigh-in, newest first. */
    val weighIns: List<WeightEntry> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MeViewModel @Inject constructor(
    wellness: WellnessSync,
    zones: Zones,
    private val weights: WeightRepository,
    val scale: MiScale,
) : ViewModel() {

    private val range = MutableStateFlow(DEFAULT_DAYS)

    val state: StateFlow<MeState?> = range.flatMapLatest { n ->
        val zone = ZoneId.systemDefault()
        val to = LocalDate.now(zone)
        val from = to.minusDays(n - 1L)
        combine(wellness.observe(from, to), zones.info, weights.observeAll()) { days, z, w ->
            val load = z?.let { TrainingLoad.summary(it.recordings, it.zones, to, zone, days = n).daily.toMap() }.orEmpty()
            MeState(n, from, to, BodyTrends.of(days, load, from, to, Weights.daily(w, zone)), days.count { it.date in from..to }, z != null, w.sortedByDescending { it.atMillis })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val days: StateFlow<Int> = range

    fun setDays(n: Int) {
        range.value = n
    }

    private val _weighIn = MutableStateFlow<ScaleEvent?>(null)

    /** The weigh-in with the scale under way (or just finished); null when none. */
    val weighIn: StateFlow<ScaleEvent?> = _weighIn.asStateFlow()
    private var weighJob: Job? = null

    fun startWeighIn() {
        weighJob?.cancel()
        weighJob = viewModelScope.launch { scale.weighIn().collect { _weighIn.value = it } }
    }

    fun closeWeighIn() {
        weighJob?.cancel()
        _weighIn.value = null
    }

    /** A weigh-in typed in; refused (false) outside a body's range. */
    fun addManual(kg: Double, atMillis: Long): Boolean {
        if (kg !in Weights.RANGE) return false
        viewModelScope.launch { weights.add(listOf(WeightEntry(atMillis = atMillis, kg = kg, source = WeightSource.MANUAL))) }
        return true
    }

    fun delete(entry: WeightEntry) = viewModelScope.launch { weights.delete(entry.id) }

    companion object {
        const val DEFAULT_DAYS = 30
        val RANGES = listOf(14, 30, 90, 180)
    }
}
