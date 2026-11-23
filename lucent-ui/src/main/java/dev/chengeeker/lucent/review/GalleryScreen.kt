// Adapted from Review, Copyright 2026 Chengeeker, MIT.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.chengeeker.lucent.review

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.widget.ImageView
import coil.compose.SubcomposeAsyncImageContent
import androidx.core.graphics.drawable.toBitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.chengeeker.lucent.LucentVideo
import dev.chengeeker.lucent.LivePhotoLayer
import dev.chengeeker.lucent.VideoSource
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified

val LocalGalleryOpenFrom = staticCompositionLocalOf<((List<GalleryMedia>, Int, Map<Int, GalleryOrigin>) -> Unit)?> { null }
internal fun GalleryMedia.galleryPreviewUrl(): String = (if (long || (width > 0 && height.toFloat() / width > 2f)) url else preview.ifBlank { url })
    .let { if (gif) it else it }
internal fun galleryDoubleTapScale(scale: Float, long: Boolean): Float = when {
    scale <= 1.01f -> if (long) 3.5f else 2.5f
    scale <= 3.6f -> 6f
    else -> 1f
}

private val galleryCurve = CubicBezierEasing(.215f, .61f, .355f, 1f)

/** Choose a visible return flight even when rapid swiping outruns image decoding. */
internal enum class GalleryReturnVisual { PAGE, THUMBNAIL, PLACEHOLDER }
internal fun galleryReturnVisual(pageReady: Boolean, thumbnailReady: Boolean): GalleryReturnVisual =
    when {
        pageReady -> GalleryReturnVisual.PAGE
        thumbnailReady -> GalleryReturnVisual.THUMBNAIL
        else -> GalleryReturnVisual.PLACEHOLDER
    }



