package dev.chengeeker.lucent.review

import android.graphics.Bitmap
import android.media.MediaFormat
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-only evidence of verified HDR media, keyed by the version-scoped Coil URL.
 * Keep the registry small and never infer HDR from an image/video filename.
 */
object GalleryHdrRegistry {
    private val known = MutableStateFlow<Set<String>>(emptySet())

    fun recordHdr(url: String) {
        if (url.isBlank()) return
        known.update { current ->
            if (url in current) current
            else (current + url).toList().takeLast(512).toSet()
        }
    }

    fun isKnownHdr(url: String): Boolean = url in known.value

    @Composable
    fun observeHdr(url: String): Boolean {
        val snapshot by known.collectAsState()
        return url in snapshot
    }
}

/** Android 14+ Ultra HDR uses an embedded gain map; filenames are not evidence. */
fun bitmapHasHdrGainmap(bitmap: Bitmap): Boolean =
    Build.VERSION.SDK_INT >= 34 && !bitmap.isRecycled && bitmap.hasGainmap()

/** SMPTE ST 2084 (HDR10 / Dolby Vision transfers) and HLG. */
fun isHdrVideoTransfer(transfer: Int?): Boolean =
    transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
