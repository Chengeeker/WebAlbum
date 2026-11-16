package com.webdav.album

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.chengeeker.lucent.review.hapticClick
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.Player

/** Music album cover and 24dp corner treatment adapted from GreenMusic.Player. */
@Composable
internal fun GreenMusicCover(track: MusicTrack, modifier: Modifier = Modifier, radius: Dp = 16.dp) {
    val artwork = track.artwork
    // Decode in the background. A large embedded JPEG should never stall Compose
    // when the user swipes between songs or opens a folder.
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, key1 = artwork
    ) {
        value = if (artwork == null || artwork.size > 768 * 1024) null else withContext(Dispatchers.Default) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(artwork, 0, artwork.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(artwork, 0, artwork.size, opts)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(radius))
        .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Crossfade(targetState = image, animationSpec = tween(180), label = "song-artwork-fade") { picture ->
            if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.46f),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private fun elapsed(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
private fun currentLyric(lyrics: MusicLyrics, now: Long, fallback: String): String =
    lyrics.lines.lastOrNull { it.timeMs <= now }?.text
        ?: lyrics.plain.lineSequence().firstOrNull { it.isNotBlank() }
        ?: fallback.ifBlank { "暂无歌词" }

@Composable
internal fun GreenMusicMiniPlayer(
    music: WebAlbumMusicPlayback, prefs: AlbumPreferences, onOpen: () -> Unit,
    modifier: Modifier = Modifier, artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    val state by music.state.collectAsState()
    val track = state.current ?: return
    val lyric by music.lyrics.collectAsState()
    val position by music.position.collectAsState()
    val duration by music.duration.collectAsState()
    val scope = rememberCoroutineScope()
    val nextIndex = remember(state.index, state.songs.size, state.shuffle) {
        if (state.songs.isEmpty()) 0 else (state.index + 1) % state.songs.size
    }
    Surface(
        modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp, shadowElevation = 5.dp
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GreenMusicSwipeFrame(
                    key = track.scopeId,
                    hasNeighbor = { state.songs.size > 1 },
                    onChange = { step ->
                        val next = if (step > 0) nextIndex else (state.index - 1 + state.songs.size) % state.songs.size
                        scope.launch { music.select(next) }
                    },
                    modifier = Modifier.weight(1f).height(66.dp)
                ) { step ->
                    val song = state.songs.getOrNull(if (step == 0) state.index else
                        (state.index + step + state.songs.size) % state.songs.size) ?: track
                    Row(Modifier.fillMaxSize().clickable(onClick = hapticClick(onOpen))
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GreenMusicCover(song, (if (step == 0) artworkModifier else Modifier).size(42.dp), 10.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, modifier = if (step == 0) titleModifier else Modifier,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (step == 0) currentLyric(lyric, position, song.artist) else song.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = hapticClick { scope.launch { music.toggle() } },
                    modifier = Modifier.size(48.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) "暂停" else "播放", Modifier.size(28.dp))
                }
                IconButton(onClick = hapticClick(onOpen), modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.QueueMusic, "打开播放器与队列")
                }
                Spacer(Modifier.width(4.dp))
            }
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f,1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {},
                strokeCap = StrokeCap.Butt
            )
        }
    }
}

