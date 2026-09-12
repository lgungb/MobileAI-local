/*
 * Encourage — 语音朗读管理器（M1 基础版 / M3 升级为双引擎调度）
 *
 * 【功能说明】
 * 朗读文本，供对话气泡上的「朗读」按钮、语音设置页的「试听」、
 * 以及 M3 定时任务的触发提醒（D2）调用。
 *
 * 【引擎策略（M3）】
 * 本类现在是「双引擎调度器」：
 * - 引擎来源由 VoiceSettingsRepository 持久化（系统 TTS / Sherpa-ONNX）；
 * - 系统 TTS 引擎：安卓内置能力，零额外依赖，随系统语言自动支持中文；
 * - Sherpa-ONNX 离线引擎：M3-6 接入，本类预留 engine 分派点，
 *   离线引擎未就绪时自动回退系统 TTS，上层调用方无感知。
 *
 * 【使用方法】
 *   // 在 Composable 中获取（生命周期安全）
 *   val speechManager = rememberSpeechManager()
 *   IconButton(onClick = { speechManager.speak(message.content) }) { ... }
 *
 *   // 停止朗读
 *   speechManager.stop()
 *
 * 【N6-A 可观测性改造】
 * 用户反馈「有界面、点了朗读/试听按钮没声音」，而此前两轮修复都改的是 Kotlin 逻辑、
 * 问题依旧 —— 根因其实在 AndroidManifest 缺 <queries> 声明（Android 11+ 包可见性），
 * 导致系统 TTS 引擎查询/绑定失败。这类「静默失败」过去完全不可见，只能盲修。
 * 本轮把下面所有静默失败点统一改成「打日志 + 暴露可读错误」：
 *   1. 引擎构造异常 / onInit 非 SUCCESS  → 记录 [initFailureReason]，日志 TAG=AGSpeechManager；
 *   2. 绑定失败（setOnUtteranceProgressListener 返回非 SUCCESS）→ 打日志；
 *   3. speak() 因引擎不可用提前 return → 打日志并写入 [lastError] / [lastErrorFlow]；
 *   4. TextToSpeech.speak() 返回 ERROR、UtteranceProgressListener.onError → 打日志并写入错误。
 * 界面侧（VoiceSettingsDialog / ChatPanel）通过 [lastErrorFlow] 与 [lastError]
 * 把原因展示给用户，避免「点了没反应」再次发生。
 *
 * 【注意事项】
 * 1. TextToSpeech 初始化是异步的，未初始化完成就调用 speak() 会静默失败，
 *    因此内部做了「待朗读队列」：初始化完成前调用会先缓存，初始化成功后自动补播。
 *    注意：若 onInit 永远不被回调（引擎绑定卡死），待朗读文本会一直挂着不播，
 *    因此日志里会记录「deferred」次数，便于定位。
 * 2. 必须在不再使用时调用 shutdown() 释放引擎，否则会泄漏；这里通过
 *    DisposableEffect 与 Composable 生命周期绑定，自动释放。
 * 3. 语速 / 音调在 speak() 时按 VoiceSettings 生效；系统引擎采用 setSpeechRate/
 *    setPitch 实现（安卓原生支持 0.5~2.0）。
 * 4. 排查「不出声」三步法（依次看 logcat 过滤 AGSpeechManager）：
 *    a. 有没有 "onInit status=0(SUCCESS)"？没有就是引擎不可用，看后面的 reason；
 *    b. 有没有 "speak() dispatched"？没有说明在 speak() 里被提前 return；
 *    c. 有没有 "Utterance error"？有说明引擎本身合成/播放失败。
 */

package com.encourage.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

private const val TAG = "AGSpeechManager"

/**
 * 语音朗读管理器（双引擎调度）。
 *
 * 线程安全说明：TextToSpeech 的回调发生在主线程，内部状态用 @Volatile 标记，
 * 保证跨线程可见性。
 *
 * M3-6：支持双引擎真正分派：
 * - 系统 TTS：安卓内置能力，零额外依赖；
 * - Sherpa-ONNX 离线引擎：由 [offlineTtsEngine] 提供。当语音设置选择离线引擎且
 *   语音包已就绪时，走离线合成；否则自动回退系统 TTS，上层调用方无感知。
 *
 * @param voiceSettingsProvider 语音设置提供方；为空时使用默认设置（正常语速音调）。
 *   通过函数而不是直接持有 VoiceSettingsRepository，避免本类与 DataStore 强耦合，
 *   也便于 Composable 场景每次朗读时读取最新设置。
 * @param offlineTtsEngine 可选的离线引擎实例。为 null 或未就绪时，离线分派回退系统 TTS。
 */
