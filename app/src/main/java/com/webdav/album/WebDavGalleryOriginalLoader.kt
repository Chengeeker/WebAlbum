package com.webdav.album

import android.content.Context
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import dev.chengeeker.lucent.review.GalleryMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

private const val MAX_ORIGINAL_BYTES = 1024L * 1024 * 1024
private const val RESERVED_SPACE_BYTES = 32L * 1024 * 1024

/** Complete local source bytes for region decoding, not a preview bitmap. */
internal class WebDavGalleryOriginalLoader(
    private val context: Context,
    private val app: AlbumApplication,
    private val connection: Connection,
) {
    @OptIn(ExperimentalCoilApi::class)
    suspend fun obtain(media: GalleryMedia, progress: (Long, Long?) -> Unit): File =
        withContext(Dispatchers.IO) {
            val sourceUrl = media.url.substringBefore('#').toHttpUrlOrNull()
                ?: throw IOException("原图地址无效")
            if (!sameOrigin(connection.root, sourceUrl))
                throw IOException("原图地址不属于当前 WebDAV 服务器")
            if (app.connection != connection) throw CancellationException("WebDAV 连接已切换")
            val hash = MessageDigest.getInstance("SHA-256").digest(
                (connection.videoScope + "\u0000" + media.url).toByteArray()
            ).joinToString("") { "%02x".format(it) }
            val dir = File(context.cacheDir, "gallery-originals").also {
                if (!it.isDirectory && !it.mkdirs()) throw IOException("无法创建原图缓存目录")
            }
            val target = File(dir, "$hash.original")
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                progress(target.length(), target.length())
                return@withContext target
            }
            val temp = File.createTempFile("original-", ".partial", dir)
            try {
                val loadingContext = coroutineContext
                val callback: (Long, Long?) -> Unit = { copied, total ->
                    loadingContext.ensureActive()
                    if (app.connection != connection) throw CancellationException("WebDAV 连接已切换")
                    if (copied > MAX_ORIGINAL_BYTES) throw IOException("原图超过 1 GiB 安全上限")
                    progress(copied, total)
                }
                val cache = Coil.imageLoader(context).diskCache
                val snapshot = cache?.openSnapshot(media.url)
                if (snapshot != null) snapshot.use {
                    val cached = it.data.toFile()
                    val total = cached.length()
                    if (total > MAX_ORIGINAL_BYTES) throw IOException("原图超过 1 GiB 安全上限")
                    ensureStorage(temp, total)
                    cached.inputStream().buffered().use { stream ->
                        temp.outputStream().buffered().use { output ->
                            copyWithProgress(stream, output, total, callback)
                        }
                    }
                } else {
                    val req = Request.Builder().url(sourceUrl).header("Accept-Encoding", "identity").build()
                    val call = app.client.newCall(req)
                    val job = coroutineContext[Job]
                    val handle = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
                    try {
                        call.execute().use { response ->
                            if (response.code != 200 || response.header("Content-Encoding")?.equals("identity", true) == false)
                                throw IOException("服务器未返回完整原图（HTTP ${response.code}）")
                            val body = response.body ?: throw IOException("服务器没有返回图片内容")
                            val total = body.contentLength().takeIf { it >= 0 }
                            if (total != null) {
                                if (total > MAX_ORIGINAL_BYTES) throw IOException("原图超过 1 GiB 安全上限")
                                ensureStorage(temp, total)
                            }
                            body.byteStream().buffered().use { input ->
                                temp.outputStream().buffered().use { output ->
                                    copyWithProgress(input, output, total, callback)
                                }
                            }
                        }
                    } finally { handle?.dispose() }
                }
                coroutineContext.ensureActive()
                if (temp.length() <= 0L) throw IOException("原图文件为空")
                if (!temp.renameTo(target)) throw IOException("无法写入原图缓存")
                pruneOriginalCache(dir, target)
                target
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally { temp.delete() }
        }

    private fun ensureStorage(target: File, size: Long) {
        if (size > 0 && (target.parentFile?.usableSpace ?: 0L) < size + RESERVED_SPACE_BYTES)
            throw IOException("存储空间不足，无法缓存完整原图")
    }
}

private suspend fun copyWithProgress(
    source: java.io.InputStream,
    target: java.io.OutputStream,
    expected: Long?,
    progress: (Long, Long?) -> Unit,
) {
    val block = ByteArray(128 * 1024)
    var copied = 0L
    while (true) {
        coroutineContext.ensureActive()
        val read = source.read(block)
        if (read < 0) break
        if (read == 0) continue
        copied += read
        if (expected != null && copied > expected) throw IOException("服务器返回了超过预期的原图数据")
        if (copied > MAX_ORIGINAL_BYTES) throw IOException("原图超过 1 GiB 安全上限")
        target.write(block, 0, read)
        progress(copied, expected)
    }
    if (expected != null && expected != copied)
        throw IOException("原图下载不完整：$copied/$expected")
    progress(copied, expected)
}

private fun pruneOriginalCache(directory: File, keep: File) {
    val maxBytes = 768L * 1024 * 1024
    val files = directory.listFiles()?.filter { it.isFile && it.extension == "original" } ?: return
    var total = files.sumOf { it.length() }
    if (total <= maxBytes) return
    files.sortedBy { it.lastModified() }.forEach { file ->
        if (total <= maxBytes || file == keep) return@forEach
        val bytes = file.length()
        if (file.delete()) total -= bytes
    }
}
