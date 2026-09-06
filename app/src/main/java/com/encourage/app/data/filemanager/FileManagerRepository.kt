/*
 * Encourage — 文件管理器数据层（M6-H6）
 *
 * 【功能说明】
 * 基于 Storage Access Framework（SAF）实现文件浏览与操作：
 *  - 根目录：用户通过系统「选择文件夹」授权一个目录树，App 持久化该 tree URI
 *    （takePersistableUriPermission），之后可长期访问，无需重复授权。
 *  - 浏览：用 androidx.documentfile.DocumentFile 封装 SAF，列出子项（文件夹/文件）、
 *    进入子目录、返回上级。
 *  - 操作：新建文件夹、重命名、删除、打开（ACTION_VIEW）、分享（ACTION_SEND）。
 *
 * 【实现说明】
 * 用 DocumentFile.fromTreeUri 而非直接 DocumentsContract 查询，逻辑更简洁且
 * 兼容性更好。所有 I/O 均应在 Dispatchers.IO 中执行（由 ViewModel 调度）。
 */

package com.encourage.app.data.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个文件或文件夹条目（SAF 文档）。 */
data class FileEntry(
  /** SAF 文档的 Uri。 */
  val uri: Uri,
  val name: String,
  val isDirectory: Boolean,
  val size: Long,
  val mimeType: String?,
  val canWrite: Boolean,
)

private const val PREFS_NAME = "file_manager"
private const val KEY_TREE_URI = "tree_uri"

@Singleton
class FileManagerRepository
@Inject
constructor(@ApplicationContext private val context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** 保存用户授权的根目录树 URI。 */
  fun saveTreeUri(uri: Uri?) {
    prefs.edit().putString(KEY_TREE_URI, uri?.toString()).apply()
  }

  /** 已持久化的根目录树 URI；未授权返回 null。 */
  fun getTreeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

  /** 已持久化的根目录的 DocumentFile；未授权或失效返回 null。 */
  fun rootDocument(): DocumentFile? = getTreeUri()?.let { DocumentFile.fromTreeUri(context, it) }

  /** 根目录下是否已授权且可访问。 */
  fun isAuthorized(): Boolean = rootDocument() != null

  /** 列出某目录下的子项（文件夹在前，按名称排序）。 */
  suspend fun listChildren(parent: DocumentFile): List<FileEntry> = withContext(Dispatchers.IO) {
    (parent.listFiles() ?: arrayOf())
      .map { file ->
        FileEntry(
          uri = file.uri,
          name = file.name ?: "?",
          isDirectory = file.isDirectory,
          size = if (file.isDirectory) 0L else file.length(),
          mimeType = file.type,
          canWrite = file.canWrite(),
        )
      }
      .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
  }

  /** 新建子目录。 */
  suspend fun createDirectory(parent: DocumentFile, name: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { parent.createDirectory(name) != null }.getOrDefault(false)
  }

  /** 重命名文件/目录。 */
  suspend fun rename(target: DocumentFile, newName: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { target.renameTo(newName) }.getOrDefault(false)
  }

  /** 删除文件/目录（目录需为空或由系统级联处理）。 */
  suspend fun delete(target: DocumentFile): Boolean = withContext(Dispatchers.IO) {
    runCatching { target.delete() }.getOrDefault(false)
  }

  /**
   * 读取文本文件内容（txt 查看）。仅适用于 isFile 文档，读取 UTF-8 文本。
   * 文件过大时截断（上限约 1MB）以保护内存。
   */
  suspend fun readTextFile(target: DocumentFile): String? = withContext(Dispatchers.IO) {
    if (!target.isFile) return@withContext null
    runCatching {
      val stream = context.contentResolver.openInputStream(target.uri) ?: return@withContext null
      stream.use { input ->
        val buf = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
          val n = input.read(buffer)
          if (n == -1) break
          total += n
          if (total > 1_000_000) break // 超过 1MB 截断
          buf.write(buffer, 0, n)
        }
        buf.toString(Charsets.UTF_8.name())
      }
    }.getOrNull()
  }

  /** 写入文本内容到文件（txt 编辑）。返回是否成功。 */
  suspend fun writeTextFile(target: DocumentFile, content: String): Boolean =
    withContext(Dispatchers.IO) {
      if (!target.isFile) return@withContext false
      runCatching {
        val stream =
          context.contentResolver.openOutputStream(target.uri, "wt") ?: return@withContext false
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        true
      }.getOrDefault(false)
    }

  /** 把 SAF 文档内容拷贝到 App 私有缓存，返回 FileProvider uri（用于打开/分享）。 */
  private suspend fun toFileProviderUri(document: DocumentFile): Uri? = withContext(Dispatchers.IO) {
    if (!document.isFile) return@withContext null
    runCatching {
      val cacheFile = File(context.cacheDir, "share/${document.name ?: "file"}").apply {
        parentFile?.mkdirs()
      }
      context.contentResolver.openInputStream(document.uri)?.use { input ->
        FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
      }
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        cacheFile,
      )
    }.getOrNull()
  }

  /** 打开文件：拷贝到缓存后 ACTION_VIEW 交给系统文件选择器。 */
  suspend fun openFile(document: DocumentFile, fallbackUri: Uri) {
    val uri = toFileProviderUri(document) ?: fallbackUri
    val mime = document.type ?: "*/*"
    val intent =
      Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    withContext(Dispatchers.Main) {
      runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }
  }

  /** 分享文件：拷贝到缓存后 ACTION_SEND 交给系统分享面板。 */
  suspend fun shareFile(document: DocumentFile, fallbackUri: Uri) {
    val uri = toFileProviderUri(document) ?: fallbackUri
    val intent =
      Intent(Intent.ACTION_SEND)
        .setType(document.type ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    withContext(Dispatchers.Main) {
      runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }
  }
}
