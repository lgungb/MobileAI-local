/*
 * Encourage — 远端 OpenAI 兼容推理 Provider（M2 / 需求 B1、B3）
 *
 * 【功能说明】
 * 通过 HTTP 调用任意 **OpenAI 兼容**的大模型服务，并以流式方式把增量结果吐给界面。
 *
 * 【关键设计：复用 AgentEvent】
 * 本地推理走的是 `AgentRuntimeExecutor.executeStream()`，产出 `Flow<AgentEvent>`；
 * 本类**刻意产出同样的 Flow<AgentEvent>**，于是界面层（LlmChatViewModel）
 * 完全不需要区分结果来自本地还是远端——只需替换 Flow 的来源，
 * 「打字机效果」「思考过程折叠」「延迟统计」「错误处理」等既有逻辑全部复用。
 * 这是本次接入对现有代码侵入最小的主要原因。
 *
 * 【支持的响应格式】
 * SSE（Server-Sent Events）流，每行形如：
 *   data: {"choices":[{"delta":{"content":"你"}}]}
 *   data: [DONE]
 * 思考型模型（如 DeepSeek-R1）会在 delta 里带 `reasoning_content`，
 * 这里把它映射为 AgentEvent.StreamToken 的 thinking 字段。
 *
 * 【使用方法】
 *   remoteProvider.streamChat(config = activeConfig, turns = turns)
 *     .onEach { event -> ... }   // 与本地推理完全同构
 *     .launchIn(scope)
 *
 * 【注意事项】
 * 1. **必须联网**：本类是本应用中唯一会外发数据的推理路径。调用方有责任在
 *    界面上明确提示用户（需求"意见与风险·逻辑"一节的要求）。
 * 2. 请求体手工用 kotlinx.serialization 构造，不引入 ContentNegotiation，
 *    以避免为单一接口增加一个序列化插件依赖。
 * 3. 超时用 Kotlin 的 withTimeout 实现，覆盖「首字节等待 + 全程流式读取」，
 *    避免服务端挂起导致界面一直转圈。
 * 4. 流被取消（用户点停止 / 界面销毁）时，Ktor 会自动关闭连接释放资源。
 */

package com.encourage.app.data.api

import android.util.Log
import com.encourage.app.agent.AgentEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AGRemoteProvider"

/** SSE 流结束标记。 */
private const val SSE_DONE = "[DONE]"

/** 整个请求（含流式读取）的超时时间。 */
private const val REQUEST_TIMEOUT_MS = 120_000L

/** 连通性自检的超时时间：只等首字节，比正式对话短得多。 */
private const val CONNECTION_TEST_TIMEOUT_MS = 30_000L

/** 连通性自检时问的一句话。越短越好，只为了确认链路通。 */
private const val CONNECTION_TEST_PROMPT = "hi"

/** 连通性自检允许的最大回复长度，避免测试本身烧掉用户额度。 */
private const val CONNECTION_TEST_MAX_TOKENS = 16

/** 对话角色。 */
enum class ChatRole(val wireName: String) {
  SYSTEM("system"),
  USER("user"),
  ASSISTANT("assistant"),
}

/** 一轮对话消息。 */
data class ChatTurn(val role: ChatRole, val content: String)

/**
 * 连通性自检的结果。
 *
 * 用密封接口而不是抛异常：[ApiProviderConfig] 填错是常态而非异常，
 * 调用方（设置界面）需要的是「把原因显示给用户」，不是崩溃。
 */
sealed interface ConnectionTestResult {
  /** 成功：带往返耗时与模型回的一小段文本，让用户确认「确实是这个模型在答」。 */
  data class Success(val latencyMs: Long, val sample: String) : ConnectionTestResult

  /** 失败：带已经翻译成中文的原因。 */
  data class Failure(val message: String) : ConnectionTestResult
}

/**
 * 远端 OpenAI 兼容推理实现。
 *
 * 无状态，可安全复用；内部持有单个 Ktor HttpClient（线程安全）。
 */
@Singleton
class RemoteOpenAICompatProvider @Inject constructor() {

  private val json = Json { ignoreUnknownKeys = true }

  private val httpClient = HttpClient(Android) { expectSuccess = false }

