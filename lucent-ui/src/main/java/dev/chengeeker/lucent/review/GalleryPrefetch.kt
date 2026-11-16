package dev.chengeeker.lucent.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

// Protect users on slower or metered WebDAV links from speculative giant downloads.
internal const val MAX_NEARBY_PREFETCH_BYTES = 12L * 1024 * 1024

/** At most one photo on either side. Never prefetch video, GIF, or known giant files. */
internal fun galleryNearbyPrefetchIndices(media: List<GalleryMedia>, center: Int): List<Int> {
    if (center !in media.indices) return emptyList()
    return listOf(center + 1, center - 1).filter { index ->
        if (index !in media.indices) return@filter false
        val item = media[index]
        if (item.url.isBlank() || item.video.isNotBlank() || item.gif ||
            !(item.url.startsWith("https://", true) || item.url.startsWith("http://", true))) return@filter false
        val knownSize = item.url.substringAfter('#', "").substringAfterLast('.', "").toLongOrNull()
        knownSize == null || knownSize in 0..MAX_NEARBY_PREFETCH_BYTES
    }
}

/**
 * Run only after the foreground original is available, never while the user is
 * still swiping. Coil saves remote source bytes in its normal version-scoped
 * disk cache; the small speculative decode is not retained in memory.
 *
 * Cancellation from LaunchedEffect stops obsolete preloads when pages change.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun GalleryAdjacentPrefetch(media: List<GalleryMedia>, current: Int, ready: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(media, current, ready) {
        if (!ready) return@LaunchedEffect
        delay(400)
        val loader = Coil.imageLoader(context)
        for (index in galleryNearbyPrefetchIndices(media, current)) {
            coroutineContext.ensureActive()
            val url = media[index].url
            val onDisk = withContext(Dispatchers.IO) {
                loader.diskCache?.openSnapshot(url)?.use { true } == true
            }
            if (onDisk) continue
            try {
                loader.execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(768, 768)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Background prefetch is best-effort; the regular viewer shows
                // detailed errors if the user opens the media.
            }
        }
    }
}
