package com.webdav.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import dev.chengeeker.lucent.review.GalleryHdrRegistry
import dev.chengeeker.lucent.review.isHdrVideoTransfer
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// Only actual decoder work occupies a slot. Cache hits bypass this semaphore.
private val thumbnailSlots = Semaphore(3)

internal class WebDavRangeSource(client: OkHttpClient, url: String, private val connectionValid: () -> Boolean = { true }) : MediaDataSource() {
    private val client = client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    private val request = Request.Builder().url(url.substringBefore('#')).header("Accept-Encoding", "identity")
    private var used = 0L
    private var calls = 0
    private val blockSize = 128 * 1024
    // The native metadata retriever may seek back to earlier boxes (moov/mdat).
    // Four bounded aligned blocks avoid downloading the same ranges repeatedly.
    private val blocks = object : LinkedHashMap<Long, ByteArray>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 4
    }
    internal val rangeRequestCount get() = calls
    internal val rangeDownloadedBytes get() = used
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
        val start = position / blockSize * blockSize
        var block = blocks[start]
        if (block == null) {
            val count = minOf(blockSize.toLong(), length - start).toInt()
            if (++calls > 128 || used + count > 8L * 1024 * 1024 ||
                System.nanoTime() > deadline) throw IOException("视频封面读取超过限制")
            val end = start + count - 1
            val builder = request.header("Range", "bytes=$start-$end")
            if (etag != null) builder.header("If-Match", etag)
            client.newCall(builder.build()).execute().use { response ->
                if (response.code != 206 ||
                    !response.header("Content-Range").orEmpty().equals("bytes $start-$end/$length", ignoreCase = true) ||
                    response.header("Content-Encoding")?.equals("identity", true) == false)
                    throw IOException("视频分段响应无效")
                val body = response.body ?: throw IOException("视频分段为空")
                if (body.contentLength() >= 0 && body.contentLength() != count.toLong())
                    throw IOException("视频分段长度不符")
                val bytes = ByteArray(count)
                body.byteStream().use { input ->
                    var read = 0
                    while (read < count) {
                        if (!connectionValid()) throw CancellationException("连接已更改")
                        val received = input.read(bytes, read, count - read)
                        if (received < 0) throw IOException("视频分段不完整")
                        read += received
                    }
                    if (input.read() != -1) throw IOException("视频分段过长")
                }
                block = bytes
                blocks[start] = bytes
                used += count
            }
        }
        val cached = requireNotNull(block)
        val from = (position - start).toInt()
        val count = minOf(size, cached.size - from)
        cached.copyInto(buffer, offset, from, from + count)
        return count
    }
    override fun close() = Unit
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class VideoThumbnailFetcher(private val uri: Uri, private val options: Options, private val client: OkHttpClient, private val loader: ImageLoader,
    private val connectionValid: () -> Boolean) : Fetcher {
    override suspend fun fetch(): FetchResult {
        if (!connectionValid()) throw CancellationException("连接已更改")
        val cache = if (options.diskCachePolicy.readEnabled || options.diskCachePolicy.writeEnabled) loader.diskCache else null
        val key = options.diskCacheKey ?: uri.toString()
        // Warm video galleries must not queue every disk lookup behind slow native
        // MediaMetadataRetriever calls. Recheck inside the queue to avoid duplicates.
        suspend fun cachedCover(): DrawableResult? = withContext(Dispatchers.IO) {
            if (!options.diskCachePolicy.readEnabled) return@withContext null
            val bitmap = cache?.openSnapshot(key)?.use { snapshot ->
                // v1.2.7+ covers persist the HDR transfer flag beside the JPEG.
                val marker = runCatching { snapshot.metadata.toFile().readBytes().firstOrNull() }.getOrNull()
                if (marker == 1.toByte()) GalleryHdrRegistry.recordHdr(uri.toString())
                BitmapFactory.decodeFile(snapshot.data.toFile().absolutePath)
            } ?: return@withContext null
            if (!connectionValid()) throw CancellationException("连接已更改")
            DrawableResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
        }
        cachedCover()?.let { return it }
        return thumbnailSlots.withPermit {
            cachedCover()?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                if (!connectionValid()) throw CancellationException("连接已更改")
                val requestContext = currentCoroutineContext()
                val validDuringFetch = { connectionValid() && requestContext.isActive }
                var isHdr = false
                val frame = try {
                    val source = WebDavRangeSource(client, uri.toString(), validDuringFetch)
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(source)
                        isHdr = videoRetrieverIsHdr(retriever)
                        decodeFrame(retriever)
                    } finally { retriever.release(); source.close() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    downloadVideoFrameFallback(client, uri.toString(), options.context.cacheDir, validDuringFetch) {
                        isHdr = it
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!connectionValid()) throw CancellationException("连接已更改")
                if (isHdr) GalleryHdrRegistry.recordHdr(uri.toString())
                if (options.diskCachePolicy.writeEnabled) {
                    val editor = cache?.openEditor(key)
                    if (editor != null) try {
                        editor.data.toFile().outputStream().use {
                            if (!frame.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("无法保存封面")
                        }
                        editor.metadata.toFile().writeBytes(byteArrayOf(if (isHdr) 1 else 0))
                        editor.commitAndOpenSnapshot()?.close()
                    } catch (_: Exception) { runCatching { editor.abort() } }
                }
                if (!connectionValid()) throw CancellationException("连接已更改")
                DrawableResult(BitmapDrawable(options.context.resources, frame), isSampled = true, dataSource = DataSource.NETWORK)
            }
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

/** Source metadata, not name heuristics: HLG and SMPTE ST2084 are HDR. */
internal fun videoRetrieverIsHdr(retriever: MediaMetadataRetriever): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    val transfer = runCatching {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
    }.getOrNull()
    return isHdrVideoTransfer(transfer)
}

internal fun decodeFrame(retriever: MediaMetadataRetriever): Bitmap {
    if (Build.VERSION.SDK_INT >= 27) {
        // Prefer a sync frame at the start: representative-frame selection at -1
        // may seek far into a remote video and trigger many extra HTTP ranges.
        val first = try { retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (first != null) return first
        val representative = try { retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (representative != null) return representative
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

internal suspend fun downloadVideoFrameFallback(client: OkHttpClient, url: String, cacheDir: File,
    connectionValid: () -> Boolean = { true }, onHdr: (Boolean) -> Unit = {}): Bitmap {
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
        try {
            retriever.setDataSource(file.absolutePath)
            onHdr(videoRetrieverIsHdr(retriever))
            return decodeFrame(retriever)
        } finally { retriever.release() }
    } finally { file.delete() }
}