  /**
   * 以流式方式调用远端模型。
   *
   * @param config 目标 API 配置；无效配置会直接产出一条错误事件。
   * @param turns 完整对话历史（含本轮用户输入），按时间正序。
   * @param systemPrompt 额外的系统提示词；非空时会作为首条 system 消息发送。
   * @return 与本地推理同构的 [AgentEvent] 流。
   */
  fun streamChat(
    config: ApiProviderConfig,
    turns: List<ChatTurn>,
    systemPrompt: String = "",
  ): Flow<AgentEvent> = flow {
    if (!config.isValid()) {
      emit(AgentEvent.Error("API 配置不完整：缺少服务地址或模型名"))
      return@flow
    }
    val url = config.buildChatCompletionsUrl()

    val fullText = StringBuilder()
    try {
      val requestBody = buildRequestBody(config, turns, systemPrompt)
      Log.d(TAG, "POST $url model=${config.modelId}")

      withTimeout(REQUEST_TIMEOUT_MS) {
        httpClient
          .preparePost(url) {
            header("Content-Type", "application/json")
            header("Accept", "text/event-stream")
            if (config.apiKey.isNotBlank()) {
              header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            setBody(requestBody)
          }
          .execute { response ->
            val status = response.status.value
            if (status !in 200..299) {
              // 非 2xx：把响应体读出来当错误详情，方便用户对照排查。
              val errorBody = runCatching { response.bodyAsChannel().readRemainingText() }.getOrNull()
              emit(AgentEvent.Error(describeHttpError(status, errorBody)))
              return@execute
            }

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
              val line = channel.readUTF8Line() ?: continue
              if (!line.startsWith("data:")) {
                continue
              }
              val payload = line.removePrefix("data:").trim()
              if (payload.isEmpty()) {
                continue
              }
              if (payload == SSE_DONE) {
                break
              }

              val chunk = parseChunk(payload) ?: continue

              // 服务端可能在流中返回错误对象。
              if (chunk.error != null) {
                emit(AgentEvent.Error(chunk.error))
                return@execute
              }

              if (!chunk.thinking.isNullOrEmpty()) {
                emit(AgentEvent.StreamToken(token = "", thinking = chunk.thinking, done = false))
              }
              if (!chunk.content.isNullOrEmpty()) {
                fullText.append(chunk.content)
                emit(AgentEvent.StreamToken(token = chunk.content, thinking = null, done = false))
              }
            }
          }
      }

      // 收尾：先发一个 done 的 token（界面据此计算延迟），再正常终止循环。
      emit(AgentEvent.StreamToken(token = "", thinking = null, done = true))
      emit(AgentEvent.LoopTerminated(finalResponse = fullText.toString()))
    } catch (e: Exception) {
      Log.e(TAG, "Remote inference failed", e)
      emit(AgentEvent.Error(describeException(e)))
    }
  }.flowOn(Dispatchers.IO)

