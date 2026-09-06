/*
 * Encourage — 本地 API Server（M4 / 需求 B2）
 *
 * 【功能说明】
 * 在手机上内嵌 HTTP 服务（Ktor embedded CIO），暴露 OpenAI 兼容的
 * POST /v1/chat/completions 端点，供局域网内其他设备 / 软件调用本机端侧模型。
 *
 * 【协议支持（第一版）】
 * - POST /v1/chat/completions：OpenAI 请求体（model / messages / stream / temperature /
 *   max_tokens / top_p），返回 OpenAI 格式响应；stream=true 时 SSE 流式返回。
 * - GET  /v1/models：列出当前可用的本地模型（兼容 OpenAI SDK 的模型发现）。
 * - Bearer Token 鉴权：token 非空时校验 Authorization 头，不匹配返回 401。
 * - CORS：允许跨域调用（供浏览器 / Web 工具使用）。
 *
 * 【推理桥接】
 * 本类不直接持有 Model 实例（模型生命周期归 ModelManagerViewModel）。
 * 通过 [modelProvider] 注入「拿当前模型」的提供者（由界面层在组合期挂载），
 * 请求到来时调用提供者获取模型 → AgentRuntimeExecutor.initialize → executeStream。
 * 未挂载或模型未就绪时返回明确的错误信息。
 *
 * 【生命周期】
 * - [start] / [stop]：由 [LocalServerService] 监听配置自动启停；
 * - 端口 / Token / 网卡变化时重新 start（服务重启）。
 */

package com.encourage.app.data.server

import android.content.Context
import android.util.Log
import com.encourage.app.agent.AgentEvent
import com.encourage.app.agent.AgentExecutionContext
import com.encourage.app.agent.AgentRequest
import com.encourage.app.agent.AgentRuntimeConfig
import com.encourage.app.agent.AgentRuntimeExecutor
import com.encourage.app.data.Model
import com.encourage.app.skills.SkillManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.write
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AGLocalApiServer"

/** 解析后的 OpenAI 聊天请求。 */
data class ChatCompletionRequest(
  val model: String? = null,
  val messages: List<ChatMessageIn> = emptyList(),
  val stream: Boolean = false,
  val temperature: Float? = null,
  val maxTokens: Int? = null,
  val topP: Float? = null,
  val system: String? = null,
)

/** 一条请求消息。 */
data class ChatMessageIn(val role: String, val content: String)

