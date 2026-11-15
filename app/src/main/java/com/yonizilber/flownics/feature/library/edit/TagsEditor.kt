package com.yonizilber.flownics.feature.library.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.ExerciseTag
import com.yonizilber.flownics.domain.model.Tags

/**
 * Free labels on an exercise ("side split"), each with what this exercise does for it. Tags
 * already used elsewhere are offered as chips, so the same goal keeps one name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsEditor(
    tags: List<ExerciseTag>,
    known: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onNote: (String, String) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.edit_tags), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.edit_tags_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        tags.forEach { tag ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("# " + tag.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(tag.name) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.edit_tag_remove, tag.name), modifier = Modifier.size(18.dp))
                        }
                    }
                    OutlinedTextField(
                        value = tag.note,
                        onValueChange = { onNote(tag.name, it) },
                        label = { Text(stringResource(R.string.edit_tag_note)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    )
                }
            }
        }
        val add = {
            if (typed.isNotBlank()) onAdd(typed)
            typed = ""
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it.take(40) },
            label = { Text(stringResource(R.string.edit_tag_new)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                IconButton(onClick = add, enabled = typed.isNotBlank()) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.edit_tag_add)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Tags from other exercises, filtered by what is being typed.
        val mine = tags.map { Tags.key(it.name) }.toSet()
        val offered = known.filter { Tags.key(it) !in mine && (typed.isBlank() || Tags.key(typed) in Tags.key(it)) }.take(12)
        if (offered.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offered.forEach { name ->
                    AssistChip(onClick = { onAdd(name); typed = "" }, label = { Text("# $name") })
                }
            }
        }
    }
}
