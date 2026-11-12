package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.history.MuscleLoad
import com.yonizilber.flownics.domain.model.Muscle
import java.text.DecimalFormat

// The legend's light swatch: a muscle with a small share of the sets.
private const val LEGEND_FEW = 0.15f

/**
 * Sets per muscle as one-colour bars growing from the start edge, busiest first, value at the tip.
 * Muscles with nothing are listed by name rather than drawn as empty bars. Above the bars, the same
 * numbers shade a body map, darker for more sets. Used for the week in
 * history and for a planned workout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuscleLoadCard(
    load: MuscleLoad,
    title: String,
    emptyText: String,
    caption: String,
    untouchedTitle: String,
    modifier: Modifier = Modifier,
    onMuscleClick: ((Muscle) -> Unit)? = null,
) {
    val ranked = load.ranked
    val max = ranked.firstOrNull()?.second ?: 0.0
    val setsFormat = DecimalFormat("0.#")
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (ranked.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BodyMap(
                    intensity = ranked.associate { (muscle, sets) -> muscle to (sets / max).toFloat() },
                    description = bodyMapDescription(ranked.map { it.first }),
                    legend = listOf(
                        BodyMapKey(stringResource(R.string.body_legend_fewer), LEGEND_FEW),
                        BodyMapKey(stringResource(R.string.body_legend_more), 1f),
                    ),
                    maxWidth = 280.dp,
                    onMuscleClick = onMuscleClick,
                )
                ranked.forEach { (muscle, sets) ->
                    Row(
                        Modifier.then(if (onMuscleClick != null) Modifier.clickable { onMuscleClick(muscle) } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(muscle.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(104.dp),
                        )
                        Box(Modifier.weight(1f)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((sets / max).toFloat().coerceAtLeast(0.03f))
                                    .height(10.dp)
                                    // Rounded at the value end only; square where it grows from.
                                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Text(
                            setsFormat.format(sets),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(36.dp),
                        )
                    }
                }
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (load.uncountedSets > 0) {
                Text(
                    LocalResources.current.getQuantityString(R.plurals.history_muscles_uncounted, load.uncountedSets, load.uncountedSets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val untouched = load.untouched
            if (ranked.isNotEmpty() && untouched.isNotEmpty()) {
                Text(
                    untouchedTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    untouched.forEach { muscle ->
                        SuggestionChip(onClick = { onMuscleClick?.invoke(muscle) }, label = { Text(stringResource(muscle.labelRes)) })
                    }
                }
            }
        }
    }
}