class SpeechManager(
  private val context: Context,
  voiceSettingsProvider: (() -> VoiceSettingsSnapshot)? = null,
  private val offlineTtsEngine: OfflineTtsEngine? = null,
) : TextToSpeech.OnInitListener {

  /**
   * 语音设置提供方（可变引用，非 val）。
   *
   * 【Bug A 修复】Compose 每次重组都会新建 provider lambda；若把它作为
   * remember 的 key，SpeechManager（及内部 TextToSpeech）会被反复重建，
   * 导致朗读不稳定/无声。这里改为可变属性，由 rememberSpeechManager 在
   * 每次重组时更新引用，保证 speak 时读到最新设置且实例保持稳定。
   */
  @Volatile
  var voiceSettingsProvider: (() -> VoiceSettingsSnapshot)? = voiceSettingsProvider

  private var tts: TextToSpeech? = null

  /** 引擎是否初始化完成。 */
  @Volatile private var ready = false

  /** 系统 TTS 是否初始化失败（语音引擎不可用）。 */
  @Volatile var initFailed: Boolean = false
    private set

  /**
   * 【N6-A】初始化失败的可读原因（如 "ERROR（...）"）。为空表示尚未失败。
   *
   * 供日志与界面排查使用：过去只能看到 initFailed=true 却不知道为什么，
   * 现在能直接读出具体失败码含义。
   */
  @Volatile var initFailureReason: String? = null
    private set

  /** 最近一次错误描述的非响应式快照，供 onClick 回调里同步读取（无需走 Flow）。 */
  @Volatile var lastError: String? = null
    private set

  /** lastError 的响应式版本，供 Compose 界面实时展示错误原因（同 OfflineTtsEngine 的做法）。 */
  private val _lastErrorFlow = MutableStateFlow<String?>(null)
  val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

  /** 统一更新 lastError 与其响应式 StateFlow，保证 UI 与日志看到的是同一份原因。 */
  private fun updateLastError(message: String?) {
    lastError = message
    _lastErrorFlow.value = message
  }

  /** 初始化完成前缓存的待朗读文本（只保留最后一次，避免堆积）。 */
  private var pendingText: String? = null

  /** 当前是否正在朗读，供界面切换「朗读/停止」图标。 */
  @Volatile var isSpeaking: Boolean = false
    private set

  init {
    try {
      Log.i(TAG, "Creating TextToSpeech engine...")
      val engine = TextToSpeech(context.applicationContext, this)
      tts = engine
      // setOnUtteranceProgressListener 返回非 SUCCESS 说明引擎实例本身不可用
      // （典型场景：Android 11+ 包可见性未声明 <queries> TTS_SERVICE，框架绑定不到引擎）。
      // 这里显式检查返回值并打日志，避免「绑定失败」被完全吞掉。
      val listenerResult =
        engine.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
              Log.d(TAG, "Utterance started: id=$utteranceId")
              isSpeaking = true
              updateLastError(null)
            }

            override fun onDone(utteranceId: String?) {
              Log.d(TAG, "Utterance done: id=$utteranceId")
              isSpeaking = false
            }

            /** 新版本回调（带错误码），用于定位引擎侧合成/播放失败原因。 */
            override fun onError(utteranceId: String?, errorCode: Int) {
              Log.e(TAG, "Utterance error: id=$utteranceId, code=$errorCode (${utteranceErrorText(errorCode)})")
              isSpeaking = false
              updateLastError(buildString {
                append("朗读失败：")
                append(utteranceErrorText(errorCode))
                append("(code=")
                append(errorCode)
                append(")")
              })
            }

            @Deprecated("Legacy API, kept for compatibility with older devices.")
            override fun onError(utteranceId: String?) {
              Log.e(TAG, "Utterance error (legacy): id=$utteranceId")
              isSpeaking = false
            }
          }
        )
      if (listenerResult != TextToSpeech.SUCCESS) {
        Log.e(TAG, "setOnUtteranceProgressListener failed, code=$listenerResult (${statusText(listenerResult)})")
      }
    } catch (e: Exception) {
      // 构造异常（极少见，但一旦发生必须可见）：标记为失败并暴露原因。
      val reason = "TextToSpeech 构造异常：${e.javaClass.simpleName}: ${e.message}"
      Log.e(TAG, "Failed to create TextToSpeech engine: $reason", e)
      initFailed = true
      initFailureReason = reason
      updateLastError(reason)
    }
  }

  override fun onInit(status: Int) {
    Log.i(TAG, "onInit: status=$status (${statusText(status)})")
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
      Log.i(TAG, "setLanguage(zh-CN) -> $result (${langResultText(result)})")
      // 若中文不可用，退回系统默认语言，保证仍能发声。
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w(TAG, "Chinese TTS not available, falling back to default locale.")
        tts?.setLanguage(Locale.getDefault())
      }
      ready = true
      initFailed = false
      initFailureReason = null
      updateLastError(null)
      // 补播初始化完成前的朗读请求。
      pendingText?.let { text ->
        pendingText = null
        doSpeak(text)
      }
    } else {
      // 【N6-A】把失败码翻译成可读原因，而不是只留一个 initFailed=true。
      val reason = statusText(status)
      Log.e(TAG, "TextToSpeech initialization failed: $reason")
      initFailed = true
      initFailureReason = reason
      updateLastError(reason)
      // 初始化失败时清空待朗读文本，避免文本长期挂起、用户以为「已排队」。
      pendingText = null
    }
  }

  /**
   * 按当前语音设置朗读（正式调用入口）。
   *
   * 分派逻辑（Bug A 修复）：
   * 1. 若设置选择 Sherpa 离线引擎，且离线引擎存在、语音包文件校验通过 →
   *    走离线合成（后台线程完成 native 初始化，初始化失败自动回退系统 TTS，
   *    绝不静默无声，也绝不在主线程执行 native 初始化）；
   * 2. 否则回退系统 TTS。
   */
  fun speakWithSettings(text: String) {
    // 空文本直接返回，不做任何状态变更，避免 isSpeaking 置位后无法复位（朗读图标卡住）。
    if (text.trim().isEmpty()) return
    val settings = voiceSettingsProvider?.invoke()
    val offlineDisabledByCrash =
      context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .getBoolean("offline_tts_disabled", false)
    val useOffline =
      !offlineDisabledByCrash &&
        settings?.engine == VoiceEngineSnapshot.SHERPA_ONNX &&
        offlineTtsEngine != null &&
        !offlineTtsEngine.initFailed // 初始化已失败的引擎直接黑名单，避免每次点击都闪退
    Log.d(
      TAG,
      "speakWithSettings: len=${text.length}, engine=${settings?.engine}, " +
        "offlineEnginePresent=${offlineTtsEngine != null}, " +
        "initFailed=${offlineTtsEngine?.initFailed}, useOffline=$useOffline",
    )
    if (useOffline && offlineTtsEngine.isModelValid()) {
      // 文件校验通过即可分派；native 初始化在后台线程完成。
      isSpeaking = true
      offlineTtsEngine.speakWithFallback(
        text = text,
        speed = settings?.rate ?: 1.0f,
        onDone = { isSpeaking = false },
        // 离线初始化失败时回退系统 TTS，保证一定有声。
        fallback = { speak(text, settings?.rate, settings?.pitch) },
      )
      return
    }
    speak(text, settings?.rate, settings?.pitch)
  }

  /** 立即朗读（无视当前设置，供试听固定语速使用）。 */
  fun speak(text: String, rate: Float? = null, pitch: Float? = null) {
    val content = text.trim()
    if (content.isEmpty()) {
      return
    }
    if (initFailed) {
      // 【N6-A】静默失败点 ①：引擎不可用时直接 return，过去只有一行 W 日志，
      // 现在同时把原因写进 lastError，界面可直接提示用户。
      Log.w(TAG, "speak() skipped: system TTS unavailable, reason=$initFailureReason")
      updateLastError(initFailureReason ?: "系统 TTS 不可用（未知原因）")
      return
    }
    if (!ready) {
      // 静默失败点 ②：初始化未完成，文本只被缓存。若 onInit 永远不回调则永远不播，
      // 这里打日志便于区分「排队中」与「彻底没戏」。
      pendingText = content
      Log.w(TAG, "speak() deferred: TTS not ready yet, cached text (len=${content.length})")
      return
    }
    doSpeak(content, rate, pitch)
  }

  private fun doSpeak(text: String, rate: Float? = null, pitch: Float? = null) {
    stop()
    // 系统 TTS 对超长文本支持不佳，这里做保守截断。
    val content = if (text.length > MAX_SPEAK_LENGTH) text.take(MAX_SPEAK_LENGTH) else text
    val engine = tts
    if (engine == null) {
      // 静默失败点 ③：引擎实例为空（构造失败或已 shutdown）。
      Log.e(TAG, "speak() failed: TextToSpeech instance is null (not constructed or already shutdown)")
      initFailed = true
      updateLastError("TTS 引擎实例为空（构造失败或已释放）")
      return
    }
    // 应用语速 / 音调（仅当显式传入时覆盖；默认不动引擎原值，避免设置残留）。
    if (rate != null) engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    if (pitch != null) engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
    val utteranceId = "encourage_tts_${System.currentTimeMillis()}"
    val result = engine.speak(content, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    if (result == TextToSpeech.ERROR) {
      // 静默失败点 ④：speak() 返回 ERROR，例如引擎语言数据缺失 / 引擎被停用。
      Log.e(TAG, "TextToSpeech.speak() returned ERROR, utteranceId=$utteranceId, len=${content.length}")
      updateLastError("系统语音引擎调用失败（speak 返回 ERROR），请检查系统 TTS 语音数据是否已安装")
    } else {
      Log.i(
        TAG,
        "speak() dispatched: utteranceId=$utteranceId, len=${content.length}, " +
          "rate=$rate, pitch=$pitch, engine=${engine.defaultEngine}",
      )
      updateLastError(null)
    }
  }

  // ===== 【N6-A】可读错误码翻译工具：把 int 状态码翻译成能直接给人看的原因 =====

  /** 把 TextToSpeech 的初始化/调用状态码翻译成可读文本（含最常见根因提示）。 */
  private fun statusText(status: Int): String =
    when (status) {
      TextToSpeech.SUCCESS -> "SUCCESS"
      TextToSpeech.ERROR ->
        "ERROR（通用失败；最常见根因：Android 11+ 包可见性未声明 " +
          "<queries><intent>android.intent.action.TTS_SERVICE</intent></queries>，" +
          "或设备未安装/未启用 TTS 引擎）"
      TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS（合成失败，语音数据可能缺失）"
      TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE（TTS 服务不可用/未连接）"
      TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT（音频输出失败）"
      TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK（网络合成失败）"
      TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT（网络合成超时）"
      TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST（请求参数非法）"
      TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET（引擎语音数据尚未安装完成）"
      else -> "UNKNOWN($status)"
    }

  /** 把 UtteranceProgressListener.onError 的错误码翻译成可读文本。 */
  private fun utteranceErrorText(errorCode: Int): String =
    when (errorCode) {
      TextToSpeech.ERROR_SYNTHESIS -> "合成失败"
      TextToSpeech.ERROR_SERVICE -> "服务不可用"
      TextToSpeech.ERROR_OUTPUT -> "音频输出失败"
      TextToSpeech.ERROR_NETWORK -> "网络错误"
      TextToSpeech.ERROR_NETWORK_TIMEOUT -> "网络超时"
      TextToSpeech.ERROR_INVALID_REQUEST -> "请求非法"
      TextToSpeech.ERROR_NOT_INSTALLED_YET -> "语音数据未安装"
      else -> "未知错误"
    }

  /**
   * 把 setLanguage 的返回值翻译成可读文本。
   *
   * 注意：TextToSpeech.LANG_COUNTRY_AVAILABLE / LANG_COUNTRY_VAR_AVAILABLE 已在
   * 新版 SDK 标记为 deprecated，这里用「>= LANG_AVAILABLE 即视为可用」的官方推荐
   * 比较方式，避免引入弃用告警。
   */
  private fun langResultText(result: Int?): String =
    when {
      result == null -> "NULL（引擎为空）"
      result == TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA（缺少语音数据）"
      result == TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED（语言不支持）"
      result >= TextToSpeech.LANG_AVAILABLE -> "OK($result)"
      else -> "UNKNOWN($result)"
    }

  /** 停止当前朗读（系统引擎与离线引擎都停止）。 */
  fun stop() {
    offlineTtsEngine?.stop()
    if (tts?.isSpeaking == true) {
      tts?.stop()
    }
    isSpeaking = false
  }

  /**
   * 释放引擎资源，必须与创建成对调用。
   *
   * 【Bug 修复】offlineTtsEngine 是从外部传入的，其生命周期由创建方（如
   * VoiceSettingsDialog）管理。此处只 stop() 停止播放，绝不 release()，
   * 否则会与外部的 release() 造成双重释放 → native free 已释放对象 → SIGSEGV 闪退。
   */
  fun shutdown() {
    Log.d(TAG, "shutdown() called")
    stop()
    tts?.shutdown()
    tts = null
    ready = false
    updateLastError(null)
  }

  companion object {
    /** 单次朗读的最大字符数（系统 TTS 对超长文本支持不佳，保守截断）。 */
    private const val MAX_SPEAK_LENGTH: Int = 3000
  }
}

