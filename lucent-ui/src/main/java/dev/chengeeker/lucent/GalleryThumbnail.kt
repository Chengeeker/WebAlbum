package dev.chengeeker.lucent

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.chengeeker.lucent.review.*

/** Keep this source mounted underneath the gallery until its return flight completes. */
@Composable
fun GalleryThumbnail(media: GalleryMedia, source: GalleryOrigin, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(context, media.url) {
        ImageRequest.Builder(context).data(media.url).allowHardware(false).build()
    }
    var loading by remember(media.url) { mutableStateOf(true) }
    var failed by remember(media.url) { mutableStateOf(false) }
    Box(modifier.onGloballyPositioned { source.coordinates = it }
        .clip(RoundedCornerShape(source.radius))
        // Hide only the drawing; the source must still consume taps during a fast return/reopen.
        .graphicsLayer { alpha = if (source.covered) 0f else 1f }
        .then(if (onLongClick == null) Modifier.lucentClick(onClick = onClick) else Modifier.combinedClickable(hapticFeedbackEnabled = false, onClick = hapticClick(onClick), onLongClick = hapticClick(onLongClick)))) {
        if (media.url.isNotBlank()) AsyncImage(request, media.description, Modifier.fillMaxSize(),
            contentScale = if (source.crop) ContentScale.Crop else ContentScale.Fit,
            onLoading = { loading = true; failed = false },
            onError = { loading = false; failed = true },
            onSuccess = { loading = false; failed = false; source.bitmap = it.result.drawable.galleryBitmap() })
        if (loading && media.url.isNotBlank()) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
        if (failed && media.video.isBlank()) Icon(Icons.Outlined.BrokenImage, "图片加载失败，点击打开后重试", Modifier.align(Alignment.Center).size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (media.video.isNotBlank()) {
            Icon(Icons.Rounded.PlayCircle, "播放视频", Modifier.align(Alignment.Center).size(44.dp), tint = androidx.compose.ui.graphics.Color.White)
            if (media.duration.isNotBlank()) Text(media.duration,
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp), color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp)
        }
        if (media.hdr) HdrBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}
