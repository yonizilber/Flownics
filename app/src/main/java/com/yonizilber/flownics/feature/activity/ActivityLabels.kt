package com.yonizilber.flownics.feature.activity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.SegmentKind

val ActivityType.labelRes: Int
    get() = when (this) {
        ActivityType.RUN -> R.string.activity_type_run
        ActivityType.WALK -> R.string.activity_type_walk
        ActivityType.HIKE -> R.string.activity_type_hike
        ActivityType.RIDE -> R.string.activity_type_ride
        ActivityType.SWIM -> R.string.activity_type_swim
        ActivityType.OTHER -> R.string.activity_type_other
    }

val ActivityType.icon: ImageVector
    get() = when (this) {
        ActivityType.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
        ActivityType.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
        ActivityType.HIKE -> Icons.Outlined.Hiking
        ActivityType.RIDE -> Icons.AutoMirrored.Outlined.DirectionsBike
        ActivityType.SWIM -> Icons.Outlined.Pool
        ActivityType.OTHER -> Icons.Outlined.FitnessCenter
    }

val SegmentKind.labelRes: Int
    get() = when (this) {
        SegmentKind.WARMUP -> R.string.segment_warmup
        SegmentKind.WORK -> R.string.segment_work
        SegmentKind.RECOVERY -> R.string.segment_recovery
        SegmentKind.COOLDOWN -> R.string.segment_cooldown
        SegmentKind.STEADY -> R.string.segment_steady
    }

/** The name to show: the user's title, or the type ("Run"). */
@Composable
fun Activity.displayTitle(): String = title.ifBlank { stringResource(type.labelRes) }

/** "8.2 km · 42:10 · 5:08 /km · HR 152": what is known, in that order. */
@Composable
fun Activity.summary(): String = buildList {
    distanceMeters?.let { add(stringResource(R.string.activity_km, ActivityFormat.formatKm(it))) }
    add(ActivityFormat.formatDuration(durationSeconds))
    paceSecondsPerKm?.takeIf { type != ActivityType.SWIM && type != ActivityType.RIDE }?.let { add(stringResource(R.string.activity_pace, ActivityFormat.formatPace(it))) }
    avgHeartRate?.let { add(stringResource(R.string.activity_hr, it)) }
}.joinToString(" · ")
