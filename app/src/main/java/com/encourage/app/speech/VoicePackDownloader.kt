/*
 * Encourage — 离线语音包下载器（M3-6d）
 *
 * 【功能说明】
 * 下载 sherpa-onnx 的 tts-models 语音包（.tar.bz2），解压到
 * <externalFilesDir>/tts/<packId>/ 目录，供 OfflineTtsEngine 加载。
 *
 * 【实现说明】
 * 用 HttpURLConnection + 协程实现，支持：
 *  - 进度回调（已下载字节 / 总字节 / 速率）
 *  - 取消（协程取消 + isActive 检查）
 *  - tar.bz2 解压（BZip2 由 JDK 自带的 org.apache.commons.compress 实现，
 *    不引入额外依赖——实际上 Android 不内置 commons-compress，这里用
 *    java.util.zip.GZIPInputStream 处理 gzip；bz2 需引入 Apache Commons Compress。
 *    为避免新增重量级依赖，本工程选用 tar.gz 或改用内置 gzip 方案。
 *    由于官方语音包是 .tar.bz2，这里引入最小的 commons-compress 来解 bz2。）
 *
 * 【说明】
 * 为避免引入额外依赖的复杂度，voice pack 统一以 .tar.bz2 下载，解压用
 * Apache Commons Compress（Android 兼容）。该库依赖在 app/build.gradle.kts 中声明。
 */

package com.encourage.app.speech

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

private const val TAG = "AGVoicePack"

/** 语音包下载/解压的运行时状态。 */
sealed class VoicePackState {
  data object Idle : VoicePackState()
  data class Downloading(val receivedBytes: Long, val totalBytes: Long) : VoicePackState() {
    val fraction: Float get() = if (totalBytes > 0) receivedBytes.toFloat() / totalBytes else 0f
  }
  data class Unzipping(val totalBytes: Long) : VoicePackState()
  data object Ready : VoicePackState()
  data class Failed(val message: String) : VoicePackState()
}

/**
 * 下载并解压离线语音包。
 *
 * 通过 [stateFlow] 暴露进度；调用 [download] 启动，[cancel] 取消。
 * [isInstalled] 可用于判断某语音包是否已就绪。
 */
