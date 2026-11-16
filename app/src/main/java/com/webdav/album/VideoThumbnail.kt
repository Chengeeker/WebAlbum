package com.webdav.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val thumbnailSlots = Semaphore(2)

internal class WebDavRangeSource(client: OkHttpClient, url: String, private val connectionValid: () -> Boolean = { true }) : MediaDataSource() {
    private val client = client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    private val request = Request.Builder().url(url.substringBefore('#')).header("Accept-Encoding", "identity")
    private var used = 0L
    private var calls = 0
    private var blockStart = -1L
    private var block = ByteArray(0)
    private val length: Long
    private val etag: String?

    init {
        if (!connectionValid()) throw CancellationException("连接已更改")
        client.newCall(request.header("Range", "bytes=0-0").build()).execute().use { response ->
            val match = Regex("bytes 0-0/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(response.header("Content-Range").orEmpty())
            if (response.code != 206 || match == null || response.header("Content-Encoding")?.equals("identity", true) == false) throw IOException("服务器不支持视频封面所需的分段读取")
            length = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("无效的视频长度")
            etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
        }
    }

    override fun getSize(): Long = length
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (!connectionValid()) throw CancellationException("连接已更改")
        if (position >= length) return -1
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) throw IOException("无效的读取范围")
        if (size == 0) return 0
        if (position >= blockStart && position < blockStart + block.size) {
            val start = (position - blockStart).toInt()
            val count = minOf(size, block.size - start)
            block.copyInto(buffer, offset, start, start + count)
            return count
        }
        val start = position / 65536 * 65536
        val count = minOf(65536L, length - start).toInt()
        if (++calls > 128 || used + count > 8L * 1024 * 1024 || System.nanoTime() > deadline) throw IOException("视频封面读取超过限制")
        val end = start + count - 1
        val builder = request.header("Range", "bytes=$start-$end")
        if (etag != null) builder.header("If-Match", etag)
        client.newCall(builder.build()).execute().use { response ->
            if (response.code != 206 || !response.header("Content-Range").orEmpty().equals("bytes $start-$end/$length", ignoreCase = true) ||
                response.header("Content-Encoding")?.equals("identity", true) == false) throw IOException("视频分段响应无效")
            val body = response.body ?: throw IOException("视频分段为空")
            if (body.contentLength() >= 0 && body.contentLength() != count.toLong()) throw IOException("视频分段长度不符")
            val stream = body.byteStream()
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = stream.read(bytes, read, count - read)
                if (n < 0) throw IOException("视频分段不完整")
                read += n
            }
            if (stream.read() != -1) throw IOException("视频分段过长")
            blockStart = start; block = bytes
        }
        used += count
        val from = (position - blockStart).toInt()
        val returned = minOf(size, block.size - from)
        block.copyInto(buffer, offset, from, from + returned)
        return returned
    }
    override fun close() = Unit
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class VideoThumbnailFetcher(private val uri: Uri, private val options: Options, private val client: OkHttpClient, private val loader: ImageLoader,
    private val connectionValid: () -> Boolean) : Fetcher {
    override suspend fun fetch(): FetchResult = thumbnailSlots.withPermit {
        withContext(Dispatchers.IO) {
            if (!connectionValid()) throw CancellationException("连接已更改")
            val cache = if (options.diskCachePolicy.readEnabled || options.diskCachePolicy.writeEnabled) loader.diskCache else null
            val key = options.diskCacheKey ?: uri.toString()
            if (options.diskCachePolicy.readEnabled) {
                val cached = cache?.openSnapshot(key)?.use { BitmapFactory.decodeFile(it.data.toFile().absolutePath) }
                if (cached != null) {
                    if (!connectionValid()) throw CancellationException("连接已更改")
                    return@withContext DrawableResult(BitmapDrawable(options.context.resources, cached), true, DataSource.DISK)
                }
            }
            val frame = try {
                val source = WebDavRangeSource(client, uri.toString(), connectionValid)
                val retriever = MediaMetadataRetriever()
                try { retriever.setDataSource(source); decodeFrame(retriever) } finally { retriever.release(); source.close() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { downloadVideoFrameFallback(client, uri.toString(), options.context.cacheDir, connectionValid) }
            currentCoroutineContext().ensureActive()
            if (!connectionValid()) throw CancellationException("连接已更改")
            if (options.diskCachePolicy.writeEnabled) {
                val editor = cache?.openEditor(key)
                if (editor != null) try {
                    editor.data.toFile().outputStream().use { if (!frame.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("无法保存封面") }
                    editor.metadata.toFile().writeBytes(byteArrayOf())
                    editor.commitAndOpenSnapshot()?.close()
                } catch (_: Exception) { runCatching { editor.abort() } }
            }
            if (!connectionValid()) throw CancellationException("连接已更改")
            DrawableResult(BitmapDrawable(options.context.resources, frame), isSampled = true, dataSource = DataSource.NETWORK)
        }
    }

    class Factory(private val client: () -> OkHttpClient, private val revision: () -> Long, private val connection: () -> Connection?) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ext = data.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (data.scheme !in setOf("http", "https") || ext !in setOf("mp4", "m4v", "mov", "3gp", "3gpp", "webm", "mkv", "ts", "m2ts", "avi", "mpg", "mpeg")) return null
            val initialRevision = revision()
            val initialConnection = connection()
            val scope = data.fragment?.substringBefore('.')?.substringAfter('~', "")
            val matches = initialConnection != null && scope == initialConnection.videoScope
            return VideoThumbnailFetcher(data, options, client(), imageLoader) { matches && revision() == initialRevision && connection() == initialConnection }
        }
    }
}

internal fun decodeFrame(retriever: MediaMetadataRetriever): Bitmap {
    if (Build.VERSION.SDK_INT >= 27) {
        val scaled = try { retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (scaled != null) return scaled
    }
    fun metadata(key: Int): Long? = try { retriever.extractMetadata(key)?.toLongOrNull() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    if (width == null || height == null || width <= 0 || height <= 0 || width > 12_000_000L / height)
        throw IOException("视频尺寸未知或过大，无法安全提取封面")
    val original = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: throw IOException("无法解码视频封面")
    val scale = minOf(1f, 320f / maxOf(original.width, original.height))
    return if (scale == 1f) original else Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true).also { original.recycle() }
}

internal suspend fun downloadVideoFrameFallback(client: OkHttpClient, url: String, cacheDir: File, connectionValid: () -> Boolean = { true }): Bitmap {
    val folder = cacheDir.resolve("video-covers-temp")
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建封面临时目录")
    val file = File.createTempFile("cover-", ".video", folder)
    try {
        if (!connectionValid()) throw CancellationException("连接已更改")
        val limited = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url.substringBefore('#')).header("Accept-Encoding", "identity").build()
        limited.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("视频内容为空")
            val length = body.contentLength()
            if (response.code != 200 || length == 0L || response.header("Content-Encoding")?.equals("identity", true) == false)
                throw IOException("视频不支持分段读取，且大小无法安全回退")
            val limit = if (length in 1..(16L * 1024 * 1024)) length else 4L * 1024 * 1024
            body.byteStream().use { input -> file.outputStream().use { output ->
                val bytes = ByteArray(64 * 1024); var total = 0L
                while (total < limit) {
                    currentCoroutineContext().ensureActive()
                    if (!connectionValid()) throw CancellationException("连接已更改")
                    val count = input.read(bytes, 0, minOf(bytes.size.toLong(), limit - total).toInt())
                    if (count < 0) break
                    total += count
                    output.write(bytes, 0, count)
                }
                if (total == 0L || (length in 1..limit && total != length)) throw IOException("视频下载不完整")
            } }
        }
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(file.absolutePath); return decodeFrame(retriever) } finally { retriever.release() }
    } finally { file.delete() }
}
