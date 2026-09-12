/*
 * Encourage — 会话列表 ViewModel（会话列表改造 T02）
 *
 * 【功能说明】
 * 为「对话」「功能」两个 Tab 共用的会话列表页提供数据与操作：
 * - 观察 ConversationProfileRepository 的 profiles（仓库已按 lastUsedAtMs 倒序）；
 * - 删除记录（级联删除其名下会话 + cacheDir 里的 img_/audio_ 缓存，由仓库内部完成）；
 * - 新建记录（供 T03 从「模型」Tab / 列表入口调用）。
 *
 * 【设计要点】
 * 1. 只做「数据 → UI」的桥接，不持有任何 Model 运行时实例（实例归 ModelManagerViewModel）。
 * 2. 所有协程操作均有异常兜底 + 日志，绝不因数据层异常崩溃。
 * 3. 过滤逻辑放在 Screen（按对话类型），VM 只暴露全量，便于两个 Tab 复用同一 VM 类型。
 */

package com.encourage.app.ui.conversation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.data.conversation.ConversationProfile
import com.encourage.app.data.conversation.ConversationProfileRepository
import com.encourage.app.data.conversation.ConversationType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ConvListViewModel"

/**
 * 会话列表的状态与操作入口。
 *
 * @param repository 「模型特调配置」仓库（T01 交付，单例）。
 */
@HiltViewModel
class ConversationListViewModel @Inject constructor(
  private val repository: ConversationProfileRepository,
) : ViewModel() {

  /** 全部记录（仓库已按 [ConversationProfile.lastUsedAtMs] 倒序）。由 Screen 按类型过滤展示。 */
  val profiles: StateFlow<List<ConversationProfile>> = repository.profiles

  /**
   * 删除一条记录。级联删除名下会话与缓存文件在仓库内部完成（单次原子写入）。
   *
   * @param id 记录 id；空 id 视为无效请求，直接忽略。
   */
  fun deleteProfile(id: String) {
    if (id.isBlank()) return
    viewModelScope.launch {
      try {
        repository.deleteProfile(id)
      } catch (e: Exception) {
        Log.e(TAG, "deleteProfile failed for id=$id", e)
      }
    }
  }

  /**
   * 新建一条记录（T03 从「模型」Tab 或列表入口调用）。
   *
   * @param taskId 任务 id（`llm_chat` / `llm_ask_image` / `llm_ask_audio` / `llm_agent_chat`）。
   * @param modelName 关联模型（`Model.name`）。
   * @param type 会话类型。
   * @param systemPrompt 记录级角色设定，默认空。
   * @param configValues 采样参数（key 用 `ConfigKey.id`），默认空。
   * @param alias 显式别名；为空时按 `"{模型名} 特调"` 自动命名。
   * @param displayName 模型显示名（用于默认别名）。
   * @param agentId Agent 专有 id。
   * @param skillIds Agent 专有 skill id。
   */
  fun createProfile(
    taskId: String,
    modelName: String,
    type: ConversationType,
    systemPrompt: String = "",
    configValues: Map<String, String> = emptyMap(),
    alias: String? = null,
    displayName: String? = null,
    agentId: String = "",
    skillIds: List<String> = emptyList(),
  ) {
    if (modelName.isBlank()) return
    viewModelScope.launch {
      try {
        repository.createProfile(
          taskId = taskId,
          modelName = modelName,
          type = type,
          systemPrompt = systemPrompt,
          configValues = configValues,
          alias = alias,
          displayName = displayName,
          agentId = agentId,
          skillIds = skillIds,
        )
      } catch (e: Exception) {
        Log.e(TAG, "createProfile failed for model=$modelName", e)
      }
    }
  }
}
