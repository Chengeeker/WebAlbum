package com.webdav.album

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * Transient, account/version-scoped music streaming cache shared by the playback
 * service and the neighbor warm-up jobs. Do not use the image cache for audio.
 */
@UnstableApi
internal class WebAlbumMusicStreamCache(private val app: AlbumApplication) {
    private val cache = SimpleCache(
        File(app.cacheDir, "music-stream-cache"),
        LeastRecentlyUsedCacheEvictor(96L * 1024 * 1024),
        StandaloneDatabaseProvider(app),
    )
    private val upstream = OkHttpDataSource.Factory(okhttp3.Call.Factory { app.client.newCall(it) })
        .setUserAgent("WebAlbumMusic/1.1")
    val factory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        // About 0.75 MiB per neighbor; deliberately not a full-song download.
        const val WARM_BYTES = 768 * 1024
        fun key(track: MusicTrack, connection: Connection): String =
            connection.videoScope + "|" + track.scopeId
        fun neighbors(count: Int, current: Int): List<Int> {
            if (count <= 1 || current !in 0 until count) return emptyList()
            return listOf(current + 1, current - 1)
                .filter { it in 0 until count }.distinct()
        }
    }

    suspend fun warmNeighbors(queue: List<MusicTrack>, current: Int, connection: Connection) {
        for (index in neighbors(queue.size, current)) {
            if (app.connection != connection) return
            val track = queue[index]
            val url = track.url.toHttpUrlOrNull() ?: continue
            if (!sameOrigin(connection.root, url)) continue
            // Catch remote servers that reject Range or files shorter than WARM_BYTES:
            // a failed speculative warm-up must never prevent playback.
            try {
                withContext(Dispatchers.IO) {
                    val data = DataSpec.Builder()
                        .setUri(track.url)
                        .setPosition(0L)
                        .setLength(WARM_BYTES.toLong())
                        .setKey(key(track, connection))
                        .build()
                    val writer = CacheWriter(factory.createDataSource(), data, null, null)
                    val context = currentCoroutineContext()
                    val handle = context[Job]?.invokeOnCompletion { if (context[Job]?.isCancelled == true) writer.cancel() }
                    try {
                        writer.cache()
                    } finally {
                        handle?.dispose()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Playback still falls back to the normal remote data source.
            }
        }
    }
}
