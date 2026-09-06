/*
 * Encourage — 模型参数说明表（M1）
 *
 * 【功能说明】
 * 为「模型参数设置」界面提供每个参数的解释文案：参数含义 + 不同取值范围的实际影响。
 * 用户在设置界面点击参数标题旁的 ⓘ 小按钮即可查看，降低端侧模型调参门槛。
 *
 * 【设计说明】
 * 1. 以 ConfigKey.id 为键映射到 strings.xml 中的说明文案资源 id（便于中英双语）。
 *    之所以不用 ConfigKey 对象做键：ConfigKey 是 data class，每次读取 ConfigKeys 里的
 *    常量都是同一实例，用 id 字符串做键更稳妥，也便于将来从网络/JSON 扩展说明。
 * 2. 映射缺失时返回 null，界面层据此隐藏 ⓘ 按钮，避免出现空的说明弹窗。
 * 3. 新增参数说明只需两步：在 strings.xml / values-zh 增加文案，在 MAP 中登记。
 *
 * 【使用方法】
 *   val hintRes = ConfigHints.hintResFor(config.key.id)   // 返回 @StringRes 或 null
 *   if (hintRes != null) { /* 显示 Info 按钮，点击弹出说明 */ }
 */

package com.encourage.app.data

import androidx.annotation.StringRes
import com.encourage.app.R

/** 模型参数说明表的集中登记处。 */
object ConfigHints {

  /** 参数 id → 说明文案资源 id。 */
  private val MAP: Map<String, Int> =
    mapOf(
      // —— 采样相关（决定文本生成的随机性与多样性）——
      ConfigKeys.TEMPERATURE.id to R.string.config_hint_temperature,
      ConfigKeys.DEFAULT_TEMPERATURE.id to R.string.config_hint_temperature,
      ConfigKeys.TOPK.id to R.string.config_hint_topk,
      ConfigKeys.DEFAULT_TOPK.id to R.string.config_hint_topk,
      ConfigKeys.TOPP.id to R.string.config_hint_topp,
      ConfigKeys.DEFAULT_TOPP.id to R.string.config_hint_topp,

      // —— 长度相关（决定上下文与生成上限，直接影响显存占用与速度）——
      ConfigKeys.MAX_TOKENS.id to R.string.config_hint_max_tokens,
      ConfigKeys.DEFAULT_MAX_TOKENS.id to R.string.config_hint_max_tokens,
      ConfigKeys.MAX_OUTPUT_TOKENS.id to R.string.config_hint_max_output_tokens,
      ConfigKeys.RESET_CONVERSATION_TURN_COUNT.id to
        R.string.config_hint_reset_conversation_turn_count,

      // —— 硬件与性能——
      ConfigKeys.ACCELERATOR.id to R.string.config_hint_accelerator,
      ConfigKeys.VISION_ACCELERATOR.id to R.string.config_hint_accelerator,
      ConfigKeys.USE_GPU.id to R.string.config_hint_use_gpu,
      ConfigKeys.ENABLE_SPECULATIVE_DECODING.id to R.string.config_hint_speculative_decoding,
      ConfigKeys.ENABLE_THINKING.id to R.string.config_hint_enable_thinking,

      // —— 能力开关——
      ConfigKeys.SUPPORT_IMAGE.id to R.string.config_hint_support_image,
      ConfigKeys.SUPPORT_AUDIO.id to R.string.config_hint_support_audio,
      ConfigKeys.SUPPORT_THINKING.id to R.string.config_hint_enable_thinking,
      ConfigKeys.SUPPORT_SPECULATIVE_DECODING.id to R.string.config_hint_speculative_decoding,

      // —— 基准测试——
      ConfigKeys.WARM_UP_ITERATIONS.id to R.string.config_hint_warm_up_iterations,
      ConfigKeys.BENCHMARK_ITERATIONS.id to R.string.config_hint_benchmark_iterations,
      ConfigKeys.PREFILL_TOKENS.id to R.string.config_hint_prefill_tokens,
      ConfigKeys.DECODE_TOKENS.id to R.string.config_hint_decode_tokens,
      ConfigKeys.NUMBER_OF_RUNS.id to R.string.config_hint_number_of_runs,
    )

  /**
   * 查询某个参数的说明文案资源。
   *
   * @param configKeyId 参数唯一标识，取自 `ConfigKey.id`。
   * @return 说明文案的 @StringRes；无对应说明时返回 null（界面层应隐藏说明按钮）。
   */
  @StringRes fun hintResFor(configKeyId: String): Int? = MAP[configKeyId]
}
