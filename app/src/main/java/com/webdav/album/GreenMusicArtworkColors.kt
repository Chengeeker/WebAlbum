package com.webdav.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapted from GreenMusic ArtworkColors: Flutter-equivalent tonal-spot artwork palette.
 * Color roles follow Material Color Utilities rather than averaging the cover pixels.
 */
internal suspend fun greenMusicArtworkSeed(bytes: ByteArray): Int? = withContext(Dispatchers.Default) {
    if (bytes.isEmpty() || bytes.size > 768 * 1024) return@withContext null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
    try {
        val ratio = minOf(1f, 112f / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), false)
        try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            Score.score(QuantizerCelebi.quantize(pixels, 128), 1).firstOrNull()
        } finally { if (scaled !== bitmap) scaled.recycle() }
    } finally { bitmap.recycle() }
}

internal fun greenMusicArtworkScheme(seed: Int, dark: Boolean): ColorScheme {
    val s = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    return lightColorScheme(
        primary = Color(s.primary), onPrimary = Color(s.onPrimary),
        primaryContainer = Color(s.primaryContainer), onPrimaryContainer = Color(s.onPrimaryContainer),
        secondary = Color(s.secondary), onSecondary = Color(s.onSecondary),
        secondaryContainer = Color(s.secondaryContainer), onSecondaryContainer = Color(s.onSecondaryContainer),
        tertiary = Color(s.tertiary), onTertiary = Color(s.onTertiary),
        tertiaryContainer = Color(s.tertiaryContainer), onTertiaryContainer = Color(s.onTertiaryContainer),
        background = Color(s.background), onBackground = Color(s.onBackground),
        surface = Color(s.surface), onSurface = Color(s.onSurface),
        surfaceVariant = Color(s.surfaceVariant), onSurfaceVariant = Color(s.onSurfaceVariant),
        surfaceContainer = Color(s.surfaceContainer), surfaceContainerLow = Color(s.surfaceContainerLow),
        surfaceContainerLowest = Color(s.surfaceContainerLowest), surfaceContainerHigh = Color(s.surfaceContainerHigh),
        surfaceContainerHighest = Color(s.surfaceContainerHighest),
        surfaceDim = Color(s.surfaceDim), surfaceBright = Color(s.surfaceBright),
        surfaceTint = Color(s.surfaceTint), outline = Color(s.outline), outlineVariant = Color(s.outlineVariant),
        inverseSurface = Color(s.inverseSurface), inverseOnSurface = Color(s.inverseOnSurface),
        inversePrimary = Color(s.inversePrimary), error = Color(s.error), onError = Color(s.onError),
        errorContainer = Color(s.errorContainer), onErrorContainer = Color(s.onErrorContainer),
        scrim = Color(s.scrim)
    )
}

/**
 * Small thread-safe playlist artwork scheme cache. The mini player prepares
 * both tonal palettes OFF the UI thread before the first fullscreen open.
 * No decode/quantization/ColorScheme construction is needed in the first
 * animation frame if the artwork has already arrived.
 */
internal object MusicArtworkPaletteCache {
    private data class Entry(
        val bytes: ByteArray,
        val light: ColorScheme,
        val dark: ColorScheme,
    )
    private val palettes = object : LinkedHashMap<String, Entry>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 6
    }

    @Synchronized
    fun cached(id: String, bytes: ByteArray, dark: Boolean): ColorScheme? {
        val entry = palettes[id] ?: return null
        if (entry.bytes !== bytes && !entry.bytes.contentEquals(bytes)) return null
        return if (dark) entry.dark else entry.light
    }

    suspend fun getOrPrepare(id: String, bytes: ByteArray, dark: Boolean): ColorScheme? {
        cached(id, bytes, dark)?.let { return it }
        val seed = greenMusicArtworkSeed(bytes) ?: return null
        val prepared = withContext(Dispatchers.Default) {
            val lightScheme = greenMusicArtworkScheme(seed, false)
            val darkScheme = greenMusicArtworkScheme(seed, true)
            lightScheme to darkScheme
        }
        synchronized(this) {
            // Do not overwrite an unrelated more recent track with stale
            // work when an old request finishes out of order.
            palettes[id] = Entry(bytes, prepared.first, prepared.second)
        }
        return if (dark) prepared.second else prepared.first
    }
}
