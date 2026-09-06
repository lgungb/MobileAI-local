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

package com.encourage.app.ui.llmsingleturn

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.agent.AgentEvent
import com.encourage.app.common.processLlmResponse
import com.encourage.app.data.api.ApiProviderConfig
import com.encourage.app.data.api.ApiProviderRepository
import com.encourage.app.data.api.ChatRole
import com.encourage.app.data.api.ChatTurn
import com.encourage.app.data.api.RemoteOpenAICompatProvider
import com.encourage.app.data.Model
import com.encourage.app.data.PromptOptionRepository
import com.encourage.app.data.Task
import com.encourage.app.data.awaitInitialization
import com.encourage.app.data.customOptionKey
import com.encourage.app.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGLlmSingleTurnVM"

data class LlmSingleTurnUiState(
  /** Indicates whether the runtime is currently processing a message. */
  val inProgress: Boolean = false,

  /**
   * Indicates whether the model is preparing (before outputting any result and after initializing).
   */
  val preparing: Boolean = false,

  // model -> <template label -> response>
  val responsesByModel: Map<String, Map<String, String>>,

  /** Selected prompt template type. */
  val selectedPromptTemplateType: PromptTemplateType = PromptTemplateType.entries[0],
)

@HiltViewModel
class LlmSingleTurnViewModel
@Inject
constructor(
  // 【M1】Prompt Lab 自定义模板选项仓库（需求 H3）。
  private val promptOptionRepository: PromptOptionRepository,
  // 【M2】云端推理依赖。与 LlmChatViewModel 用同一套路由规则：
  // 有激活的云端配置就走云端，否则走本地模型。
  private val apiProviderRepository: ApiProviderRepository,
  private val remoteProvider: RemoteOpenAICompatProvider,
) : ViewModel() {
  private val _uiState = MutableStateFlow(createUiState())
  val uiState = _uiState.asStateFlow()

  /** 当前生成任务的句柄，云端模式下靠取消它来中断请求。 */
  private var generationJob: Job? = null

  /** 最近一次生成是否走了云端，决定 stopResponse 的中断方式。 */
  @Volatile private var lastGenerationWasRemote: Boolean = false

  /**
   * 【M1】用户在 Prompt Lab 中自定义的模板选项。
   *
   * key 为 [customOptionKey] 生成的键（"<模板类型>|<编辑器 key>"），value 为自定义选项列表。
   * 界面读取后与内置选项合并展示，因此新增的「语气」「摘要样式」「代码语言」
   * 会和内置项出现在同一个下拉菜单里。
   */
  private val _customOptions = MutableStateFlow<Map<String, List<String>>>(mapOf())
  val customOptions = _customOptions.asStateFlow()

  init {
    // 订阅 DataStore，自定义选项变化时界面自动刷新。
    promptOptionRepository
      .allCustomOptionsFlow()
      .onEach { _customOptions.value = it }
      .launchIn(viewModelScope)
  }

  /**
   * 新增一个自定义模板选项。
   *
   * @param templateType 目标模板类型（语气改写 / 文本摘要 / 代码生成）。
   * @param editorKey 模板中的编辑器 key，例如 "tone"、"style"、"language"。
   * @param value 用户输入的选项名，将同时作为显示文案与拼进提示词的值。
   */
  fun addCustomPromptOption(
    templateType: PromptTemplateType,
    editorKey: String,
    value: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      promptOptionRepository.addCustomOption(templateType.name, editorKey, value)
    }
  }

  /** 删除一个自定义模板选项，参数含义同 [addCustomPromptOption]。 */
  fun removeCustomPromptOption(
    templateType: PromptTemplateType,
    editorKey: String,
    value: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      promptOptionRepository.removeCustomOption(templateType.name, editorKey, value)
    }
  }

  fun generateResponse(task: Task, model: Model, input: String) {
    generationJob = viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // 【M2】本地 / 云端路由。Prompt Lab 是单轮场景，没有历史，
      // 只需把用户输入作为唯一一轮发给远端模型。
      val remoteConfig = apiProviderRepository.activeConfig.firstOrNull()
      lastGenerationWasRemote = remoteConfig != null
      if (remoteConfig != null) {
        runRemoteGeneration(config = remoteConfig, model = model, input = input)
        return@launch
      }

      // Wait for instance to be initialized.
      if (model.instance == null) {
        try {
          model.awaitInitialization()
        } catch (e: Exception) {
          setPreparing(false)
          setInProgress(false)
          return@launch
        }
      }
      if (model.instance == null) {
        setPreparing(false)
        setInProgress(false)
        return@launch
      }

      val supportImage =
        model.llmSupportImage && task.id == com.encourage.app.data.BuiltInTaskId.LLM_ASK_IMAGE
      val supportAudio =
        model.llmSupportAudio && task.id == com.encourage.app.data.BuiltInTaskId.LLM_ASK_AUDIO
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = supportImage,
        supportAudio = supportAudio,
      )
      delay(500)

      // Run inference.
      var firstRun = true
      var response = ""
      model.runtimeHelper.runInference(
        model = model,
        input = input,
        resultListener = { partialResult: String, done: Boolean, partialThinkingResult: String? ->
          if (firstRun) {
            setPreparing(false)
            firstRun = false
          }

          // Incrementally update the streamed partial results.
          response = processLlmResponse(response = "$response$partialResult")

          // Update response.
          updateResponse(
            model = model,
            promptTemplateType = uiState.value.selectedPromptTemplateType,
            response = response,
          )

          if (done) {
            setInProgress(false)
          }
        },
        cleanUpListener = {
          setPreparing(false)
          setInProgress(false)
        },
        onError = { _: String ->
          setPreparing(false)
          setInProgress(false)
        },
        coroutineScope = viewModelScope,
      )
    }
  }

  fun selectPromptTemplate(model: Model, promptTemplateType: PromptTemplateType) {
    Log.d(TAG, "selecting prompt template: ${promptTemplateType.name}")

    // Clear response.
    updateResponse(model = model, promptTemplateType = promptTemplateType, response = "")

    this._uiState.update {
      this.uiState.value.copy(selectedPromptTemplateType = promptTemplateType)
    }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { _uiState.value.copy(inProgress = inProgress) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { _uiState.value.copy(preparing = preparing) }
  }

  fun updateResponse(model: Model, promptTemplateType: PromptTemplateType, response: String) {
    _uiState.update { currentState ->
      val currentResponses = currentState.responsesByModel
      val modelResponses = currentResponses[model.name]?.toMutableMap() ?: mutableMapOf()
      modelResponses[promptTemplateType.name] = response
      val newResponses = currentResponses.toMutableMap()
      newResponses[model.name] = modelResponses
      currentState.copy(responsesByModel = newResponses)
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      // 【M2】云端模式：取消协程即可中断请求，本地 runtimeHelper 此时并没有在跑。
      if (lastGenerationWasRemote) {
        setPreparing(false)
        generationJob?.cancel()
        generationJob = null
      } else {
        model.runtimeHelper.stopResponse(model)
      }
    }
  }

  /**
   * 【M2】走云端接口生成一次结果。
   *
   * Prompt Lab 本地用的是 `runtimeHelper.runInference` 的回调式 API，
   * 云端用的是 [RemoteOpenAICompatProvider] 产出的 [AgentEvent] 流，两者形状不同。
   * 这里做一个适配：把事件流转成与本地同款的「增量累加 + 更新 UI」，
   * 界面层完全感知不到区别。
   *
   * @param config 当前激活的云端配置。
   * @param model 界面上的模型对象（仅用于定位结果存放位置）。
   * @param input 用户拼好的完整提示词。
   */
  private suspend fun runRemoteGeneration(
    config: ApiProviderConfig,
    model: Model,
    input: String,
  ) {
    var firstRun = true
    var response = ""
    val templateType = uiState.value.selectedPromptTemplateType

    remoteProvider
      .streamChat(
        config = config,
        turns = listOf(ChatTurn(ChatRole.USER, input)),
        systemPrompt = config.systemPrompt,
      )
      .collect { event ->
        when (event) {
          is AgentEvent.LoopInitiated -> {}
          is AgentEvent.StreamToken -> {
            if (firstRun) {
              setPreparing(false)
              firstRun = false
            }
            if (event.token.isNotEmpty()) {
              response = processLlmResponse(response = response + event.token)
              updateResponse(model = model, promptTemplateType = templateType, response = response)
            }
          }
          is AgentEvent.LoopTerminated -> {
            setPreparing(false)
            setInProgress(false)
          }
          is AgentEvent.Error -> {
            Log.e(TAG, "Remote generation failed: ${event.errorMessage}")
            setPreparing(false)
            setInProgress(false)
            // 直接把错误写进结果区：Prompt Lab 没有错误态字段，
            // 与其静默失败让用户以为模型没反应，不如把原因显示出来。
            updateResponse(
              model = model,
              promptTemplateType = templateType,
              response = "生成失败：${event.errorMessage}",
            )
          }
          is AgentEvent.LoopCancelled -> {
            setPreparing(false)
            setInProgress(false)
          }
        }
      }
  }

  private fun createUiState(): LlmSingleTurnUiState {
    val responsesByModel: MutableMap<String, Map<String, String>> = mutableMapOf()
    return LlmSingleTurnUiState(responsesByModel = responsesByModel)
  }
}
