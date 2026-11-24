package com.yonizilber.flownics.domain.player

import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.workout.RestPart
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.Side
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import com.yonizilber.flownics.domain.workout.WorkoutStep

/** Where a rest exercise stands: done, now, in a later rest, or left for the end of the workout. */
enum class LaneStatus { DONE, NOW, NEXT, AT_END }

/**
 * One rest exercise in the lane (or a round of them done together, [exerciseIds] in order), at
 * [stepIndex]: its rest, or its first step at the end.
 */
data class LaneEntry(val exerciseIds: List<Long>, val status: LaneStatus, val stepIndex: Int) {
    val exerciseId: Long get() = exerciseIds.first()
}

/**
 * The rests as a lane of their own, beside the sets. The exercises done in the rests between sets
 * and rounds (a combined workout's stretches, or the active rest set in the workout) form a queue
 * with its own place: moving back or forward in one lane leaves the other where it is.
 *
 * Only rests marked [RestStep.slot] belong to it; the short rests between the exercises of a round
 * never get an exercise. What doesn't fit in the rests left goes to the end of the workout
 * ([WorkStep.fromRests]), so nothing is lost on the way. Pure, so it is unit-tested.
 */
object RestLane {

    /** An exercise (or a round of them) in the queue; [unit] is its own steps when it waits at the end. */
    private data class Item(val parts: List<RestPart>, val source: Int, val unit: List<WorkoutStep>? = null)

    /** The first step of a unit at the end: a set of a rest exercise that isn't the second side or the next of a round. */
    private fun startsUnit(s: WorkoutStep?): Boolean = s is WorkStep && s.fromRests && s.side != Side.RIGHT && s.restPart == 0

    /** The steps of the unit at the end that starts at [start]. */
    private fun unitEnd(steps: List<WorkoutStep>, start: Int): Int {
        var i = start + 1
        while (i < steps.size && !startsUnit(steps[i]) && (steps[i] as? WorkStep)?.fromRests != false) i++
        return i
    }

    private fun partsAt(steps: List<WorkoutStep>, start: Int): List<RestPart> =
        (start until unitEnd(steps, start)).mapNotNull { (steps[it] as? WorkStep)?.takeIf { w -> w.side != Side.RIGHT } }.map { RestPart(it.exerciseId, it.target) }

    /** The exercises left at the end that can still change: after [from], none logged. */
    private data class Tail(val start: Int, val end: Int, val units: List<List<WorkoutStep>>, val blockIndex: Int)

    /** Every rest exercise of the workout, in order, with where it stands from step [index]. */
    fun entries(steps: List<WorkoutStep>, index: Int): List<LaneEntry> = buildList {
        steps.forEachIndexed { i, s ->
            when {
                s is RestStep && s.slot && s.active.isNotEmpty() -> add(
                    LaneEntry(
                        s.active.map { it.exerciseId },
                        when {
                            i < index -> LaneStatus.DONE
                            i == index -> LaneStatus.NOW
                            else -> LaneStatus.NEXT
                        },
                        i,
                    ),
                )
                startsUnit(s) -> {
                    val end = unitEnd(steps, i)
                    add(
                        LaneEntry(
                            partsAt(steps, i).map { it.exerciseId },
                            when {
                                // Its own rest at the end of the unit belongs to the next one's time, not to it.
                                index in i until end && !(index == end - 1 && steps[index] is RestStep && end - 1 > i) -> LaneStatus.NOW
                                i < index -> LaneStatus.DONE
                                else -> LaneStatus.AT_END
                            },
                            i,
                        ),
                    )
                }
            }
        }
    }

    /** The workout has a rest lane at all: some rest exercise, or a rest that could take one. */
    fun exists(steps: List<WorkoutStep>): Boolean = entries(steps, 0).isNotEmpty()

    /** The rest at [index] has an exercise of the lane in it now. */
    fun isActiveSlot(steps: List<WorkoutStep>, index: Int): Boolean =
        (steps.getOrNull(index) as? RestStep)?.let { it.slot && it.active.isNotEmpty() } == true

    /**
     * The rest exercise to go back to from the rest at [index]: the one done before it when [back]
     * is 0, and one further back each time the user already went back from this rest (the one
     * brought back moves on, so walking back never offers the same one twice).
     */
    fun previousEntry(steps: List<WorkoutStep>, index: Int, back: Int): LaneEntry? {
        val done = entries(steps, index).filter { it.status == LaneStatus.DONE }
        return done.getOrNull(done.lastIndex - back)
    }