/** 本地 API 服务：Ktor embedded HTTP 服务器。 */
@Singleton
class LocalApiServer
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val executor: AgentRuntimeExecutor,
  private val skillManager: SkillManager,
) {
  /** 由界面层挂载：返回当前应使用的本地模型（含下载状态检查）。 */
  @Volatile var modelProvider: (() -> Model?)? = null

  private var server: EmbeddedServer<*, *>? = null

  @Volatile var isRunning: Boolean = false
    private set

  /** 当前生效端口（供设置页显示状态）。 */
  @Volatile var currentPort: Int = DEFAULT_SERVER_PORT
    private set

  private val json = Json { ignoreUnknownKeys = true }

  /** 启动服务（先停旧实例再起新实例，配置变更即重启）。 */
  fun start(settings: LocalServerSettings) {
    stop()
    val port = settings.effectivePort
    val host = if (settings.bindAllInterfaces) "0.0.0.0" else "127.0.0.1"
    try {
      server =
        embeddedServer(factory = CIO, host = host, port = port) {
          install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
          }
          routing {
            get("/v1/models") { handleModelsList(call) }
            get("/v1/skills") { handleSkillsList(call, settings) }
            get("/v1/skills/{name}") { handleSkillDetail(call, settings, call.parameters["name"]) }
            post("/v1/chat/completions") { handleChatCompletions(call, settings) }
            get("/") {
              call.respondText(
                "Encourage Local API Server is running.\n" +
                  "OpenAI-compatible endpoint: POST /v1/chat/completions\n" +
                  "Capabilities: GET /v1/models, GET /v1/skills, GET /v1/skills/{name}",
                ContentType.Text.Plain,
              )
            }
          }
        }
      server?.start(wait = false)
      isRunning = true
      currentPort = port
      Log.i(TAG, "Local API server started on $host:$port")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start local API server", e)
      isRunning = false
    }
  }

  /** 停止服务。 */
  fun stop() {
    try {
      server?.stop(100, 500)
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping server", e)
    }
    server = null
    isRunning = false
  }

  // ---------------------------------------------------------------------------------------------
  // 端点实现

  private suspend fun handleModelsList(call: ApplicationCall) {
    val modelId = modelProvider?.invoke()?.name ?: "unknown"
    val payload =
      buildJsonObject {
        put(
          "data",
          buildJsonArray {
            add(
              buildJsonObject {
                put("id", modelId)
                put("object", "model")
                put("owned_by", "encourage-local")
              }
            )
          },
        )
      }
    call.respondText(payload.toString(), ContentType.Application.Json)
  }

  /**
   * 【M5-2】列出已启用的 skill（F2：外部 AI 调用本地能力的第一步——能力发现）。
   * 返回 [{name, description, built_in}]。
   */
  private suspend fun handleSkillsList(call: ApplicationCall, settings: LocalServerSettings) {
    if (settings.token.isNotEmpty() && !isAuthorized(call, settings.token)) {
      call.respondText(
        """{"error":{"message":"Invalid API key.","type":"invalid_request_error","code":"invalid_api_key"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
      )
      return
    }
    ensureSkillsLoaded()
    val payload =
      buildJsonArray {
        for (skill in skillManager.skills.value.filter { it.selected }) {
          add(
            buildJsonObject {
              put("name", skill.name)
              put("description", skill.description)
              put("built_in", skill.builtIn)
            }
          )
        }
      }
    call.respondText(payload.toString(), ContentType.Application.Json)
  }

  /**
   * 【M5-2】返回单个 skill 的完整指令内容，外部 AI 取回后可按指令执行。
   */
  private suspend fun handleSkillDetail(
    call: ApplicationCall,
    settings: LocalServerSettings,
    name: String?,
  ) {
    if (settings.token.isNotEmpty() && !isAuthorized(call, settings.token)) {
      call.respondText(
        """{"error":{"message":"Invalid API key.","type":"invalid_request_error","code":"invalid_api_key"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
      )
      return
    }
    if (name.isNullOrEmpty()) {
      call.respondText(
        """{"error":{"message":"skill name is required","type":"invalid_request_error"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    ensureSkillsLoaded()
    val skill = skillManager.skills.value.firstOrNull { it.name == name && it.selected }
    if (skill == null) {
      call.respondText(
        """{"error":{"message":"Skill '$name' not found or not enabled.","type":"invalid_request_error"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.NotFound,
      )
      return
    }
    val payload =
      buildJsonObject {
        put("name", skill.name)
        put("description", skill.description)
        put("instructions", skill.instructions)
        put("built_in", skill.builtIn)
      }
    call.respondText(payload.toString(), ContentType.Application.Json)
  }

  /** 确保 skill 索引已加载（首个 HTTP 请求触发，之后复用）。 */
  private suspend fun ensureSkillsLoaded() {
    withContext(Dispatchers.IO) {
      if (!skillManager.skillLoaded) {
        skillManager.loadSkills(SkillManager.DEFAULT_DISABLED_SKILLS)
      }
    }
  }

  private suspend fun handleChatCompletions(call: ApplicationCall, settings: LocalServerSettings) {
    // 鉴权。
    if (settings.token.isNotEmpty() && !isAuthorized(call, settings.token)) {
      call.respondText(
        """{"error":{"message":"Invalid API key. Check your Authorization: Bearer token.","type":"invalid_request_error","code":"invalid_api_key"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
      )
      return
    }

    val request = parseRequest(call.receiveText())
    if (request.messages.isEmpty()) {
      call.respondText(
        """{"error":{"message":"messages is required","type":"invalid_request_error"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
      )
      return
    }

    val model = modelProvider?.invoke()
    if (model == null) {
      call.respondText(
        """{"error":{"message":"No local model available. Open the app and ensure a model is downloaded, then retry.","type":"server_error"}}""",
        ContentType.Application.Json,
        status = HttpStatusCode.ServiceUnavailable,
      )
      return
    }

    val query = buildQuery(request)
    if (request.stream) {
      streamCompletion(call, model, query)
    } else {
      nonStreamCompletion(call, model, query)
    }
  }

  private fun isAuthorized(call: ApplicationCall, token: String): Boolean {
    val header = call.request.headers[HttpHeaders.Authorization] ?: return false
    return header == "Bearer $token"
  }

  /** 解析 OpenAI 格式请求体（容错：坏 JSON 返回空 messages → 触发 400）。 */
  private fun parseRequest(body: String): ChatCompletionRequest {
    return try {
      val root = json.parseToJsonElement(body).jsonObject
      ChatCompletionRequest(
        model = root["model"]?.jsonPrimitive?.contentOrNull,
        messages =
          (root["messages"] as? JsonArray)
            ?.mapNotNull { elem ->
              val obj = elem.jsonObject
              val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
              val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
              ChatMessageIn(role = role, content = content)
            }
            ?: emptyList(),
        stream = root["stream"]?.jsonPrimitive?.contentOrNull == "true",
        temperature = root["temperature"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        maxTokens = root["max_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        topP = root["top_p"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
        system = root["system"]?.jsonPrimitive?.contentOrNull,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse chat completion request", e)
      ChatCompletionRequest()
    }
  }

  /** 把消息列表拼成模型输入（system 前置 + 最近 20 轮）。 */
  private fun buildQuery(request: ChatCompletionRequest): String {
    val sb = StringBuilder()
    request.system?.let { sb.append("[System]\n$it\n\n") }
    for (m in request.messages.takeLast(20)) {
      when (m.role) {
        "system" -> sb.append("[System]\n${m.content}\n\n")
        "user" -> sb.append("[User]\n${m.content}\n\n")
        "assistant" -> sb.append("[Assistant]\n${m.content}\n\n")
        else -> sb.append("${m.content}\n\n")
      }
    }
    sb.append("[Assistant]\n")
    return sb.toString()
  }

  /** 非流式：收集全部 token 后一次性返回。 */
  private suspend fun nonStreamCompletion(call: ApplicationCall, model: Model, query: String) {
    val fullText = withContext(Dispatchers.Default) { runInference(model, query) }
    val payload =
      buildJsonObject {
        put("id", "chatcmpl-encourage-local")
        put("object", "chat.completion")
        put(
          "choices",
          buildJsonArray {
            add(
              buildJsonObject {
                put(
                  "message",
                  buildJsonObject {
                    put("role", "assistant")
                    put("content", fullText)
                  },
                )
                put("finish_reason", "stop")
                put("index", 0)
              }
            )
          },
        )
        put("created", System.currentTimeMillis() / 1000)
        put("model", model.name)
      }
    call.respondText(payload.toString(), ContentType.Application.Json)
  }

  /** 流式：SSE 逐 token 输出，OpenAI 风格 chunk。 */
  private suspend fun streamCompletion(call: ApplicationCall, model: Model, query: String) {
    call.respondTextWriter(ContentType.parse("text/event-stream")) {
      val inference = withContext(Dispatchers.Default) { runInference(model, query) }
      // 按 token 边界（空格）切分，模拟流式输出。
      val tokens = splitTokens(inference)
      for (token in tokens) {
        write("data: " + buildChunk(token) + "\n\n")
        flush()
      }
      write("data: [DONE]\n\n")
      flush()
    }
  }

  private fun splitTokens(text: String): List<String> {
    if (text.isEmpty()) return listOf("")
    val regex = Regex("\\s+")
    val parts = regex.split(text)
    return parts
  }

  private fun buildChunk(delta: String): String =
    buildJsonObject {
      put("id", "chatcmpl-encourage-local")
      put("object", "chat.completion.chunk")
      put(
        "choices",
        buildJsonArray {
          add(
            buildJsonObject {
              put(
                "delta",
                buildJsonObject {
                  put("role", "assistant")
                  put("content", delta)
                },
              )
              put("index", 0)
              put("finish_reason", null)
            }
          )
        },
      )
    }.toString()

  /** 完整收集一次推理的全部输出。 */
  private suspend fun runInference(model: Model, query: String): String {
    val config =
      AgentRuntimeConfig(
        model = model,
        taskId = "local_api_server",
        supportImage = false,
        supportAudio = false,
        systemInstruction = null,
      )
    var errorMsg = ""
    executor.initialize(context = context.applicationContext, config = config, onDone = { msg -> errorMsg = msg })
    if (errorMsg.isNotEmpty()) {
      return "Error initializing model: $errorMsg"
    }
    val sb = StringBuilder()
    executor
      .executeStream(context = AgentExecutionContext(), request = AgentRequest(query = query))
      .collect { event ->
        if (event is AgentEvent.StreamToken) {
          sb.append(event.token)
        }
      }
    executor.cleanUp {}
    return sb.toString()
  }
}

/** 服务编排：监听配置变化自动启停（@Singleton，由 Hilt 实例化）。 */
@Singleton
class LocalServerService
@Inject
constructor(
  private val repository: LocalServerRepository,
  private val apiServer: LocalApiServer,
) {
  /** 应用启动时调用一次：订阅配置，自动启停。 */
  fun initialize(scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
      repository.settings.collect { settings ->
        if (settings.enabled) {
          apiServer.start(settings)
        } else {
          apiServer.stop()
        }
      }
    }
  }
}
