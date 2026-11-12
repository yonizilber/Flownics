package com.yonizilber.flownics.feature.ai.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.ai.IssueSeverity
import com.yonizilber.flownics.domain.ai.ReviewRating
import com.yonizilber.flownics.domain.ai.WorkoutReview
import com.yonizilber.flownics.domain.model.Muscle
import com.yonizilber.flownics.feature.ai.AiActionButton
import com.yonizilber.flownics.feature.ai.AiAnswerFooter
import com.yonizilber.flownics.feature.ai.AiCall
import com.yonizilber.flownics.feature.ai.AiErrorCard
import com.yonizilber.flownics.feature.ai.AiNotReadyCard
import com.yonizilber.flownics.feature.ai.AiRunningCard
import com.yonizilber.flownics.feature.ai.aiLanguage
import com.yonizilber.flownics.ui.component.MuscleLoadCard

/**
 * The workout's division of work as the app counts it (free, from the muscle tags), and on request
 * an AI review of how it is built.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutReviewScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenMuscle: (Muscle) -> Unit,
    viewModel: WorkoutReviewViewModel = hiltViewModel(),
) {
    val workout by viewModel.workout.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    var goal by rememberSaveable { mutableStateOf("") }
    val res = LocalResources.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val ctx = when (val w = workout) {
            WorkoutLoad.Loading -> {
                Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Scaffold
            }
            WorkoutLoad.NotFound -> {
                Text(stringResource(R.string.ai_workout_gone), modifier = Modifier.padding(innerPadding).padding(16.dp))
                return@Scaffold
            }
            is WorkoutLoad.Loaded -> w.context
        }
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(ctx.template.name, style = MaterialTheme.typography.headlineSmall)
            MuscleLoadCard(
                load = ctx.load,
                title = stringResource(R.string.ai_review_balance_title),
                emptyText = stringResource(R.string.ai_review_balance_none),
                caption = stringResource(R.string.ai_review_balance_caption),
                untouchedTitle = stringResource(R.string.ai_review_untouched),
                onMuscleClick = onOpenMuscle,
            )
            if (!aiReady) {
                AiNotReadyCard(onOpenAiSettings)
                return@Column
            }
            when (val c = call) {
                AiCall.Idle, is AiCall.Failed -> {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text(stringResource(R.string.ai_review_goal)) },
                        placeholder = { Text(stringResource(R.string.ai_review_goal_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (c is AiCall.Failed) {
                        AiErrorCard(c.error, onRetry = { viewModel.review(goal, res.aiLanguage()) }, onOpenSettings = onOpenAiSettings)
                    } else {
                        AiActionButton(
                            stringResource(R.string.ai_review_ask),
                            enabled = true,
                            onClick = { viewModel.review(goal, res.aiLanguage()) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                AiCall.Running -> AiRunningCard(viewModel.model, webSearch = false, onCancel = viewModel::cancel)
                is AiCall.Done -> {
                    ReviewContent(c.value.value)
                    AiAnswerFooter(c.value.model, c.value.costUsd, c.value.sources)
                    TextButton(onClick = { viewModel.review(goal, res.aiLanguage()) }) { Text(stringResource(R.string.ai_ask_again)) }
                }
            }
        }
    }
}

@Composable
private fun ReviewContent(review: WorkoutReview) {
    val (container, onContainer) = when (review.rating) {
        ReviewRating.GOOD -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ReviewRating.NEEDS_WORK -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ReviewRating.OK, null -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            review.rating?.let { Text(stringResource(ratingRes(it)), style = MaterialTheme.typography.titleMedium) }
            Text(review.verdict, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (review.balance.isNotBlank()) {
        Text(stringResource(R.string.ai_review_balance), style = MaterialTheme.typography.titleSmall)
        Text(review.balance, style = MaterialTheme.typography.bodyMedium)
    }
    if (review.issues.isNotEmpty()) {
        Text(stringResource(R.string.ai_review_issues), style = MaterialTheme.typography.titleSmall)
        review.issues.forEach { issue ->
            val (icon, label) = severity(issue.severity)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    icon,
                    contentDescription = stringResource(label),
                    tint = if (issue.severity == IssueSeverity.INFO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(issue.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (review.suggestions.isNotEmpty()) {
        Text(stringResource(R.string.ai_review_suggestions), style = MaterialTheme.typography.titleSmall)
        review.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun ratingRes(rating: ReviewRating) = when (rating) {
    ReviewRating.GOOD -> R.string.ai_rating_good
    ReviewRating.OK -> R.string.ai_rating_ok
    ReviewRating.NEEDS_WORK -> R.string.ai_rating_needs_work
}

// Icon plus a spoken label: severity is never colour alone.
private fun severity(s: IssueSeverity): Pair<ImageVector, Int> = when (s) {
    IssueSeverity.INFO -> Icons.Outlined.Info to R.string.ai_severity_info
    IssueSeverity.WARN -> Icons.Outlined.WarningAmber to R.string.ai_severity_warn
    IssueSeverity.PROBLEM -> Icons.Outlined.ErrorOutline to R.string.ai_severity_problem
}
