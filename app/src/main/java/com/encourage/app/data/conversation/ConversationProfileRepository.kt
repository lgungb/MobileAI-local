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

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import com.encourage.app.R
import com.encourage.app.proto.ConversationProfileProto
import com.encourage.app.proto.UserData
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "ConversationProfileRepo"

/**
 * 「模型特调配置」仓库（单例）。
 *
 * 职责：
 * - 复用 `UserData` DataStore（方案 A，见设计 §3.3）：`conversation_profiles` 与 `chat_sessions`
 *   同属一份文件，**删除记录 + 级联删除会话单次原子写入**，避免跨文件不一致；
 * - 暴露 [profiles] / [profilesOfType] 的响应式列表（按 [ConversationProfile.lastUsedAtMs] 倒序）；
 * - CRUD：创建 / 更新 / 重命名 / 删除（级联）/ 触达最后使用时间 / 查询；
 * - 惰性幂等迁移：首次加载时把旧会话（`profile_id` 为空）按 `(task_id, original_model)`
 *   归并为「历史」记录并回填 `profile_id`，用迁移标记位保证幂等。
 *
 * 约束：本仓库**绝不持有** `Model` 运行时实例，只保存 [ConversationProfile.modelName] 与
 * [ConversationProfile.configValues]；模型实例唯一性由 `ModelManagerViewModel` 保证。
 *
 * @param userDataDataStore `UserData` 的 Proto DataStore（Hilt 提供，方案 A 复用）。
 * @param appContext 应用上下文，用于本地化别名与清理缓存文件（不持有 Activity）。
 */
