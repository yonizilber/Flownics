package com.yonizilber.flownics.feature.library.tag

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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.yonizilber.flownics.domain.model.Tags
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.navigation.Destination
import com.yonizilber.flownics.ui.component.labelRes
import com.yonizilber.flownics.ui.component.levelText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One tag: what it is for, and every exercise carrying it with what each does for it. */
data class TagPage(val name: String, val description: String, val exercises: List<Pair<Exercise, String>>)

@HiltViewModel
class TagExercisesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ExerciseRepository,
) : ViewModel() {

    // The name follows a rename, so the page stays on the same tag.
    private val name = MutableStateFlow(checkNotNull(savedStateHandle.get<String>(Destination.ARG_TAG)))

    private val order = compareBy<Exercise> { it.level ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() }

    val page: StateFlow<TagPage?> = combine(name, repository.observeAll(), repository.observeTagInfos()) { tag, all, infos ->
        val key = Tags.key(tag)
        val carrying = all.mapNotNull { e -> e.tags.firstOrNull { Tags.key(it.name) == key }?.let { e to it.note } }
            .sortedWith(compareBy(order) { it.first })
        TagPage(
            name = carrying.firstOrNull()?.first?.tags?.first { Tags.key(it.name) == key }?.name ?: tag,
            description = infos.firstOrNull { Tags.key(it.name) == key }?.description.orEmpty(),
            exercises = carrying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setDescription(text: String) = viewModelScope.launch { repository.setTagDescription(page.value?.name ?: name.value, text) }

    fun rename(to: String) = viewModelScope.launch {
        if (to.isBlank()) return@launch
        repository.renameTag(page.value?.name ?: name.value, to.trim())
        name.value = to.trim()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagExercisesScreen(
    onBack: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    viewModel: TagExercisesViewModel = hiltViewModel(),
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    var editingDescription by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("# " + (page?.name ?: "")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.tag_rename))
                    }
                },
            )
        },
    ) { innerPadding ->
        val p = page ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "about") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.tag_about), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        p.description.ifBlank { stringResource(R.string.tag_about_empty) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (p.description.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { editingDescription = true }) { Text(stringResource(R.string.tag_about_edit)) }
                }
            }
            item(key = "count") {
                Text(
                    stringResource(R.string.tag_exercises, p.exercises.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (p.exercises.isEmpty()) item { Text(stringResource(R.string.tag_none), style = MaterialTheme.typography.bodyLarge) }
            items(p.exercises, key = { it.first.id }) { (exercise, note) ->
                ElevatedCard(onClick = { onOpenExercise(exercise.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodyMedium)
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
        if (editingDescription) {
            TextDialog(
                title = stringResource(R.string.tag_about),
                initial = p.description,
                singleLine = false,
                onDismiss = { editingDescription = false },
                onSave = { viewModel.setDescription(it); editingDescription = false },
            )
        }
        if (renaming) {
            TextDialog(
                title = stringResource(R.string.tag_rename),
                initial = p.name,
                singleLine = true,
                hint = stringResource(R.string.tag_rename_hint),
                onDismiss = { renaming = false },
                onSave = { viewModel.rename(it); renaming = false },
            )
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, singleLine: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit, hint: String? = null) {
    var text by rememberSaveable { mutableStateOf(initial) }
    LaunchedEffect(initial) { if (text.isEmpty()) text = initial }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = singleLine, minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.tag_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
