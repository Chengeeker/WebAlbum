@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.chengeeker.lucent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.chengeeker.lucent.review.reviewPulse
import dev.chengeeker.lucent.review.HdrBadge
import dev.chengeeker.lucent.review.isHdrVideoTransfer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlinx.coroutines.delay

data class VideoSource(val label: String, val uri: String)

val LocalVideoDataSource = staticCompositionLocalOf<DataSource.Factory?> { null }

fun manualQualities(sources: List<VideoSource>) =
    sources
        .filter { it.label.isNotBlank() && it.label != "未知" && it.uri.isNotBlank() }
        .distinctBy { it.uri }
        .distinctBy { it.label }

fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

@Composable
fun LucentVideo(
    sources: List<VideoSource>,
    modifier: Modifier = Modifier,
    autoplay: Boolean = false,
    initialSpeed: Float = 1f,
    loop: Boolean = false,
    title: String = "视频播放",
    onSpeed: (Float) -> Unit = {},
    onExitFullscreen: () -> Unit = {},
    onControlsHeight: (Int) -> Unit = {},
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
    onHdrMetadataChanged: (Boolean) -> Unit = {},
) {
    val valid = remember(sources) { manualQualities(sources) }
    if (valid.isEmpty()) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("暂无可播放的视频", color = Color.White)
        }
        return
    }
    val context = LocalContext.current
    val activity = context.activity()
    val lifecycle = LocalLifecycleOwner.current
    val dataSource = LocalVideoDataSource.current
    val player = remember(dataSource) {
        ExoPlayer.Builder(context).apply { if (dataSource != null) setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)) }.build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
    }
    var quality by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var hdrVideo by remember(player) { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var speed by remember { mutableFloatStateOf(initialSpeed) }
    var fullscreen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf("") }
    var controls by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pulse by rememberUpdatedState(reviewPulse())
    val initialBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }
    LaunchedEffect(valid) {
        quality = quality.coerceIn(valid.indices)
        hdrVideo = false
        player.setMediaItem(MediaItem.fromUri(valid[quality].uri))
        player.prepare()
        player.playWhenReady = autoplay
        player.setPlaybackSpeed(initialSpeed)
    }
    LaunchedEffect(loop) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    DisposableEffect(player, lifecycle) {
        fun updateHdrMetadata() {
            val format = player.videoFormat ?: return
            val detected = isHdrVideoTransfer(format.colorInfo?.colorTransfer)
            hdrVideo = detected
            onHdrMetadataChanged(detected)
        }
        val listener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) { updateHdrMetadata() }
                override fun onVideoSizeChanged(videoSize: VideoSize) { updateHdrMetadata() }

                override fun onIsPlayingChanged(value: Boolean) {
                    playing = value
                }

                override fun onPlaybackStateChanged(state: Int) {
                    loading = state == Player.STATE_BUFFERING
                    duration = player.duration.coerceAtLeast(0)
                    updateHdrMetadata()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = true
                    loading = false
                }
            }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            onFullscreenChanged(false)
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = initialBrightness }
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    DisposableEffect(playing) {
        if (playing)
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            activity
                ?.window
                ?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity
                ?.window
                ?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            delay(if (playing) 250 else 1000)
        }
    }
    LaunchedEffect(hint) {
        if (hint.isNotBlank()) {
            delay(1300)
            hint = ""
        }
    }
    DisposableEffect(activity, fullscreen) {
        val window = activity?.window
        if (!fullscreen || window == null) {
            onDispose {}
        } else {
            val orientation = activity.requestedOrientation
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val behavior = controller.systemBarsBehavior
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            val status = WindowInsetsCompat.Type.statusBars()
            val navigation = WindowInsetsCompat.Type.navigationBars()
            val statusVisible = insets?.isVisible(status) != false
            val navigationVisible = insets?.isVisible(navigation) != false
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(status or navigation)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose {
                activity.requestedOrientation = orientation
                controller.systemBarsBehavior = behavior
                if (statusVisible) controller.show(status) else controller.hide(status)
                if (navigationVisible) controller.show(navigation) else controller.hide(navigation)
            }
        }
    }
    fun setFull(value: Boolean) {
        fullscreen = value
        controls = true
        onControlsVisibilityChanged(true)
        onFullscreenChanged(value)
        if (!value) {
            menu = ""
            onExitFullscreen()
        }
    }
    BackHandler(fullscreen) { setFull(false) }
    BoxWithConstraints(modifier.background(Color.Black)) {
        val showChrome = controls && (!fullscreen || (landscape && maxWidth > maxHeight))
        // TextureView can tone-map HDR into SDR; use SurfaceView only for
        // verified HDR tracks while keeping the established SDR overlay path.
        key(hdrVideo) {
            AndroidView(
                factory = {
                    val layout = if (hdrVideo) R.layout.lucent_video_hdr_surface else R.layout.lucent_video_texture
                    (android.view.LayoutInflater.from(it).inflate(layout, null, false) as PlayerView).apply {
                        useController = false
                        this.player = player
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.player = null },
            )
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(player, speed) {
                    // Compose detectTapGestures(onDoubleTap=...) defers the SINGLE-tap
                    // callback until the double-tap timeout. Respond on the first tap
                    // instead, then undo its visibility toggle on the second tap.
                    var lastTapTime = 0L
                    var lastTapPosition = Offset.Unspecified
                    val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
                    val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
                    detectTapGestures(
                        onTap = { point ->
                            val now = SystemClock.uptimeMillis()
                            val isDoubleTap = lastTapTime > 0L &&
                                now - lastTapTime <= doubleTapTimeout &&
                                lastTapPosition.isSpecified &&
                                (point - lastTapPosition).getDistance() <= doubleTapSlop
                            pulse()
                            controls = !controls
                            onControlsVisibilityChanged(controls)
                            if (isDoubleTap) {
                                lastTapTime = 0L
                                lastTapPosition = Offset.Unspecified
                                if (player.isPlaying) player.pause() else player.play()
                            } else {
                                lastTapTime = now
                                lastTapPosition = point
                            }
                        },
                        onLongPress = {
                            pulse()
                            player.setPlaybackSpeed(2f)
                            hint = "2× 临时倍速"
                        },
                        onPress = {
                            try {
                                tryAwaitRelease()
                            } finally {
                                player.setPlaybackSpeed(speed)
                            }
                        },
                    )
                }
                .pointerInput(player) {
                    var totalX = 0f
                    var totalY = 0f
                    var start = 0L
                    var left = false
                    var direction = 0
                    var startVolume = 0
                    var brightness = .5f
                    detectDragGestures(
                        onDragStart = { p ->
                            totalX = 0f
                            totalY = 0f
                            direction = 0
                            start = player.currentPosition
                            left = p.x < size.width / 2
                            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            brightness =
                                activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 }
                                    ?: .5f
                        },
                        onDragEnd = {
                            if (direction == 1)
                                player.seekTo(
                                    (start + totalX / size.width * 60_000)
                                        .toLong()
                                        .coerceIn(0, player.duration.coerceAtLeast(0))
                                )
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            totalX += delta.x
                            totalY += delta.y
                            if (direction == 0) direction = if (abs(totalX) > abs(totalY)) 1 else 2
                            if (direction == 1)
                                hint =
                                    "定位 ${((start+totalX/size.width*60_000).coerceAtLeast(0f)/1000).toInt()} 秒"
                            else if (left) {
                                val value = (brightness - totalY / size.height).coerceIn(.05f, 1f)
                                activity?.window?.let { w ->
                                    w.attributes = w.attributes.apply { screenBrightness = value }
                                }
                                hint = "亮度 ${(value*100).toInt()}%"
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val value =
                                    (startVolume - totalY / size.height * max)
                                        .toInt()
                                        .coerceIn(0, max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                hint = "音量 ${(value*100/max.coerceAtLeast(1))}%"
                            }
                        },
                    )
                }
        )
        if (loading)
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (error)
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("播放失败", color = Color.White)
                LucentAction(
                    "重试",
                    {
                        error = false
                        player.prepare()
                        player.play()
                    },
                )
            }
        if (showChrome && hdrVideo) {
            HdrBadge(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (fullscreen) 86.dp else 82.dp),
                description = "HDR 视频",
            )
        }
        if (hint.isNotBlank())
            Text(
                hint,
                Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = .7f))
                    .padding(12.dp),
                color = Color.White,
            )
        if (showChrome)
            LucentVideoBottom(
                Modifier.align(Alignment.BottomCenter).onSizeChanged { onControlsHeight(it.height) },
                position,
                duration,
                player.bufferedPosition,
                playing,
                speed,
                fullscreen,
                quality = valid.takeIf { it.size > 1 }?.get(quality)?.label,
                onPlay = {
                    if (playing) player.pause()
                    else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                onSpeed = { menu = "speed" },
                onQuality = { menu = "quality" },
                onFullscreen = { setFull(!fullscreen) },
                onSeek = { target ->
                    player.seekTo(target)
                    position = target
                },
            )
        if (showChrome && fullscreen)
            LucentVideoTop(
                modifier = Modifier.align(Alignment.TopCenter),
                title = title,
                fullscreen = true,
                close = { setFull(false) },
            )
        if (menu.isNotBlank())
            LucentVideoChoices(
                if (menu == "quality") "清晰度选择" else "播放倍速",
                if (menu == "quality") valid.map { it.label }
                else standardVideoSpeeds.map { "${it}X" },
                if (menu == "quality") valid[quality].label else "${speed}X",
                dismiss = { menu = "" },
            ) { label ->
                if (menu == "quality") {
                    val at = player.currentPosition
                    val resume = player.playWhenReady
                    quality = valid.indexOfFirst { it.label == label }.coerceAtLeast(0)
                    hdrVideo = false
                    player.setMediaItem(MediaItem.fromUri(valid[quality].uri), at)
                    player.prepare()
                    player.playWhenReady = resume
                } else {
                    speed = label.removeSuffix("X").toFloat()
                    player.setPlaybackSpeed(speed)
                    onSpeed(speed)
                }
            }
    }
}

