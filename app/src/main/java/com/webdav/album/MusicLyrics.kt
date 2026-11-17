package com.webdav.album

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import kotlin.coroutines.coroutineContext

data class MusicLyricWord(val text: String, val timeMs: Long, val endMs: Long? = null)

/** Original and translation always share ONE time/index; not two rival timed rows. */
data class MusicLyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val words: List<MusicLyricWord> = emptyList(),
)
data class MusicLyrics(val lines: List<MusicLyricLine> = emptyList(), val plain: String = "") {
    val isTimed: Boolean get() = lines.isNotEmpty()
}
data class EmbeddedMusicTags(
    val title: String = "", val artist: String = "", val album: String = "",
    val lyrics: String = "", val cover: ByteArray? = null,
)

enum class MusicKaraokeMode(val key: String, val label: String, val description: String) {
    OFF("off", "关闭", "仅高亮当前整行，不播放逐字动画"),
    TIMED("timed", "仅真实逐字", "只有歌词提供逐字时间戳时播放逐字动画"),
    SONG("song", "拓展同首歌词", "歌曲含真实逐字时间戳时，其余行按行长估算动画"),
    ALL("all", "拓展全部行（默认）", "所有歌曲都可逐字显示；无时间戳时按行时长估算");
    companion object {
        fun from(value: String?): MusicKaraokeMode = entries.firstOrNull { it.key == value } ?: ALL
    }
}

internal fun parseMusicLyrics(raw: String, translationRaw: String = ""): MusicLyrics {
    val content = raw.trim().take(256 * 1024)
    val rowTag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    val wordTag = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    val offsetTag = Regex("""(?i)\[offset:([+-]?\d+)\]""")
    fun stamp(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val millis = match.groupValues[3].let { if (it.isEmpty()) 0L else it.padEnd(3, '0').take(3).toLongOrNull() ?: 0L }
        return (minutes * 60 + seconds) * 1000 + millis
    }
    fun parse(input: String): List<MusicLyricLine> {
        val offset = offsetTag.find(input)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return input.lineSequence().flatMap { row ->
            val marks = rowTag.findAll(row).toList()
            if (marks.isEmpty()) return@flatMap emptySequence()
            val contentText = row.substring(marks.last().range.last + 1).trim()
            val clean = wordTag.replace(contentText, "").trim()
            if (clean.isEmpty()) return@flatMap emptySequence()
            val wordMarkers = wordTag.findAll(contentText).toList()
            marks.asSequence().map { match ->
                val time = (stamp(match) + offset).coerceAtLeast(0L)
                var valid = wordMarkers.isNotEmpty() &&
                    contentText.substring(0, wordMarkers.first().range.first).isBlank()
                val words = mutableListOf<MusicLyricWord>()
                for ((i, marker) in wordMarkers.withIndex()) {
                    if (!valid) break
                    val start = (stamp(marker) + offset).coerceAtLeast(0L)
                    val end = wordMarkers.getOrNull(i + 1)?.let { (stamp(it) + offset).coerceAtLeast(0L) }
                    if (start < time || end != null && end < start) { valid = false; break }
                    var text = contentText.substring(
                        marker.range.last + 1,
                        wordMarkers.getOrNull(i + 1)?.range?.first ?: contentText.length,
                    )
                    if (i == 0) text = text.trimStart()
                    if (i == wordMarkers.lastIndex) text = text.trimEnd()
                    if (text.isNotEmpty()) words += MusicLyricWord(text, start, end)
                }
                MusicLyricLine(time, clean, words = if (valid && words.joinToString("") { it.text } == clean)
                    words else emptyList())
            }
        }.sortedBy { it.timeMs }.take(2000).toList()
    }
    // Common bilingual LRC repeats [00:xx.xx] for original and translation.
    // Fold these into a single line so current-line selection NEVER skips either.
    val grouped = linkedMapOf<Long, MutableList<MusicLyricLine>>()
    for (line in parse(content)) grouped.getOrPut(line.timeMs) { mutableListOf() }.add(line)
    val translationCandidates = if (translationRaw.isBlank()) emptyMap()
        else parse(translationRaw.take(256 * 1024)).groupBy { it.timeMs }
    val lines = grouped.map { (time, parts) ->
        val main = parts.first()
        val remainder = parts.drop(1).map { it.text }
            .filter { it.isNotBlank() && it != main.text }.distinct().joinToString(" / ")
        val externalTranslation = translationCandidates[time]
            ?.firstOrNull { it.text.isNotBlank() && it.text != main.text }?.text
        main.copy(translation = externalTranslation ?: remainder)
    }
    return if (lines.isEmpty()) MusicLyrics(plain = content)
    else MusicLyrics(lines, content)
}

