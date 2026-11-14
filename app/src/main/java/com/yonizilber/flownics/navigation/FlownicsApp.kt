package com.yonizilber.flownics.navigation

import com.yonizilber.flownics.feature.settings.WorkoutDefaultsScreen
import com.yonizilber.flownics.feature.settings.HeartRateSensorScreen
import com.yonizilber.flownics.feature.garmin.GarminSettingsScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yonizilber.flownics.feature.data.DataScreen
import com.yonizilber.flownics.feature.data.ShareScreen
import com.yonizilber.flownics.feature.ai.activity.AiActivityScreen
import com.yonizilber.flownics.feature.activity.ActivityEditScreen
import com.yonizilber.flownics.feature.coach.CoachMemoryScreen
import com.yonizilber.flownics.feature.coach.CoachScreen
import com.yonizilber.flownics.feature.ai.generate.WorkoutGenerateScreen
import com.yonizilber.flownics.feature.ai.review.WorkoutReviewScreen
import com.yonizilber.flownics.feature.ai.settings.AiSettingsScreen
import com.yonizilber.flownics.feature.ai.variations.ExerciseVariationsScreen
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.feature.disclaimer.DisclaimerScreen
import com.yonizilber.flownics.feature.disclaimer.DisclaimerViewModel
import com.yonizilber.flownics.feature.history.detail.SessionDetailScreen
import com.yonizilber.flownics.feature.journal.JournalScreen
import com.yonizilber.flownics.feature.plan.PlanEditScreen
import com.yonizilber.flownics.feature.settings.CalendarSettingsScreen
import com.yonizilber.flownics.feature.library.detail.ExerciseDetailScreen
import com.yonizilber.flownics.feature.library.edit.ExerciseEditScreen
import com.yonizilber.flownics.feature.library.list.LibraryScreen
import com.yonizilber.flownics.feature.library.muscle.MuscleExercisesScreen
import com.yonizilber.flownics.feature.player.PlayerScreen
import com.yonizilber.flownics.feature.settings.SettingsScreen
import com.yonizilber.flownics.feature.workouts.edit.WorkoutEditScreen
import com.yonizilber.flownics.feature.workouts.list.WorkoutsScreen
import com.yonizilber.flownics.feature.workouts.overview.WorkoutOverviewScreen

@Composable
fun FlownicsApp(
    openPlayerRequest: Int,
    modifier: Modifier = Modifier,
    disclaimerViewModel: DisclaimerViewModel = hiltViewModel(),
) {
    val accepted by disclaimerViewModel.accepted.collectAsStateWithLifecycle()
    if (!accepted) {
        DisclaimerScreen(onAccept = disclaimerViewModel::accept, modifier = modifier)
        return
    }
    MainScaffold(openPlayerRequest = openPlayerRequest, modifier = modifier)
}

