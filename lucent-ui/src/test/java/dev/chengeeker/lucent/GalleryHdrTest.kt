package dev.chengeeker.lucent.review

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test

class GalleryHdrTest {
    @Test fun videoTransferDistinguishesHdrAndSdr() {
        assertTrue(isHdrVideoTransfer(MediaFormat.COLOR_TRANSFER_ST2084))
        assertTrue(isHdrVideoTransfer(MediaFormat.COLOR_TRANSFER_HLG))
        assertFalse(isHdrVideoTransfer(MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        assertFalse(isHdrVideoTransfer(MediaFormat.COLOR_TRANSFER_LINEAR))
        assertFalse(isHdrVideoTransfer(null))
    }

    @Test fun registryOnlyPromotesVerifiedVersionScopedUrls() {
        val url = "https://example.invalid/dav/media.mp4#hdrtest"
        val changed = "https://example.invalid/dav/media.mp4#newversion"
        assertFalse(GalleryHdrRegistry.isKnownHdr(url))
        GalleryHdrRegistry.recordHdr(url)
        assertTrue(GalleryHdrRegistry.isKnownHdr(url))
        assertFalse(GalleryHdrRegistry.isKnownHdr(changed))
    }
}
