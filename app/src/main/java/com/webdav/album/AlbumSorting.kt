package com.webdav.album

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AlbumSort(val key: String, val label: String) {
    CAPTURE("capture", "拍摄时间"),
    MODIFIED("modified", "修改时间"),
    NAME("name", "名称");

    companion object {
        fun from(key: String?): AlbumSort = entries.firstOrNull { it.key == key } ?: MODIFIED
    }
}

/** WebDAV has a modified timestamp, not a portable capture-time property. */
internal fun modifiedTimestamp(item: DavItem): Long? {
    val raw = item.version.split('|').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(raw).toEpochMilli() }
        .getOrNull()
}

/**
 * Missing capture metadata falls back to the server modified date. Missing all date
 * metadata sorts last in either direction. Ties use the name and URL for stable order.
 */
internal fun orderedMedia(
    media: List<DavItem>,
    mode: AlbumSort,
    descending: Boolean,
    captureTimes: Map<String, Long> = emptyMap(),
): List<DavItem> {
    val nameOrder = compareBy<DavItem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.url }
    if (mode == AlbumSort.NAME) return media.sortedWith(if (descending) nameOrder.reversed() else nameOrder)
    return media.sortedWith { a, b ->
        val aTime = if (mode == AlbumSort.CAPTURE) captureTimes[a.url] ?: modifiedTimestamp(a) else modifiedTimestamp(a)
        val bTime = if (mode == AlbumSort.CAPTURE) captureTimes[b.url] ?: modifiedTimestamp(b) else modifiedTimestamp(b)
        when {
            aTime == null && bTime == null -> nameOrder.compare(a, b)
            aTime == null -> 1
            bTime == null -> -1
            aTime != bTime -> if (descending) bTime.compareTo(aTime) else aTime.compareTo(bTime)
            else -> nameOrder.compare(a, b)
        }
    }
}

/** Every drag update is a snapshot, so reversing the gesture removes exited cells. */
internal fun rangeSelection(baseline: Set<String>, media: List<DavItem>, start: Int, end: Int): Set<String> {
    if (start !in media.indices || end !in media.indices) return baseline
    return baseline + (minOf(start, end)..maxOf(start, end)).map { media[it].url }
}
