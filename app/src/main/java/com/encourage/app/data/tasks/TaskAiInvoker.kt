/*
 * Encourage — 定时任务 AI 调用器（N5 / 需求 N5）
 *
 * 【功能说明】
 * 定时任务触发时，若任务配置了「AI 提示词」（TimedTaskModel.aiPrompt），本类负责
 * 调用本地或外部 AI 生成一段回复文本，供通知展示与语音朗读。
 *
 * 【路由策略（与对话页 LlmChatViewModel 对齐）】
 * 1. 优先外部 AI：若用户在设置里开启了外部 API 且存在激活配置（ApiProviderRepository.activeConfig
 *    非空，其本身已受 useRemoteApi 门控），走 RemoteOpenAICompatProvider.streamChat 流式拉取全文。
 * 2. 否则回退本地端侧 AI：与 LocalApiServer 同样的桥接方式，由组合层（GalleryApp）挂载
 *    modelProvider 返回当前已下载的本地模型；通过 AgentRuntimeExecutor.initialize + executeStream 推理。
 * 3. 两者都不可用（未配置外部、且本地模型未就绪）时返回 null，调用方仅用任务 content 提醒，
 *    保证闹钟功能始终可用，AI 只是增强。
 *
 * 【线程模型】
 * 所有方法为 suspend，在调用方（BroadcastReceiver 的协程）中执行；内部推理统一切到
 * Dispatchers.Default，避免阻塞主线程。
 *
 * 【生命周期】
 * - modelProvider 由 GalleryApp 在组合期挂载（与 LocalApiServer 共用同一策略）；
 * - 本类为 @Singleton，可被 TaskScheduler 的 EntryPoint 取出。
 */

package com.encourage.app.data.tasks

import android.content.Context
import android.util.Log
import com.encourage.app.agent.AgentEvent
import com.encourage.app.agent.AgentExecutionContext
import com.encourage.app.agent.AgentRequest
import com.encourage.app.agent.AgentRuntimeConfig
import com.encourage.app.agent.AgentRuntimeExecutor
import com.encourage.app.data.Model
import com.encourage.app.data.api.ApiProviderConfig
import com.encourage.app.data.api.ApiProviderRepository
import com.encourage.app.data.api.ChatRole
import com.encourage.app.data.api.ChatTurn
import com.encourage.app.data.api.RemoteOpenAICompatProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

private const val TAG = "AGTaskAiInvoker"

/** 任务触发时的 AI 推理结果。 */
data class TaskAiResult(
  /** AI 生成的回复全文；为 null 表示未能生成（未配置/模型不可用/出错）。 */
  val reply: String?,
  /** 推理来源标签（"remote" / "local" / null）。 */
  val source: String?,
)

/**
 * 定时任务触发时的 AI 调用器：按提示词路由到外部或本地端侧模型，返回回复全文。
 */
@Singleton
class TaskAiInvoker
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val apiProviderRepository: ApiProviderRepository,
  private val remoteProvider: RemoteOpenAICompatProvider,
  private val executor: AgentRuntimeExecutor,
) {

  /** 由组合层挂载：返回当前应使用的本地模型；未挂载或模型未就绪时返回 null。 */
  @Volatile var modelProvider: (() -> Model?)? = null

  /**
   * 依据任务提示词调用 AI，返回回复文本。
   *
   * @param prompt 任务配置的 AI 提示词（已 trim 的非空字符串）。
   * @param contextText 任务的提醒内容，作为附加上下文注入提示词（可选，便于模型知道任务主题）。
   */
  suspend fun invoke(prompt: String, contextText: String = ""): TaskAiResult {
    // 1) 外部 AI 优先（与对话页一致：activeConfig 已在仓库层按 useRemoteApi 门控）。
    val remoteConfig = apiProviderRepository.activeConfig.firstOrNull()
    if (remoteConfig != null) {
      val reply = runRemote(remoteConfig, prompt)
      if (reply != null) return TaskAiResult(reply = reply, source = "remote")
      Log.w(TAG, "Remote AI returned null/error, falling back to local.")
    }

    // 2) 本地端侧 AI。
    val localReply = runLocal(prompt, contextText)
    if (localReply != null) return TaskAiResult(reply = localReply, source = "local")

    // 3) 均不可用。
    return TaskAiResult(reply = null, source = null)
  }

  /** 调用外部 OpenAI 兼容服务，返回全文（失败返回 null）。 */
  private suspend fun runRemote(config: ApiProviderConfig, prompt: String): String? {
    return withContext(Dispatchers.IO) {
      try {
        val turns =
          listOf(
            ChatTurn(role = ChatRole.USER, content = prompt),
          )
        val sb = StringBuilder()
        remoteProvider
          .streamChat(config = config, turns = turns)
          .collect { event ->
            when (event) {
              is AgentEvent.StreamToken -> {
                event.token?.let { sb.append(it) }
              }
              is AgentEvent.Error -> {
                Log.w(TAG, "Remote AI error: ${event.errorMessage}")
              }
              else -> {}
            }
          }
        val text = sb.toString().trim()
        if (text.isEmpty()) null else text
      } catch (e: Exception) {
        Log.e(TAG, "Remote AI inference failed", e)
        null
      }
    }
  }

  /** 调用本地端侧模型，返回全文（模型未就绪 / 出错返回 null）。 */
  private suspend fun runLocal(prompt: String, contextText: String): String? {
    return withContext(Dispatchers.Default) {
      val model = modelProvider?.invoke() ?: run {
        Log.d(TAG, "No local model mounted; skip local inference.")
        return@withContext null
      }
      try {
        val config =
          AgentRuntimeConfig(
            model = model,
            taskId = "timed_task_ai",
            supportImage = false,
            supportAudio = false,
            systemInstruction = null,
          )
        var errorMsg = ""
        executor.initialize(
          context = context.applicationContext,
          config = config,
          onDone = { msg -> errorMsg = msg },
        )
        if (errorMsg.isNotEmpty()) {
          Log.w(TAG, "Local model init failed: $errorMsg")
          return@withContext null
        }
        val query = if (contextText.isNotBlank()) "$prompt\n（任务提醒内容：$contextText）" else prompt
        val sb = StringBuilder()
        executor
          .executeStream(context = AgentExecutionContext(), request = AgentRequest(query = query))
          .collect { event ->
            if (event is AgentEvent.StreamToken) {
              event.token?.let { sb.append(it) }
            }
          }
        executor.cleanUp {}
        val text = sb.toString().trim()
        if (text.isEmpty()) null else text
      } catch (e: Exception) {
        Log.e(TAG, "Local AI inference failed", e)
        runCatching { executor.cleanUp {} }
        null
      }
    }
  }
}
