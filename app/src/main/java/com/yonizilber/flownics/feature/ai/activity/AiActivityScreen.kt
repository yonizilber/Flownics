package com.yonizilber.flownics.feature.ai.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.ai.AiActivity
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.domain.ai.AiOutcome
import com.yonizilber.flownics.feature.ai.formatUsd
import com.yonizilber.flownics.feature.ai.labelRes
import com.yonizilber.flownics.feature.ai.settings.featureLabelRes
import com.yonizilber.flownics.ui.component.DateTexts
import com.yonizilber.flownics.ui.component.rememberDateTexts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.DecimalFormat
import javax.inject.Inject

@HiltViewModel
class AiActivityViewModel @Inject constructor(ai: AiService) : ViewModel() {
    val activity: StateFlow<List<AiActivity>?> = ai.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Every AI call, newest first: what it was for, what it was about, how it ended and what it cost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiActivityScreen(onBack: () -> Unit, viewModel: AiActivityViewModel = hiltViewModel()) {
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val dates = rememberDateTexts()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val calls = activity.orEmpty()
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.ai_activity_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (activity != null && calls.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.ai_activity_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            calls.forEachIndexed { index, call ->
                val previous = calls.getOrNull(index - 1)
                if (previous == null || dates.date(previous.atMillis) != dates.date(call.atMillis)) {
                    val day = calls.filter { dates.date(it.atMillis) == dates.date(call.atMillis) }
                    item(key = "day-${call.atMillis}") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                dates.fullDate(call.atMillis),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(formatUsd(day.sumOf { it.costUsd }), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                item(key = "call-${call.atMillis}-$index") { CallCard(call, dates) }
            }
        }
    }
}

@Composable
private fun CallCard(call: AiActivity, dates: DateTexts) {
    val seconds = DecimalFormat("0.#").format(call.durationMs / 1000.0)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${dates.time(call.atMillis)} · ${stringResource(featureLabelRes(call.feature))}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (call.costKnown) formatUsd(call.costUsd) else stringResource(R.string.ai_activity_cost_unknown),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (call.subject.isNotBlank()) {
                Text(call.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                stringResource(
                    R.string.ai_activity_details,
                    call.model?.let { stringResource(it.labelRes) } ?: call.modelId,
                    seconds,
                    call.usage.inputTokens,
                    call.usage.outputTokens,
                ) + if (call.usage.webSearches > 0) " · " + stringResource(R.string.ai_activity_searches, call.usage.webSearches) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (call.outcome != AiOutcome.OK) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(outcomeRes(call.outcome)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (call.detail.isNotBlank()) {
                    Text(
                        stringResource(R.string.ai_activity_detail, call.detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun outcomeRes(outcome: AiOutcome) = when (outcome) {
    AiOutcome.OK -> R.string.ai_outcome_ok
    AiOutcome.FAILED -> R.string.ai_outcome_failed
    AiOutcome.TIMED_OUT -> R.string.ai_outcome_timed_out
    AiOutcome.CANCELLED -> R.string.ai_outcome_cancelled
}
