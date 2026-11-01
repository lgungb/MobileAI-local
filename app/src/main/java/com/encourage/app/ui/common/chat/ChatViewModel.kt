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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.common.processLlmResponse
import com.encourage.app.data.ConfigKeys
import com.encourage.app.data.Model
import com.encourage.app.data.conversation.SessionFileStore
import com.encourage.app.proto.AudioMessageProto
import com.encourage.app.proto.ChatMessageProto
import com.encourage.app.proto.ChatSessionProto
import com.encourage.app.proto.ChatSideProto
import com.encourage.app.proto.UserData
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGChatViewModel"

data class ChatUiState(
  /** Indicates whether the runtime is currently processing a message. */
  val inProgress: Boolean = false,

  /** Indicates whether the session is being reset. */
  val isResettingSession: Boolean = false,

  /**
   * Indicates whether the model is preparing (before outputting any result and after initializing).
   */
  val preparing: Boolean = false,

  /** A map of model names to lists of chat messages. */
  val messagesByModel: Map<String, MutableList<ChatMessage>> = mapOf(),

  /** A map of model names to the currently streaming chat message. */
  val streamingMessagesByModel: Map<String, ChatMessage> = mapOf(),

)

/** ViewModel responsible for managing the chat UI state and handling chat-related operations. */
abstract class ChatViewModel(
  val userDataDataStore: DataStore<UserData>? = null,
  /**
   * 【P1b】会话文件存储。非空时**它就是会话的权威数据源**：
   * 列表读内存索引、详情读单文件、保存只写单文件；
   * `UserData.chat_sessions` 只作为回退快照，不再写入。
   *
   * 可空是为了让测试/无持久化的子类能直接构造；为 null 时退回旧的整份写入路径。
   */
  val sessionFileStore: SessionFileStore? = null,
) : ViewModel() {
  var currentSessionId: String = UUID.randomUUID().toString()

  private val _uiState = MutableStateFlow(createUiState())
  val uiState = _uiState.asStateFlow()

  init {
    // 索引只在内存里用，必须先把 index.tsv 读进来，否则列表会一直是空的。
    sessionFileStore?.let { store ->
      viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.ensureIndexLoaded() }
          .onFailure { Log.e(TAG, "Failed to load session index.", it) }
      }
    }
  }

  /**
   * 会话历史列表。
   *
   * 【P1b】已切换为「读文件层内存索引」：每条只是**元数据**（id/标题/时间/归属），
   * 不含消息正文。列表滚动不再需要反序列化任何 `.pb`；点开某条时调用
   * [readSessionContent] 取全文。
   *
   * [sessionFileStore] 为空（测试场景）时保留旧的「整份 UserData」来源。
   */
  val historySessions: StateFlow<List<ChatSessionProto>> =
    sessionFileStore
      ?.let { store ->
        store.indexState
          .map { entries -> entries.map(store::toLiteProto) }
          .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
          )
      }
      ?: userDataDataStore
        ?.data
        ?.map { userData -> userData.chatSessionsList.sortedByDescending { it.timestampMs } }
        ?.stateIn(
          scope = viewModelScope,
          started = SharingStarted.WhileSubscribed(5000),
          initialValue = emptyList(),
        )
      ?: MutableStateFlow(emptyList())

  /**
   * 读取单条会话的完整内容（含消息）。
   *
   * 【P1b】优先读文件；文件不存在（新装/未迁移）时回退到旧字段，保证不丢历史。
   */
  suspend fun readSessionContent(sessionId: String): ChatSessionProto? {
    sessionFileStore?.readSession(sessionId)?.let {
      return it
    }
    return runCatching {
        userDataDataStore?.data?.first()?.chatSessionsList?.firstOrNull {
          it.sessionId == sessionId
        }
      }
      .getOrNull()
  }

  /**
   * 读取全部会话的完整内容（导出 Markdown / 备份用）。
   *
   * 【P1b】优先用文件层；文件层为空时回退旧字段。
   */
  suspend fun readAllSessionContents(): List<ChatSessionProto> {
    val fromFiles = sessionFileStore?.readAllSessions().orEmpty()
    if (fromFiles.isNotEmpty()) return fromFiles
    return runCatching { userDataDataStore?.data?.first()?.chatSessionsList.orEmpty() }
      .getOrDefault(emptyList())
  }

  fun addMessage(model: Model, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    newMessagesByModel[model.name] = newMessages
    // Remove prompt template message if it is the current last message.
    if (newMessages.size > 0 && newMessages.last().type == ChatMessageType.PROMPT_TEMPLATES) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessages.add(message)
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun insertMessageAfter(model: Model, anchorMessage: ChatMessage, messageToAdd: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    newMessagesByModel[model.name] = newMessages
    // Find the index of the anchor message
    val anchorIndex = newMessages.indexOf(anchorMessage)
    if (anchorIndex != -1) {
      // Insert the new message after the anchor message
      newMessages.add(anchorIndex + 1, messageToAdd)
    }
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeMessageAt(model: Model, index: Int) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList()
    if (newMessages != null) {
      newMessagesByModel[model.name] = newMessages
      if (index >= 0 && index < newMessages.size) {
        newMessages.removeAt(index)
      }
    }
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun removeLastMessage(model: Model) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      newMessages.removeAt(newMessages.size - 1)
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun clearAllMessages(model: Model) {
    _uiState.update { state ->
      state.copy(
        messagesByModel = state.messagesByModel + (model.name to mutableListOf()),
      )
    }
  }

  fun getLastMessage(model: Model): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull()
  }

  fun getLastMessageWithType(model: Model, type: ChatMessageType): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull { it.type == type }
  }

  fun getLastMessageWithTypeAndSide(
    model: Model,
    type: ChatMessageType,
    side: ChatSide,
  ): ChatMessage? {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).lastOrNull {
      it.type == type && it.side == side
    }
  }

  fun updateLastThinkingMessageContentIncrementally(model: Model, partialContent: String) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageThinking) {
        val newContent = processLlmResponse(response = "${lastMessage.content}${partialContent}")
        val newLastMessage =
          ChatMessageThinking(
            content = newContent,
            inProgress = lastMessage.inProgress,
            side = lastMessage.side,
            hideSenderLabel = lastMessage.hideSenderLabel,
            accelerator = lastMessage.accelerator,
          )
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(newLastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun updateLastTextMessageContentIncrementally(
    model: Model,
    partialContent: String,
    latencyMs: Float,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        val newContent = processLlmResponse(response = "${lastMessage.content}${partialContent}")
        val newLastMessage =
          ChatMessageText(
            content = newContent,
            side = lastMessage.side,
            latencyMs = latencyMs,
            accelerator = lastMessage.accelerator,
            hideSenderLabel = lastMessage.hideSenderLabel,
          )
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(newLastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun updateLastTextMessageLlmBenchmarkResult(
    model: Model,
    llmBenchmarkResult: ChatMessageBenchmarkLlmResult,
  ) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        lastMessage.llmBenchmarkResult = llmBenchmarkResult
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(lastMessage)
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  /** 【N8 问题1】把本轮生成的 token 数写到最后一条 AI 文本消息，供消息下方展示。 */
  fun updateLastTextMessageTokenCount(model: Model, tokenCount: Int) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastMessage = newMessages.last()
      if (lastMessage is ChatMessageText) {
        val updated =
          ChatMessageText(
            content = lastMessage.content,
            side = lastMessage.side,
            latencyMs = lastMessage.latencyMs,
            accelerator = lastMessage.accelerator,
            hideSenderLabel = lastMessage.hideSenderLabel,
            isMarkdown = lastMessage.isMarkdown,
            llmBenchmarkResult = lastMessage.llmBenchmarkResult,
            data = lastMessage.data,
            tokenCount = tokenCount,
          )
        newMessages.removeAt(newMessages.size - 1)
        newMessages.add(updated)
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun replaceLastMessage(model: Model, message: ChatMessage, type: ChatMessageType) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.size > 0) {
      val index = newMessages.indexOfLast { it.type == type }
      if (index >= 0) {
        newMessages[index] = message
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun replaceMessage(model: Model, index: Int, message: ChatMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (index >= 0 && index < newMessages.size) {
      newMessages[index] = message
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun updateStreamingMessage(model: Model, message: ChatMessage) {
    val newStreamingMessagesByModel = _uiState.value.streamingMessagesByModel.toMutableMap()
    newStreamingMessagesByModel[model.name] = message
    _uiState.update { it.copy(streamingMessagesByModel = newStreamingMessagesByModel) }
  }

  fun updateCollapsableProgressPanelMessage(
    model: Model,
    title: String,
    inProgress: Boolean,
    doneIcon: ImageVector,
    addItemTitle: String,
    addItemDescription: String,
    customData: Any? = null,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()

    val createNewCollapsableMessage = {
      ChatMessageCollapsableProgressPanel(
        title = title,
        inProgress = inProgress,
        doneIcon = doneIcon,
        items =
          if (addItemTitle.isNotEmpty()) {
            listOf(ProgressPanelItem(title = addItemTitle, description = addItemDescription))
          } else {
            listOf()
          },
        accelerator = accelerator,
        customData = customData,
      )
    }

    if (newMessages.isNotEmpty() && newMessages.last() is ChatMessageLoading) {
      newMessages.removeAt(newMessages.size - 1)
      newMessages.add(createNewCollapsableMessage())
    } else {
      val lastProgressPanelMessage =
        getLastMessageWithType(model = model, type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL)
      val lastProgressPanelMessageIndex = newMessages.indexOf(lastProgressPanelMessage)
      val lastUserTextMessage =
        getLastMessageWithTypeAndSide(
          model = model,
          type = ChatMessageType.TEXT,
          side = ChatSide.USER,
        )
      val lastUserTextMessageIndex = newMessages.indexOf(lastUserTextMessage)

      // If the last user text message is after the last progress panel message, insert the new
      // collapsable message after the last user text message.
      if (
        lastProgressPanelMessage != null &&
          lastUserTextMessage != null &&
          lastUserTextMessageIndex > lastProgressPanelMessageIndex
      ) {
        newMessages.add(lastUserTextMessageIndex + 1, createNewCollapsableMessage())
      }
      // If the last progress panel message is a collapsable progress panel, update it.
      else if (
        lastProgressPanelMessage != null &&
          lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
      ) {
        val updatedMessage =
          ChatMessageCollapsableProgressPanel(
            title = title,
            accelerator = accelerator,
            inProgress = inProgress,
            doneIcon = doneIcon,
            items =
              lastProgressPanelMessage.items +
                if (addItemTitle.isNotEmpty()) {
                  listOf(ProgressPanelItem(title = addItemTitle, description = addItemDescription))
                } else {
                  listOf()
                },
            customData = lastProgressPanelMessage.customData,
            logMessages = lastProgressPanelMessage.logMessages,
          )
        newMessages[lastProgressPanelMessageIndex] = updatedMessage
      } else {
        // If none of the above conditions match (for example, the chat history for the
        // current model is empty after a model switch during skill execution),
        // simply append a new collapsable progress panel to show the running skill status.
        newMessages.add(createNewCollapsableMessage())
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun addLogMessageToLastCollapsableProgressPanel(model: Model, logMessage: LogMessage) {
    val newMessagesByModel = _uiState.value.messagesByModel.toMutableMap()
    val newMessages = newMessagesByModel[model.name]?.toMutableList() ?: mutableListOf()
    if (newMessages.isNotEmpty()) {
      val lastCollapsableIndex = newMessages.indexOfLast {
        it is ChatMessageCollapsableProgressPanel
      }
      if (lastCollapsableIndex != -1) {
        val lastMessage = newMessages[lastCollapsableIndex] as ChatMessageCollapsableProgressPanel
        val newLogMessages = lastMessage.logMessages + logMessage
        val updatedMessage =
          ChatMessageCollapsableProgressPanel(
            title = lastMessage.title,
            inProgress = lastMessage.inProgress,
            accelerator = lastMessage.accelerator,
            doneIcon = lastMessage.doneIcon,
            items = lastMessage.items,
            logMessages = newLogMessages,
            customData = lastMessage.customData,
          )
        newMessages[lastCollapsableIndex] = updatedMessage
      }
    }
    newMessagesByModel[model.name] = newMessages
    _uiState.update { it.copy(messagesByModel = newMessagesByModel) }
  }

  fun setInProgress(inProgress: Boolean) {
    _uiState.update { it.copy(inProgress = inProgress) }
  }

  fun setIsResettingSession(isResettingSession: Boolean) {
    _uiState.update { it.copy(isResettingSession = isResettingSession) }
  }

  fun setPreparing(preparing: Boolean) {
    _uiState.update { it.copy(preparing = preparing) }
  }

  fun addConfigChangedMessage(
    oldConfigValues: Map<String, Any>,
    newConfigValues: Map<String, Any>,
    model: Model,
  ) {
    Log.d(TAG, "Adding config changed message. Old: ${oldConfigValues}, new: $newConfigValues")
    val message =
      ChatMessageConfigValuesChange(
        model = model,
        oldValues = oldConfigValues,
        newValues = newConfigValues,
      )
    addMessage(message = message, model = model)
  }

  fun getMessageIndex(model: Model, message: ChatMessage): Int {
    return (_uiState.value.messagesByModel[model.name] ?: listOf()).indexOf(message)
  }

  private fun createUiState(): ChatUiState {
    return ChatUiState()
  }

  /**
   * Saves the current chat session to the data store.
   *
   * Extracts the first text message to use as the title, converts message models to protos, and
   * updates the persistent storage.
   *
   * @param sessionId Unique identifier for the session.
   * @param messages List of messages to save.
   * @param originalModel The model active when the session was created.
   * @param taskId The task associated with this session.
   * @param profileId The owning conversation profile id; empty means the session is not grouped
   *   under any profile (e.g. legacy data). When set, the owning profile's session list, last
   *   message preview and last-used timestamp are updated in the same atomic write.
   */
  fun saveSession(
    sessionId: String,
    messages: List<ChatMessage>,
    originalModel: String,
    taskId: String,
    profileId: String = "",
    context: Context? = null,
  ) {
    val messagesSnapshot = messages.toList()
    viewModelScope.launch(Dispatchers.IO) {
      val firstTextMessage =
        messagesSnapshot.filterIsInstance<ChatMessageText>().firstOrNull()?.content
      val title =
        firstTextMessage?.take(30)?.let { if (it.length == 30) "$it..." else it }
          ?: "New Chat Session"
      val now = System.currentTimeMillis()
      val lastMessagePreview =
        messagesSnapshot.filterIsInstance<ChatMessageText>().lastOrNull()?.content.orEmpty().take(50)

      val protoMessages = messagesSnapshot.mapNotNull { msg ->
        val builder = ChatMessageProto.newBuilder()
        when (msg) {
          is ChatMessageText -> {
            builder
              .setMessageType("TEXT")
              .setContent(msg.content)
              .setSide(mapChatSide(msg.side))
              .setLatencyMs(msg.latencyMs)
              .setAccelerator(msg.accelerator)
              .setHideSenderLabel(msg.hideSenderLabel)
              .setIsMarkdown(msg.isMarkdown)
          }
          is ChatMessageThinking -> {
            builder
              .setMessageType("THINKING")
              .setContent(msg.content)
              .setSide(mapChatSide(msg.side))
              .setInProgress(msg.inProgress)
              .setAccelerator(msg.accelerator)
              .setHideSenderLabel(msg.hideSenderLabel)
          }
          is ChatMessageInfo -> {
            builder.setMessageType("INFO").setContent(msg.content).setSide(mapChatSide(msg.side))
          }
          is ChatMessageWarning -> {
            builder.setMessageType("WARNING").setContent(msg.content).setSide(mapChatSide(msg.side))
          }
          is ChatMessageError -> {
            builder.setMessageType("ERROR").setContent(msg.content).setSide(mapChatSide(msg.side))
          }
          is ChatMessageImage -> {
            builder
              .setMessageType("IMAGE")
              .setSide(mapChatSide(msg.side))
              .setLatencyMs(msg.latencyMs)
            synchronized(msg) {
              val cachedPaths = msg.persistedPaths
              if (cachedPaths != null) {
                builder.addAllImageFilePaths(cachedPaths)
              } else if (context != null) {
                msg.persistedPaths = buildList {
                  msg.bitmaps.forEachIndexed { index, bitmap ->
                    val fileName = "img_${sessionId}_${System.currentTimeMillis()}_$index.png"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { fos ->
                      bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    add(file.absolutePath)
                    builder.addImageFilePaths(file.absolutePath)
                  }
                }
              }
            }
          }
          is ChatMessageAudioClip -> {
            builder
              .setMessageType("AUDIO_CLIP")
              .setSide(mapChatSide(msg.side))
              .setLatencyMs(msg.latencyMs)
            synchronized(msg) {
              val cachedPath = msg.persistedPath
              if (cachedPath != null) {
                val audioProto =
                  AudioMessageProto.newBuilder()
                    .setFilePath(cachedPath)
                    .setSampleRate(msg.sampleRate)
                    .build()
                builder.addAudioClips(audioProto)
              } else if (context != null) {
                val fileName = "audio_${sessionId}_${System.currentTimeMillis()}.pcm"
                val file = File(context.cacheDir, fileName)
                FileOutputStream(file).use { fos -> fos.write(msg.audioData) }
                msg.persistedPath = file.absolutePath
                val audioProto =
                  AudioMessageProto.newBuilder()
                    .setFilePath(file.absolutePath)
                    .setSampleRate(msg.sampleRate)
                    .build()
                builder.addAudioClips(audioProto)
              }
            }
          }
          else -> return@mapNotNull null
        }
        builder.build()
      }

      val sessionProto =
        ChatSessionProto.newBuilder()
          .setSessionId(sessionId)
          .setTitle(title)
          .setTimestampMs(now)
          .setOriginalModel(originalModel)
          .setTaskId(taskId)
          .setProfileId(profileId)
          .addAllMessages(protoMessages)
          .build()

      // 【P1b】会话归属记录的冗余字段（会话 id 列表 / 最后消息摘要 / 最后使用时间）。
      // 新旧两条写入路径都要更新它——会话列表页读的是 profile，不是 session。
      fun withUpdatedProfile(userData: UserData): UserData {
        if (profileId.isEmpty()) return userData
        val updatedProfiles =
          userData.conversationProfilesList.map { proto ->
            if (proto.id == profileId) {
              val newSessionIds = listOf(sessionId) + proto.sessionIdsList.filter { it != sessionId }
              proto
                .toBuilder()
                .clearSessionIds()
                .addAllSessionIds(newSessionIds)
                .setLastMessagePreview(lastMessagePreview)
                .setLastUsedAtMs(now)
                .build()
            } else {
              proto
            }
          }
        return userData
          .toBuilder()
          .clearConversationProfiles()
          .addAllConversationProfiles(updatedProfiles)
          .build()
      }

      // 【P1b】主路径：只把这**一条**会话写进独立文件，写盘成本 O(单会话)。
      // 旧字段 UserData.chat_sessions 不再更新（它已降级为回退快照）。
      val written = sessionFileStore?.writeSession(sessionProto) ?: false
      if (sessionFileStore != null && written) {
        if (profileId.isNotEmpty()) {
          userDataDataStore?.updateData { userData -> withUpdatedProfile(userData) }
        }
        return@launch
      }

      // 兜底：未接入文件层、或文件写失败 → 退回「整份重写旧字段」。
      // 慢但不会丢数据；正常情况下走不到这里。
      if (sessionFileStore != null) {
        Log.w(TAG, "Session file write failed; falling back to legacy full rewrite.")
      }
      userDataDataStore?.updateData { userData ->
        val currentSessions = userData.chatSessionsList.toMutableList()
        currentSessions.removeAll { it.sessionId == sessionId }
        currentSessions.add(sessionProto)
        withUpdatedProfile(
          userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
        )
      }
    }
  }

  /**
   * Deletes a chat session from persistent storage by its ID.
   *
   * @param sessionId The ID of the session to delete.
   */
  fun deleteSession(sessionId: String, context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      deleteSessionCacheFiles(sessionIds = listOf(sessionId), context = context)
      // 【P1b】文件层删掉；旧字段也一并删，否则回退快照里会留下已删会话。
      runCatching { sessionFileStore?.deleteSession(sessionId) }
        .onFailure { Log.e(TAG, "Failed to delete session file '$sessionId'.", it) }
      userDataDataStore?.updateData { userData ->
        val currentSessions = userData.chatSessionsList.filter { it.sessionId != sessionId }
        userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
      }
    }
  }

  /**
   * Deletes all chat sessions owned by the given conversation profile, including their cached
   * image/audio files. Sessions are linked to a profile via `ChatSessionProto.profile_id`.
   *
   * This is the cascade half of "delete a conversation profile": it removes the sessions and their
   * media, while the profile record itself is removed by
   * [com.encourage.app.data.conversation.ConversationProfileRepository.deleteProfile].
   *
   * @param profileId The owning conversation profile id. Empty id is a no-op.
   * @param context Optional context used to clean up cached media files.
   */
  fun deleteSessionsOfProfile(profileId: String, context: Context? = null) {
    if (profileId.isEmpty()) return
    val dataStore = userDataDataStore ?: return
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // 【P1b】id 从两处合并取：文件索引（切路径后新增的）+ 旧字段（迁移前的老会话）。
        val fromIndex =
          runCatching {
              sessionFileStore
                ?.listSessions()
                ?.filter { it.profileId == profileId }
                ?.map { it.sessionId }
                .orEmpty()
            }
            .getOrDefault(emptyList())
        val fromLegacy =
          dataStore.data.first().chatSessionsList.filter { it.profileId == profileId }.map {
            it.sessionId
          }
        val sessionIds = (fromIndex + fromLegacy).toSet()

        deleteSessionCacheFiles(sessionIds = sessionIds, context = context)
        sessionIds.forEach { id ->
          runCatching { sessionFileStore?.deleteSession(id) }
            .onFailure { Log.e(TAG, "Failed to delete session file '$id'.", it) }
        }
        dataStore.updateData { userData ->
          val currentSessions = userData.chatSessionsList.filter { it.profileId != profileId }
          userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
        }
      } catch (e: Exception) {
        Log.e(TAG, "deleteSessionsOfProfile failed for profileId=$profileId", e)
      }
    }
  }

  /**
   * Deletes the cached media files (`img_<sessionId>_*` / `audio_<sessionId>_*`) in `cacheDir` for
   * the given [sessionIds].
   */
  private fun deleteSessionCacheFiles(sessionIds: Collection<String>, context: Context?) {
    if (context == null || sessionIds.isEmpty()) return
    val prefixes = sessionIds.flatMap { listOf("img_${it}_", "audio_${it}_") }
    val files = context.cacheDir.listFiles() ?: return
    files.forEach { file ->
      if (prefixes.any { file.name.startsWith(it) }) {
        file.delete()
      }
    }
  }

  /** Clears all saved chat sessions from persistent storage. */
  fun clearAllSessions(context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      if (context != null) {
        val files = context.cacheDir.listFiles()
        files?.forEach { file ->
          if (file.name.startsWith("img_") || file.name.startsWith("audio_")) {
            file.delete()
          }
        }
      }
      // 【P1b】文件层全清。逐条删而不是删目录，保证索引同步更新（列表会立刻变空）。
      runCatching {
          sessionFileStore?.listSessions()?.forEach { entry ->
            sessionFileStore.deleteSession(entry.sessionId)
          }
        }
        .onFailure { Log.e(TAG, "Failed to clear session files.", it) }
      userDataDataStore?.updateData { userData -> userData.toBuilder().clearChatSessions().build() }
    }
  }

  /** Maps the domain [ChatSide] enum to its corresponding proto representation. */
  private fun mapChatSide(side: ChatSide): ChatSideProto {
    return when (side) {
      ChatSide.USER -> ChatSideProto.CHAT_SIDE_USER
      ChatSide.AGENT -> ChatSideProto.CHAT_SIDE_MODEL
      ChatSide.SYSTEM -> ChatSideProto.CHAT_SIDE_SYSTEM
    }
  }
}
