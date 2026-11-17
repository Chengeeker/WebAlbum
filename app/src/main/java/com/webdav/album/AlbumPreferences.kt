package com.webdav.album

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chengeeker.lucent.LucentStyle
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Persisted Storage Access Framework folders per media category. */
enum class MediaSaveKind(val label: String) {
    IMAGE("图片"), VIDEO("视频"), MUSIC("音乐"),
}

/** Do not treat an audio file as an image/video just because it is browsed together. */
internal fun mediaSaveKind(item: DavItem): MediaSaveKind? = when {
    item.directory -> null
    item.audio -> MediaSaveKind.MUSIC
    item.video -> MediaSaveKind.VIDEO
    item.image -> MediaSaveKind.IMAGE
    else -> null
}

data class NetworkSettings(val timeout: Int = 30, val mode: String = "system", val host: String = "127.0.0.1", val port: Int = 8080)
fun validNetwork(value: NetworkSettings) = value.timeout in setOf(15, 30, 60) && value.mode in setOf("system", "direct", "http") &&
    value.port in 1..65535 && value.host.length in 1..253 && value.host.none { it.isWhitespace() || it in "/@\\?#" }

class AlbumPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var style by mutableStateOf(LucentStyle(
        mode = prefs.getString("mode", "system")?.takeIf { it in listOf("system", "light", "dark") } ?: "system",
        dynamic = prefs.getBoolean("dynamic", false), seed = prefs.getInt("seed", 0).coerceIn(0, 7),
        black = prefs.getBoolean("black", false), font = prefs.getFloat("font", 1f).coerceIn(.85f, 1.5f),
        weight = prefs.getInt("weight", 0).coerceIn(-3, 3), customWeight = prefs.getBoolean("weightCustom", false),
        glass = prefs.getBoolean("glass", false), translucent = prefs.getBoolean("translucent", true), motion = prefs.getBoolean("motion", true),
        haptics = prefs.getBoolean("haptics", true),
    ))
        private set
    var columns by mutableIntStateOf(prefs.getInt("columns", 3).coerceIn(1, 5))
        private set
    var folderColumns by mutableIntStateOf(prefs.getInt("folderColumns", 2).coerceIn(1, 5))
        private set
    var showMediaNames by mutableStateOf(prefs.getBoolean("showMediaNames", false))
        private set
    var compactMediaNames by mutableStateOf(prefs.getBoolean("compactMediaNames", false))
        private set
    var musicMiniPlayer by mutableStateOf(prefs.getBoolean("musicMiniPlayer", true))
        private set
    var musicMiniRecovery by mutableStateOf(MiniPlayerRecovery.from(prefs.getString("musicMiniRecovery", "manual")))
        private set
    var musicKeepBackground by mutableStateOf(prefs.getBoolean("musicKeepBackground", true))
        private set
    var musicPreloadEnabled by mutableStateOf(prefs.getBoolean("musicPreloadEnabled", true))
        private set
    var musicProgressWavy by mutableStateOf(prefs.getBoolean("musicProgressWavy", false))
        private set
    var musicLyricsFontSize by mutableIntStateOf(prefs.getInt("musicLyricsFontSize", 24).coerceIn(16, 40))
        private set
    var musicLyricsWeight by mutableIntStateOf(prefs.getInt("musicLyricsWeight", 600).coerceIn(100, 900))
        private set
    var musicLyricsCenter by mutableStateOf(prefs.getBoolean("musicLyricsCenter", true))
        private set
    var musicLyricsBlur by mutableStateOf(prefs.getBoolean("musicLyricsBlur", true))
        private set
    var musicLyricsStagger by mutableStateOf(prefs.getBoolean("musicLyricsStagger", true))
        private set
    var musicLyricKaraoke by mutableStateOf(
        MusicKaraokeMode.from(prefs.getString("musicLyricKaraoke", MusicKaraokeMode.ALL.key))
    )
        private set
    var sortMode by mutableStateOf(AlbumSort.from(prefs.getString("sortMode", "modified")))
        private set
    var sortDescending by mutableStateOf(prefs.getBoolean("sortDescending", true))
        private set
    // Legacy v1.5.0 shared image/video folder. Never silently reuse it for music.
    var downloadTree by mutableStateOf(prefs.getString("downloadTree", "").orEmpty())
        private set
    var imageDownloadTree by mutableStateOf(prefs.getString("imageDownloadTree", downloadTree).orEmpty())
        private set
    var videoDownloadTree by mutableStateOf(prefs.getString("videoDownloadTree", downloadTree).orEmpty())
        private set
    var musicDownloadTree by mutableStateOf(prefs.getString("musicDownloadTree", "").orEmpty())
        private set
    var predictive by mutableStateOf(prefs.getBoolean("predictive", false))
        private set
    var privacy by mutableStateOf(prefs.getBoolean("privacy", false))
        private set
    var refresh by mutableIntStateOf(prefs.getInt("refresh", 0))
        private set
    var icon by mutableIntStateOf(prefs.getInt("icon", 0).coerceIn(0, 2))
        private set
    var cacheThreshold by mutableIntStateOf(prefs.getInt("cacheThreshold", 0).takeIf { it in listOf(0, 16, 64, 256, 512, 1024) } ?: 0)
        private set
    var network by mutableStateOf(NetworkSettings(prefs.getInt("networkTimeout", 30), prefs.getString("proxyMode", "system").orEmpty(),
        prefs.getString("proxyHost", "127.0.0.1").orEmpty(), prefs.getInt("proxyPort", 8080)).let { if (validNetwork(it)) it else NetworkSettings() })
        private set

    fun updateStyle(value: LucentStyle) {
        check(prefs.edit().putString("mode", value.mode).putBoolean("dynamic", value.dynamic).putInt("seed", value.seed)
            .putBoolean("black", value.black).putFloat("font", value.font).putInt("weight", value.weight)
            .putBoolean("weightCustom", value.customWeight).putBoolean("glass", value.glass).putBoolean("translucent", value.translucent)
            .putBoolean("motion", value.motion).putBoolean("haptics", value.haptics).commit())
        style = value
    }
    fun updateColumns(value: Int) { val v = value.coerceIn(1, 5); check(prefs.edit().putInt("columns", v).commit()); columns = v }
    fun updateFolderColumns(value: Int) { val v = value.coerceIn(1, 5); check(prefs.edit().putInt("folderColumns", v).commit()); folderColumns = v }
    fun updateShowMediaNames(value: Boolean) { check(prefs.edit().putBoolean("showMediaNames", value).commit()); showMediaNames = value }
    fun updateCompactMediaNames(value: Boolean) { check(prefs.edit().putBoolean("compactMediaNames", value).commit()); compactMediaNames = value }
    fun updateMusicMiniPlayer(value: Boolean) { check(prefs.edit().putBoolean("musicMiniPlayer", value).commit()); musicMiniPlayer = value }
    fun updateMusicMiniRecovery(value: MiniPlayerRecovery) {
        check(prefs.edit().putString("musicMiniRecovery", value.key).commit())
        musicMiniRecovery = value
    }
    fun updateMusicKeepBackground(value: Boolean) { check(prefs.edit().putBoolean("musicKeepBackground", value).commit()); musicKeepBackground = value }
    fun updateMusicPreloadEnabled(value: Boolean) { check(prefs.edit().putBoolean("musicPreloadEnabled", value).commit()); musicPreloadEnabled = value }
    fun updateMusicProgressWavy(value: Boolean) { check(prefs.edit().putBoolean("musicProgressWavy", value).commit()); musicProgressWavy = value }
    fun updateMusicLyricsFontSize(value: Int) { val n = value.coerceIn(16, 40); check(prefs.edit().putInt("musicLyricsFontSize", n).commit()); musicLyricsFontSize = n }
    fun updateMusicLyricsWeight(value: Int) { val n = value.coerceIn(100, 900); check(prefs.edit().putInt("musicLyricsWeight", n).commit()); musicLyricsWeight = n }
    fun updateMusicLyricsCenter(value: Boolean) { check(prefs.edit().putBoolean("musicLyricsCenter", value).commit()); musicLyricsCenter = value }
    fun updateMusicLyricsBlur(value: Boolean) { check(prefs.edit().putBoolean("musicLyricsBlur", value).commit()); musicLyricsBlur = value }
    fun updateMusicLyricsStagger(value: Boolean) { check(prefs.edit().putBoolean("musicLyricsStagger", value).commit()); musicLyricsStagger = value }
    fun updateMusicLyricKaraoke(value: MusicKaraokeMode) {
        check(prefs.edit().putString("musicLyricKaraoke", value.key).commit())
        musicLyricKaraoke = value
    }
    fun updateSort(mode: AlbumSort) {
        val reverse = if (sortMode == mode) !sortDescending else true
        check(prefs.edit().putString("sortMode", mode.key).putBoolean("sortDescending", reverse).commit())
        sortMode = mode; sortDescending = reverse
    }
    /** Backward-compatible old shared setting: applies only to pictures and videos. */
    fun updateDownloadTree(value: String) {
        check(prefs.edit().putString("downloadTree", value)
            .remove("imageDownloadTree").remove("videoDownloadTree").commit())
        downloadTree = value
        imageDownloadTree = value
        videoDownloadTree = value
    }
    fun treeFor(kind: MediaSaveKind): String = when (kind) {
        MediaSaveKind.IMAGE -> imageDownloadTree
        MediaSaveKind.VIDEO -> videoDownloadTree
        MediaSaveKind.MUSIC -> musicDownloadTree
    }
    fun treeFor(item: DavItem): String = mediaSaveKind(item)?.let(::treeFor).orEmpty()
    fun isTreeStillUsed(uri: String): Boolean = uri.isNotBlank() &&
        MediaSaveKind.entries.any { treeFor(it) == uri }
    /** A cleared individual folder overrides an old shared folder, it must not fall back. */
    fun updateMediaDownloadTree(kind: MediaSaveKind, value: String) {
        val key = when (kind) {
            MediaSaveKind.IMAGE -> "imageDownloadTree"
            MediaSaveKind.VIDEO -> "videoDownloadTree"
            MediaSaveKind.MUSIC -> "musicDownloadTree"
        }
        check(prefs.edit().putString(key, value).commit())
        when (kind) {
            MediaSaveKind.IMAGE -> imageDownloadTree = value
            MediaSaveKind.VIDEO -> videoDownloadTree = value
            MediaSaveKind.MUSIC -> musicDownloadTree = value
        }
    }
    fun updatePredictive(value: Boolean) { check(prefs.edit().putBoolean("predictive", value).commit()); predictive = value }
    fun updatePrivacy(value: Boolean) { check(prefs.edit().putBoolean("privacy", value).commit()); privacy = value }
    fun updateRefresh(value: Int) { check(prefs.edit().putInt("refresh", value).commit()); refresh = value }
    fun updateCacheThreshold(value: Int) { require(value in listOf(0, 16, 64, 256, 512, 1024)); check(prefs.edit().putInt("cacheThreshold", value).commit()); cacheThreshold = value }
    fun updateNetwork(value: NetworkSettings) {
        require(validNetwork(value))
        check(prefs.edit().putInt("networkTimeout", value.timeout).putString("proxyMode", value.mode)
            .putString("proxyHost", value.host).putInt("proxyPort", value.port).commit())
        network = value
    }
    fun exportSettings(): String = JSONObject().put("format", 1).put("mode", style.mode).put("dynamic", style.dynamic)
        .put("seed", style.seed).put("black", style.black).put("font", style.font.toDouble()).put("weight", style.weight)
        .put("weightCustom", style.customWeight).put("glass", style.glass).put("translucent", style.translucent).put("motion", style.motion).put("haptics", style.haptics)
        .put("columns", columns).put("folderColumns", folderColumns).put("showMediaNames", showMediaNames).put("compactMediaNames", compactMediaNames).put("sortMode", sortMode.key).put("sortDescending", sortDescending).put("predictive", predictive).put("privacy", privacy).put("refresh", refresh)
        .put("cacheThreshold", cacheThreshold)
        .put("musicMiniPlayer", musicMiniPlayer).put("musicMiniRecovery", musicMiniRecovery.key)
        .put("musicKeepBackground", musicKeepBackground)
        .put("musicPreloadEnabled", musicPreloadEnabled).put("musicProgressWavy", musicProgressWavy)
        .put("musicLyricsFontSize", musicLyricsFontSize).put("musicLyricsWeight", musicLyricsWeight)
        .put("musicLyricsCenter", musicLyricsCenter).put("musicLyricsBlur", musicLyricsBlur)
        .put("musicLyricsStagger", musicLyricsStagger)
        .put("musicLyricKaraoke", musicLyricKaraoke.key).toString(2)

    fun importSettings(text: String) {
        val json = JSONObject(text)
        require(json.getInt("format") == 1)
        val value = style.copy(mode = json.getString("mode"), dynamic = json.getBoolean("dynamic"), seed = json.getInt("seed"),
            black = json.getBoolean("black"), font = json.getDouble("font").toFloat(), weight = json.getInt("weight"),
            customWeight = json.getBoolean("weightCustom"), glass = json.getBoolean("glass"), translucent = if (json.has("translucent")) json.getBoolean("translucent") else true,
            motion = json.getBoolean("motion"), haptics = json.getBoolean("haptics"))
        val newColumns = json.getInt("columns"); val newFolderColumns = if (json.has("folderColumns")) json.getInt("folderColumns") else 2; val newPredictive = json.getBoolean("predictive")
        val newPrivacy = json.getBoolean("privacy"); val newRefresh = json.getInt("refresh"); val newThreshold = json.getInt("cacheThreshold")
        val newSort = AlbumSort.from(json.optString("sortMode", "modified"))
        val newDescending = json.optBoolean("sortDescending", true)
        val newShowNames = json.optBoolean("showMediaNames", false)
        val newCompactNames = json.optBoolean("compactMediaNames", false)
        val newMusicMini = json.optBoolean("musicMiniPlayer", true)
        val newMusicMiniRecovery = MiniPlayerRecovery.from(json.optString("musicMiniRecovery", "manual"))
        val newMusicBackground = json.optBoolean("musicKeepBackground", true)
        val newMusicPreload = json.optBoolean("musicPreloadEnabled", true)
        val newMusicProgressWavy = json.optBoolean("musicProgressWavy", false)
        val newLyricSize = json.optInt("musicLyricsFontSize", 24).coerceIn(16, 40)
        val newLyricWeight = json.optInt("musicLyricsWeight", 600).coerceIn(100, 900)
        val newLyricCenter = json.optBoolean("musicLyricsCenter", true)
        val newLyricBlur = json.optBoolean("musicLyricsBlur", true)
        val newLyricStagger = json.optBoolean("musicLyricsStagger", true)
        val newLyricKaraoke = MusicKaraokeMode.from(json.optString("musicLyricKaraoke", MusicKaraokeMode.ALL.key))
        require(value.mode in listOf("system", "light", "dark") && value.seed in 0..7 && value.font.isFinite() && value.font in .85f..1.5f &&
            value.weight in -3..3 && newColumns in 1..5 && newFolderColumns in 1..5 && newRefresh in 0..240 && newThreshold in listOf(0, 16, 64, 256, 512, 1024))
        check(prefs.edit().putString("mode", value.mode).putBoolean("dynamic", value.dynamic).putInt("seed", value.seed)
            .putBoolean("black", value.black).putFloat("font", value.font).putInt("weight", value.weight).putBoolean("weightCustom", value.customWeight)
            .putBoolean("glass", value.glass).putBoolean("translucent", value.translucent).putBoolean("motion", value.motion).putBoolean("haptics", value.haptics)
            .putInt("columns", newColumns).putInt("folderColumns", newFolderColumns)
            .putBoolean("showMediaNames", newShowNames).putBoolean("compactMediaNames", newCompactNames)
            .putBoolean("musicMiniPlayer", newMusicMini)
            .putString("musicMiniRecovery", newMusicMiniRecovery.key)
            .putBoolean("musicKeepBackground", newMusicBackground)
            .putBoolean("musicPreloadEnabled", newMusicPreload).putBoolean("musicProgressWavy", newMusicProgressWavy)
            .putInt("musicLyricsFontSize", newLyricSize).putInt("musicLyricsWeight", newLyricWeight)
            .putBoolean("musicLyricsCenter", newLyricCenter).putBoolean("musicLyricsBlur", newLyricBlur)
            .putBoolean("musicLyricsStagger", newLyricStagger)
            .putString("musicLyricKaraoke", newLyricKaraoke.key)
            .putString("sortMode", newSort.key).putBoolean("sortDescending", newDescending)
            .putBoolean("predictive", newPredictive).putBoolean("privacy", newPrivacy)
            .putInt("refresh", newRefresh).putInt("cacheThreshold", newThreshold).commit())
        style = value; columns = newColumns; folderColumns = newFolderColumns
        showMediaNames = newShowNames; compactMediaNames = newCompactNames
        musicMiniPlayer = newMusicMini; musicMiniRecovery = newMusicMiniRecovery
        musicKeepBackground = newMusicBackground
        musicPreloadEnabled = newMusicPreload; musicProgressWavy = newMusicProgressWavy
        musicLyricsFontSize = newLyricSize; musicLyricsWeight = newLyricWeight
        musicLyricsCenter = newLyricCenter; musicLyricsBlur = newLyricBlur
        musicLyricsStagger = newLyricStagger
        musicLyricKaraoke = newLyricKaraoke
        sortMode = newSort; sortDescending = newDescending; predictive = newPredictive; privacy = newPrivacy; refresh = newRefresh; cacheThreshold = newThreshold
    }
    fun setIcon(context: Context, value: Int) {
        require(value in 0..2)
        val names = listOf("LauncherDefault", "LauncherLight", "LauncherDark")
        val pm = context.packageManager
        val previous = icon
        fun component(index: Int) = ComponentName(context, context.packageName + "." + names[index])
        try {
            pm.setComponentEnabledSetting(component(value), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            names.indices.filter { it != value }.forEach { pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
            check(prefs.edit().putInt("icon", value).commit())
            icon = value
        } catch (error: Exception) {
            runCatching { pm.setComponentEnabledSetting(component(previous), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP) }
            throw error
        }
    }

    private fun folderKey(connection: Connection): String = "folder_" + MessageDigest.getInstance("SHA-256")
        .digest((connection.url + "\u0000" + connection.user).toByteArray()).joinToString("") { "%02x".format(it) }
    fun defaultFolder(connection: Connection): String? = prefs.getString(folderKey(connection), null)
        ?.takeIf { folderChain(connection.root, it) != null }
    fun setDefaultFolder(connection: Connection, value: String?) {
        require(value == null || folderChain(connection.root, value) != null)
        check(prefs.edit().apply { if (value == null) remove(folderKey(connection)) else putString(folderKey(connection), value) }.commit())
    }
}

/** Returns every child folder between the configured root and the selected folder. */
fun folderChain(root: HttpUrl, selected: String): List<String>? {
    val target = selected.toHttpUrlOrNull() ?: return null
    if (!sameOrigin(root, target) || target.username.isNotEmpty() || target.password.isNotEmpty() || target.query != null || target.fragment != null) return null
    val prefix = root.encodedPath.trimEnd('/') + "/"
    val path = target.encodedPath.trimEnd('/') + "/"
    if (path == prefix) return emptyList()
    if (!path.startsWith(prefix)) return null
    val parts = path.removePrefix(prefix).trimEnd('/').split('/')
    if (parts.any { it.isEmpty() }) return null
    val rootSegments = root.pathSegments.filter { it.isNotEmpty() }.size
    if (target.pathSegments.filter { it.isNotEmpty() }.drop(rootSegments).any { it == "." || it == ".." || '/' in it || '\\' in it }) return null
    return parts.indices.map { index -> root.newBuilder().encodedPath(prefix + parts.take(index + 1).joinToString("/") + "/").build().toString() }
}
