package com.webdav.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumSortingTest {
    private fun file(name: String, whenModified: String) =
        DavItem("https://example.com/dav/$name", name, false, "etag|$whenModified|123")

    @Test fun modifiedTimeNewestFirstAndReverse() {
        val old = file("a.jpg", "Tue, 12 Nov 2024 08:00:00 GMT")
        val recent = file("b.jpg", "Wed, 13 Nov 2024 08:00:00 GMT")
        assertEquals(listOf(recent, old), orderedMedia(listOf(old, recent), AlbumSort.MODIFIED, true))
        assertEquals(listOf(old, recent), orderedMedia(listOf(recent, old), AlbumSort.MODIFIED, false))
    }

    @Test fun missingDatesAlwaysLastAndCaptureUsesExifWhenKnown() {
        val without = file("empty.jpg", "")
        val recent = file("recent.jpg", "Wed, 13 Nov 2024 08:00:00 GMT")
        val old = file("old.jpg", "Tue, 12 Nov 2024 08:00:00 GMT")
        val results = orderedMedia(listOf(without, recent, old), AlbumSort.CAPTURE, true,
            mapOf(old.url to 1_900_000_000_000L))
        assertEquals(listOf(old, recent, without), results)
    }

    @Test fun nameSortAndDragReversal() {
        val a = file("a.jpg", "")
        val b = file("b.jpg", "")
        val c = file("c.mp4", "")
        val media = listOf(c, a, b)
        assertEquals(listOf(a, b, c), orderedMedia(media, AlbumSort.NAME, false))
        assertEquals(setOf(c.url, a.url, b.url), rangeSelection(emptySet(), media, 0, 2))
        assertEquals(setOf(c.url), rangeSelection(emptySet(), media, 0, 0))
        assertEquals(setOf(b.url, c.url), rangeSelection(setOf(b.url), media, 0, 0))
        assertTrue(rangeSelection(emptySet(), media, -1, 1).isEmpty())
    }
}
