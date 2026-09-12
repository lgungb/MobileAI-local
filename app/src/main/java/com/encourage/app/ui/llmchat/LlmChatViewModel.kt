/*
 * Copyright 2025 Google LLC
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

package com.encourage.app.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.encourage.app.agent.AgentEvent
import com.encourage.app.agent.AgentExecutionContext
import com.encourage.app.agent.AgentRequest
import com.encourage.app.agent.AgentRuntimeConfig
import com.encourage.app.agent.AgentRuntimeExecutor
import com.encourage.app.agent.AiChatExecutor
import com.encourage.app.agent.Attachment
import com.encourage.app.common.SystemPromptHelper
import com.encourage.app.data.api.ApiProviderConfig
import com.encourage.app.data.api.ApiProviderRepository
import com.encourage.app.data.api.ChatRole
import com.encourage.app.data.api.ChatTurn
import com.encourage.app.data.api.RemoteOpenAICompatProvider
import com.encourage.app.data.ConfigKey
import com.encourage.app.data.ConfigKeys
import com.encourage.app.data.Model
import com.encourage.app.data.SystemPromptRepository
import com.encourage.app.data.Task
import com.encourage.app.data.awaitInitialization
import com.encourage.app.data.conversation.ConversationProfile
import com.encourage.app.data.conversation.ConversationProfileRepository
import com.encourage.app.proto.UserData
import com.encourage.app.tools.ToolAction
import com.encourage.app.ui.common.chat.ChatMessageAudioClip
import com.encourage.app.ui.common.chat.ChatMessage
import com.encourage.app.ui.common.chat.ChatMessageConfigValuesChange
import com.encourage.app.ui.common.chat.ChatMessageError
import com.encourage.app.ui.common.chat.ChatMessageImage
import com.encourage.app.ui.common.chat.ChatMessageInfo
import com.encourage.app.ui.common.chat.ChatMessageLoading
import com.encourage.app.ui.common.chat.ChatMessageText
import com.encourage.app.ui.common.chat.ChatMessageThinking
import com.encourage.app.ui.common.chat.ChatMessageType
import com.encourage.app.ui.common.chat.ChatMessageWarning
import com.encourage.app.ui.common.chat.ChatSide
import com.encourage.app.ui.common.chat.ChatViewModel
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

/**
 * 【M2】云端链路的附件占位提示。
 *
 * 当前版本不把图片 / 语音上传到云端，但完全丢弃这些轮次会让上下文出现断层，
 * 所以用一句自然语言告诉模型「这里原本有个附件」，让它至少知道用户做了什么。
 * 后续若要支持多模态，只需把这里换成真正的 image_url / input_audio 内容即可。
 */
private const val REMOTE_ATTACHMENT_IMAGE_HINT = "[用户在此处发送了一张图片，当前无法查看其内容]"

private const val REMOTE_ATTACHMENT_AUDIO_HINT = "[用户在此处发送了一段语音，当前无法查看其内容]"

/**
 * 【T05-②】本模块关心的「记录采样参数」键白名单。
 *
 * 【设计要点】运行时 `Model.getTypedConfigValue` 用 `key.label` 作键（如 `TopK`），
 * 而 `ConversationProfile.configValues` 用 `key.id` 作键（如 `topk`）——
 * 直接把记录的 map 塞进 `model.configValues` 会**静默失效**（读不到值且不报错）。
 * 因此这里显式列出需要双向映射的键，用于 id ↔ label 转换。按需扩展即可。
 */
private val PROFILE_CONFIG_KEYS: List<ConfigKey> =
  listOf(
    ConfigKeys.MAX_TOKENS,
    ConfigKeys.MAX_OUTPUT_TOKENS,
    ConfigKeys.TOPK,
    ConfigKeys.TOPP,
    ConfigKeys.TEMPERATURE,
    ConfigKeys.DEFAULT_MAX_TOKENS,
    ConfigKeys.DEFAULT_TOPK,
    ConfigKeys.DEFAULT_TOPP,
    ConfigKeys.DEFAULT_TEMPERATURE,
    ConfigKeys.ENABLE_THINKING,
    ConfigKeys.ACCELERATOR,
    ConfigKeys.VISION_ACCELERATOR,
  )

