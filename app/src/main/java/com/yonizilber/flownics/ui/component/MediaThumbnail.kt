package com.yonizilber.flownics.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yonizilber.flownics.R
import com.yonizilber.flownics.domain.model.ExerciseMedia
import com.yonizilber.flownics.domain.model.MediaKind
import java.io.File

/** Square-ish tile for one media item. [resolveFile] maps a stored file name to the private file. */
@Composable
fun MediaThumbnail(
    media: ExerciseMedia,
    resolveFile: (String) -> File,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when (media.kind) {
            MediaKind.IMAGE -> AsyncImage(
                model = resolveFile(media.location),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            MediaKind.VIDEO -> Icon(
                Icons.Outlined.PlayCircle,
                contentDescription = stringResource(R.string.media_open_video),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            MediaKind.LINK -> Icon(
                Icons.Outlined.Link,
                contentDescription = stringResource(R.string.media_open_link),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}
