package com.webdav.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chengeeker.lucent.review.hapticClick

internal val miniQueueHeaderHeight = 42.dp
internal val miniQueueRowHeight = 52.dp

/**
 * The entire expanded glass card, INCLUDING its 58dp mini player, fits in
 * half the screen. The queue is content-sized for short playlists and bounded
 * for long playlists; only the song rows scroll, never the header.
 */
internal fun inlineMiniQueueHeight(songCount: Int, screenHeight: Dp): Dp {
    val maxPanel = (screenHeight * .5f - miniPlayerCompactRowHeight - 12.dp)
        .coerceAtLeast(miniQueueHeaderHeight)
    val rows = if (songCount <= 0) 48.dp
        else miniQueueRowHeight * songCount.coerceAtMost(50)
    return minOf(miniQueueHeaderHeight + rows, maxPanel)
}

/**
 * Must remain INSIDE the same Surface as the compact player so classic,
 * translucent and solid materials grow and retract as one contiguous panel.
 */
@Composable
internal fun InlineMiniMusicQueue(
    songs: List<MusicTrack>,
    selectedIndex: Int,
    tokens: MiniPlayerGlassTokens,
    height: Dp,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(
            Modifier.fillMaxWidth().height(miniQueueHeaderHeight)
                .padding(start = 14.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播放队列", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = tokens.primary, maxLines = 1)
            Spacer(Modifier.width(7.dp))
            Text("${songs.size} 首", fontSize = 11.sp,
                color = tokens.secondary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = hapticClick(onClose), modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Close, "收起播放队列",
                    Modifier.size(19.dp), tint = tokens.secondary)
            }
        }
        HorizontalDivider(
            thickness = .5.dp,
            color = tokens.primary.copy(alpha = .13f),
        )
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center) {
                Text("播放队列为空", fontSize = 12.sp, color = tokens.secondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = list,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                itemsIndexed(songs, key = { index, song -> song.scopeId + "#" + index }) { index, song ->
                    val playing = index == selectedIndex
                    Row(
                        Modifier.fillMaxWidth().height(miniQueueRowHeight)
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (playing) tokens.primary.copy(alpha = .09f)
                                else Color.Transparent)
                            .clickable(onClick = hapticClick { onSelect(index) })
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GreenMusicCover(song, Modifier.size(34.dp), 9.dp)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, fontSize = 13.sp, lineHeight = 17.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
                                color = tokens.primary)
                            Text(song.artist.ifBlank { "未知歌手" },
                                fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = tokens.secondary)
                        }
                        if (playing) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.GraphicEq, "正在播放",
                                Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
