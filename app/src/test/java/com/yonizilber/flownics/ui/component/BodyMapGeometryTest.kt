package com.yonizilber.flownics.ui.component

import com.yonizilber.flownics.domain.model.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMapGeometryTest {

    @Test
    fun everyMuscleIsDrawnSomewhere() {
        val drawn = (BodyMapGeometry.front + BodyMapGeometry.back).map { it.muscle }.toSet()
        assertEquals(Muscle.entries.toSet(), drawn)
    }

    @Test
    fun tappingTheMiddleOfAShapeGivesThatMuscleOnBothSides() {
        for (view in BodyView.entries) {
            for (shape in BodyMapGeometry.shapes(view)) {
                val (x, y) = middle(shape.points)
                assertEquals("$view ${shape.muscle}", shape.muscle, BodyMapGeometry.muscleAt(view, x, y))
                assertEquals("$view ${shape.muscle} mirrored", shape.muscle, BodyMapGeometry.muscleAt(view, 100f - x, y))
            }
        }
    }

    @Test
    fun shapesStayOnTheirHalfAndInsideTheBox() {
        for (shape in BodyMapGeometry.front + BodyMapGeometry.back) {
            shape.points.forEachIndexed { i, v ->
                if (i % 2 == 0) assertTrue("${shape.muscle} x=$v", v in 0f..50f)
                else assertTrue("${shape.muscle} y=$v", v in 0f..BodyMapGeometry.HEIGHT)
            }
        }
    }

    @Test
    fun skinAndBackgroundAreNoMuscle() {
        // Beside the figure, and the head.
        assertNull(BodyMapGeometry.muscleAt(BodyView.FRONT, 2f, 100f))
        assertNull(BodyMapGeometry.muscleAt(BodyView.BACK, 50f, 10f))
    }

    /** A point inside the shape: the vertex average, nudged toward the first vertex if it falls out. */
    private fun middle(points: FloatArray): Pair<Float, Float> {
        val n = points.size / 2
        var x = (0 until n).map { points[2 * it] }.average().toFloat()
        var y = (0 until n).map { points[2 * it + 1] }.average().toFloat()
        repeat(10) {
            if (BodyMapGeometry.contains(points, x, y)) return x to y
            x = (x + points[0]) / 2
            y = (y + points[1]) / 2
        }
        return x to y
    }
}
