package com.webdav.album

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chengeeker.lucent.*
import dev.chengeeker.lucent.review.AppearanceModes
import dev.chengeeker.lucent.review.AppearancePalette
import dev.chengeeker.lucent.review.FontSizeSetting
import dev.chengeeker.lucent.review.FontWeightSetting
import dev.chengeeker.lucent.review.LocalPageActive
import dev.chengeeker.lucent.review.NavigationGlassChoice
import dev.chengeeker.lucent.review.SettingsTile
import dev.chengeeker.lucent.review.hapticClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackdropHost(overlay = { LucentHeader(title, onBack) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun SettingsHome(prefs: AlbumPreferences, connection: Connection?, lock: AppLock, onBack: () -> Unit, open: (String) -> Unit) {
    val revision = lock.revision
    SettingsScaffold("设置", onBack) {
        LucentSection("偏好与功能") { LucentRow("个性化", "主题、字体与图库布局", Icons.Outlined.Palette, onClick = { open("appearance") }) }
        LucentSection("存储与连接") {
            LucentRow("WebDAV 连接", connection?.root?.host ?: "尚未连接", Icons.Outlined.Cloud, onClick = { open("connection") })
            LucentRow("默认打开文件夹", connection?.let { prefs.defaultFolder(it)?.let(::folderName) ?: "WebDAV 根目录" } ?: "请先连接 WebDAV",
                Icons.Outlined.FolderOpen, enabled = connection != null, onClick = { open("folder") })
            LucentRow("存储设置", "选择图片与视频保存位置", Icons.Outlined.Download, onClick = { open("storage") })
            LucentRow("缓存管理", "查看和清理临时图片", Icons.Outlined.CleaningServices, onClick = { open("cache") })
            LucentRow("本地备份与迁移", "导出或恢复非敏感设置", Icons.Outlined.Save, onClick = { open("backup") })
            LucentRow("网络设置", "超时与代理", Icons.Outlined.Wifi, onClick = { open("network") })
        }
        LucentSection("隐私与安全") {
            LucentRow("应用锁", if (remember(revision) { lock.enabled }) "已开启" else "未开启", Icons.Outlined.Lock, onClick = { open("lock") })
            LucentToggle("截图保护", prefs.privacy, prefs::updatePrivacy, "禁止截图及最近任务预览")
        }
        LucentSection("关于与支持") { LucentRow("关于 WebAlbum", "版本 ${BuildConfig.VERSION_NAME} · 基于 LucentUI", Icons.Outlined.Info) }
    }
}

@Composable
fun StorageSettingsPage(prefs: AlbumPreferences, onBack: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            val previous = prefs.downloadTree
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.updateDownloadTree(uri.toString())
            if (previous.isNotBlank() && previous != uri.toString()) runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }.onFailure { report("未获得目录写入权限，请重新选择。") }
    }
    SettingsScaffold("存储设置", onBack) {
        LucentSection("存储位置") {
            LucentRow("图片与视频目录", if (prefs.downloadTree.isBlank()) "每次保存时选择位置" else Uri.parse(prefs.downloadTree).lastPathSegment ?: "已选择系统授权目录", Icons.Outlined.FolderOpen, onClick = { choose.launch(null) })
            if (prefs.downloadTree.isNotBlank()) LucentRow("恢复默认", "保存单个文件时重新选择位置", onClick = {
                val previous = prefs.downloadTree
                prefs.updateDownloadTree("")
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            })
        }
        Text("批量下载需要先选择保存目录。目录授权不可随设置备份迁移。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsBackupPage(prefs: AlbumPreferences, locked: Boolean, onBack: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val contents = prefs.exportSettings()
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(contents.toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件")
                } }
                report(result.fold({ "设置已导出" }, { "导出失败，请重试" }))
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(out.size() + count <= 64 * 1024) { "设置文件过大" }; out.write(buffer, 0, count)
                    }
                    out.toString("UTF-8").also { org.json.JSONObject(it) }
                } ?: error("无法读取文件")
            } }
            result.onSuccess { pending = it }.onFailure { report("无法读取设置文件，请检查文件格式。") }
        }
    }
    SettingsScaffold("本地备份与迁移", onBack) {
        LucentSection("安全设置备份") {
            LucentRow("导出设置", "保存外观、布局和缓存偏好", Icons.Outlined.Upload, onClick = { export.launch("WebAlbum-settings.json") })
            LucentRow("恢复设置", "从本地文件恢复偏好", Icons.Outlined.Download, onClick = { import.launch(arrayOf("application/json", "text/plain")) })
        }
        LucentCard { Text("备份不包含 WebDAV 地址、账号和密码、默认文件夹、网络代理、应用锁或桌面图标。恢复不会更改这些内容。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    pending?.takeIf { !locked }?.let { text ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("恢复设置？") },
            text = { Text("将覆盖当前的外观、布局和缓存偏好；连接和应用锁保持不变。") },
            confirmButton = { TextButton(onClick = hapticClick {
                val result = runCatching { prefs.importSettings(text) }; pending = null
                report(result.fold({ "设置已恢复" }, { "设置文件无效，原有设置未更改。" }))
            }) { Text("恢复") } }, dismissButton = { TextButton(onClick = hapticClick { pending = null }) { Text("取消") } })
    }
}