@Composable
private fun MainScaffold(
    openPlayerRequest: Int,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // Tapping the workout notification opens the player on top of wherever the user was.
    LaunchedEffect(openPlayerRequest) {
        if (openPlayerRequest > 0) navController.navigate(Destination.Player.route) { launchSingleTop = true }
    }
    val openPlayer = { navController.navigate(Destination.Player.route) { launchSingleTop = true } }
    val openSession = { id: Long -> navController.navigate(Destination.SessionDetail.createRoute(id)) }
    val openAiSettings = { navController.navigate(Destination.AiSettings.route) { launchSingleTop = true } }
    val openWorkout = { id: Long -> navController.navigate(Destination.WorkoutOverview.createRoute(id)) }
    val openExercise = { id: Long -> navController.navigate(Destination.ExerciseDetail.createRoute(id)) }
    val openMuscle = { muscle: Muscle -> navController.navigate(Destination.MuscleExercises.createRoute(muscle.name)) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val onTopLevel = currentDestination == null ||
        TopLevelTab.entries.any { tab -> currentDestination.route == tab.destination.route }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (onTopLevel) NavigationBar {
                TopLevelTab.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.target) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Workouts.route,
            // The bars' room is taken here once; each screen's own Scaffold must not add it again.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(Destination.Workouts.route) {
                WorkoutsScreen(
                    onWorkoutClick = { id -> navController.navigate(Destination.WorkoutOverview.createRoute(id)) },
                    onNewWorkout = { navController.navigate(Destination.WorkoutEdit.createRoute()) },
                    onResumeWorkout = openPlayer,
                    onCreateWithAi = { navController.navigate(Destination.AiWorkoutGenerate.createRoute()) },
                )
            }
            composable(
                Destination.WorkoutOverview.route,
                arguments = listOf(navArgument(Destination.ARG_WORKOUT_ID) { type = NavType.LongType }),
            ) {
                WorkoutOverviewScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Destination.WorkoutEdit.createRoute(id)) },
                    onOpenWorkout = openWorkout,
                    onOpenPlayer = openPlayer,
                    onAiReview = { id -> navController.navigate(Destination.AiWorkoutReview.createRoute(id)) },
                    onAiVariation = { id -> navController.navigate(Destination.AiWorkoutGenerate.createRoute(id)) },
                    onAskAi = { id -> navController.navigate(Destination.Coach.aboutWorkout(id)) },
                    onShare = { id -> navController.navigate(Destination.Share.workout(id)) },
                )
            }
            composable(Destination.Player.route) {
                PlayerScreen(
                    onClose = { navController.popBackStack() },
                    onOpenExercise = { id -> navController.navigate(Destination.ExerciseDetail.createRoute(id)) },
                    // The saved workout replaces the player: back returns to wherever it was started from.
                    onSaved = { id ->
                        navController.navigate(Destination.SessionDetail.createRoute(id)) {
                            popUpTo(Destination.Player.route) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                Destination.WorkoutEdit.route,
                arguments = listOf(navArgument(Destination.ARG_WORKOUT_ID) { type = NavType.LongType }),
            ) {
                WorkoutEditScreen(
                    onClose = { navController.popBackStack() },
                    onSaved = { id ->
                        if (navController.previousBackStackEntry?.destination?.route == Destination.WorkoutOverview.route) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Destination.WorkoutOverview.createRoute(id)) {
                                popUpTo(Destination.WorkoutEdit.route) { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable(Destination.Library.route) {
                LibraryScreen(
                    onExerciseClick = { id -> navController.navigate(Destination.ExerciseDetail.createRoute(id)) },
                    onAddClick = { navController.navigate(Destination.ExerciseEdit.createRoute()) },
                )
            }
            composable(
                Destination.ExerciseDetail.route,
                arguments = listOf(navArgument(Destination.ARG_EXERCISE_ID) { type = NavType.LongType }),
            ) {
                ExerciseDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Destination.ExerciseEdit.createRoute(id)) },
                    onOpenExercise = { id -> navController.navigate(Destination.ExerciseDetail.createRoute(id)) },
                    onOpenSession = openSession,
                    onAskAi = { id -> navController.navigate(Destination.Coach.aboutExercise(id)) },
                    onVariations = { id -> navController.navigate(Destination.AiExerciseVariations.createRoute(id)) },
                    onShare = { id -> navController.navigate(Destination.Share.exercise(id)) },
                    onOpenMuscle = openMuscle,
                )
            }
            composable(
                Destination.ExerciseEdit.route,
                arguments = listOf(
                    navArgument(Destination.ARG_EXERCISE_ID) { type = NavType.LongType },
                    optionalString(Destination.ARG_PREFILL_NAME),
                    optionalString(Destination.ARG_PREFILL_NOTES),
                    optionalString(Destination.ARG_PREFILL_EQUIPMENT),
                    optionalString(Destination.ARG_PREFILL_CATEGORY),
                    navArgument(Destination.ARG_PREFILL_LEVEL) { type = NavType.IntType; defaultValue = 0 },
                    navArgument(Destination.ARG_VARIATION_OF) { type = NavType.LongType; defaultValue = 0L },
                    navArgument(Destination.ARG_VARIATION_ID) { type = NavType.LongType; defaultValue = 0L },
                ),
            ) {
                ExerciseEditScreen(
                    onOpenAiSettings = openAiSettings,
                    onClose = { navController.popBackStack() },
                    onSaved = { id ->
                        // Editing an existing exercise returns to its detail page; a new one opens
                        // its freshly created detail page in place of the editor.
                        if (navController.previousBackStackEntry?.destination?.route == Destination.ExerciseDetail.route) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Destination.ExerciseDetail.createRoute(id)) {
                                popUpTo(Destination.ExerciseEdit.route) { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable(Destination.History.route) {
                JournalScreen(
                    onOpenSession = openSession,
                    onOpenActivity = { id -> navController.navigate(Destination.ActivityEdit.createRoute(id)) },
                    onLogActivity = { navController.navigate(Destination.ActivityEdit.createRoute()) },
                    onPlan = { day -> navController.navigate(Destination.PlanEdit.forDay(day.toEpochDay())) },
                    onOpenPlan = { id -> navController.navigate(Destination.PlanEdit.createRoute(id)) },
                    onAskAi = { navController.navigate(Destination.Coach.aboutHistory()) },
                    onOpenMuscle = openMuscle,
                    onOpenGarmin = { navController.navigate(Destination.GarminSettings.route) },
                    onOpenWellness = { navController.navigate(Destination.Wellness.route) },
                )
            }
            composable(Destination.WorkoutDefaults.route) {
                WorkoutDefaultsScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.HeartRateSensor.route) {
                HeartRateSensorScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.GarminSettings.route) {
                GarminSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenExplorer = { navController.navigate(Destination.GarminExplorer.route) },
                    onOpenWellness = { navController.navigate(Destination.Wellness.route) },
                )
            }
            composable(Destination.GarminExplorer.route) {
                com.yonizilber.flownics.feature.wellness.GarminExplorerScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.Wellness.route) {
                com.yonizilber.flownics.feature.wellness.WellnessScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGarmin = { navController.navigate(Destination.GarminSettings.route) },
                )
            }
            composable(
                Destination.PlanEdit.route,
                arguments = listOf(
                    navArgument(Destination.ARG_PLAN_ID) { type = NavType.LongType },
                    navArgument(Destination.ARG_EPOCH_DAY) { type = NavType.LongType; defaultValue = 0L },
                ),
            ) {
                PlanEditScreen(
                    onClose = { navController.popBackStack() },
                    onOpenWorkout = openWorkout,
                    onOpenSession = openSession,
                    onOpenActivity = { id -> navController.navigate(Destination.ActivityEdit.createRoute(id)) },
                )
            }
            composable(Destination.CalendarSettings.route) {
                CalendarSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Destination.SessionDetail.route,
                arguments = listOf(navArgument(Destination.ARG_SESSION_ID) { type = NavType.LongType }),
            ) {
                SessionDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenExercise = { id -> navController.navigate(Destination.ExerciseDetail.createRoute(id)) },
                    onOpenWorkout = openWorkout,
                    onAskCoach = { id -> navController.navigate(Destination.Coach.aboutSession(id)) },
                    onOpenActivity = { id -> navController.navigate(Destination.ActivityEdit.createRoute(id)) },
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    onOpenAi = openAiSettings,
                    onAskAi = {
                        navController.navigate(Destination.Coach.TAB) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenData = { navController.navigate(Destination.Data.route) },
                    onOpenCalendar = { navController.navigate(Destination.CalendarSettings.route) },
                    onOpenGarmin = { navController.navigate(Destination.GarminSettings.route) },
                    onOpenSensor = { navController.navigate(Destination.HeartRateSensor.route) },
                    onOpenDefaults = { navController.navigate(Destination.WorkoutDefaults.route) },
                )
            }
            composable(Destination.Data.route) {
                DataScreen(onBack = { navController.popBackStack() }, onOpenShare = { navController.navigate(Destination.Share.choose()) })
            }
            composable(
                Destination.Share.route,
                arguments = listOf(
                    navArgument(Destination.ARG_EXERCISE_ID) { type = NavType.LongType; defaultValue = 0L },
                    navArgument(Destination.ARG_WORKOUT_ID) { type = NavType.LongType; defaultValue = 0L },
                ),
            ) {
                ShareScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.AiSettings.route) {
                AiSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenActivity = { navController.navigate(Destination.AiActivity.route) },
                )
            }
            composable(Destination.AiActivity.route) {
                AiActivityScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Destination.AiExerciseVariations.route,
                arguments = listOf(navArgument(Destination.ARG_EXERCISE_ID) { type = NavType.LongType }),
            ) {
                ExerciseVariationsScreen(
                    onBack = { navController.popBackStack() },
                    onAddToLibrary = { saved ->
                        val v = saved.variation
                        navController.navigate(
                            Destination.ExerciseEdit.fromVariation(
                                name = v.name,
                                notes = listOf(v.difference, v.why).filter { it.isNotBlank() }.joinToString("\n\n"),
                                equipment = v.equipment,
                                category = v.category?.name,
                                level = v.level,
                                variationOf = saved.exerciseId,
                                variationId = saved.id,
                            ),
                        )
                    },
                    onOpenExercise = openExercise,
                    onOpenAiSettings = openAiSettings,
                )
            }
            composable(
                Destination.AiWorkoutReview.route,
                arguments = listOf(navArgument(Destination.ARG_WORKOUT_ID) { type = NavType.LongType }),
            ) {
                WorkoutReviewScreen(onBack = { navController.popBackStack() }, onOpenAiSettings = openAiSettings, onOpenMuscle = openMuscle)
            }
            composable(
                Destination.MuscleExercises.route,
                arguments = listOf(navArgument(Destination.ARG_MUSCLE) { type = NavType.StringType }),
            ) {
                MuscleExercisesScreen(onBack = { navController.popBackStack() }, onOpenExercise = openExercise)
            }
            composable(
                Destination.AiWorkoutGenerate.route,
                arguments = listOf(navArgument(Destination.ARG_WORKOUT_ID) { type = NavType.LongType }),
            ) {
                WorkoutGenerateScreen(
                    onBack = { navController.popBackStack() },
                    // The new workout replaces the generator; back returns to where it was opened from.
                    onSaved = { id ->
                        navController.navigate(Destination.WorkoutOverview.createRoute(id)) {
                            popUpTo(Destination.AiWorkoutGenerate.route) { inclusive = true }
                        }
                    },
                    onOpenAiSettings = openAiSettings,
                )
            }
            composable(
                Destination.Coach.route,
                arguments = listOf(
                    navArgument(Destination.ARG_EXERCISE_ID) { type = NavType.LongType; defaultValue = 0L },
                    navArgument(Destination.ARG_WORKOUT_ID) { type = NavType.LongType; defaultValue = 0L },
                    navArgument(Destination.ARG_SESSION_ID) { type = NavType.LongType; defaultValue = 0L },
                    navArgument(Destination.ARG_HISTORY) { type = NavType.BoolType; defaultValue = false },
                ),
            ) {
                CoachScreen(
                    onOpenAiSettings = openAiSettings,
                    onOpenMemory = { navController.navigate(Destination.CoachMemory.route) },
                    onOpenWorkout = openWorkout,
                    onOpenExercise = openExercise,
                    onOpenActivity = { id -> navController.navigate(Destination.ActivityEdit.createRoute(id)) },
                )
            }
            composable(
                Destination.ActivityEdit.route,
                arguments = listOf(navArgument(Destination.ARG_ACTIVITY_ID) { type = NavType.LongType }),
            ) {
                ActivityEditScreen(onClose = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
            composable(Destination.CoachMemory.route) {
                CoachMemoryScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun optionalString(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}
