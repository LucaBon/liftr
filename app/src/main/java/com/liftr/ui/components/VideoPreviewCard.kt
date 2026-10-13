package com.liftr.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.liftr.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preview card for a workout set video. Supports inline playback (raw recording
 * or annotated export), plus share / save / delete actions.
 */
@Composable
fun VideoPreviewCard(
    videoPath: String,
    sizeBytes: Long,
    onShare: () -> Unit,
    onSaveToGallery: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isAnnotated: Boolean = false
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, videoPath) {
        value = withContext(Dispatchers.IO) { extractThumbnail(videoPath) }
    }
    var isPlaying by remember(videoPath) { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.video_ready_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = if (isAnnotated) {
                    stringResource(R.string.video_ready_subtitle, formatSize(sizeBytes))
                } else {
                    stringResource(R.string.video_recording_subtitle, formatSize(sizeBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    VideoPlayer(
                        videoPath = videoPath,
                        modifier = Modifier.fillMaxSize(),
                        onPlaybackEnded = { isPlaying = false }
                    )
                } else {
                    thumbnail?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = stringResource(R.string.video_ready_title),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { isPlaying = !isPlaying },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.video_pause else R.string.video_play
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.video_share))
                }
                OutlinedButton(
                    onClick = onSaveToGallery,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.video_save_gallery))
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.video_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(
    videoPath: String,
    modifier: Modifier = Modifier,
    onPlaybackEnded: () -> Unit
) {
    val videoView = remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoPath) {
        onDispose {
            videoView.value?.stopPlayback()
            videoView.value = null
        }
    }

    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoPath(videoPath)
                setOnCompletionListener { onPlaybackEnded() }
                start()
                videoView.value = this
            }
        },
        modifier = modifier,
        update = { view ->
            if (view.tag != videoPath) {
                view.tag = videoPath
                view.setVideoPath(videoPath)
                view.start()
            }
        }
    )
}

private fun extractThumbnail(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val frame: Bitmap? = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        frame?.asImageBitmap()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}