@Composable
fun LivePhotoLayer(uri: String, playing: Boolean, onError: () -> Unit, onStopped: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val dataSource = LocalVideoDataSource.current
    val player = remember(uri, dataSource) {
        ExoPlayer.Builder(context).apply { if (dataSource != null) setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)) }
            .build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    }
    var rendered by remember(player) { mutableStateOf(false) }
    var prepared by remember(player) { mutableStateOf(false) }
    val currentError by rememberUpdatedState(onError)
    val currentStopped by rememberUpdatedState(onStopped)
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { rendered = true }
            override fun onPlayerError(error: PlaybackException) { currentError() }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { player.pause(); currentStopped() } }
        player.addListener(listener)
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing) {
        if (playing) {
            if (!prepared) { player.setMediaItem(MediaItem.fromUri(uri)); prepared = true }
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        } else player.pause()
    }
    AndroidView(factory = {
        (android.view.LayoutInflater.from(it).inflate(R.layout.lucent_video_texture, null, false) as PlayerView).apply {
            useController = false; isClickable = false; isFocusable = false; this.player = player
        }
    }, modifier = modifier.graphicsLayer { alpha = if (playing && rendered) 1f else 0f }, onRelease = { it.player = null })
}

private fun time(ms: Long) = "${ms/60000}:${(ms/1000%60).toString().padStart(2,'0')}"
