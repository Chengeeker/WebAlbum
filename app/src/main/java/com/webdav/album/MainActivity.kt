package com.webdav.album

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.Coil
import dev.chengeeker.lucent.*
import dev.chengeeker.lucent.review.GalleryMedia
import dev.chengeeker.lucent.review.GalleryOrigin
import dev.chengeeker.lucent.review.PageStack
import dev.chengeeker.lucent.review.ReviewGallery
import dev.chengeeker.lucent.review.GalleryOriginalLoader
import dev.chengeeker.lucent.review.ReviewRoute
import dev.chengeeker.lucent.review.ReviewBottomBar
import dev.chengeeker.lucent.review.ReviewHapticKind
import dev.chengeeker.lucent.review.reviewHaptic
import dev.chengeeker.lucent.review.hapticClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

@OptIn(ExperimentalAnimationApi::class, coil.annotation.ExperimentalCoilApi::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun AlbumScreen(app: AlbumApplication, prefs: AlbumPreferences, lock: AppLock, activity: MainActivity, session: LockSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf(app.connection) }
    var folders by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf(listOf<DavItem>()) }
    val snapshots = remember { mutableStateMapOf<String, List<DavItem>>() }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val music = remember(app) { app.music }
    val musicState by music.state.collectAsState()
    var musicExpanded by remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "未获得通知权限，系统可能不会显示音乐通知；可在系统设置中重新授权。"
    }
    fun requestMusicNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(connection) {
        music.onConnectionChanged(connection)
        if (connection != null) music.connect()
    }
    var gallery by remember { mutableStateOf<Pair<List<GalleryMedia>, Int>?>(null) }
    var origins by remember { mutableStateOf<Map<Int, GalleryOrigin>>(emptyMap()) }
    var pending by remember { mutableStateOf<GalleryMedia?>(null) }
    var saving by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var rangeOriginal by remember { mutableStateOf<Set<String>>(emptySet()) }
    var captureTimes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var properties by remember { mutableStateOf<List<DavItem>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val routes = remember { mutableStateListOf(ReviewRoute(0, "root")) }
    var nextKey by remember { mutableLongStateOf(1) }
    val dav = remember(prefs.network) { DavClient(app.client) }
    val folderCache = remember(connection, prefs.network) { FolderCache() }
    var folderJob by remember { mutableStateOf<Job?>(null) }
    val prefetchJob = remember { arrayOfNulls<Job>(1) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var initializedConnection by remember { mutableStateOf<Connection?>(null) }
    val locked = session.locked
    val videoFactory = remember(app) { androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttp3.Call.Factory { app.client.newCall(it) }) }
    val motionDetector = remember(context) { MotionPhotoDetector(context) }
    val resolveMotion: suspend (GalleryMedia) -> String? = remember(motionDetector) { { motionDetector.find(it) } }
    val originalLoader: GalleryOriginalLoader? = remember(app, connection) {
        connection?.let { config ->
            val originStore = WebDavGalleryOriginalLoader(context, app, config)
            val load: GalleryOriginalLoader = { item, report -> originStore.obtain(item, report) }
            load
        }
    }
    LaunchedEffect(folders) { selecting = false; selected.clear(); captureTimes = emptyMap() }
    LaunchedEffect(connection, entries, prefs.sortMode) {
        val current = connection
        if (current == null || prefs.sortMode != AlbumSort.CAPTURE) return@LaunchedEffect
        captureTimes = withContext(Dispatchers.IO) {
            val cache = Coil.imageLoader(context).diskCache
            entries.asSequence().filter { it.image }.mapNotNull { item ->
                runCatching {
                    cache?.openSnapshot(item.imageUrl(current))?.use { snapshot ->
                        val exif = android.media.ExifInterface(snapshot.data.toFile().absolutePath)
                        val raw = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
                        raw?.let {
                            val format = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).apply { isLenient = false }
                            format.parse(it)?.time?.let { timestamp -> item.url to timestamp }
                        }
                    }
                }.getOrNull()
            }.toMap()
        }
    }
    fun download(items: List<DavItem>) {
        if (saving || items.isEmpty()) return
        val tree = prefs.downloadTree
        if (tree.isBlank()) { message = "请先在设置 → 存储设置中选择保存目录。"; return }
        saving = true
        scope.launch {
            var completed = 0
            val failures = mutableListOf<String>()
            items.forEach { item ->
                try { withContext(Dispatchers.IO) { saveToTree(context, app.client, tree, item) }; completed++ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failures += item.name }
            }
            saving = false
            message = if (failures.isEmpty()) "已保存 $completed 个文件。" else "已保存 $completed 个文件，${failures.size} 个失败。请检查目录授权、空间及网络后重试。"
        }
    }
    fun open(name: String) { if (!loading && routes.last().name != name) routes.add(ReviewRoute(nextKey++, name)) }
    fun back() { if (!loading && routes.size > 1) routes.removeAt(routes.lastIndex) }
    fun home(config: Connection): List<String> = prefs.defaultFolder(config)?.let { folderChain(config.root, it) }.orEmpty()
    fun cancelPrefetch() { prefetchJob[0]?.cancel(); prefetchJob[0] = null }
    fun load(path: List<String>, fallback: Boolean = false, force: Boolean = false) {
        if (loading && folderJob == null) return
        val config = connection ?: return
        cancelPrefetch()
        val previous = folders.lastOrNull().orEmpty()
        if (!force && !loading && folderError == null && snapshots.containsKey(previous)) folderCache.put(previous, entries)
        folderJob?.cancel()
        val next = path.lastOrNull().orEmpty()
        val cached = if (force) null else folderCache.get(next)
        val shown = cached ?: if (path == folders) entries else emptyList()
        if (path != folders) { selecting = false; selected.clear() }
        snapshots.keys.toList().filter { it != previous && it != next }.forEach { snapshots.remove(it) }
        snapshots[next] = shown; entries = shown; folders = path; folderError = null
        if (cached != null) { folderJob = null; loading = false; return }
        loading = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val listing = dav.listFolderAsync(config, path, fallback)
                if (app.connection != config) return@launch
                if (listing.usedFallback) message = "默认文件夹暂时无法打开，已返回 WebDAV 根目录；原设置仍保留。"
                val actual = listing.path.lastOrNull().orEmpty()
                folderCache.put(actual, listing.entries)
                entries = listing.entries; snapshots[actual] = listing.entries; folders = listing.path
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { folderError = error.message ?: "连接失败，请检查网络和服务器设置" }
            finally { if (folderJob === currentCoroutineContext()[Job]) { folderJob = null; loading = false } }
        }
        folderJob = job
        job.start()
    }
    LaunchedEffect(locked) {
        if (!locked && !session.cacheChecked) {
            val threshold = prefs.cacheThreshold
            if (threshold > 0) try {
                withContext(Dispatchers.IO) {
                    val cache = Coil.imageLoader(context).diskCache
                    val originalDir = context.cacheDir.resolve("gallery-originals")
                    val originalBytes = originalDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
                    if ((cache?.size ?: 0L) + originalBytes > threshold.toLong() * 1024 * 1024) {
                        cache?.clear()
                        originalDir.deleteRecursively()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "启动时清理缓存失败，已保留现有缓存。" }
            session.cacheChecked = true
        }
    }
    LaunchedEffect(connection, locked, session.cacheChecked) { connection?.let { if (!locked && session.cacheChecked && initializedConnection != it) { initializedConnection = it; load(home(it), fallback = true) } } }
    val routeName = routes.lastOrNull()?.name
    LaunchedEffect(connection, prefs.network, folders, entries, locked, loading, routeName, gallery) {
        val config = connection ?: return@LaunchedEffect
        if (locked || loading || routeName != "root" || gallery != null || app.connection != config) return@LaunchedEffect
        val job = currentCoroutineContext()[Job]
        prefetchJob[0] = job
        try {
            folderCache.prefetch(entries, { app.connection == config }) { folder -> dav.listAsync(config.root, folder.url.toHttpUrl()) }
        } finally { if (prefetchJob[0] === job) prefetchJob[0] = null }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = pending; pending = null
        if (uri != null && item != null) {
            saving = true
            scope.launch {
                val url = item.video.ifBlank { item.url }.substringBefore('#')
                val size = entries.firstOrNull { it.url == url }?.version?.substringAfterLast('|')?.toLongOrNull()
                val result = runCatching { downloadImage(app.client, url, context.cacheDir.resolve("download-parts"), size) {
                    context.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
                } }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                message = result.fold({ "文件已保存" }, { it.message ?: "保存失败，请重试" }); saving = false
            }
        }
    }
    val predictive = Build.VERSION.SDK_INT >= 34 && prefs.predictive && LocalLucentStyle.current.motion
    BackHandler(enabled = selecting && gallery == null && routes.size == 1) { selecting = false; selected.clear() }
    val canPageBack = !selecting && gallery == null && routes.size > 1 && !loading
    BackHandler(enabled = !selecting && gallery == null && (loading || folders.isNotEmpty() || routes.size > 1) && !(canPageBack && predictive)) {
        if (!loading && routes.size > 1) back() else if (routes.size == 1 && folders.isNotEmpty() && (!loading || folderJob != null)) load(folders.dropLast(1))
    }
    PredictiveBackHandler(enabled = canPageBack && predictive) { events ->
        val route = routes.last()
        try {
            events.collect { if (routes.lastOrNull() === route) route.backProgress = it.progress }
            if (routes.lastOrNull() === route) back()
        } finally { route.backProgress = null }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageStack(routes.toList()) { route ->
            when (route.name) {
                "root" -> {
                    val config = connection
                    if (config == null) ConnectionPage(null, loading, null) { next ->
                        scope.launch {
                            loading = true
                            cancelPrefetch(); folderCache.clear()
                            val old = app.connection; app.connection = next
                            val result = runCatching { withContext(Dispatchers.IO) { dav.list(next.root, next.root) } }
                            result.onSuccess { list ->
                                runCatching { Secrets(context).save(next) }.onSuccess {
                                    connection = next; initializedConnection = next; entries = list; folders = emptyList(); snapshots.clear(); snapshots[""] = list
                                }.onFailure { app.connection = old; message = "保存连接失败：${it.message}" }
                            }.onFailure { app.connection = old; message = it.message ?: "连接失败，请检查网络和服务器设置" }
                            loading = false
                            if (connection == next) load(home(next), fallback = true)
                        }
                    }
                    else BrowsePage(config, folders, snapshots, prefs.columns, prefs.folderColumns, prefs.showMediaNames, prefs.compactMediaNames,
                        musicState.current != null && prefs.musicMiniPlayer, music, prefs.sortMode, prefs.sortDescending, captureTimes, loading, folderError, selecting, selected,
                        onBack = { load(folders.dropLast(1)) }, onFolder = { load(folders + it.url) },
                        onRefresh = { cancelPrefetch(); folderCache.clear(); load(folders, force = true) }, onSettings = { open("settings") },
                        onSelection = { item -> selecting = true; if (item.url in selected) selected.remove(item.url) else selected.add(item.url) },
                        onSelectMode = { selecting = !selecting; selected.clear() },
                        onColumnsChange = { prefs.updateColumns(it) },
                        onSortChange = { prefs.updateSort(it) },
                        onRangeSelection = { start, end, fresh ->
                            if (!loading) {
                                if (fresh) rangeOriginal = selected.toSet()
                                val ordered = orderedMedia(entries.filter { it.image || it.video }, prefs.sortMode, prefs.sortDescending, captureTimes)
                                val updated = rangeSelection(rangeOriginal, ordered, start, end)
                                selecting = true
                                selected.clear()
                                selected.addAll(updated)
                            }
                        },
                        onSelectAll = { selected.clear(); selected.addAll(entries.filter { it.image || it.video || it.audio }.map { it.url }) },
                        onAction = { action ->
                            val items = entries.filter { it.url in selected }
                            if (!loading && !saving && items.isNotEmpty()) when (action) { 0 -> download(items); 1 -> properties = items; 2 -> confirmDelete = true }
                        },
                        onGallery = { media, index, source -> origins = source; gallery = media to index },
                        onAudio = { tracks, index ->
                            val activeConnection = connection
                            if (activeConnection != null && !locked) {
                                requestMusicNotification()
                                scope.launch {
                                    try { music.playQueue(tracks, index, activeConnection) }
                                    catch (error: Exception) { message = error.message ?: "播放失败，请检查音频格式或 WebDAV 连接" }
                                }
                            }
                        })
                }
                "settings" -> SettingsHome(prefs, connection, lock, ::back, ::open)
                "musicSettings" -> MusicSettingsPage(prefs, ::back, ::requestMusicNotification)
                "appearance" -> AppearancePage(prefs, locked, ::back, ::open)
                "columns" -> ColumnsPage(prefs, false, ::back)
                "folderColumns" -> ColumnsPage(prefs, true, ::back)
                "icons" -> IconSettingsPage(prefs, ::back) { message = it }
                "refresh" -> RefreshSettingsPage(prefs, activity, ::back)
                "lock" -> AppLockSettingsPage(lock, activity, locked, ::back) { message = it }
                "network" -> NetworkSettingsPage(prefs, app, ::back) { message = it }
                "storage" -> StorageSettingsPage(prefs, ::back) { message = it }
                "backup" -> SettingsBackupPage(prefs, locked, ::back) { message = it }
                "cache" -> CacheSettingsPage(prefs, locked, ::back) { finished ->
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { Coil.imageLoader(context).diskCache?.clear()
                            Coil.imageLoader(context).memoryCache?.clear()
                            context.cacheDir.resolve("gallery-originals").deleteRecursively() } }
                        message = result.fold({ "图片缓存已清理" }, { "清理失败，请稍后重试" })
                        finished(result.isSuccess)
                        if (result.isSuccess) load(folders)
                    }
                }
                "folder" -> connection?.let { config -> FolderPickerPage(config, app, ::back) { selected ->
                    runCatching { prefs.setDefaultFolder(config, selected) }.onSuccess {
                        while (routes.size > 1) routes.removeAt(routes.lastIndex)
                        load(selected?.let { folderChain(config.root, it) }.orEmpty(), fallback = true)
                    }.onFailure { message = "无法保存默认文件夹" }
                } }
                "connection" -> ConnectionPage(connection, loading, ::back) { next ->
                    scope.launch {
                        loading = true
                        cancelPrefetch(); folderCache.clear()
                        val old = app.connection; app.connection = next
                        val result = runCatching { withContext(Dispatchers.IO) { dav.list(next.root, next.root) } }
                        var saved = false
                        result.onSuccess { list ->
                            runCatching { Secrets(context).save(next) }.onSuccess {
                                connection = next; initializedConnection = next; entries = list; folders = emptyList(); snapshots.clear(); snapshots[""] = list
                                while (routes.size > 1) routes.removeAt(routes.lastIndex)
                                saved = true
                            }.onFailure { app.connection = old; message = "保存连接失败：${it.message}" }
                        }.onFailure { app.connection = old; message = it.message ?: "连接失败，请检查网络和服务器设置" }
                        loading = false
                        if (saved) load(home(next), fallback = true)
                    }
                }
            }
        }
        GreenMusicPlayerTransition(
            music = music, prefs = prefs, expanded = musicExpanded,
            available = !locked && gallery == null,
            onExpand = { musicExpanded = true },
            onCollapse = { musicExpanded = false },
            modifier = Modifier.fillMaxSize(),
        )
    gallery?.takeIf { !locked }?.let { (media, index) ->
            CompositionLocalProvider(LocalVideoDataSource provides videoFactory) {
                ReviewGallery(media, index, origins, onClose = { gallery = null }, saving = saving,
                    resolveMotion = resolveMotion, originalLoader = originalLoader, onSave = { item ->
                    val entry = entries.firstOrNull { it.url == (item.video.ifBlank { item.url }).substringBefore('#') }
                    if (entry != null && prefs.downloadTree.isNotBlank()) download(listOf(entry))
                    else { pending = item; save.launch(item.description.ifBlank { "image.jpg" }) }
                })
            }
        }
    }
    message?.takeIf { !locked }?.let { LucentResult("WebAlbum", it) { message = null } }
    properties?.takeIf { !locked }?.let { items ->
        var dimensions by remember(items) { mutableStateOf<String?>(null) }
        val config = connection
        LaunchedEffect(items, config) {
            val item = items.singleOrNull()
            if (item?.image == true && config != null) dimensions = withContext(Dispatchers.IO) {
                runCatching {
                    Coil.imageLoader(context).diskCache?.openSnapshot(item.imageUrl(config))?.use { snapshot ->
                        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(snapshot.data.toFile().absolutePath, opts)
                        if (opts.outWidth > 0 && opts.outHeight > 0) "${opts.outWidth} × ${opts.outHeight}" else null
                    }
                }.getOrNull()
            }
        }
        AlertDialog(onDismissRequest = { properties = null }, title = { Text(if (items.size == 1) "文件属性" else "已选择 ${items.size} 个文件") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { items.forEach { item ->
                val parts = item.version.split('|'); val size = parts.getOrNull(2)?.toLongOrNull()
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text("格式：${item.name.substringAfterLast('.', "未知").uppercase()}\n大小：${size?.let { "${it} 字节" } ?: "未知"}\n修改时间：${parts.getOrNull(1)?.ifBlank { "未知" } ?: "未知"}" +
                    if (items.size == 1 && item.image) "\n图片尺寸：${dimensions ?: "未缓存或无法读取"}" else "", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
            } } }, confirmButton = { TextButton(onClick = hapticClick { properties = null }) { Text("关闭") } })
    }
    if (confirmDelete && !locked) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除服务器文件？") },
        text = { Text("将从 WebDAV 服务器永久删除选中的 ${selected.size} 个文件，此操作无法撤销。") },
        confirmButton = { TextButton(onClick = hapticClick {
            confirmDelete = false
            val config = connection
            val items = entries.filter { it.url in selected }
            if (config != null && items.isNotEmpty()) scope.launch {
                loading = true
                cancelPrefetch(); folderCache.clear()
                val deleted = mutableSetOf<String>()
                for (item in items) try {
                    withContext(Dispatchers.IO) { dav.delete(config.root, (folders.lastOrNull() ?: config.url).toHttpUrl(), item) }
                    deleted += item.url
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                entries = entries.filterNot { it.url in deleted }; snapshots[folders.lastOrNull().orEmpty()] = entries
                selected.removeAll(deleted); loading = false
                message = "已确认删除 ${deleted.size} 个文件，${items.size - deleted.size} 个失败或状态未确认。请刷新目录核对。"
            }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = hapticClick { confirmDelete = false }) { Text("取消") } })
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BrowsePage(config: Connection, folders: List<String>, snapshots: Map<String, List<DavItem>>, columns: Int, folderColumns: Int,
    showMediaNames: Boolean, compactMediaNames: Boolean, miniVisible: Boolean, music: WebAlbumMusicPlayback, sortMode: AlbumSort, sortDescending: Boolean, captureTimes: Map<String, Long>, loading: Boolean, folderError: String?, selecting: Boolean, selected: List<String>, onBack: () -> Unit, onFolder: (DavItem) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit,
    onSelection: (DavItem) -> Unit, onSelectMode: () -> Unit, onSelectAll: () -> Unit, onAction: (Int) -> Unit,
    onColumnsChange: (Int) -> Unit, onSortChange: (AlbumSort) -> Unit, onRangeSelection: (Int, Int, Boolean) -> Unit,
    onGallery: (List<GalleryMedia>, Int, Map<Int, GalleryOrigin>) -> Unit,
    onAudio: (List<MusicTrack>, Int) -> Unit) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val motion = LocalLucentStyle.current.motion
    val visibleMusicTags by music.discoveredTags.collectAsState()
    AnimatedContent(targetState = folders, transitionSpec = {
        if (!motion) EnterTransition.None togetherWith ExitTransition.None
        else if (targetState.size > initialState.size)
            (slideInHorizontally(tween(320, easing = CubicBezierEasing(.215f, .61f, .355f, 1f))) { it * direction } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(220, easing = CubicBezierEasing(.55f, .055f, .675f, .19f))) { -it / 6 * direction } + fadeOut(tween(220)))
        else
            (slideInHorizontally(tween(280, easing = CubicBezierEasing(.215f, .61f, .355f, 1f))) { -it / 6 * direction } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260, easing = CubicBezierEasing(.55f, .055f, .675f, .19f))) { it * direction } + fadeOut(tween(220)))
    }, label = "folder-transition") { path ->
        val list = snapshots[path.lastOrNull().orEmpty()].orEmpty()
        val folderItems = remember(list) { list.filter { it.directory } }
        val mediaItems = remember(list, sortMode, sortDescending, captureTimes) {
            orderedMedia(list.filter { it.image || it.video }, sortMode, sortDescending, captureTimes)
        }
        val photos = remember(mediaItems, config) { GalleryItems(mediaItems, config) }
        val audioEntries = remember(list, sortMode, sortDescending) {
            orderedMedia(list.filter { it.audio }, sortMode, sortDescending)
        }
        val audioQueue = remember(audioEntries) {
            audioEntries.map { MusicTrack(it.url, it.version, it.name) }
        }
        val sourceMap = remember(photos) { mutableMapOf<Int, GalleryOrigin>() }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        var moreOpen by remember { mutableStateOf(false) }
        var columnsOpen by remember { mutableStateOf(false) }
        var sortOpen by remember { mutableStateOf(false) }
        val pressTick = reviewHaptic(ReviewHapticKind.LONG_PRESS)
        val audioTapTick = reviewHaptic()
        val selectionTick = reviewHaptic(ReviewHapticKind.SELECTION)
        val titleTick = reviewHaptic()
        BackdropHost(overlay = {
            LucentHeader(if (selecting) "已选择 ${selected.size} 项" else if (path.isEmpty()) "WebAlbum" else folderName(path.last()),
                onBack = if (path.isNotEmpty()) onBack else null,
                onTitleDoubleTap = { titleTick(); scope.launch { gridState.animateScrollToItem(0) } },
                actions = {
                    if (selecting) {
                        LucentIcon(Icons.Outlined.SelectAll, "全选", onClick = onSelectAll)
                        LucentIcon(Icons.Outlined.Close, "取消选择", onClick = onSelectMode)
                    } else {
                        Box {
                            LucentIcon(Icons.Outlined.GridView, "每行媒体数量", onClick = { columnsOpen = true })
                            DropdownMenu(expanded = columnsOpen, onDismissRequest = { columnsOpen = false },
                                modifier = Modifier.widthIn(min = 188.dp, max = 240.dp),
                                shape = RoundedCornerShape(22.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text("每行图片与视频", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                                (1..5).forEach { value ->
                                    DropdownMenuItem(text = { Text("${value} 列") },
                                        leadingIcon = { Icon(if (columns == value) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null) },
                                        onClick = hapticClick { columnsOpen = false; onColumnsChange(value) })
                                }
                            }
                        }
                        Box {
                            LucentIcon(Icons.Outlined.MoreVert, "更多", onClick = { moreOpen = true; sortOpen = false })
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false },
                                modifier = Modifier.widthIn(min = 208.dp, max = 240.dp),
                                shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                DropdownMenuItem(text = { Text("多选") }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                                    onClick = hapticClick { moreOpen = false; onSelectMode() })
                                DropdownMenuItem(text = { Text("刷新") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                    onClick = hapticClick { moreOpen = false; onRefresh() })
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                DropdownMenuItem(text = { Text("排序方式") },
                                    leadingIcon = { Icon(Icons.Outlined.Sort, null) },
                                    trailingIcon = { Icon(Icons.Outlined.ChevronRight, "进入排序菜单") },
                                    onClick = hapticClick { moreOpen = false; sortOpen = true })
                                DropdownMenuItem(text = { Text("设置") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = hapticClick { moreOpen = false; onSettings() })
                            }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false },
                                modifier = Modifier.widthIn(min = 208.dp, max = 240.dp),
                                shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                DropdownMenuItem(text = { Text("排序方式", style = MaterialTheme.typography.titleSmall) },
                                    leadingIcon = { Icon(Icons.Outlined.ArrowBack, null) },
                                    onClick = hapticClick { sortOpen = false; moreOpen = true })
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                AlbumSort.entries.forEach { mode ->
                                    DropdownMenuItem(text = { Text(mode.label) },
                                        leadingIcon = { if (sortMode == mode) Icon(Icons.Outlined.Check, null) },
                                        trailingIcon = {
                                            if (sortMode == mode) Icon(
                                                if (sortDescending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                                                if (sortDescending) "降序" else "升序"
                                            )
                                        },
                                        onClick = hapticClick { sortOpen = false; onSortChange(mode) })
                                }
                            }
                        }
                    }
                })
            if (selecting && path == folders) Box(Modifier.align(Alignment.BottomCenter)) { ReviewBottomBar(-1, floating = true, glass = LocalLucentStyle.current.glass, translucent = LocalLucentStyle.current.translucent,
                names = listOf("下载", "属性", "删除"), inactive = listOf(Icons.Outlined.Download, Icons.Outlined.Info, Icons.Outlined.Delete),
                active = listOf(Icons.Outlined.Download, Icons.Outlined.Info, Icons.Outlined.Delete), select = onAction) }
        }) {
            val gridUnits = (1..20).first { it % columns == 0 && it % folderColumns == 0 }
            val mediaStart = folderItems.size + if (folderItems.isNotEmpty() && mediaItems.isNotEmpty()) 1 else 0
            PullToRefreshBox(
                isRefreshing = loading && path == folders,
                onRefresh = { if (!loading && path == folders) { titleTick(); onRefresh() } },
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp),
            ) {
            LazyVerticalGrid(columns = GridCells.Fixed(gridUnits), state = gridState,
                modifier = Modifier.fillMaxSize().pointerInput(path, mediaItems, loading) {
                    fun mediaAt(position: androidx.compose.ui.geometry.Offset): Int? {
                        val cell = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                            position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
                                position.y >= info.offset.y && position.y < info.offset.y + info.size.height
                        } ?: return null
                        return (cell.index - mediaStart).takeIf { it in mediaItems.indices }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pressed = awaitLongPressOrCancellation(down.id)
                        if (pressed != null && !loading && path == folders) {
                            val anchor = mediaAt(pressed.position)
                            if (anchor != null) {
                                pressed.consume()
                                pressTick()
                                onRangeSelection(anchor, anchor, true)
                                var latest = anchor
                                while (true) {
                                    // Consume UP before child click handlers run.
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    val index = mediaAt(change.position)
                                    if (index != null && index != latest) {
                                        latest = index
                                        selectionTick()
                                        onRangeSelection(anchor, latest, false)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                },
                contentPadding = PaddingValues(start = 13.dp, end = 13.dp, top = 13.dp,
                    bottom = (if (selecting) 96.dp else if (miniVisible) 112.dp else 21.dp) +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                items(folderItems, key = { it.url }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(gridUnits / folderColumns) }) { folder ->
                    FolderCard(folder, folderColumns, !loading && path == folders) { onFolder(folder) }
                }
                if (folderItems.isNotEmpty() && photos.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(2.dp)) }
                items(photos.size, key = { mediaItems[it].url }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(gridUnits / columns) }) { index ->
                    val item = mediaItems[index]
                    val namedCard = showMediaNames && columns <= 3
                    val openOrSelect: () -> Unit = {
                        if (!loading && path == folders) {
                            if (selecting) onSelection(item) else onGallery(photos, index, sourceMap)
                        }
                    }
                    Column(
                        Modifier.padding(3.dp).then(
                            if (namedCard) Modifier.clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            else Modifier
                        )
                    ) {
                        Box {
                            GalleryThumbnail(
                                photos[index],
                                sourceMap.getOrPut(index) { GalleryOrigin(12.dp) },
                                Modifier.fillMaxWidth().aspectRatio(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                                onLongClick = null,
                                onClick = openOrSelect,
                            )
                            if (selecting) Icon(
                                if (item.url in selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                "选择 ${item.name}",
                                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape)
                                    .background(Color.Black.copy(alpha = .45f)).padding(2.dp),
                                tint = Color.White,
                            )
                        }
                        if (namedCard) {
                            Box(
                                Modifier.fillMaxWidth().height(if (columns <= 2) 54.dp else 33.dp)
                                    .lucentClick(onClick = openOrSelect)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    displayMediaFilename(item.name, compactMediaNames),
                                    maxLines = if (columns <= 2) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = if (columns <= 2) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (audioEntries.isNotEmpty()) {
                    item(key = "music-heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("音乐 · " + audioEntries.size + " 首",
                                style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(audioEntries.size, key = { "music-" + audioEntries[it].url },
                        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { index ->
                        val item = audioEntries[index]
                        val musicTrack = audioQueue[index]
                        val mediaTags = visibleMusicTags[config.videoScope + "|" + musicTrack.scopeId]
                        LaunchedEffect(config.videoScope, item.url, item.version) {
                            try {
                                music.warmTags(musicTrack, config)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The filename and default artwork remain usable offline.
                            }
                        }
                        val track = remember(musicTrack, mediaTags) {
                            if (mediaTags == null) musicTrack else musicTrack.copy(
                                title = mediaTags.title.ifBlank { musicTrack.title },
                                artist = mediaTags.artist.ifBlank { musicTrack.artist },
                                album = mediaTags.album.ifBlank { musicTrack.album },
                                artwork = mediaTags.cover,
                            )
                        }
                        LucentCard(Modifier.padding(horizontal = 3.dp, vertical = 3.dp)) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (!loading && path == folders) {
                                                audioTapTick()
                                                if (selecting) onSelection(item) else onAudio(audioQueue, index)
                                            }
                                        },
                                        onLongClick = { if (!loading) { pressTick(); onSelection(item) } }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GreenMusicCover(track, Modifier.size(48.dp), 14.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(track.artist.ifBlank { item.name.substringAfterLast('.', "").uppercase() + " · WebDAV" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(if (selecting) {
                                    if (item.url in selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked
                                } else Icons.Outlined.PlayArrow, "播放或选择")
                            }
                        }
                    }
                }
                if (!loading && folderError != null && path == folders) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    LucentCard { Column(Modifier.padding(16.dp)) { Text("无法读取文件夹", style = MaterialTheme.typography.titleMedium); Text(folderError, color = MaterialTheme.colorScheme.error); LucentAction("重试", onClick = onRefresh) } }
                }
                if (!loading && folderError == null && list.none { it.directory || it.image || it.video || it.audio }) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    LucentEmpty("这里还没有媒体", "此目录没有子文件夹或支持的媒体格式。")
                }
            }
            }
        }
    }
}

@Composable
private fun FolderCard(folder: DavItem, columns: Int, enabled: Boolean, onClick: () -> Unit) {
    val compact = columns >= 4
    val iconSize = when (columns) { 1 -> 34.dp; 2 -> 32.dp; 3 -> 28.dp; 4 -> 24.dp; else -> 21.dp }
    val textSize = when (columns) { 1 -> 15.sp; 2 -> 13.sp; 3 -> 12.sp; 4 -> 11.sp; else -> 10.sp }
    LucentCard(Modifier.padding(3.dp)) {
        val content = Modifier.fillMaxWidth().heightIn(min = if (columns == 1) 68.dp else if (compact) 80.dp else 104.dp)
            .lucentClick(enabled = enabled, onClick = onClick).padding(if (compact) 8.dp else 12.dp)
        if (columns == 1) Row(content, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp)); Text(folder.name, maxLines = 2, fontSize = textSize, lineHeight = textSize * 1.3f)
        } else Column(content, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(if (compact) 5.dp else 8.dp)); Text(folder.name, maxLines = 2, fontSize = textSize, lineHeight = textSize * 1.3f)
        }
    }
}

@Composable
private fun ConnectionPage(initial: Connection?, loading: Boolean, onCancel: (() -> Unit)?, onConnect: (Connection) -> Unit) {
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var user by remember(initial) { mutableStateOf(initial?.user.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    BackdropHost(overlay = { LucentHeader("WebDAV 连接", onCancel) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).imePadding().padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LucentCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("连接你的照片", style = MaterialTheme.typography.titleMedium)
                    Text("输入 WebDAV 服务器地址与账号，连接成功后即可浏览文件夹。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(url, { url = it; error = null }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("https://example.com/dav/") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = error != null, supportingText = error?.let { { Text(it) } })
                    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Text("建议使用 HTTPS。HTTP 连接会以明文传输账号和图片。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    LucentAction("连接", onClick = {
                        val parsed = validConnection(url)
                        if (parsed == null) error = "请输入不含账号、查询参数的 HTTP 或 HTTPS 地址"
                        else onConnect(Connection(parsed.toString().trimEnd('/') + "/", user, password))
                    }, modifier = Modifier.fillMaxWidth(), enabled = !loading)
                }
            }
        }
    }
}
