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

import androidx.hilt.navigation.compose.hiltViewModel
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.encourage.app.GalleryEvent
import com.encourage.app.R
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.Model
import com.encourage.app.data.ModelCapability
import com.encourage.app.data.RuntimeType
import com.encourage.app.data.Task
import com.encourage.app.firebaseAnalytics
import com.encourage.app.ui.common.chat.ChatMessage
import com.encourage.app.ui.common.chat.ChatMessageAudioClip
import com.encourage.app.ui.common.chat.ChatMessageImage
import com.encourage.app.ui.common.chat.ChatMessageText
import com.encourage.app.ui.common.chat.ChatView
import com.encourage.app.ui.common.chat.SendMessageTrigger
import com.encourage.app.ui.common.chat.convertToLitertMessage
import com.encourage.app.ui.home.ApiProviderViewModel
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.theme.emptyStateContent
import com.encourage.app.ui.theme.emptyStateTitle

private const val TAG = "AGLlmChatScreen"

@Composable
fun LlmChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  taskId: String = BuiltInTaskId.LLM_CHAT,
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onAgentClicked: () -> Unit = {},
  currentAgentName: String? = null,
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean, () -> Unit) -> Unit)? =
    null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  viewModel: LlmChatViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  getActiveSkills: () -> List<String> = { emptyList() },
  skillCount: Int = 0,
  mcpCount: Int = 0,
  mcpToolsCount: Int = 0,
  onNavigateToTask: ((Task) -> Unit)? = null,
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = taskId,
    navigateUp = navigateUp,
    modifier = modifier,
    onSkillClicked = onSkillClicked,
    onMcpClicked = onMcpClicked,
    onAgentClicked = onAgentClicked,
    currentAgentName = currentAgentName,
    onFirstToken = onFirstToken,
    onGenerateResponseDone = onGenerateResponseDone,
    onResetSessionClickedOverride = onResetSessionClickedOverride,
    composableBelowMessageList = composableBelowMessageList,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    skillCount = skillCount,
    mcpCount = mcpCount,
    mcpToolsCount = mcpToolsCount,
    onSystemPromptChanged = onSystemPromptChanged,
    emptyStateComposable = emptyStateComposable,
    sendMessageTrigger = sendMessageTrigger,
    showImagePicker = showImagePicker,
    showAudioPicker = showAudioPicker,
    getActiveSkills = getActiveSkills,
    onNavigateToTask = onNavigateToTask,
  )
}

@Composable
fun LlmAskImageScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskImageViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  onNavigateToTask: ((Task) -> Unit)? = null,
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_IMAGE,
    navigateUp = navigateUp,
    modifier = modifier,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    showImagePicker = true,
    showAudioPicker = false,
    onNavigateToTask = onNavigateToTask,
    emptyStateComposable = { model ->
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(stringResource(R.string.askimage_emptystate_title), style = emptyStateTitle)
          val contentRes =
            if (model.runtimeType == RuntimeType.AICORE) {
              R.string.askimage_emptystate_content_aicore
            } else {
              R.string.askimage_emptystate_content
            }
          Text(
            stringResource(contentRes),
            style = emptyStateContent,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
  )
}

@Composable
fun LlmAskAudioScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmAskAudioViewModel = hiltViewModel(),
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  onNavigateToTask: ((Task) -> Unit)? = null,
) {
  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_ASK_AUDIO,
    navigateUp = navigateUp,
    modifier = modifier,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    showImagePicker = false,
    showAudioPicker = true,
    onNavigateToTask = onNavigateToTask,
    emptyStateComposable = {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(stringResource(R.string.askaudio_emptystate_title), style = emptyStateTitle)
          Text(
            stringResource(R.string.askaudio_emptystate_content),
            style = emptyStateContent,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    },
  )
}

