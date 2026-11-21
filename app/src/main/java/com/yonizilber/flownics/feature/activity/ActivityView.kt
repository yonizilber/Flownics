package com.yonizilber.flownics.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityFormat
import com.yonizilber.flownics.domain.model.ActivitySegment
import com.yonizilber.flownics.domain.model.ActivityType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * An activity as it was saved, to read: what opens from the journal and the history. Editing is
 * one tap away (the pencil), never the first thing.
 */
@Composable
fun ActivityDetails(a: Activity) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(a.type.icon, contentDescription = stringResource(a.type.labelRes), modifier = Modifier.size(28.dp))
            Text(a.displayTitle(), style = MaterialTheme.typography.headlineSmall)
        }
        val start = Instant.ofEpochMilli(a.startedAtMillis).atZone(ZoneId.systemDefault())
        Text(
            start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) + " · " + start.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(a.summary(), style = MaterialTheme.typography.titleMedium)
        val more = buildList {
            a.maxHeartRate?.let { add(stringResource(R.string.activity_view_max_hr, it)) }
            a.elevationGainMeters?.takeIf { it > 0 }?.let { add(stringResource(R.string.activity_view_climb, it)) }
            a.rpe?.let { add(stringResource(R.string.activity_view_rpe, it)) }
        }
        if (more.isNotEmpty()) Text(more.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        if (a.notes.isNotBlank()) {
            Text(stringResource(R.string.activity_notes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            Text(a.notes, style = MaterialTheme.typography.bodyMedium)
        }
        if (a.segments.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.activity_view_parts, a.segments.size), style = MaterialTheme.typography.titleSmall)
            a.segments.forEachIndexed { i, seg -> Text("${i + 1}. " + segmentLine(seg, a.type), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** "Work: 3:00 · 0.66 km · 4:31 /km · HR 162", what is known of the part. */
@Composable
private fun segmentLine(seg: ActivitySegment, type: ActivityType): String {
    val parts = buildList {
        seg.durationSeconds?.let { add(ActivityFormat.formatDuration(it)) }
        seg.distanceMeters?.let { add(stringResource(R.string.activity_km, ActivityFormat.formatKm(it))) }
        seg.paceSecondsPerKm?.takeIf { type != ActivityType.SWIM && type != ActivityType.RIDE }?.let { add(stringResource(R.string.activity_pace, ActivityFormat.formatPace(it))) }
        seg.avgHeartRate?.let { add(stringResource(R.string.activity_hr, it)) }
        seg.note.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return stringResource(seg.kind.labelRes) + if (parts.isEmpty()) "" else ": " + parts.joinToString(" · ")
}
