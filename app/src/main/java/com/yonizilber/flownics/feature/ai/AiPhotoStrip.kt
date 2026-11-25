package com.yonizilber.flownics.feature.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yonizilber.flownics.R
import com.yonizilber.flownics.data.local.media.AiPhotos
import java.io.File

/**
 * Photos to send with an AI request: what is attached, a way to remove each, and a button to add
 * more (up to [AiPhotos.MAX_PER_REQUEST]). Says plainly that the photos go to the AI provider.
 */
@Composable
fun AiPhotoStrip(
    paths: List<String>,
    error: Boolean,
    onPicked: (List<Uri>) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** False where the screen has its own button for adding. */
    showAdd: Boolean = true,
) {
    val room = AiPhotos.MAX_PER_REQUEST - paths.size
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AiPhotos.MAX_PER_REQUEST)) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            paths.forEach { path ->
                Box {
                    AsyncImage(
                        model = File(path),
                        contentDescription = stringResource(R.string.ai_photo_attached),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    FilledTonalIconButton(
                        onClick = { onRemove(path) },
                        modifier = Modifier.size(24.dp).align(Alignment.TopEnd),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                    ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ai_photo_remove), modifier = Modifier.size(14.dp)) }
                }
            }
            if (showAdd && room > 0) {
                TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Text(stringResource(R.string.ai_photo_add), modifier = Modifier)
                }
            }
        }
        if (error) Text(stringResource(R.string.ai_photo_unreadable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (paths.isNotEmpty()) {
            Text(stringResource(R.string.ai_photo_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
