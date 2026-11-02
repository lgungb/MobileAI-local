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
 * 通过 [modelCatalog] 注入「拿到全部已下载模型」的提供者（由界面层在组合期挂载），
 * 具体对外提供哪一个由设置里的**锁定模型**决定：
 * 请求到来时解析出唯一模型 → AgentRuntimeExecutor.initialize → executeStream。
 * 请求体 model 名与锁定模型不一致时返回 404 model_not_found，不做静默替换。
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "AGLocalApiServer"

/** 设置变更合并窗口：避免端口 / Token 逐字符输入时反复重启服务器。 */
private const val SETTINGS_APPLY_DEBOUNCE_MS = 400L

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
  /**
   * 由界面层挂载：返回**全部**已下载的本地 LLM 模型。
   *
   * 【N10 改造】原先是 `() -> Model?`（只会给第一个模型），现在改成返回整个目录：
   * 具体对外提供哪一个由设置里的「锁定模型」决定（[LocalServerSettings.modelName]），
   * 锁定为空时才回退到「列表第一个」，保持旧行为。
   */
  @Volatile var modelCatalog: (() -> List<Model>)? = null

  private var server: EmbeddedServer<*, *>? = null

  /**
   * 当前生效配置。
   *
   * Token / 锁定模型都在**请求到达时**读这里，所以改这两项无需重建 Ktor；
   * 只有端口、网卡变了才必须重启监听。
   */
  @Volatile private var activeSettings: LocalServerSettings = LocalServerSettings()

  /** 运行状态的响应式来源：设置页直接订阅，避免每秒轮询。 */
  private val _isRunning = MutableStateFlow(false)
  val isRunningState: StateFlow<Boolean> = _isRunning.asStateFlow()

  /** 兼容旧调用方的同步读取；与 [isRunningState] 同源，不会出现两份状态不一致。 */
  val isRunning: Boolean
    get() = _isRunning.value

  /**
   * 端侧模型不支持并发推理：同一时刻只放行一个请求。
   *
   * 否则局域网多客户端同时调用会抢占同一个模型 runtime，轻则上下文串台，
   * 重则 OOM / 闪退。抢不到许可的请求直接返回 429，由调用方重试。
   */
  private val inferencePermit = Semaphore(permits = 1)

  /** 当前生效端口（供设置页显示状态）。 */
  @Volatile var currentPort: Int = DEFAULT_SERVER_PORT
    private set

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * 应用最新配置（[LocalServerService] 的唯一入口）。
   *
   * 【为什么不一律重启】端口 / 网卡变化必须重建 Ktor；而 Token、锁定模型是在请求到达时
   * 读 [activeSettings] 的 —— 每改一次就 stop + start，除了白发一次 bind，
   * 还会把正在跑的推理连接直接掐断。所以只有「监听参数」变了才重启。
   */
  fun applySettings(settings: LocalServerSettings, forceRestart: Boolean = false) {
    val prev = activeSettings
    activeSettings = settings
    if (!settings.enabled) {
      stop()
      return
    }
    val needRestart =
      forceRestart ||
        server == null ||
        prev.effectivePort != settings.effectivePort ||
        prev.bindAllInterfaces != settings.bindAllInterfaces
    if (needRestart) {
      start(settings)
    } else {
      Log.i(TAG, "Local API server config updated in place (no restart).")
    }
  }

  /** 启动服务（先停旧实例再起新实例，配置变更即重启）。 */
  fun start(settings: LocalServerSettings) {
    activeSettings = settings
    stop()
    val port = settings.effectivePort
    val host = if (settings.bindAllInterfaces) "0.0.0.0" else "127.0.0.1"

    // 【防闪退】CIO 引擎的异步 accept 循环在 bind 失败时抛出的 SocketException
    // 发生在 Ktor 自己的后台线程（见崩溃堆栈 DefaultDispatcher-worker-6），**绕过外层 try-catch**，
    // 直接冒泡到全局崩溃处理器 → 杀进程闪退。这里先做一次**同步端口预检**：
    // 能绑上就释放（让 Ktor 去绑），绑不上（端口占用 / Android 17 权限限制 / 特权端口）
    // 就绝不启动 Ktor，仅记日志返回，保证 App 不因本地服务崩溃。
    if (!canBind(host, port)) {
      Log.e(TAG, "Local API server cannot bind $host:$port (permission denied or port in use); not started.")
      _isRunning.value = false
      return
    }

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
            get("/v1/skills") { handleSkillsList(call) }
            get("/v1/skills/{name}") { handleSkillDetail(call, call.parameters["name"]) }
            post("/v1/chat/completions") { handleChatCompletions(call) }
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
      _isRunning.value = true
      currentPort = port
      Log.i(TAG, "Local API server started on $host:$port")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start local API server", e)
      _isRunning.value = false
    }
  }

  /**
   * 同步探测某 host:port 能否绑定（bind 后立即 close，不占用端口）。
   *
   * 这是「本地 API Server 绑端口失败导致闪退」的兜底：Android 上 CIO 引擎异步 bind
   * 抛 `SocketException: Permission denied` 无法被外层 try-catch 捕获，因此在真正启动 Ktor
   * 前先用 JDK 的 [java.net.ServerSocket] 做一次同步预检。失败返回 false，调用方据此放弃启动。
   */
  private fun canBind(host: String, port: Int): Boolean {
    return try {
      java.net.ServerSocket().use { socket ->
        socket.reuseAddress = true
        // 特权端口（<1024）普通 App 无法绑定，这里直接判失败。
        if (port < 1024) {
          return false
        }
        socket.bind(java.net.InetSocketAddress(host, port))
      }
      true
    } catch (e: Exception) {
      Log.w(TAG, "Port pre-check failed for $host:$port", e)
      false
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
    _isRunning.value = false
  }

  // ---------------------------------------------------------------------------------------------
  // 端点实现

  /**
   * GET /v1/models —— 模型发现。
   *
   * 【N10】只返回**本次对外提供的那一个**模型（锁定模型 / 自动模式的第一个）。
   * 客户端拿到什么名字，就必须用这个名字请求，不存在「列表里有 A、实际跑 B」的可能。
   */
  private suspend fun handleModelsList(call: ApplicationCall) {
    if (!authorize(call)) return
    val resolved = resolveServedModel()
    if (resolved.model == null) {
      respondError(call, HttpStatusCode.ServiceUnavailable, resolved.errorMessage, "model_unavailable")
      return
    }
    val payload =
      buildJsonObject {
        put("object", "list")
        put(
          "data",
          buildJsonArray {
            add(
              buildJsonObject {
                put("id", resolved.model.name)
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
  private suspend fun handleSkillsList(call: ApplicationCall) {
    if (!authorize(call)) return
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
    name: String?,
  ) {
    if (!authorize(call)) return
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

  /**
   * POST /v1/chat/completions —— OpenAI 兼容对话补全。
   *
   * 【N10 模型锁定】本机只对外提供「设置里选定的那一个模型」：
   * - 请求体 `model` 留空 → 用锁定模型；
   * - 请求体 `model` 与锁定模型不一致 → 404 `model_not_found`，并回传唯一可用模型名。
   *
   * 这样外部客户端（DSH / OpenAI SDK / 其它软件）**不可能**再出现
   * 「界面上填的是 A，实际跑的是 B」的静默错配。
   */
  private suspend fun handleChatCompletions(call: ApplicationCall) {
    if (!authorize(call)) return

    val request = parseRequest(call.receiveText())
    if (request.messages.isEmpty()) {
      respondError(
        call,
        HttpStatusCode.BadRequest,
        "messages is required (array of {role, content}).",
        "invalid_request",
      )
      return
    }

    val resolved = resolveServedModel()
    val model = resolved.model
    if (model == null) {
      respondError(call, HttpStatusCode.ServiceUnavailable, resolved.errorMessage, "model_unavailable")
      return
    }

    val requested = request.model?.trim().orEmpty()
    if (requested.isNotEmpty() && !modelMatches(model, requested)) {
      respondError(
        call,
        HttpStatusCode.NotFound,
        "Model '$requested' is not served by this device. Only '${model.name}' is available.",
        "model_not_found",
      )
      return
    }

    // 【并发限流】端侧模型是单实例、不支持并发推理。局域网多客户端同时请求时会抢占
    // 同一个 runtime（串台 / OOM）。这里用信号量串行化，抢不到直接 429，让调用方重试。
    if (!inferencePermit.tryAcquire()) {
      respondError(
        call,
        HttpStatusCode.TooManyRequests,
        "Model is busy. Please retry later.",
        "model_busy",
      )
      return
    }

    try {
      val query = buildQuery(request)
      if (request.stream) {
        streamCompletion(call, model, query)
      } else {
        nonStreamCompletion(call, model, query)
      }
    } finally {
      // 无论正常结束、抛异常还是客户端断开，都必须归还许可，否则服务会永久卡死在 429。
      inferencePermit.release()
    }
  }

  /** 校验 Authorization；不通过时直接写 401 并返回 false（调用方 return 即可）。 */
  private suspend fun authorize(call: ApplicationCall): Boolean {
    val token = activeSettings.token.trim()
    // Token 留空 = 不鉴权（仅建议本机 / 可信局域网使用）。
    if (token.isEmpty()) return true
    if (isAuthorized(call, token)) return true
    respondError(
      call,
      HttpStatusCode.Unauthorized,
      "Invalid API key. Check your Authorization: Bearer token.",
      "invalid_api_key",
    )
    return false
  }

  private fun isAuthorized(call: ApplicationCall, token: String): Boolean {
    val header = call.request.headers[HttpHeaders.Authorization]?.trim() ?: return false
    // 前缀大小写不敏感（部分客户端写 "bearer"），Token 本体按原样比较但去掉首尾空白。
    val prefix = "bearer "
    if (!header.startsWith(prefix, ignoreCase = true)) return false
    return header.substring(prefix.length).trim() == token
  }

  /** 统一的 OpenAI 风格错误响应（客户端 SDK 都认这个结构）。 */
  private suspend fun respondError(
    call: ApplicationCall,
    status: HttpStatusCode,
    message: String,
    code: String,
  ) {
    val payload =
      buildJsonObject {
        put(
          "error",
          buildJsonObject {
            put("message", message)
            put("type", errorType(code))
            put("code", code)
          },
        )
      }
    call.respondText(payload.toString(), ContentType.Application.Json, status = status)
  }

  private fun errorType(code: String): String =
    when (code) {
      "model_busy" -> "rate_limit_error"
      "model_unavailable", "inference_failed" -> "server_error"
      else -> "invalid_request_error"
    }

  /**
   * 解析「本次对外提供哪个模型」。
   *
   * 锁定名为空 → 取目录第一个（旧行为，兼容老配置）；
   * 锁定名查不到 → 返回明确错误，而不是偷偷换成别的模型。
   */
  private fun resolveServedModel(): ServedModel {
    val catalog = modelCatalog?.invoke().orEmpty()
    if (catalog.isEmpty()) {
      return ServedModel(
        null,
        "No local model available. Open the app, download an LLM model, then retry.",
      )
    }
    val locked = activeSettings.modelName.trim()
    if (locked.isEmpty()) return ServedModel(catalog.first())
    val found = catalog.firstOrNull { modelMatches(it, locked) }
    return if (found != null) {
      ServedModel(found)
    } else {
      ServedModel(
        null,
        "The locked model '$locked' is not available (not downloaded or deleted). " +
          "Open the app's local server settings and pick an available model.",
      )
    }
  }

  /** 请求里的名字可以是模型 id，也可以是展示名。 */
  private fun modelMatches(model: Model, nameOrDisplayName: String): Boolean =
    model.name.equals(nameOrDisplayName.trim(), ignoreCase = true) ||
      model.displayName.equals(nameOrDisplayName.trim(), ignoreCase = true)

  /** 解析 OpenAI 格式请求体（容错：坏 JSON 返回空 messages → 触发 400）。 */
  private fun parseRequest(body: String): ChatCompletionRequest {
    return try {
      val root = json.parseToJsonElement(body).jsonObject
      ChatCompletionRequest(
        model = root["model"]?.jsonPrimitive?.contentOrNull,
        messages =
          (root["messages"] as? JsonArray)
            ?.mapNotNull { elem ->
              val obj = elem as? JsonObject ?: return@mapNotNull null
              val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
              ChatMessageIn(role = role, content = extractContent(obj["content"]))
            }
            ?: emptyList(),
        stream = (root["stream"]?.jsonPrimitive?.contentOrNull ?: "").equals("true", ignoreCase = true),
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

  /**
   * 抽取一条消息的正文。
   *
   * OpenAI 的 content 有两种形态：纯字符串，或 `[{type:"text", text:"..."}]` 多段数组。
   *
   * 【修 bug】原先只做了 `jsonPrimitive` 强转，客户端一传数组就抛异常 → 整个请求被当成
   * "messages is required" 400 掉。现在数组形态会把文本段拼起来，图片 / 音频段忽略
   * （端侧文本推理用不上，但不能因此拒绝整个请求）。
   */
  private fun extractContent(element: JsonElement?): String {
    val primitive = element as? JsonPrimitive
    if (primitive != null) return primitive.contentOrNull ?: ""
    val array = element as? JsonArray ?: return ""
    return array
      .mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj["text"]?.jsonPrimitive?.contentOrNull
      }
      .joinToString("\n")
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
    val result = runInference(model, query)
    if (result.error != null) {
      respondError(
        call,
        HttpStatusCode.BadGateway,
        "Local inference failed: ${result.error}",
        "inference_failed",
      )
      return
    }
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
                    put("content", result.text)
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

  /**
   * 流式：SSE **边生成边输出**。
   *
   * 【修 bug】原先是「先跑完整次推理拿到全文，再按空格切块假装流式」：
   * 首字节要等到生成结束才出现，长回答等于没有流式。现在直接消费
   * [AgentRuntimeExecutor.executeStream] 的 token 事件，生成一个写一个。
   */
  private suspend fun streamCompletion(call: ApplicationCall, model: Model, query: String) {
    call.respondTextWriter(ContentType.parse("text/event-stream")) {
      val initError = initializeEngine(model)
      if (initError.isNotEmpty()) {
        write("data: ${buildErrorChunk("Local inference failed: $initError")}\n\n")
        write("data: [DONE]\n\n")
        flush()
        return@respondTextWriter
      }
      var streamError: String? = null
      try {
        executor
          .executeStream(context = AgentExecutionContext(), request = AgentRequest(query = query))
          .collect { event ->
            when (event) {
              is AgentEvent.StreamToken -> {
                if (event.token.isNotEmpty()) {
                  write("data: ${buildChunk(event.token, model.name)}\n\n")
                  flush()
                }
              }
              is AgentEvent.Error -> {
                streamError = event.errorMessage
                write("data: ${buildErrorChunk(event.errorMessage)}\n\n")
                flush()
              }
              else -> Unit
            }
          }
        // 收尾 chunk：部分客户端（含 OpenAI 官方 SDK）依赖 finish_reason 判定正常结束。
        write("data: ${buildFinalChunk(model.name)}\n\n")
        write("data: [DONE]\n\n")
        flush()
        if (streamError != null) Log.w(TAG, "Stream finished with error: $streamError")
      } catch (e: Exception) {
        // 客户端提前断开 / 推理异常：写一条错误 chunk 后收尾，不让协程带着异常炸掉服务。
        Log.w(TAG, "Streaming inference aborted", e)
        try {
          write("data: ${buildErrorChunk(e.message ?: "unknown error")}\n\n")
          write("data: [DONE]\n\n")
          flush()
        } catch (_: Exception) {
          // 连接已断，写不进去就算了。
        }
      } finally {
        // 【修 bug】原先只在成功路径 cleanUp：初始化失败 / 中途异常会把上一个会话的上下文
        // 留给下一个请求（表现为「客户端串台」）。
        runCatching { executor.cleanUp {} }
      }
    }
  }

  private fun buildChunk(delta: String, modelName: String): String =
    buildJsonObject {
      put("id", "chatcmpl-encourage-local")
      put("object", "chat.completion.chunk")
      put("created", System.currentTimeMillis() / 1000)
      put("model", modelName)
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
              put("finish_reason", JsonNull)
            }
          )
        },
      )
    }.toString()

  /** 正常结束的收尾 chunk（delta 为空 + finish_reason=stop）。 */
  private fun buildFinalChunk(modelName: String): String =
    buildJsonObject {
      put("id", "chatcmpl-encourage-local")
      put("object", "chat.completion.chunk")
      put("created", System.currentTimeMillis() / 1000)
      put("model", modelName)
      put(
        "choices",
        buildJsonArray {
          add(
            buildJsonObject {
              put("delta", buildJsonObject {})
              put("index", 0)
              put("finish_reason", "stop")
            }
          )
        },
      )
    }.toString()

  private fun buildErrorChunk(message: String): String =
    buildJsonObject {
      put(
        "error",
        buildJsonObject {
          put("message", message)
          put("type", "server_error")
          put("code", "inference_failed")
        },
      )
    }.toString()

  /** 初始化引擎；返回空串表示成功，非空为错误描述。 */
  private suspend fun initializeEngine(model: Model): String {
    val config =
      AgentRuntimeConfig(
        model = model,
        taskId = "local_api_server",
        supportImage = false,
        supportAudio = false,
        systemInstruction = null,
      )
    var errorMsg = ""
    withContext(Dispatchers.Default) {
      executor.initialize(
        context = context.applicationContext,
        config = config,
        onDone = { msg -> errorMsg = msg },
      )
    }
    return errorMsg
  }

  /** 一次性推理：收集全部 token。 */
  private suspend fun runInference(model: Model, query: String): InferenceResult {
    val initError = initializeEngine(model)
    if (initError.isNotEmpty()) return InferenceResult("", initError)
    return try {
      val sb = StringBuilder()
      var streamError: String? = null
      executor
        .executeStream(context = AgentExecutionContext(), request = AgentRequest(query = query))
        .collect { event ->
          when (event) {
            is AgentEvent.StreamToken -> sb.append(event.token)
            is AgentEvent.Error -> streamError = event.errorMessage
            else -> Unit
          }
        }
      if (sb.isEmpty() && streamError != null) {
        InferenceResult("", streamError)
      } else {
        InferenceResult(sb.toString())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Inference failed", e)
      InferenceResult("", e.message ?: "inference failed")
    } finally {
      runCatching { executor.cleanUp {} }
    }
  }

  /** 「对外提供哪个模型」的解析结果：要么有模型，要么有一句人话错误。 */
  private data class ServedModel(val model: Model?, val errorMessage: String = "")

  /** 一次非流式推理的结果。 */
  private data class InferenceResult(val text: String, val error: String? = null)
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
  // debounce 在 kotlinx-coroutines 1.9 仍属 FlowPreview API，显式 opt-in 以消除编译警告。
  @OptIn(kotlinx.coroutines.FlowPreview::class)
  fun initialize(scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
      try {
        repository.settings
          // 【防抖】端口 / Token 在设置页是逐字符输入的，每敲一个字符 settings 就变一次。
          // 不防抖等于「每输入一个字符就 stop + 重建一次 Ktor」：端口短暂不可用、白耗资源。
          // 合并到 400ms 后只按最终配置重启一次。
          .debounce(SETTINGS_APPLY_DEBOUNCE_MS)
          .collect { settings ->
            try {
              // 【N10】统一走 applySettings：端口 / 网卡没变时只热更新配置（Token、锁定模型），
              // 不再无谓地 stop + 重建 Ktor（每次重建都会掐断正在跑的推理连接）。
              apiServer.applySettings(settings)
            } catch (e: Exception) {
              // 【防闪退】服务启停的任何异常都不应导致应用崩溃，仅记录日志。
              Log.e(TAG, "Local API server start/stop failed, ignored.", e)
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "Local API server settings collection failed, ignored.", e)
      }
    }
  }
}
