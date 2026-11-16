# WebAlbum

**面向 WebDAV 的 Android 图片、视频与音乐浏览播放器。** 连接自己的 WebDAV 服务器，按文件夹浏览照片、视频和音乐，在手机上预览、播放和管理远程媒体。

[简体中文](README.md) · [English](README.en.md) · [下载最新发行版](https://github.com/Chengeeker/WebAlbum/releases/latest) · [LucentUI](https://github.com/Chengeeker/LucentUI)

## 项目介绍

WebAlbum 的定位是一款轻量的 **WebDAV 媒体浏览与播放应用**，不是网盘服务端，也不需要将整个相册同步到手机后才能查看。连接 WebDAV 地址后，可以直接查看目录中的图片、视频和音乐，并根据需要下载到本地。

界面基于 **Material Design 3**，并引用我开发的 [**LucentUI**](https://github.com/Chengeeker/LucentUI) 组件与设计实现。本仓库内的 `lucent-ui` 模块包含实际使用的 UI 代码；部分组件也沿用了 [Review](https://github.com/Chengeeker/Review) 的设计思路。相关授权与第三方声明请见 [NOTICE.md](NOTICE.md)。

## 功能特性

### WebDAV 浏览与播放
- **连接自己的服务器**：填写 WebDAV URL、用户名和密码，浏览服务器文件夹及其中的媒体文件；支持连接配置保存、默认打开文件夹以及网络超时、代理设置。
- **图片浏览**：支持常见图片格式，包括 JPEG、PNG、WebP、GIF、BMP、HEIC/HEIF、AVIF 等；提供图片预览、缩放和图库滑动浏览。
- **视频播放**：基于 AndroidX Media3 / ExoPlayer，支持视频封面、进度控制、播放与暂停、全屏横屏、倍速，以及播放器内的亮度、音量与进度手势。实际可播放格式取决于设备解码能力、文件封装与服务器响应。
- **动态照片**：对兼容的动态照片（Motion Photo / Live Photo 类媒体）提供识别与播放支持，效果取决于文件格式和设备兼容性。
- **渐进式预览**：图片先显示已缓存的缩略图，再平滑替换为高清内容；只按需预取少量相邻图片。普通 WebDAV 不一定支持服务器缩略图，首次访问仍可能下载原文件。
- **HDR 媒体**：Android 14+ 支持检测带增益图的 Ultra HDR 图片；通过视频色彩元数据识别 HLG/PQ，兼容设备使用 HDR 视频渲染路径，并显示对应 HDR 角标。最终效果取决于屏幕、系统及解码器。
- **原图高清浏览**：查看器新增底部居中的「加载原图」半透明胶囊，可复用或下载完整原始文件并显示进度；放大到约 1.6 倍时按当前视野解码高清区域，不再只是放大低分辨率预览。胶囊与顶部控件共用液态玻璃设置及触觉反馈。已识别的 Ultra HDR 图片暂不叠加可能丢失增益图的普通区域解码层。
- **按需缓存**：图片封面与预览使用本地缓存；无需预先下载整个远程图库。

### 音乐播放（1.3.2 新增）
- **WebDAV 音乐识别**：识别 MP3、FLAC、M4A、AAC、OGG、OPUS、WAV 等常见扩展名；实际解码取决于设备与服务器，同目录歌曲组成播放队列。
- **GreenMusic 风格播放器**：复用自有项目 [GreenMusic](https://github.com/Chengeeker/GreenMusic) 的交互设计，适配迷你播放器、全屏专辑图、歌词视图、队列、渐变背景及动画。
- **连续可打断动画**：播放器收起过程中可以点击迷你播放器，从当前共享封面、标题位置反向展开；收起时快速淡出非核心控件。
- **歌词与音频元信息**：支持同目录 LRC、MP3 ID3 USLT/TXXX、FLAC Vorbis Comment 歌词；无时间戳显示普通文本。M4A 内嵌歌词尚未完整适配。音乐列表可提前显示可识别的内嵌封面和歌手信息。
- **后台播放**：Media3 MediaSessionService，支持系统通知及媒体控制；Android 13+ 根据系统要求申请通知权限。实际后台行为受设备限制。
- **相邻歌曲预加载**：默认预读上一首与下一首各约 768 KiB，减少 WebDAV 切歌等待；可在音乐设置中关闭。支持随机、循环、倍速及歌词显示设置。

### 图库管理与操作
- **目录与网格**：按文件夹浏览，图片和视频混排；可调整媒体每行 1～5 列，另可配置文件夹网格。1～3 列可启用文件名卡片，并精简 Screenshot、IMG、VID、微信导出等自动生成的文件名前缀。
- **快速操作**：下拉刷新，双击顶栏标题回到列表顶部。
- **排序**：按拍摄时间、文件修改时间或名称排序，支持升序与降序切换，默认按修改时间由近到远。
- **批量选择**：长按进入多选，保持按住并滑动可扩展选择范围，向回滑动可撤销超出范围的选择；支持批量下载、查看属性及删除。
- **文件保存**：可通过 Android 系统文件选择器指定保存位置，并在设置中选择批量下载目录。

> **拍摄时间排序说明**：WebDAV 不一定提供拍摄时间。当前版本优先读取本地已缓存图片的 EXIF 信息；未读取到时（包括部分视频）会回退到服务器修改时间，因此不能保证所有媒体都严格按真实拍摄时间排列。

### 界面与隐私
- **LucentUI + Material Design 3**：支持主题配色、字体与布局调整，以及可选的液态玻璃效果。
- **交互反馈**：支持页面过渡动画、长按与连续选择震动，并允许在设置中关闭动画或触感反馈。
- **应用保护**：支持 PIN / 生物识别等应用锁选项以及截图保护。
- **偏好备份**：可导出与恢复部分非敏感设置；备份不包含 WebDAV 密码等私密连接数据。

## 下载与安装

从 [GitHub Releases](https://github.com/Chengeeker/WebAlbum/releases) 下载正式 APK：

**[下载 WebAlbum v1.3.2（arm64-v8a）](https://github.com/Chengeeker/WebAlbum/releases/download/v1.3.2/WebAlbum_v1.3.2_arm64-v8a.apk)**

| 项目 | 要求 |
| --- | --- |
| 当前版本 | **1.3.2**（Version Code 18） |
| Android | **8.0（API 26）或以上** |
| 架构 | **arm64-v8a** |
| 应用包名 | `com.webdav.album` |
| 远程媒体来源 | 用户自己的 WebDAV 服务器 |

应用没有内置公开媒体库，需自行准备可访问的 WebDAV 地址。建议使用 **HTTPS**；HTTP 连接可能使认证和传输内容暴露在网络中。

## 使用方法

1. 安装 APK，打开 WebAlbum。
2. 输入 WebDAV 服务器地址、用户名和密码，连接成功后进入文件夹浏览。
3. 点击图片或视频进行预览，点击音乐文件即可播放；通过顶部的列数按钮和「更多」菜单调整布局、排序及选择模式。
4. 长按媒体执行多选，通过下方操作栏下载、查看属性或删除；更多设置位于「更多 → 设置」。

**注意：删除操作会直接删除 WebDAV 服务器上的对应文件，通常不可撤销。** 建议提前备份重要媒体。部分视频拖动进度需要服务器支持 HTTP Range 请求，播放与解码兼容性也可能因设备不同而变化。

## 开发与构建

项目使用 **Kotlin + Jetpack Compose**，界面模块为仓库内的 `lucent-ui`，媒体播放使用 Media3，网络请求使用 OkHttp，图片加载使用 Coil。

- 开发环境：Android Studio、JDK 21，以及可用的 Android SDK（当前工程的 `compileSdk` 为 Android API 37，minor API 2）。
- 构建 Debug APK 并运行单元测试（Windows PowerShell）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- Release 使用项目配置的签名环境变量 `WEBALBUM_KEYSTORE` 和 `WEBALBUM_KEY_PASSWORD`，具体配置见 `app/build.gradle.kts`。**不要将签名文件、密码、`local.properties` 或生成的 APK 提交到仓库。**

主要目录：

```text
app/                  Android 应用、WebDAV/图库/播放逻辑及测试
lucent-ui/            本项目使用的 LucentUI Compose UI 模块
gradle/wrapper/       Gradle Wrapper
NOTICE.md             第三方组件与版权说明
README.en.md          English documentation
```

## 版本状态与限制

v1.3.2 已通过正式签名构建与 103 项主机端单元测试（0 失败）。音乐播放器的连续反向动画、后台通知、蓝牙控制及真实 WebDAV 切歌速度仍需真机验证；M4A 内嵌歌词也有未支持的格式。欢迎通过 [Issues](https://github.com/Chengeeker/WebAlbum/issues) 反馈。

## 开源与致谢

WebAlbum 以 [MIT License](LICENSE) 开源。UI 来源与设计参考包括 [LucentUI](https://github.com/Chengeeker/LucentUI)、[Review](https://github.com/Chengeeker/Review) 和自有项目 [GreenMusic](https://github.com/Chengeeker/GreenMusic)；其他依赖及相关许可证信息见 [NOTICE.md](NOTICE.md) 和 `lucent-ui/src/main/assets/licenses/`。
