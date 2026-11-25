package com.yonizilber.flownics.feature.ai.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.local.prefs.AiSettings
import com.yonizilber.flownics.domain.ai.AiException
import com.yonizilber.flownics.domain.ai.AiFeature
import com.yonizilber.flownics.domain.ai.AiModel
import com.yonizilber.flownics.domain.ai.AiProvider
import com.yonizilber.flownics.feature.ai.aiErrorText
import com.yonizilber.flownics.feature.ai.formatUsd
import com.yonizilber.flownics.feature.ai.labelRes
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit, onOpenActivity: () -> Unit, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spent by viewModel.monthSpend.collectAsStateWithLifecycle()
    val byFeature by viewModel.spendByFeature.collectAsStateWithLifecycle()
    var keyDialogFor by rememberSaveable { mutableStateOf<AiProvider?>(null) }
    val provider = settings.provider

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.ai_settings_intro), style = MaterialTheme.typography.bodyMedium)

            Section(stringResource(R.string.ai_settings_provider)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AiProvider.entries.forEachIndexed { index, p ->
                        SegmentedButton(
                            selected = p == provider,
                            onClick = { viewModel.setProvider(p) },
                            shape = SegmentedButtonDefaults.itemShape(index, AiProvider.entries.size),
                        ) { Text(stringResource(p.labelRes)) }
                    }
                }
                Text(
                    stringResource(if (provider == AiProvider.GEMINI) R.string.ai_terms_gemini else R.string.ai_terms_claude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            KeySection(settings, provider, onAdd = { keyDialogFor = provider }, onRemove = { viewModel.removeKey(provider) })

            Section(stringResource(R.string.ai_settings_model)) {
                AiModel.forProvider(provider).forEach { model ->
                    ModelRow(model, selected = settings.models[provider] == model, onSelect = { viewModel.setModel(model) })
                }
            }

            SpendSection(settings, spent, byFeature.map { it.feature to it.costUsd }, viewModel::setMonthlyCap, onOpenActivity)

            Section(stringResource(R.string.ai_settings_privacy)) {
                Text(stringResource(R.string.ai_settings_privacy_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    keyDialogFor?.let { p ->
        KeyDialog(
            provider = p,
            viewModel = viewModel,
            onDismiss = {
                keyDialogFor = null
                viewModel.resetKeyTest()
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun KeySection(settings: AiSettings, provider: AiProvider, onAdd: () -> Unit, onRemove: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val hint = settings.keyHints[provider]
    Section(stringResource(R.string.ai_settings_key, stringResource(provider.labelRes))) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    hint != null -> Text(stringResource(R.string.ai_key_saved, hint), style = MaterialTheme.typography.bodyLarge)
                    provider in settings.unreadableKeys -> Text(
                        stringResource(R.string.ai_key_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(stringResource(R.string.ai_key_none), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    stringResource(R.string.ai_key_storage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAdd) { Text(stringResource(if (hint == null) R.string.ai_key_add else R.string.ai_key_replace)) }
                    if (hint != null) OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.ai_key_remove)) }
                }
                TextButton(onClick = { runCatching { uriHandler.openUri(keyPageUrl(provider)) } }) {
                    Text(stringResource(R.string.ai_key_get, stringResource(provider.labelRes)))
                }
                Text(
                    stringResource(R.string.ai_key_limit_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(model: AiModel, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(model.labelRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    R.string.ai_model_price,
                    String.format(Locale.ROOT, "%.2f", model.inputUsdPerMillion),
                    String.format(Locale.ROOT, "%.2f", model.outputUsdPerMillion),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            modelNoteRes(model)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun modelNoteRes(model: AiModel): Int? = when (model) {
    AiModel.GEMINI_FLASH -> R.string.ai_model_note_recommended_gemini
    AiModel.GEMINI_PRO -> R.string.ai_model_note_preview
    AiModel.CLAUDE_OPUS -> R.string.ai_model_note_recommended_claude
    AiModel.CLAUDE_HAIKU -> R.string.ai_model_note_cheap
    else -> null
}

@Composable
private fun SpendSection(
    settings: AiSettings,
    spent: Double,
    byFeature: List<Pair<String, Double>>,
    onCap: (Double?) -> Unit,
    onOpenActivity: () -> Unit,
) {
    val cap = settings.monthlyCapUsd
    var capText by rememberSaveable(cap) { mutableStateOf(cap?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "") }
    Section(stringResource(R.string.ai_settings_spend)) {
        Text(
            if (cap != null) stringResource(R.string.ai_spend_of_cap, formatUsd(spent), formatUsd(cap)) else stringResource(R.string.ai_spend_month, formatUsd(spent)),
            style = MaterialTheme.typography.titleLarge,
        )
        byFeature.forEach { (feature, cost) ->
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(featureLabelRes(feature)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(formatUsd(cost), style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedButton(onClick = onOpenActivity) { Text(stringResource(R.string.ai_activity_open)) }
        if (byFeature.isNotEmpty()) HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = cap != null, role = Role.Switch, onValueChange = { on -> onCap(if (on) AiSettings.DEFAULT_CAP_USD else null) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ai_cap_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = cap != null, onCheckedChange = null)
        }
        if (cap != null) {
            OutlinedTextField(
                value = capText,
                onValueChange = { text ->
                    capText = text.filter(Char::isDigit).take(4)
                    capText.toIntOrNull()?.takeIf { it > 0 }?.let { onCap(it.toDouble()) }
                },
                label = { Text(stringResource(R.string.ai_cap_amount)) },
                prefix = { Text("$") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(0.6f),
            )
        }
        Text(stringResource(R.string.ai_spend_estimate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyDialog(provider: AiProvider, viewModel: AiSettingsViewModel, onDismiss: () -> Unit) {
    val test by viewModel.keyTest.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf("") }
    var understood by rememberSaveable { mutableStateOf(false) }
    val providerName = stringResource(provider.labelRes)

    LaunchedEffect(test) { if (test == KeyTest.Saved) onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_key_dialog_title, providerName)) },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.ai_key_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.ai_key_notice, providerName), style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Text(stringResource(R.string.ai_key_understood), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                when (val t = test) {
                    KeyTest.Testing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(stringResource(R.string.ai_key_testing))
                    }
                    is KeyTest.Failed -> {
                        Text(aiErrorText(LocalResources.current, t.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        // A rejected key is never saved; a failure that says nothing about the key may be.
                        if (t.error !is AiException.InvalidKey) {
                            TextButton(onClick = { viewModel.saveUntested(provider, key) }) { Text(stringResource(R.string.ai_key_save_untested)) }
                        }
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.testAndSave(provider, key) },
                enabled = key.isNotBlank() && understood && test != KeyTest.Testing,
            ) { Text(stringResource(R.string.ai_key_test_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun keyPageUrl(provider: AiProvider) = when (provider) {
    AiProvider.GEMINI -> "https://aistudio.google.com/apikey"
    AiProvider.CLAUDE -> "https://console.anthropic.com/settings/keys"
}

fun featureLabelRes(feature: String): Int = when (runCatching { AiFeature.valueOf(feature) }.getOrNull()) {
    AiFeature.KEY_TEST -> R.string.ai_feature_key_test
    AiFeature.EXERCISE_FILL -> R.string.ai_feature_exercise_fill
    AiFeature.EXERCISE_VARIATIONS -> R.string.ai_feature_exercise_variations
    AiFeature.EXERCISE_VIDEOS -> R.string.ai_feature_exercise_videos
    AiFeature.WORKOUT_REVIEW -> R.string.ai_feature_workout_review
    AiFeature.WORKOUT_VARIATIONS -> R.string.ai_feature_workout_variations
    AiFeature.WORKOUT_DRAFT -> R.string.ai_feature_workout_draft
    AiFeature.CHAT -> R.string.ai_feature_chat
    AiFeature.COACH -> R.string.ai_feature_coach
    AiFeature.PHOTO -> R.string.ai_feature_photo
    null -> R.string.ai_feature_other
}