/** GreenMusic-compatible karaoke policy; estimated timings are never tagged as real. */
internal fun musicKaraokeWords(
    lines: List<MusicLyricLine>, index: Int, durationMs: Long, mode: MusicKaraokeMode,
): List<MusicLyricWord> {
    if (mode == MusicKaraokeMode.OFF || index !in lines.indices) return emptyList()
    val line = lines[index]
    if (line.words.isNotEmpty()) return line.words.mapIndexed { i, word ->
        word.copy(endMs = word.endMs ?: line.words.getOrNull(i + 1)?.timeMs
            ?: lines.getOrNull(index + 1)?.timeMs ?: word.timeMs + 750L)
    }
    if (mode == MusicKaraokeMode.TIMED ||
        mode == MusicKaraokeMode.SONG && lines.none { it.words.isNotEmpty() }) return emptyList()
    val parts = musicLyricGraphemes(line.text)
    if (parts.isEmpty()) return emptyList()
    val next = lines.getOrNull(index + 1)?.timeMs
    var end = next?.coerceAtMost(line.timeMs + 8000L)
        ?: line.timeMs + (parts.size * 220L).coerceIn(800L, 8000L)
    if (durationMs > 0) end = minOf(end, durationMs)
    if (end <= line.timeMs) return emptyList()
    val span = end - line.timeMs
    return parts.mapIndexed { i, part ->
        MusicLyricWord(part, line.timeMs + span * i / parts.size,
            line.timeMs + span * (i + 1) / parts.size)
    }
}

internal fun musicLyricGraphemes(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var index = 0
    var join = false
    var regional = false
    while (index < text.length) {
        val cp = text.codePointAt(index)
        val type = Character.getType(cp)
        val mark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF
        val isRegional = cp in 0x1F1E6..0x1F1FF
        if (index > start && !mark && !join && cp != 0x200D && !(isRegional && regional)) {
            result += text.substring(start, index)
            start = index
        }
        join = cp == 0x200D
        regional = isRegional && !regional
        index += Character.charCount(cp)
    }
    if (index > start) result += text.substring(start)
    return result
}

internal fun currentMusicLyricLine(lyrics: MusicLyrics, position: Long): MusicLyricLine? =
    lyrics.lines.lastOrNull { it.timeMs <= position }


private fun intBE(a: ByteArray, i: Int): Int = if (i < 0 || i + 4 > a.size) -1 else
    ((a[i].toInt() and 255) shl 24) or ((a[i+1].toInt() and 255) shl 16) or
        ((a[i+2].toInt() and 255) shl 8) or (a[i+3].toInt() and 255)
private fun intLE(a: ByteArray, i: Int): Int = if (i < 0 || i + 4 > a.size) -1 else
    (a[i].toInt() and 255) or ((a[i+1].toInt() and 255) shl 8) or
        ((a[i+2].toInt() and 255) shl 16) or ((a[i+3].toInt() and 255) shl 24)
private fun syncSafe(a: ByteArray, i: Int): Int {
    if (i < 0 || i + 4 > a.size || (i..i+3).any { a[it].toInt() and 128 != 0 }) return -1
    return ((a[i].toInt() and 127) shl 21) or ((a[i+1].toInt() and 127) shl 14) or
        ((a[i+2].toInt() and 127) shl 7) or (a[i+3].toInt() and 127)
}
private fun charsetOf(encoding: Int): Charset = when (encoding) {
    0 -> Charsets.ISO_8859_1
    1 -> Charsets.UTF_16
    2 -> Charsets.UTF_16BE
    else -> Charsets.UTF_8
}
private fun decodeText(data: ByteArray): String {
    if (data.size < 2) return ""
    return runCatching { String(data, 1, data.size - 1, charsetOf(data[0].toInt() and 255)).trim('\u0000',' ','\r','\n').take(65536) }.getOrDefault("")
}
private fun untilTerminator(a: ByteArray, start: Int, encoding: Int): Int {
    val width = if (encoding in 1..2) 2 else 1
    var i = start
    while (i + width <= a.size) {
        if (a[i] == 0.toByte() && (width == 1 || a[i+1] == 0.toByte())) return i + width
        i += width
    }
    return a.size
}
private fun fromEncoding(bytes: ByteArray, begin: Int, encoding: Int): String =
    if (begin >= bytes.size) "" else decodeText(byteArrayOf(encoding.toByte()) + bytes.copyOfRange(begin,bytes.size))