    /** Do it again in the next rest too; the later ones each move one rest on. */
    fun again(steps: List<WorkoutStep>, index: Int, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout? {
        if (!isActiveSlot(steps, index)) return null
        val current = item(steps, index)
        return layout(steps, slots(steps, index + 1), listOf(current) + queue(steps, index + 1, logged), library, logged, index, newBlock)
    }

    /** Not now: this rest stays plain and its exercise moves to the next one, the later ones on with it. */
    fun postpone(steps: List<WorkoutStep>, index: Int, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout? {
        if (!isActiveSlot(steps, index)) return null
        val cleared = steps.toMutableList().also { it[index] = (it[index] as RestStep).plain() }
        return layout(cleared, slots(steps, index + 1), queue(steps, index, logged), library, logged, index, newBlock)
    }

    /** Skip it: the next one comes into this rest and the queue closes up (the skipped one can be picked again). */
    fun skip(steps: List<WorkoutStep>, index: Int, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout? {
        if (!isActiveSlot(steps, index)) return null
        return layout(steps, slots(steps, index), queue(steps, index + 1, logged), library, logged, index, newBlock)
    }

    /**
     * Do [entry] now (in this rest, when the current step is a rest between sets) or in the next
     * rest. One still to come moves up from its place; one already done is done again, and the
     * others each move one rest on.
     */
    fun pick(steps: List<WorkoutStep>, index: Int, entry: LaneEntry, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout? {
        val inRest = (steps.getOrNull(index) as? RestStep)?.slot == true
        val from = if (inRest) index else index + 1
        val positions = slots(steps, from)
        if (positions.isEmpty()) return null
        val rest = queue(steps, from, logged)
        val chosen = rest.firstOrNull { it.source == entry.stepIndex } ?: itemAt(steps, entry.stepIndex) ?: return null
        return layout(steps, positions, listOf(chosen.copy(source = -1)) + rest.filter { it.source != entry.stepIndex }, library, logged, index, newBlock)
    }

    /**
     * Moving from step [from] to step [to] in the sets (back to redo one, or ahead) keeps the rest
     * lane where it is: the rests from [to] on take the exercises that were still to come.
     */
    fun moveSets(steps: List<WorkoutStep>, from: Int, to: Int, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout? {
        if (!exists(steps)) return null
        val tailFrom = maxOf(from, to)
        val q = queue(steps, from, logged).filter { it.unit == null || it.source > tailFrom }
        // Rests jumped over weren't done: they stay plain, their exercises go on with the queue.
        val passed = steps.toMutableList()
        slots(steps, from).filter { it < to }.forEach { i -> passed[i] = (steps[i] as RestStep).plain() }
        return layout(passed, slots(steps, to), q, library, logged, tailFrom, newBlock)
    }

    /**
     * New exercises for the rests, [added] one per rest: in this rest when the current step is a
     * rest between sets, else from the next one, the others each moving on (the last ones to the
     * end). When no rest is left, they come first of those at the end.
     */
    fun addNext(steps: List<WorkoutStep>, index: Int, added: List<RestPart>, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout {
        val inRest = (steps.getOrNull(index) as? RestStep)?.slot == true
        val from = if (inRest) index else index + 1
        return layout(steps, slots(steps, from), added.map { Item(listOf(it), -1) } + queue(steps, from, logged), library, logged, index, newBlock)
    }

    /**
     * The rest exercises still to come laid again over the rests from step [from] on, after rests
     * were added there (an exercise added to the main workout): they move into the new rests in
     * order, and fewer are left for the end.
     */
    fun reflow(steps: List<WorkoutStep>, from: Int, library: Map<Long, Exercise>, logged: Set<Int>, newBlock: Int): Layout? {
        if (!exists(steps)) return null
        return layout(steps, slots(steps, from), queue(steps, from, logged), library, logged, from - 1, newBlock)
    }

    /** Steps after a change to the lane; logs at [shiftFrom] and after move by [shiftBy]; [newBlockUsed] when the end got a new block. */
    data class Layout(val steps: List<WorkoutStep>, val shiftFrom: Int, val shiftBy: Int, val newBlockUsed: Boolean)

    private fun slots(steps: List<WorkoutStep>, from: Int): List<Int> =
        steps.indices.filter { it >= from && (steps[it] as? RestStep)?.slot == true }

    private fun item(steps: List<WorkoutStep>, index: Int): Item {
        val r = steps[index] as RestStep
        check(r.active.isNotEmpty())
        return Item(r.active, index)
    }

    /** A rest exercise from wherever it is (a rest, or the end), for doing it again. */
    private fun itemAt(steps: List<WorkoutStep>, index: Int): Item? = when (val s = steps.getOrNull(index)) {
        is RestStep -> s.active.takeIf { it.isNotEmpty() }?.let { Item(it, index) }
        is WorkStep -> if (startsUnit(s)) Item(partsAt(steps, index), index) else null
        null -> null
    }

    /** The exercises still to come from [from]: those in the rests, then those at the end. */
    private fun queue(steps: List<WorkoutStep>, from: Int, logged: Set<Int>): List<Item> {
        val inRests = slots(steps, from).mapNotNull { i -> (steps[i] as RestStep).active.takeIf { it.isNotEmpty() }?.let { Item(it, i) } }
        val tail = tail(steps, from - 1, logged)
        var at = tail?.start ?: 0
        val atEnd = tail?.units.orEmpty().map { unit ->
            Item(unit.filterIsInstance<WorkStep>().filter { it.side != Side.RIGHT }.map { RestPart(it.exerciseId, it.target) }, at, unit).also { at += unit.size }
        }
        return inRests + atEnd
    }

    private fun tail(steps: List<WorkoutStep>, after: Int, logged: Set<Int>): Tail? {
        val lastLogged = logged.filter { (steps.getOrNull(it) as? WorkStep)?.fromRests == true }.maxOrNull() ?: -1
        val firstFree = maxOf(after, lastLogged) + 1
        val start = (firstFree until steps.size).firstOrNull { startsUnit(steps[it]) } ?: return null
        // The end block runs until the next set that isn't one of them (say, an exercise added at the end).
        val end = (start until steps.size).firstOrNull { (steps[it] as? WorkStep)?.fromRests == false } ?: steps.size
        val units = mutableListOf<MutableList<WorkoutStep>>()
        for (i in start until end) {
            val s = steps[i]
            if (startsUnit(s) || units.isEmpty()) units += mutableListOf(s) else units.last() += s
        }
        return Tail(start, end, units, steps[start].blockIndex)
    }

    /**
     * The queue laid over the rests at [positions] in order; rests past its end stay plain, and what
     * doesn't fit becomes (or stays) the block at the end.
     */
    private fun layout(
        steps: List<WorkoutStep>,
        positions: List<Int>,
        queue: List<Item>,
        library: Map<Long, Exercise>,
        logged: Set<Int>,
        tailAfter: Int,
        newBlock: Int,
    ): Layout {
        val confirm = steps.filterIsInstance<RestStep>().firstOrNull { it.slot && it.active.isNotEmpty() }?.holdAtEnd ?: true
        val out = steps.toMutableList()
        positions.forEachIndexed { k, i ->
            val r = steps[i] as RestStep
            val it = queue.getOrNull(k)
            out[i] = r.copy(active = it?.parts.orEmpty(), holdAtEnd = it != null && confirm)
        }
        val overflow = queue.drop(positions.size)
        val tail = tail(steps, tailAfter, logged)
        // With none left to do there, an end block the workout finishes with still takes them (no second one).
        val endBlock = (steps.lastOrNull { it is WorkStep } as? WorkStep)?.takeIf { it.fromRests }?.blockIndex
        val block = tail?.blockIndex ?: endBlock ?: newBlock
        val restBetween = tail?.units?.firstNotNullOfOrNull { u -> (u.lastOrNull() as? RestStep)?.durationSeconds }
            ?: steps.firstOrNull { it is RestStep && !it.slot && it.blockIndex == block }?.durationSeconds
        val newTail = overflow.flatMap { item ->
            val unit = item.unit ?: unitFor(item, library, restBetween)
            unit.map { s ->
                when (s) {
                    is WorkStep -> s.copy(blockIndex = block)
                    is RestStep -> s.copy(blockIndex = block)
                }
            }
        }
        val start = tail?.start ?: steps.size
        val end = tail?.end ?: steps.size
        var region = newTail
        // After the last of the end block (whose own rest went, as nothing came after it), its rest again.
        if (tail == null && endBlock != null && region.isNotEmpty() && restBetween != null && steps.lastOrNull() is WorkStep) {
            region = listOf(RestStep(block, restBetween, slot = false)) + region
        }
        // Nothing to rest for after the very last set.
        if (end == steps.size && region.lastOrNull() is RestStep) region = region.dropLast(1)
        var result = out.take(start) + region + out.drop(end)
        // A rest left plain at the very end (its exercise moved up) has nothing to rest for.
        if (result.lastIndex > tailAfter && (result.lastOrNull() as? RestStep)?.active?.isEmpty() == true) result = result.dropLast(1)
        return Layout(result, shiftFrom = end, shiftBy = region.size - (end - start), newBlockUsed = tail == null && endBlock == null && region.isNotEmpty())
    }

    /**
     * Exercises moved from a rest to the end: one set of each, one after the other (both sides of a
     * one-sided hold), their own time when timed.
     */
    private fun unitFor(item: Item, library: Map<Long, Exercise>, restSeconds: Int?): List<WorkoutStep> =
        item.parts.flatMapIndexed { k, part ->
            val exercise = library[part.exerciseId]
            val timedMode = exercise?.measureMode == MeasureMode.TIME
            val target = part.target ?: if (timedMode) SetTarget(seconds = exercise?.let(WorkoutCompiler::activeRestSeconds) ?: DEFAULT_SECONDS) else SetTarget()
            val timed = timedMode && (target.seconds ?: 0) > 0
            val sides = if (exercise?.unilateral == true && timed) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
            sides.map { side ->
                WorkStep(0, 0, part.exerciseId, 1, 1, 1, 1, target, side, if (timed) target.seconds else null, fromRests = true, restPart = k)
            }
        } + listOfNotNull(restSeconds?.takeIf { it > 0 }?.let { RestStep(0, it, slot = false) })

    private const val DEFAULT_SECONDS = 30
}
