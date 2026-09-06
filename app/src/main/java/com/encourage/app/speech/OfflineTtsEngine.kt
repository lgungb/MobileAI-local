/*
 * Encourage — Sherpa-ONNX 离线 TTS 引擎封装（M3-6）
 *
 * 【功能说明】
 * 在端侧用 sherpa-onnx 的 OfflineTts 加载中文 VITS 语音包，将文本合成为 PCM，
 * 并通过 AudioTrack 播放。完全离线，不依赖网络与系统 TTS。
 *
 * 【语音包来源（M3-6b）】
 * 应用内下载（见 VoicePackDownloader）：把 tts-models 的 .tar.bz2 语音包
 * 下载并解压到 <externalFilesDir>/tts/<packId>/ 目录，结构如下（vits-melo）：
 *   model.onnx / lexicon.txt / tokens.txt / dict/ / phone.fst / date.fst / number.fst
 * 初版使用 vits-melo-tts-zh_en（中文+英文，单说话人，sid=0，约 163MB）。
 *
 * 【多角色说明】
 * vits-melo-tts-zh_en 是单说话人模型，因此无论用户在语音设置里选择哪个角色，
 * 初版都合成同一声线。真正多音色需要每个角色一个不同人声的语音包
 * （如 vits-zh-hf-* 系列），本类用 [voicePackForRole] 预留了「角色 → 语音包」映射，
 * 后续补充多语音包即可实现差异化音色，无需改动调用方。
 *
 * 【线程模型（Bug A/C 加固）】
 * - OfflineTts 构造（native 初始化）与 generate（CPU 密集）都必须在后台线程执行；
 *   本类保证 [ensureReady] / [speak] / [speakWithFallback] 内部的 native 调用
 *   只发生在 engineScope（Dispatchers.Default）的协程里，绝不占用主线程。
 * - 构造 native 对象前必须先做【文件完整性校验】（[validateVoicePack]）：
 *   sherpa 的 native 层在模型缺失/损坏时是直接 SIGSEGV/abort，Java try-catch 拦不住，
 *   一旦发生整个 app 闪退。因此【校验不通过绝不构造 OfflineTts】。
 *
 * 【使用方法】
 *   val engine = OfflineTtsEngine(context)
 *   if (engine.ensureReady()) engine.speak("你好", speed = 1.0f) { /* done */ }
 *   engine.stop()
 *   engine.release()   // 与 create 成对调用，避免泄漏
 */

package com.encourage.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import android.util.Log
import com.encourage.app.data.speech.VoiceRoles
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGOfflineTts"

/** 端侧语音包在 externalFilesDir 下的根目录。 */
const val TTS_PACKS_ROOT_DIR = "tts"

/** 默认中文语音包 id（对应 vits-melo-tts-zh_en）。 */
const val DEFAULT_TTS_PACK_ID = "vits-melo-tts-zh_en"

/**
 * 语音包必备文件（vits-melo 关键文件）。
 *
 * 缺少/损坏任一文件时，sherpa native 构造可能直接崩溃（SIGSEGV），无法被
 * try-catch 捕获，因此任何构造 native 的路径都必须先用 [missingVoicePackFiles] 校验。
 * dict/ 目录与 .fst 文件按设计容忍缺失（分词精度/数字规范降级，但不影响发声）。
 */
private val REQUIRED_VOICE_PACK_FILES = listOf("model.onnx", "lexicon.txt", "tokens.txt")

/** model.onnx 的最小合理大小（字节）。vits-melo 模型约 163MB，低于 5MB 视为损坏/不完整。 */
private const val MIN_MODEL_ONNX_BYTES = 5L * 1024 * 1024

/** ENCODING_PCM_FLOAT 下每个采样占用的字节数。 */
private const val FLOAT_SAMPLE_BYTES = 4

/**
 * 校验语音包目录：返回缺失/损坏的文件名列表（空列表 = 完整可用）。
 * 纯文件系统检查，不构造 native 对象，可安全在任何线程（含主线程）调用。
 *
 * @param dir 语音包根目录（应包含 model.onnx / lexicon.txt / tokens.txt）。
 */
