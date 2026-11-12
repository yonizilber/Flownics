package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.domain.model.ExerciseCategory

@Composable
fun CategoryBadge(category: ExerciseCategory, modifier: Modifier = Modifier) {
    val colors = category.colors()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(category.icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(16.dp))
        Text(stringResource(category.labelRes), style = MaterialTheme.typography.labelMedium, color = colors.content)
    }
}

@Composable
fun CategoryAvatar(category: ExerciseCategory, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val colors = category.colors()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(category.icon, contentDescription = stringResource(category.labelRes), tint = colors.content)
    }
}
