package com.webdav.album

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.chengeeker.lucent.review.hapticClick
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.Player

/**
 * Share decoded covers between the outgoing and pre-rendered incoming swipe
 * frames. Compose creates separate slots for content(0) and content(step);
 * without a shared cache the incoming cover briefly becomes a placeholder
 * again after Media3 confirms the song change.
 */
private object SharedMusicCoverCache {
    private val covers = object : LinkedHashMap<String, Pair<ByteArray, ImageBitmap>>(
        16, .75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<ByteArray, ImageBitmap>>?,
        ): Boolean = size > 12
    }
    // Media3 may materialize the same JPEG/PNG as a new ByteArray instance
    // after a track change. Reference-only comparison discarded a decoded
    // neighbor and caused the album art to pop in a second time.
    @Synchronized fun get(id: String, bytes: ByteArray? = null): ImageBitmap? {
        val entry = covers[id] ?: return null
        return entry.second.takeIf {
            bytes == null || entry.first === bytes || entry.first.contentEquals(bytes)
        }
    }
    @Synchronized fun put(id: String, bytes: ByteArray, image: ImageBitmap) {
        covers[id] = bytes to image
    }
}

/** Bounded off-main-thread decoding for both player and list artwork. */
internal fun decodeMusicArtwork(artwork: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
    if (artwork == null || artwork.size !in 64..(768 * 1024)) return null
    return runCatching {
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

/** List cells can supply predecoded art so icon/title/artist appear together. */
@Composable
internal fun GreenMusicCover(
    track: MusicTrack,
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    decodedForList: androidx.compose.ui.graphics.ImageBitmap? = null,
    listArtworkReady: Boolean = false,
    fadeArtwork: Boolean = true,
) {
    val artwork = track.artwork
    val cachedCover = remember(track.scopeId, artwork, listArtworkReady) {
        if (!listArtworkReady)
            SharedMusicCoverCache.get(track.scopeId, artwork)
        else null
    }
    // The state itself must be keyed by track. produceState(initialValue=...)
    // keeps the previous value across key changes, causing a wrong-cover
    // flash even if the next cover had already been decoded next door.
    val decoded = remember(track.scopeId, artwork, listArtworkReady) {
        mutableStateOf(cachedCover)
    }
    LaunchedEffect(track.scopeId, artwork, listArtworkReady) {
        decoded.value = if (listArtworkReady) null
            else if (artwork == null) SharedMusicCoverCache.get(track.scopeId)
            else SharedMusicCoverCache.get(track.scopeId, artwork)
                ?: withContext(Dispatchers.Default) { decodeMusicArtwork(artwork) }
                    ?.also { SharedMusicCoverCache.put(track.scopeId, artwork, it) }
    }
    val image = if (listArtworkReady) decodedForList else decoded.value
    Box(modifier.clip(RoundedCornerShape(radius))
        .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (listArtworkReady || !fadeArtwork) {
            // Mini swipe already moves the complete artwork into position.
            // Fading it again when it becomes content(0) made the cover blink.
            // Lists also display their predecoded artwork without a fade.
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.46f),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            Crossfade(targetState = image, animationSpec = tween(180), label = "song-artwork-fade") { picture ->
                if (picture != null) Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.46f),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
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

/** Compact mini-player: no progress rail; only metadata and playback actions. */
internal val miniPlayerCompactRowHeight = 58.dp

@Composable
internal fun GreenMusicMiniPlayer(
    music: WebAlbumMusicPlayback, onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier, artworkModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    renderGlassBackground: Boolean = true,
) {
    val state by music.state.collectAsState()
    val track = state.current ?: return
    val glassTokens = rememberMiniPlayerGlassTokens()
    val lyric by music.lyrics.collectAsState()
    val position by music.position.collectAsState()
    val scope = rememberCoroutineScope()
    var showMiniQueue by remember { mutableStateOf(false) }
    val nextIndex = remember(state.index, state.songs.size, state.shuffle) {
        if (state.songs.isEmpty()) 0 else (state.index + 1) % state.songs.size
    }
    // Down swipes belong to the bar; horizontal gestures stay in the song frame.
    val latestDismiss by rememberUpdatedState(hapticClick(onDismiss))
    // Gesture coordinates must stay anchored while the glass moves. The old
    // pointerInput lived INSIDE this translating Surface: local pointer
    // coordinates changed as it moved, feeding back into the offset and
    // making the player shake violently halfway through a downward drag.
    var dismissDrag by remember { mutableFloatStateOf(0f) }
    var restoreJob by remember { mutableStateOf<Job?>(null) }
    fun springBack() {
        restoreJob?.cancel()
        restoreJob = scope.launch {
            Animatable(dismissDrag).animateTo(0f, tween(160)) {
                dismissDrag = value
            }
        }
    }
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    BackHandler(enabled = showMiniQueue) { showMiniQueue = false }
    // Attach this observer to the STATIC outer Box, not to the translating
    // Surface. Only touches beginning in the compact bottom row can dismiss;
    // the inline queue above remains free for normal vertical scrolling.
    val miniBarSwipe = Modifier.pointerInput(Unit) {
        val touchSlop = 7.dp.toPx()
        val dismissThreshold = 14.dp.toPx()
        val minimumFling = 550.dp.toPx()
        val barTouchHeight = (miniPlayerCompactRowHeight + 8.dp).toPx()
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            // Parent coordinates do NOT move with the child glass layer.
            if (down.position.y < size.height - barTouchHeight) {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var verticalClaimed = false
            var horizontalClaimed = false
            var completed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!verticalClaimed && !horizontalClaimed) {
                    when (miniPlayerSwipeIntent(dx, dy, touchSlop)) {
                        1 -> {
                            verticalClaimed = true
                            restoreJob?.cancel()
                        }
                        -1 -> horizontalClaimed = true
                    }
                }
                if (verticalClaimed) {
                    // Follow the finger in one direct GPU translation; NO
                    // per-frame tween and NO alpha oscillation on the glass.
                    dismissDrag = (dy.coerceAtLeast(0f) * .65f)
                        .coerceAtMost(44.dp.toPx())
                    change.consume()
                }
                if (!change.pressed) {
                    completed = true
                    if (verticalClaimed && isMiniPlayerDismissSwipe(
                            dx, dy, dismissThreshold,
                            velocityY = tracker.calculateVelocity().y,
                            minimumFling = minimumFling,
                        )
                    ) {
                        latestDismiss() // hide the mini, keep media playing
                    } else if (dismissDrag > 0f) {
                        springBack()
                    }
                    break
                }
            } while (true)
            // If Android cancels the gesture, restore the anchored card.
            if (!completed && verticalClaimed && dismissDrag > 0f) {
                springBack()
            }
        }
    }
    // No second capsule outline while the fullscreen fill boundary is moving.
    // Keep the shared album art/title in this tree, but hand ALL background,
    // refraction rim and shadow painting to the morphing fullscreen RoundRect.
    // The normal Review glass returns only after that shape fully collapses.
    val glassDecoration = if (renderGlassBackground)
        Modifier.miniPlayerGlassBackground(glassTokens)
    else Modifier
    Box(
        modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
            .then(miniBarSwipe)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                translationY = dismissDrag
            }.then(glassDecoration),
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            contentColor = glassTokens.primary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column {
                AnimatedVisibility(
                    visible = showMiniQueue,
                    enter = expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(280, easing = CubicBezierEasing(.215f, .61f, .355f, 1f)),
                    ) + fadeIn(tween(150)),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(260, easing = CubicBezierEasing(.215f, .61f, .355f, 1f)),
                    ) + fadeOut(tween(140)),
                ) {
                    InlineMiniMusicQueue(
                        songs = state.songs,
                        selectedIndex = state.index,
                        tokens = glassTokens,
                        height = inlineMiniQueueHeight(state.songs.size, screenHeight),
                        onSelect = { index ->
                            showMiniQueue = false
                            scope.launch { music.select(index) }
                        },
                        onClose = { showMiniQueue = false },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GreenMusicSwipeFrame(
                    key = track.scopeId,
                    hasNeighbor = { state.songs.size > 1 },
                    onChange = { step ->
                        val next = if (step > 0) nextIndex else (state.index - 1 + state.songs.size) % state.songs.size
                        scope.launch { music.select(next) }
                    },
                    modifier = Modifier.weight(1f).height(miniPlayerCompactRowHeight)
                ) { step ->
                    val song = state.songs.getOrNull(if (step == 0) state.index else
                        (state.index + step + state.songs.size) % state.songs.size) ?: track
                    Row(Modifier.fillMaxSize().clickable(onClick = hapticClick(onOpen))
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GreenMusicCover(
                            song,
                            (if (step == 0) artworkModifier else Modifier).size(38.dp),
                            radius = 10.dp,
                            fadeArtwork = false,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, modifier = if (step == 0) titleModifier else Modifier,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = glassTokens.primary)
                            Text(if (step == 0) {
                                    val currentLine = currentMusicLyricLine(lyric, position)
                                    val original = currentLyric(lyric, position, song.artist)
                                    currentLine?.translation?.takeIf { it.isNotBlank() }
                                        ?.let { original + " / " + it } ?: original
                                } else song.artist,
                                color = glassTokens.secondary,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = hapticClick { scope.launch { music.toggle() } },
                    modifier = Modifier.size(48.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(22.dp),
                        strokeWidth = 2.dp, color = glassTokens.primary)
                    else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) "暂停" else "播放", Modifier.size(28.dp),
                        tint = glassTokens.primary)
                }
                IconButton(onClick = hapticClick { showMiniQueue = !showMiniQueue },
                    modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.QueueMusic, "打开播放队列", tint = glassTokens.primary)
                }
                Spacer(Modifier.width(4.dp))
                } // compact playback controls
            } // one unified glass Surface: queue + mini player
        }
    }
}