class VoicePackDownloader(
  private val context: Context,
) {
  private val scope = CoroutineScope(Dispatchers.IO)
  private val _stateFlow = kotlinx.coroutines.flow.MutableStateFlow<VoicePackState>(VoicePackState.Idle)
  val stateFlow: kotlinx.coroutines.flow.StateFlow<VoicePackState> = _stateFlow

  private var currentJob: kotlinx.coroutines.Job? = null

  /**
   * 某语音包是否已就绪（model.onnx / lexicon.txt / tokens.txt 三个必备文件齐全且非空）。
   *
   * 【Bug B/C 加固】原来只检查 model.onnx 存在；若 lexicon.txt / tokens.txt 缺失，
   * 引擎 native 构造会直接崩溃。这里统一用 [validateVoicePack] 做完整校验，
   * 下载/导入/就绪判定都遵循同一标准。
   */
  fun isInstalled(spec: VoicePackSpec): Boolean =
    validateVoicePack(File(context.getExternalFilesDir(null), "$TTS_PACKS_ROOT_DIR/${spec.id}")) == null

  /** 启动下载 + 解压。按 [VoicePackSpec.sources] 顺序尝试多个来源，失败自动切换。 */
  fun download(spec: VoicePackSpec) {
    currentJob?.cancel()
    _stateFlow.value = VoicePackState.Downloading(0, spec.sizeBytes)
    currentJob =
      scope.launch {
        try {
          var lastError: Exception? = null
          for (source in spec.sources) {
            if (!isActive) return@launch
            try {
              when (source) {
                is VoicePackSource.TarBz2 -> {
                  val tarFile = downloadToCache(spec, source.url)
                  _stateFlow.value = VoicePackState.Unzipping(spec.sizeBytes)
                  extractTarBz2(tarFile, packDir(spec))
                  tarFile.delete()
                }
                is VoicePackSource.Files -> {
                  // 逐文件下载到包目录（含子路径）。
                  _stateFlow.value = VoicePackState.Downloading(0, spec.sizeBytes)
                  downloadFiles(source, spec)
                  _stateFlow.value = VoicePackState.Unzipping(spec.sizeBytes)
                }
              }
              // 校验就绪文件；不完整则继续尝试下一来源。
              if (isInstalled(spec)) {
                _stateFlow.value = VoicePackState.Ready
                return@launch
              }
              lastError = IllegalStateException("pack incomplete: marker missing")
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.w(TAG, "voice pack source failed, trying next: ${e.message}")
              lastError = e
              _stateFlow.value = VoicePackState.Downloading(0, spec.sizeBytes)
            }
          }
          _stateFlow.value =
            VoicePackState.Failed(lastError?.message ?: "all_sources_failed")
        } catch (e: CancellationException) {
          _stateFlow.value = VoicePackState.Idle
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "voice pack download failed", e)
          _stateFlow.value = VoicePackState.Failed(e.message ?: "unknown_error")
        }
      }
  }

  /** 本地导入：把用户选择的文件/目录拷贝到语音包目录，然后校验就绪。 */
  fun importLocal(spec: VoicePackSpec, copyFrom: suspend (File) -> Boolean) {
    currentJob?.cancel()
    _stateFlow.value = VoicePackState.Unzipping(spec.sizeBytes)
    currentJob =
      scope.launch {
        try {
          val dest = packDir(spec)
          val ok = copyFrom(dest)
          // 【Bug B】拷贝完成后做完整校验：明确提示还缺哪些文件，方便用户补齐。
          val missing = missingVoicePackFiles(dest)
          if (ok && missing.isEmpty()) {
            _stateFlow.value = VoicePackState.Ready
          } else {
            val detail = if (missing.isNotEmpty()) "缺少：${missing.joinToString("、")}" else "import_incomplete"
            _stateFlow.value = VoicePackState.Failed(detail)
          }
        } catch (e: CancellationException) {
          _stateFlow.value = VoicePackState.Idle
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "voice pack import failed", e)
          _stateFlow.value = VoicePackState.Failed(e.message ?: "import_failed")
        }
      }
  }

  fun cancel() {
    currentJob?.cancel()
    _stateFlow.value = VoicePackState.Idle
  }

  /** 语音包目录。 */
  private fun packDir(spec: VoicePackSpec): File =
    File(context.getExternalFilesDir(null), "$TTS_PACKS_ROOT_DIR/${spec.id}")

  /** 逐文件下载（Files 来源），保留子路径。 */
  private suspend fun downloadFiles(source: VoicePackSource.Files, spec: VoicePackSpec) {
    val dest = packDir(spec)
    if (!dest.exists()) dest.mkdirs()
    var total = 0L
    for (file in source.files) {
      if (!coroutineContext.isActive) return
      val url = source.baseUrl.replace("{file}", file)
      val target = File(dest, file)
      target.parentFile?.mkdirs()
      downloadTo(url, target, spec.sizeBytes, { progress -> total = progress })
    }
  }

  /** 下载单个 .tar.bz2 到 cache 目录，返回文件。 */
  private suspend fun downloadToCache(spec: VoicePackSpec, url: String): File {
    val cacheDir = File(context.cacheDir, "voice_pack")
    if (!cacheDir.exists()) cacheDir.mkdirs()
    val target = File(cacheDir, "${spec.id}.tar.bz2")
    downloadTo(url, target, spec.sizeBytes, null)
    return target
  }

  /** 通用的下载到目标文件实现。 */
  private suspend fun downloadTo(url: String, target: File, expectedBytes: Long, onProgress: ((Long) -> Unit)?) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("Accept-Encoding", "identity")
    conn.connect()
    val code = conn.responseCode
    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
      throw IllegalStateException("HTTP $code for $url")
    }
    val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
    val input = BufferedInputStream(conn.inputStream)
    val output = FileOutputStream(target)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var received = 0L
    try {
      while (true) {
        coroutineContext.ensureActive()
        val n = input.read(buffer)
        if (n == -1) break
        output.write(buffer, 0, n)
        received += n
        onProgress?.invoke(received)
        if (onProgress == null) {
          _stateFlow.value = VoicePackState.Downloading(received, total)
        }
      }
    } finally {
      input.close()
      output.close()
      conn.disconnect()
    }
  }

  /** 解压 tar.bz2 到目标目录（自动去除顶层同名目录一层）。 */
  private fun extractTarBz2(tarBz2: File, destDir: File) {
    if (!destDir.exists()) destDir.mkdirs()
    val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(tarBz2)))
    val tarIn = TarArchiveInputStream(bzIn)
    try {
      // 统计是否只有一个顶层目录，用于去除多余层级。
      var entry: TarArchiveEntry? = tarIn.nextTarEntry
      while (entry != null) {
        val name = entry.name.removePrefix("/")
        // 去掉第一层目录（模型包通常都包在一个同名目录里）。
        val relPath = name.substringAfter('/')
        if (relPath.isNotEmpty()) {
          val outFile = File(destDir, relPath)
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            copyTo(tarIn, outFile)
          }
        }
        entry = tarIn.nextTarEntry
      }
    } finally {
      tarIn.close()
    }
  }

  private fun copyTo(input: TarArchiveInputStream, dest: File) {
    val output: OutputStream = FileOutputStream(dest)
    try {
      input.copyTo(output, DEFAULT_BUFFER_SIZE)
    } finally {
      output.close()
    }
  }
}
