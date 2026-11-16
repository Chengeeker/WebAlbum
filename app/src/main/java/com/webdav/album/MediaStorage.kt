package com.webdav.album

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.IOException
import okhttp3.OkHttpClient

suspend fun saveToTree(context: Context, client: OkHttpClient, tree: String, item: DavItem) {
    val resolver = context.contentResolver
    val uri = Uri.parse(tree)
    val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    val names = mutableSetOf<String>()
    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) names += cursor.getString(0).orEmpty()
    } ?: throw IOException("无法读取保存目录，请重新授权")
    val name = item.name.replace('/', '_').replace('\\', '_').ifBlank { "media" }
    val stem = name.substringBeforeLast('.', name)
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var unique = name
    var index = 1
    while (unique in names) unique = "$stem (${index++})$ext"
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.removePrefix(".").lowercase()) ?: "application/octet-stream"
    val target = DocumentsContract.createDocument(resolver, parent, mime, unique) ?: throw IOException("无法创建目标文件")
    try {
        downloadImage(client, item.url, context.cacheDir.resolve("download-parts"), item.version.substringAfterLast('|').toLongOrNull()) {
            resolver.openOutputStream(target, "w") ?: throw IOException("无法写入目标文件")
        }
    } catch (error: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, target) }
        throw error
    }
}
