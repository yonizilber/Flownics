package com.yonizilber.flownics.ui.component

import com.yonizilber.flownics.domain.model.Muscle

enum class BodyView { FRONT, BACK }

/** One muscle's outline on the figure's left half; it is drawn again mirrored on the right. */
class MuscleShape(val muscle: Muscle, val points: FloatArray) {
    init {
        require(points.size >= 6 && points.size % 2 == 0)
    }
}

/**
 * A schematic figure drawn for this app (no outside artwork, so nothing to license), in a
 * [WIDTH] x [HEIGHT] box with the body's midline at x = 50. Only the left half is described;
 * the right half is its mirror image. Muscle shapes don't overlap, so a tap means one muscle.
 */
object BodyMapGeometry {
    const val WIDTH = 100f
    const val HEIGHT = 212f
    private const val MID = 50f

    /** The silhouette, left half (except the head, which is a circle on the midline). */
    val silhouette: List<FloatArray> = listOf(
        // Neck
        floatArrayOf(45f, 20f, 50f, 20f, 50f, 30f, 45f, 30f),
        // Torso
        floatArrayOf(50f, 28f, 42f, 28f, 30f, 32f, 27f, 38f, 29f, 50f, 34f, 60f, 35f, 76f, 34f, 92f, 32f, 102f, 50f, 104f),
        // Upper arm
        floatArrayOf(27f, 34f, 22f, 40f, 20f, 60f, 21f, 79f, 29.5f, 79f, 32f, 60f, 32f, 46f),
        // Forearm
        floatArrayOf(21f, 79f, 17f, 96f, 17f, 112f, 24f, 112f, 29f, 96f, 29.5f, 79f),
        // Hand
        floatArrayOf(17f, 112f, 16f, 120f, 19f, 124f, 23f, 122f, 24f, 112f),
        // Thigh
        floatArrayOf(32f, 100f, 31f, 120f, 33f, 152f, 47.5f, 152f, 49.5f, 120f, 50f, 104f),
        // Lower leg
        floatArrayOf(33f, 152f, 34f, 172f, 37f, 200f, 45f, 200f, 47f, 172f, 47.5f, 152f),
        // Foot
        floatArrayOf(37f, 200f, 34f, 208f, 46f, 208f, 45f, 200f),
    )
    const val HEAD_X = 50f
    const val HEAD_Y = 12f
    const val HEAD_R = 9f

    private fun shape(muscle: Muscle, vararg points: Float) = MuscleShape(muscle, points)

    private val forearms = shape(Muscle.FOREARMS, 21f, 81f, 28f, 81f, 28f, 94f, 24f, 110f, 19f, 110f, 18f, 96f)

    val front: List<MuscleShape> = listOf(
        shape(Muscle.NECK, 45f, 22f, 47f, 22f, 49f, 30f, 46f, 30f),
        shape(Muscle.TRAPS, 44f, 27f, 46f, 30f, 38f, 32f, 33f, 32f),
        shape(Muscle.SHOULDERS, 30f, 32f, 36f, 32f, 37f, 40f, 33f, 48f, 28f, 48f, 27f, 40f),
        shape(Muscle.CHEST, 37f, 34f, 49f, 34f, 49f, 49f, 42f, 51f, 37f, 46f),
        shape(Muscle.BICEPS, 23f, 49f, 30f, 49f, 31f, 62f, 29f, 76f, 23f, 76f, 21f, 62f),
        forearms,
        shape(Muscle.ABS, 43f, 53f, 49f, 53f, 49f, 92f, 44f, 90f, 43f, 72f),
        shape(Muscle.OBLIQUES, 35f, 56f, 42f, 54f, 42f, 74f, 43f, 88f, 36f, 84f, 36f, 70f),
        shape(Muscle.HIP_FLEXORS, 37f, 88f, 43f, 92f, 46f, 104f, 41f, 104f, 36f, 94f),
        shape(Muscle.QUADS, 33f, 106f, 42f, 106f, 46f, 116f, 46f, 148f, 36f, 150f, 33f, 130f),
        shape(Muscle.ADDUCTORS, 46f, 108f, 49.5f, 108f, 48.5f, 130f, 46.5f, 138f, 46f, 116f),
        // The edge of the calf, as it shows from the front.
        shape(Muscle.CALVES, 34f, 160f, 37f, 158f, 38f, 184f, 36f, 186f, 34f, 172f),
    )

    val back: List<MuscleShape> = listOf(
        shape(Muscle.NECK, 46f, 21f, 50f, 21f, 50f, 26f, 46f, 26f),
        shape(Muscle.TRAPS, 44f, 26f, 50f, 26f, 50f, 62f, 48f, 58f, 45f, 46f, 33f, 33.5f, 40f, 30f),
        shape(Muscle.SHOULDERS, 29f, 34f, 33f, 35.5f, 35f, 42f, 32f, 48f, 28f, 47f, 27f, 39f),
        shape(Muscle.UPPER_BACK, 35.5f, 38.5f, 44.5f, 47f, 47.6f, 58.5f, 43f, 61f, 37f, 53f, 35.5f, 45f),
        shape(Muscle.LATS, 35f, 54f, 42f, 61f, 46f, 64f, 47f, 78f, 40f, 84f, 36f, 74f),
        shape(Muscle.LOWER_BACK, 47.5f, 66f, 50f, 66f, 50f, 94f, 45f, 92f, 47.5f, 80f),
        shape(Muscle.TRICEPS, 22f, 48f, 30f, 48f, 32f, 58f, 30f, 76f, 23f, 76f, 21f, 60f),
        forearms,
        shape(Muscle.ABDUCTORS, 33f, 92f, 42f, 92f, 40f, 100f, 33f, 106f),
        shape(Muscle.GLUTES, 35f, 107f, 41f, 99f, 49.5f, 98f, 49.5f, 116f, 44f, 120f, 35f, 118f),
        shape(Muscle.HAMSTRINGS, 34f, 121f, 45f, 121f, 47f, 124f, 47f, 148f, 37f, 150f, 34f, 136f),
        shape(Muscle.ADDUCTORS, 47.2f, 122f, 49.5f, 118f, 48.5f, 134f, 47.3f, 140f),
        shape(Muscle.CALVES, 35f, 158f, 46f, 158f, 47f, 172f, 44f, 190f, 38f, 190f, 34f, 172f),
    )

    fun shapes(view: BodyView) = if (view == BodyView.FRONT) front else back

    /** The muscle under a point in figure coordinates, either half, or null for skin or background. */
    fun muscleAt(view: BodyView, x: Float, y: Float): Muscle? {
        val half = if (x > MID) 2 * MID - x else x
        return shapes(view).lastOrNull { contains(it.points, half, y) }?.muscle
    }

    /** Even-odd ray casting; the shapes are simple polygons. */
    fun contains(points: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        var j = points.size - 2
        var i = 0
        while (i < points.size) {
            val xi = points[i]
            val yi = points[i + 1]
            val xj = points[j]
            val yj = points[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
            i += 2
        }
        return inside
    }

    /** The mirrored copy of a left-half outline. */
    fun mirrored(points: FloatArray) = FloatArray(points.size) { i -> if (i % 2 == 0) 2 * MID - points[i] else points[i] }
}
