/*
 * Encourage — 语音设置仓库（M3 / 需求 D1 D3）
 *
 * 【功能说明】
 * 语音设置的唯一读写入口：引擎来源（系统 TTS / Sherpa-ONNX）、语速、音调、音色角色。
 * 底层是 DataStore<Settings>（settings.proto），与 ApiProviderRepository 同款模式。
 *
 * 【角色（音色）说明】
 * 系统 TTS 没有稳定的「角色」概念（不同引擎的语音集差异很大），真正的多角色
 * 要等 Sherpa-ONNX 离线引擎接入（M3-6）。因此角色表在这里统一定义：
 * - DEFAULT（跟随引擎默认）——系统 TTS 阶段唯一可用的角色；
 * - 其他角色（女声 / 男声 / 童声 / 温柔）在离线引擎接入后生效，
 *   选择非默认角色但引擎未就绪时，朗读会优雅回退到引擎默认角色。
 *
 * 【使用方法】
 *   val settings by voiceSettingsRepository.flow.collectAsState(initial = ...)
 *   voiceSettingsRepository.setRate(1.2f)
 */

package com.encourage.app.data.speech

import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import com.encourage.app.R
import com.encourage.app.proto.Settings
import com.encourage.app.proto.VoiceEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 一个音色角色定义。 */
data class VoiceRole(
  val id: String,
  @StringRes val labelRes: Int,
  /** 是否为离线引擎（Sherpa-ONNX）专属角色。 */
  val requiresOfflineEngine: Boolean = false,
)

/** 音色角色表。DEFAULT 永远可用；其余在离线引擎接入后生效。 */
object VoiceRoles {
  const val DEFAULT = "default"

  val ALL: List<VoiceRole> =
    listOf(
      VoiceRole(id = DEFAULT, labelRes = R.string.voice_role_default),
      VoiceRole(
        id = "female_gentle",
        labelRes = R.string.voice_role_female_gentle,
        requiresOfflineEngine = true,
      ),
      VoiceRole(
        id = "male_natural",
        labelRes = R.string.voice_role_male_natural,
        requiresOfflineEngine = true,
      ),
      VoiceRole(
        id = "child_clear",
        labelRes = R.string.voice_role_child_clear,
        requiresOfflineEngine = true,
      ),
    )

  fun byId(id: String?): VoiceRole = ALL.firstOrNull { it.id == id } ?: ALL.first()
}

/** 语音设置的状态快照。 */
data class VoiceSettings(
  /** 引擎来源。 */
  val engine: VoiceEngine = VoiceEngine.VOICE_ENGINE_SYSTEM,
  /** 语速倍率 0.5~2.0；1.0 为正常。 */
  val rate: Float = 1.0f,
  /** 音调倍率 0.5~2.0；1.0 为正常。 */
  val pitch: Float = 1.0f,
  /** 音色角色 id。 */
  val role: String = VoiceRoles.DEFAULT,
) {
  /** 是否已配置为离线引擎。 */
  val usesOfflineEngine: Boolean get() = engine == VoiceEngine.VOICE_ENGINE_SHERPA_ONNX
}

/** 语音设置的读写仓库（DataStore<Settings>）。 */
@Singleton
class VoiceSettingsRepository
@Inject
constructor(private val settingsDataStore: DataStore<Settings>) {

  /** 语音设置的响应式流。 */
  val flow: Flow<VoiceSettings> =
    settingsDataStore.data.map { proto ->
      VoiceSettings(
        engine =
          when (proto.voiceEngine) {
            VoiceEngine.VOICE_ENGINE_SHERPA_ONNX -> VoiceEngine.VOICE_ENGINE_SHERPA_ONNX
            else -> VoiceEngine.VOICE_ENGINE_SYSTEM
          },
        rate = if (proto.voiceRate > 0f) proto.voiceRate.coerceIn(0.5f, 2.0f) else 1.0f,
        pitch = if (proto.voicePitch > 0f) proto.voicePitch.coerceIn(0.5f, 2.0f) else 1.0f,
        role = proto.voiceRole.ifEmpty { VoiceRoles.DEFAULT },
      )
    }

  /** 切换引擎来源。 */
  suspend fun setEngine(engine: VoiceEngine) {
    settingsDataStore.updateData { proto ->
      proto.toBuilder().setVoiceEngine(engine).build()
    }
  }

  /** 设置语速（0.5~2.0）。 */
  suspend fun setRate(rate: Float) {
    settingsDataStore.updateData { proto ->
      proto.toBuilder().setVoiceRate(rate.coerceIn(0.5f, 2.0f)).build()
    }
  }

  /** 设置音调（0.5~2.0）。 */
  suspend fun setPitch(pitch: Float) {
    settingsDataStore.updateData { proto ->
      proto.toBuilder().setVoicePitch(pitch.coerceIn(0.5f, 2.0f)).build()
    }
  }

  /** 设置音色角色。 */
  suspend fun setRole(role: String) {
    settingsDataStore.updateData { proto ->
      proto.toBuilder().setVoiceRole(role).build()
    }
  }
}