@Composable
fun AppearancePage(prefs: AlbumPreferences, locked: Boolean, onBack: () -> Unit, open: (String) -> Unit) {
    val style = prefs.style
    val modes = listOf("system", "light", "dark")
    SettingsScaffold("个性化", onBack) {
        AppearanceModes(modes.indexOf(style.mode).coerceAtLeast(0), { prefs.updateStyle(style.copy(mode = modes[it])) }, style.black, { prefs.updateStyle(style.copy(black = it)) })
        AppearancePalette(style.dynamic, { prefs.updateStyle(style.copy(dynamic = it)) }, style.seed, { prefs.updateStyle(style.copy(seed = it)) })
        FontWeightSetting(style.customWeight, style.weight * 100, "WebAlbum", hapticsEnabled = style.haptics,
            onReset = { prefs.updateStyle(style.copy(customWeight = false)) }, onCommit = { prefs.updateStyle(style.copy(weight = it / 100, customWeight = true)) })
        FontSizeSetting(style.font, onCommit = { prefs.updateStyle(style.copy(font = it)) })
        LucentSection("图库布局") {
            LucentRow("文件夹每行显示数量", "${prefs.folderColumns} 列", Icons.Outlined.Folder, onClick = { open("folderColumns") })
            LucentRow("图片与视频每行显示数量", "${prefs.columns} 列", Icons.Outlined.GridView, onClick = { open("columns") })
            LucentToggle("显示文件名称", prefs.showMediaNames, prefs::updateShowMediaNames,
                "图片和视频使用封面 + 文件名卡片；1～3 列生效，4～5 列自动隐藏名称")
            if (prefs.showMediaNames) {
                LucentToggle("精简文件名称", prefs.compactMediaNames, prefs::updateCompactMediaNames,
                    "隐藏常见截图及微信图片前缀和媒体扩展名，仅改变显示内容")
            }
        }
        LucentSection("显示效果") {
            LucentToggle("使用液态玻璃效果", style.glass, { prefs.updateStyle(style.copy(glass = it)) }, "应用于图片查看器和悬浮操作栏")
            if (style.glass && !locked) NavigationGlassChoice(style.translucent, hapticsEnabled = style.haptics) { prefs.updateStyle(style.copy(translucent = it)) }
        }
        LucentSection("应用图标") { LucentRow("自定义应用图标", listOf("圆润", "清浅", "深邃")[prefs.icon], Icons.Outlined.AppShortcut, onClick = { open("icons") }) }
        LucentSection("屏幕显示") { LucentRow("屏幕帧率设置", if (prefs.refresh == 0) "系统默认" else "${prefs.refresh} Hz", Icons.Outlined.Speed, onClick = { open("refresh") }) }
        LucentSection("手势与动效") {
            LucentToggle("界面动画", style.motion, { prefs.updateStyle(style.copy(motion = it)) }, "跟随系统动画设置")
            if (Build.VERSION.SDK_INT >= 34) LucentToggle("可预测性返回", prefs.predictive, prefs::updatePredictive, "返回手势预览上一页")
        }
        LucentSection("触感与震动") { LucentToggle("触感与震动反馈", style.haptics, { prefs.updateStyle(style.copy(haptics = it)) }, "点击和指定手势时提供反馈") }
    }
}

@Composable
fun ColumnsPage(prefs: AlbumPreferences, folder: Boolean, onBack: () -> Unit) {
    SettingsScaffold(if (folder) "文件夹每行显示数量" else "图片与视频每行显示数量", onBack) {
        LucentSection(if (folder) "文件夹列数" else "媒体列数") {
            (1..5).forEach { value -> LucentRow("$value 列", onClick = { if (folder) prefs.updateFolderColumns(value) else prefs.updateColumns(value) }, trailing = { RadioButton((if (folder) prefs.folderColumns else prefs.columns) == value, null) }) }
        }
    }
}

