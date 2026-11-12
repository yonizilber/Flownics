package com.yonizilber.flownics.feature.library.list

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.domain.library.ExerciseFilter
import com.yonizilber.flownics.domain.library.applyFilter
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.domain.model.ExerciseCategory
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.domain.repository.ExerciseRepository
import com.yonizilber.flownics.ui.component.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.yonizilber.flownics.domain.library.ExerciseFamily
import com.yonizilber.flownics.domain.library.Families
import javax.inject.Inject

data class LibraryUiState(
    val loading: Boolean = true,
    val totalCount: Int = 0,
    val exercises: List<Exercise> = emptyList(),
    /** Without a filter, the library is shown by family (a default and its styles); null while filtering. */
    val families: List<ExerciseFamily>? = null,
    /** For a style shown on its own (while filtering): its family's default. */
    val baseNames: Map<Long, String> = emptyMap(),
    val filter: ExerciseFilter = ExerciseFilter(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: ExerciseRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val filter = MutableStateFlow(ExerciseFilter())

    val uiState: StateFlow<LibraryUiState> =
        combine(repository.observeAll(), filter) { all, currentFilter ->
            val filtering = currentFilter.query.isNotBlank() || currentFilter.categories.isNotEmpty() || currentFilter.muscle != null
            val byId = all.associateBy { it.id }
            LibraryUiState(
                loading = false,
                totalCount = all.size,
                exercises = all.applyFilter(currentFilter) { context.getString(it.labelRes) },
                families = if (filtering) null else Families.group(all),
                baseNames = all.mapNotNull { e -> Families.baseOf(e, byId).takeIf { it.id != e.id }?.let { e.id to it.name } }.toMap(),
                filter = currentFilter,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onQueryChange(query: String) = filter.update { it.copy(query = query) }

    fun onToggleCategory(category: ExerciseCategory) = filter.update {
        it.copy(categories = if (category in it.categories) it.categories - category else it.categories + category)
    }

    fun onMuscleSelected(muscle: Muscle?) = filter.update { it.copy(muscle = muscle) }

    fun onClearFilters() = filter.update { ExerciseFilter() }
}
