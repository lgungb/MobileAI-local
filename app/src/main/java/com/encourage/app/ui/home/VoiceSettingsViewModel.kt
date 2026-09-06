/*
 * Encourage — 语音设置 ViewModel（M3 / 需求 D3）
 *
 * 【功能说明】
 * 语音设置页的状态与操作：引擎来源、语速、音调、音色角色。
 * 通过 VoiceSettingsRepository 读写 DataStore<Settings>。
 *
 * 【设计要点】
 * 1. 语速 / 音调用「拖动滑块即时预览 + 松手才落盘」：
 *    UI 层在 onValueChangeFinished 时调用 setRate/setPitch，避免每次拖动
 *    都触发一次 DataStore 写（DataStore 写是有代价的）。
 * 2. 试听按钮直接调 SpeechManager（系统引擎），不经过 ViewModel。
 *
 * 【使用方法】
 *   val viewModel: VoiceSettingsViewModel = hiltViewModel()
 *   val settings by viewModel.settings.collectAsState()
 */

package com.encourage.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.data.speech.VoiceSettings
import com.encourage.app.data.speech.VoiceSettingsRepository
import com.encourage.app.proto.VoiceEngine
import com.encourage.app.speech.TTS_PACKS_ROOT_DIR
import com.encourage.app.speech.VoicePackDownloader
import com.encourage.app.speech.VoicePackState
import com.encourage.app.speech.VoicePacks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 语音设置界面的状态与操作入口。 */
@HiltViewModel
class VoiceSettingsViewModel
@Inject
constructor(
  private val repository: VoiceSettingsRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {

  /** 离线语音包下载器。 */
  private val downloader = VoicePackDownloader(context)

  /** 当前语音设置的响应式状态。 */
  val settings: StateFlow<VoiceSettings> =
    repository.flow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = VoiceSettings(),
    )

  /** 离线语音包下载进度状态。 */
  val packState: StateFlow<VoicePackState> = downloader.stateFlow

  /** 默认中文语音包是否已就绪（决定离线引擎是否可用）。 */
  fun isDefaultPackInstalled(): Boolean = downloader.isInstalled(VoicePacks.ZH_MELO)

  /** 切换引擎来源（系统 TTS / Sherpa-ONNX）。 */
  fun setEngine(engine: VoiceEngine) {
    viewModelScope.launch { repository.setEngine(engine) }
  }

  /** 设置语速（0.5~2.0）。 */
  fun setRate(rate: Float) {
    viewModelScope.launch { repository.setRate(rate) }
  }

  /** 设置音调（0.5~2.0）。 */
  fun setPitch(pitch: Float) {
    viewModelScope.launch { repository.setPitch(pitch) }
  }

  /** 设置音色角色。 */
  fun setRole(role: String) {
    viewModelScope.launch { repository.setRole(role) }
  }

  /** 下载默认中文语音包（ModelScope → hf-mirror → GitHub，按序尝试）。 */
  fun downloadDefaultPack() {
    downloader.download(VoicePacks.ZH_MELO)
  }

  /** 取消语音包下载。 */
  fun cancelPackDownload() {
    downloader.cancel()
  }

  /**
   * 本地导入语音包：把用户通过系统目录选择器选中的【整个目录】递归拷贝到语音包目录。
   *
   * 【Bug B 修复】原来的实现用 OpenMultipleDocuments + DocumentFile.fromSingleUri，
   * 拿到的单文档 URI 无法持久化授权、无法递归枚举目录内容（listFiles 返回空），
   * 导致用户选一个含 model.onnx 的目录时拷贝结果是空目录、导入必失败。
   * 现改用 ActivityResultContracts.OpenDocumentTree + takePersistableUriPermission +
   * DocumentFile.fromTreeUri：tree URI 可持久化授权，能递归枚举并拷贝子文件。
   *
   * @param treeUri OpenDocumentTree 返回的目录 tree URI（已带读授权）。
   */
  fun importDefaultPack(treeUri: Uri) {
    if (treeUri == Uri.EMPTY) return
    // 持久化授权：避免应用进程重启后无法再次读取该目录。
    runCatching {
      context.contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }
    val spec = VoicePacks.ZH_MELO
    downloader.importLocal(spec) { dest ->
      withContext(Dispatchers.IO) {
        runCatching {
          val root = DocumentFile.fromTreeUri(context, treeUri)
          if (root == null || !root.exists()) return@runCatching false
          copyDocumentTree(root, dest)
          // 若用户选的是语音包的父目录（文件落在子目录里），自动提升到包根目录。
          promoteIfNested(dest)
          true
        }.getOrDefault(false)
      }
    }
  }

  /** 递归拷贝 SAF 目录树到本地目录（保留相对结构）。 */
  private fun copyDocumentTree(doc: DocumentFile, destDir: File) {
    if (!doc.isDirectory) {
      copyDocumentFile(doc, destDir)
      return
    }
    if (!destDir.exists()) destDir.mkdirs()
    val children = doc.listFiles() ?: return
    for (child in children) {
      if (child.isDirectory) {
        copyDocumentTree(child, File(destDir, child.name ?: "dir"))
      } else {
        copyDocumentFile(child, destDir)
      }
    }
  }

  /**
   * 若用户选中的是语音包的父目录，文件会被拷到子目录（如 <dest>/vits-melo-tts-zh_en/...），
   * 此时把含 model.onnx 的最浅子目录内容提升到包根目录，保证 isInstalled 判定通过。
   */
  private fun promoteIfNested(destDir: File) {
    if (File(destDir, "model.onnx").exists()) return
    val candidates =
      destDir.walkTopDown()
        .filter { it.isDirectory && File(it, "model.onnx").exists() }
        .toList()
    if (candidates.size == 1) {
      val src = candidates[0]
      src.listFiles()?.forEach { child ->
        val target = File(destDir, child.name)
        if (target.exists()) child.copyRecursively(target, overwrite = true)
        else child.renameTo(target)
      }
      src.deleteRecursively()
    }
  }

  /** 拷贝单个 SAF 文件到 destDir（按原文件名）。 */
  private fun copyDocumentFile(doc: DocumentFile, destDir: File) {
    val name = doc.name ?: return
    val target = File(destDir, name)
    target.parentFile?.mkdirs()
    val input = context.contentResolver.openInputStream(doc.uri) ?: return
    input.use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
  }

  /** 清理未就绪语音包的残留文件（导入/下载失败后）。 */
  fun clearVoicePack() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        runCatching {
          val dir =
            File(context.getExternalFilesDir(null), "$TTS_PACKS_ROOT_DIR/${VoicePacks.ZH_MELO.id}")
          if (dir.exists()) dir.deleteRecursively()
        }
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    downloader.cancel()
  }
}