fun missingVoicePackFiles(dir: File): List<String> {
  if (!dir.exists()) return listOf("语音包目录不存在")
  val missing = mutableListOf<String>()
  val model = File(dir, "model.onnx")
  if (!model.exists()) {
    missing += "model.onnx"
  } else if (model.length() < MIN_MODEL_ONNX_BYTES) {
    missing += "model.onnx（大小异常，可能未拷贝完整）"
  }
  // lexicon / tokens 必须存在且非空，0 字节文件视为缺失，防止引擎构造崩溃。
  val lexicon = File(dir, "lexicon.txt")
  if (!lexicon.exists() || lexicon.length() == 0L) missing += "lexicon.txt"
  val tokens = File(dir, "tokens.txt")
  if (!tokens.exists() || tokens.length() == 0L) missing += "tokens.txt"
  return missing
}

/**
 * 校验语音包目录，返回错误描述；null 表示完整可用。
 * 供下载/导入完成后的完整性判定，以及构造 native 前的安全检查共用。
 */
fun validateVoicePack(dir: File): String? {
  val missing = missingVoicePackFiles(dir)
  return if (missing.isEmpty()) null else "缺少文件：${missing.joinToString("、")}"
}

/**
 * 一个语音包下载来源。
 *
 * - [TarBz2]：单个 .tar.bz2 压缩包（官方 GitHub release / 镜像），下载后解压到包目录。
 * - [Files]：逐文件下载（ModelScope / HuggingFace 镜像的 raw 文件），每个文件直接落到包目录。
 *   多个来源按 [VoicePackSpec.sources] 顺序尝试，失败自动切换到下一个。
 */
sealed class VoicePackSource {
  data class TarBz2(val url: String) : VoicePackSource()

  /** baseUrl 需含 `{file}` 占位符，会被替换为 files 中的每一项。 */
  data class Files(val baseUrl: String, val files: List<String>) : VoicePackSource()
}

/** 一个语音包的定义：id 即解压后的目录名，sources 为可用的下载来源（按序尝试）。 */
data class VoicePackSpec(
  val id: String,
  val sources: List<VoicePackSource>,
  /** 打包后的总大小（字节），用于下载进度显示（估算值）。 */
  val sizeBytes: Long,
  /** 模型内主要文件（判断语音包是否已就绪）。 */
  val markerFile: String = "model.onnx",
  val sampleRate: Int = 22050,
)

/**
 * 官方 tts-models 语音包清单。
 *
 * 国内下载策略（N2）：
 * 1. 优先 ModelScope（www.modelscope.cn）与 HuggingFace 国内镜像（hf-mirror.com），
 *    两者都按文件逐个下载，网络更友好；
 * 2. 回退到 GitHub release 的 .tar.bz2 压缩包（含官方 gh-proxy 镜像，方便无法直连 GitHub 的用户）；
 * 3. 若所有来源都失败，用户可手动「本地导入」模型文件（见 VoiceSettings 的导入入口）。
 */
object VoicePacks {
  /** vits-melo 模型逐文件清单（dict/ 目录按需容忍缺失，见 buildTtsConfig）。 */
  private val ZH_MELO_FILES =
    listOf(
      "model.onnx",
      "lexicon.txt",
      "tokens.txt",
      "date.fst",
      "number.fst",
      "phone.fst",
    )

  /** vits-melo-tts-zh_en：中英文、单说话人。 */
  val ZH_MELO =
    VoicePackSpec(
      id = DEFAULT_TTS_PACK_ID,
      sources =
        listOf(
          // 1) ModelScope：sherpa tts 模型仓库（逐文件）。
          VoicePackSource.Files(
            baseUrl =
              "https://www.modelscope.cn/models/csukuangfj/tts-models/resolve/master/" +
                "vits-melo-tts-zh_en/{file}",
            files = ZH_MELO_FILES,
          ),
          // 2) HuggingFace 国内镜像（逐文件）。
          VoicePackSource.Files(
            baseUrl =
              "https://hf-mirror.com/csukuangfj/vits-melo-tts-zh_en/resolve/main/{file}",
            files = ZH_MELO_FILES,
          ),
          // 3) GitHub release 官方 .tar.bz2（国内不可直连时最后尝试）。
          VoicePackSource.TarBz2(
            url =
              "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "vits-melo-tts-zh_en.tar.bz2"
          ),
        ),
      sizeBytes = 163_000_000L,
    )

