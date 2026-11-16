package com.webdav.album

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal const val MIN_PARALLEL_BYTES = 4L * 1024 * 1024
private const val FREE_SPACE_RESERVE = 8L * 1024 * 1024
private const val PART_COUNT = 3
private val segmentSlot = Semaphore(1)

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}

private suspend fun copyChecked(input: InputStream, output: OutputStream, expected: Long? = null) {
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        count += read
        if (expected != null && count > expected) throw IOException("服务器返回的图片分段长度不正确")
        output.write(buffer, 0, read)
    }
    if (expected != null && count != expected) throw IOException("图片传输不完整")
}

/** Copies the original bytes, using parallel ranges only when they can be validated. */
suspend fun downloadImage(client: OkHttpClient, url: String, tempDir: File, knownSize: Long?, output: () -> OutputStream) = withContext(Dispatchers.IO) {
    val base = Request.Builder().url(url.substringBefore('#')).build()
    suspend fun single() {
        client.newCall(base).awaitResponse().use { response ->
            if (!response.isSuccessful || response.code == 206) throw IOException("下载失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("下载内容为空")
            output().use { target -> body.byteStream().use { copyChecked(it, target, body.contentLength().takeIf { size -> size >= 0 }) } }
        }
    }
    if (knownSize != null && knownSize < MIN_PARALLEL_BYTES) {
        single()
        return@withContext
    }
    val probe = client.newCall(base.newBuilder().header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()).awaitResponse()
    val metadata = probe.use { response ->
        if (response.code == 200 && (response.header("Content-Encoding") == null || response.header("Content-Encoding").equals("identity", true))) {
            val body = response.body ?: throw IOException("下载内容为空")
            output().use { target -> body.byteStream().use { copyChecked(it, target, body.contentLength().takeIf { size -> size >= 0 }) } }
            return@withContext
        }
        if (response.code in setOf(401, 403, 404)) throw IOException("下载失败（HTTP ${response.code}）")
        val range = response.header("Content-Range")
        val length = range?.let { Regex("^bytes 0-0/([0-9]+)$", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        val etag = response.header("ETag")
        val encoding = response.header("Content-Encoding")
        if (response.code != 206 || length == null || length < MIN_PARALLEL_BYTES ||
            etag == null || etag.length < 2 || !etag.startsWith('"') || !etag.endsWith('"') ||
            (encoding != null && !encoding.equals("identity", true))) null
        else length to etag
    }
    if (metadata == null || ((tempDir.parentFile?.usableSpace ?: 0L) - FREE_SPACE_RESERVE) / 2 < metadata.first || !segmentSlot.tryAcquire()) {
        single()
        return@withContext
    }
    val (length, etag) = metadata
    val files = mutableListOf<File>()
    try {
        val ok = try {
            check(tempDir.isDirectory || tempDir.mkdirs()) { "无法创建临时目录" }
            repeat(PART_COUNT) { files += File.createTempFile("part-", ".tmp", tempDir) }
            coroutineScope {
                files.mapIndexed { index, file -> async(Dispatchers.IO) {
                    val start = length / PART_COUNT * index
                    val end = if (index == PART_COUNT - 1) length - 1 else length / PART_COUNT * (index + 1) - 1
                    val request = base.newBuilder().header("Range", "bytes=$start-$end").header("If-Range", etag).header("Accept-Encoding", "identity").build()
                    client.newCall(request).awaitResponse().use { response ->
                        if (response.code != 206 || !response.header("Content-Range").equals("bytes $start-$end/$length", true) ||
                            response.header("ETag") != etag || response.header("Content-Encoding")?.equals("identity", true) == false) {
                            throw IOException("服务器未返回一致的图片分段")
                        }
                        val body = response.body ?: throw IOException("图片分段为空")
                        file.outputStream().buffered().use { target -> body.byteStream().use { copyChecked(it, target, end - start + 1) } }
                    }
                } }.awaitAll()
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            single()
        } else {
            output().use { target -> files.forEach { file -> file.inputStream().buffered().use { copyChecked(it, target) } } }
        }
    } finally {
        files.forEach { it.delete() }
        segmentSlot.release()
    }
}
