package com.webdav.album

private val screenshotPrefix = Regex(
    """(?i)^(?:screenshot|screen[\s_-]*shot|屏幕截图|截屏)[\s._-]*(?=(?:20\d{2}|\d{8}))"""
)
private val cameraGeneratedPrefix = Regex(
    """(?i)^(?:img|vid|pxl|dsc|mvimg|jpeg|photo)[\s._-]*(?=(?:20\d{2}[-_.]?\d{2}|\d{8}))"""
)
private val wechatExportPrefix = Regex("""(?i)^mmexport[\s._-]*(?=\d{12,})""")
private val wechatMmpPrefix = Regex("""(?i)^mmp[\s._-]*(?=\d{12,})""")
private val wechatMmpGeneratedPrefix = Regex(
    """(?i)^mmp[a-z0-9]{8,}[\s._-]+(?=(?:20\d{2}|\d{8}))"""
)
private val mediaExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
    "mp4", "m4v", "mov", "3gp", "3gpp", "webm", "mkv", "ts", "m2ts", "avi", "mpg", "mpeg",
)

/** Presentation only: download paths, WebDAV names and cache keys are never modified. */
internal fun displayMediaFilename(filename: String, compact: Boolean): String {
    if (!compact || filename.isBlank()) return filename
    val dot = filename.lastIndexOf('.')
    val base = if (dot > 0 && filename.substring(dot + 1).lowercase() in mediaExtensions)
        filename.substring(0, dot) else filename
    val withoutPrefix = base
        .replaceFirst(screenshotPrefix, "")
        .replaceFirst(cameraGeneratedPrefix, "")
        .replaceFirst(wechatExportPrefix, "")
        .replaceFirst(wechatMmpGeneratedPrefix, "")
        .replaceFirst(wechatMmpPrefix, "")
    // Never strip leading dots, underscores or meaningful characters from
    // names that did not match one of the recognized generated prefixes.
    val result = if (withoutPrefix != base)
        withoutPrefix.trimStart { it.isWhitespace() || it == '_' || it == '-' || it == '.' }.trim()
    else base.trim()
    return result.takeUnless { it.isBlank() } ?: filename
}
