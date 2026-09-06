/*
 * Encourage — 功能开关位（Feature Flags）
 *
 * 【为什么需要】
 * 需求整理时确认：商业化按难度分级——轻量项全做，需要后端/支付的只留接口位。
 * 这里就是那个「接口位」：一个统一的功能开关常量表。
 *
 * 【怎么用】
 * 业务代码需要判断某能力是否开放时，直接读对应常量：
 *   if (FeatureFlags.PRO_TIMED_TASKS) { ... }
 * 现阶段全部为 true（全免费开放）。将来订阅制 / 服务端下发生效时，
 * 只需把常量改为从订阅状态或远程配置读取，业务代码一行不用动。
 *
 * 【命名约定】
 * PRO_ 前缀表示将来可能进入付费能力位；免费常能力不在此表。
 */

package com.encourage.app.data

import androidx.annotation.StringRes
import com.encourage.app.R

/** 一个功能开关位的描述，供设置页展示当前开放状态。 */
data class FeatureFlag(
  /** 开关位标识。 */
  val key: String,
  /** 显示名（中英资源）。 */
  @StringRes val labelRes: Int,
  /** 说明文案（中英资源）。 */
  @StringRes val descriptionRes: Int,
  /** 当前是否开放。 */
  val enabled: Boolean,
)

/**
 * 全应用统一的功能开关位表。
 *
 * 新增带商业化潜力的能力时，在这里追加一条，并在设置页自动展示（表驱动）。
 */
object FeatureFlags {

  /** M2 云端模型通道（多模型互联）。Pro 预留：免费额度之外的用量计费位。 */
  const val PRO_CLOUD_MODEL_CHANNEL: Boolean = true

  /** M3 高级 TTS 音色与角色（语音设置页的角色选择）。Pro 预留。 */
  const val PRO_ADVANCED_TTS_VOICES: Boolean = true

  /** M3 定时任务与任务中心。Pro 预留。 */
  const val PRO_TIMED_TASKS: Boolean = true

  /** M1 Prompt Lab 自定义模板。Pro 预留。 */
  const val PRO_CUSTOM_PROMPT_TEMPLATES: Boolean = true

  /** M1 会话保存与导出。Pro 预留。 */
  const val PRO_CHAT_EXPORT: Boolean = true

  /** 全部开关位，供设置页展示。 */
  val ALL: List<FeatureFlag> =
    listOf(
      FeatureFlag(
        key = "cloud_model_channel",
        labelRes = R.string.feature_flag_cloud_model,
        descriptionRes = R.string.feature_flag_cloud_model_desc,
        enabled = PRO_CLOUD_MODEL_CHANNEL,
      ),
      FeatureFlag(
        key = "advanced_tts_voices",
        labelRes = R.string.feature_flag_tts_voices,
        descriptionRes = R.string.feature_flag_tts_voices_desc,
        enabled = PRO_ADVANCED_TTS_VOICES,
      ),
      FeatureFlag(
        key = "timed_tasks",
        labelRes = R.string.feature_flag_timed_tasks,
        descriptionRes = R.string.feature_flag_timed_tasks_desc,
        enabled = PRO_TIMED_TASKS,
      ),
      FeatureFlag(
        key = "custom_prompt_templates",
        labelRes = R.string.feature_flag_custom_prompts,
        descriptionRes = R.string.feature_flag_custom_prompts_desc,
        enabled = PRO_CUSTOM_PROMPT_TEMPLATES,
      ),
      FeatureFlag(
        key = "chat_export",
        labelRes = R.string.feature_flag_chat_export,
        descriptionRes = R.string.feature_flag_chat_export_desc,
        enabled = PRO_CHAT_EXPORT,
      ),
    )
}