  /**
   * 连通性自检：用当前配置发一条极短的非流式请求，确认「地址 + 密钥 + 模型名」三者都对。
   *
   * 与 [streamChat] 的区别：
   * - 非流式（`stream: false`），一次拿完结果，省去 SSE 解析；
   * - [maxTokens] 强行压到 [CONNECTION_TEST_MAX_TOKENS]，避免测试本身烧掉用户的额度；
   * - 同步返回 [ConnectionTestResult]，调用方（设置界面）直接展示成功 / 失败原因。
   *
   * 失败时给出的是 [describeHttpError] / [describeException] 翻译过的中文提示，
   * 用户不用去看 raw JSON 就能知道是密钥错了还是地址错了。
   *
   * @param config 待检测的配置。
   * @return 成功时带往返耗时与模型回的样例文本，失败时带可读的错误原因。
   */
  suspend fun testConnection(config: ApiProviderConfig): ConnectionTestResult {
    if (!config.isValid()) {
      return ConnectionTestResult.Failure("配置不完整：缺少服务地址或模型名")
    }
    val url = config.buildChatCompletionsUrl()
    val start = System.currentTimeMillis()
    return try {
      withTimeout(CONNECTION_TEST_TIMEOUT_MS) {
        httpClient
          .preparePost(url) {
            header("Content-Type", "application/json")
            if (config.apiKey.isNotBlank()) {
              header("Authorization", "Bearer ${config.apiKey.trim()}")
            }
            setBody(buildTestRequestBody(config))
          }
          .execute { response ->
            val status = response.status.value
            val body =
              runCatching { response.bodyAsChannel().readRemainingText(maxChars = 800) }
                .getOrNull()
            if (status !in 200..299) {
              ConnectionTestResult.Failure(describeHttpError(status, body))
            } else {
              ConnectionTestResult.Success(
                latencyMs = System.currentTimeMillis() - start,
                sample = extractMessageContent(body).orEmpty(),
              )
            }
          }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Connection test failed", e)
      ConnectionTestResult.Failure(describeException(e))
    }
  }

  /** 从非流式响应里取出 `choices[0].message.content`。取不到返回 null。 */
  private fun extractMessageContent(body: String?): String? {
    if (body.isNullOrBlank()) {
      return null
    }
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val message =
      root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        ?: return null
    return runCatching { message["content"]?.jsonPrimitive?.contentOrNull }.getOrNull()
  }

  /** 构造连通性自检的请求体：只问一句、只回十几个 token。 */
  private fun buildTestRequestBody(config: ApiProviderConfig): String {
    val messages = buildJsonArray {
        val systemPrompt = config.systemPrompt.trim()
        if (systemPrompt.isNotEmpty()) {
          add(
            buildJsonObject {
              put("role", ChatRole.SYSTEM.wireName)
              put("content", systemPrompt)
            }
          )
        }
        add(
          buildJsonObject {
            put("role", ChatRole.USER.wireName)
            put("content", CONNECTION_TEST_PROMPT)
          }
        )
      }
    return buildJsonObject {
        put("model", config.modelId.trim())
        put("stream", false)
        put("messages", messages)
        put("max_tokens", CONNECTION_TEST_MAX_TOKENS)
      }
      .toString()
  }

  /** 构造 /chat/completions 的请求体 JSON。 */
  private fun buildRequestBody(
    config: ApiProviderConfig,
    turns: List<ChatTurn>,
    systemPrompt: String,
  ): String {
    val messages = buildJsonArray {
      // 优先用配置里的系统提示词，其次用调用方传入的。
      val effectiveSystemPrompt =
        config.systemPrompt.trim().ifEmpty { systemPrompt.trim() }
      if (effectiveSystemPrompt.isNotEmpty()) {
        add(
          buildJsonObject {
            put("role", ChatRole.SYSTEM.wireName)
            put("content", effectiveSystemPrompt)
          }
        )
      }
      for (turn in turns) {
        if (turn.content.isEmpty()) {
          continue
        }
        add(
          buildJsonObject {
            put("role", turn.role.wireName)
            put("content", turn.content)
          }
        )
      }
    }

    return buildJsonObject {
        put("model", config.modelId)
        put("stream", true)
        put("messages", messages)
        // 三个采样参数：为 0 表示不发送，交由服务端策略决定。
        if (config.temperature > 0f) {
          put("temperature", config.temperature)
        }
        if (config.topP > 0f) {
          put("top_p", config.topP)
        }
        if (config.maxTokens > 0) {
          put("max_tokens", config.maxTokens)
        }
      }
      .toString()
  }

  /** 单个 SSE 数据块解析结果。 */
  private data class Chunk(
    val content: String?,
    val thinking: String?,
    val error: String?,
  )

  /** 解析一条 SSE data 载荷。解析失败返回 null（跳过，不中断整个流）。 */
  private fun parseChunk(payload: String): Chunk? {
    val root =
      runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null

    // 部分服务会在流中直接返回 {"error": {...}}。
    root["error"]?.let { errorElement ->
      val message =
        errorElement.jsonObject["message"]?.let { (it as? JsonPrimitive)?.contentOrNull }
          ?: errorElement.toString()
      return Chunk(content = null, thinking = null, error = message)
    }

    val delta =
      root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
        ?: return null

    fun read(name: String): String? {
      val element = delta[name] ?: return null
      if (element is JsonNull) {
        return null
      }
      return runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
    }

    // 正文字段：OpenAI 标准用 content。
    val content = read("content")
    // 思考字段：DeepSeek 用 reasoning_content，部分服务用 reasoning。
    val thinking = read("reasoning_content") ?: read("reasoning")
    return Chunk(content = content, thinking = thinking, error = null)
  }

  /** 把 HTTP 状态码翻译成用户看得懂的提示。 */
  private fun describeHttpError(status: Int, body: String?): String {
    val hint =
      when (status) {
        401, 403 -> "密钥无效或没有权限"
        404 -> "接口地址不存在，请检查服务地址"
        429 -> "请求过于频繁或额度不足"
        in 500..599 -> "服务端异常，请稍后重试"
        else -> "HTTP $status"
      }
    val detail = body?.trim()?.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
    return "请求失败（$hint）${detail.take(200)}"
  }

  /** 把异常翻译成用户看得懂的提示。 */
  private fun describeException(e: Exception): String {
    val raw = e.message.orEmpty()
    return when {
      raw.contains("timeout", ignoreCase = true) -> "请求超时，请检查网络或稍后重试"
      raw.contains("Unable to resolve host", ignoreCase = true) -> "无法连接服务器，请检查网络与服务地址"
      raw.contains("Cleartext", ignoreCase = true) ->
        "系统禁止明文 HTTP 传输。若使用 Ollama 等本地服务，请改用 https 或允许明文流量"
      raw.contains("CERT_", ignoreCase = true) || raw.contains("SSL", ignoreCase = true) ->
        "证书校验失败，请检查服务地址是否为 https"
      else -> "网络请求失败：$raw"
    }
  }
}

/** 读取通道剩余文本，用于非 2xx 响应时提取错误详情。 */
private suspend fun io.ktor.utils.io.ByteReadChannel.readRemainingText(maxChars: Int = 500): String {
  val builder = StringBuilder()
  while (!isClosedForRead && builder.length < maxChars) {
    val line = readUTF8Line() ?: break
    builder.append(line).append('\n')
  }
  return builder.toString()
}
