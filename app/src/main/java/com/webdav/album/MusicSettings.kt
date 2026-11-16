package com.webdav.album

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chengeeker.lucent.*

@Composable
internal fun MusicSettingsPage(
    prefs: AlbumPreferences, onBack: () -> Unit,
    requestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val listener = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        owner.lifecycle.addObserver(listener)
        onDispose { owner.lifecycle.removeObserver(listener) }
    }
    val canNotify = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    BackdropHost(overlay = { LucentHeader("音乐播放设置", onBack) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LucentSection("播放与交互") {
                LucentToggle("显示迷你播放器", prefs.musicMiniPlayer, prefs::updateMusicMiniPlayer,
                    "参考 GreenMusic：封面、歌曲信息、迷你歌词、横滑切歌与进度条")
                LucentToggle("允许后台继续播放", prefs.musicKeepBackground, prefs::updateMusicKeepBackground,
                    "使用媒体播放服务；关闭时应用进入后台会暂停")
                LucentToggle("预加载相邻歌曲", prefs.musicPreloadEnabled, prefs::updateMusicPreloadEnabled,
                    "提前缓存上一首和下一首各约 768 KiB，减少 WebDAV 切歌等待；使用额外网络流量")
                LucentRow("主题与动画", "沿用 WebAlbum 个性化中的主题色、动效和触感设置",
                    Icons.Outlined.Palette)
            }
            LucentSection("歌词界面") {
                LucentRow("歌词字体大小", prefs.musicLyricsFontSize.toString() + " sp",
                    Icons.Outlined.TextFields, onClick = {
                        val next = listOf(16, 18, 20, 24, 28, 32, 36, 40)
                        val idx = next.indexOf(prefs.musicLyricsFontSize)
                        prefs.updateMusicLyricsFontSize(next[(idx+1).coerceAtLeast(0) % next.size])
                    })
                LucentRow("歌词文字粗细", prefs.musicLyricsWeight.toString(),
                    Icons.Outlined.FormatBold, onClick = {
                        prefs.updateMusicLyricsWeight(if (prefs.musicLyricsWeight >= 800) 400 else prefs.musicLyricsWeight+100)
                    })
                LucentToggle("居中显示歌词", prefs.musicLyricsCenter, prefs::updateMusicLyricsCenter)
                LucentToggle("非当前行降低对比度", prefs.musicLyricsBlur, prefs::updateMusicLyricsBlur,
                    "参考 GreenMusic 的模糊层次，减少视觉干扰")
                LucentToggle("歌词滚动动画", prefs.musicLyricsStagger, prefs::updateMusicLyricsStagger,
                    "跟随播放进度平滑切换歌词行")
            }
            LucentSection("后台通知") {
                LucentRow("播放通知权限",
                    if (canNotify) "已授权或系统无需单独授权" else "未授权，点此请求",
                    Icons.Outlined.Notifications, onClick = {
                        refresh++
                        requestNotificationPermission()
                    })
                LucentRow("系统媒体控制", "通知、锁屏、耳机和蓝牙控制由 Android MediaSession 提供",
                    Icons.Outlined.Headphones)
            }
            Text("默认使用音频文件内的歌曲信息与歌词；优先读取同目录 LRC，若不存在则读取 ID3 / FLAC 内嵌歌词。音频格式支持最终取决于设备解码器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
