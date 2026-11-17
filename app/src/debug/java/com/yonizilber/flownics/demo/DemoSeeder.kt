package com.yonizilber.flownics.demo

import com.yonizilber.flownics.data.coach.CoachStore
import com.yonizilber.flownics.data.garmin.FitImporter
import com.yonizilber.flownics.data.local.db.dao.WellnessDao
import com.yonizilber.flownics.data.local.db.entity.WellnessDayEntity
import com.yonizilber.flownics.data.local.prefs.AiSettingsStore
import com.yonizilber.flownics.data.local.prefs.AppPreferences
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.data.repository.ActivityRepository
import com.yonizilber.flownics.data.repository.PlanRepository
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.coach.CoachProfile
import com.yonizilber.flownics.domain.model.ActiveRest
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.model.BlockStructure
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.ExerciseCategory.BALANCE
import com.yonizilber.flownics.domain.model.ExerciseCategory.CORE
import com.yonizilber.flownics.domain.model.ExerciseCategory.FLEXIBILITY
import com.yonizilber.flownics.domain.model.ExerciseCategory.MOBILITY
import com.yonizilber.flownics.domain.model.ExerciseCategory.STRENGTH
import com.yonizilber.flownics.domain.model.ExerciseCategory.WARMUP
import com.yonizilber.flownics.domain.model.ExerciseStage
import com.yonizilber.flownics.domain.model.ExerciseTag
import com.yonizilber.flownics.domain.model.MeasureMode
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.model.Muscle.*
import com.yonizilber.flownics.domain.model.PlanKind
import com.yonizilber.flownics.domain.model.PlannedStep
import com.yonizilber.flownics.domain.model.PlannedWorkout
import com.yonizilber.flownics.domain.model.PoolFocus
import com.yonizilber.flownics.domain.model.RestFiller
import com.yonizilber.flownics.domain.model.SegmentKind
import com.yonizilber.flownics.domain.model.SessionStatus
import com.yonizilber.flownics.domain.model.SetLog
import com.yonizilber.flownics.domain.model.SetTarget
import com.yonizilber.flownics.domain.model.WorkoutBlock
import com.yonizilber.flownics.domain.model.WorkoutItem
import com.yonizilber.flownics.domain.model.WorkoutSession
import com.yonizilber.flownics.domain.model.WorkoutTemplate
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.domain.repository.SessionRepository
import com.yonizilber.flownics.domain.repository.WorkoutRepository
import com.yonizilber.flownics.domain.wellness.SleepLevel
import com.yonizilber.flownics.domain.wellness.SleepStage
import com.yonizilber.flownics.domain.wellness.WellnessDay
import com.yonizilber.flownics.domain.workout.RestStep
import com.yonizilber.flownics.domain.workout.WorkStep
import com.yonizilber.flownics.domain.workout.WorkoutCompiler
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug builds only: fills an EMPTY app with made-up demo data (no real person's data) for
 * screenshots: a library with tags and families, workouts, six weeks of sessions, recorded runs
 * (through the real FIT import), a week of plans, all-day watch data and a coach conversation.
 * Refuses to touch an app that already has exercises.
 */
class DemoSeeder @Inject constructor(
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val sessions: SessionRepository,
    private val activities: ActivityRepository,
    private val plans: PlanRepository,
    private val fit: FitImporter,
    private val wellness: WellnessDao,
    private val coach: CoachStore,
    private val prefs: AppPreferences,
    private val ai: AiSettingsStore,
    private val json: Json,
) {
    private var he = false
    private fun t(en: String, iw: String) = if (he) iw else en

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(daysAgo: Int, hour: Int, minute: Int = 0): Long =
        today.minusDays(daysAgo.toLong()).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    suspend fun seed(hebrew: Boolean, log: (String) -> Unit) {
        he = hebrew
        check(exercises.observeAll().first().isEmpty()) { "the app already has exercises: demo data goes only into an empty app" }
        prefs.acceptDisclaimer()
        // A placeholder, not a key: the coach screen then looks as it does in use. Any call with it fails.
        if (ai.apiKey(AiProvider.GEMINI) == null) ai.setKey(AiProvider.GEMINI, "DEMO-NOT-A-REAL-KEY")

        log("exercises")
        val ids = library()
        log("workouts")
        val w = workouts(ids)
        log("sessions")
        history(w, ids)
        log("runs")
        val intervalsId = runs()
        log("plans")
        plans(w)
        log("wellness")
        wellnessDays()
        log("coach")
        coach(intervalsId)
    }

    private class Ids(val map: Map<String, Long>) {
        operator fun get(k: String) = map.getValue(k)
    }

    private suspend fun library(): Ids {
        val ids = mutableMapOf<String, Long>()
        val split = t("side split", "שפגט צד")
        val hips = t("hip mobility", "מוביליטי ירכיים")
        suspend fun add(key: String, e: Exercise) { ids[key] = exercises.save(e) }

        add("warmup", Exercise(
            name = t("General warm-up", "חימום כללי"), category = WARMUP, general = true, measureMode = MeasureMode.TIME,
            notes = t("Easy jog or skipping, arm circles, leg swings. Until you feel warm, then tap next.",
                "ריצה קלה או דילגית, סיבובי ידיים, הנפות רגליים. עד שמרגישים חם, ואז ממשיכים."),
        ))
        add("goblet", Exercise(
            name = t("Goblet squat", "סקוואט גביע"), category = STRENGTH, primaryMuscles = setOf(QUADS, GLUTES), secondaryMuscles = setOf(ADDUCTORS, ABS),
            equipment = t("Kettlebell", "קטלבל"), measureMode = MeasureMode.REPS_WEIGHT, level = 2,
            instructions = listOf(
                t("Hold the kettlebell by the horns at your chest.", "מחזיקים את הקטלבל בידיות מול החזה."),
                t("Feet a little wider than hips, toes slightly out.", "כפות רגליים מעט רחב מהאגן, אצבעות מעט החוצה."),
                t("Sit down between your heels, chest up.", "יורדים לישיבה בין העקבים, חזה למעלה."),
                t("Drive up through the whole foot.", "עולים בדחיפה מכל כף הרגל."),
            ),
            cues = listOf(t("Knees track over toes", "ברכיים בכיוון האצבעות"), t("Elbows inside the knees at the bottom", "מרפקים בתוך הברכיים בתחתית"), t("Brace before each rep", "נעילת בטן לפני כל חזרה")),
            stages = listOf(ExerciseStage(t("Lower", "ירידה"), t("3 seconds down", "3 שניות למטה"), 3), ExerciseStage(t("Pause", "עצירה"), t("1 second at the bottom", "שנייה בתחתית"), 1), ExerciseStage(t("Drive up", "עלייה"), t("Fast but controlled", "מהיר אבל בשליטה"))),
            tags = listOf(ExerciseTag(hips, t("depth under load", "עומק תחת משקל"))),
        ))
        add("rdl", Exercise(
            name = t("Romanian deadlift", "דדליפט רומני"), category = STRENGTH, primaryMuscles = setOf(HAMSTRINGS, GLUTES), secondaryMuscles = setOf(LOWER_BACK, FOREARMS),
            equipment = t("Barbell", "מוט"), measureMode = MeasureMode.REPS_WEIGHT, level = 3,
            instructions = listOf(t("Stand tall, bar at the hips.", "עומדים זקוף, המוט מול האגן."), t("Push the hips back, bar close to the legs.", "דוחפים את האגן אחורה, המוט צמוד לרגליים."), t("Stop when the hamstrings are long, back flat.", "עוצרים כשהמשך האחורי מתוח, הגב ישר."), t("Squeeze the glutes to stand.", "מכווצים ישבן לעמידה.")),
            cues = listOf(t("Soft knees, not a squat", "ברכיים רכות, לא סקוואט"), t("Lats tight", "גב רחב נעול")),
        ))
        add("slrdl", Exercise(
            name = t("Single-leg RDL", "דדליפט רומני על רגל אחת"), category = STRENGTH, extraCategories = setOf(BALANCE), primaryMuscles = setOf(HAMSTRINGS, GLUTES), secondaryMuscles = setOf(ABDUCTORS),
            equipment = t("Dumbbell", "משקולת יד"), measureMode = MeasureMode.REPS_WEIGHT, unilateral = true, level = 3, variationOf = ids["rdl"],
            cues = listOf(t("Hips square to the floor", "אגן מקביל לרצפה")),
        ))
        add("bss", Exercise(
            name = t("Bulgarian split squat", "סקוואט בולגרי"), category = STRENGTH, extraCategories = setOf(BALANCE), primaryMuscles = setOf(QUADS, GLUTES), secondaryMuscles = setOf(ADDUCTORS, HIP_FLEXORS),
            equipment = t("Bench, dumbbells", "ספסל, משקולות יד"), measureMode = MeasureMode.REPS_WEIGHT, unilateral = true, level = 3,
        ))
        add("pushup", Exercise(
            name = t("Push-up", "שכיבת סמיכה"), category = STRENGTH, primaryMuscles = setOf(CHEST, TRICEPS), secondaryMuscles = setOf(SHOULDERS, ABS), level = 2,
            cues = listOf(t("One straight line, head to heels", "קו ישר מהראש לעקבים"), t("Elbows about 45°", "מרפקים בערך 45°")),
        ))
        add("inclinepush", Exercise(
            name = t("Incline push-up", "שכיבת סמיכה בשיפוע"), category = STRENGTH, primaryMuscles = setOf(CHEST, TRICEPS), secondaryMuscles = setOf(SHOULDERS),
            equipment = t("Bench", "ספסל"), level = 1, variationOf = ids["pushup"],
        ))
        add("pullup", Exercise(
            name = t("Pull-up", "מתח"), category = STRENGTH, primaryMuscles = setOf(LATS, UPPER_BACK), secondaryMuscles = setOf(BICEPS, FOREARMS),
            equipment = t("Pull-up bar", "מוט מתח"), level = 3, cues = listOf(t("Start from a dead hang", "מתחילים מתלייה מלאה"), t("Chest to the bar", "חזה אל המוט")),
        ))
        add("ohp", Exercise(
            name = t("Overhead press", "לחיצת כתפיים"), category = STRENGTH, primaryMuscles = setOf(SHOULDERS, TRICEPS), secondaryMuscles = setOf(UPPER_BACK, ABS),
            equipment = t("Barbell", "מוט"), measureMode = MeasureMode.REPS_WEIGHT, level = 2,
        ))
        add("plank", Exercise(
            name = t("Plank", "פלאנק"), category = CORE, primaryMuscles = setOf(ABS), secondaryMuscles = setOf(OBLIQUES, SHOULDERS), measureMode = MeasureMode.TIME, level = 1,
        ))
        add("deadbug", Exercise(
            name = t("Dead bug", "חיפושית מתה"), category = CORE, primaryMuscles = setOf(ABS), secondaryMuscles = setOf(HIP_FLEXORS), level = 1,
            cues = listOf(t("Lower back stays on the floor", "הגב התחתון נשאר על הרצפה")),
        ))
        add("couch", Exercise(
            name = t("Couch stretch", "מתיחת ספה"), category = FLEXIBILITY, primaryMuscles = setOf(HIP_FLEXORS, QUADS), measureMode = MeasureMode.TIME, unilateral = true, level = 2,
            stages = listOf(ExerciseStage(t("Settle in", "כניסה"), t("Knee against the wall, tuck the pelvis", "ברך צמודה לקיר, אגן מגולגל"), 15), ExerciseStage(t("Hold", "החזקה"), t("Tall chest, breathe", "חזה גבוה, נושמים"), 30)),
            tags = listOf(ExerciseTag(hips, t("front of the hip", "קדמת הירך"))),
        ))
        add("pancake", Exercise(
            name = t("Pancake stretch", "מתיחת פנקייק"), category = FLEXIBILITY, primaryMuscles = setOf(ADDUCTORS, HAMSTRINGS), secondaryMuscles = setOf(LOWER_BACK), measureMode = MeasureMode.TIME, level = 2,
            tags = listOf(ExerciseTag(split, t("opens the adductors", "פותח את המקרבים"))),
        ))
        add("horse", Exercise(
            name = t("Horse stance", "עמידת סוס"), category = STRENGTH, extraCategories = setOf(FLEXIBILITY), primaryMuscles = setOf(ADDUCTORS, QUADS), secondaryMuscles = setOf(GLUTES),
            measureMode = MeasureMode.TIME, level = 2, tags = listOf(ExerciseTag(split, t("strength at the end range", "כוח בטווח הקצה"))),
        ))
        add("frog", Exercise(
            name = t("Frog stretch", "מתיחת צפרדע"), category = FLEXIBILITY, primaryMuscles = setOf(ADDUCTORS), measureMode = MeasureMode.TIME, level = 2,
            tags = listOf(ExerciseTag(split, t("hip rotation in the split", "סיבוב הירך בשפגט")), ExerciseTag(hips)),
        ))
        add("cossack", Exercise(
            name = t("Cossack squat", "סקוואט קוזאקי"), category = MOBILITY, extraCategories = setOf(STRENGTH), primaryMuscles = setOf(ADDUCTORS, QUADS), secondaryMuscles = setOf(GLUTES, HAMSTRINGS),
            unilateral = true, level = 3, tags = listOf(ExerciseTag(split, t("adductors long and loaded", "מקרבים ארוכים ותחת עומס"))),
        ))
        add("thoracic", Exercise(
            name = t("Thoracic rotations", "סיבובי גב עליון"), category = MOBILITY, primaryMuscles = setOf(UPPER_BACK), secondaryMuscles = setOf(OBLIQUES), unilateral = true, level = 1,
        ))
        add("9090", Exercise(
            name = t("90/90 hip switch", "החלפות 90/90"), category = MOBILITY, primaryMuscles = setOf(GLUTES, HIP_FLEXORS), secondaryMuscles = setOf(ADDUCTORS, ABDUCTORS), level = 2,
            tags = listOf(ExerciseTag(hips, t("internal and external rotation", "סיבוב פנימי וחיצוני"))),
        ))
        exercises.setTagDescription(split, t("Goal: a flat side split by summer. Adductor length plus strength at the end range.", "מטרה: שפגט צד מלא עד הקיץ. אורך של המקרבים וכוח בטווח הקצה."))
        return Ids(ids)
    }

    private class Workouts(val a: Long, val b: Long, val split: Long, val templates: Map<Long, WorkoutTemplate>)

    private suspend fun workouts(ids: Ids): Workouts {
        val split = WorkoutTemplate(
            name = t("Side split flow", "רצף לשפגט צד"), description = t("Adductors: long, then strong.", "מקרבים: קודם ארוכים, אחר כך חזקים."),
            blocks = listOf(WorkoutBlock(title = t("Flow", "רצף"), items = listOf(
                WorkoutItem(ids["pancake"], sets = 2, target = SetTarget(seconds = 60), restSeconds = 20),
                WorkoutItem(ids["horse"], sets = 3, target = SetTarget(seconds = 40), restSeconds = 30),
                WorkoutItem(ids["frog"], sets = 2, target = SetTarget(seconds = 60), restSeconds = 20),
                WorkoutItem(ids["cossack"], sets = 2, target = SetTarget(reps = 8), restSeconds = 30),
            ))),
        )
        val splitId = workouts.save(split)
        val a = WorkoutTemplate(
            name = t("Strength A", "כוח A"), description = t("Squat, push, pull. Stretch in the rests.", "סקוואט, דחיפה, משיכה. מתיחות במנוחות."),
            blocks = listOf(
                WorkoutBlock(title = t("Warm-up", "חימום"), items = listOf(WorkoutItem(ids["warmup"], sets = 1, target = SetTarget(), restSeconds = 0))),
                WorkoutBlock(title = t("Main", "עיקרי"), items = listOf(
                    WorkoutItem(ids["goblet"], sets = 4, target = SetTarget(reps = 8, weightKg = 24.0), restSeconds = 90, activeRest = ActiveRest.Pool(setOf(FLEXIBILITY, MOBILITY), PoolFocus.WORKED_MUSCLES)),
                )),
                WorkoutBlock(title = t("Superset", "סופרסט"), structure = BlockStructure.CIRCUIT, rounds = 3, roundRestSeconds = 75, roundActiveRest = ActiveRest.Specific(ids["thoracic"]), items = listOf(
                    WorkoutItem(ids["pushup"], target = SetTarget(reps = 12), restSeconds = 15),
                    WorkoutItem(ids["pullup"], target = SetTarget(reps = 6), restSeconds = 15),
                )),
                WorkoutBlock(title = t("Core", "ליבה"), items = listOf(
                    WorkoutItem(ids["plank"], sets = 3, target = SetTarget(seconds = 45), restSeconds = 30),
                    WorkoutItem(ids["deadbug"], sets = 2, target = SetTarget(reps = 10), restSeconds = 30),
                )),
            ),
        )
        val aId = workouts.save(a)
        val b = WorkoutTemplate(
            name = t("Strength B + splits", "כוח B + שפגט"), description = t("Hinge and single leg; the side split flow fills the rests.", "כפיפת אגן ורגל אחת; הרצף לשפגט ממלא את המנוחות."),
            restFiller = RestFiller(splitId, leftoversAtEnd = true),
            blocks = listOf(
                WorkoutBlock(title = t("Warm-up", "חימום"), items = listOf(WorkoutItem(ids["warmup"], sets = 1, target = SetTarget(), restSeconds = 0))),
                WorkoutBlock(title = t("Main", "עיקרי"), items = listOf(
                    WorkoutItem(ids["rdl"], sets = 4, target = SetTarget(reps = 8, weightKg = 60.0), restSeconds = 120),
                    WorkoutItem(ids["bss"], sets = 3, target = SetTarget(reps = 8, weightKg = 12.0), restSeconds = 90),
                    WorkoutItem(ids["ohp"], sets = 3, target = SetTarget(reps = 6, weightKg = 35.0), restSeconds = 90),
                )),
            ),
        )
        val bId = workouts.save(b)
        val all = listOf(aId, bId, splitId).associateWith { workouts.get(it)!! }
        return Workouts(aId, bId, splitId, all)
    }

    /** Six weeks of A and B, the weights going up. */
    private suspend fun history(w: Workouts, ids: Ids) {
        val library = exercises.observeAll().first().associateBy { it.id }
        val rnd = Random(7)
        listOf(1, 8, 15, 22, 29, 36).forEachIndexed { i, d -> session(w.templates.getValue(w.a), library, w.templates, d, 18, i, rnd) }
        listOf(5, 12, 19, 26, 33, 40).forEachIndexed { i, d -> session(w.templates.getValue(w.b), library, w.templates, d, 7, i, rnd) }
    }

    private suspend fun session(template: WorkoutTemplate, library: Map<Long, Exercise>, all: Map<Long, WorkoutTemplate>, daysAgo: Int, hour: Int, weeksBack: Int, rnd: Random) {
        val compiled = WorkoutCompiler.compile(template, library, all)
        val start = at(daysAgo, hour, 5)
        val titles = template.blocks.map { it.title } + listOfNotNull(compiled.extraBlockTitle)
        // Stored as finished from the start, so the app never sees a workout in progress.
        val id = sessions.create(
            WorkoutSession(templateId = template.id, workoutName = template.name, blockTitles = titles, steps = compiled.steps,
                status = SessionStatus.COMPLETED, startedAtMillis = start, endedAtMillis = start, currentIndex = compiled.steps.size),
        )
        var clock = start
        compiled.steps.forEachIndexed { index, step ->
            when (step) {
                is RestStep -> clock += step.durationSeconds * 1000L
                is WorkStep -> {
                    val ex = library.getValue(step.exerciseId)
                    val target = step.target
                    // Two and a half kilos less for every two weeks back; a rep missed now and then.
                    val weight = target.weightKg?.let { (it - (if (it < 30) 2.0 else 2.5) * (weeksBack / 2)).coerceAtLeast(2.0) }
                    val reps = target.reps?.let { if (rnd.nextInt(6) == 0) it - 1 else it }
                    val secs = step.durationSeconds ?: if (ex.general) 6 * 60 else (target.reps ?: 10) * 3 + 8
                    val began = clock
                    clock += secs * 1000L
                    sessions.saveLog(
                        SetLog(id, index, ex.id, ex.name, step.blockIndex, step.set, step.round, step.side,
                            actual = target.copy(reps = reps, weightKg = weight), skipped = false, completedAtMillis = clock, startedAtMillis = began),
                    )
                }
            }
        }
        val notes = if (weeksBack == 0) t("Felt strong. Goblet squat 24 kg finally smooth.", "הרגשתי חזק. סקוואט גביע 24 ק\"ג סוף סוף חלק.") else ""
        sessions.complete(id, clock, notes)
    }

    /** Runs through the app's own FIT import, as if a watch recorded them. Returns the latest intervals run. */
    private suspend fun runs(): Long {
        val schedule = listOf(
            2 to "intervals", 4 to "easy", 6 to "progressive", 9 to "easy", 11 to "intervals", 13 to "easy",
            16 to "progressive", 18 to "easy", 23 to "intervals", 27 to "easy", 30 to "progressive", 34 to "easy", 38 to "intervals",
        )
        var latestIntervals = 0L
        schedule.forEachIndexed { i, (d, kind) ->
            val start = at(d, if (kind == "progressive") 7 else 18, 30) / 1000
            val sim = when (kind) {
                "intervals" -> DemoRuns.simulate(start, 4000L + i, DemoRuns.intervals(6, 180, listOf(272, 270, 270, 268, 267, 266), 120), null, false, i)
                "progressive" -> DemoRuns.simulate(start, 4000L + i, listOf(DemoRuns.Part(55 * 60, 375, 305)), 1000, false, i)
                else -> DemoRuns.simulate(start, 4000L + i, listOf(DemoRuns.Part(42 * 60, 352, 348)), 1000, true, i)
            }
            val outcome = fit.import(sim.bytes)
            val a = activities.get(outcome.activityId)!!
            val title = when (kind) {
                "intervals" -> t("Intervals 6 × 3 min", "אינטרוולים 6 × 3 דק׳")
                "progressive" -> t("Progressive long run", "ריצה ארוכה מתגברת")
                else -> t("Easy run", "ריצה קלה")
            }
            activities.save(a.copy(title = title, rpe = when (kind) { "intervals" -> 8; "progressive" -> 6; else -> 3 }))
            if (kind == "intervals" && latestIntervals == 0L) {
                latestIntervals = outcome.activityId
                lastIntervals = sim
            }
        }
        return latestIntervals
    }

    private var lastIntervals: DemoRuns.Sim? = null

    private suspend fun plans(w: Workouts) {
        val reps = buildList {
            add(PlannedStep(SegmentKind.WARMUP, durationSeconds = 15 * 60, maxHeartRate = 150))
            repeat(6) { i ->
                add(PlannedStep(SegmentKind.WORK, durationSeconds = 180, paceFastSecondsPerKm = 265, paceSlowSecondsPerKm = 275))
                add(PlannedStep(SegmentKind.RECOVERY, durationSeconds = 120))
            }
            add(PlannedStep(SegmentKind.COOLDOWN, durationSeconds = 8 * 60))
        }
        fun day(offset: Int) = today.plusDays(offset.toLong())
        listOf(
            PlannedWorkout(date = day(-5), time = LocalTime.of(7, 0), kind = PlanKind.WORKOUT, workoutId = w.b),
            PlannedWorkout(date = day(-4), kind = PlanKind.ACTIVITY, activityType = ActivityType.RUN, title = t("Easy run", "ריצה קלה"), durationMinutes = 40,
                steps = listOf(PlannedStep(SegmentKind.STEADY, durationSeconds = 40 * 60, maxHeartRate = 150))),
            PlannedWorkout(date = day(-3), time = LocalTime.of(19, 0), kind = PlanKind.WORKOUT, workoutId = w.split),
            PlannedWorkout(date = day(-2), time = LocalTime.of(18, 30), kind = PlanKind.ACTIVITY, activityType = ActivityType.RUN, title = t("Intervals 6 × 3 min", "אינטרוולים 6 × 3 דק׳"),
                notes = t("Reps at 10 km pace; jog the recoveries.", "החזרות בקצב 10 ק\"מ; ריצה קלה בהתאוששות."), steps = reps),
            PlannedWorkout(date = day(-1), time = LocalTime.of(18, 0), kind = PlanKind.WORKOUT, workoutId = w.a),
            PlannedWorkout(date = day(0), time = LocalTime.of(19, 0), kind = PlanKind.WORKOUT, workoutId = w.split),
            PlannedWorkout(date = day(1), kind = PlanKind.ACTIVITY, activityType = ActivityType.RUN, title = t("Easy run", "ריצה קלה"), durationMinutes = 45,
                steps = listOf(PlannedStep(SegmentKind.STEADY, durationSeconds = 45 * 60, maxHeartRate = 150))),
            PlannedWorkout(date = day(2), time = LocalTime.of(7, 0), kind = PlanKind.WORKOUT, workoutId = w.b),
            PlannedWorkout(date = day(4), time = LocalTime.of(7, 30), kind = PlanKind.ACTIVITY, activityType = ActivityType.RUN, title = t("Long run", "ריצה ארוכה"), durationMinutes = 70,
                notes = t("Start easy, finish at marathon pace.", "מתחילים קל, מסיימים בקצב מרתון.")),
        ).forEach { plans.save(it) }
    }

    /** 180 days of made-up all-day data with a slow, believable rhythm. */
    private suspend fun wellnessDays() {
        val rnd = Random(11)
        val days = (0 until 180).map { back ->
            val date = today.minusDays(back.toLong())
            val k = 180 - back
            val wave = sin(k / 9.0)
            val hardDay = back in listOf(1, 2, 3, 9, 10, 23, 24)
            val sleepH = (7.3 + 0.5 * sin(k / 5.0) + rnd.nextDouble(-0.6, 0.5) - if (date.dayOfWeek.value == 5) 0.6 else 0.0).coerceIn(5.4, 8.9)
            val sleepS = (sleepH * 3600).roundToInt()
            val wake = date.atTime(6, 40).atZone(zone).toInstant().toEpochMilli() + rnd.nextLong(-25, 35) * 60_000
            val bed = wake - sleepS * 1000L - rnd.nextLong(10, 30) * 60_000
            val stages = if (back < 30) hypnogram(bed, wake, rnd) else emptyList()
            fun total(l: SleepLevel) = stages.filter { it.level == l }.sumOf { ((it.endMillis - it.startMillis) / 1000).toInt() }
            val hrvBase = 58 + (k / 60.0).roundToInt()
            val hrv = (hrvBase + 6 * wave + rnd.nextDouble(-6.0, 6.0) - if (hardDay) 7 else 0).roundToInt()
            WellnessDay(
                date = date,
                sleepStartMillis = bed, sleepEndMillis = wake, sleepSeconds = sleepS,
                deepSeconds = if (stages.isEmpty()) (sleepS * 0.18).roundToInt() else total(SleepLevel.DEEP),
                lightSeconds = if (stages.isEmpty()) (sleepS * 0.55).roundToInt() else total(SleepLevel.LIGHT),
                remSeconds = if (stages.isEmpty()) (sleepS * 0.22).roundToInt() else total(SleepLevel.REM),
                awakeSeconds = if (stages.isEmpty()) (sleepS * 0.05).roundToInt() else total(SleepLevel.AWAKE),
                sleepScore = (62 + 9 * (sleepH - 6.5) + rnd.nextDouble(-6.0, 6.0)).roundToInt().coerceIn(40, 97),
                stages = stages, stagesConfirmed = stages.isNotEmpty(),
                sleepAvgStress = 14.0 + rnd.nextDouble(0.0, 8.0),
                sleepAvgRespiration = 13.6 + 0.4 * wave + rnd.nextDouble(-0.4, 0.4),
                hrvLastNight = hrv, hrvWeeklyAvg = hrvBase + (4 * wave).roundToInt(), hrvStatus = "BALANCED",
                hrvBaselineLow = hrvBase - 7, hrvBaselineHigh = hrvBase + 9,
                restingHr = (54 - k / 45.0 - 1.5 * wave + rnd.nextDouble(-1.5, 1.5) + if (hardDay) 2 else 0).roundToInt(),
                minHr = 46 + rnd.nextInt(5), maxHr = 150 + rnd.nextInt(30),
                bodyBatteryHigh = (70 + 3 * (sleepH - 6.5) * 4 + rnd.nextInt(-5, 6)).roundToInt().coerceIn(30, 100),
                bodyBatteryLow = 10 + rnd.nextInt(20),
                bodyBatteryAtWake = (66 + 10 * (sleepH - 6.8) + rnd.nextInt(-6, 7) - if (hardDay) 8 else 0).roundToInt().coerceIn(20, 100),
                stressAvg = 26 + rnd.nextInt(-6, 9) + if (date.dayOfWeek.value in 1..4) 4 else 0,
                steps = 6500 + rnd.nextInt(0, 6000) + if (back in listOf(2, 4, 6, 9, 11, 13)) 6000 else 0,
                vo2Max = 48.0 + k / 90.0,
            )
        }
        wellness.insertDays(days.map { WellnessDayEntity(it.date.toString(), json.encodeToString(WellnessDay.serializer(), it)) })
    }

    /** Sleep cycles of about 90 minutes: deep early in the night, more REM towards the morning. */
    private fun hypnogram(bed: Long, wake: Long, rnd: Random): List<SleepStage> {
        val out = mutableListOf<SleepStage>()
        var tm = bed
        fun add(level: SleepLevel, minutes: Int) {
            val end = minOf(wake, tm + minutes * 60_000L)
            if (end > tm) out += SleepStage(tm, end, level)
            tm = end
        }
        add(SleepLevel.AWAKE, 6 + rnd.nextInt(8))
        var cycle = 0
        while (tm < wake) {
            val late = cycle / 4.0
            add(SleepLevel.LIGHT, 28 + rnd.nextInt(15))
            add(SleepLevel.DEEP, ((30 - 26 * late) + rnd.nextInt(8)).roundToInt().coerceAtLeast(3))
            add(SleepLevel.LIGHT, 15 + rnd.nextInt(12))
            add(SleepLevel.REM, ((12 + 22 * late) + rnd.nextInt(8)).roundToInt())
            if (rnd.nextInt(3) == 0) add(SleepLevel.AWAKE, 2 + rnd.nextInt(4))
            cycle++
        }
        return out
    }

    /** A conversation as the coach would have it, with its answer worked out from the demo run's own numbers. */
    private suspend fun coach(intervalsId: Long) {
        coach.saveProfile(
            CoachProfile(
                goals = t("Flat side split by summer; 10 km under 47 minutes.", "שפגט צד מלא עד הקיץ; 10 ק\"מ מתחת ל-47 דקות."),
                limitations = t("Left knee gets cranky on long downhills.", "הברך השמאלית מתלוננת בירידות ארוכות."),
                equipment = t("Kettlebells, barbell, pull-up bar at home.", "קטלבלים, מוט, מוט מתח בבית."),
                schedule = t("4–5 sessions a week, mornings or evenings.", "4–5 אימונים בשבוע, בוקר או ערב."),
                maxHeartRate = 188, restingHeartRate = 50,
            ),
        )
        val sim = lastIntervals ?: return
        val reps = sim.laps.filter { it.intensity == DemoRuns.INTERVAL }
        val recoveries = sim.laps.filter { it.intensity == DemoRuns.RECOVERY }
        fun pace(l: DemoRuns.Lap) = (l.seconds / (l.meters / 1000)).roundToInt()
        fun fmt(s: Int) = "%d:%02d".format(s / 60, s % 60)
        val paces = reps.map(::pace)
        val drop = recoveries.zip(reps).map { (rec, rep) -> rep.endHr - rec.endHr }.average().roundToInt()
        val inRange = paces.count { it in 265..275 }

        val start = at(2, 20, 10)
        val conv = coach.newConversation(start)
        val dayName = today.minusDays(2).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag(if (he) "he" else "en"))
        val question = t("How did $dayName's intervals go compared with the plan?", "איך היו האינטרוולים של $dayName לעומת התוכנית?")
        coach.addUser(conv, question, start)
        coach.addStep(conv, "plan_list", buildJsonObject { put("from", today.minusDays(2).toString()); put("to", today.minusDays(2).toString()) }, false, start + 4000)
        coach.addStep(conv, "run_structure", buildJsonObject { put("activity_id", intervalsId) }, false, start + 7000)
        val answer = if (!he) {
            "${if (inRange == paces.size) "All" else "$inRange of"} your ${paces.size} repeats landed inside the 4:25–4:35 target (${paces.joinToString(", ") { fmt(it) }}), and the last one was the fastest, so there was more in the tank.\n\n" +
                "Heart rate climbed a little from rep to rep (${reps.first().maxHr} → ${reps.last().maxHr} bpm at the peak), normal drift for 6 × 3 minutes, and it fell by about $drop bpm in each 2-minute jog: a good recovery.\n\n" +
                "Next time keep the same paces and add a seventh repeat. Your next easy run: stay under 150 bpm."
        } else {
            "${if (inRange == paces.size) "כל ${paces.size}" else "$inRange מתוך ${paces.size}"} החזרות נכנסו לטווח היעד 4:25–4:35 (${paces.joinToString(", ") { fmt(it) }}), והאחרונה הייתה המהירה ביותר, כלומר נשאר עוד כוח.\n\n" +
                "הדופק עלה מעט מחזרה לחזרה (${reps.first().maxHr} → ${reps.last().maxHr} בשיא), סחיפה רגילה ב-6 × 3 דקות, וירד בכ-$drop פעימות בכל 2 דקות של ריצה קלה: התאוששות טובה.\n\n" +
                "בפעם הבאה: אותם קצבים ועוד חזרה שביעית. הריצה הקלה הבאה: מתחת ל-150 דופק."
        }
        coach.addAssistant(conv, answer, AiModel.GEMINI_FLASH.id, 0.004, start + 15000)
        val note = t("Goal: flat side split by summer.", "מטרה: שפגט צד מלא עד הקיץ.")
        val noteId = coach.addNote(note, start - 86_400_000L * 10)
        coach.touch(conv, question, start + 15000)
        coach.addNote(t("Prefers strength in the morning, runs in the evening.", "מעדיף כוח בבוקר וריצות בערב."), start - 86_400_000L * 5)
        check(noteId > 0)
    }
}
