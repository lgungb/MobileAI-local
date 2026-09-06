/*
 * Encourage — 对话导出辅助工具（M1）
 *
 * 【功能说明】
 * 封装「把会话导出为 Markdown 文件」所需的周边能力：
 *   1. 会话标题生成：优先让本地模型自动生成，失败或超时时回退为启发式标题；
 *   2. 文件名清洗：去掉文件系统不允许的字符，避免保存失败；
 *   3. 写文件：把文本写入用户在系统文件选择器中选定的 URI（SAF）。
 *
 * 【为什么标题要让模型生成】
 * 需求要求「保存时让模型自动给出对话命名」。模型生成的标题更贴近对话语义
 * （例如「用 Gemma 调参的心得」），比简单截取首条消息更易检索。
 *
 * 【重要设计约束】
 * 模型自动命名直接调用 `model.runtimeHelper.runInference`，**不会**往界面消息列表里
 * 追加内容（即这条命名提问对用户在界面上不可见、也不会被持久化），代价是模型内部
 * 会话上下文会多出一轮极短的问答。命名提示词刻意做得很短，以把上下文占用降到最低。
 *
 * 【使用方法】
 *   ChatExportHelper.generateTitle(
 *     model = selectedModel,
 *     messages = currentMessages,
 *     onResult = { title -> /* 展示给用户确认或直接保存 */ },
 *   )
 *   ChatExportHelper.writeTextToUri(context, uri, markdown)
 */

package com.encourage.app.ui.common.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.encourage.app.data.Model
import com.encourage.app.runtime.runtimeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGChatExportHelper"

/** 对话导出相关的纯工具集合。 */
object ChatExportHelper {

  /** 标题最长字符数，超出会被截断，避免文件名过长。 */
  private const val MAX_TITLE_LENGTH = 30

  /**
   * 生成会话标题。
   *
   * 流程：先用模型生成；若模型未初始化、生成失败或 15 秒内没有结果，回退为启发式标题。
   *
   * @param model 当前模型（需已初始化，否则直接走启发式）。
   * @param messages 当前会话的消息列表。
   * @param onResult 标题生成完毕后的回调，保证只会被调用一次（主线程）。
   */
  fun generateTitle(model: Model, messages: List<ChatMessage>, onResult: (String) -> Unit) {
    val fallback = heuristicTitle(messages)
    if (messages.isEmpty()) {
      onResult(fallback)
      return
    }

    val prompt = buildTitlePrompt(messages)
    val buffer = StringBuilder()
    var delivered = false
    var done = false

    // 超时保护：端侧模型偶发卡住时，不能让保存流程一直等待。
    val timeoutGuard =
      object : Thread() {
        override fun run() {
          try {
            sleep(15_000)
          } catch (_: InterruptedException) {
            return
          }
          if (!done && !delivered) {
            delivered = true
            Log.w(TAG, "Title generation timed out, fallback to heuristic title.")
            onResult(fallback)
          }
        }
      }
    timeoutGuard.isDaemon = true
    timeoutGuard.start()

    try {
      model.runtimeHelper.runInference(
        model = model,
        input = prompt,
        resultListener = { partialResult: String, isDone: Boolean, _: String? ->
          buffer.append(partialResult)
          if (isDone) {
            done = true
            timeoutGuard.interrupt()
            if (!delivered) {
              delivered = true
              val generated = cleanGeneratedTitle(buffer.toString())
              // 模型可能返回空串或明显异常的内容，此时仍回退。
              onResult(if (generated.isBlank()) fallback else generated)
            }
          }
        },
        cleanUpListener = {
          // 清理阶段若仍无结果，说明推理异常结束，走回退。
          if (!delivered) {
            delivered = true
            onResult(fallback)
          }
        },
        onError = { message ->
          Log.w(TAG, "Title generation failed: $message")
          if (!delivered) {
            delivered = true
            onResult(fallback)
          }
        },
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to run title generation inference", e)
      if (!delivered) {
        delivered = true
        onResult(fallback)
      }
    }
  }

  /**
   * 构造命名提示词。
   *
   * 只取对话开头若干字符：标题通常由开头主题决定，且能显著减少模型需要处理的 token。
   */
  private fun buildTitlePrompt(messages: List<ChatMessage>): String {
    val transcript =
      messages
        .filterIsInstance<ChatMessageText>()
        .take(6)
        .joinToString("\n") { msg ->
          val who = if (msg.side == ChatSide.USER) "用户" else "助手"
          "$who：${msg.content.take(200)}"
        }
    return "请为下面这段对话起一个简短标题，只输出标题本身，不要解释、不要引号、不要标点结尾，20字以内。\n\n$transcript"
  }

  /** 清洗模型生成的标题：去掉引号、Markdown 标记、首尾空白，并限制长度。 */
  private fun cleanGeneratedTitle(raw: String): String {
    return raw
      .replace(Regex("^[#>*\\-\\s\"`']+"), "")
      .replace(Regex("[\"`']"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
      .trimEnd('。', '.', '，', ',', '：', ':', '！', '!', '？', '?')
      .take(MAX_TITLE_LENGTH)
  }

  /**
   * 启发式标题：取首条用户消息的前若干字。
   *
   * 模型不可用时的兜底方案，保证任何情况下都能保存。
   */
  fun heuristicTitle(messages: List<ChatMessage>): String {
    val firstUserMessage =
      messages.filterIsInstance<ChatMessageText>().firstOrNull { it.side == ChatSide.USER }
    val text = firstUserMessage?.content?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    return if (text.isEmpty()) "未命名会话" else text.take(MAX_TITLE_LENGTH)
  }

  /**
   * 把标题转换为安全的文件名。
   *
   * 去掉路径分隔符与系统保留字符，并统一空白为下划线。
   */
  fun sanitizeFileName(title: String): String {
    val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").replace(Regex("\\s+"), "_")
    return (if (cleaned.isBlank()) "未命名会话" else cleaned).take(40)
  }

  /**
   * 把文本写入用户选定的 URI。
   *
   * @return 写入成功返回 true；失败返回 false（调用方据此提示用户）。
   */
  suspend fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean =
    withContext(Dispatchers.IO) {
      try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
          output.write(text.toByteArray(Charsets.UTF_8))
          output.flush()
        }
          ?: return@withContext false
        true
      } catch (e: Exception) {
        Log.e(TAG, "Failed to write export file", e)
        false
      }
    }
}
