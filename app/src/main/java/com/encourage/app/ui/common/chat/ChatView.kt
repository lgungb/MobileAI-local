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

package com.encourage.app.ui.common.chat

// import com.encourage.app.ui.preview.PreviewChatModel
// import com.encourage.app.ui.preview.PreviewModelManagerViewModel
// import com.encourage.app.ui.preview.TASK_TEST1
// import com.encourage.app.ui.theme.GalleryTheme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.encourage.app.GalleryEvent
import com.encourage.app.R
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.ChatMarkdownExporter
import com.encourage.app.data.ConfigKeys
import com.encourage.app.data.Model
import com.encourage.app.data.ModelDownloadStatusType
import com.encourage.app.data.Task
import com.encourage.app.firebaseAnalytics
import com.encourage.app.ui.common.ModelPageAppBar
import com.encourage.app.ui.common.copyBitmapToClipboard
import com.encourage.app.ui.common.saveBitmapToMediaStore
import com.encourage.app.ui.common.shareBitmap
import com.encourage.app.ui.modelmanager.ModelInitializationStatusType
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.llmchat.GenerationStats
import com.encourage.app.ui.llmchat.InferenceSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGChatView"

data class SendMessageTrigger(val model: Model, val messages: List<ChatMessage>)

/**
 * 【M7】推理来源控制条。
 *
 * 当用户配置了云端 API 时显示在消息列表上方，提供「跟随配置 / 本地 / 云端」三档
 * 切换。本地是隐私优先的常态；跟随配置是默认行为（有激活配置则云端，否则本地）；
 * 云端需在设置里已配置激活接口才生效。
 *
 * @param providerName 当前云端服务商名，用于提示「数据会发往谁」。
 * @param inferenceSource 当前选择的来源。
 * @param onInferenceSourceChange 切换来源的回调。
 */
@Composable
fun InferenceSourceBar(
  providerName: String,
  inferenceSource: InferenceSource,
  onInferenceSourceChange: (InferenceSource) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.tertiaryContainer)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // 第一行：隐私提示（数据发往谁）+ 来源切换。
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(
        Icons.Rounded.Cloud,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      Text(
        stringResource(R.string.chat_remote_inference_banner, providerName),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.weight(1f),
      )
    }
    // 第二行：三档来源切换。
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
      SourceFilterChip(
        label = stringResource(R.string.inference_source_auto),
        selected = inferenceSource == InferenceSource.AUTO,
        onClick = { onInferenceSourceChange(InferenceSource.AUTO) },
      )
      SourceFilterChip(
        label = stringResource(R.string.inference_source_local),
        selected = inferenceSource == InferenceSource.LOCAL,
        onClick = { onInferenceSourceChange(InferenceSource.LOCAL) },
      )
      SourceFilterChip(
        label = stringResource(R.string.inference_source_remote),
        selected = inferenceSource == InferenceSource.REMOTE,
        onClick = { onInferenceSourceChange(InferenceSource.REMOTE) },
      )
    }
  }
}

/** 【M7】来源切换用的小号筛选片，统一配色到 tertiaryContainer 场景。 */
@Composable
private fun SourceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    colors =
      FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
        selectedContainerColor = MaterialTheme.colorScheme.tertiary,
        selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
      ),
  )
}

/**
 * 【M7】token 速度统计条。
 *
 * 生成完成后在消息列表上方短暂显示本轮性能：token 数、平均速度（token/s）、
 * 首 token 延迟与总耗时。让用户直观感受到端侧与云端的性能差异。
 */