@Composable
fun IconSettingsPage(prefs: AlbumPreferences, onBack: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    SettingsScaffold("自定义应用图标", onBack) {
        LucentSection("应用图标") {
            listOf("圆润", "清浅", "深邃").forEachIndexed { index, name ->
                SettingsTile(headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium) },
                    leadingContent = { Image(painterResource(listOf(R.drawable.ic_album, R.drawable.ic_album_light, R.drawable.ic_album_dark)[index]), null, Modifier.size(48.dp)) },
                    trailingContent = { RadioButton(prefs.icon == index, null) },
                    modifier = Modifier.lucentClick { runCatching { prefs.setIcon(context, index) }.onFailure { report("切换图标失败，请稍后重试") } })
            }
        }
        Text("桌面图标的刷新时间由系统桌面决定。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("DEPRECATION")
@Composable
fun RefreshSettingsPage(prefs: AlbumPreferences, activity: MainActivity, onBack: () -> Unit) {
    val rates = remember { activity.windowManager.defaultDisplay.supportedModes.map { it.refreshRate.roundToInt() }.filter { it > 0 }.distinct().sorted() }
    SettingsScaffold("屏幕帧率设置", onBack) {
        LucentSection("屏幕帧率") {
            (listOf(0) + rates).forEach { rate ->
                LucentRow(if (rate == 0) "系统默认" else "$rate Hz", onClick = { prefs.updateRefresh(rate) }, trailing = { RadioButton(prefs.refresh == rate, null) })
            }
        }
        Text("系统会根据设备状态决定实际刷新率。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
fun CacheSettingsPage(prefs: AlbumPreferences, locked: Boolean, onBack: () -> Unit, onClear: ((Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    var bytes by remember { mutableLongStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var threshold by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { coil.Coil.imageLoader(context).diskCache?.size ?: 0L } }
    SettingsScaffold("缓存管理", onBack) {
        LucentSection("图片缓存") {
            LucentRow("当前占用", "${bytes / 1024 / 1024} MiB，上限 1280 MiB", Icons.Outlined.FolderOpen)
            LucentRow("清理图片缓存", "不删除服务器文件和已下载的图片", Icons.Outlined.DeleteSweep, onClick = { confirm = true })
            LucentRow("启动时自动清理阈值", if (prefs.cacheThreshold == 0) "关闭" else "${prefs.cacheThreshold} MiB", onClick = { threshold = true })
        }
    }
    if (threshold && !locked) AlertDialog(onDismissRequest = { threshold = false }, title = { Text("启动时自动清理阈值") }, text = {
        Column { listOf(0, 16, 64, 256, 512, 1024).forEach { value -> LucentRow(if (value == 0) "关闭" else "$value MiB", onClick = { prefs.updateCacheThreshold(value); threshold = false }, trailing = { RadioButton(prefs.cacheThreshold == value, null) }) } }
    }, confirmButton = { TextButton(onClick = hapticClick { threshold = false }) { Text("取消") } })
    if (confirm && !locked) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理图片缓存？") },
        text = { Text("不会删除服务器文件和已下载的图片。") },
        confirmButton = { TextButton(onClick = hapticClick { confirm = false; onClear { success -> if (success) bytes = 0 } }) { Text("清理") } },
        dismissButton = { TextButton(onClick = hapticClick { confirm = false }) { Text("取消") } })
}

@Composable
fun NetworkSettingsPage(prefs: AlbumPreferences, app: AlbumApplication, onBack: () -> Unit, report: (String) -> Unit) {
    var timeout by remember { mutableIntStateOf(prefs.network.timeout) }
    var mode by remember { mutableStateOf(prefs.network.mode) }
    var host by remember { mutableStateOf(prefs.network.host) }
    var port by remember { mutableStateOf(prefs.network.port.toString()) }
    SettingsScaffold("网络设置", onBack) {
        LucentSection("超时（秒）") {
            listOf(15, 30, 60).forEach { value -> LucentRow("$value 秒", onClick = { timeout = value }, trailing = { RadioButton(timeout == value, null) }) }
        }
        LucentSection("连接方式") {
            listOf("system" to "系统", "direct" to "直连", "http" to "HTTP 代理").forEach { (value, label) ->
                LucentRow(label, onClick = { mode = value }, trailing = { RadioButton(mode == value, null) })
            }
            if (mode == "http") {
                OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("HTTP 代理主机") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter { ch -> ch in '0'..'9' }.take(5) }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("端口") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
        LucentAction("保存网络设置", modifier = Modifier.fillMaxWidth(), onClick = {
            val value = NetworkSettings(timeout, mode, host.trim(), port.toIntOrNull() ?: -1)
            if (!validNetwork(value)) report("请填写有效的代理主机和 1–65535 端口。")
            else runCatching { prefs.updateNetwork(value); app.applyNetwork(value) }
                .onSuccess { report("网络设置已保存，新请求将使用新的连接方式。") }
                .onFailure { report("无法保存网络设置，请重试。") }
        })
    }
}

fun folderName(url: String): String = android.net.Uri.decode(url.trimEnd('/').substringAfterLast('/')).ifBlank { "WebDAV 根目录" }

@Composable
fun FolderPickerPage(connection: Connection, app: AlbumApplication, onBack: () -> Unit, onSelect: (String?) -> Unit) {
    val active = LocalPageActive.current
    var path by remember(connection) { mutableStateOf(emptyList<String>()) }
    var folders by remember(connection) { mutableStateOf(emptyList<DavItem>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = path.lastOrNull() ?: connection.url
    BackHandler(enabled = active && path.isNotEmpty()) { if (!loading) path = path.dropLast(1) }
    LaunchedEffect(current) {
        loading = true; error = null
        try { folders = withContext(Dispatchers.IO) { DavClient(app.client).list(connection.root, current.toHttpUrl()).filter { it.directory } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { folders = emptyList(); error = failure.message ?: "无法读取文件夹" }
        finally { loading = false }
    }
    BackdropHost(overlay = { LucentHeader("选择默认文件夹", onBack) }) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                LucentSection("当前位置") {
                    LucentRow(if (path.isEmpty()) "WebDAV 根目录" else folderName(current), "可返回上级或进入子文件夹", Icons.Outlined.FolderOpen)
                    LucentAction("将此文件夹设为默认", { onSelect(path.lastOrNull()) }, enabled = !loading && error == null, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { problem -> item { LucentCard { Text(problem, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } } }
            if (path.isNotEmpty()) item { LucentSection("上级") { LucentRow("返回上一级", icon = Icons.Outlined.ArrowBack, onClick = { if (!loading) path = path.dropLast(1) }) } }
            if (folders.isNotEmpty()) item { Text("文件夹", Modifier.padding(start = 16.dp, top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall) }
            items(folders, key = { it.url }) { folder ->
                LucentCard { LucentRow(folder.name, icon = Icons.Outlined.Folder, onClick = { if (!loading) path = path + folder.url }) }
            }
            if (!loading && error == null && folders.isEmpty()) item { Text("没有子文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun AppLockSettingsPage(lock: AppLock, activity: MainActivity, locked: Boolean, onBack: () -> Unit, report: (String) -> Unit) {
    val revision = lock.revision
    val pinEnabled = remember(revision) { lock.pinEnabled }
    val bioEnabled = remember(revision) { lock.biometricEnabled }
    var setup by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun requireAuth(action: () -> Unit) { if (lock.enabled) pending = action else action() }
    SettingsScaffold("应用锁", onBack) {
        LucentSection("解锁方式") {
            LucentToggle("PIN 码解锁", pinEnabled, {
                requireAuth { if (pinEnabled) lock.disablePin() else setup = true }
            }, "使用 4–12 位数字解锁")
            if (pinEnabled) LucentRow("修改 PIN 码", onClick = { requireAuth { setup = true } })
            LucentToggle("生物识别解锁", bioEnabled, {
                requireAuth {
                    if (bioEnabled) lock.setBiometric(false)
                    else if (!activity.deviceCredentialAvailable()) report("请先在系统设置中启用设备锁屏。")
                    else activity.authenticateBiometric { success, error ->
                        if (success) lock.setBiometric(true) else if (error != null) report(error)
                    }
                }
            }, "使用设备支持的指纹或面容")
        }
        LucentCard { Text("启用生物识别后，也可以使用设备锁屏验证。应用锁保护访问界面，不加密缓存和已下载的图片。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (setup && !locked) PinSetupDialog(lock, { setup = false }, { setup = false; report("PIN 码已保存") })
    if (pending != null && !locked) AuthenticationDialog(lock, activity, onDismiss = { pending = null }, onVerified = {
        val action = pending; pending = null; action?.invoke()
    })
}
