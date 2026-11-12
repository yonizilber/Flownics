package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R

/** Big −/+ buttons: easier than a keyboard with sweaty hands mid-workout. */
@Composable
fun NumberStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..999,
    step: Int = 1,
    format: (Int) -> String = { it.toString() },
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
                onClick = { onValueChange((value - step).coerceIn(range)) },
                enabled = value > range.first,
            ) { Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.wedit_decrease)) }
            Text(
                format(value),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 48.dp),
            )
            FilledTonalIconButton(
                onClick = { onValueChange((value + step).coerceIn(range)) },
                enabled = value < range.last,
            ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.wedit_increase)) }
        }
    }
}
