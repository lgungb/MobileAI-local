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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.Model
import com.encourage.app.data.Task
import com.encourage.app.speech.OfflineTtsEngine
import com.encourage.app.speech.VoiceEngineSnapshot
import com.encourage.app.speech.VoiceSettingsSnapshot
import com.encourage.app.speech.rememberSpeechManager
import com.encourage.app.ui.common.AudioAnimation
import com.encourage.app.ui.common.ErrorDialog
import com.encourage.app.ui.common.FloatingBanner
import com.encourage.app.ui.common.RotationalLoader
import com.encourage.app.ui.common.ScrollToBottomButton
import com.encourage.app.ui.home.VoiceSettingsViewModel
import com.encourage.app.ui.modelmanager.ModelInitializationStatusType
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.theme.customColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "AGChatPanel"
private const val SCROLL_ANIMATION_DURATION_MS = 300

/** Composable function for the main chat panel, displaying messages and handling user input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
  modelManagerViewModel: ModelManagerViewModel,
  task: Task,
  selectedModel: Model,
  viewModel: ChatViewModel,
  innerPadding: PaddingValues,
  modifier: Modifier = Modifier,
  skillCount: Int = 0,
  mcpCount: Int = 0,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, warmUpIterations: Int, benchmarkIterations: Int) -> Unit,
  navigateUp: () -> Unit,
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStreamEnd: (Int) -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onAgentClicked: () -> Unit = {},
  currentAgentName: String? = null,
  onImageSelected: (bitmaps: List<Bitmap>, selectedBitmapIndex: Int) -> Unit = { _, _ -> },
  showStopButtonInInputWhenInProgress: Boolean = false,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  emptyStateComposable: @Composable (Model) -> Unit = {},
  // 【M1】批量删除：回调参数为待删除的消息下标（升序），由调用方从 ViewModel 中移除。
  onDeleteMessages: (List<Int>) -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val messages = uiState.messagesByModel[selectedModel.name] ?: listOf()
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  // 【N6-A】系统语音不可用的提示文案。必须在 @Composable 作用域里取值（onClick 是
  // 普通 lambda，里面不能调用 stringResource），因此在这里先取出再在回调中使用。
  val ttsFailedMsg = stringResource(R.string.voice_speak_failed)
  val context = LocalContext.current
  val clipboard =
    remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
  val copyToClipboard: (String) -> Unit =
    remember(clipboard) {
      { text -> clipboard.setPrimaryClip(ClipData.newPlainText("message", text)) }
    }

  // ==========================================================================
  // 【M1】消息操作：朗读 / 删除 / 多选
  // - 朗读：使用系统 TTS（M3 升级为「系统 + 离线模型」双引擎，调用方式不变）；
  //   语速 / 音调遵循用户语音设置（M3 D3）。
  // - 删除：单条删除直接删除；长按消息进入多选模式，可一次删除多条。
  // ==========================================================================
  val voiceSettingsViewModel: VoiceSettingsViewModel = hiltViewModel()
  val voiceSettings by voiceSettingsViewModel.settings.collectAsState()
  // 【N1】离线引擎接入对话：仅当语音包已就绪且用户选择了离线引擎时才创建，
  // 否则不实例化，避免白占内存。语音包下载/导入完成后（packState→Ready）自动重建引擎。
  // 【Bug 修复】同 VoiceSettingsDialog：旧实现 remember(key)+DisposableEffect(key)
  // 的 onDispose 释放的是新引擎而非旧引擎，导致 native use-after-free 闪退。
  // 改用 DisposableEffect 统一管理创建/释放，闭包捕获局部变量 engine。
  val voicePackInstalled = voiceSettingsViewModel.isDefaultPackInstalled()
  val contextForTts = LocalContext.current
  var offlineTtsEngine by remember { mutableStateOf<OfflineTtsEngine?>(null) }
  DisposableEffect(voicePackInstalled) {
    val engine = if (voicePackInstalled) OfflineTtsEngine(contextForTts) else null
    offlineTtsEngine = engine
    onDispose {
      engine?.release()
    }
  }
  val speechManager =
    rememberSpeechManager(
      offlineTtsEngine = offlineTtsEngine,
      voiceSettingsProvider = {
        VoiceSettingsSnapshot(
          rate = voiceSettings.rate,
          pitch = voiceSettings.pitch,
          engine =
            if (voiceSettings.usesOfflineEngine) VoiceEngineSnapshot.SHERPA_ONNX
            else VoiceEngineSnapshot.SYSTEM,
          role = voiceSettings.role,
        )
      },
    )
  var selectionMode by remember { mutableStateOf(false) }
  val selectedIndices = remember { mutableStateListOf<Int>() }

  /** 退出多选模式并清空选择。 */
  val exitSelectionMode = {
    selectionMode = false
    selectedIndices.clear()
  }
  val imageCountToLastConfigChange =
    remember(messages) {
      var imageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageImage) {
          imageCount += message.bitmaps.size
        }
      }
      imageCount
    }
  val audioClipMesssageCountToLastconfigChange =
    remember(messages) {
      var audioClipMessageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageAudioClip) {
          audioClipMessageCount++
        }
      }
      audioClipMessageCount
    }

  var curMessage by remember { mutableStateOf("") } // Correct state
  val focusManager = LocalFocusManager.current

  // List state to control scrolling.
  val listState = rememberScrollState()
  val density = LocalDensity.current
  var showBenchmarkConfigsDialog by remember { mutableStateOf(false) }
  val benchmarkMessage: MutableState<ChatMessage?> = remember { mutableStateOf(null) }

  var showErrorDialog by remember { mutableStateOf(false) }
  var customErrorMessage by remember { mutableStateOf<String?>(null) }
  var showFeedbackDialog by remember { mutableStateOf(false) }
  var isPositiveFeedback by remember { mutableStateOf(true) }
  var feedbackMessageIndex by remember { mutableIntStateOf(-1) }

  var showAudioRecorder by remember { mutableStateOf(false) }
  var curAmplitude by remember { mutableIntStateOf(0) }
  var pickedImagesCount by remember { mutableIntStateOf(0) }
  var pickedAudioClipsCount by remember { mutableIntStateOf(0) }

  var showImageLimitBanner by remember { mutableStateOf(false) }

  // Stores the heights of the items in the list, indexed by the item index.
  val itemHeights = remember { mutableStateMapOf<Int, Int>() }

  // Turn messages into a derived state to trigger updates when the list is updated.
  val currentMessages by rememberUpdatedState(messages)

  // Stores the height of the viewport in pixels.
  var viewportHeightPx by remember { mutableIntStateOf(0) }

  // Stores if the list is at the scrollable area's bottom.
  //
  // It will only be updated when the state holds for at least 500ms to improve user experience.
  var isAtBottom by remember { mutableStateOf(true) }
  LaunchedEffect(listState) {
    snapshotFlow {
        // Read the raw scroll state here
        !listState.canScrollForward
      }
      .collectLatest { rawAtBottom ->
        if (!rawAtBottom) {
          delay(500)
        }
        // Update the actual state.
        isAtBottom = rawAtBottom
      }
  }

  // Stores the index of the last user message as a derived state.
  val lastUserMessageIndex by
    remember(currentMessages) {
      derivedStateOf {
        currentMessages.indexOfLast { it is ChatMessageText && it.side == ChatSide.USER }
      }
    }

  // Stores the dynamic bottom padding required to push the last user message to the top edge of the
  // view.
  val dynamicBottomPadding by remember {
    derivedStateOf {
      if (lastUserMessageIndex == -1 || viewportHeightPx == 0) return@derivedStateOf 0.dp

      // Sum the heights of the last user message and everything below it.
      //
      // If the message immediately preceding the last user message is an image or audio,
      // include it in the height calculation by starting one index earlier.
      var bottomContentHeight = 0
      var startIndex = lastUserMessageIndex
      if (startIndex > 0) {
        val prevMessage = currentMessages.getOrNull(startIndex - 1)
        if (
          prevMessage != null &&
            (prevMessage is ChatMessageImage || prevMessage is ChatMessageAudioClip)
        ) {
          startIndex -= 1
        }
      }
      for (i in startIndex until currentMessages.size) {
        bottomContentHeight += itemHeights[i] ?: 0
      }

      // The padding required to push the last user message to the top.
      val paddingPx = maxOf(0, viewportHeightPx - bottomContentHeight)
      with(density) { paddingPx.toDp() }
    }
  }

  // Nested scroll connection to handle scrolling behavior.
  val nestedScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // If downward scroll, clear the focus from any currently focused composable.
        // This is useful for dismissing software keyboards or hiding text input fields
        // when the user starts scrolling down a list.
        if (available.y > 0) {
          focusManager.clearFocus()
        }
        // Let LazyColumn handle the scroll
        return Offset.Zero
      }
    }
  }

  // Show the image limit banner for 3 seconds.
  LaunchedEffect(showImageLimitBanner) {
    if (showImageLimitBanner) {
      delay(3000) // 3 seconds
      showImageLimitBanner = false
    }
  }

  // Show the error dialog when the model initialization status is error.
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  // Scroll to the bottom when the last user message index changes (i.e. when a new user prompt is
  // sent).
  //
  // Due to the calculation of `dynamicBottomPadding`, the new user message will be positioned at
  // the top edge of the view when list scrolled all the way to the bottom.
  LaunchedEffect(lastUserMessageIndex) {
    if (lastUserMessageIndex != -1) {
      val unused = awaitFrame()
      scrollToBottom(
        listState = listState,
        animate = true,
        animationDurationMs = SCROLL_ANIMATION_DURATION_MS * 2,
      )
    }
  }

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    // Audio record animation.
    AnimatedVisibility(
      showAudioRecorder,
      enter =
        slideInVertically(
          animationSpec =
            spring(
              stiffness = Spring.StiffnessLow,
              visibilityThreshold = IntOffset.VisibilityThreshold,
            )
        ) {
          it
        } + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)),
      exit = fadeOut(),
      modifier = Modifier.graphicsLayer { alpha = 0.8f },
    ) {
      AudioAnimation(bgColor = MaterialTheme.colorScheme.surface, amplitude = curAmplitude)
    }

    Column(
      modifier = modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()
    ) {
      Box(
        contentAlignment = Alignment.BottomCenter,
        modifier =
          Modifier.weight(1f).onSizeChanged {
            // Update the viewport height when the size of the box changes.
            viewportHeightPx = it.height
          },
      ) {
        val cdChatPanel = stringResource(R.string.cd_chat_panel)
        Column(
          modifier =
            Modifier.fillMaxSize()
              .nestedScroll(nestedScrollConnection)
              .verticalScroll(state = listState)
              .semantics { contentDescription = cdChatPanel },
          verticalArrangement = Arrangement.Top,
        ) {
          messages.forEachIndexed { index, message ->
            val imageHistoryCurIndex = remember { mutableIntStateOf(0) }
            var hAlign: Alignment.Horizontal = Alignment.End
            var backgroundColor: Color = MaterialTheme.customColors.userBubbleBgColor
            var hardCornerAtLeftOrRight = false
            var extraPaddingStart = 48.dp
            var extraPaddingEnd = 0.dp
            if (message.side == ChatSide.AGENT) {
              hAlign = Alignment.Start
              backgroundColor = MaterialTheme.customColors.agentBubbleBgColor
              hardCornerAtLeftOrRight = true
              extraPaddingStart = 0.dp
              if (
                message.type !== ChatMessageType.LOADING &&
                  message.type !== ChatMessageType.WEBVIEW &&
                  message.type !== ChatMessageType.COLLAPSABLE_PROGRESS_PANEL
              ) {
                extraPaddingEnd = 48.dp
              }
              if (message.type == ChatMessageType.TEXT) {
                extraPaddingStart = 0.dp
                extraPaddingEnd = 0.dp
              }
            } else if (message.side == ChatSide.SYSTEM) {
              extraPaddingStart = 24.dp
              extraPaddingEnd = 24.dp
              if (message.type == ChatMessageType.PROMPT_TEMPLATES) {
                extraPaddingStart = 12.dp
                extraPaddingEnd = 12.dp
              }
            }
            if (message.type == ChatMessageType.IMAGE) {
              backgroundColor = Color.Transparent
            }
            val bubbleBorderRadius = dimensionResource(R.dimen.chat_bubble_corner_radius)

            // 【M1】只有文本类消息（含思考过程）支持多选与长按操作。
            val isTextMessage = message is ChatMessageText || message is ChatMessageThinking
            val isSelected = selectionMode && selectedIndices.contains(index)

            /** 切换当前消息的选中状态；若取消后无选中项则自动退出多选模式。 */
            val toggleSelected: () -> Unit = {
              if (isSelected) {
                selectedIndices.remove(index)
                if (selectedIndices.isEmpty()) {
                  selectionMode = false
                }
              } else {
                selectedIndices.add(index)
              }
            }

            Column(
              modifier =
                Modifier.fillMaxWidth()
                  // Update the height of the item in the list when the size changes.
                  //
                  // Need to put this modifier here to get the correct size that includes the
                  // paddings.
                  .onSizeChanged { size ->
                    if (itemHeights[index] != size.height) {
                      itemHeights[index] = size.height
                    }
                  }
                  .padding(
                    start = 16.dp + extraPaddingStart,
                    end = 12.dp + extraPaddingEnd,
                    top = 6.dp,
                    bottom = 6.dp,
                  )
                  // 【M1】选中高亮 + 长按进入多选、多选下点击切换选中。
                  //
                  // 说明：热区放在整条消息上（而不是只放在复选框上），
                  // 这样点气泡任意位置都能切换选中，符合常见聊天应用的交互习惯。
                  .then(
                    if (isTextMessage) {
                      Modifier.clip(RoundedCornerShape(8.dp))
                        .background(
                          if (isSelected) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                          } else {
                            Color.Transparent
                          }
                        )
                        .combinedClickable(
                          onClick = { if (selectionMode) toggleSelected() },
                          onLongClick = {
                            if (!uiState.inProgress) {
                              selectionMode = true
                              if (!selectedIndices.contains(index)) {
                                selectedIndices.add(index)
                              }
                            }
                          },
                        )
                        .padding(vertical = 2.dp)
                    } else {
                      Modifier
                    }
                  ),
              horizontalAlignment = hAlign,
            ) messageColumn@{
              // 【M1】多选模式下在消息上方展示复选框，方便批量勾选。
              if (selectionMode && isTextMessage) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Start,
                ) {
                  Checkbox(checked = isSelected, onCheckedChange = { toggleSelected() })
                }
              }

              // Sender row.
              var agentName = stringResource(task.agentNameRes)
              if (message.accelerator.isNotEmpty()) {
                agentName = "$agentName on ${message.accelerator}"
              }
              if (!message.hideSenderLabel) {
                MessageSender(
                  message = message,
                  agentName = agentName,
                  imageHistoryCurIndex = imageHistoryCurIndex.intValue,
                )
              }

              // Message body.
              when (message) {
                // Loading.
                is ChatMessageLoading -> MessageBodyLoading(message = message)

                // Info.
                is ChatMessageInfo -> MessageBodyInfo(message = message)

                // Warning
                is ChatMessageWarning -> MessageBodyWarning(message = message)

                // Error
                is ChatMessageError -> MessageBodyError(message = message)

                // Config values change.
                is ChatMessageConfigValuesChange -> MessageBodyConfigUpdate(message = message)

                // Prompt templates.
                is ChatMessagePromptTemplates ->
                  MessageBodyPromptTemplates(
                    message = message,
                    task = task,
                    onPromptClicked = { template ->
                      onSendMessage(
                        selectedModel,
                        listOf(ChatMessageText(content = template.prompt, side = ChatSide.USER)),
                      )
                    },
                  )

                // Non-system messages.
                else -> {
                  // The bubble shape around the message body.
                  var messageBubbleModifier: Modifier = Modifier
                  // No bubble shape for agent response text messages.
                  val isAgentResponseText =
                    message.type == ChatMessageType.TEXT && message.side == ChatSide.AGENT
                  if (!message.disableBubbleShape && !isAgentResponseText) {
                    // Use a rounded rectangle clip for multi-image image message.
                    if (message is ChatMessageImage && message.bitmaps.size > 1) {
                      messageBubbleModifier = messageBubbleModifier.clip(RoundedCornerShape(6.dp))
                    }
                    // For other messages, use a bubble shape to clip.
                    else {
                      messageBubbleModifier =
                        messageBubbleModifier.clip(
                          MessageBubbleShape(
                            radius = bubbleBorderRadius,
                            hardCornerAtLeftOrRight = hardCornerAtLeftOrRight,
                          )
                        )
                    }
                    messageBubbleModifier = messageBubbleModifier.background(backgroundColor)
                  }
                  Box(modifier = messageBubbleModifier) {
                    when (message) {
                      // Text
                      is ChatMessageText ->
                        MessageBodyText(
                          message = message,
                          inProgress = uiState.inProgress,
                          horizontalPadding =
                            if (isAgentResponseText) {
                              0.dp
                            } else {
                              12.dp
                            },
                          onCopyClicked = copyToClipboard,
                        )

                      // Image
                      is ChatMessageImage -> {
                        MessageBodyImage(message = message, onImageClicked = onImageSelected)
                      }

                      // Audio clip.
                      is ChatMessageAudioClip -> MessageBodyAudioClip(message = message)

                      // Benchmark result.
                      is ChatMessageBenchmarkResult -> MessageBodyBenchmark(message = message)

                      // Benchmark LLM result.
                      is ChatMessageBenchmarkLlmResult ->
                        MessageBodyBenchmarkLlm(
                          message = message,
                          modifier = Modifier.wrapContentWidth(),
                        )

                      // Webview.
                      is ChatMessageWebView -> MessageBodyWebview(message = message)

                      // Collapsable progress panel.
                      is ChatMessageCollapsableProgressPanel ->
                        MessageBodyCollapsableProgressPanel(message = message)

                      // Thinking
                      is ChatMessageThinking ->
                        MessageBodyThinking(
                          thinkingText = message.content,
                          inProgress = message.inProgress,
                          onCopyClicked = copyToClipboard,
                        )

                      else -> {}
                    }
                  }

                  if (message.side == ChatSide.AGENT) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      LatencyText(message = message)
                      if (message is ChatMessageText && !uiState.inProgress) {
                        // 【M1】AI 回复的操作区：复制 / 朗读 / 删除。
                        MessageActionIcon(
                          icon = Icons.Rounded.ContentCopy,
                          contentDescription = stringResource(R.string.copy),
                          onClick = { copyToClipboard(message.content) },
                        )
                        MessageActionIcon(
                          icon = Icons.AutoMirrored.Rounded.VolumeUp,
                          contentDescription = stringResource(R.string.speak),
                          onClick = {
                            if (speechManager.isSpeaking) {
                              speechManager.stop()
                            } else {
                              speechManager.speakWithSettings(message.content)
                              // 【N6-A】朗读请求被静默丢弃（系统 TTS 不可用 / 引擎调用失败）时，
                              // 用 Snackbar 明确提示用户，而不是「点了没反应」。
                              // lastError 是同步写入的，这里可以立刻读到本次失败原因。
                              speechManager.lastError?.let { reason ->
                                scope.launch {
                                  snackbarHostState.showSnackbar(
                                    message = "$ttsFailedMsg\n$reason",
                                    duration = SnackbarDuration.Short,
                                  )
                                }
                              }
                            }
                          },
                        )
                        MessageActionIcon(
                          icon = Icons.Rounded.Delete,
                          contentDescription = stringResource(R.string.delete),
                          onClick = { onDeleteMessages(listOf(index)) },
                        )
                      }
                    }
                  } else if (message.side == ChatSide.USER) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                      // Run again button.
                      if (selectedModel.showRunAgainButton) {
                        MessageActionButton(
                          label = stringResource(R.string.run_again),
                          icon = Icons.Rounded.Refresh,
                          onClick = { onRunAgainClicked(selectedModel, message) },
                          enabled = !uiState.inProgress,
                        )
                      }

                      // Benchmark button
                      if (selectedModel.showBenchmarkButton) {
                        MessageActionButton(
                          label = stringResource(R.string.run_benchmark),
                          icon = Icons.Outlined.Timer,
                          onClick = {
                            showBenchmarkConfigsDialog = true
                            benchmarkMessage.value = message
                          },
                          enabled = !uiState.inProgress,
                        )
                      }

                      // 【M1】用户消息的操作区：复制 / 朗读 / 删除。
                      if (message is ChatMessageText && !uiState.inProgress) {
                        MessageActionIcon(
                          icon = Icons.Rounded.ContentCopy,
                          contentDescription = stringResource(R.string.copy),
                          onClick = { copyToClipboard(message.content) },
                        )
                        MessageActionIcon(
                          icon = Icons.AutoMirrored.Rounded.VolumeUp,
                          contentDescription = stringResource(R.string.speak),
                          onClick = {
                            if (speechManager.isSpeaking) {
                              speechManager.stop()
                            } else {
                              speechManager.speakWithSettings(message.content)
                              // 【N6-A】朗读请求被静默丢弃（系统 TTS 不可用 / 引擎调用失败）时，
                              // 用 Snackbar 明确提示用户，而不是「点了没反应」。
                              // lastError 是同步写入的，这里可以立刻读到本次失败原因。
                              speechManager.lastError?.let { reason ->
                                scope.launch {
                                  snackbarHostState.showSnackbar(
                                    message = "$ttsFailedMsg\n$reason",
                                    duration = SnackbarDuration.Short,
                                  )
                                }
                              }
                            }
                          },
                        )
                        MessageActionIcon(
                          icon = Icons.Rounded.Delete,
                          contentDescription = stringResource(R.string.delete),
                          onClick = { onDeleteMessages(listOf(index)) },
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // The spacer at the bottom to push the content up so that the last user message will be
          // positioned at the top edge of the view when the list is scrolled to the bottom.
          //
          // See how `dynamicBottomPadding` is calculated above.
          Spacer(modifier = Modifier.height(dynamicBottomPadding).fillMaxWidth())
        }

        // 【M1】多选模式下的底部操作条：显示已选数量、执行批量删除、退出多选。
        if (selectionMode) {
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              stringResource(R.string.selected_count, selectedIndices.size),
              style = MaterialTheme.typography.bodyMedium,
            )
            Row {
              TextButton(onClick = { exitSelectionMode() }) {
                Text(stringResource(R.string.cancel))
              }
              TextButton(
                onClick = {
                  onDeleteMessages(selectedIndices.sorted())
                  exitSelectionMode()
                }
              ) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
              }
            }
          }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(vertical = 4.dp))

        // Show empty state.
        if (messages.isEmpty() && pickedImagesCount == 0 && pickedAudioClipsCount == 0) {
          emptyStateComposable(selectedModel)
        }
        // Loading screen when model is initialized for that first time.
        val isFirstInitializing =
          modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING &&
            modelInitializationStatus.isFirstInitialization(selectedModel)
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          AnimatedVisibility(
            isFirstInitializing,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
          ) {
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface).fillMaxSize()) {
              Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                RotationalLoader(size = 32.dp)
                Text(
                  stringResource(R.string.aichat_initializing_title),
                  style =
                    MaterialTheme.typography.headlineLarge.copy(
                      fontSize = 24.sp,
                      fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                  stringResource(R.string.aichat_initializing_content),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }

        FloatingBanner(
          visible = showImageLimitBanner,
          text = stringResource(R.string.aicore_image_limit_message),
          modifier =
            Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // "Scroll to bottom" button, only shown when the list is not at the bottom.
        Column(
          modifier =
            Modifier.align(alignment = Alignment.BottomCenter)
              .fillMaxWidth()
              .padding(bottom = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          ScrollToBottomButton(
            isAtBottom = isAtBottom,
            onClick = { scope.launch { scrollToBottom(listState, animate = true) } },
          )
        }
      }

      val modelNotSupportImageMsg = stringResource(R.string.model_not_support_image_message)
      val modelNotSupportAudioMsg = stringResource(R.string.model_not_support_audio_message)
      val imageLimitIgnoredMsg = stringResource(R.string.image_limit_ignored_message)

      // 【T04】输入区上方的能力选择器已删除（收敛到统一对话界面）。
      // 保留外层 Column 与其内的 MessageInputText，仅移除能力 Chip 栏。
      Column(
        modifier = Modifier.fillMaxWidth(),
      ) {
      MessageInputText(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        curMessage = curMessage,
        inProgress = uiState.inProgress,
        isResettingSession = uiState.isResettingSession,
        modelPreparing = uiState.preparing,
        imageCount = imageCountToLastConfigChange,
        audioClipMessageCount = audioClipMesssageCountToLastconfigChange,
        skillCount = skillCount,
        mcpCount = mcpCount,
        modelInitializing =
          modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING,
        textFieldPlaceHolderRes = task.textInputPlaceHolderRes,
        onValueChanged = { curMessage = it },
        onSendMessage = {
          onSendMessage(selectedModel, it)
          curMessage = ""
          // Hide software keyboard.
          focusManager.clearFocus()
        },
        onOpenPromptTemplatesClicked = {
          onSendMessage(
            selectedModel,
            listOf(
              ChatMessagePromptTemplates(
                templates = selectedModel.llmPromptTemplates,
                showMakeYourOwn = false,
              )
            ),
          )
        },
        onStopButtonClicked = onStopButtonClicked,
        onSetAudioRecorderVisible = { start ->
          showAudioRecorder = start
          if (!showAudioRecorder) {
            curAmplitude = 0
          }
        },
        onAmplitudeChanged = { curAmplitude = it },
        onSkillsClicked = onSkillClicked,
        onMcpClicked = onMcpClicked,
        onAgentClicked = onAgentClicked,
        currentAgentName = currentAgentName,
        showAgentPicker = task.id === BuiltInTaskId.LLM_AGENT_CHAT,
        onPickedImagesChanged = { pickedImagesCount = it.size },
        onPickedAudioClipsChanged = { pickedAudioClipsCount = it.size },
        showPromptTemplatesInMenu = false,
        showSkillsPicker = task.id === BuiltInTaskId.LLM_AGENT_CHAT,
        showMcpPicker = task.id === BuiltInTaskId.LLM_AGENT_CHAT,
        showImagePicker = showImagePicker,
        showAudioPicker = showAudioPicker,
        showStopButtonWhenInProgress = showStopButtonInInputWhenInProgress,
        onImageLimitExceeded = { showImageLimitBanner = true },
        onImagesIgnored = {
          scope.launch {
            snackbarHostState.showSnackbar(
              message = imageLimitIgnoredMsg,
              duration = SnackbarDuration.Short,
            )
          }
        },
        onModelNotSupportImage = { customErrorMessage = modelNotSupportImageMsg },
        onModelNotSupportAudio = { customErrorMessage = modelNotSupportAudioMsg },
      )
      }
    }
  }

  // Error dialog.
  if (showErrorDialog || customErrorMessage != null) {
    ErrorDialog(
      error = customErrorMessage ?: modelInitializationStatus?.error ?: "",
      onDismiss = {
        if (customErrorMessage != null) {
          customErrorMessage = null
        } else {
          showErrorDialog = false
        }
      },
    )
  }

  // Benchmark config dialog.
  if (showBenchmarkConfigsDialog) {
    BenchmarkConfigDialog(
      onDismissed = { showBenchmarkConfigsDialog = false },
      messageToBenchmark = benchmarkMessage.value,
      onBenchmarkClicked = { message, warmUpIterations, benchmarkIterations ->
        onBenchmarkClicked(selectedModel, message, warmUpIterations, benchmarkIterations)
      },
    )
  }
}

private suspend fun scrollToBottom(
  listState: ScrollState,
  animate: Boolean = false,
  animationDurationMs: Int = SCROLL_ANIMATION_DURATION_MS,
) {
  if (animate) {
    listState.animateScrollTo(
      listState.maxValue,
      animationSpec = tween(durationMillis = animationDurationMs, easing = FastOutSlowInEasing),
    )
  } else {
    listState.scrollTo(listState.maxValue)
  }
}