/** 【T05-②】记录键（id）→ 运行时键（label）。 */
private val PROFILE_CONFIG_ID_TO_LABEL: Map<String, String> =
  PROFILE_CONFIG_KEYS.associate { key -> key.id to key.label }

/** 【T05-②】运行时键（label）→ 记录键（id），用于把模型参数写回记录。 */
private val PROFILE_CONFIG_LABEL_TO_ID: Map<String, String> =
  PROFILE_CONFIG_KEYS.associate { key -> key.label to key.id }

/**
 * 【M7】推理来源选择。
 *
 * 用户在对话页顶部可手动切换本轮推理走哪条链路：
 * - [AUTO]：跟随设置里的激活配置（默认）。有激活云端配置则走云端，否则走本地端侧；
 * - [LOCAL]：强制本地端侧推理，即使设置了云端配置也不联网；
 * - [REMOTE]：强制云端。仅在存在激活云端配置时生效，否则回退本地并提示。
 */
enum class InferenceSource { AUTO, LOCAL, REMOTE }

/**
 * 【M7】单轮生成的性能统计，用于在对话页实时展示 token 速度。
 *
 * @param tokenCount 本次生成的累计 token 数（按流式分片累计，近似值）。
 * @param tokensPerSecond 平均生成速度（token / 秒）。
 * @param firstTokenMs 从发起生成到收到首个 token 的延迟（毫秒）。
 * @param totalMs 从发起生成到完成的总耗时（毫秒）。
 * @param source 实际生效的推理来源（本地 / 云端），便于界面如实展示。
 */
data class GenerationStats(
  val tokenCount: Int = 0,
  val tokensPerSecond: Float = 0f,
  val firstTokenMs: Long = 0,
  val totalMs: Long = 0,
  val source: InferenceSource = InferenceSource.AUTO,
)

/**
 * 聊天 ViewModel 基类：同时支持本地 LiteRT 推理与云端 OpenAI 兼容接口。
 *
 * ### 推理来源的决策
 * 每次生成前会向 [ApiProviderRepository] 询问当前激活的云端配置：
 * - 为 null（用户在设置里关闭了「使用云端 API」，或没有可用配置）→ 走本地 [runtimeExecutor]；
 * - 非 null → 走 [RemoteOpenAICompatProvider]。
 *
 * 两条链路产出的是同一种 [Flow]<[AgentEvent]>，所以下面 `when (event)` 的渲染逻辑
 * 完全不需要区分来源，这也是本类改动能保持在最小范围的原因。
 *
 * ### 为什么两个新参数可空
 * 这两个参数由 Hilt 注入，理论上永远非空；标成可空是为了让测试或其他不需要
 * 联网能力的子类可以直接用两段构造参数创建实例，不必强行提供依赖。
 * [remoteRouteEnabled] 会在两者任一缺失时自动退回纯本地模式。
 */