@Composable
private fun GreenMusicLyricPanel(
    lyric: MusicLyrics, position: Long, prefs: AlbumPreferences, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lyric.lines.isEmpty()) {
        Box(modifier.verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(lyric.plain.ifBlank { "此歌曲没有可读取的歌词" },
                textAlign = if (prefs.musicLyricsCenter) TextAlign.Center else TextAlign.Start,
                fontSize = prefs.musicLyricsFontSize.sp,
                lineHeight = (prefs.musicLyricsFontSize * 1.5f).sp,
                fontWeight = FontWeight(prefs.musicLyricsWeight),
                color = MaterialTheme.colorScheme.onSurface)
        }
        return
    }
    val current = remember(lyric.lines, position) {
        lyric.lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
    }
    val state = rememberLazyListState()
    LaunchedEffect(lyric.lines, current, prefs.musicLyricsStagger, prefs.style.motion) {
        if (!state.isScrollInProgress) {
            if (prefs.musicLyricsStagger && prefs.style.motion) state.animateScrollToItem(current)
            else state.scrollToItem(current)
        }
    }
    LazyColumn(
        modifier = modifier.clipToBounds(), state = state,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 108.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(lyric.lines) { i, line ->
            val selected = i == current
            val alpha by animateFloatAsState(
                if (selected) 1f else if (prefs.musicLyricsBlur) .48f else .65f,
                animationSpec = tween(280, easing = CubicBezierEasing(.645f,.045f,.355f,1f)), label = "GreenMusic lyric fade"
            )
            val scale by animateFloatAsState(if (selected) 1f else .94f,
                animationSpec = tween(280), label = "GreenMusic lyric scale")
            Text(line.text,
                Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                    .clickable(onClick = hapticClick { onSeek(line.timeMs) })
                    .padding(vertical = 4.dp),
                textAlign = if (prefs.musicLyricsCenter) TextAlign.Center else TextAlign.Start,
                fontSize = prefs.musicLyricsFontSize.sp,
                fontWeight = FontWeight(if (selected) prefs.musicLyricsWeight else
                    (prefs.musicLyricsWeight - 100).coerceAtLeast(100)),
                lineHeight = (prefs.musicLyricsFontSize * 1.3f).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MusicPlayerButtons(music: WebAlbumMusicPlayback, state: MusicPlaybackState) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = hapticClick { scope.launch { music.toggleShuffle() } },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor =
                if (state.shuffle) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        ) { Icon(Icons.Rounded.Shuffle, "随机播放") }
        IconButton(onClick = hapticClick { scope.launch { music.previous() } }) {
            Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(32.dp))
        }
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = hapticClick { scope.launch { music.toggle() } }),
            contentAlignment = Alignment.Center) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放或暂停",
                Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
            if (state.loading) CircularProgressIndicator(
                Modifier.size(57.dp), strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .45f)
            )
        }
        IconButton(onClick = hapticClick { scope.launch { music.next() } }) {
            Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(32.dp))
        }
        FilledTonalIconButton(
            onClick = hapticClick { scope.launch { music.cycleRepeat() } },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor =
                if (state.repeat == Player.REPEAT_MODE_OFF) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer)
        ) { Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "循环模式") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GreenMusicPlayerScreen(music: WebAlbumMusicPlayback, prefs: AlbumPreferences,
    onBack: () -> Unit, modifier: Modifier = Modifier,
    expanded: Boolean = true,
    artworkModifier: Modifier = Modifier, titleModifier: Modifier = Modifier) {
    val state by music.state.collectAsState()
    val track = state.current
    val lyric by music.lyrics.collectAsState()
    val loadingLyrics by music.lyricsLoading.collectAsState()
    val position by music.position.collectAsState()
    val duration by music.duration.collectAsState()
    val scope = rememberCoroutineScope()
    var showLyrics by remember(track?.scopeId) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var draggedPosition by remember { mutableStateOf<Float?>(null) }
    // Preserve the cover and title as the only shared moving elements.
    // Everything else exits quickly while the hero flight continues to travel.
    val nonCoreAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (prefs.style.motion) tween(
            durationMillis = if (expanded) 165 else 70,
            delayMillis = if (expanded) 95 else 0,
        ) else snap(),
        label = "greenmusic-noncore-fast-exit",
    )
    LaunchedEffect(expanded) {
        if (!expanded) {
            showQueue = false
            showSpeed = false
        }
    }
    BackHandler(enabled = expanded, onBack = if (showLyrics) {{ showLyrics = false }} else onBack)
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < .5f
    val seed by produceState<Int?>(initialValue = null, key1 = track?.scopeId, key2 = track?.artwork) {
        value = track?.artwork?.let { runCatching { greenMusicArtworkSeed(it) }.getOrNull() }
    }
    val artworkScheme = remember(seed, dark, base) {
        seed?.let { greenMusicArtworkScheme(it, dark) } ?: base
    }
    MaterialTheme(colorScheme = artworkScheme) {
    val gradient = Brush.verticalGradient(listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = .46f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        MaterialTheme.colorScheme.surface
    ))
    Box(modifier.fillMaxSize()
        .drawBehind { drawRect(gradient, alpha = nonCoreAlpha) }
        .systemBarsPadding()) {
        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("选择一首歌曲开始聆听")
            }
            return@Box
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp).graphicsLayer { alpha = nonCoreAlpha },
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(onBack)) { Icon(Icons.Rounded.KeyboardArrowDown, "收起播放器") }
                Spacer(Modifier.weight(1f))
                Text("正在播放", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = hapticClick { showQueue = true }) { Icon(Icons.Rounded.QueueMusic, "播放队列") }
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp)) {
                Text(track.title, modifier = titleModifier, fontSize = 24.sp, lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(track.artist.ifBlank { "未知歌手" },
                    modifier = Modifier.graphicsLayer { alpha = nonCoreAlpha },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GreenMusicSwipeFrame(
                key = Pair(track.scopeId, showLyrics), hasNeighbor = { dir ->
                    if (dir > 0) !showLyrics else showLyrics
                }, onChange = { showLyrics = it > 0 },
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { step ->
                val show = expanded && (if (step == 0) showLyrics else !showLyrics)
                if (show) GreenMusicLyricPanel(lyric, position, prefs,
                    onSeek = { ms -> scope.launch { music.seek(ms) } },
                    Modifier.fillMaxSize().graphicsLayer { alpha = nonCoreAlpha })
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val coverSize = minOf(maxWidth, 320.dp)
                        Box(artworkModifier.size(coverSize)) {
                            Crossfade(targetState = track, animationSpec = tween(220),
                                label = "fullscreen-song-crossfade") { song ->
                                GreenMusicCover(song, Modifier.fillMaxSize()
                                    .clickable(onClick = hapticClick { showLyrics = true }), 28.dp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(if (loadingLyrics) "正在读取歌曲歌词…" else currentLyric(lyric, position,track.artist),
                        Modifier.fillMaxWidth().graphicsLayer { alpha = nonCoreAlpha }
                            .clickable(onClick = hapticClick { showLyrics = true })
                            .padding(vertical = 8.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Keep its layout measured so the album cover and title do not jump.
            Column(Modifier.graphicsLayer { alpha = nonCoreAlpha }) {
            Spacer(Modifier.height(12.dp))
            Slider(
                value = draggedPosition ?: (if (duration > 0) position.toFloat().coerceIn(0f,duration.toFloat()) else 0f),
                onValueChange = { draggedPosition = it },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                onValueChangeFinished = {
                    draggedPosition?.let { scope.launch { music.seek(it.toLong()) } }
                    draggedPosition = null
                },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(elapsed((draggedPosition?.toLong() ?: position)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(elapsed(duration), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            MusicPlayerButtons(music, state)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onClick = hapticClick { showLyrics = !showLyrics }) {
                    Icon(Icons.Outlined.Lyrics, if (showLyrics) "封面" else "歌词")
                }
                Box {
                    IconButton(onClick = hapticClick { showSpeed = true }) {
                        Icon(Icons.Rounded.Speed, "倍速")
                    }
                    DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }) {
                        listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(text = { Text(speed.toString() + "×") },
                                onClick = {
                                    showSpeed = false
                                    scope.launch { music.speed(speed) }
                                })
                        }
                    }
                }
                IconButton(onClick = hapticClick { showQueue = true }) {
                    Icon(Icons.Rounded.QueueMusic, "播放队列")
                }
            }
            state.error?.let { Text(it, Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (showQueue) ModalBottomSheet(onDismissRequest = { showQueue = false }) {
        Text("播放队列 · " + state.songs.size + " 首",
            Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding()) {
            itemsIndexed(state.songs) { index, song ->
                ListItem(
                    headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(song.artist.ifBlank { song.filename }, maxLines = 1) },
                    leadingContent = { GreenMusicCover(song, Modifier.size(42.dp), 12.dp) },
                    trailingContent = { if (index == state.index) Icon(Icons.Rounded.GraphicEq, "正在播放") },
                    modifier = Modifier.clickable(onClick = hapticClick {
                        showQueue = false; scope.launch { music.select(index) }
                    })
                )
            }
        }
    }    }
}
