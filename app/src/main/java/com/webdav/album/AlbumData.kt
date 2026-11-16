package com.webdav.album

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import android.util.Base64
import android.util.Xml
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.disk.DiskCache
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class Connection(val url: String, val user: String, val password: String) {
    val root: HttpUrl get() = requireNotNull(url.toHttpUrlOrNull())
    val videoScope: String by lazy {
        val bytes = MessageDigest.getInstance("SHA-256").digest((url + "\u0000" + user + "\u0000" + password).toByteArray())
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

data class DavItem(val url: String, val name: String, val directory: Boolean, val version: String) {
    val image: Boolean get() = !directory && name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    val video: Boolean get() = !directory && name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mov", "3gp", "3gpp", "webm", "mkv", "ts", "m2ts", "avi", "mpg", "mpeg")
    val audio: Boolean get() = isAudioFile(name, directory)
    fun imageUrl(connection: Connection): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((connection.user + "\u0000" + connection.password + "\u0000" + version).toByteArray())
        val token = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val size = version.substringAfterLast('|').toLongOrNull()?.takeIf { it >= MIN_PARALLEL_BYTES }
        return url.substringBefore('#') + "#" + token + (if (video) "~${connection.videoScope}" else "") + (size?.let { ".$it" } ?: "")
    }
}

fun validConnection(url: String): HttpUrl? = url.trim().toHttpUrlOrNull()?.takeIf {
    it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
}

fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

class AlbumApplication : Application(), ImageLoaderFactory {
    internal val musicStreamCache by lazy { WebAlbumMusicStreamCache(this) }
    val music by lazy { WebAlbumMusicPlayback(this) }
    private val connectionRevision = java.util.concurrent.atomic.AtomicLong()
    @Volatile var connection: Connection? = null
        set(value) { field = value; connectionRevision.incrementAndGet() }
    fun connectionRevision(): Long = connectionRevision.get()
    @Volatile var client: OkHttpClient = createClient(NetworkSettings())
        private set
    fun applyNetwork(settings: NetworkSettings) { require(validNetwork(settings)); client = createClient(settings) }
    private fun createClient(settings: NetworkSettings): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 })
        .connectTimeout(settings.timeout.toLong(), TimeUnit.SECONDS).readTimeout(settings.timeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(settings.timeout.toLong(), TimeUnit.SECONDS)
        .apply {
            if (settings.mode == "direct") proxy(Proxy.NO_PROXY)
            if (settings.mode == "http") proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(settings.host, settings.port)))
        }.followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
        var request = chain.request()
        val config = connection
        var redirects = 0
        while (true) {
            val builder = request.newBuilder().removeHeader("Authorization")
            if (config != null && sameOrigin(config.root, request.url)) {
                builder.header("Authorization", Credentials.basic(config.user, config.password, Charsets.UTF_8))
            }
            val response = chain.proceed(builder.build())
            val next = response.header("Location")?.let { request.url.resolve(it) }
            val redirect = response.code in setOf(301, 302, 303, 307, 308)
            val safeTarget = next != null && next.username.isEmpty() && next.password.isEmpty() &&
                (sameOrigin(request.url, next) || (request.method == "GET" && next.isHttps)) &&
                (!request.url.isHttps || next.isHttps)
            if (!redirect || !safeTarget || redirects >= 5 || request.method !in setOf("GET", "HEAD", "PROPFIND") ||
                (response.code == 303 && request.method == "PROPFIND")) {
                return@addInterceptor response
            }
            response.close()
            request = request.newBuilder().url(next!!).build()
            redirects++
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }.build()
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory(okhttp3.Call.Factory { request -> client.newCall(request) })
        // A local-only Wi-Fi network can reach WebDAV without Android's INTERNET capability.
        .networkObserverEnabled(false)
        .respectCacheHeaders(false)
        .components {
            add(VideoThumbnailFetcher.Factory({ client }, ::connectionRevision, { connection }))
            add(ParallelImageFetcher.Factory({ client }, cacheDir.resolve("download-parts")))
            add(GifDecoder.Factory())
            // ImageDecoder on Android 14+ preserves Ultra HDR gainmaps and wide-gamut metadata.
            if (Build.VERSION.SDK_INT >= 34) add(coil.decode.ImageDecoderDecoder.Factory())
        }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(1280L * 1024 * 1024).build() }
        .build()
    override fun onCreate() {
        super.onCreate()
        applyNetwork(AlbumPreferences(this).network)
        cacheDir.resolve("download-parts").listFiles()?.forEach { it.delete() }
        cacheDir.resolve("video-covers-temp").listFiles()?.forEach { it.delete() }
        connection = runCatching { Secrets(this).load() }.getOrNull()
    }
}

