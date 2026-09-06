/*
 * Encourage — 文件管理器 ViewModel（M6-H6）
 *
 * 【职责】
 * 维护文件管理器状态：根目录授权、当前目录栈、当前目录子项、操作对话框，
 * 并把数据层（FileManagerRepository）的 I/O 调度到协程。
 *
 * 【设计要点】
 * 目录栈直接持有 DocumentFile 对象（根由 treeUri 创建、子项由 listFiles 得到，
 * 均可继续向下导航），避免用 uri 重建的不稳定；路径文本用独立的分段栈拼接。
 */

package com.encourage.app.ui.filemanager

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.encourage.app.data.filemanager.FileEntry
import com.encourage.app.data.filemanager.FileManagerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FileManagerViewModel
@Inject
constructor(
  private val repository: FileManagerRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {

  /** 当前目录的条目（文件夹 + 文件，文件夹在前）。 */
  private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
  val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()

  /** 是否已授权根目录。 */
  private val _authorized = MutableStateFlow(false)
  val authorized: StateFlow<Boolean> = _authorized.asStateFlow()

  /** 当前路径文本（如 /Download）。 */
  private val _currentPath = MutableStateFlow("")
  val currentPath: StateFlow<String> = _currentPath.asStateFlow()

  /** 一次性提示（成功/失败）。 */
  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()

  /** 当前目录栈（根在底部）。 */
  private val dirStack = ArrayDeque<DocumentFile>()

  /** 路径分段栈（与 dirStack 一一对应）。 */
  private val pathStack = ArrayDeque<String>()

  init {
    _authorized.value = repository.isAuthorized()
    if (_authorized.value) {
      reloadRoot()
    }
  }

  /** 用户通过系统选择器授权根目录。 */
  fun authorize(rootUri: Uri) {
    repository.saveTreeUri(rootUri)
    _authorized.value = true
    reloadRoot()
  }

  /** 重新加载根目录并清空栈。 */
  private fun reloadRoot() {
    val root = repository.rootDocument()
    if (root == null) {
      _authorized.value = false
      repository.saveTreeUri(null)
      dirStack.clear()
      pathStack.clear()
      _entries.value = emptyList()
      _currentPath.value = ""
      return
    }
    dirStack.clear()
    pathStack.clear()
    dirStack.addLast(root)
    pathStack.addLast(root.name ?: "/")
    refresh(dirStack.last())
  }

  /** 进入子目录。 */
  fun navigateInto(entry: FileEntry) {
    if (!entry.isDirectory) return
    // 【N4】不能用 DocumentFile.fromSingleUri(context, entry.uri)：listFiles() 返回的是
    // 树子文档 URI（document://.../tree/.../document/...），fromSingleUri 无法解析这类
    // 文档为可用的目录 DocumentFile，导致进入子目录失败甚至崩溃。
    // 正确做法：用当前父目录的 findFile(name) 拿到同一棵树下的子目录 DocumentFile，
    // 它保留了树的授权上下文，可继续 listFiles() 向下导航。
    val parent = dirStack.lastOrNull() ?: return
    val doc = runCatching { parent.findFile(entry.name) }.getOrNull()
    if (doc == null || !doc.isDirectory) return
    dirStack.addLast(doc)
    pathStack.addLast(doc.name ?: entry.name)
    refresh(doc)
  }

  /** 返回上一级；已在根时无操作。 */
  fun navigateUp() {
    if (dirStack.size <= 1) return
    dirStack.removeLast()
    pathStack.removeLast()
    refresh(dirStack.last())
  }

  /** 回到根目录。 */
  fun goToRoot() {
    while (dirStack.size > 1) {
      dirStack.removeLast()
      pathStack.removeLast()
    }
    refresh(dirStack.first())
  }

  /** 刷新当前目录。 */
  fun refreshCurrent() {
    if (dirStack.isNotEmpty()) refresh(dirStack.last())
  }

  /** 新建子目录。 */
  fun createDirectory(name: String) {
    val parent = dirStack.lastOrNull() ?: return
    viewModelScope.launch {
      val ok = repository.createDirectory(parent, name)
      _message.value = if (ok) null else "create_failed"
      if (ok) refreshCurrent()
    }
  }

  /** 重命名。 */
  fun rename(entry: FileEntry, newName: String) {
    val doc = resolveDocument(entry) ?: return
    viewModelScope.launch {
      val ok = repository.rename(doc, newName)
      _message.value = if (ok) null else "rename_failed"
      if (ok) refreshCurrent()
    }
  }

  /** 删除。 */
  fun delete(entry: FileEntry) {
    val doc = resolveDocument(entry) ?: return
    viewModelScope.launch {
      val ok = repository.delete(doc)
      _message.value = if (ok) null else "delete_failed"
      if (ok) refreshCurrent()
    }
  }

  /** 打开文件。 */
  fun open(entry: FileEntry) {
    val doc = resolveDocument(entry) ?: return
    viewModelScope.launch { repository.openFile(doc, entry.uri) }
  }

  /** 分享文件。 */
  fun share(entry: FileEntry) {
    val doc = resolveDocument(entry) ?: return
    viewModelScope.launch { repository.shareFile(doc, entry.uri) }
  }

  /** 读取文本文件内容（txt 查看）。 */
  fun readTextFile(entry: FileEntry, onResult: (String?) -> Unit) {
    val doc = resolveDocument(entry) ?: run { onResult(null); return }
    viewModelScope.launch { onResult(repository.readTextFile(doc)) }
  }

  /** 写入文本文件内容（txt 编辑）。 */
  fun writeTextFile(entry: FileEntry, newContent: String, onResult: (Boolean) -> Unit) {
    val doc = resolveDocument(entry) ?: run { onResult(false); return }
    viewModelScope.launch { onResult(repository.writeTextFile(doc, newContent)) }
  }

  /**
   * 把列表条目解析为当前目录树下的可用 DocumentFile。
   * 优先用父目录 findFile(name)；失败时回退直接使用该条目的 uri（对根级文件也安全）。
   */
  private fun resolveDocument(entry: FileEntry): DocumentFile? {
    val parent = dirStack.lastOrNull()
    if (parent != null) {
      runCatching { parent.findFile(entry.name) }.getOrNull()?.let { found ->
        if (found != null) return found
      }
    }
    // 兜底：找不到父子文档时，退回单文档解析。
    return runCatching { DocumentFile.fromSingleUri(context, entry.uri) }.getOrNull()
  }

  /** 消费并清空一次性提示。 */
  fun consumeMessage() {
    _message.value = null
  }

  /** 刷新目录（IO 线程列子项，并更新路径文本）。 */
  private fun refresh(dir: DocumentFile) {
    viewModelScope.launch {
      _entries.value = repository.listChildren(dir)
      _currentPath.value = pathStack.joinToString(prefix = "/")
    }
  }
}