/**
 * 语音设置快照（与 data 层解耦的最小接口，避免 SpeechManager 依赖 DataStore）。
 *
 * M3-6 扩展：新增 [engine]（当前引擎）与 [role]（角色），供 SpeechManager
 * 在「系统 TTS」与「Sherpa 离线引擎」之间做真正分派。
 */
data class VoiceSettingsSnapshot(
  val rate: Float = 1.0f,
  val pitch: Float = 1.0f,
  /** 当前引擎：null 表示未知（默认走系统 TTS）。 */
  val engine: VoiceEngineSnapshot = VoiceEngineSnapshot.SYSTEM,
  /** 当前角色 id（VoiceRoles.byId 解析）。 */
  val role: String = com.encourage.app.data.speech.VoiceRoles.DEFAULT,
)

/** 与 data 层 proto 解耦的引擎枚举快照。 */
enum class VoiceEngineSnapshot { SYSTEM, SHERPA_ONNX }

/**
 * 在 Composable 中获取一个与生命周期绑定的 [SpeechManager]。
 *
 * 组件销毁时自动 shutdown()，避免引擎泄漏。
 *
 * @param voiceSettingsProvider 可选：语音设置提供方（一般从 VoiceSettingsViewModel 取）。
 *   传入后 [SpeechManager.speakWithSettings] 会按用户设置的语速 / 音调朗读。
 * @param offlineTtsEngine 可选：Sherpa 离线引擎。传入后 [SpeechManager.speakWithSettings]
 *   在设置选择离线引擎且语音包就绪时走离线合成。
 */
@Composable
fun rememberSpeechManager(
  offlineTtsEngine: OfflineTtsEngine? = null,
  voiceSettingsProvider: (() -> VoiceSettingsSnapshot)? = null,
): SpeechManager {
  val context = LocalContext.current
  // 【Bug A 修复】voiceSettingsProvider 是每次重组新建的 lambda，不能作为 remember key；
  // 否则每次重组都会重建 SpeechManager（TextToSpeech 反复初始化，导致朗读不稳定/无声）。
  // 只以 context / offlineTtsEngine 作为 key，保证实例稳定。
  val manager =
    remember(context, offlineTtsEngine) {
      SpeechManager(context, voiceSettingsProvider, offlineTtsEngine)
    }
  // 每次重组把最新的 provider 写入 manager（可变属性），保证 speak 时读到最新设置。
  manager.voiceSettingsProvider = voiceSettingsProvider
  DisposableEffect(manager) { onDispose { manager.shutdown() } }
  return manager
}