  /** 全部可选语音包（初版只发布默认中文包）。 */
  val ALL: List<VoicePackSpec> = listOf(ZH_MELO)

  fun byId(id: String?): VoicePackSpec? = ALL.firstOrNull { it.id == id }
}

/**
 * Sherpa-ONNX 离线 TTS 引擎。
 *
 * 线程安全：加载与播放都在 [engineScope]（Default 调度器）中串行进行，
 * 避免 OfflineTts 被多线程并发调用导致崩溃。[ensureReady] 用 @Synchronized
 * 保证同一时刻只有一个线程执行 native 构造。
 */
class OfflineTtsEngine(
  private val context: Context,
  private val packSpec: VoicePackSpec = VoicePacks.ZH_MELO,
) {
  private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  /**
   * 播放/合成互斥锁：保证 release() 释放 native 对象前，当前播放/合成已完全结束，
   * 杜绝 native use-after-free 导致的 SIGSEGV 闪退。
   * ensureReady / doSpeakInternal / play 都在锁内执行 native 调用。
   */
  private val playMutex = Mutex()

  /** 引擎已被释放：释放后所有 native 调用直接拒绝，避免操作已 free 的对象。 */
  @Volatile private var released = false

  private var tts: OfflineTts? = null
  private var audioTrack: AudioTrack? = null

  /** 当前播放/合成任务的引用，新任务或 stop 时取消旧任务，避免并发播放冲突。 */
  private var playbackJob: Job? = null

  /** 引擎已加载语音包、可合成。 */
  @Volatile var isReady: Boolean = false
    private set

  /** 引擎加载失败的根因（界面提示用）。 */
  @Volatile var lastError: String? = null
    private set

  /** lastError 的响应式版本，供 Compose 界面实时展示错误原因。 */
  private val _lastErrorFlow = MutableStateFlow<String?>(null)
  val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

  /** 当前是否正在播放。 */
  @Volatile var isSpeaking: Boolean = false
    private set

  /** 停止标志：内部对正在生成的请求置 true，让回调提前返回。 */
  @Volatile private var stopRequested = false

  /** 语音包在 externalFilesDir 下的目录。 */
  private fun packDir(): File = File(context.getExternalFilesDir(null), "$TTS_PACKS_ROOT_DIR/${packSpec.id}")

  /** 语音包是否已下载并解压（以 model.onnx 存在为准，仅作粗略判断）。 */
  fun isModelDownloaded(): Boolean = File(packDir(), packSpec.markerFile).exists()

  /**
   * 语音包文件是否完整可用（纯文件校验，不构造 native，可安全在主线程调用）。
   * 这是「是否值得走离线引擎」的快速判定；真正的 native 初始化由 [speakWithFallback]
   * 在后台线程完成。
   */
  fun isModelValid(): Boolean = validateVoicePack(packDir()) == null

  /** 语音包所在目录的绝对路径（供下载解压与加载共用）。 */
  fun modelDirPath(): String = packDir().absolutePath

  /**
   * 尝试加载语音包到 [OfflineTts]。若语音包未下载/不完整，返回 false 并记录原因。
   *
   * 【Bug C 加固】
   * 1. 构造 native 对象前先做文件完整性校验（model.onnx/lexicon.txt/tokens.txt），
   *    不满足直接返回 false 并记录 [lastError]，【绝不构造 native 对象】——
   *    这是根治「模型缺失时 native SIGSEGV 闪退」的关键。
   * 2. 本方法必须在后台线程调用（内部有主线程守卫），调用方（speak/speakWithFallback）
   *    已保证在 engineScope 协程内执行。
   * 可重复调用：已就绪时直接返回 true；失败后重新下载/导入可再次调用。
   */
  @Synchronized
  fun ensureReady(): Boolean {
    if (released) return false
    if (isReady && tts != null) return true
    // 防御：native 初始化绝不允许在主线程执行（可能 ANR 甚至 native crash）。
    if (Looper.myLooper() == Looper.getMainLooper()) {
      updateLastError("离线引擎初始化不允许在主线程执行")
      Log.e(TAG, "ensureReady called on main thread, skip native init")
      return false
    }
    val dir = packDir()
    // 关键防护：文件不完整时绝不构造 OfflineTts（native 层缺失文件会直接 crash）。
    val invalid = validateVoicePack(dir)
    if (invalid != null) {
      updateLastError(invalid)
      Log.w(TAG, "voice pack invalid: $invalid")
      return false
    }
    return try {
      releaseEngineLocked()
      val config = buildTtsConfig(dir)
      tts = OfflineTts(assetManager = null, config = config)
      isReady = true
      updateLastError(null)
      Log.i(TAG, "OfflineTts loaded: ${packSpec.id}")
      true
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to init OfflineTts", e)
      updateLastError(e.message ?: "init_failed")
      isReady = false
      tts?.let { runCatching { it.free() } }
      tts = null
      false
    }
  }

  /** 根据语音包目录构造 [OfflineTtsConfig]（vits-melo 中文包专用）。 */
  private fun buildTtsConfig(dir: File): OfflineTtsConfig {
    val base = dir.absolutePath
    val vits =
      OfflineTtsVitsModelConfig(
        model = "$base/model.onnx",
        lexicon = "$base/lexicon.txt",
        tokens = "$base/tokens.txt",
        dataDir = "", // 中文包不依赖 espeak-ng-data
        dictDir =
          // 【N2】dict/（Jieba 字典）在逐文件下载时可能缺失；缺失时置空，
          // 引擎退化为按字切分，仍可朗读中文，只是分词精度略降。
          if (File(dir, "dict").exists()) "$base/dict" else "",
      )
    // 【低配置手机保护】根据CPU核心数动态决定推理线程数：
    // 4核及以下用2线程（兼顾速度与发热），
    // 4核以上用4线程（速度优先）。绝不超过4线程，防止低端机过热。
    val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val numThreads = if (cpuCores <= 4) 2 else 4
    Log.i(TAG, "TTS threads=$numThreads (cpuCores=$cpuCores)")
    val modelConfig =
      OfflineTtsModelConfig(
        vits = vits,
        numThreads = numThreads,
        debug = false,
        provider = "cpu",
      )
    val ruleFsts =
      listOf("phone.fst", "date.fst", "number.fst")
        .filter { File(dir, it).exists() } // 【N2】容忍缺失的 .fst 文件
        .joinToString(",") { "$base/$it" } // 数字/日期/标点规范
    // 【防闪退】ruleFsts 为空字符串时 sherpa-onnx native 层可能崩溃，
    // 此时不传该参数，使用库默认值。
    return if (ruleFsts.isNotEmpty()) {
      OfflineTtsConfig(
        model = modelConfig,
        ruleFsts = ruleFsts,
        maxNumSentences = 1,
      )
    } else {
      OfflineTtsConfig(
        model = modelConfig,
        maxNumSentences = 1,
      )
    }
  }

  /**
   * 合成并播放文本（异步）。
   *
   * native 初始化 / 合成 / 播放全部在后台线程串行完成，调用方线程只负责入队。
   *
   * @param text 待朗读文本。
   * @param speed 语速倍率（0.5~2.0），对应 sherpa 的 speed 参数。
   * @param onDone 播放结束（或被 stop 中断）后的回调，在主线程执行。
   * @return 是否已成功入队；文本为空时返回 false。
   */
  fun speak(text: String, speed: Float = 1.0f, onDone: (() -> Unit)? = null): Boolean =
    speakWithFallback(text = text, speed = speed, onDone = onDone, fallback = {})

  /**
   * 合成并播放文本（异步）；若引擎初始化失败则回退到 [fallback]（在主线程执行）。
   *
   * 【Bug A 修复】这是 SpeechManager 的分派入口：文件校验通过即入队，native 初始化
   * 在后台完成；万一 native 加载失败（如 onnx 损坏），自动回退系统 TTS，避免
   * 「选了离线引擎却无声」的静默失败。
   */
  fun speakWithFallback(
    text: String,
    speed: Float = 1.0f,
    onDone: (() -> Unit)? = null,
    fallback: () -> Unit = {},
  ): Boolean {
    val content = text.trim()
    if (content.isEmpty()) return false
    if (released) {
      Log.w(TAG, "speakWithFallback called after release, fallback")
      fallback()
      return false
    }
    // 先请求停止旧任务（让旧播放循环检测到 stopRequested 后退出），
    // 再 cancel 旧协程。新协程拿到 playMutex 后才重置 stopRequested=false，
    // 确保旧任务已完全停止，避免旧循环因标志被提前重置而继续运行。
    stopRequested = true
    playbackJob?.cancel()
    playbackJob =
      engineScope.launch {
        // 整个 native 流程（初始化 + 合成 + 播放）在互斥锁内，
        // 保证 release() 必须等当前任务结束才能 free native 对象。
        playMutex.withLock {
          if (released) {
            withContext(Dispatchers.Main) { fallback() }
            return@launch
          }
          // 拿到锁后再重置停止标志，此时旧任务已退出。
          stopRequested = false
          try {
            // ensureReady 内部的 native 构造现在跑在后台线程，安全且不阻塞主线程。
            if (!ensureReady()) {
              Log.w(TAG, "offline engine not ready (${lastError}), fallback to system TTS")
              // 回退逻辑切回主线程执行，避免 UI 线程安全问题。
              runCatching { withContext(Dispatchers.Main) { fallback() } }
              return@launch
            }
            doSpeakInternal(content, speed)
          } finally {
            isSpeaking = false
            onDone?.let { runCatching { withContext(Dispatchers.Main) { it() } } }
            // 【延迟释放】如果 release() 在播放期间被调用（tryLock 失败），
            // 在这里 native 调用已全部结束，安全释放 native 对象。
            if (released) {
              releaseEngineLocked()
            }
          }
        }
      }
    return true
  }

  /** 在后台线程执行合成（native 调用全部 try-catch，失败只记 lastError，不崩溃）。 */
  private suspend fun doSpeakInternal(text: String, speed: Float) {
    val engine = tts ?: return
    val genConfig =
      com.k2fsa.sherpa.onnx.GenerationConfig(
        sid = 0, // vits-melo 单说话人
        speed = speed.coerceIn(0.5f, 2.0f),
        silenceScale = 0.2f,
      )
    val audio: GeneratedAudio? =
      try {
        // 用回调检测停止：返回 0 立即终止合成。
        engine.generateWithConfigAndCallback(
          text = text,
          config = genConfig,
          callback = { _: FloatArray -> if (stopRequested) 0 else 1 },
        )
      } catch (e: Throwable) {
        Log.e(TAG, "generate failed", e)
        updateLastError(e.message ?: "generate_failed")
        null
      }
    if (audio == null || stopRequested) return
    play(audio)
  }

  /**
   * 用 AudioTrack 播放整段 PCM（MODE_STATIC，一次性写入）。
   *
   * 【Bug C 加固】缓冲大小计算、FLOAT 编码 write、返回值与异常都做了防御：
   * - getMinBufferSize 可能返回负错误码，此时直接用数据大小兜底；
   * - write 返回负数视为失败并记录 lastError，不继续 play；
   * - 构建/播放全程 try-catch，任何异常只记录，绝不让 app 崩溃。
   */
  private fun play(audio: GeneratedAudio) {
    if (audio.samples.isEmpty()) return
    val sampleRate = audio.sampleRate
    // ENCODING_PCM_FLOAT 下每采样 4 字节。
    val dataBytes = audio.samples.size * FLOAT_SAMPLE_BYTES
    val minBuffer =
      AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    val bufferSize = if (minBuffer > 0) maxOf(minBuffer, dataBytes) else dataBytes
    stopAudioTrack()
    val track =
      try {
        AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          .setAudioFormat(
            AudioFormat.Builder()
              .setSampleRate(sampleRate)
              .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .build()
          )
          .setTransferMode(AudioTrack.MODE_STATIC)
          .setBufferSizeInBytes(bufferSize)
          .build()
      } catch (e: Throwable) {
        Log.e(TAG, "AudioTrack build failed", e)
        updateLastError(e.message ?: "audio_track_build_failed")
        return
      }
    audioTrack = track
    try {
      // WRITE_BLOCKING 写满缓冲；返回值 < 0 表示错误，< 数据量表示未写完。
      val written = track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
      if (written < 0) {
        Log.e(TAG, "AudioTrack write failed, code=$written")
        updateLastError("audio_write_failed($written)")
        return
      }
      if (written < audio.samples.size) {
        Log.w(TAG, "AudioTrack wrote $written/${audio.samples.size} floats")
      }
      isSpeaking = true
      track.play()
      // 【防卡死】用 playbackHeadPosition 检测播放完成，不依赖 playState。
      // 某些设备 MODE_STATIC 播完后 playState 仍为 PLAYSTATE_PLAYING，
      // 导致循环永久卡住 → playMutex 被持有 → 后续试听全部无反应。
      // 单声道：帧数 = 采样数。添加超时保护（音频时长 + 5秒）。
      val totalFrames = audio.samples.size
      val startTime = System.currentTimeMillis()
      val timeoutMs = (totalFrames * 1000L / sampleRate.coerceAtLeast(1)) + 5000L
      while (!stopRequested && !released) {
        val position =
          try {
            track.playbackHeadPosition
          } catch (_: Throwable) {
            break
          }
        if (position >= totalFrames) break
        if (System.currentTimeMillis() - startTime > timeoutMs) {
          Log.w(TAG, "play timeout: pos=$position/$totalFrames")
          break
        }
        Thread.sleep(20)
      }
    } catch (e: Throwable) {
      Log.e(TAG, "AudioTrack play failed", e)
      updateLastError(e.message ?: "audio_play_failed")
    } finally {
      stopAudioTrack()
      isSpeaking = false
    }
  }

  /**
   * 停止当前播放（并请求中止尚未完成的合成）。
   *
   * 【防崩溃】只设置 stopRequested 标志并 cancel 协程，不直接操作 AudioTrack。
   * AudioTrack 的释放由 play() 的 finally 块负责，避免 stop() 与 play() 在不同
   * 线程并发操作同一 AudioTrack（pause/flush/release vs play/write）导致 native 崩溃。
   */
  fun stop() {
    stopRequested = true
    playbackJob?.cancel()
    isSpeaking = false
  }

  private fun stopAudioTrack() {
    val track = audioTrack
    audioTrack = null
    runCatching {
      track?.let {
        if (it.state == AudioTrack.STATE_INITIALIZED) {
          if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
            it.pause()
          }
          it.stop()
          it.flush()
        }
        it.release()
      }
    }
  }

  /**
   * 释放引擎，必须与创建成对调用。
   *
   * 【防闪退加固】设置 released 标志后，先 stop() 中止当前播放，再尝试获取 playMutex。
   * - 拿到锁：说明当前没有 native 调用在执行，立即 free native 对象；
   * - 拿不到锁：说明正在播放/合成，不强制 free，由 playMutex.withLock 的 finally 块
   *   检查 released 标志后释放，杜绝 use-after-free 导致的 SIGSEGV。
   * engineScope 被 cancel 后，正在执行的 native 调用（generate）不可中断，但
   * stopRequested=true 会让播放循环立即退出，native 对象在 finally 中安全释放。
   */
  fun release() {
    if (released) return
    released = true
    stop()
    engineScope.coroutineContext[Job]?.cancel()
    // 无播放任务时立即释放；有播放任务时由 doSpeakInternal 的 finally 延迟释放。
    if (playMutex.tryLock()) {
      try {
        releaseEngineLocked()
      } finally {
        playMutex.unlock()
      }
    }
  }

  @Synchronized
  private fun releaseEngineLocked() {
    isReady = false
    tts?.let { runCatching { it.free() } }
    tts = null
  }

  /** 统一更新 lastError 与其响应式 StateFlow，保证 UI 能实时看到原因。 */
  private fun updateLastError(message: String?) {
    lastError = message
    _lastErrorFlow.value = message
  }

  /** 角色 → 语音包 id 的映射。初版全部落到默认包（单说话人），预留多包扩展。 */
  companion object {
    fun voicePackForRole(roleId: String): String = DEFAULT_TTS_PACK_ID

    // 供 UI 判断：用户选择需要离线的角色时，检查默认语音包是否就绪。
    fun defaultPackId(): String = DEFAULT_TTS_PACK_ID
  }
}