/** Glyph boxes returned by Compose may have reversed horizontal bounds (RTL).
 * A negative width must never be passed as the upper bound of coerceIn(). */
internal fun karaokeGlyphWidth(width: Float): Float =
    if (width.isFinite()) kotlin.math.abs(width) else 0f

internal fun karaokeGlyphFill(remaining: Float, glyphWidth: Float): Float =
    if (remaining.isFinite()) remaining.coerceIn(0f, karaokeGlyphWidth(glyphWidth)) else 0f

/**
 * The reference GreenMusic parser and karaoke painter use one primary line
 * with its translated companion, not two competing active indices.
 */
@Composable
private fun KaraokeMusicLine(
    text: String,
    words: List<MusicLyricWord>,
    positionMs: Float,
    fontSize: Int,
    weight: Int,
    center: Boolean,
    base: Color,
    accent: Color,
) {
    if (words.isEmpty()) {
        Text(text, Modifier.fillMaxWidth(),
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.3f).sp,
            fontWeight = FontWeight(weight),
            color = base)
        return
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 0)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize.sp, lineHeight = (fontSize * 1.3f).sp,
            fontWeight = FontWeight(weight),
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
        val layout = remember(text, style, width, measurer) {
            measurer.measure(
                androidx.compose.ui.text.AnnotatedString(text), style = style,
                constraints = androidx.compose.ui.unit.Constraints(minWidth = width, maxWidth = width),
            )
        }
        val ranges = remember(words, text) {
            var cursor = 0
            words.map { word ->
                val begin = cursor
                cursor = (cursor + word.text.length).coerceAtMost(text.length)
                begin until cursor
            }
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { layout.size.height.toDp() })) {
            drawText(layout, color = base.copy(alpha = .55f))
            val mask = Path()
            words.forEachIndexed { wordIndex, word ->
                val endMs = word.endMs ?: word.timeMs
                val fraction = when {
                    positionMs <= word.timeMs -> 0f
                    endMs <= word.timeMs -> 1f
                    else -> ((positionMs - word.timeMs) / (endMs - word.timeMs)).coerceIn(0f, 1f)
                }
                if (fraction <= 0f) return@forEachIndexed
                val range = ranges[wordIndex]
                if (range.isEmpty()) return@forEachIndexed
                if (fraction >= 1f) {
                    mask.addPath(layout.getPathForRange(range.first, range.last + 1))
                } else {
                    val boxes = range.map { layout.getBoundingBox(it) }
                    var remaining = boxes.sumOf { karaokeGlyphWidth(it.width).toDouble() }.toFloat() * fraction
                    boxes.forEachIndexed { i, rect ->
                        // Compose can return a reversed-width box for bidirectional
                        // glyphs; coerceIn(0f, rect.width) previously crashed here.
                        val fill = karaokeGlyphFill(remaining, rect.width)
                        if (fill > 0f) {
                            val rtl = layout.getBidiRunDirection((range.first + i)
                                .coerceAtMost(text.lastIndex)) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                            val left = minOf(rect.left, rect.right)
                            val right = maxOf(rect.left, rect.right)
                            mask.addRect(androidx.compose.ui.geometry.Rect(
                                if (rtl) right - fill else left,
                                rect.top,
                                if (rtl) right else left + fill,
                                rect.bottom,
                            ))
                        }
                        remaining -= fill
                    }
                }
            }
            clipPath(mask) { drawText(layout, color = accent) }
        }
    }
}