@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val systemPromptRepository: SystemPromptRepository? = null,
  userDataDataStore: DataStore<UserData>? = null,
  private val modelFeedbackRepository: Any? = null,
  val runtimeExecutor: AgentRuntimeExecutor,
  private val apiProviderRepository: ApiProviderRepository? = null,
  private val remoteProvider: RemoteOpenAICompatProvider? = null,
  // 【T04】特调记录仓库：绑定记录后，编辑系统提示词写入记录而非全局默认值；可空便于测试。
  private val conversationProfileRepository: ConversationProfileRepository? = null,
) : ChatViewModel(userDataDataStore) {
  private val _uiSystemPrompt = MutableStateFlow("")
  val uiSystemPrompt = _uiSystemPrompt.asStateFlow()

  /** 云端推理开关是否可用：依赖注入齐全时才允许路由到云端。 */
  private val remoteRouteEnabled: Boolean
    get() = apiProviderRepository != null && remoteProvider != null

  /** 最近一次生成任务的执行句柄；云端请求靠取消它来中断，本地推理则靠 executor.interrupt()。 */
  private var generationJob: Job? = null

  /** 最近一次生成是否走了云端。决定 handleError 要不要去重建本地会话。 */
  @Volatile private var lastGenerationWasRemote: Boolean = false

  /**
   * 【M7】用户在对话页选择的推理来源。默认 [InferenceSource.AUTO]，
   * 即「跟随激活的云端配置」——保持原有的本地优先语义不变。
   */
  private val _inferenceSource = MutableStateFlow(InferenceSource.AUTO)
  val inferenceSource = _inferenceSource.asStateFlow()

  /** 【M7】最近一次生成的性能统计，供对话页实时显示 token 速度。 */
  private val _generationStats = MutableStateFlow<GenerationStats?>(null)
  val generationStats = _generationStats.asStateFlow()

  /**
   * 【M7】切换本轮推理来源。见 [InferenceSource]。
   */
  fun setInferenceSource(source: InferenceSource) {
    _inferenceSource.value = source
  }

  /**
   * 读取当前激活的云端配置。
   *
   * 配置在 DataStore 里以 Flow 形式暴露，这里只取一次快照即可 —— 用户不会
   * 在同一次生成过程中切换服务商，取快照能避免长期持有订阅。
   */
  private suspend fun resolveActiveRemoteConfig(): ApiProviderConfig? {
    if (!remoteRouteEnabled) return null
    return apiProviderRepository!!.activeConfig.firstOrNull()
  }

  /**
   * Sets the system prompt in the UI.
   *
   * This method updates the UI system prompt without saving it to the repository or resetting the
   * session. It is primarily used for initializing the UI system prompt.
   *
   * @param systemPrompt The new system prompt to set in the UI.
   */
  fun setUISystemPrompt(systemPrompt: String) {
    _uiSystemPrompt.value = systemPrompt
  }

  /**
   * Loads the system prompt for the given [task] from the repository.
   *
   * @param task The task to load the system prompt for.
   */
  fun loadSystemPrompt(task: Task) {
    viewModelScope.launch {
      val effectivePrompt =
        SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      _uiSystemPrompt.value = effectivePrompt
    }
  }

  /** 【T04】当前会话绑定的「特调记录」id；null / 空串表示未绑定（走 task 全局提示词）。 */
  private val _boundProfileId = MutableStateFlow<String?>(null)
  val boundProfileId = _boundProfileId.asStateFlow()

  /**
   * 【T04】绑定一条「特调记录」：记录级提示词优先，为空时回退 task 全局默认。
   * 【T05-②】同时把记录里的采样参数写入 [model]（键从 [ConfigKey.id] 映射到 [ConfigKey.label]）。
   *
   * @param profile 待绑定的记录；为 null 时等价于加载 task 全局默认提示词。
   * @param task 当前任务（用于回退全局默认值）。
   * @param model 当前模型；非空时应用记录级采样参数。
   */
  fun bindProfile(profile: ConversationProfile?, task: Task, model: Model? = null) {
    if (profile == null) {
      _boundProfileId.value = null
      loadSystemPrompt(task)
      return
    }
    _boundProfileId.value = profile.id
    if (model != null) {
      try {
        applyProfileConfigValues(profile = profile, model = model)
      } catch (e: Exception) {
        Log.e(TAG, "applyProfileConfigValues failed for id=${profile.id}", e)
      }
    }
    viewModelScope.launch {
      try {
        val prompt =
          profile.systemPrompt.ifBlank {
            SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
          }
        _uiSystemPrompt.value = prompt
      } catch (e: Exception) {
        Log.e(TAG, "bindProfile failed for id=${profile.id}", e)
      }
    }
  }

  /**
   * 【T05-②】把记录里的采样参数写入 [model]。
   *
   * 【设计要点】
   * - 运行时键是 [ConfigKey.label]，记录里存的是 [ConfigKey.id]，必须显式映射；
   * - 只写入 [model] 实际支持的配置键（存在于 `model.configValues`），其余丢弃并 `Log.w`；
   * - 值按「当前值的运行时类型」转换；`toFloatOrNull()` / `toIntOrNull()` 失败则跳过该键并
   *   `Log.w`，绝不强转导致崩溃；
   * - 若模型已初始化，本次不生效、只提示「下次初始化生效」，**绝不**在此 cleanup 重建
   *   ——那正好违背 T05-① 的模型保活目标。
   */
  private fun applyProfileConfigValues(profile: ConversationProfile, model: Model) {
    if (profile.configValues.isEmpty()) return
    val alreadyInitialized = model.instance != null
    val updated = model.configValues.toMutableMap()
    var changed = false
    for ((keyId, rawValue) in profile.configValues) {
      val label = PROFILE_CONFIG_ID_TO_LABEL[keyId]
      if (label == null) {
        Log.w(TAG, "Profile config key '$keyId' is not mapped; skipped.")
        continue
      }
      if (!updated.containsKey(label)) {
        Log.w(TAG, "Model '${model.name}' does not support config '$label'; skipped.")
        continue
      }
      val converted = convertProfileValue(rawValue = rawValue, current = updated[label])
      if (converted == null) {
        Log.w(TAG, "Cannot convert profile config '$label' value '$rawValue'; skipped.")
        continue
      }
      if (updated[label] != converted) {
        updated[label] = converted
        changed = true
      }
    }
    if (!changed) return
    val oldValues = model.configValues
    model.configValues = updated
    if (alreadyInitialized) {
      Log.w(TAG, "Model '${model.name}' already initialized; profile configs apply next init.")
      addMessage(model, ChatMessageConfigValuesChange(model, oldValues, updated))
    }
  }

  /** 【T05-②】按当前值的运行时类型，把记录里的字符串值还原为 `model.configValues` 需要的类型。 */
  private fun convertProfileValue(rawValue: String, current: Any?): Any? =
    when (current) {
      is Float -> rawValue.toFloatOrNull()
      is Int -> rawValue.toIntOrNull()
      is Double -> rawValue.toDoubleOrNull()
      is Boolean -> rawValue.toBoolean()
      is String -> rawValue
      else -> null
    }

  /**
   * 【T05-②】把 [model] 当前的采样参数快照为「记录格式」（key 用 [ConfigKey.id]，值字符串化）。
   *
   * 用于「另存为特调」与写回当前记录，保证 round-trip 一致。
   */
  fun profileConfigValuesOf(model: Model): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for ((label, value) in model.configValues) {
      val keyId = PROFILE_CONFIG_LABEL_TO_ID[label] ?: continue
      result[keyId] = value.toString()
    }
    return result
  }

  /**
   * 【T05-②】把 [model] 当前的采样参数写回已绑定的记录。
   *
   * 未绑定记录（或仓库不可用）时为空操作；异常仅记录日志，不影响界面。
   */
  fun persistConfigValuesToProfile(model: Model) {
    val profileId = _boundProfileId.value
    if (profileId.isNullOrBlank() || conversationProfileRepository == null) return
    val values = profileConfigValuesOf(model)
    viewModelScope.launch {
      try {
        // 【T05-④】记录可能在会话进行中被删除：以 DataStore 为准校验，避免写进一条不存在的
        // 记录（仓库里是空操作，用户会以为保存成功）。不存在则解除绑定，后续回退全局默认值。
        if (conversationProfileRepository.getProfileFromStore(profileId) == null) {
          Log.w(TAG, "Bound profile '$profileId' no longer exists; unbind and skip persist.")
          _boundProfileId.value = null
          return@launch
        }
        conversationProfileRepository.updateConfigValues(profileId, values)
      } catch (e: Exception) {
        Log.e(TAG, "persistConfigValuesToProfile failed for id=$profileId", e)
      }
    }
  }

  /**
   * Applies a system prompt change to the given [task] and [model].
   *
   * This method updates the UI system prompt, saves the new prompt to the repository, and resets
   * the session with the new prompt.
   *
   * @param task The task to apply the system prompt change to.
   * @param model The model to apply the system prompt change to.
   * @param newPrompt The new system prompt to apply.
   * @param systemPromptUpdatedMessage The message to add to the chat after the system prompt is
   *   updated.
   */
  fun applySystemPromptChange(
    task: Task,
    model: Model,
    newPrompt: String,
    systemPromptUpdatedMessage: String,
  ) {
    _uiSystemPrompt.value = newPrompt
    viewModelScope.launch {
      // 【T04】绑定了记录时写入记录级提示词；否则回退写入全局默认值（旧键降级为默认）。
      // 【T05-④】记录若在会话进行中被删除 → 解除绑定并回退全局，绝不静默丢弃用户编辑。
      val profileId = _boundProfileId.value
      var useProfile = false
      try {
        if (!profileId.isNullOrBlank() && conversationProfileRepository != null) {
          if (conversationProfileRepository.getProfileFromStore(profileId) != null) {
            useProfile = true
          } else {
            Log.w(TAG, "Bound profile '$profileId' no longer exists; fallback to global prompt.")
            _boundProfileId.value = null
          }
        }
        if (useProfile && profileId != null && conversationProfileRepository != null) {
          conversationProfileRepository.updateSystemPrompt(profileId, newPrompt)
        } else {
          systemPromptRepository?.updateSystemPrompt(task.id, newPrompt)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist system prompt for task=${task.id}", e)
      }
      resetSession(
        task = task,
        model = model,
        systemInstruction = newPrompt,
        supportImage = true,
        supportAudio = true,
        onDone = { addMessage(model, ChatMessageInfo(content = systemPromptUpdatedMessage)) },
      )
    }
  }

  open fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    // 【防闪退】为本轮生成协程挂一个异常兜底：本地执行器 / 流式渲染若抛出未捕获异常
    // （例如首次运行初始化竞态：模型尚未就绪就发送消息），不再让异常冒泡导致应用崩溃
    // （会落到全局崩溃展示页），而是复位状态并回调 onError，由界面友好提示。
    val generationExceptionHandler =
      CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "generateResponse failed with an uncaught exception.", throwable)
        setPreparing(false)
        setInProgress(false)
        onError(throwable.message ?: "Generation failed.")
      }
    generationJob =
      viewModelScope.launch(Dispatchers.Default + generationExceptionHandler) {
      setInProgress(true)
      setPreparing(true)
      // 【M7】新一轮开始前清空上一轮的统计，避免展示过期数据。
      _generationStats.value = null

      // 【M2】先决定这一轮走本地还是云端。默认行为是「本地优先」：
      // 只有用户在设置里显式打开云端开关、且存在可用配置时才会走到云端。
      //
      // 【M7】增加对话页来源切换：用户可强制 LOCAL / REMOTE，
      // 覆盖「跟随激活配置」的默认语义。
      val chosenSource = _inferenceSource.value
      val activeConfig = resolveActiveRemoteConfig()
      // 是否实际走云端：
      //  - AUTO   → 有激活配置则云端
      //  - LOCAL  → 永不走云端
      //  - REMOTE → 仅在存在激活配置时走云端（否则回退本地）
      val useRemote =
        when (chosenSource) {
          InferenceSource.LOCAL -> false
          InferenceSource.REMOTE -> activeConfig != null
          InferenceSource.AUTO -> activeConfig != null
        }
      val remoteConfig = if (useRemote) activeConfig else null
      lastGenerationWasRemote = remoteConfig != null
      val effectiveAccelerator =
        if (remoteConfig != null) remoteConfig.displayLabel() else accelerator

      // 【M7】token 速度统计的累计变量。
      val start = System.currentTimeMillis()
      var tokenCount = 0
      var firstTokenAt = -1L
      var lastTokenAt = start

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = effectiveAccelerator))

      val attachments = mutableListOf<Attachment>()
      for (image in images) {
        attachments.add(Attachment.ImageBitmap(image))
      }
      for (audioMessage in audioMessages) {
        attachments.add(Attachment.AudioBytes(audioMessage.genByteArrayForWav()))
      }

      val enableThinking =
        allowThinking &&
          model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
      val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else emptyMap()
      val metadata =
        if (extraContext.isNotEmpty()) {
          mapOf(AgentRequest.LITERTLM_EXTRA_CONTEXT to extraContext)
        } else {
          emptyMap()
        }

      val request = AgentRequest(query = input, attachments = attachments, metadata = metadata)

      val context = AgentExecutionContext()

      var firstRun = true

      // Run inference.
      //
      // 【M2】本地与云端在此合流：两条链路都产出 Flow<AgentEvent>，
      // 下面整段 when (event) 的渲染逻辑对两者通用，无需分别维护。
      val eventFlow =
        if (remoteConfig != null) {
          val history = uiState.value.messagesByModel[model.name] ?: emptyList()
          remoteProvider!!.streamChat(
            config = remoteConfig,
            turns = buildRemoteChatTurns(messages = history, fallbackInput = input),
            systemPrompt = remoteConfig.systemPrompt.ifBlank { _uiSystemPrompt.value },
          )
        } else {
          runtimeExecutor.executeStream(context = context, request = request)
        }

      eventFlow.collect { event ->
        when (event) {
          is AgentEvent.LoopInitiated -> {}
          is AgentEvent.StreamToken -> {
            val lastMessage = getLastMessage(model = model)
            val wasLoading = lastMessage?.type == ChatMessageType.LOADING
            // Remove the last message if it is a "loading" message.
            // This will only be done once.
            if (wasLoading) {
              removeLastMessage(model = model)
            }

            val thinkingText = event.thinking
            val isThinking = !thinkingText.isNullOrEmpty()
            var currentLastMessage = getLastMessage(model = model)

            // If thinking is enabled, add a thinking message.
            if (isThinking) {
              if (currentLastMessage?.type != ChatMessageType.THINKING) {
                addMessage(
                  model = model,
                  message =
                    ChatMessageThinking(
                      content = "",
                      inProgress = true,
                      side = ChatSide.AGENT,
                      accelerator = effectiveAccelerator,
                      hideSenderLabel =
                        currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                    ),
                )
              }
              updateLastThinkingMessageContentIncrementally(
                model = model,
                partialContent = thinkingText!!,
              )
            } else {
              if (currentLastMessage?.type == ChatMessageType.THINKING) {
                val thinkingMsg = currentLastMessage as ChatMessageThinking
                if (thinkingMsg.inProgress) {
                  replaceLastMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = thinkingMsg.content,
                        inProgress = false,
                        side = thinkingMsg.side,
                        accelerator = thinkingMsg.accelerator,
                        hideSenderLabel = thinkingMsg.hideSenderLabel,
                      ),
                    type = ChatMessageType.THINKING,
                  )
                }
              }
              currentLastMessage = getLastMessage(model = model)
              if (
                currentLastMessage?.type != ChatMessageType.TEXT ||
                  currentLastMessage.side != ChatSide.AGENT
              ) {
                // Add an empty message that will receive streaming results.
                addMessage(
                  model = model,
                  message =
                    ChatMessageText(
                      content = "",
                      side = ChatSide.AGENT,
                      accelerator = effectiveAccelerator,
                      hideSenderLabel =
                        currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                          currentLastMessage?.type == ChatMessageType.THINKING,
                    ),
                )
              }

              // Incrementally update the streamed partial results.
              val latencyMs: Long = if (event.done) System.currentTimeMillis() - start else -1
              if (event.token.isNotEmpty() || wasLoading || event.done) {
                updateLastTextMessageContentIncrementally(
                  model = model,
                  partialContent = event.token,
                  latencyMs = latencyMs.toFloat(),
                )
              }
            }

            // 【M7】累计 content token 数（thinking 分片不计入，与真实 token 口径更接近）。
            val isContentChunk = event.thinking == null
            if (isContentChunk && event.token.isNotEmpty()) {
              tokenCount++
            }
            if (isContentChunk && event.token.isNotEmpty() && firstTokenAt < 0) {
              firstTokenAt = System.currentTimeMillis() - start
            }
            // 用最后一个 content 分片的时间近似总耗时。
            if (isContentChunk && event.token.isNotEmpty()) {
              lastTokenAt = System.currentTimeMillis()
            }

            if (firstRun) {
              firstRun = false
              setPreparing(false)
              onFirstToken(model)
            }
          }
          is AgentEvent.LoopTerminated -> {
            val finalLastMessage = getLastMessage(model = model)
            if (finalLastMessage?.type == ChatMessageType.THINKING) {
              val thinkingMsg = finalLastMessage as ChatMessageThinking
              if (thinkingMsg.inProgress) {
                replaceLastMessage(
                  model = model,
                  message =
                    ChatMessageThinking(
                      content = thinkingMsg.content,
                      inProgress = false,
                      side = thinkingMsg.side,
                      accelerator = thinkingMsg.accelerator,
                      hideSenderLabel = thinkingMsg.hideSenderLabel,
                    ),
                  type = ChatMessageType.THINKING,
                )
              }
            }
            setInProgress(false)
            setPreparing(false)
            // 【M7】生成完成，发布本轮 token 速度统计供界面展示。
            val totalMs = System.currentTimeMillis() - start
            val elapsedMs = lastTokenAt - start
            val tokensPerSecond =
              if (tokenCount > 0 && elapsedMs > 0) {
                (tokenCount * 1000f) / elapsedMs
              } else {
                0f
              }
            _generationStats.value =
              GenerationStats(
                tokenCount = tokenCount,
                tokensPerSecond = tokensPerSecond,
                firstTokenMs = if (firstTokenAt > 0) firstTokenAt else 0,
                totalMs = totalMs,
                source =
                  if (remoteConfig != null) InferenceSource.REMOTE
                  else InferenceSource.LOCAL,
              )
            onDone()
          }
          is AgentEvent.Error -> {
            Log.e(TAG, "Error occurred while running inference: ${event.errorMessage}")
            setInProgress(false)
            setPreparing(false)
            onError(event.errorMessage)
          }
          is AgentEvent.LoopCancelled -> {
            setInProgress(false)
            setPreparing(false)
          }
        }
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    // 【M2】两条中断路径分开处理，避免互相干扰：
    // - 云端：请求跑在协程里，直接取消 Job 即可。取消后不会再有事件回调，
    //   所以 preparing 状态要在这里手动复位（本地路径是由 LoopCancelled 事件复位的）。
    // - 本地：完全保持原有行为 —— 交给 runtimeExecutor.interrupt() 优雅收尾，
    //   它会回调 LoopCancelled 并把 inProgress / preparing 一起复位。
    if (lastGenerationWasRemote) {
      setPreparing(false)
      generationJob?.cancel()
    }
    generationJob = null
    runtimeExecutor.interrupt()
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    actionChannel: SendChannel<ToolAction>? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
    clearHistory: Boolean = true,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      if (clearHistory) {
        clearAllMessages(model = model)
      }
      stopResponse(model = model)

      // 【M2】云端模式下不需要初始化本地模型：跳过 runtimeExecutor.resetSession()，
      // 否则只是打开云端对话页，也会把几个 GB 的本地模型加载进内存。
      if (resolveActiveRemoteConfig() != null) {
        Log.d(TAG, "Remote API active, skip local session reset")
        setIsResettingSession(false)
        onDone()
        return@launch
      }

      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = actionChannel,
          supportImage = supportImage,
          supportAudio = supportAudio,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          systemInstruction = systemInstruction,
          initialMessages = initialMessages,
        )
      runtimeExecutor.resetSession(config = config)

      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // 【M2】云端模式下本地模型未必初始化过，跳过下面的等待 —— 否则「重新生成」
      // 会因为没有本地模型而直接报 "Model not initialized."。
      if (resolveActiveRemoteConfig() == null) {
        // Wait for model to be initialized.
        if (model.instance == null) {
          try {
            model.awaitInitialization()
          } catch (e: Exception) {
            onError("Model initialization failed: ${e.message}")
            return@launch
          }
        }
        if (model.instance == null) {
          onError("Model not initialized.")
          return@launch
        }
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // 【M2】云端接口报错与本地模型无关（可能是密钥过期、欠费、限流），
    // 这时去销毁并重建本地会话既没意义又很慢，还会把刚加载好的模型卸掉。
    if (lastGenerationWasRemote) {
      Log.d(TAG, "Remote generation failed, skip local model re-initialization")
      setInProgress(false)
      setPreparing(false)
      return
    }

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      // 本地推理失败时才会走到这里
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = model,
            onDone = {
              // Add a warning message for re-initializing the session.
              addMessage(
                model = model,
                message = ChatMessageWarning(content = "Session re-initialized"),
              )
            },
            onError = {
              addMessage(
                model = model,
                message =
                  ChatMessageError(
                    content = "Failed to re-initialize session, please restart the app"
                  ),
              )
            },
          )
        },
      )
    }
  }

  /**
   * 把界面上的历史消息转换成远端接口需要的多轮对话结构。
   *
   * 处理规则：
   * 1. 只保留 [ChatMessageText]；加载态、信息提示、错误、配置变更等中间态消息对远端
   *    模型没有意义，还会污染上下文。
   * 2. 图片 / 语音消息当前不上传云端，用一条占位文本提示模型本轮包含附件，
   *    避免模型因为「凭空少了一段对话」而答非所问。
   * 3. 连续相同角色的消息会被合并 —— 部分服务商严格要求 user / assistant 交替出现。
   * 4. 末尾的模型回复会被丢弃。OpenAI 兼容接口要求最后一条是 user，
   *    而「重新生成」场景下这正好等价于把上一次回答撤回再问一遍。
   *
   * @param messages 当前模型的完整消息列表（已包含本轮用户输入）。
   * @param fallbackInput 历史里一条可用文本都没有时的兜底输入，通常是纯语音/纯图片场景。
   */
  private fun buildRemoteChatTurns(
    messages: List<ChatMessage>,
    fallbackInput: String,
  ): List<ChatTurn> {
    val turns = mutableListOf<ChatTurn>()

    for (message in messages) {
      when (message) {
        is ChatMessageText -> {
          if (message.content.isBlank()) continue
          val role = if (message.side == ChatSide.USER) ChatRole.USER else ChatRole.ASSISTANT
          val last = turns.lastOrNull()
          if (last != null && last.role == role) {
            turns[turns.size - 1] = ChatTurn(role, last.content + "\n" + message.content)
          } else {
            turns.add(ChatTurn(role, message.content))
          }
        }
        is ChatMessageImage -> {
          if (message.bitmaps.isNotEmpty()) {
            appendUserTurn(turns, REMOTE_ATTACHMENT_IMAGE_HINT)
          }
        }
        is ChatMessageAudioClip -> {
          appendUserTurn(turns, REMOTE_ATTACHMENT_AUDIO_HINT)
        }
        else -> {
          // 其余消息类型不参与上下文
        }
      }
    }

    while (turns.isNotEmpty() && turns.last().role != ChatRole.USER) {
      turns.removeAt(turns.size - 1)
    }

    if (turns.isEmpty() && fallbackInput.isNotBlank()) {
      turns.add(ChatTurn(ChatRole.USER, fallbackInput))
    }
    return turns
  }

  /** 追加一条用户轮次；若上一条也是用户则合并，保持 user / assistant 交替。 */
  private fun appendUserTurn(turns: MutableList<ChatTurn>, content: String) {
    val last = turns.lastOrNull()
    if (last != null && last.role == ChatRole.USER) {
      turns[turns.size - 1] = ChatTurn(ChatRole.USER, last.content + "\n" + content)
    } else {
      turns.add(ChatTurn(ChatRole.USER, content))
    }
  }
}

