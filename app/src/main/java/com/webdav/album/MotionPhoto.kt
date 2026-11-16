package com.webdav.album

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.mp4.MotionPhotoMetadata
import coil.Coil
import coil.request.ImageRequest
import dev.chengeeker.lucent.review.GalleryMedia
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun hasMotionVideo(file: File, start: Long, size: Long): Boolean {
    if (start < 0 || size < 12 || start > file.length() || size > file.length() - start) return false
    return RandomAccessFile(file, "r").use { input ->
        input.seek(start + 4)
        val type = ByteArray(4)
        input.readFully(type)
        type.contentEquals(byteArrayOf(0x66, 0x74, 0x79, 0x70))
    }
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MotionPhotoDetector(private val context: Context) {
    private val known = LruCache<String, Boolean>(256)

    suspend fun find(media: GalleryMedia): String? {
        val key = media.url
        val ext = key.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()
        if (key.isBlank() || ext !in setOf("jpg", "jpeg", "heic", "heif", "avif")) return null
        known.get(key)?.let { return key.substringBefore('#').takeIf { _ -> it } }
        val loader = Coil.imageLoader(context)
        if (loader.diskCache?.openSnapshot(key)?.use { true } != true) {
            loader.execute(ImageRequest.Builder(context).data(key).size(64, 64).allowHardware(false).build())
        }
        val result = withContext(Dispatchers.IO) {
            try {
                loader.diskCache?.openSnapshot(key)?.use { snapshot ->
                    val file = snapshot.data.toFile()
                    val retriever = MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(file))).build()
                    val future = retriever.retrieveTrackGroups()
                    try {
                        val groups = future.get(6, TimeUnit.SECONDS)
                        (0 until groups.length).any { index ->
                            val metadata = groups[index].getFormat(0).metadata
                            metadata != null && (0 until metadata.length()).any { entry ->
                                val motion = metadata[entry] as? MotionPhotoMetadata
                                motion != null && hasMotionVideo(file, motion.videoStartPosition, motion.videoSize)
                            }
                        }
                    } finally { future.cancel(true); retriever.close() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        if (result != null) known.put(key, result)
        return key.substringBefore('#').takeIf { result == true }
    }
}