private fun ByteArray.indexFrom(value: Byte, start: Int): Int {
    for (i in start until size) if (get(i) == value) return i
    return -1
}

internal fun parseEmbeddedMusicTags(bytes: ByteArray): EmbeddedMusicTags = when {
    bytes.size >= 10 && String(bytes,0,3,Charsets.US_ASCII) == "ID3" -> parseId3(bytes)
    bytes.size >= 8 && String(bytes,0,4,Charsets.US_ASCII) == "fLaC" -> parseFlac(bytes)
    else -> EmbeddedMusicTags()
}
private fun parseId3(bytes: ByteArray): EmbeddedMusicTags {
    val version = bytes[3].toInt() and 255
    if (version !in 3..4) return EmbeddedMusicTags()
    val length = syncSafe(bytes,6)
    if (length < 1) return EmbeddedMusicTags()
    val sourceEnd = minOf(bytes.size, length + 10)
    // GreenMusic writes MP3 v2.3 with the tag-level unsynchronisation bit.
    // Undo FF-00 escaping *before* following the original frame byte lengths.
    val tag = if (bytes[5].toInt() and 0x80 != 0) {
        val restored = java.io.ByteArrayOutputStream(sourceEnd)
        restored.write(bytes, 0, 10)
        var cursor = 10
        while (cursor < sourceEnd) {
            val value = bytes[cursor]
            restored.write(value.toInt() and 255)
            cursor++
            if (value.toInt() and 255 == 255 && cursor < sourceEnd && bytes[cursor] == 0.toByte())
                cursor++
        }
        restored.toByteArray()
    } else bytes
    val end = tag.size
    var p = 10
    var title="";var artist="";var album="";var lyrics="";var cover:ByteArray?=null
    val lyricSources = linkedSetOf<String>()
    while (p+10 <= end) {
        val id = String(tag,p,4,Charsets.US_ASCII)
        if (id.any { !it.isLetterOrDigit() }) break
        val n = if (version==4) syncSafe(tag,p+4) else intBE(tag,p+4)
        if (n <= 0 || n > 1024*1024 || p+10+n>end) break
        val body=tag.copyOfRange(p+10,p+10+n)
        when(id) {
            "TIT2" -> title=decodeText(body)
            "TPE1" -> artist=decodeText(body)
            "TALB" -> album=decodeText(body)
            "USLT" -> if (body.size >= 5) {
                val enc=body[0].toInt() and 255
                val pos=untilTerminator(body,4,enc)
                val value=fromEncoding(body,pos,enc)
                if(value.isNotBlank()) lyricSources += value
            }
            "TXXX" -> if(body.size>=3) {
                val enc=body[0].toInt() and 255
                val pos=untilTerminator(body,1,enc)
                val label=runCatching {String(body,1,(pos-1-(if(enc in 1..2)2 else 1)).coerceAtLeast(0),charsetOf(enc)).lowercase()}.getOrDefault("")
                if(label.contains("lyric")) {
                    val value=fromEncoding(body,pos,enc)
                    if(value.isNotBlank()) lyricSources += value
                }
            }
            "APIC" -> if(body.size in 15..786432) {
                val mimeEnd=body.indexFrom(0,1)
                if(mimeEnd in 2..80 && mimeEnd+2<body.size) {
                    val begin=untilTerminator(body,mimeEnd+2,body[0].toInt() and 255)
                    val art=body.copyOfRange(begin,body.size)
                    if(art.size in 9..786432)cover=art
                }
            }
        }
        p+=10+n
    }
    return EmbeddedMusicTags(title,artist,album,lyricSources.joinToString("\n"),cover)
}
private fun parseFlac(bytes: ByteArray): EmbeddedMusicTags {
    var p=4
    var title="";var artist="";var album="";var lyrics="";var cover:ByteArray?=null
    val lyricSources = linkedSetOf<String>()
    while(p+4<=bytes.size) {
        val flag=bytes[p].toInt() and 255
        val type=flag and 127
        val n=((bytes[p+1].toInt() and 255) shl 16) or ((bytes[p+2].toInt() and 255) shl 8) or (bytes[p+3].toInt() and 255)
        val end=p+4+n
        if(n>1024*1024||end>bytes.size)break
        val begin=p+4
        if(type==4) {
            var i=begin
            val vendor=intLE(bytes,i)
            if(vendor<0 || vendor>end-i-8)break
            i+=4+vendor
            val count=intLE(bytes,i);i+=4
            if(count !in 0..10000)break
            for(j in 0 until count) {
                if(i+4>end)break
                val len=intLE(bytes,i);i+=4
                if(len<0 || len>end-i)break
                val pair=String(bytes,i,len,Charsets.UTF_8);i+=len
                val key=pair.substringBefore('=',"").uppercase()
                val value=pair.substringAfter('=',"").take(256*1024)
                when(key) {
                    "TITLE" -> title=value
                    "ARTIST" -> artist=value
                    "ALBUM" -> album=value
                    "LYRICS","UNSYNCEDLYRICS","SYNCEDLYRICS","LYRIC" -> if(value.isNotBlank()) lyricSources += value
                }
            }
        }
        if(type==6 && n in 32..786432) {
            var i=begin+4
            val mime=intBE(bytes,i);i+=4
            if(mime in 1..128 && i+mime+4<end) {
                i+=mime
                val desc=intBE(bytes,i);i+=4
                if(desc in 0..4096 && i+desc+20<=end) {
                    i+=desc+16
                    val photoLen=intBE(bytes,i);i+=4
                    if(photoLen in 9..750000 && i+photoLen<=end) cover=bytes.copyOfRange(i,i+photoLen)
                }
            }
        }
        p=end
        if(flag and 128!=0)break
    }
    return EmbeddedMusicTags(title,artist,album,lyricSources.joinToString("\n"),cover)
}