@Composable
private fun GreenMusicLyricPanel(
    lyric: MusicLyrics, position: Long, duration: Long,
    prefs: AlbumPreferences, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyric.lines.isEmpty()) {
        Box(modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            contentAlignment = Alignment.Center) {
            Text(lyric.plain.ifBlank { "这首歌没有可读取的歌词" },
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
    val karaokeTime by animateFloatAsState(
        targetValue = position.toFloat(),
        animationSpec = if (prefs.style.motion) tween(220, easing = androidx.compose.animation.core.LinearEasing)
            else snap(),
        label = "karaoke-playback-time",
    )
    BoxWithConstraints(modifier.clipToBounds()) {
        // Scroll the active BILINGUAL row into the body of the viewport,
        // not flush to its top edge where the Chinese row was cut off.
        // Derive the anchor from available space (including smaller phones).
        val anchor = 112.dp
        val trailing = 196.dp
        val anchorPx = with(androidx.compose.ui.platform.LocalDensity.current) { anchor.roundToPx() }
        LaunchedEffect(lyric.lines, current, anchorPx, prefs.musicLyricsStagger, prefs.style.motion) {
            if (!state.isScrollInProgress) {
                if (prefs.musicLyricsStagger && prefs.style.motion)
                    state.animateScrollToItem(current, scrollOffset = -anchorPx)
                else state.scrollToItem(current, scrollOffset = -anchorPx)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp,
                top = anchor, bottom = trailing),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        itemsIndexed(lyric.lines) { i, line ->
            val selected = i == current
            val alpha by animateFloatAsState(
                if (selected) 1f else if (prefs.musicLyricsBlur) .48f else .65f,
                animationSpec = tween(280, easing = CubicBezierEasing(.645f,.045f,.355f,1f)),
                label = "GreenMusic lyric fade",
            )
            val scale by animateFloatAsState(
                if (selected) 1f else .94f, animationSpec = tween(280),
                label = "GreenMusic lyric scale",
            )
            val words = remember(lyric.lines, i, selected, duration,
                prefs.musicLyricKaraoke, prefs.style.motion) {
                if (selected && prefs.style.motion)
                    musicKaraokeWords(lyric.lines, i, duration, prefs.musicLyricKaraoke)
                else emptyList()
            }
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                    .clickable(onClick = hapticClick { onSeek(line.timeMs) })
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                KaraokeMusicLine(
                    text = line.text, words = words, positionMs = karaokeTime,
                    fontSize = prefs.musicLyricsFontSize,
                    weight = if (selected) prefs.musicLyricsWeight
                        else (prefs.musicLyricsWeight - 100).coerceAtLeast(100),
                    center = prefs.musicLyricsCenter,
                    base = MaterialTheme.colorScheme.onSurface,
                    accent = MaterialTheme.colorScheme.primary,
                )
                if (line.translation.isNotBlank()) {
                    Text(
                        line.translation,
                        Modifier.fillMaxWidth(),
                        textAlign = if (prefs.musicLyricsCenter) TextAlign.Center else TextAlign.Start,
                        fontSize = (prefs.musicLyricsFontSize * .68f).sp,
                        lineHeight = (prefs.musicLyricsFontSize * .95f).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
            showLyrics = false // ensure the album cover is the shared mini-player endpoint
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
            GreenMusicAlbumLyricsSwitcher(
                trackKey = track.scopeId,
                lyricsVisible = showLyrics,
                expanded = expanded,
                onChange = { showLyrics = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                album = {
                    // Keep this node mounted even while lyrics are displayed.
                    // Decoding the cover again on return caused a brief placeholder flash.
                    // Album and bilingual subtitle occupy two independent fixed
                    // zones.  A 320dp cover plus the former verticalScroll used
                    // to push the translated line offscreen until a manual swipe.
                    Column(Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            BoxWithConstraints(Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center) {
                                val coverSize = minOf(maxWidth, maxHeight, 320.dp)
                                    .coerceAtLeast(1.dp)
                                Box(artworkModifier.size(coverSize)) {
                                    Crossfade(targetState = track, animationSpec = tween(220),
                                        label = "fullscreen-song-crossfade") { song ->
                                        GreenMusicCover(song, Modifier.fillMaxSize()
                                            .clickable(onClick = hapticClick { showLyrics = true }), 28.dp)
                                    }
                                }
                            }
                        }
                        // Exactly the same position for monolingual and bilingual
                        // lyrics: reserve enough height for two lines of each.
                        Box(Modifier.fillMaxWidth().height(112.dp),
                            contentAlignment = Alignment.Center) {
                            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = nonCoreAlpha }
                                .clickable(onClick = hapticClick { showLyrics = true })
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (loadingLyrics) "正在读取歌词…"
                                    else currentLyric(lyric, position, track.artist),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!loadingLyrics) currentMusicLyricLine(lyric, position)
                                    ?.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                                        Text(translation, textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodySmall,
                                            lineHeight = 19.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                            }
                        }
                    }
                },
                lyrics = {
                    GreenMusicLyricPanel(lyric, position, duration, prefs,
                        onSeek = { ms -> scope.launch { music.seek(ms) } },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = nonCoreAlpha })
                },
            )
            // Keep its layout measured so the album cover and title do not jump.
            Column(Modifier.graphicsLayer { alpha = nonCoreAlpha }) {
            Spacer(Modifier.height(12.dp))
            val sliderInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Slider(
                interactionSource = sliderInteractionSource,
                value = draggedPosition ?: (if (duration > 0) position.toFloat().coerceIn(0f,duration.toFloat()) else 0f),
                onValueChange = { draggedPosition = it },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                onValueChangeFinished = {
                    draggedPosition?.let { scope.launch { music.seek(it.toLong()) } }
                    draggedPosition = null
                },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                thumb = { _ ->
                    if (prefs.musicProgressWavy) {
                        Box(
                            Modifier.width(4.dp).height(musicWaveThumbHeight(true))
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    } else {
                        SliderDefaults.Thumb(sliderInteractionSource)
                    }
                },
                track = { sliderState ->
                    if (prefs.musicProgressWavy) {
                        val fraction = musicSliderProgress(
                            sliderState.value, sliderState.valueRange.start,
                            sliderState.valueRange.endInclusive,
                        )
                        MusicWavyProgressTrack(
                            fraction, Modifier.fillMaxWidth().height(20.dp),
                            active = state.playing && prefs.style.motion,
                            stroke = 4.dp,
                        )
                    } else {
                        SliderDefaults.Track(sliderState)
                    }
                },
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
    if (showQueue) GreenMusicQueueSheet(music) { showQueue = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GreenMusicQueueSheet(music: WebAlbumMusicPlayback, onDismiss: () -> Unit) {
    val state by music.state.collectAsState()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(state.index.coerceAtLeast(0))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播放队列 · " + state.songs.size + " 首",
                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = hapticClick(onDismiss)) {
                Icon(Icons.Rounded.Close, "关闭队列")
            }
        }
        if (state.songs.isEmpty()) {
            Text("当前没有播放中的歌曲", Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding(), state = list
        ) {
            itemsIndexed(state.songs, key = { i, song -> song.scopeId + "#" + i }) { index, song ->
                ListItem(
                    headlineContent = {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(song.artist.ifBlank { "未知歌手" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { GreenMusicCover(song, Modifier.size(42.dp), 12.dp) },
                    trailingContent = {
                        if (index == state.index)
                            Icon(Icons.Rounded.GraphicEq, "正在播放",
                                tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = hapticClick {
                        onDismiss()
                        scope.launch { music.select(index) }
                    }),
                )
            }
        }
    }
}
