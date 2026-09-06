/*
 * Encourage — 对话 Markdown 导出器（M1）
 *
 * 【功能说明】
 * 把会话内容转换为 Markdown 文本，用于「保存当前会话」「保存全部会话」以及分享。
 * 支持两类输入：
 *   1) 内存中的实时消息列表 `List<ChatMessage>`（当前正在进行的会话）；
 *   2) 已持久化的历史会话 `ChatSessionProto`（从 DataStore 读出的历史记录）。
 *
 * 【输出格式约定】
 *   # 会话标题
 *   > 元信息：时间 / 模型 / 任务 / 加速器
 *
 *   ## 我          或  ## AI    （按发送方分节，便于阅读与二次加工）
 *   消息正文（原生 Markdown，代码块、列表等结构会被保留）
 *
 *   图片以 `![图片](路径)` 形式保留；音频以引用行标注文件路径；
 *   思考过程（THINKING）折叠为引用块，避免冲淡正文。
 *
 * 【使用方法】
 *   // 导出当前会话（含内存中的消息）
 *   val md = ChatMarkdownExporter.exportSession(
 *     title = "与 Gemma 的对话",
 *     messages = uiState.messagesByModel[model.name] ?: emptyList(),
 *     modelName = model.name,
 *     taskLabel = task.label,
 *   )
 *   // 导出全部历史会话
 *   val mdAll = ChatMarkdownExporter.exportAllSessions(sessions)
 *
 * 【扩展提示】
 * 若将来需要导出为 HTML / PDF，只需新增对应渲染器并实现相同签名，
 * 上层保存逻辑（SAF 写入）可完全复用。
 */

package com.encourage.app.data

import com.encourage.app.proto.ChatSessionProto
import com.encourage.app.proto.ChatSideProto
import com.encourage.app.ui.common.chat.ChatMessage
import com.encourage.app.ui.common.chat.ChatMessageAudioClip
import com.encourage.app.ui.common.chat.ChatMessageImage
import com.encourage.app.ui.common.chat.ChatMessageThinking
import com.encourage.app.ui.common.chat.ChatMessageWarning
import com.encourage.app.ui.common.chat.ChatMessageError
import com.encourage.app.ui.common.chat.ChatMessageInfo
import com.encourage.app.ui.common.chat.ChatMessageText
import com.encourage.app.ui.common.chat.ChatSide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话 Markdown 导出器。所有方法均为纯函数，不涉及 IO，便于单元测试。 */
object ChatMarkdownExporter {

  /** 日期时间格式：2026-08-31 01:20。 */
  private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

  /**
   * 导出单个会话（内存中的消息列表）。
   *
   * @param title 会话标题（通常由模型自动生成，见 ChatViewModel 的自动命名逻辑）。
   * @param messages 会话消息列表；空列表时只输出标题与元信息。
   * @param modelName 生成回复所用的模型名。
   * @param taskLabel 所属任务名称（如 AI Chat）。
   * @param timestampMs 会话时间戳（毫秒）；为 0 时省略时间元信息。
   * @return 可直接写入 .md 文件的完整文本。
   */
  fun exportSession(
    title: String,
    messages: List<ChatMessage>,
    modelName: String = "",
    taskLabel: String = "",
    timestampMs: Long = 0L,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("# ${title.ifBlank { "未命名会话" }}")
    sb.appendLine()

    // 元信息：以引用块呈现，不干扰正文。
    val meta = mutableListOf<String>()
    if (timestampMs > 0) {
      meta.add("时间：${DATE_FORMAT.format(Date(timestampMs))}")
    }
    if (modelName.isNotBlank()) {
      meta.add("模型：$modelName")
    }
    if (taskLabel.isNotBlank()) {
      meta.add("任务：$taskLabel")
    }
    if (meta.isNotEmpty()) {
      meta.forEach { sb.appendLine("> $it") }
      sb.appendLine()
    }

    messages.forEach { message -> sb.append(renderMessage(message)) }
    return sb.toString()
  }