@Composable
fun GenerationStatsBar(stats: GenerationStats, modifier: Modifier = Modifier) {
  val speed =
    if (stats.tokensPerSecond > 0) {
      stringResource(R.string.inference_stats_speed, stats.tokensPerSecond)
    } else {
      ""
    }
  val summary =
    stringResource(
      R.string.inference_stats_summary,
      stats.tokenCount,
      speed,
      stats.firstTokenMs,
      stats.totalMs,
    )
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      summary,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * A composable that displays a chat interface, allowing users to interact with different models
 * associated with a given task.
 *
 * This composable provides a horizontal pager for switching between models, a model selector for
 * configuring the selected model, and a chat panel for sending and receiving messages. It also
 * manages model initialization, cleanup, and download status, and handles navigation and system
 * back gestures.
 */
@Composable
fun ChatView(
  task: Task,
  viewModel: ChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, Int, Int) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  skillCount: Int = 0,
  mcpCount: Int = 0,
  onResetSessionClicked:
    (
      model: Model, initialMessages: List<ChatMessage>, clearHistory: Boolean, onDone: () -> Unit,
    ) -> Unit =
    { _, _, _, onDone ->
      onDone()
    },
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStopButtonClicked: (Model) -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onAgentClicked: () -> Unit = {},
  currentAgentName: String? = null,
  showStopButtonInInputWhenInProgress: Boolean = false,
  composableBelowMessageList: @Composable (Model) -> Unit = {},
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  emptyStateComposable: @Composable (Model) -> Unit = {},
  allowEditingSystemPrompt: Boolean = false,
  curSystemPrompt: String = "",
  onSystemPromptChanged: (String) -> Unit = {},
  sendMessageTrigger: SendMessageTrigger? = null,
  /**
   * 【M2-5b】当前生效的云端服务商名。
   *
   * 非空表示这一轮对话会发往第三方接口，界面顶部会显示一条常驻提示条；
   * 空字符串表示走本地端侧模型，不显示提示条（本地是本应用的默认与常态）。
   *
   * 这是一个隐私相关的显式告知：用户随时能看出数据会不会离开本机，
   * 而不是靠「记得自己开过开关」。
   */
  remoteProviderName: String = "",
  /**
   * 【M7】推理来源控制。当 [remoteProviderName] 非空（存在云端配置）时，
   * 顶部会显示一个「本地 / 云端」切换条，让用户在对话页直接选择本轮走哪条链路，
   * 而不是只能靠设置里的激活配置。
   *
   * @param inferenceSource 当前选择的来源（AUTO/LOCAL/REMOTE）。
   * @param onInferenceSourceChange 用户切换来源时的回调。
   * @param generationStats 最近一次生成的 token 速度统计，非空时在对话中显示。
   */
  inferenceSource: InferenceSource = InferenceSource.AUTO,
  onInferenceSourceChange: (InferenceSource) -> Unit = {},
  generationStats: GenerationStats? = null,
  /**
   * 【N6 统一入口】能力选择器回调：用户在对话页点击能力 Chip 时触发，
   * 导航到对应任务的对话页（复用当前模型）。为空时不显示能力选择器。
   */
  onNavigateToTask: ((Task) -> Unit)? = null,
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  // Image viewer related.
  var selectedImageIndex by remember { mutableIntStateOf(-1) }
  var allImageViewerImages by remember { mutableStateOf<List<Bitmap>>(listOf()) }
  var showImageViewer by remember { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }

  // Chat history drawer.
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val allHistorySessions by viewModel.historySessions.collectAsState()
  val historySessions =
    remember(allHistorySessions, task.id) { allHistorySessions.filter { it.taskId == task.id } }

  val context = LocalContext.current

  val currentMessages = uiState.messagesByModel[selectedModel.name] ?: emptyList()
  // 协程作用域：导出流程（生成标题、写文件、弹出提示）都依赖它，须在导出逻辑之前声明。
  val scope = rememberCoroutineScope()

  // ==========================================================================
  // 【M1】对话导出：保存为 Markdown
  // 流程：生成标题（模型优先，超时回退）→ 弹出标题确认框（可编辑）→ 系统文件选择器保存。
  // ==========================================================================
  var pendingMarkdown by remember { mutableStateOf<String?>(null) }
  var pendingFileName by remember { mutableStateOf("") }
  var showSaveTitleDialog by remember { mutableStateOf(false) }
  val exportSuccessMsg = stringResource(R.string.export_success)
  val exportFailedMsg = stringResource(R.string.export_failed)

  /** 用户选择保存位置后写入文件。 */
  val exportLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
      val markdown = pendingMarkdown
      if (uri == null || markdown == null) {
        return@rememberLauncherForActivityResult
      }
      scope.launch {
        val ok = ChatExportHelper.writeTextToUri(context, uri, markdown)
        snackbarHostState.showSnackbar(if (ok) exportSuccessMsg else exportFailedMsg)
        pendingMarkdown = null
      }
    }

  /** 导出当前会话：先让模型生成标题，再交给用户确认保存。 */
  val exportCurrentSession = {
    val messages = currentMessages.toList()
    if (messages.isEmpty()) {
      scope.launch { snackbarHostState.showSnackbar(exportFailedMsg) }
    } else {
      ChatExportHelper.generateTitle(
        model = selectedModel,
        messages = messages,
        onResult = { title ->
          // 推理回调在子线程，切回主线程再更新界面状态。
          scope.launch(Dispatchers.Main) {
            pendingMarkdown =
              ChatMarkdownExporter.exportSession(
                title = title,
                messages = messages,
                modelName = selectedModel.name,
                taskLabel = task.label,
                timestampMs = System.currentTimeMillis(),
              )
            pendingFileName = ChatExportHelper.sanitizeFileName(title)
            showSaveTitleDialog = true
          }
        },
      )
    }
  }

  /** 导出全部历史会话（本任务下）。 */
  val exportAllSessions = {
    if (historySessions.isEmpty()) {
      scope.launch { snackbarHostState.showSnackbar(exportFailedMsg) }
    } else {
      pendingMarkdown = ChatMarkdownExporter.exportAllSessions(historySessions)
      pendingFileName = "Encourage_${task.id}_全部会话"
      showSaveTitleDialog = true
    }
  }

  /** 标题确认对话框：展示模型生成的标题，允许用户修改后保存。 */
  if (showSaveTitleDialog) {
    SaveConversationDialog(
      initialFileName = pendingFileName,
      onDismissed = {
        showSaveTitleDialog = false
        pendingMarkdown = null
      },
      onConfirmed = { fileName ->
        showSaveTitleDialog = false
        exportLauncher.launch("$fileName.md")
      },
    )
  }
  LaunchedEffect(uiState.inProgress) {
    if (!uiState.inProgress && currentMessages.isNotEmpty()) {
      viewModel.saveSession(
        sessionId = viewModel.currentSessionId,
        messages = currentMessages,
        originalModel = selectedModel.name,
        taskId = task.id,
        context = context,
      )
    }
  }
  var navigatingUp by remember { mutableStateOf(false) }

  val handleNavigateUp = {
    navigatingUp = true
    navigateUp()

    // clean up all models.
    scope.launch(Dispatchers.Default) {
      for (model in task.models) {
        modelManagerViewModel.cleanupModel(context = context, task = task, model = model)
      }
    }
  }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(TAG, "Initializing model '${selectedModel.name}' from ChatView launched effect")
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  LaunchedEffect(sendMessageTrigger) {
    sendMessageTrigger?.let { trigger -> onSendMessage(trigger.model, trigger.messages) }
  }

  // Handle system's edge swipe.
  BackHandler {
    val modelInitializationStatus =
      modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelInitializing =
      modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
    if (drawerState.isOpen) {
      scope.launch { drawerState.close() }
    } else if (!isModelInitializing && !uiState.inProgress) {
      handleNavigateUp()
    }
  }

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(
      drawerState = drawerState,
      drawerContent = {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          ModalDrawerSheet {
            ChatHistorySideSheetContent(
              history = historySessions,
              onHistoryItemClicked = { sessionId ->
                val session = historySessions.firstOrNull { it.sessionId == sessionId }
                if (session != null) {
                  Log.d(
                    TAG,
                    "Analytics: chat_history, action=load_past_chat, capability_name=${task.id}, model_id=${selectedModel.name}, model_version=${selectedModel.version}",
                  )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.CHAT_HISTORY.id,
                    Bundle().apply {
                      putString("action", "load_past_chat")
                      putString("capability_name", task.id)
                      putString("model_id", selectedModel.name)
                      putString("model_version", selectedModel.version)
                    },
                  )

                  scope.launch {
                    viewModel.setIsResettingSession(true)
                    val messages =
                      withContext(Dispatchers.IO) { deserializeProtoMessages(session.messagesList) }
                    viewModel.clearAllMessages(selectedModel)
                    for (msg in messages) {
                      viewModel.addMessage(selectedModel, msg)
                    }
                    onResetSessionClicked(selectedModel, messages, /* clearHistory= */ false) {
                      viewModel.setIsResettingSession(false)
                    }
                    viewModel.currentSessionId = session.sessionId
                  }
                }
                scope.launch { drawerState.close() }
              },
              onHistoryItemDeleted = { sessionId ->
                viewModel.deleteSession(sessionId, context)
                if (sessionId == viewModel.currentSessionId) {
                  onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
                  viewModel.currentSessionId = UUID.randomUUID().toString()
                }
              },
              onHistoryItemsDeleteAll = {
                viewModel.clearAllSessions(context)
                onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
                viewModel.currentSessionId = UUID.randomUUID().toString()
                scope.launch { drawerState.close() }
              },
              // 【M1】导出为 Markdown。
              onExportCurrentSessionClicked = {
                Log.d(TAG, "Export current session as markdown, task=${task.id}")
                exportCurrentSession()
              },
              onExportAllSessionsClicked = {
                Log.d(TAG, "Export all sessions as markdown, task=${task.id}")
                exportAllSessions()
              },
              onNewChatClicked = {
                Log.d(
                  TAG,
                  "Analytics: chat_history, action=click_new_chat, capability_name=${task.id}, model_id=${selectedModel.name}, model_version=${selectedModel.version}",
                )
                firebaseAnalytics?.logEvent(
                  GalleryEvent.CHAT_HISTORY.id,
                  Bundle().apply {
                    putString("action", "click_new_chat")
                    putString("capability_name", task.id)
                    putString("model_id", selectedModel.name)
                    putString("model_version", selectedModel.version)
                  },
                )

                onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
                viewModel.currentSessionId = UUID.randomUUID().toString()
                scope.launch { drawerState.close() }
              },
              onDismissed = { scope.launch { drawerState.close() } },
            )
          }
        }
      },
      gesturesEnabled = drawerState.isOpen,
    ) {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Scaffold(
          modifier = modifier,
          snackbarHost = { SnackbarHost(snackbarHostState) },
          topBar = {
            ModelPageAppBar(
              task = task,
              model = selectedModel,
              modelManagerViewModel = modelManagerViewModel,
              inProgress = uiState.inProgress,
              modelPreparing = uiState.preparing,
              shouldShowHistoryButton = true,
              onConfigChanged = { old, new ->
                // Filter out config values that are not relevant to the task.
                //
                // - The "reset conversation turn count" is only valid for tiny garden task.
                val filteredOld = old.toMutableMap()
                val filteredNew = new.toMutableMap()
                if (task.id != BuiltInTaskId.LLM_TINY_GARDEN) {
                  filteredOld.remove(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label)
                  filteredNew.remove(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label)
                }
                viewModel.addConfigChangedMessage(
                  oldConfigValues = filteredOld,
                  newConfigValues = filteredNew,
                  model = selectedModel,
                )
              },
              onBackClicked = { handleNavigateUp() },
              onModelSelected = { prevModel, curModel ->
                if (prevModel.name != curModel.name) {
                  modelManagerViewModel.cleanupModel(
                    context = context,
                    task = task,
                    model = prevModel,
                  )
                }
                modelManagerViewModel.selectModel(model = curModel)
              },
              allowEditingSystemPrompt = allowEditingSystemPrompt,
              curSystemPrompt = curSystemPrompt,
              onSystemPromptChanged = onSystemPromptChanged,
              onHistoryClicked = {
                Log.d(
                  TAG,
                  "Analytics: chat_history, action=click_history_tab, capability_name=${task.id}, model_id=${selectedModel.name}, model_version=${selectedModel.version}",
                )
                firebaseAnalytics?.logEvent(
                  GalleryEvent.CHAT_HISTORY.id,
                  Bundle().apply {
                    putString("action", "click_history_tab")
                    putString("capability_name", task.id)
                    putString("model_id", selectedModel.name)
                    putString("model_version", selectedModel.version)
                  },
                )
                scope.launch { drawerState.open() }
              },
            )
          },
        ) { innerPadding ->
          Box {
            val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]

            composableBelowMessageList(selectedModel)

            Column(
              modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            ) {
              // 【M7】推理来源控制条 + 云端提示条。
              // 仅当存在云端配置（remoteProviderName 非空）时才显示控制条，
              // 让用户可在对话页直接切换本地 / 跟随配置 / 云端。
              if (remoteProviderName.isNotEmpty()) {
                InferenceSourceBar(
                  providerName = remoteProviderName,
                  inferenceSource = inferenceSource,
                  onInferenceSourceChange = onInferenceSourceChange,
                )
              }
              // 【M7】token 速度统计条：最近一次生成完成后的性能信息。
              if (generationStats != null) {
                GenerationStatsBar(stats = generationStats)
              }
              // 【N6 统一入口】能力选择器：在对话页内快速切换到其他能力（复用当前模型）。
              // 仅当外部传入 onNavigateToTask 回调时显示（如 AI 对话主入口）。
              if (onNavigateToTask != null) {
                val allTasks = modelManagerUiState.tasks
                val capabilityTasks =
                  remember(allTasks) {
                    listOfNotNull(
                      allTasks.find { it.id == BuiltInTaskId.LLM_CHAT },
                      allTasks.find { it.id == BuiltInTaskId.LLM_ASK_IMAGE },
                      allTasks.find { it.id == BuiltInTaskId.LLM_ASK_AUDIO },
                      allTasks.find { it.id == BuiltInTaskId.LLM_PROMPT_LAB },
                      allTasks.find { it.id == BuiltInTaskId.LLM_AGENT_CHAT },
                    )
                  }
                if (capabilityTasks.size > 1) {
                  Row(
                    modifier =
                      Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    for (capTask in capabilityTasks) {
                      FilterChip(
                        selected = capTask.id == task.id,
                        onClick = { onNavigateToTask(capTask) },
                        label = { Text(capTask.label, maxLines = 1) },
                      )
                    }
                  }
                }
              }
              AnimatedContent(
                targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
              ) { targetState ->
                when (targetState) {
                  // Main UI when model is downloaded.
                  true ->
                    ChatPanel(
                      modelManagerViewModel = modelManagerViewModel,
                      task = task,
                      selectedModel = selectedModel,
                      viewModel = viewModel,
                      innerPadding = innerPadding,
                      skillCount = skillCount,
                      mcpCount = mcpCount,
                      navigateUp = navigateUp,
                      onSendMessage = { model, messages -> onSendMessage(model, messages) },
                      onRunAgainClicked = onRunAgainClicked,
                      onBenchmarkClicked = onBenchmarkClicked,
                      onStreamImageMessage = onStreamImageMessage,
                      onStreamEnd = { averageFps ->
                        viewModel.addMessage(
                          model = selectedModel,
                          message =
                            ChatMessageInfo(
                              content = "Live camera session ended. Average FPS: $averageFps"
                            ),
                        )
                      },
                      onStopButtonClicked = { onStopButtonClicked(selectedModel) },
                      onImageSelected = { bitmaps, selectedBitmapIndex ->
                        selectedImageIndex = selectedBitmapIndex
                        allImageViewerImages = bitmaps
                        showImageViewer = true
                      },
                      onSkillClicked = onSkillClicked,
                      onMcpClicked = onMcpClicked,
                      onAgentClicked = onAgentClicked,
                      currentAgentName = currentAgentName,
                      modifier = Modifier.weight(1f),
                      showStopButtonInInputWhenInProgress = showStopButtonInInputWhenInProgress,
                      showImagePicker = showImagePicker,
                      showAudioPicker = showAudioPicker,
                      emptyStateComposable = emptyStateComposable,
                      // 【M1】删除消息：下标从大到小删除，避免前面的删除导致后面下标错位。
                      onDeleteMessages = { indices ->
                        for (i in indices.sortedDescending()) {
                          viewModel.removeMessageAt(selectedModel, i)
                        }
                      },
                    )
                  // Model download
                  false ->
                    ModelDownloadStatusInfoPanel(
                      model = selectedModel,
                      task = task,
                      modelManagerViewModel = modelManagerViewModel,
                    )
                }
              }
            }

            // Image viewer.
            if (showImageViewer) {
              Dialog(
                onDismissRequest = { showImageViewer = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
              ) {
                val dialogSnackbarHostState = remember { SnackbarHostState() }
                val pagerState =
                  rememberPagerState(
                    pageCount = { allImageViewerImages.size },
                    initialPage = selectedImageIndex,
                  )
                val scrollEnabled = remember { mutableStateOf(true) }
                Box(modifier = Modifier.fillMaxSize()) {
                  HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = scrollEnabled.value,
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)),
                  ) { page ->
                    allImageViewerImages[page].let { image ->
                      ZoomableImage(
                        bitmap = image.asImageBitmap(),
                        pagerState = pagerState,
                        modifier = Modifier.fillMaxSize(),
                      )
                    }
                  }

                  val curBitmap = allImageViewerImages.getOrNull(pagerState.currentPage)

                  // Top item: ArrowBack (top left).
                  Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    IconButton(onClick = { showImageViewer = false }) {
                      Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                      )
                    }
                  }

                  // Bottom items: Share, Copy, Save.
                  val copySuccessMsg = stringResource(R.string.snackbar_copy_to_clipboard_success)
                  val saveSuccessMsg = stringResource(R.string.snackbar_save_to_album_success)
                  val saveFailedMsg = stringResource(R.string.snackbar_save_to_album_failed)
                  Row(
                    modifier =
                      Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                  ) {
                    // Share button
                    IconButton(
                      onClick = {
                        curBitmap?.let { bitmap -> scope.launch { context.shareBitmap(bitmap) } }
                      },
                      modifier = Modifier.size(64.dp),
                    ) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                          Icons.Rounded.Share,
                          contentDescription = stringResource(R.string.share),
                          tint = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                          text = stringResource(R.string.share),
                          color = Color.White,
                          fontSize = 12.sp,
                          textAlign = TextAlign.Center,
                        )
                      }
                    }

                    // Copy button
                    IconButton(
                      onClick = {
                        curBitmap?.let { bitmap ->
                          scope.launch {
                            context.copyBitmapToClipboard(bitmap)
                            dialogSnackbarHostState.showSnackbar(copySuccessMsg)
                          }
                        }
                      },
                      modifier = Modifier.size(64.dp),
                    ) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                          Icons.Rounded.ContentCopy,
                          contentDescription = stringResource(R.string.copy),
                          tint = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                          text = stringResource(R.string.copy),
                          color = Color.White,
                          fontSize = 12.sp,
                          textAlign = TextAlign.Center,
                        )
                      }
                    }

                    // Save button
                    IconButton(
                      onClick = {
                        curBitmap?.let { bitmap ->
                          scope.launch {
                            val success =
                              context.saveBitmapToMediaStore(
                                bitmap,
                                "chat_image_${System.currentTimeMillis()}.png",
                              )
                            if (success) {
                              dialogSnackbarHostState.showSnackbar(saveSuccessMsg)
                            } else {
                              dialogSnackbarHostState.showSnackbar(saveFailedMsg)
                            }
                          }
                        }
                      },
                      modifier = Modifier.size(64.dp),
                    ) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                          Icons.Rounded.Download,
                          contentDescription = stringResource(R.string.save),
                          tint = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                          text = stringResource(R.string.save),
                          color = Color.White,
                          fontSize = 12.sp,
                          textAlign = TextAlign.Center,
                        )
                      }
                    }
                  }
                  SnackbarHost(
                    hostState = dialogSnackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Helper function to construct the first message when a session is restored from history.
 *
 * It prepends the entire text chat history (from User and Model) as context for the message,
 * ensuring the model understands the prior conversation when running the newly restored session.
 *
 * @param history The list of past messages for the selected model.
 * @param originalShortMessage The newly entered message to be added to the history.
 * @return A new [ChatMessageText] with history prepended, or null if there is no valid history.
 */
private fun buildFirstMessageWithHistory(
  history: List<ChatMessage>,
  originalShortMessage: ChatMessageText,
): ChatMessageText? {
  val prefix =
    history
      .mapNotNull {
        when (it) {
          is ChatMessageText ->
            if (it.side == ChatSide.USER) "User:\n${it.content}" else "Model:\n${it.content}"
          else -> null
        }
      }
      .joinToString("\n\n")

  if (prefix.isEmpty()) {
    return null
  }

  return ChatMessageText(
    content = "$prefix\n\nUser:\n${originalShortMessage.content}",
    side = originalShortMessage.side,
    latencyMs = originalShortMessage.latencyMs,
    isMarkdown = originalShortMessage.isMarkdown,
    llmBenchmarkResult = originalShortMessage.llmBenchmarkResult,
    accelerator = originalShortMessage.accelerator,
    hideSenderLabel = originalShortMessage.hideSenderLabel,
    data = originalShortMessage.data,
  )
}

/**
 * Deserializes a list of [com.encourage.app.proto.ChatMessageProto] from persistent
 * storage into the corresponding [ChatMessage] UI models.
 *
 * @param protoMessages The list of saved protobuf messages.
 * @return The list of restored UI/domain message objects.
 */
private fun deserializeProtoMessages(
  protoMessages: List<com.encourage.app.proto.ChatMessageProto>
): List<ChatMessage> {
  return protoMessages.mapNotNull { protoMsg ->
    val side =
      when (protoMsg.side) {
        com.encourage.app.proto.ChatSideProto.CHAT_SIDE_USER -> ChatSide.USER
        com.encourage.app.proto.ChatSideProto.CHAT_SIDE_MODEL -> ChatSide.AGENT
        com.encourage.app.proto.ChatSideProto.CHAT_SIDE_SYSTEM -> ChatSide.SYSTEM
        else -> ChatSide.SYSTEM
      }

    when (protoMsg.messageType) {
      "TEXT" ->
        ChatMessageText(
          content = protoMsg.content,
          side = side,
          latencyMs = protoMsg.latencyMs,
          isMarkdown = protoMsg.isMarkdown,
          accelerator = protoMsg.accelerator,
          hideSenderLabel = protoMsg.hideSenderLabel,
        )
      "THINKING" ->
        ChatMessageThinking(
          content = protoMsg.content,
          side = side,
          inProgress = protoMsg.inProgress,
          accelerator = protoMsg.accelerator,
          hideSenderLabel = protoMsg.hideSenderLabel,
        )
      "INFO" -> ChatMessageInfo(protoMsg.content)
      "WARNING" -> ChatMessageWarning(protoMsg.content)
      "ERROR" -> ChatMessageError(protoMsg.content)
      "IMAGE" -> {
        val bitmaps =
          protoMsg.imageFilePathsList.mapNotNull { path -> BitmapFactory.decodeFile(path) }
        if (bitmaps.isNotEmpty()) {
          ChatMessageImage(
            bitmaps = bitmaps,
            imageBitMaps = bitmaps.map { it.asImageBitmap() },
            side = side,
            latencyMs = protoMsg.latencyMs,
            accelerator = protoMsg.accelerator,
            hideSenderLabel = protoMsg.hideSenderLabel,
            persistedPaths = protoMsg.imageFilePathsList.toList(),
          )
        } else null
      }
      "AUDIO_CLIP" -> {
        val firstAudio = protoMsg.audioClipsList.firstOrNull()
        if (firstAudio != null) {
          try {
            ChatMessageAudioClip(
              audioData = File(firstAudio.filePath).readBytes(),
              sampleRate = firstAudio.sampleRate,
              side = side,
              latencyMs = protoMsg.latencyMs,
              persistedPath = firstAudio.filePath,
            )
          } catch (e: Exception) {
            null
          }
        } else null
      }
      else -> null
    }
  }
}