@HiltViewModel
open class LlmChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  apiProviderRepository: ApiProviderRepository,
  remoteProvider: RemoteOpenAICompatProvider,
  conversationProfileRepository: ConversationProfileRepository,
) :
LlmChatViewModelBase(
  systemPromptRepository,
  userDataDataStore,
  null,
  runtimeExecutor,
  apiProviderRepository,
  remoteProvider,
  conversationProfileRepository,
)

@HiltViewModel
class LlmAskImageViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  apiProviderRepository: ApiProviderRepository,
  remoteProvider: RemoteOpenAICompatProvider,
  conversationProfileRepository: ConversationProfileRepository,
) :
LlmChatViewModelBase(
  systemPromptRepository,
  userDataDataStore,
  null,
  runtimeExecutor,
  apiProviderRepository,
  remoteProvider,
  conversationProfileRepository,
)

@HiltViewModel
class LlmAskAudioViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  apiProviderRepository: ApiProviderRepository,
  remoteProvider: RemoteOpenAICompatProvider,
  conversationProfileRepository: ConversationProfileRepository,
) :
LlmChatViewModelBase(
  systemPromptRepository,
  userDataDataStore,
  null,
  runtimeExecutor,
  apiProviderRepository,
  remoteProvider,
  conversationProfileRepository,
)
