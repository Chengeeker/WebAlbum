package com.webdav.album

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class MusicPlaybackState(
    val songs: List<MusicTrack> = emptyList(), val index: Int = -1,
    val playing: Boolean = false, val loading: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF, val shuffle: Boolean = false,
    val speed: Float = 1f, val error: String? = null,
) { val current: MusicTrack? get() = songs.getOrNull(index) }

/** GreenMusic MediaController controls, adapted to WebAlbum's existing WebDAV auth. */
@UnstableApi
class WebAlbumMusicPlayback(private val app: AlbumApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(MusicPlaybackState())
    val state = mutable.asStateFlow()
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val lyrics = MutableStateFlow(MusicLyrics())
    val lyricsLoading = MutableStateFlow(false)
    // Cache small metadata/artwork for rows before the user starts playback.
    private val metadata = MutableStateFlow<Map<String, EmbeddedMusicTags>>(emptyMap())
    val discoveredTags = metadata.asStateFlow()
    private val metadataSlots = Semaphore(2)
    // Concurrent list rows and player callbacks must await the SAME probe,
    // rather than treating a second caller as already loaded.
    private val pendingTags = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var neighborJob: Job? = null
    private var lastNeighborSignature: String? = null
    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var connecting: Deferred<MediaController>? = null
    private var connectionScope: String? = null
    private var metadataJob: Job? = null
    private var lastMetadataKey: String? = null
    private val musicReader = WebDavMusicMetadata(app)

    private fun media(track: MusicTrack): MediaItem = MediaItem.Builder()
        .setUri(track.url).setMediaId(track.scopeId)
        .setCustomCacheKey(app.connection?.let { WebAlbumMusicStreamCache.key(track, it) }
            ?: track.scopeId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).apply {
                track.artwork?.takeIf { it.size in 64..786432 }?.let { art ->
                    setArtworkData(art, 3) // front cover, matches GreenMusic's tag writing
                }
            }.build()).build()

    private fun tagKey(track: MusicTrack, connection: Connection) =
        connection.videoScope + "|" + track.scopeId

    fun cachedTags(track: MusicTrack, connection: Connection): EmbeddedMusicTags? =
        metadata.value[tagKey(track, connection)]

    private fun enriched(track: MusicTrack, connection: Connection): MusicTrack {
        val tag = cachedTags(track, connection) ?: return track
        return track.copy(
            title = tag.title.ifBlank { track.title },
            artist = tag.artist.ifBlank { track.artist },
            album = tag.album.ifBlank { track.album },
            artwork = tag.cover ?: track.artwork,
        )
    }

    /** Called only by visible list rows and the active track; bounded, deduplicated. */
    suspend fun warmTags(track: MusicTrack, connection: Connection) {
        if (app.connection != connection) return
        val key = tagKey(track, connection)
        if (metadata.value.containsKey(key)) return
        val (probe, started) = synchronized(pendingTags) {
            val existing = pendingTags[key]
            if (existing != null) existing to false else {
                val created = CompletableDeferred<Unit>()
                pendingTags[key] = created
                created to true
            }
        }
        if (!started) {
            probe.await() // The display row must not reveal raw filenames too early.
            return
        }
        try {
            metadataSlots.withPermit {
                if (app.connection != connection) return@withPermit
                val tags = musicReader.read(track, connection)
                if (app.connection != connection) return@withPermit
                val previous = metadata.value
                val updated = previous + (key to tags)
                metadata.value = if (updated.size > 64) updated.entries.toList().takeLast(64)
                    .associate { it.key to it.value } else updated
                // Update the current card without replacing the active ExoPlayer MediaItem:
                // replaceMediaItem() would reprepare the audio and interrupt playback.
                val playing = mutable.value
                if (playing.songs.any { it.scopeId == track.scopeId }) {
                    mutable.value = playing.copy(songs = playing.songs.map {
                        if (it.scopeId == track.scopeId) enriched(it, connection) else it
                    })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Artwork/tag probes also run from visible list-row LaunchedEffects.
            // Optional metadata failures must not become uncaught UI crashes.
            android.util.Log.w("WebAlbumMusic", "Optional tag probe failed", error)
        } finally {
            probe.complete(Unit)
            synchronized(pendingTags) { pendingTags.remove(key) }
        }
    }

    private fun prefetchAdjacent() {
        val connection = app.connection ?: return
        if (connection.videoScope != connectionScope) return
        if (!AlbumPreferences(app).musicPreloadEnabled) {
            neighborJob?.cancel()
            lastNeighborSignature = null
            return
        }
        val snapshot = mutable.value
        val signature = connection.videoScope + "|" + snapshot.current?.scopeId +
            "|" + snapshot.index + "|" + snapshot.songs.size
        // The Media3 transition callback and explicit next/previous actions can
        // both fire; never restart the very same warm-up unnecessarily.
        if (signature == lastNeighborSignature) return
        lastNeighborSignature = signature
        neighborJob?.cancel()
        neighborJob = scope.launch {
            app.musicStreamCache.warmNeighbors(snapshot.songs, snapshot.index, connection)
        }
    }

    fun connect() {
        if (controller != null || connecting != null) return
        val future = MediaController.Builder(app, SessionToken(app,
            ComponentName(app, WebAlbumMusicService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(player: MediaController) {
                    if (controller === player) {
                        controller = null
                        connecting = null
                        positionJob?.cancel()
                        mutable.value = mutable.value.copy(
                            playing = false, loading = false,
                            error = "播放服务连接中断，点击播放可重新连接",
                        )
                    }
                }
            }).buildAsync()
        val ready = CompletableDeferred<MediaController>()
        connecting = ready
        future.addListener({
            try {
                val player = future.get()
                controller = player
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        try {
                            refresh()
                            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                                updateMetadata()
                                prefetchAdjacent()
                            }
                        } catch (error: Exception) {
                            android.util.Log.e("WebAlbumMusic", "MediaController event failed", error)
                            mutable.value = mutable.value.copy(
                                error = "播放状态更新失败：" + (error.localizedMessage ?: "请重试"),
                            )
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        mutable.value = mutable.value.copy(error = error.localizedMessage ?: "无法播放此音频")
                    }
                })
                refresh()
                positionJob?.cancel()
                positionJob = scope.launch {
                    while (isActive) {
                        try {
                            if (controller === player) refreshPosition()
                            else break
                        } catch (error: Exception) {
                            // A stale controller may disconnect between frames:
                            // this poller must never take down the main process.
                            android.util.Log.w("WebAlbumMusic",
                                "Playback position poll failed", error)
                            if (controller === player) {
                                controller = null
                                connecting = null
                                mutable.value = mutable.value.copy(
                                    playing = false, loading = false,
                                    error = "播放服务暂时不可用，请重试",
                                )
                            }
                            break
                        }
                        delay(250)
                    }
                }
                connecting = null
                ready.complete(player)
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = "无法连接播放服务：" + error.message)
                connecting = null
                ready.completeExceptionally(error)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private suspend fun ready(): MediaController {
        connect()
        return controller ?: requireNotNull(connecting).await()
    }
    private fun refreshPosition() {
        val p = controller ?: return
        position.value = p.currentPosition.coerceAtLeast(0)
        duration.value = p.duration.takeIf { it > 0 } ?: 0
    }
    private fun refresh() {
        val p = controller ?: return
        val previous = mutable.value
        val recovered = if (p.mediaItemCount != previous.songs.size ||
            (p.mediaItemCount > 0 && previous.songs.getOrNull(p.currentMediaItemIndex)?.scopeId != p.currentMediaItem?.mediaId)) {
            (0 until p.mediaItemCount).map { n ->
                val item = p.getMediaItemAt(n)
                val url = item.localConfiguration?.uri?.toString().orEmpty()
                val rawName = android.net.Uri.decode(url.substringAfterLast('/'))
                val version = item.mediaId.removePrefix(url + "|")
                MusicTrack(url, version, rawName,
                    item.mediaMetadata.title?.toString()?.ifBlank { rawName } ?: rawName,
                    item.mediaMetadata.artist?.toString().orEmpty(),
                    item.mediaMetadata.albumTitle?.toString().orEmpty())
            }
        } else previous.songs
        if (connectionScope == null && recovered.isNotEmpty() && app.connection != null &&
            recovered.all { track -> track.url.toHttpUrlOrNull()?.let { sameOrigin(app.connection!!.root,it) } == true })
            connectionScope = app.connection?.videoScope
        val index = if (p.mediaItemCount == 0) -1 else p.currentMediaItemIndex
        mutable.value = previous.copy(songs = recovered,
            index = index.coerceIn(-1, recovered.lastIndex),
            playing = p.playWhenReady, loading = p.playbackState == Player.STATE_BUFFERING,
            repeat = p.repeatMode, shuffle = p.shuffleModeEnabled, speed = p.playbackParameters.speed,
        )
        refreshPosition()
    }

    suspend fun playQueue(tracks: List<MusicTrack>, start: Int, connection: Connection) {
        if (tracks.isEmpty() || start !in tracks.indices || app.connection != connection) return
        require(tracks.all { it.url.toHttpUrlOrNull()?.let { url -> sameOrigin(connection.root, url) } == true })
        val p = ready()
        connectionScope = connection.videoScope
        lastMetadataKey = null
        mutable.value = MusicPlaybackState(songs = tracks, index = start, loading = true)
        val prepared = tracks.map { enriched(it, connection) }
        mutable.value = MusicPlaybackState(songs = prepared, index = start, loading = true)
        p.setMediaItems(prepared.map(::media), start, 0L)
        p.prepare(); p.play()
        refresh()
        updateMetadata()
        prefetchAdjacent()
    }
    /** MediaController can disconnect during foreground/background transitions.
     * Never let a Media3 command exception escape an onClick coroutine on main. */
    private suspend fun safeControl(action: String, operation: suspend () -> Unit) {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("WebAlbumMusic", "MediaController action failed: $action", error)
            mutable.value = mutable.value.copy(
                loading = false,
                error = "播放控制失败，请重试：" + (error.localizedMessage ?: error.javaClass.simpleName),
            )
            // Reconnect on the NEXT action instead of reusing a disconnected
            // controller. A stale completed Deferred must be cleared as well.
            positionJob?.cancel()
            runCatching { controller?.release() }
            controller = null
            connecting = null
        }
    }

    suspend fun toggle() = safeControl("toggle") {
        val p = ready()
        if (p.playWhenReady) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            mutable.value = mutable.value.copy(error = null)
            p.play()
        }
        refresh()
    }
    suspend fun pause() = safeControl("pause") { controller?.pause(); refresh() }
    fun pauseForBackground() { scope.launch { pause() } }
    suspend fun seek(ms: Long) = safeControl("seek") {
        val p = ready()
        p.seekTo(ms.coerceIn(0L, duration.value.coerceAtLeast(0L)))
        refreshPosition()
    }
    suspend fun next() = safeControl("next") {
        val p = ready()
        if (p.mediaItemCount > 0) {
            if (p.hasNextMediaItem()) p.seekToNextMediaItem() else p.seekTo(0, 0L)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play(); refresh(); updateMetadata(); prefetchAdjacent()
        }
    }
    suspend fun previous() = safeControl("previous") {
        val p = ready()
        if (p.currentPosition > 3000L) p.seekTo(0L) else if (p.mediaItemCount > 0) {
            if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(p.mediaItemCount - 1, 0L)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
        refresh(); updateMetadata(); prefetchAdjacent()
    }
    suspend fun select(index: Int) = safeControl("select") {
        val p = ready()
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0L)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play(); refresh(); updateMetadata(); prefetchAdjacent()
        }
    }
    suspend fun cycleRepeat() = safeControl("repeat") {
        val p = ready()
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refresh()
    }
    suspend fun toggleShuffle() = safeControl("shuffle") {
        val p = ready()
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        refresh()
    }
    suspend fun speed(value: Float) = safeControl("speed") {
        ready().setPlaybackSpeed(value.coerceIn(.5f, 2f))
        refresh()
    }
    suspend fun stop(clear: Boolean = false) = safeControl("stop") {
        controller?.stop()
        if (clear) {
            controller?.clearMediaItems()
            metadataJob?.cancel()
            neighborJob?.cancel()
            lastNeighborSignature = null
            lastMetadataKey = null
            mutable.value = MusicPlaybackState()
            position.value = 0L; duration.value = 0L; lyrics.value = MusicLyrics()
        } else refresh()
    }
    fun onConnectionChanged(connection: Connection?) {
        val next = connection?.videoScope
        if (connectionScope != null && connectionScope != next) {
            connectionScope = null
            neighborJob?.cancel()
            lastNeighborSignature = null
            metadata.value = emptyMap()
            scope.launch { stop(clear = true) }
        }
    }
    private fun updateMetadata() {
        val track = mutable.value.current ?: return
        val conn = app.connection ?: return
        if (conn.videoScope != connectionScope) return
        val key = conn.videoScope + "/" + track.scopeId
        if (key == lastMetadataKey) return
        lastMetadataKey = key
        metadataJob?.cancel()
        lyrics.value = MusicLyrics()
        metadataJob = scope.launch {
            lyricsLoading.value = true
            try {
                warmTags(track, conn)
                val tags = cachedTags(track, conn) ?: EmbeddedMusicTags()
                if (app.connection != conn || mutable.value.current?.scopeId != track.scopeId) return@launch
                val updated = track.copy(title = tags.title.ifBlank { track.title },
                    artist = tags.artist.ifBlank { track.artist }, album = tags.album.ifBlank { track.album },
                    artwork = tags.cover)
                val index = mutable.value.index
                mutable.value = mutable.value.copy(songs = mutable.value.songs.toMutableList().apply {
                    if (index in indices) set(index, updated)
                })
                // Never replace the currently playing MediaItem. Media3 would
                // reprepare its source, causing an audible pause on each song.
                val words = withContext(Dispatchers.IO) { musicReader.lyrics(track, conn, tags) }
                if (app.connection == conn && mutable.value.current?.scopeId == track.scopeId) lyrics.value = words
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing tags or lyrics cannot prevent playback. */ }
            finally { lyricsLoading.value = false }
        }
    }
}
