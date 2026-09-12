/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.encourage.app.data.conversation

import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.ConfigKey
import com.encourage.app.proto.ConversationProfileProto
import com.encourage.app.proto.ConversationType as ConversationTypeProto

/**
 * 会话类型（领域枚举）。
 *
 * 决定列表图标、以及统一对话界面的输入能力开关（识图走图片选择器、语音走录音）。
 * 与 proto 的 [ConversationTypeProto] 一一对应，但领域层不暴露 `UNSPECIFIED`（统一归并为
 * [CHAT]）。
 */
enum class ConversationType {
  /** 对话（文字）。 */
  CHAT,

  /** 识图。 */
  IMAGE,

  /** 语音。 */
  AUDIO,

  /** Agent Skills。 */
  AGENT;

  /** 转换为 proto 枚举。 */
  fun toProto(): ConversationTypeProto =
    when (this) {
      CHAT -> ConversationTypeProto.CONVERSATION_TYPE_CHAT
      IMAGE -> ConversationTypeProto.CONVERSATION_TYPE_IMAGE
      AUDIO -> ConversationTypeProto.CONVERSATION_TYPE_AUDIO
      AGENT -> ConversationTypeProto.CONVERSATION_TYPE_AGENT
    }

  companion object {
    /** 由 proto 枚举转换为领域枚举；未知 / UNSPECIFIED 一律回退为 [CHAT]。 */
    fun fromProto(proto: ConversationTypeProto): ConversationType =
      when (proto) {
        ConversationTypeProto.CONVERSATION_TYPE_IMAGE -> IMAGE
        ConversationTypeProto.CONVERSATION_TYPE_AUDIO -> AUDIO
        ConversationTypeProto.CONVERSATION_TYPE_AGENT -> AGENT
        ConversationTypeProto.CONVERSATION_TYPE_CHAT -> CHAT
        else -> CHAT
      }

    /**
     * 由 [taskId] 推断会话类型（用于旧数据迁移映射）。
     *
     * `llm_chat→CHAT`、`llm_ask_image→IMAGE`、`llm_ask_audio→AUDIO`、`llm_agent_chat→AGENT`，
     * 其余（含空串）回退为 [CHAT]。
     */
    fun fromTaskId(taskId: String): ConversationType =
      when (taskId) {
        BuiltInTaskId.LLM_ASK_IMAGE -> IMAGE
        BuiltInTaskId.LLM_ASK_AUDIO -> AUDIO
        BuiltInTaskId.LLM_AGENT_CHAT -> AGENT
        else -> CHAT
      }
  }
}

/**
 * 「模型特调配置」领域模型（= 微信里的一个"联系人"）。
 *
 * 只保存模型的「名称 + 采样参数」（[modelName] / [configValues]），**绝不持有** `Model`
 * 运行时实例 —— 实例由 `ModelManagerViewModel` 统一管理，以保证同一模型只有一个 runtime 实例。
 *
 * 一条记录 1:N 多条会话，靠 [sessionIds]（倒序，最新在前）记录归属；会话侧的归属字段是
 * `ChatSessionProto.profile_id`。
 */
data class ConversationProfile(
  /** UUID，稳定主键。 */
  val id: String = "",
  /** 用户可命名的别名。 */
  val alias: String = "",
  /** `llm_chat` / `llm_ask_image` / `llm_ask_audio` / `llm_agent_chat`。 */
  val taskId: String = "",
  /** 关联模型（`Model.name`，唯一标识）。 */
  val modelName: String = "",
  /** 会话类型。 */
  val type: ConversationType = ConversationType.CHAT,
  /** 角色设定（per-record，替代全局 per-task 提示词）。 */
  val systemPrompt: String = "",
  /** 采样参数；key 统一使用 [ConfigKey.id]（如 `topk` / `temperature`），值为字符串化。 */
  val configValues: Map<String, String> = emptyMap(),
  /** Agent 专有：选中的 Agent id（可空）。 */
  val agentId: String = "",
  /** Agent 专有：选中的 Skill 短 id（可空）。 */
  val skillIds: List<String> = emptyList(),
  /** 创建时间（Unix 毫秒）。 */
  val createdAtMs: Long = 0L,
  /** 最后使用时间（Unix 毫秒），列表按此倒序。 */
  val lastUsedAtMs: Long = 0L,
  /** 名下会话 id（倒序，最新在前）。 */
  val sessionIds: List<String> = emptyList(),
  /** 冗余字段：列表摘要，避免遍历 messages。 */
  val lastMessagePreview: String = "",
) {

  /**
   * 读取某个采样参数（优先用 [ConfigKey.id]）。
   *
   * @param key 配置键。
   * @param defaultValue key 不存在时返回的默认值。
   */
  fun configValue(key: ConfigKey, defaultValue: String = ""): String =
    configValues[key.id] ?: defaultValue

  /** 转换为 proto。 */
  fun toProto(): ConversationProfileProto =
    ConversationProfileProto.newBuilder()
      .setId(id)
      .setAlias(alias)
      .setTaskId(taskId)
      .setModelName(modelName)
      .setType(type.toProto())
      .setSystemPrompt(systemPrompt)
      .putAllConfigValues(configValues)
      .setAgentId(agentId)
      .addAllSkillIds(skillIds)
      .setCreatedAtMs(createdAtMs)
      .setLastUsedAtMs(lastUsedAtMs)
      .addAllSessionIds(sessionIds)
      .setLastMessagePreview(lastMessagePreview)
      .build()

  companion object {
    /** 由 proto 还原领域模型（缺失字段使用默认值，保证向后兼容）。 */
    fun fromProto(proto: ConversationProfileProto): ConversationProfile =
      ConversationProfile(
        id = proto.id,
        alias = proto.alias,
        taskId = proto.taskId,
        modelName = proto.modelName,
        type = ConversationType.fromProto(proto.type),
        systemPrompt = proto.systemPrompt,
        configValues = proto.configValuesMap.toMap(),
        agentId = proto.agentId,
        skillIds = proto.skillIdsList.toList(),
        createdAtMs = proto.createdAtMs,
        lastUsedAtMs = proto.lastUsedAtMs,
        sessionIds = proto.sessionIdsList.toList(),
        lastMessagePreview = proto.lastMessagePreview,
      )
  }
}
