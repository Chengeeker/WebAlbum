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

/**
 * Only displayed AFTER the lightweight tag probe has completed. If the file
 * contains no supported embedded tags, prefer a plausible "artist - title"
 * filename to an unrelated codec/server label. We must never claim that
 * "MP3 · WebDAV" is the musician's name.
 */
internal data class MusicListLabels(val title: String, val artist: String)

internal fun fallbackMusicLabels(filename: String): MusicListLabels {
    val stem = filename.substringBeforeLast('.', filename).trim()
    val separator = stem.indexOf(" - ")
    return if (separator > 0 && separator + 3 < stem.length) {
        MusicListLabels(
            stem.substring(separator + 3).trim().ifBlank { stem },
            stem.substring(0, separator).trim().ifBlank { "未知歌手" },
        )
    } else MusicListLabels(stem.ifBlank { "未知歌曲" }, "未知歌手")
}

internal fun musicListTrack(track: MusicTrack, tags: EmbeddedMusicTags?): MusicTrack {
    val fromFilename = fallbackMusicLabels(track.filename)
    return track.copy(
        title = tags?.title?.takeIf { it.isNotBlank() } ?: fromFilename.title,
        artist = tags?.artist?.takeIf { it.isNotBlank() } ?: fromFilename.artist,
        album = tags?.album?.takeIf { it.isNotBlank() } ?: track.album,
        artwork = tags?.cover ?: track.artwork,
    )
}

internal fun folderAudioQueue(items: List<DavItem>, mode: AlbumSort, descending: Boolean): List<MusicTrack> =
    orderedMedia(items.filter { it.audio }, mode, descending)
        .map { MusicTrack(it.url, it.version, it.name) }