@Composable
fun ReviewGallery(media: List<GalleryMedia>, start: Int, sources: Map<Int, GalleryOrigin> = emptyMap(), onClose: () -> Unit, saving: Boolean = false,
    resolveMotion: (suspend (GalleryMedia) -> String?)? = null, originalLoader: GalleryOriginalLoader? = null,
    onPageSettled: (Int) -> Unit = {}, prepareReturn: suspend (Int) -> Unit = {},
    onSave: (GalleryMedia) -> Unit) {
    require(media.isNotEmpty())
    val initial = start.coerceIn(media.indices)
    val pager = rememberPagerState(initial) { media.size }
    val scope = rememberCoroutineScope()
    val origin = remember { sources[initial] }
    val openingAnchor = remember { origin?.anchor() }
    val openingImage = remember { origin?.bitmap?.takeUnless(Bitmap::isRecycled) }
    val motion = remember { GalleryOpeningMotion(scope, if (openingAnchor != null && openingImage != null) 0f else 1f) }
    var flight by remember { mutableStateOf<GalleryFlight?>(null) }
    var returnGeneration by remember { mutableIntStateOf(0) }
    var preparingReturn by remember { mutableStateOf(false) }
    // The settled page changes only when a fling ENDS. During rapid swipes the
    // return destination must follow the CURRENT page immediately.
    LaunchedEffect(pager.currentPage) {
        onPageSettled(pager.currentPage)
    }
    // Used only when neither the full page nor the grid thumbnail has decoded
    // yet. Still morph a visible card back instead of dropping the animation.
    val returnPlaceholder = remember {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.rgb(57, 59, 63)) }
    }
    // The filmstrip may decode a small real preview before the remote full
    // image finishes. Reuse it as the return-flight bitmap when possible.
    val filmstripBitmaps = remember { mutableMapOf<Int, Bitmap>() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val pagePlanes = remember { mutableMapOf<Int, () -> GalleryPlane?>() }
    val closing = motion.returning
    val preparingFlight = openingAnchor != null && openingImage != null && motion.position == 0f && !closing
    val morphing = preparingFlight || (flight != null && (motion.position < 1f || closing))
    DisposableEffect(flight?.source) {
        val source = flight?.source
        source?.covered = true
        onDispose { source?.covered = false }
    }
    LaunchedEffect(motion.position == 1f, closing) {
        if (motion.position == 1f && !closing) {
            withFrameNanos {}
            if (!motion.returning && motion.position == 1f) flight = null
        }
    }
    fun close() {
        if (motion.returning || preparingReturn) return
        preparingReturn = true
        scope.launch {
            try {
                val page = pager.currentPage
                // Freeze an in-progress fling to the page the user is returning
                // from, before the asynchronous list positioning step.
                pager.requestScrollToPage(page)
                prepareReturn(page)
                if (flight == null || motion.position >= 1f) {
                    val source = sources[page]
                    val anchor = source?.anchor()
                    // Fast swipes can outrun OriginalImage's decode; in that
                    // case use the thumbnail bitmap already in memory.
                    val thumbnail = source?.bitmap?.takeUnless(Bitmap::isRecycled)
                        ?: filmstripBitmaps[page]?.takeUnless(Bitmap::isRecycled)
                    val decoded = pagePlanes[page]?.invoke()
                    val plane = when (galleryReturnVisual(decoded != null, thumbnail != null)) {
                        GalleryReturnVisual.PAGE -> requireNotNull(decoded)
                        GalleryReturnVisual.THUMBNAIL -> requireNotNull(thumbnail).let { bitmap ->
                            GalleryPlane(bitmap,
                                galleryImagePlane(viewport,
                                    Size(bitmap.width.toFloat(), bitmap.height.toFloat()), crop = false),
                                viewport)
                        }
                        GalleryReturnVisual.PLACEHOLDER ->
                            GalleryPlane(returnPlaceholder, viewport, viewport)
                    }
                    flight = if (source != null && anchor != null)
                        GalleryFlight(source, anchor, plane) else null
                }
                val generation = ++returnGeneration
                motion.returnToSource {
                    flight?.source?.covered = false
                    scope.launch {
                        withFrameNanos {}
                        snapshotFlow {
                            !motion.holdingGesture || !motion.returning || generation != returnGeneration
                        }.first { it }
                        if (motion.returning && generation == returnGeneration) onClose()
                    }
                }
            } finally {
                preparingReturn = false
            }
        }
    }
    @Composable fun Content(inDialog: Boolean) {
        val pulse = reviewPulse()
        val item = media[pager.currentPage]
        val settledItem = media[pager.settledPage]
        val context = LocalContext.current
        var zoomed by remember { mutableStateOf(false) }
        var dragY by remember(pager.currentPage) { mutableFloatStateOf(0f) }
        var dragFraction by remember(pager.currentPage) { mutableFloatStateOf(0f) }

        var showControls by remember { mutableStateOf(true) }
        var currentOriginalState by remember { mutableStateOf<GalleryHiResState?>(null) }
        var playingMotion by remember(item.url) { mutableStateOf(false) }
        var motionError by remember(item.url) { mutableStateOf(false) }
        val liveUrl by produceState<String?>(null, item.url, resolveMotion, pager.settledPage) {
            value = if (item.video.isBlank() && pager.currentPage == pager.settledPage) resolveMotion?.invoke(item) else null
        }
        var videoControlsHeight by remember { mutableIntStateOf(0) }
        var videoFullscreen by remember { mutableStateOf(false) }
        var thumbnailScrolling by remember { mutableStateOf(false) }




        val mainDragging by pager.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(mainDragging) { if (mainDragging) playingMotion = false }

        val density = LocalDensity.current
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val view = LocalView.current
        val window = if (inDialog) (view.parent as? DialogWindowProvider)?.window else LocalActivity.current?.window
        DisposableEffect(window, inDialog) {
            if (inDialog) window?.apply {
                setWindowAnimations(0)
                setDimAmount(0f)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
                androidx.core.view.WindowCompat.getInsetsController(this, view).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            }
            val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            val lightStatus = bars?.isAppearanceLightStatusBars
            val lightNavigation = bars?.isAppearanceLightNavigationBars
            bars?.apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            val status = androidx.core.view.WindowInsetsCompat.Type.statusBars()
            onDispose {
                if (!inDialog) bars?.apply {
                    show(status)
                    isAppearanceLightStatusBars = lightStatus ?: false
                    isAppearanceLightNavigationBars = lightNavigation ?: false
                }
            }
        }
        LaunchedEffect(window, showControls) {
            window?.let {
                val bars = androidx.core.view.WindowCompat.getInsetsController(it, view)
                val status = androidx.core.view.WindowInsetsCompat.Type.statusBars()
                if (showControls) bars.show(status) else bars.hide(status)
            }
        }
        val hasHdr = item.hdr || GalleryHdrRegistry.observeHdr(item.url)
        var hdrActive by remember(window) { mutableStateOf(false) }
        LaunchedEffect(hasHdr, motion.position == 1f, closing) {
            if (!hasHdr) hdrActive = false
            else if (motion.position == 1f && !closing) hdrActive = true
        }
        DisposableEffect(window, hdrActive) {
            val original = window?.colorMode
            val changed = Build.VERSION.SDK_INT >= 34 && hdrActive && view.display?.isHdr == true && original != ActivityInfo.COLOR_MODE_HDR
            if (changed) {
                window?.colorMode = ActivityInfo.COLOR_MODE_HDR
                if (Build.VERSION.SDK_INT >= 35) window?.setDesiredHdrHeadroom(0f)
            }
            onDispose { if (changed && original != null) window?.colorMode = original }
        }
        BackHandler { if (playingMotion) playingMotion = false else close() }
        fun save(target: GalleryMedia) = onSave(target)
        val mediaLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        val mediaBackdrop = remember(mediaLayer) { ReviewBackdrop(mediaLayer) }
        CompositionLocalProvider(LocalBackdrop provides mediaBackdrop) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            val position = coordinates.positionOnScreen()
            if (!position.isSpecified) return@onGloballyPositioned
            viewport = Rect(position, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            if (motion.position == 0f && !motion.returning && flight == null && origin != null && openingAnchor != null && openingImage != null) {
                val destination = galleryImagePlane(viewport, Size(openingImage.width.toFloat(), openingImage.height.toFloat()), crop = false)
                flight = GalleryFlight(origin, openingAnchor, GalleryPlane(openingImage, destination, viewport))
                motion.open()
            }
        }.galleryOverlayInput(
            target = { if (motion.returning) flight?.anchor?.visible?.translate(-viewport.topLeft) else null },
            canSwipe = { flight != null && media.size > 1 && !motion.returning },
            hold = { motion.holdingGesture = it },
            reopen = { if (motion.returning) { returnGeneration++; flight?.source?.covered = true; pulse(); motion.open() } },
            swipe = { direction ->
                val index = pager.currentPage + if (direction < 0) 1 else -1
                if (index in media.indices) {
                    motion.finishOpening(); flight = null
                    scope.launch { pager.scrollToPage(index) }
                }
            },
        ).graphicsLayer { alpha = if (closing && !morphing) motion.position else 1f }
            .background(Color.Black.copy(alpha = motion.position * (1f - dragFraction).coerceIn(0f, 1f)))) {
            HorizontalPager(pager, userScrollEnabled = !zoomed && !videoFullscreen && !closing && !preparingReturn && !morphing,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (morphing) 0f else 1f }.recordBackdrop(mediaBackdrop), beyondViewportPageCount = 0) { page ->
                val picture = media[page]
                if (picture.video.isNotBlank()) {
                    if (page == pager.settledPage && !pager.isScrollInProgress && !closing) key(picture.video) {
                        LucentVideo(listOf(VideoSource("自动", picture.video)), Modifier.fillMaxSize(), autoplay = true, title = picture.description,
                            onControlsHeight = { videoControlsHeight = it }, onControlsVisibilityChanged = { showControls = it },
                            onFullscreenChanged = { videoFullscreen = it },
                            onHdrMetadataChanged = { detected -> if (detected) GalleryHdrRegistry.recordHdr(picture.url) })
                    } else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        GalleryMediaImage(picture, "视频封面", Modifier.fillMaxSize())
                        Icon(Icons.Rounded.PlayCircle, "播放视频", Modifier.size(56.dp), tint = Color.White)
                    }
                } else run {
                    val firstBitmap = remember(page) { if (page == initial) openingImage else sources[page]?.bitmap }
                    var scale by remember(page) { mutableFloatStateOf(1f) }
                    var pan by remember(page) { mutableStateOf(Offset.Zero) }
                    val highRes = rememberGalleryHiResState(picture.url)
                    SideEffect {
                        if (page == pager.currentPage && currentOriginalState !== highRes)
                            currentOriginalState = highRes
                    }
                    var displayedBitmap by remember(page) { mutableStateOf(firstBitmap) }
                    var fullImageReady by remember(page, picture.url) { mutableStateOf(false) }
                    var zoomAnimation by remember(page) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    GalleryAdjacentPrefetch(media, page, fullImageReady && page == pager.settledPage && !pager.isScrollInProgress && !closing)
                    DisposableEffect(page) {
                        pagePlanes[page] = {
                            displayedBitmap?.takeUnless(Bitmap::isRecycled)?.let { bitmap ->
                                val plane = galleryImagePlane(viewport, Size(bitmap.width.toFloat(), bitmap.height.toFloat()), crop = false)
                                val center = viewport.center
                                GalleryPlane(bitmap, Rect(center + (plane.topLeft - center) * scale + pan + Offset(0f, dragY),
                                    center + (plane.bottomRight - center) * scale + pan + Offset(0f, dragY)), viewport)
                            }
                        }
                        onDispose { pagePlanes.remove(page) }
                    }
                    LaunchedEffect(pager.currentPage) { zoomAnimation?.cancel(); scale = 1f; pan = Offset.Zero; zoomed = false }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val pageWidth = maxWidth; val pageHeight = maxHeight
                        val width = with(density) { pageWidth.toPx() }; val height = with(density) { pageHeight.toPx() }
                        val gesture = rememberTransformableState { zoom, offset, _ ->
                            zoomAnimation?.cancel()
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            pan = Offset((pan.x + offset.x).coerceIn(-width * (scale - 1) / 2, width * (scale - 1) / 2),
                                (pan.y + offset.y).coerceIn(-height * (scale - 1) / 2, height * (scale - 1) / 2)); zoomed = scale > 1.01f
                        }
                        Box(Modifier.fillMaxSize().transformable(gesture, canPan = { scale > 1f })
                            .pointerInput(page, width, height) { detectTapGestures(onTap = { tap ->
                                if (tap.x >= width / 3 && tap.x < width * 2 / 3) showControls = !showControls else close()
                            }, onLongPress = { if (page == pager.currentPage && liveUrl != null) { pulse(); playingMotion = true } },
                            onPress = { try { tryAwaitRelease() } finally { if (page == pager.currentPage && liveUrl != null) playingMotion = false } }, onDoubleTap = { tap ->
                                pulse()
                                zoomAnimation?.cancel()
                                val target = galleryDoubleTapScale(scale, picture.long)
                                val from = pan
                                val imageSize = displayedBitmap?.takeUnless(Bitmap::isRecycled)?.let { Size(it.width.toFloat(), it.height.toFloat()) }
                                val focus = galleryDoubleTapFocus(Size(width, height), imageSize, scale, pan, dragY, tap)
                                val destination = focus - (focus - from) * (target / scale)
                                val boundX = width * (target - 1) / 2
                                val boundY = height * (target - 1) / 2
                                val to = Offset(destination.x.coerceIn(-boundX, boundX), destination.y.coerceIn(-boundY, boundY))
                                zoomed = true
                                zoomAnimation = scope.launch {
                                    val initialScale = scale
                                    Animatable(0f).animateTo(1f, tween(220, easing = galleryCurve)) {
                                        scale = initialScale + (target - initialScale) * value
                                        pan = from + (to - from) * value
                                    }
                                    zoomed = scale > 1.01f
                                }
                            }) }
                            .pointerInput(page, zoomed, closing, height) { if (!zoomed && !closing) detectVerticalDragGestures(onDragEnd = {
                                if (abs(dragY) > height * .18f) { pulse(); close() }
                                else scope.launch { Animatable(dragY).animateTo(0f, tween(200, easing = galleryCurve)) { dragY = value; dragFraction = abs(value) / (height * .5f) } }
                            }, onDragCancel = { dragY = 0f; dragFraction = 0f }) { _, delta ->
                                dragY += delta; dragFraction = abs(dragY) / (height * .5f)
                            } }, contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y + dragY }, contentAlignment = Alignment.Center) {
                                OriginalImage(picture, Modifier.fillMaxSize(), previewBitmap = firstBitmap,
                                    onBitmap = { displayedBitmap = it; fullImageReady = true }, showImage = !morphing)
                                GalleryHighResolutionLayer(picture, highRes, scale, pan, Size(width, height),
                                    originalLoader = originalLoader,
                                    preserveHdr = picture.hdr || GalleryHdrRegistry.observeHdr(picture.url),
                                    active = page == pager.currentPage && !morphing && !closing)
                                if (page == pager.currentPage && liveUrl != null && !closing && !morphing)
                                    LivePhotoLayer(liveUrl!!, playingMotion, onError = { playingMotion = false; motionError = true },
                                        onStopped = { playingMotion = false }, modifier = Modifier.fillMaxSize())

                            }
                        }
                    }
                }
            }
            flight?.let { GalleryFlightImage(it, viewport.topLeft, motion.position) }
            val chrome = showControls && !videoFullscreen && !morphing && !closing && dragFraction == 0f
            val desiredVideoThumbnail = settledItem.video.isNotBlank()
            var videoThumbnail by remember { mutableStateOf(desiredVideoThumbnail) }
            LaunchedEffect(desiredVideoThumbnail, thumbnailScrolling) {
                if (!thumbnailScrolling) videoThumbnail = desiredVideoThumbnail
            }
            val bottomInsets = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            if (chrome) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GalleryGlassButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", click = ::close)
                Spacer(Modifier.weight(1f))
                if (liveUrl != null) {
                    val pill = RoundedCornerShape(20.dp)
                    val textColor = if (playingMotion) Color.Black else Color.White
                    val background by animateColorAsState(if (playingMotion) Color.White else Color.Black.copy(alpha = .54f), tween(250), label = "live-background")
                    val outline by animateColorAsState(if (playingMotion) Color.White else Color.White.copy(alpha = .24f), tween(250), label = "live-outline")
                    Row(Modifier.clip(pill).background(background).border(1.dp, outline, pill)
                        .reviewClickable { playingMotion = !playingMotion; motionError = false }.padding(horizontal = 15.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (playingMotion) Icons.Rounded.PauseCircleOutline else Icons.Rounded.PlayCircleOutline, null, Modifier.size(16.dp), tint = textColor)
                        Spacer(Modifier.width(5.dp))
                        Text(if (playingMotion) "暂停 Live 图" else "播放 Live 图", color = textColor, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                } else Text("${pager.currentPage + 1} / ${media.size}",
                    Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                    color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                GalleryGlassButton(Icons.Rounded.Download, "下载", enabled = !saving, busy = saving) { save(item) }
            }
            if (chrome && hasHdr && item.video.isBlank()) HdrBadge(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(bottomInsets)
                .padding(end = 16.dp, bottom = if (media.size > 1) 110.dp else 24.dp))
            if (motionError && chrome) Text("动态照片播放失败", Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White)
            // The original-file action follows the same glass preference as the top buttons.
            // Center it just above the 64dp filmstrip (or above the bottom inset when alone).
            if (chrome && item.video.isBlank()) {
                val highResState = currentOriginalState?.takeIf { it.url == item.url }
                val stripVisible = media.size > 1 && !(landscape && videoThumbnail)
                val stripBottom = if (videoThumbnail && videoControlsHeight > 0)
                    with(density) { videoControlsHeight.toDp() } + 8.dp else 32.dp
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(bottomInsets)
                        .padding(bottom = galleryOriginalPillBottomMargin(stripVisible, stripBottom)),
                    contentAlignment = Alignment.Center,
                ) {
                    GalleryOriginalPill(highResState, hasHdr)
                }
            }
            if (chrome && media.size > 1 && !(landscape && videoThumbnail)) GalleryThumbnails(media, pager,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(bottomInsets)
                    .padding(start = 12.dp, end = 12.dp, bottom = if (videoThumbnail && videoControlsHeight > 0)
                        with(density) { videoControlsHeight.toDp() } + 8.dp else 32.dp),
                onScrolling = { thumbnailScrolling = it }, onUserDrag = { playingMotion = false },
                onThumbnailReady = { index, bitmap ->
                    if (!bitmap.isRecycled) {
                        filmstripBitmaps[index] = bitmap
                        if (filmstripBitmaps.size > 16) {
                            filmstripBitmaps.keys.sortedBy { abs(it - pager.currentPage) }
                                .drop(16).forEach { filmstripBitmaps.remove(it) }
                        }
                    }
                })


        }
        }
    }
    if (sources.isEmpty()) {
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { Content(true) }
    } else Content(false)
}