class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("webalbum", null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("webalbum", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(c: Connection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(c.password.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("url", c.url).putString("user", c.user)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("password", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存连接" }
    }
    fun load(): Connection? {
        val url = prefs.getString("url", null) ?: return null
        requireNotNull(validConnection(url))
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("password", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return Connection(url, prefs.getString("user", "").orEmpty(), cipher.doFinal(encrypted).toString(Charsets.UTF_8))
    }
}

class DavClient(private val client: OkHttpClient) {
    fun delete(root: HttpUrl, folder: HttpUrl, item: DavItem) {
        val target = requireNotNull(item.url.toHttpUrlOrNull())
        val base = folder.encodedPath.trimEnd('/') + "/"
        val name = target.pathSegments.lastOrNull().orEmpty()
        require(!item.directory && sameOrigin(root, folder) && sameOrigin(root, target) && folderChain(root, folder.toString()) != null &&
            target.query == null && target.fragment == null && target.username.isEmpty() && target.password.isEmpty() &&
            target.encodedPath.startsWith(base) && !target.encodedPath.removePrefix(base).contains('/') &&
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        val request = Request.Builder().url(target).delete()
        val etag = item.version.substringBefore('|')
        if (etag.startsWith('"') && etag.endsWith('"') && etag.length >= 2 && etag.none { it == '\r' || it == '\n' }) request.header("If-Match", etag)
        client.newCall(request.build()).execute().use { response ->
            if (response.code !in setOf(200, 204)) error("删除失败（HTTP ${response.code}）")
        }
    }
    private fun listRequest(root: HttpUrl, folder: HttpUrl): Request {
        require(sameOrigin(root, folder))
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>"""
        return Request.Builder().url(folder).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
    }
    private fun readListing(root: HttpUrl, folder: HttpUrl, response: okhttp3.Response): List<DavItem> {
        if (response.code != 207) error("读取目录失败（HTTP ${response.code}）")
        val source = response.body?.source() ?: error("服务器没有返回目录")
        source.request(4L * 1024 * 1024 + 1)
        require(source.buffer.size <= 4L * 1024 * 1024) { "目录内容过大，请选择较小的目录" }
        return parse(root, folder, source.readUtf8())
    }
    fun list(root: HttpUrl, folder: HttpUrl): List<DavItem> =
        client.newCall(listRequest(root, folder)).execute().use { readListing(root, folder, it) }

    suspend fun listAsync(root: HttpUrl, folder: HttpUrl): List<DavItem> = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val call = client.newCall(listRequest(root, folder))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val result = runCatching { response.use { readListing(root, folder, it) } }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
    fun parse(root: HttpUrl, folder: HttpUrl, xml: String): List<DavItem> {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "不支持的目录响应" }
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(StringReader(xml)) }
        val items = mutableListOf<DavItem>()
        var href = ""; var etag = ""; var modified = ""; var size = ""; var directory = false; var successful = false
        var status = ""; var inPropstat = false; var propEtag = ""; var propModified = ""; var propSize = ""; var propDirectory = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL) error("不支持的目录响应")
            if (event == XmlPullParser.START_TAG && parser.namespace == "DAV:") when (parser.name) {
                "response" -> { href = ""; etag = ""; modified = ""; size = ""; directory = false; successful = false }
                "propstat" -> { inPropstat = true; status = ""; propEtag = ""; propModified = ""; propSize = ""; propDirectory = false }
                "href" -> if (!inPropstat) href = parser.nextText()
                "status" -> if (inPropstat) status = parser.nextText()
                "collection" -> if (inPropstat) propDirectory = true
                "getetag" -> if (inPropstat) propEtag = parser.nextText()
                "getlastmodified" -> if (inPropstat) propModified = parser.nextText()
                "getcontentlength" -> if (inPropstat) propSize = parser.nextText()
            }
            if (event == XmlPullParser.END_TAG && parser.namespace == "DAV:") when (parser.name) {
                "propstat" -> { if (status.contains(" 200 ")) { etag = propEtag.ifEmpty { etag }; modified = propModified.ifEmpty { modified }; size = propSize.ifEmpty { size }; directory = directory || propDirectory; successful = true }; inPropstat = false }
                "response" -> {
                    val target = folder.resolve(href)
                    if (successful && href.isNotBlank() && target != null && target.username.isEmpty() && target.password.isEmpty() && target.query == null && target.fragment == null && sameOrigin(root, target)) {
                        val base = folder.encodedPath.trimEnd('/') + "/"
                        val path = target.encodedPath.trimEnd('/')
                        if (path.startsWith(base) && !path.removePrefix(base).contains('/')) {
                            val name = target.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
                            if (name.isNotBlank()) items += DavItem(target.toString().let { if (directory) it.trimEnd('/') + "/" else it }, name, directory, "$etag|$modified|$size")
                        }
                    }
                }
            }
            event = parser.next()
        }
        return items.distinctBy { it.url }.sortedWith(compareBy<DavItem> { !it.directory }.thenBy { it.name.lowercase() })
    }
}

data class FolderListing(val path: List<String>, val entries: List<DavItem>, val usedFallback: Boolean)

fun DavClient.listFolder(connection: Connection, path: List<String>, fallback: Boolean): FolderListing {
    return try {
        FolderListing(path, list(connection.root, requireNotNull((path.lastOrNull() ?: connection.url).toHttpUrlOrNull())), false)
    } catch (error: Exception) {
        if (!fallback || path.isEmpty() || error is kotlinx.coroutines.CancellationException) throw error
        FolderListing(emptyList(), list(connection.root, connection.root), true)
    }
}

suspend fun DavClient.listFolderAsync(connection: Connection, path: List<String>, fallback: Boolean): FolderListing {
    return try {
        FolderListing(path, listAsync(connection.root, requireNotNull((path.lastOrNull() ?: connection.url).toHttpUrlOrNull())), false)
    } catch (error: Exception) {
        if (!fallback || path.isEmpty() || error is kotlinx.coroutines.CancellationException) throw error
        FolderListing(emptyList(), listAsync(connection.root, connection.root), true)
    }
}
