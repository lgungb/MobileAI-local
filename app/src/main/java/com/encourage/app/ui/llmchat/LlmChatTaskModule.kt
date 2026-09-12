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
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.agent.AgentRuntimeConfig
import com.encourage.app.agent.AgentRuntimeExecutor
import com.encourage.app.agent.AiChatExecutor
import com.encourage.app.customtasks.common.CustomTask
import com.encourage.app.customtasks.common.CustomTaskDataForBuiltinTask
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.Category
import com.encourage.app.data.Model
import com.encourage.app.data.RuntimeType
import com.encourage.app.data.Task
import com.encourage.app.data.conversation.ConversationProfileRepository
import com.encourage.app.ui.theme.emptyStateContent
import com.encourage.app.ui.theme.emptyStateTitle
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatTask"

////////////////////////////////////////////////////////////////////////////////////////////////////
// AI Chat.

class LlmChatTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
  private val conversationProfileRepository: ConversationProfileRepository,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_CHAT,
      label = context.getString(R.string.task_label_ai_chat),
      category = Category.LLM,
      icon = Icons.Outlined.Forum,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_ai_chat),
      shortDescription = context.getString(R.string.task_short_desc_ai_chat),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = model.llmSupportImage,
          supportAudio = model.llmSupportAudio,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmChatViewModel = hiltViewModel()
    val boundProfileId by viewModel.boundProfileId.collectAsState()
    // 【T04】按记录绑定提示词：有 profileId 用记录级提示词，否则回退 task 全局默认。
    LaunchedEffect(task, myData.profileId) {
      val profile =
        myData.profileId?.takeIf { it.isNotBlank() }?.let { id ->
          try {
            conversationProfileRepository.getProfile(id = id)
          } catch (e: Exception) {
            Log.e(TAG, "getProfile failed for id=$id", e)
            null
          }
        }
      if (profile == null && !myData.conversationType.isNullOrBlank()) {
        Log.w(
          TAG,
          "conversationType=${myData.conversationType} without profileId; " +
            "fallback to task default prompt.",
        )
      }
      viewModel.bindProfile(profile, task)
    }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmChatScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      showImagePicker = true,
      showAudioPicker = true,
      boundProfileId = boundProfileId.orEmpty(),
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
      emptyStateComposable = { model ->
        Box(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier =
              Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(stringResource(R.string.aichat_emptystate_title), style = emptyStateTitle)
            Text(
              stringResource(R.string.aichat_emptystate_content),
              style = emptyStateContent,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            val multimodalRes =
              when {
                model.llmSupportImage && model.llmSupportAudio -> {
                  if (model.runtimeType == RuntimeType.AICORE) {
                    R.string.aichat_emptystate_support_image_aicore_audio
                  } else {
                    R.string.aichat_emptystate_support_image_audio
                  }
                }
                model.llmSupportImage -> {
                  if (model.runtimeType == RuntimeType.AICORE) {
                    R.string.aichat_emptystate_support_image_aicore
                  } else {
                    R.string.aichat_emptystate_support_image
                  }
                }
                model.llmSupportAudio -> R.string.aichat_emptystate_support_audio
                else -> null
              }

            if (multimodalRes != null) {
              Text(
                stringResource(multimodalRes),
                style = emptyStateContent,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
    conversationProfileRepository: ConversationProfileRepository,
  ): CustomTask {
    return LlmChatTask(context, executor, conversationProfileRepository)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask image.

// 【T04】已收敛到统一对话界面（llm_chat + ConversationType）。保留仅为兼容旧深链与历史记录。
class LlmAskImageTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_ASK_IMAGE,
      label = context.getString(R.string.task_label_ask_image),
      category = Category.LLM,
      icon = Icons.Outlined.Mms,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_ask_image),
      shortDescription = context.getString(R.string.task_short_desc_ask_image),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = true,
          supportAudio = false,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskImageViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskImageScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskImageModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmAskImageTask(context, executor)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Ask audio.

// 【T04】已收敛到统一对话界面（llm_chat + ConversationType）。保留仅为兼容旧深链与历史记录。
class LlmAskAudioTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_ASK_AUDIO,
      label = context.getString(R.string.task_label_audio_scribe),
      category = Category.LLM,
      icon = Icons.Outlined.Mic,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_audio_scribe),
      shortDescription = context.getString(R.string.task_short_desc_audio_scribe),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          supportImage = false,
          supportAudio = true,
          systemInstruction = systemInstruction?.toString(),
        )
      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    val viewModel: LlmAskAudioViewModel = hiltViewModel()
    LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
    val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
    val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
    LlmAskAudioScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      viewModel = viewModel,
      allowEditingSystemPrompt = true,
      curSystemPrompt = uiSystemPrompt,
      onSystemPromptChanged = { newPrompt ->
        val selectedModel = myData.modelManagerViewModel.uiState.value.selectedModel
        viewModel.applySystemPromptChange(
          task = task,
          model = selectedModel,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object LlmAskAudioModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmAskAudioTask(context, executor)
  }
}
