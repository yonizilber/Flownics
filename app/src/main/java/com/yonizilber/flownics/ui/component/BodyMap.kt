package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Muscle

/** One legend entry: a swatch at [level] (0..1, as in [BodyMap]'s intensities) and what it means. */
data class BodyMapKey(val label: String, val level: Float)

/**
 * Front and back of a schematic figure with each muscle shaded by [intensity] (0..1; missing or 0
 * is unworked). One hue, light to dark, so more always reads as darker. The drawing adds to the
 * muscle names shown beside it rather than replacing them: screen readers get [description], and
 * a tap on a muscle calls [onMuscleClick].
 */
@Composable
fun BodyMap(
    intensity: Map<Muscle, Float>,
    description: String,
    modifier: Modifier = Modifier,
    legend: List<BodyMapKey> = emptyList(),
    maxWidth: Dp = 320.dp,
    onMuscleClick: ((Muscle) -> Unit)? = null,
) {
    val colors = bodyMapColors()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BodyView.entries.forEach { view ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BodyCanvas(view, intensity, colors, onMuscleClick)
                    Text(
                        stringResource(if (view == BodyView.FRONT) R.string.body_front else R.string.body_back),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (legend.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                legend.forEach { key ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(colors.fill(key.level), RoundedCornerShape(3.dp)),
                        )
                        Text(key.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private class BodyMapColors(val skin: Color, private val idle: Color, private val worked: Color) {
    fun fill(level: Float): Color =
        if (level <= 0f) idle else lerp(idle, worked, MIN_SHADE + (1 - MIN_SHADE) * level.coerceIn(0f, 1f))

    private companion object {
        // Even the lightest worked muscle is clearly apart from an unworked one.
        const val MIN_SHADE = 0.3f
    }
}

@Composable
private fun bodyMapColors() = BodyMapColors(
    skin = MaterialTheme.colorScheme.surfaceContainerHighest,
    idle = MaterialTheme.colorScheme.outlineVariant,
    worked = MaterialTheme.colorScheme.primary,
)

@Composable
private fun BodyCanvas(view: BodyView, intensity: Map<Muscle, Float>, colors: BodyMapColors, onMuscleClick: ((Muscle) -> Unit)?) {
    val tap = if (onMuscleClick == null) Modifier else Modifier.pointerInput(view, onMuscleClick) {
        detectTapGestures { at ->
            val scale = size.width / BodyMapGeometry.WIDTH
            BodyMapGeometry.muscleAt(view, at.x / scale, at.y / scale)?.let(onMuscleClick)
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(BodyMapGeometry.WIDTH / BodyMapGeometry.HEIGHT)
            .then(tap),
    ) {
        val scale = size.width / BodyMapGeometry.WIDTH
        fun path(points: FloatArray) = Path().apply {
            moveTo(points[0] * scale, points[1] * scale)
            for (i in 2 until points.size step 2) lineTo(points[i] * scale, points[i + 1] * scale)
            close()
        }
        // The figure is symmetric, so it reads the same in left-to-right and right-to-left layouts.
        BodyMapGeometry.silhouette.forEach { half ->
            drawPath(path(half), colors.skin)
            drawPath(path(BodyMapGeometry.mirrored(half)), colors.skin)
        }
        drawCircle(colors.skin, BodyMapGeometry.HEAD_R * scale, Offset(BodyMapGeometry.HEAD_X * scale, BodyMapGeometry.HEAD_Y * scale))
        val gap = Stroke(width = 1.dp.toPx())
        BodyMapGeometry.shapes(view).forEach { shape ->
            val fill = colors.fill(intensity[shape.muscle] ?: 0f)
            listOf(path(shape.points), path(BodyMapGeometry.mirrored(shape.points))).forEach {
                drawPath(it, fill)
                // A thin line of skin between neighbouring muscles keeps them apart.
                drawPath(it, colors.skin, style = gap)
            }
        }
    }
}

/** "Body map. Highlighted: Chest, Triceps", for screen readers. */
@Composable
fun bodyMapDescription(muscles: Collection<Muscle>): String =
    if (muscles.isEmpty()) {
        stringResource(R.string.body_map_none)
    } else {
        stringResource(R.string.body_map_desc, muscles.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }.joinToString(", "))
    }