  /**
   * 导出全部历史会话（按时间倒序，与历史列表一致）。
   *
   * @param sessions 历史会话列表，通常来自 ChatViewModel.historySessions。
   * @return 所有会话拼接后的 Markdown；会话之间以分隔线隔开。
   */
  fun exportAllSessions(sessions: List<ChatSessionProto>): String {
    if (sessions.isEmpty()) {
      return "# 对话记录\n\n（暂无历史会话）\n"
    }
    val sb = StringBuilder()
    sb.appendLine("# 对话记录")
    sb.appendLine()
    sb.appendLine("> 共 ${sessions.size} 个会话，按时间倒序排列")
    sb.appendLine()

    sessions.forEachIndexed { index, session ->
      if (index > 0) {
        sb.appendLine("---")
        sb.appendLine()
      }
      sb.appendLine("## ${session.title.ifBlank { "未命名会话" }}")
      sb.appendLine()
      val meta = mutableListOf<String>()
      if (session.timestampMs > 0) {
        meta.add("时间：${DATE_FORMAT.format(Date(session.timestampMs))}")
      }
      if (session.originalModel.isNotBlank()) {
        meta.add("模型：${session.originalModel}")
      }
      if (session.taskId.isNotBlank()) {
        meta.add("任务：${session.taskId}")
      }
      if (meta.isNotEmpty()) {
        meta.forEach { sb.appendLine("> $it") }
        sb.appendLine()
      }
      session.messagesList.forEach { msg ->
        val senderLabel =
          when (msg.side) {
            ChatSideProto.CHAT_SIDE_USER -> "我"
            ChatSideProto.CHAT_SIDE_MODEL -> "AI"
            else -> "系统"
          }
        when (msg.messageType) {
          "TEXT" -> {
            sb.appendLine("**$senderLabel**：")
            sb.appendLine()
            sb.appendLine(msg.content)
            sb.appendLine()
          }
          "THINKING" -> {
            sb.appendLine("> 💭 思考过程：${msg.content}")
            sb.appendLine()
          }
          "WARNING",
          "ERROR" -> {
            sb.appendLine("> ⚠️ ${msg.content}")
            sb.appendLine()
          }
          "INFO" -> {
            sb.appendLine("> ${msg.content}")
            sb.appendLine()
          }
          "IMAGE" -> {
            msg.imageFilePathsList.forEach { path ->
              sb.appendLine("**$senderLabel**：")
              sb.appendLine()
              sb.appendLine("![]($path)")
              sb.appendLine()
            }
          }
          "AUDIO_CLIP" -> {
            msg.audioClipsList.forEach { clip ->
              sb.appendLine("**$senderLabel**：> 🎤 音频：${clip.filePath}")
              sb.appendLine()
            }
          }
          else -> {
            // 其它类型（基准测试结果等）忽略，避免导出噪声。
          }
        }
      }
    }
    return sb.toString()
  }

  /**
   * 渲染单条内存消息为 Markdown 片段。
   *
   * 之所以单独抽出：消息操作栏的「保存本条」与整会话导出可共用同一渲染规则。
   */
  private fun renderMessage(message: ChatMessage): String {
    val sb = StringBuilder()
    when (message) {
      is ChatMessageText -> {
        sb.appendLine("**${senderLabelOf(message.side)}**：")
        sb.appendLine()
        sb.appendLine(message.content)
        sb.appendLine()
      }

      is ChatMessageThinking -> {
        sb.appendLine("> 💭 思考过程：${message.content}")
        sb.appendLine()
      }

      is ChatMessageWarning -> {
        sb.appendLine("> ⚠️ ${message.content}")
        sb.appendLine()
      }

      is ChatMessageError -> {
        sb.appendLine("> ⚠️ ${message.content}")
        sb.appendLine()
      }

      is ChatMessageInfo -> {
        sb.appendLine("> ${message.content}")
        sb.appendLine()
      }

      is ChatMessageImage -> {
        // 优先使用已落盘的文件路径；尚未落盘时只能标注数量。
        val paths = message.persistedPaths
        sb.appendLine("**${senderLabelOf(message.side)}**：")
        sb.appendLine()
        if (!paths.isNullOrEmpty()) {
          paths.forEach { path -> sb.appendLine("![]($path)") }
        } else {
          sb.appendLine("（${message.bitmaps.size} 张图片，未保存到本地，故未嵌入）")
        }
        sb.appendLine()
      }

      is ChatMessageAudioClip -> {
        val path = message.persistedPath
        sb.appendLine("**${senderLabelOf(message.side)}**：")
        sb.appendLine()
        sb.appendLine(
          if (path != null) {
            "> 🎤 音频：$path"
          } else {
            "> 🎤 音频（未保存到本地）：${"%.1f".format(message.getDurationInSeconds())} 秒"
          }
        )
        sb.appendLine()
      }

      else -> {
        // 加载中、基准测试、提示模板等界面性消息不导出。
      }
    }
    return sb.toString()
  }

  /** 把发送方枚举转为中文标签。 */
  private fun senderLabelOf(side: ChatSide): String =
    when (side) {
      ChatSide.USER -> "我"
      ChatSide.AGENT -> "AI"
      ChatSide.SYSTEM -> "系统"
    }
}