@Singleton
class ConversationProfileRepository(
  private val userDataDataStore: DataStore<UserData>,
  private val appContext: Context,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _profiles = MutableStateFlow<List<ConversationProfile>>(emptyList())

  /** 全部记录，按 [ConversationProfile.lastUsedAtMs] 倒序（最新在前）。 */
  val profiles: StateFlow<List<ConversationProfile>> = _profiles.asStateFlow()

  init {
    scope.launch { observeProfiles() }
    scope.launch { migrateLegacySessionsIfNeeded() }
  }

  /** 某一类型下的记录（用于列表页过滤：对话 Tab / 功能 Tab 共用同一数据源）。 */
  fun profilesOfType(type: ConversationType): Flow<List<ConversationProfile>> =
    profiles.map { list -> list.filter { it.type == type } }

  /**
   * 从内存快照查询记录（同步、零 IO）。
   *
   * @return 命中返回记录，否则 `null`（调用方必须兜底，禁止 `!!`）。
   */
  fun getProfile(id: String): ConversationProfile? =
    _profiles.value.firstOrNull { it.id == id }

  /**
   * 从 DataStore 直接读取（suspend），用于需要强一致性的场景（如进入会话前 `bindProfile`）。
   *
   * @return 命中返回记录，否则 `null`。
   */
  suspend fun getProfileFromStore(id: String): ConversationProfile? =
    try {
      userDataDataStore.data.first().conversationProfilesList
        .firstOrNull { it.id == id }
        ?.let { ConversationProfile.fromProto(it) }
    } catch (e: Exception) {
      Log.e(TAG, "getProfileFromStore failed for id=$id", e)
      null
    }

  /**
   * 创建一条记录。
   *
   * @param taskId 任务 id（`llm_chat` / `llm_ask_image` / `llm_ask_audio` / `llm_agent_chat`）。
   * @param modelName 关联模型（`Model.name`）。
   * @param type 会话类型。
   * @param systemPrompt 角色设定，默认空。
   * @param configValues 采样参数（key 用 `ConfigKey.id`），默认空。
   * @param alias 显式别名；为空时按 `"{模型名} 特调"` 自动命名（Agent 追加 `" · Agent"`）。
   * @param displayName 模型显示名（用于默认别名），为空回退 [modelName]。
   * @param agentId Agent 专有 id，默认空。
   * @param skillIds Agent 专有 skill id，默认空。
   * @return 新建的记录。
   */
  suspend fun createProfile(
    taskId: String,
    modelName: String,
    type: ConversationType,
    systemPrompt: String = "",
    configValues: Map<String, String> = emptyMap(),
    alias: String? = null,
    displayName: String? = null,
    agentId: String = "",
    skillIds: List<String> = emptyList(),
  ): ConversationProfile {
    val now = System.currentTimeMillis()
    val baseName = displayName?.takeIf { it.isNotBlank() } ?: modelName
    val resolvedAlias =
      alias?.takeIf { it.isNotBlank() }
        ?: buildDefaultAlias(
          baseName = baseName,
          type = type,
          existingAliases = _profiles.value.map { it.alias },
        )
    val profile =
      ConversationProfile(
        id = UUID.randomUUID().toString(),
        alias = resolvedAlias,
        taskId = taskId,
        modelName = modelName,
        type = type,
        systemPrompt = systemPrompt,
        configValues = configValues,
        agentId = agentId,
        skillIds = skillIds,
        createdAtMs = now,
        lastUsedAtMs = now,
        sessionIds = emptyList(),
        lastMessagePreview = "",
      )
    userDataDataStore.updateData { userData ->
      userData.toBuilder().addConversationProfiles(profile.toProto()).build()
    }
    return profile
  }

  /** 覆盖式更新一条记录（按 [ConversationProfile.id] 定位；不存在则忽略）。 */
  suspend fun updateProfile(profile: ConversationProfile) {
    userDataDataStore.updateData { userData ->
      val updated =
        userData.conversationProfilesList.map { proto ->
          if (proto.id == profile.id) profile.toProto() else proto
        }
      userData
        .toBuilder()
        .clearConversationProfiles()
        .addAllConversationProfiles(updated)
        .build()
    }
  }

  /** 重命名一条记录（昵称允许重名，空别名直接忽略）。 */
  suspend fun renameProfile(id: String, alias: String) {
    if (alias.isBlank()) return
    userDataDataStore.updateData { userData ->
      val updated =
        userData.conversationProfilesList.map { proto ->
          if (proto.id == id) proto.toBuilder().setAlias(alias).build() else proto
        }
      userData
        .toBuilder()
        .clearConversationProfiles()
        .addAllConversationProfiles(updated)
        .build()
    }
  }

  /**
   * 更新某条记录的角色设定（记录级提示词）。
   *
   * 【T04】统一对话界面下，编辑系统提示词优先写入这条「特调记录」；
   * 未绑定记录时才回退写入全局 `system_prompt_$taskId`（作为默认值）。
   * 空 id 直接忽略，避免误写。
   */
  suspend fun updateSystemPrompt(id: String, systemPrompt: String) {
    if (id.isBlank()) return
    userDataDataStore.updateData { userData ->
      val updated =
        userData.conversationProfilesList.map { proto ->
          if (proto.id == id) proto.toBuilder().setSystemPrompt(systemPrompt).build() else proto
        }
      userData
        .toBuilder()
        .clearConversationProfiles()
        .addAllConversationProfiles(updated)
        .build()
    }
  }

  /**
   * 更新某条记录的采样参数。
   *
   * 【T05-②】key 统一用 [com.encourage.app.data.ConfigKey.id]（如 `topk`），值为字符串化；
   * 由会话页在参数变更时写回，保证重启后仍是用户改过的值。
   * 空 id 直接忽略。
   */
  suspend fun updateConfigValues(id: String, values: Map<String, String>) {
    if (id.isBlank()) return
    userDataDataStore.updateData { userData ->
      val updated =
        userData.conversationProfilesList.map { proto ->
          if (proto.id == id) {
            proto.toBuilder().clearConfigValues().putAllConfigValues(values).build()
          } else {
            proto
          }
        }
      userData
        .toBuilder()
        .clearConversationProfiles()
        .addAllConversationProfiles(updated)
        .build()
    }
  }

  /** 更新「最后使用时间」为当前时刻（进入会话时调用，用于列表倒序）。 */
  suspend fun touchLastUsed(id: String) {
    val now = System.currentTimeMillis()
    userDataDataStore.updateData { userData ->
      val updated =
        userData.conversationProfilesList.map { proto ->
          if (proto.id == id) proto.toBuilder().setLastUsedAtMs(now).build() else proto
        }
      userData
        .toBuilder()
        .clearConversationProfiles()
        .addAllConversationProfiles(updated)
        .build()
    }
  }

  /**
   * 删除一条记录，并**级联删除**其名下全部会话（含 `cacheDir` 里 `img_` / `audio_` 缓存文件）。
   *
   * 记录与其会话同属一份 `UserData`，删除在单次 `updateData` 内原子完成。
   *
   * @param id 记录 id。
   * @param context 用于清理缓存文件；默认使用应用上下文。
   */
  suspend fun deleteProfile(id: String, context: Context? = appContext) {
    if (id.isEmpty()) return
    try {
      // 先取出待删除的会话 id（合并记录快照与 session 侧归属，保证不漏删）。
      val sessionIds = collectOwnedSessionIds(id)
      deleteSessionCacheFiles(sessionIds, context)
      userDataDataStore.updateData { userData ->
        val remainingProfiles = userData.conversationProfilesList.filter { it.id != id }
        val remainingSessions = userData.chatSessionsList.filter { it.profileId != id }
        userData
          .toBuilder()
          .clearConversationProfiles()
          .addAllConversationProfiles(remainingProfiles)
          .clearChatSessions()
          .addAllChatSessions(remainingSessions)
          .build()
      }
    } catch (e: Exception) {
      Log.e(TAG, "deleteProfile failed for id=$id", e)
    }
  }

  // -------------------------------------------------------------------------------------------
  // 内部实现
  // -------------------------------------------------------------------------------------------

  /** 持续观测 DataStore，把记录列表同步到 [profiles]（异常不致命，仅记录日志）。 */
  private suspend fun observeProfiles() {
    try {
      userDataDataStore.data.collect { userData ->
        _profiles.value =
          userData.conversationProfilesList
            .map { ConversationProfile.fromProto(it) }
            .sortedByDescending { it.lastUsedAtMs }
      }
    } catch (e: Exception) {
      Log.e(TAG, "observeProfiles failed", e)
    }
  }

  /** 汇总某记录名下的会话 id（记录快照 ∪ session 侧 profile_id）。 */
  private suspend fun collectOwnedSessionIds(profileId: String): Set<String> =
    try {
      val userData = userDataDataStore.data.first()
      val fromProfile =
        userData.conversationProfilesList
          .firstOrNull { it.id == profileId }
          ?.sessionIdsList
          .orEmpty()
      val fromSessions =
        userData.chatSessionsList.filter { it.profileId == profileId }.map { it.sessionId }
      (fromProfile + fromSessions).toSet()
    } catch (e: Exception) {
      Log.e(TAG, "collectOwnedSessionIds failed for id=$profileId", e)
      emptySet()
    }

  /** 删除指定会话在 `cacheDir` 下的 `img_<sessionId>_*` / `audio_<sessionId>_*` 缓存文件。 */
  private fun deleteSessionCacheFiles(sessionIds: Collection<String>, context: Context?) {
    if (context == null || sessionIds.isEmpty()) return
    val prefixes = sessionIds.flatMap { listOf("img_${it}_", "audio_${it}_") }
    if (prefixes.isEmpty()) return
    val files: Array<File> = context.cacheDir.listFiles() ?: return
    files.forEach { file ->
      if (prefixes.any { file.name.startsWith(it) }) {
        file.delete()
      }
    }
  }

  /** 生成默认别名；若与现有别名冲突则追加序号（如「Gemma 特调 2」）。 */
  private fun buildDefaultAlias(
    baseName: String,
    type: ConversationType,
    existingAliases: List<String>,
  ): String {
    val safeBase = baseName.ifBlank { appContext.getString(R.string.conversation_profile_unnamed_model) }
    val agentSuffix =
      if (type == ConversationType.AGENT) {
        appContext.getString(R.string.conversation_profile_agent_alias_suffix)
      } else {
        ""
      }
    val candidate = appContext.getString(R.string.conversation_profile_default_alias, safeBase) + agentSuffix
    if (existingAliases.none { it == candidate }) return candidate
    var index = 2
    while (existingAliases.any { it == "$candidate $index" }) {
      index++
    }
    return "$candidate $index"
  }

  /**
   * 惰性、幂等地迁移旧会话。
   *
   * - 仅在迁移标记位未设置时执行；
   * - 按 `(task_id, original_model)` 归并为「历史」记录，type 由 taskId 映射；
   * - 回填会话 `profile_id`，并在同一次写入中设置标记位 → 再次执行为空操作。
   */
  private suspend fun migrateLegacySessionsIfNeeded() {
    try {
      userDataDataStore.updateData { userData -> runMigration(userData) }
    } catch (e: Exception) {
      Log.e(TAG, "migrateLegacySessionsIfNeeded failed", e)
    }
  }

  /** 纯函数式迁移实现（便于单测与幂等保证）。 */
  private fun runMigration(userData: UserData): UserData {
    if (userData.secretsMap[MIGRATION_FLAG_KEY] == MIGRATION_FLAG_DONE) return userData

    val orphans = userData.chatSessionsList.filter { it.profileId.isEmpty() }
    if (orphans.isEmpty()) {
      return userData.toBuilder().putSecrets(MIGRATION_FLAG_KEY, MIGRATION_FLAG_DONE).build()
    }

    val groups = orphans.groupBy { it.taskId to it.originalModel }
    val newProfiles = ArrayList<ConversationProfileProto>(groups.size)
    val sessionIdToProfileId = HashMap<String, String>()

    groups.forEach { (key, sessions) ->
      val (taskId, originalModel) = key
      val type = ConversationType.fromTaskId(taskId)
      val profileId = UUID.randomUUID().toString()
      val sortedDesc = sessions.sortedByDescending { it.timestampMs }
      val createdAtMs = sortedDesc.minOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
      val lastUsedAtMs = sortedDesc.maxOfOrNull { it.timestampMs } ?: createdAtMs
      val preview = extractPreview(sortedDesc.firstOrNull())
      newProfiles.add(
        ConversationProfile(
            id = profileId,
            alias = appContext.getString(R.string.conversation_profile_history_alias, originalModel),
            taskId = taskId,
            modelName = originalModel,
            type = type,
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            sessionIds = sortedDesc.map { it.sessionId },
            lastMessagePreview = preview,
          )
          .toProto()
      )
      sortedDesc.forEach { sessionIdToProfileId[it.sessionId] = profileId }
    }

    val updatedSessions =
      userData.chatSessionsList.map { session ->
        val newProfileId = sessionIdToProfileId[session.sessionId]
        if (newProfileId != null) session.toBuilder().setProfileId(newProfileId).build() else session
      }

    return userData
      .toBuilder()
      .clearChatSessions()
      .addAllChatSessions(updatedSessions)
      .addAllConversationProfiles(newProfiles)
      .putSecrets(MIGRATION_FLAG_KEY, MIGRATION_FLAG_DONE)
      .build()
  }

  /** 从一条会话里提取列表摘要（最后一条 TEXT 消息，截断 50 字）。 */
  private fun extractPreview(
    session: com.encourage.app.proto.ChatSessionProto?
  ): String {
    if (session == null) return ""
    val lastText =
      session.messagesList.lastOrNull { it.messageType == MESSAGE_TYPE_TEXT }?.content ?: ""
    return lastText.take(PREVIEW_MAX_LENGTH)
  }

  companion object {
    /** 迁移标记位在 `UserData.secrets` 中的 key。 */
    private const val MIGRATION_FLAG_KEY = "conversation_profiles_migrated_v1"

    /** 迁移标记位的完成值。 */
    private const val MIGRATION_FLAG_DONE = "1"

    /** 列表摘要最大长度。 */
    private const val PREVIEW_MAX_LENGTH = 50

    /** 与 `ChatViewModel.saveSession` 保持一致的文本消息类型标识。 */
    private const val MESSAGE_TYPE_TEXT = "TEXT"
  }
}
