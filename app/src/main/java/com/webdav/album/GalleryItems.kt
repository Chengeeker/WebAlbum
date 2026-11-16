package com.webdav.album

import dev.chengeeker.lucent.review.GalleryMedia

internal class GalleryItems(private val items: List<DavItem>, private val connection: Connection) : AbstractList<GalleryMedia>() {
    private val cached = arrayOfNulls<GalleryMedia>(items.size)
    override val size: Int get() = items.size
    override fun get(index: Int): GalleryMedia = cached[index] ?: items[index].let { item ->
        GalleryMedia(item.imageUrl(connection), video = if (item.video) item.url else "", gif = item.name.endsWith(".gif", true), description = item.name)
            .also { cached[index] = it }
    }
}
