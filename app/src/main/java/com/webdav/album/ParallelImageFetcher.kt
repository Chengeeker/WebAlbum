package com.webdav.album

import android.net.Uri
import android.webkit.MimeTypeMap
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import java.io.File
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoilApi::class)
class ParallelImageFetcher private constructor(
    private val uri: Uri,
    private val options: Options,
    private val loader: ImageLoader,
    private val client: OkHttpClient,
    private val tempDir: File,
    private val size: Long,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val cache = loader.diskCache ?: return null
        val key = options.diskCacheKey ?: uri.toString()
        cache.openSnapshot(key)?.use { return null }
        val editor = cache.openEditor(key) ?: return null
        var committed = false
        try {
            downloadImage(client, uri.toString(), tempDir, size) { editor.data.toFile().outputStream() }
            editor.metadata.toFile().writeBytes(byteArrayOf())
            val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            val snapshot = editor.commitAndOpenSnapshot()
            committed = true
            if (snapshot == null) return null
            try {
                return SourceResult(ImageSource(snapshot.data, cache.fileSystem, key, snapshot), mime, DataSource.NETWORK)
            } catch (error: Exception) {
                snapshot.close()
                throw error
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            if (!committed) runCatching { editor.abort() }
        }
    }

    class Factory(private val client: () -> OkHttpClient, private val tempDir: File) : Fetcher.Factory<Uri> {
        constructor(client: OkHttpClient, tempDir: File) : this({ client }, tempDir)
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val size = data.fragment?.substringAfterLast('.', "")?.toLongOrNull() ?: return null
            if (data.scheme !in setOf("http", "https") || size < MIN_PARALLEL_BYTES ||
                !options.diskCachePolicy.readEnabled || !options.diskCachePolicy.writeEnabled ||
                !options.networkCachePolicy.readEnabled || options.headers.size != 0) return null
            return ParallelImageFetcher(data, options, imageLoader, client(), tempDir, size)
        }
    }
}