class WebDavMusicMetadata(private val app: AlbumApplication) {
    private val limit=1024*1024
    suspend fun read(track:MusicTrack, connection:Connection):EmbeddedMusicTags=withContext(Dispatchers.IO) {
        val url=track.url.toHttpUrlOrNull()?:return@withContext EmbeddedMusicTags()
        if(!sameOrigin(connection.root,url)||app.connection!=connection)return@withContext EmbeddedMusicTags()
        app.client.newCall(Request.Builder().url(url).header("Range","bytes=0-" + (limit-1))
            .header("Accept-Encoding","identity").build()).execute().use{response->
            if(response.code !in listOf(200,206))return@use EmbeddedMusicTags()
            val input=response.body?.byteStream()?:return@use EmbeddedMusicTags()
            input.use{
                val out=ByteArrayOutputStream()
                val buf=ByteArray(32768)
                while(out.size()<limit) {
                    coroutineContext.ensureActive()
                    val n=it.read(buf,0,minOf(buf.size,limit-out.size()))
                    if(n<=0)break
                    out.write(buf,0,n)
                }
                parseEmbeddedMusicTags(out.toByteArray())
            }
        }
    }
    suspend fun lyrics(track:MusicTrack,connection:Connection,embedded:EmbeddedMusicTags?=null):MusicLyrics=withContext(Dispatchers.IO) {
        val url=track.url.toHttpUrlOrNull()?:return@withContext MusicLyrics()
        if(!sameOrigin(connection.root,url)||app.connection!=connection)return@withContext MusicLyrics()
        val path=url.encodedPath.substringBeforeLast('.',url.encodedPath)
        val sidecar=url.newBuilder().encodedPath(path+".lrc").build()
        val external=runCatching {
            app.client.newCall(Request.Builder().url(sidecar).header("Accept-Encoding","identity").build()).execute().use{r->
                if(r.code!=200 || r.body==null || r.body!!.contentLength()>131072)return@use ""
                val out=ByteArrayOutputStream()
                r.body!!.byteStream().use{stream->
                    val buffer=ByteArray(8192)
                    while(out.size()<=131072) {
                        coroutineContext.ensureActive()
                        val n=stream.read(buffer,0,minOf(buffer.size,131073-out.size()))
                        if(n<=0)break
                        out.write(buffer,0,n)
                    }
                }
                if(out.size()>131072)"" else out.toString(Charsets.UTF_8.name())
            }
        }.getOrDefault("")
        // A sidecar alone is sufficient. When metadata was already loaded for
        // playback, merge its translations; do NOT trigger a second audio-range
        // request just to enrich an otherwise usable external .lrc.
        val embeddedText = embedded?.lyrics ?: if (external.isBlank())
            read(track,connection).lyrics else ""
        if(external.isNotBlank())return@withContext parseMusicLyrics(external, embeddedText)
        parseMusicLyrics(embeddedText)
    }
}
