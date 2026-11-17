package com.webdav.album

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chengeeker.lucent.*

internal const val WEBALBUM_SOURCE_URL = "https://github.com/Chengeeker/WebAlbum"

@Composable
internal fun AboutWebAlbumPage(onBack: () -> Unit) {
    val context = LocalContext.current
    BackdropHost(overlay = { LucentHeader("关于 WebAlbum", onBack) }) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 84.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Image(
                    painterResource(R.drawable.webalbum_icon),
                    contentDescription = "WebAlbum 应用图标",
                    modifier = Modifier.size(92.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
                Text("WebAlbum", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("版本 " + BuildConfig.VERSION_NAME + " · Build " + BuildConfig.VERSION_CODE,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "轻巧的 WebDAV 私人图库与音乐播放器。集中浏览远端照片、视频及歌曲，" +
                        "支持音乐队列、嵌入式封面与歌词，并遵循 Material 3 设计。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            LucentSection("开源项目") {
                LucentRow(
                    title = "GitHub 源代码",
                    subtitle = WEBALBUM_SOURCE_URL,
                    icon = Icons.Outlined.Code,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(WEBALBUM_SOURCE_URL))
                            )
                        }
                    },
                )
                LucentRow("开源许可", "MIT License", Icons.Outlined.Description,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("$WEBALBUM_SOURCE_URL/blob/main/LICENSE"))
                            )
                        }
                    })
            }
            LucentSection("界面与技术") {
                LucentRow(
                    title = "LucentUI",
                    subtitle = "Material 3 与液态玻璃界面组件",
                    icon = Icons.Outlined.Palette,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Chengeeker/LucentUI"))
                            )
                        }
                    },
                )
            }
        }
    }
}
