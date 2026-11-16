package com.webdav.album

import java.util.Locale

private val audioExtensions = setOf(
    "mp3", "flac", "m4a", "mp4a", "aac", "ogg", "oga", "opus",
    "wav", "wave", "aiff", "aif", "alac", "wma", "amr", "3ga",
)

/** Recognition is extension-based, final codec support is platform-dependent. */
internal fun isAudioFile(name: String, directory: Boolean = false): Boolean =
    !directory && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in audioExtensions

data class MusicTrack(
    val url: String,
    val version: String,
    val filename: String,
    val title: String = filename.substringBeforeLast('.', filename),
    val artist: String = "",
    val album: String = "",
    val artwork: ByteArray? = null,
) {
    val scopeId: String get() = "$url|$version"
}

internal fun folderAudioQueue(items: List<DavItem>, mode: AlbumSort, descending: Boolean): List<MusicTrack> =
    orderedMedia(items.filter { it.audio }, mode, descending)
        .map { MusicTrack(it.url, it.version, it.name) }
