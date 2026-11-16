package com.webdav.album

import android.os.SystemClock
import kotlinx.coroutines.CancellationException

internal class FolderCache(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {
    private data class Entry(val time: Long, val items: List<DavItem>)
    private val entries = LinkedHashMap<String, Entry>(8, .75f, true)

    fun get(key: String): List<DavItem>? {
        val entry = entries[key] ?: return null
        if (now() - entry.time >= 30_000 || now() < entry.time) { entries.remove(key); return null }
        return entry.items
    }

    fun put(key: String, items: List<DavItem>) {
        entries[key] = Entry(now(), items)
        while (entries.size > 6) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}

internal suspend fun FolderCache.prefetch(entries: List<DavItem>, isCurrent: () -> Boolean, load: suspend (DavItem) -> List<DavItem>) {
    for (folder in entries.asSequence().filter { it.directory }.take(2)) {
        if (!isCurrent()) return
        if (get(folder.url) != null) continue
        try {
            val children = load(folder)
            if (isCurrent()) put(folder.url, children)
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
    }
}
