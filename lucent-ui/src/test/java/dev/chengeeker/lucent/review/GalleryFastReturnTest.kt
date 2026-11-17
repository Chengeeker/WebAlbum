package dev.chengeeker.lucent.review

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryFastReturnTest {
    @Test fun readyFullscreenBitmapWinsOverThumbnail() {
        assertEquals(GalleryReturnVisual.PAGE, galleryReturnVisual(true, true))
        assertEquals(GalleryReturnVisual.PAGE, galleryReturnVisual(true, false))
    }

    @Test fun fastSwipeCanFlyUsingAlreadyDecodedThumbnail() {
        assertEquals(GalleryReturnVisual.THUMBNAIL, galleryReturnVisual(false, true))
    }

    @Test fun fastSwipeBeforeAnyDecodeStillProducesPlaceholderFlight() {
        assertEquals(GalleryReturnVisual.PLACEHOLDER, galleryReturnVisual(false, false))
    }
}