private fun galleryRatio(media: GalleryMedia): Float = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 1f

@Composable
internal fun GalleryThumbnails(media: List<GalleryMedia>, pager: androidx.compose.foundation.pager.PagerState, modifier: Modifier,
    onScrolling: (Boolean) -> Unit, onUserDrag: () -> Unit = {},
    onThumbnailReady: (Int, Bitmap) -> Unit = { _, _ -> }) {
    val thumbnails = rememberLazyListState(pager.currentPage)
    val dragged by thumbnails.interactionSource.collectIsDraggedAsState()
    val mainDragged by pager.interactionSource.collectIsDraggedAsState()
    var scrollOwner by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val tick = reviewPulse()
    val userDrag by rememberUpdatedState(onUserDrag)
    LaunchedEffect(dragged) { if (dragged) userDrag() }
    LaunchedEffect(dragged, thumbnails.isScrollInProgress, scrollOwner) {
        onScrolling(scrollOwner == 2 && (dragged || thumbnails.isScrollInProgress))
    }
    DisposableEffect(Unit) { onDispose { onScrolling(false) } }
    LaunchedEffect(dragged, mainDragged) {
        if (dragged) { scrollOwner = 2; pager.stopScroll() }
        else if (mainDragged) { scrollOwner = 1; thumbnails.stopScroll() }
    }
    BoxWithConstraints(modifier) {
        val cell = maxWidth * .16f
        val density = LocalDensity.current
        val cellPixels = with(density) { cell.toPx() }
        val decay = rememberSplineBasedDecay<Float>()
        var dragDirection by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(dragged) {
            if (dragged) {
                dragDirection = 0f
                var previous: Float? = null
                snapshotFlow { thumbnails.firstVisibleItemIndex * cellPixels + thumbnails.firstVisibleItemScrollOffset }
                    .collect { position ->
                        previous?.let { if (abs(position - it) > .1f) dragDirection = (position - it).sign }
                        previous = position
                    }
            }
        }
        val direction by rememberUpdatedState(dragDirection)
        val fling = remember(thumbnails, cellPixels, media.size, decay) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val layout = thumbnails.layoutInfo
                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                    val item = layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) } ?: return initialVelocity
                    val position = (item.index + (center - item.offset - item.size / 2f) / cellPixels).coerceIn(0f, media.lastIndex.toFloat())
                    val projected = (position + decay.calculateTargetValue(0f, initialVelocity) / cellPixels).coerceIn(0f, media.lastIndex.toFloat())
                    val motion = if (abs(initialVelocity) > 1f) initialVelocity.sign else direction
                    val target = when { motion > 0f -> ceil(projected); motion < 0f -> floor(projected); else -> projected.roundToInt().toFloat() }.coerceIn(0f, media.lastIndex.toFloat())
                    val distance = (target - position) * cellPixels
                    if (abs(distance) < .5f) return 0f
                    val duration = (abs(distance) / abs(initialVelocity).coerceAtLeast(cellPixels * 2f) * 2000f).roundToInt().coerceIn(180, 700)
                    var consumed = 0f
                    animate(0f, distance, animationSpec = tween(duration, easing = LinearOutSlowInEasing)) { value, _ ->
                        consumed += scrollBy(value - consumed)
                    }
                    return 0f
                }
            }
        }
        LaunchedEffect(scrollOwner, cellPixels) {
            if (scrollOwner == 1) {
                snapshotFlow { pager.currentPage to pager.currentPageOffsetFraction }
                    .collect { (page, fraction) -> thumbnails.requestScrollToItem(page, (fraction * cellPixels).roundToInt()) }
            } else {
                var previousPage = -1
                var lastTick = 0L
                snapshotFlow {
                    val layout = thumbnails.layoutInfo
                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                    layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }
                        ?.let { it.index + (center - it.offset - it.size / 2f) / cellPixels }
                }.collect { rawPosition ->
                    if (rawPosition != null) {
                        val position = rawPosition.coerceIn(0f, media.lastIndex.toFloat())
                        val page = position.roundToInt()
                        val fraction = position - page
                        if (page != previousPage) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTick >= 12L) { tick(); lastTick = now }
                            previousPage = page
                        }
                        if (pager.currentPage != page || abs(pager.currentPageOffsetFraction - fraction) > .001f) {
                            pager.requestScrollToPage(page, fraction)
                        }
                    }
                }
            }
        }
        LazyRow(state = thumbnails, flingBehavior = fling, overscrollEffect = null,
            contentPadding = PaddingValues(horizontal = (maxWidth - cell) / 2),
            modifier = Modifier.height(64.dp)) {
            items(media.size) { index ->
                val item = media[index]
                val selected = pager.currentPage == index
                val outline = RoundedCornerShape(8.dp)
                Box(Modifier.width(cell).fillMaxHeight().padding(horizontal = 3.dp, vertical = 2.dp)
                    .then(if (selected) Modifier.border(2.dp, Color.White, outline) else Modifier)
                    .semantics { this.selected = selected }
                    .reviewClickable { scrollOwner = 1; scope.launch { pager.scrollToPage(index) } }) {
                    Box(Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(6.dp))) {
                        val image = Modifier.fillMaxSize()
                        GalleryMediaImage(item.copy(url = item.preview.ifBlank { item.url }),
                            if (item.video.isNotBlank()) "第 ${index + 1} 个视频封面" else "第 ${index + 1} 张图片", image, onBitmap = { bitmap -> onThumbnailReady(index, bitmap) })
                        if (item.video.isNotBlank()) Icon(Icons.Rounded.PlayCircle, null,
                            Modifier.align(Alignment.Center).size(20.dp), tint = Color.White)
                        if (item.duration.isNotBlank()) Text(item.duration,
                            Modifier.align(Alignment.BottomEnd).padding(2.dp)
                                .background(Color.Black.copy(alpha = .7f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            color = Color.White, fontSize = 8.sp)
                        if (!selected) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .5f)))
                        if (item.hdr || GalleryHdrRegistry.observeHdr(item.url)) HdrBadge(
                            Modifier.align(if (item.video.isNotBlank()) Alignment.TopEnd else Alignment.BottomEnd).padding(3.dp),
                            description = if (item.video.isNotBlank()) "第 ${index + 1} 个 HDR 视频" else "第 ${index + 1} 张 HDR 图片", compact = true)
                    }
                }
            }
            }
    }
}


