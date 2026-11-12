package com.yonizilber.flownics.feature.library.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.data.remote.youtube.YouTubeLinks
import com.yonizilber.flownics.data.remote.youtube.YouTubeVerifier
import com.yonizilber.flownics.data.remote.youtube.YouTubeVideo
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiResult
import com.yonizilber.flownics.domain.ai.FitnessAnswers
import com.yonizilber.flownics.domain.ai.FitnessPrompts
import com.yonizilber.flownics.domain.model.Exercise
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.feature.ai.AiAnswerFooter
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.feature.ai.AiErrorCard
import com.yonizilber.flownics.feature.ai.AiNotReadyCard
import com.yonizilber.flownics.feature.ai.AiRunningCard
import com.yonizilber.flownics.feature.ai.aiLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class FoundVideo(val video: YouTubeVideo, val why: String)

/** Videos YouTube confirmed, plus how many links the AI gave that it didn't. */
data class VideoSearch(val videos: List<FoundVideo>, val unverified: Int)

@HiltViewModel
class VideoFinderViewModel @Inject constructor(
    private val ai: AiService,
    private val youtube: YouTubeVerifier,
) : ViewModel() {

    val aiReady: StateFlow<Boolean> = ai.settings.map { it.ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ai.settings.value.ready)
    val model: AiModel get() = ai.settings.value.model

    private val _call = MutableStateFlow<AiCall<AiResult<VideoSearch>>>(AiCall.Idle)
    val call: StateFlow<AiCall<AiResult<VideoSearch>>> = _call.asStateFlow()
    private var job: Job? = null

    fun find(exercise: Exercise, language: String) {
        job?.cancel()
        _call.value = AiCall.Running
        job = viewModelScope.launch {
            _call.value = try {
                val answer = ai.ask(FitnessPrompts.exerciseVideos(exercise, language), FitnessAnswers::videos)
                // Links the model wrote, then YouTube pages the search itself returned.
                val why = linkedMapOf<String, String>()
                answer.value.forEach { (url, reason) -> YouTubeLinks.videoId(url)?.let { why.putIfAbsent(it, reason) } }
                answer.sources.forEach { source -> YouTubeLinks.videoId(source.url)?.let { why.putIfAbsent(it, "") } }
                val candidates = why.keys.take(MAX_CANDIDATES)
                val found = candidates.map { id -> async { youtube.lookUp(id) } }.awaitAll()
                    .filterNotNull()
                    .map { FoundVideo(it, why[it.id].orEmpty()) }
                val unverified = answer.value.size - found.count { f -> answer.value.any { YouTubeLinks.videoId(it.first) == f.video.id } }
                AiCall.Done(AiResult(VideoSearch(found, unverified.coerceAtLeast(0)), answer.sources, answer.model, answer.costUsd))
            } catch (e: AiException) {
                AiCall.Failed(e)
            } catch (e: IOException) {
                AiCall.Failed(AiException.Network(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _call.value = AiCall.Idle
    }

    private companion object {
        const val MAX_CANDIDATES = 8
    }
}

/**
 * Two ways to a demonstration video: YouTube's own search (free, no AI), or the AI searching the
 * web, with every link it returns checked against YouTube before it is offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoFinderSheet(
    exercise: Exercise,
    alreadyAdded: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: VideoFinderViewModel = hiltViewModel(),
) {
    val call by viewModel.call.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val res = LocalResources.current
    val query = stringResource(R.string.video_search_query, exercise.name)
    val addedIds = alreadyAdded.mapNotNull { YouTubeLinks.videoId(it) }.toSet()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.video_title, exercise.name), style = MaterialTheme.typography.titleLarge)

            OutlinedButton(onClick = { runCatching { uriHandler.openUri(YouTubeLinks.searchUrl(query)) } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.video_search_youtube), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                stringResource(R.string.video_search_youtube_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            if (!aiReady) {
                AiNotReadyCard(onOpenAiSettings)
                return@Column
            }
            when (val c = call) {
                AiCall.Idle -> {
                    Text(stringResource(R.string.video_ai_intro), style = MaterialTheme.typography.bodyMedium)
                    AiActionButton(
                        stringResource(R.string.video_ai_find),
                        enabled = true,
                        onClick = { viewModel.find(exercise, res.aiLanguage()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AiCall.Running -> AiRunningCard(viewModel.model, webSearch = true, onCancel = viewModel::cancel)
                is AiCall.Failed -> AiErrorCard(c.error, onRetry = { viewModel.find(exercise, res.aiLanguage()) }, onOpenSettings = onOpenAiSettings)
                is AiCall.Done -> {
                    val search = c.value.value
                    if (search.videos.isEmpty()) Text(stringResource(R.string.video_ai_none), style = MaterialTheme.typography.bodyMedium)
                    search.videos.forEach { found ->
                        VideoCard(
                            found = found,
                            added = found.video.id in addedIds,
                            onOpen = { runCatching { uriHandler.openUri(found.video.url) } },
                            onAdd = { onAdd(found.video.url) },
                        )
                    }
                    if (search.unverified > 0) {
                        Text(
                            pluralStringResource(R.plurals.video_ai_unverified, search.unverified, search.unverified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(R.string.video_ai_check),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The sources are the search pages; the videos above are what was checked.
                    AiAnswerFooter(c.value.model, c.value.costUsd, sources = emptyList())
                }
            }
        }
    }
}

@Composable
private fun VideoCard(found: FoundVideo, added: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = found.video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 112.dp, height = 63.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpen),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Title and channel come from YouTube, not from the AI.
                Text(found.video.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(found.video.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (found.why.isNotBlank()) Text(found.why, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpen, label = { Text(stringResource(R.string.video_watch)) })
                    AssistChip(
                        onClick = onAdd,
                        enabled = !added,
                        label = { Text(stringResource(if (added) R.string.video_added else R.string.video_add)) },
                        leadingIcon = { Icon(if (added) Icons.Outlined.Check else Icons.Outlined.Add, contentDescription = null) },
                    )
                }
            }
        }
    }
}
