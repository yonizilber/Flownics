package com.yonizilber.flownics.feature.ai

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.ai.AiService
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProgress
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.domain.ai.AiSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.inject.Inject

/** One AI request's progress, as every AI screen shows it. */
sealed interface AiCall<out T> {
    data object Idle : AiCall<Nothing>
    data object Running : AiCall<Nothing>
    data class Failed(val error: AiException) : AiCall<Nothing>
    data class Done<T>(val value: T) : AiCall<T>
}

/** The language the model should answer in: the app's current one, in English ("Hebrew"). */
fun Resources.aiLanguage(): String = configuration.locales[0].getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }

@get:StringRes
val AiProvider.labelRes: Int
    get() = when (this) {
        AiProvider.GEMINI -> R.string.ai_provider_gemini
        AiProvider.CLAUDE -> R.string.ai_provider_claude
    }

@get:StringRes
val AiModel.labelRes: Int
    get() = when (this) {
        AiModel.GEMINI_FLASH_LITE -> R.string.ai_model_gemini_flash_lite
        AiModel.GEMINI_FLASH -> R.string.ai_model_gemini_flash
        AiModel.GEMINI_PRO -> R.string.ai_model_gemini_pro
        AiModel.CLAUDE_HAIKU -> R.string.ai_model_claude_haiku
        AiModel.CLAUDE_SONNET -> R.string.ai_model_claude_sonnet
        AiModel.CLAUDE_OPUS -> R.string.ai_model_claude_opus
    }

/** Below a cent, "< $0.01": an estimate shouldn't look more precise than it is. */
fun formatUsd(usd: Double): String = when {
    usd <= 0.0 -> "$0"
    usd < 0.01 -> "< $0.01"
    usd < 10 -> String.format(Locale.ROOT, "$%.2f", usd)
    else -> String.format(Locale.ROOT, "$%.0f", usd)
}

fun aiErrorText(res: Resources, e: AiException): String = when (e) {
    is AiException.NoKey -> res.getString(R.string.ai_error_no_key, res.getString(e.provider.labelRes))
    is AiException.InvalidKey -> res.getString(R.string.ai_error_invalid_key, res.getString(e.provider.labelRes))
    is AiException.NoAccess -> res.getString(R.string.ai_error_no_access, e.detail)
    is AiException.RateLimited -> res.getString(if (e.daily) R.string.ai_error_daily_quota else R.string.ai_error_rate_limited)
    is AiException.Overloaded -> res.getString(R.string.ai_error_overloaded, res.getString(e.provider.labelRes))
    is AiException.Network -> res.getString(R.string.ai_error_network)
    is AiException.Timeout -> res.getString(if (e.stalled) R.string.ai_error_stalled else R.string.ai_error_timeout)
    is AiException.Refused -> res.getString(R.string.ai_error_refused)
    is AiException.Truncated -> res.getString(R.string.ai_error_truncated)
    is AiException.BadResponse -> res.getString(R.string.ai_error_bad_response, e.detail)
    is AiException.Service -> res.getString(R.string.ai_error_service, e.status, e.detail)
    is AiException.CapReached -> res.getString(R.string.ai_error_cap, formatUsd(e.spentUsd), formatUsd(e.capUsd))
}

/** Shown in place of an AI action until a key is set: says what is needed and where. */
@Composable
fun AiNotReadyCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_not_ready_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_not_ready_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.ai_open_settings)) }
        }
    }
}

/** Hands the running call's live progress to [AiRunningCard], wherever that card is shown. */
@HiltViewModel
class AiProgressViewModel @Inject constructor(ai: AiService) : ViewModel() {
    val progress: StateFlow<AiProgress?> = ai.progress
}

/**
 * While a call runs: who is being asked, a running clock, what the model is doing right now
 * (thinking, searching, writing: so a slow answer never looks frozen), and a way out. Cancelling
 * stops waiting; the provider may still bill work it already did.
 */
@Composable
fun AiRunningCard(
    model: AiModel,
    webSearch: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    progressViewModel: AiProgressViewModel = hiltViewModel(),
) {
    val progress by progressViewModel.progress.collectAsStateWithLifecycle()
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_running, stringResource(model.labelRes)), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ai_running_stage, seconds, stageText(progress)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(if (webSearch) R.string.ai_running_search else R.string.ai_running_plain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            // A free-text answer shows as it is written; only its end, so the card stays small.
            (progress as? AiProgress.Writing)?.preview?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    if (text.length > PREVIEW_CHARS) "…" + text.takeLast(PREVIEW_CHARS) else text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val PREVIEW_CHARS = 600

@Composable
private fun stageText(progress: AiProgress?): String = when (progress) {
    null, AiProgress.Connecting -> stringResource(R.string.ai_stage_connecting)
    is AiProgress.Retrying -> stringResource(R.string.ai_stage_retrying, progress.attempt)
    AiProgress.Thinking -> stringResource(R.string.ai_stage_thinking)
    is AiProgress.Searching -> stringResource(R.string.ai_stage_searching, progress.searches)
    is AiProgress.Writing -> stringResource(R.string.ai_stage_writing, progress.chars)
}

@Composable
fun AiErrorCard(
    error: AiException,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingsFix = error is AiException.NoKey || error is AiException.InvalidKey ||
        error is AiException.CapReached || error is AiException.NoAccess
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    aiErrorText(LocalResources.current, error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settingsFix) {
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.ai_open_settings)) }
                } else {
                    Button(onClick = onRetry) { Text(stringResource(R.string.ai_retry)) }
                    // A slow or busy service: a faster model is the other way out.
                    if (error is AiException.Timeout || error is AiException.Overloaded) {
                        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.ai_open_settings)) }
                    }
                }
            }
        }
    }
}

/** Under every AI answer: which model wrote it, roughly what it cost, that it can be wrong, and its sources. */
@Composable
fun AiAnswerFooter(model: AiModel, costUsd: Double, sources: List<AiSource>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (sources.isNotEmpty()) {
            Text(stringResource(R.string.ai_sources), style = MaterialTheme.typography.labelLarge)
            SourceList(sources)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.ai_footer, stringResource(model.labelRes), formatUsd(costUsd)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SourceList(sources: List<AiSource>) {
    val uriHandler = LocalUriHandler.current
    Column {
        sources.forEach { source ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri(source.url) } }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    source.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The button that starts a paid call: says what it does and that it uses the user's key. */
@Composable
fun AiActionButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val content: @Composable () -> Unit = {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}