data class GalleryMedia(val url: String, val preview: String = "", val width: Int = 0, val height: Int = 0,
    val long: Boolean = false, val gif: Boolean = false, val hdr: Boolean = false, val description: String = "图片",
    val video: String = "", val duration: String = "")

@Composable private fun GalleryMediaImage(
    media: GalleryMedia, description: String, modifier: Modifier,
    onBitmap: ((Bitmap) -> Unit)? = null,
) {
    coil.compose.AsyncImage(media.url, description, modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        onSuccess = { state -> onBitmap?.invoke(state.result.drawable.galleryBitmap()) })
}

@Composable private fun OriginalImage(
    media: GalleryMedia,
    modifier: Modifier,
    previewBitmap: Bitmap?,
    onBitmap: (Bitmap) -> Unit,
    showImage: Boolean,
) {
    var attempt by remember(media.url) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val motionEnabled = dev.chengeeker.lucent.LocalLucentStyle.current.motion
    val request = remember(context, media.url, attempt) {
        coil.request.ImageRequest.Builder(context).data(media.url).allowHardware(false).build()
    }
    var ready by remember(media.url, attempt) { mutableStateOf(false) }
    var failure by remember(media.url, attempt) { mutableStateOf<Throwable?>(null) }
    val fullAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(if (motionEnabled) 180 else 0, easing = galleryCurve),
        label = "progressive-image-quality",
    )
    // Reuse the already-decoded grid bitmap. No second low-resolution GET is made.
    val preview = previewBitmap?.takeUnless(Bitmap::isRecycled)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (preview != null && fullAlpha < 1f) {
            androidx.compose.foundation.Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = media.description,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (showImage) 1f - fullAlpha else 0f
                },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        key(media.url, attempt) {
            coil.compose.SubcomposeAsyncImage(
                request, media.description,
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (showImage) fullAlpha else 0f
                },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                onSuccess = { state ->
                    failure = null
                    val bitmap = state.result.drawable.galleryBitmap()
                    if (bitmapHasHdrGainmap(bitmap)) GalleryHdrRegistry.recordHdr(media.url)
                    onBitmap(bitmap)
                    ready = true
                },
                onError = { state -> failure = state.result.throwable },
                success = { state ->
                    val bitmap = (state.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap == null) SubcomposeAsyncImageContent()
                    else AndroidView(
                        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.setImageBitmap(bitmap); it.contentDescription = media.description },
                        onRelease = { it.setImageDrawable(null) },
                    )
                },
                loading = {},
                error = {},
            )
        }
        // The spinner is a fallback only when there was no grid bitmap to show.
        if (showImage && !ready && preview == null && failure == null) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
        }
        if (showImage && failure != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = if (preview != null) Alignment.BottomCenter else Alignment.Center) {
                TextButton(
                    onClick = hapticClick { attempt++ },
                    modifier = Modifier.background(Color.Black.copy(alpha = .7f), RoundedCornerShape(12.dp)),
                ) {
                    Text(galleryLoadError(failure!!) + "\n点击重试", color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
internal fun galleryLoadError(error: Throwable): String = when (error) {
    is coil.network.HttpException -> when (val code = error.response.code) {
        401, 403 -> "服务器拒绝访问（HTTP $code），请检查账号权限"
        404 -> "图片不存在或已移动（HTTP 404）"
        in 300..399 -> "图片地址跳转未成功（HTTP $code）"
        else -> "服务器返回错误（HTTP $code）"
    }
    is javax.net.ssl.SSLException -> "安全连接失败，请检查服务器证书"
    is java.net.UnknownHostException -> "无法找到服务器，请检查网络和地址"
    is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
    is java.io.IOException -> "图片传输失败，请检查网络后重试"
    else -> "图片无法解码或加载，请检查格式及文件"
}

@Composable
fun HdrBadge(modifier: Modifier = Modifier, description: String = "HDR 图片", compact: Boolean = false) {
    Text("HDR", modifier.semantics { contentDescription = description }
        .background(Color.Black.copy(alpha = .4f), RoundedCornerShape(4.dp))
        .padding(horizontal = if (compact) 3.dp else 6.dp, vertical = if (compact) 1.dp else 2.dp),
        color = Color.White, fontSize = if (compact) 8.sp else 10.sp, lineHeight = if (compact) 9.sp else 12.sp,
        fontWeight = FontWeight.Normal)
}

internal fun android.graphics.drawable.Drawable.galleryBitmap(): Bitmap {
    if (this is android.graphics.drawable.BitmapDrawable) return bitmap
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val scale = minOf(1f, 1024f / maxOf(width, height))
    return toBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
}
