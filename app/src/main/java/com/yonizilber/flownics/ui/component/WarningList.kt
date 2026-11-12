package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.workout.CompileWarning

@Composable
fun WarningList(
    warnings: List<CompileWarning>,
    blocks: List<WorkoutBlock>,
    library: Map<Long, Exercise>,
    modifier: Modifier = Modifier,
) {
    if (warnings.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            warnings.forEach { warning ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(warningText(warning, blocks, library), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
