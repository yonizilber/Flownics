package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.fit.HeartZones

/**
 * Time in each heart-rate zone: one bar, zone 1 to 5 in one hue from light to dark, with the
 * minutes written out under it (so it doesn't rely on colour), and a caption saying how the zones
 * were set.
 */
@Composable
fun ZonesBar(histogram: List<Int>, zones: HeartZones, maxFromRecordings: Boolean) {
    val seconds = zones.secondsInZones(histogram)
    val total = seconds.sum()
    if (total <= 0) return
    val base = MaterialTheme.colorScheme.error
    val minutes = seconds.map { (it + 30) / 60 }
    val text = minutes.mapIndexed { i, m -> "Z${i + 1} $m′" }.joinToString(" · ")
    val bounds = zones.bounds
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.zones_title), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics { contentDescription = text },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            seconds.forEachIndexed { i, s ->
                if (s > 0) Box(Modifier.weight(s.toFloat()).fillMaxHeight().background(base.copy(alpha = 0.25f + 0.15f * i)))
            }
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(
                if (maxFromRecordings) R.string.zones_caption_estimated else R.string.zones_caption,
                (1..5).joinToString(", ") { "Z$it ${bounds[it - 1]}–${bounds[it]}" },
                zones.max,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
