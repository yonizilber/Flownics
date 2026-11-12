package com.yonizilber.flownics.feature.library.muscle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.navigation.Destination
import com.yonizilber.flownics.ui.component.BodyMap
import com.yonizilber.flownics.ui.component.bodyMapDescription
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.levelText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Exercises that work one muscle, split by whether it is a main or a helping muscle. */
data class MuscleExercises(val primary: List<Exercise>, val secondary: List<Exercise>)

@HiltViewModel
class MuscleExercisesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: ExerciseRepository,
) : ViewModel() {

    val muscle: Muscle = Muscle.valueOf(checkNotNull(savedStateHandle.get<String>(Destination.ARG_MUSCLE)))

    // Easiest first, so each list reads as a progression; unrated ones last.
    private val order = compareBy<Exercise> { it.level ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() }

    val exercises: StateFlow<MuscleExercises?> = repository.observeAll().map { all ->
        MuscleExercises(
            primary = all.filter { muscle in it.primaryMuscles }.sortedWith(order),
            secondary = all.filter { muscle in it.secondaryMuscles && muscle !in it.primaryMuscles }.sortedWith(order),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuscleExercisesScreen(
    onBack: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    viewModel: MuscleExercisesViewModel = hiltViewModel(),
) {
    val exercises by viewModel.exercises.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(viewModel.muscle.labelRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val lists = exercises ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "map") {
                BodyMap(
                    intensity = mapOf(viewModel.muscle to 1f),
                    description = bodyMapDescription(listOf(viewModel.muscle)),
                    maxWidth = 200.dp,
                )
            }
            if (lists.primary.isEmpty() && lists.secondary.isEmpty()) {
                item { Text(stringResource(R.string.muscle_exercises_none), style = MaterialTheme.typography.bodyLarge) }
            }
            listOf(R.string.muscle_exercises_primary to lists.primary, R.string.muscle_exercises_secondary to lists.secondary)
                .filter { it.second.isNotEmpty() }
                .forEach { (title, list) ->
                    item(key = "title-$title") {
                        Text(
                            "${stringResource(title)} · ${list.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(list, key = { "$title-${it.id}" }) { exercise ->
                        ElevatedCard(onClick = { onOpenExercise(exercise.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (exercise.categories.map { stringResource(it.labelRes) } + listOfNotNull(exercise.level?.let { levelText(it) }))
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
        }
    }
}
