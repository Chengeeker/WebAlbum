package dev.chengeeker.lucent.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPrefetchTest {
    private fun photo(id: String) = GalleryMedia(url = "https://example.test/dav/$id.jpg#version")

    @Test fun prefetchesOnlyImmediateNeighborsWithNextFirst() {
        val media = (0..4).map { photo(it.toString()) }
        assertEquals(listOf(3, 1), galleryNearbyPrefetchIndices(media, 2))
        assertEquals(listOf(1), galleryNearbyPrefetchIndices(media, 0))
        assertEquals(listOf(3), galleryNearbyPrefetchIndices(media, 4))
        assertTrue(galleryNearbyPrefetchIndices(media, 7).isEmpty())
    }

    @Test fun neverSpeculativelyLoadsVideosOrAnimatedPictures() {
        val media = listOf(
            GalleryMedia(url = "https://example.test/movie.mp4", video = "https://example.test/movie.mp4"),
            photo("current"),
            GalleryMedia(url = "https://example.test/animated.gif", gif = true),
        )
        assertTrue(galleryNearbyPrefetchIndices(media, 1).isEmpty())
    }

    @Test fun refusesKnownGiantFilesButAllowsUnknownSizes() {
        val bytesOverLimit = MAX_NEARBY_PREFETCH_BYTES + 1L
        val media = listOf(
            GalleryMedia(url = "https://example.test/too-large.jpg#version.$bytesOverLimit"),
            photo("current"),
            GalleryMedia(url = "https://example.test/acceptable.jpg#version.$MAX_NEARBY_PREFETCH_BYTES"),
        )
        assertEquals(listOf(2), galleryNearbyPrefetchIndices(media, 1))
    }

    @Test fun ignoresEmptyOrNonHttpPreviewSources() {
        val media = listOf(
            GalleryMedia(url = ""),
            photo("current"),
            GalleryMedia(url = "file:///private/cache.jpg"),
        )
        assertTrue(galleryNearbyPrefetchIndices(media, 1).isEmpty())
    }
}