@Composable
fun ChatViewWrapper(
  viewModel: LlmChatViewModelBase,
  modelManagerViewModel: ModelManagerViewModel,
  taskId: String,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onAgentClicked: () -> Unit = {},
  currentAgentName: String? = null,
  onFirstToken: (Model) -> Unit = {},
  onGenerateResponseDone: (Model) -> Unit = {},
  onResetSessionClickedOverride: ((Task, Model, List<ChatMessage>, Boolean, () -> Unit) -> Unit)? =
    null,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  getActiveSkills: () -> List<String> = { emptyList() },
  skillCount: Int = 0,
  mcpCount: Int = 0,
  mcpToolsCount: Int = 0,
  /** 【N6 统一入口】能力选择器导航回调，透传给 ChatView。 */
  onNavigateToTask: ((Task) -> Unit)? = null,
) {
  val context = LocalContext.current
  // 【防闪退】任务尚未装配完成（首次运行 / 冷启动竞态，uiState.tasks 还未就绪）时，
  // 原实现用 `!!` 强解包会抛 NPE 直接崩溃。这里短路返回一个空占位，绝不崩溃。
  val task = modelManagerViewModel.getTaskById(id = taskId)
  if (task == null) {
    Log.e(TAG, "ChatViewWrapper: task '$taskId' is not available yet; showing placeholder.")
    Box(modifier = modifier.fillMaxSize())
    return
  }
  val scope = rememberCoroutineScope()

  // 【M2-5b】当前生效的云端配置。非空时在聊天页顶部显示来源提示条，
  // 让用户随时知道这一轮对话的数据会不会离开本机。
  val apiProviderViewModel: ApiProviderViewModel = hiltViewModel()
  val activeApiConfig by apiProviderViewModel.activeConfig.collectAsState()
  val remoteProviderName = activeApiConfig?.displayLabel().orEmpty()

  // 【M7】推理来源选择 + token 速度统计，供对话页顶部展示与切换。
  val inferenceSource by viewModel.inferenceSource.collectAsState()
  val generationStats by viewModel.generationStats.collectAsState()

  ChatView(
    task = task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    onSendMessage = { model, messages ->
      for (message in messages) {
        viewModel.addMessage(model = model, message = message)
      }

      var text = ""
      val images: MutableList<Bitmap> = mutableListOf()
      val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
      var chatMessageText: ChatMessageText? = null
      for (message in messages) {
        if (message is ChatMessageText) {
          chatMessageText = message
          text = message.content
        } else if (message is ChatMessageImage) {
          images.addAll(message.bitmaps)
        } else if (message is ChatMessageAudioClip) {
          audioMessages.add(message)
        }
      }
      if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
        if (text.isNotEmpty()) {
          modelManagerViewModel.addTextInputHistory(text)
        }
        viewModel.generateResponse(
          model = model,
          input = text,
          images = images,
          audioMessages = audioMessages,
          onFirstToken = onFirstToken,
          onDone = { onGenerateResponseDone(model) },
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )

        val activeSkills = getActiveSkills()
        Log.d(
          TAG,
          "Analytics: generate_action, capability_name=${task.id}, active_skills=${activeSkills.joinToString(",")}, active_mcp_servers_count=$mcpCount, active_mcp_tools_count=$mcpToolsCount",
        )
        firebaseAnalytics?.logEvent(
          GalleryEvent.GENERATE_ACTION.id,
          Bundle().apply {
            putString("capability_name", task.id)
            putString("model_id", model.name)
            putBoolean("has_image", images.isNotEmpty())
            putInt("image_count", images.size)
            putBoolean("has_audio", audioMessages.isNotEmpty())
            putInt("audio_count", audioMessages.size)
            putInt("active_skills_count", activeSkills.size)
            putString("active_skills_list", activeSkills.joinToString(","))
            putInt("active_mcp_servers_count", mcpCount)
            putInt("active_mcp_tools_count", mcpToolsCount)
          },
        )
      }
    },
    onRunAgainClicked = { model, message ->
      if (message is ChatMessageText) {
        viewModel.runAgain(
          model = model,
          message = message,
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              errorMessage = errorMessage,
              modelManagerViewModel = modelManagerViewModel,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
        )
      }
    },
    onBenchmarkClicked = { _, _, _, _ -> },
    onResetSessionClicked = { model, chatMessages, clearHistory, onDone ->
      val litertMessages = chatMessages.mapNotNull { convertToLitertMessage(it) }
      if (onResetSessionClickedOverride != null) {
        onResetSessionClickedOverride(task, model, chatMessages, clearHistory, onDone)
      } else {
        viewModel.resetSession(
          task = task,
          model = model,
          systemInstruction = curSystemPrompt,
          supportImage = showImagePicker,
          supportAudio = showAudioPicker,
          initialMessages = litertMessages,
          onDone = onDone,
          clearHistory = clearHistory,
        )
      }
    },
    showStopButtonInInputWhenInProgress = true,
    onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
    onSkillClicked = onSkillClicked,
    onMcpClicked = onMcpClicked,
    onAgentClicked = onAgentClicked,
    currentAgentName = currentAgentName,
    navigateUp = navigateUp,
    skillCount = skillCount,
    mcpCount = mcpCount,
    modifier = modifier,
    composableBelowMessageList = composableBelowMessageList,
    showImagePicker = showImagePicker,
    emptyStateComposable = emptyStateComposable,
    allowEditingSystemPrompt = allowEditingSystemPrompt,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = onSystemPromptChanged,
    sendMessageTrigger = sendMessageTrigger,
    showAudioPicker = showAudioPicker,
    remoteProviderName = remoteProviderName,
    inferenceSource = inferenceSource,
    onInferenceSourceChange = { viewModel.setInferenceSource(it) },
    generationStats = generationStats,
    onNavigateToTask = onNavigateToTask,
  )
}
