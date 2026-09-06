/*
 * Encourage — 外部 API 配置数据模型与服务预设（M2 / 需求 B1）
 *
 * 【功能说明】
 * 描述一套外部大模型 API 的连接参数，并提供常用服务商的预设模板。
 *
 * 【为什么统一用 OpenAI 兼容协议】
 * 需求要求「可配置多条、可切换」。目前绝大多数国产与海外服务都提供了
 * OpenAI 兼容端点（DeepSeek、通义千问、Kimi、智谱、硅基流动、Groq、OpenAI，
 * 以及本地的 Ollama / LM Studio），用一套协议就能覆盖全部，
 * 服务端差异只体现在 BaseURL、模型名和 Key 三个字段上。
 * 这样只需写一个 Provider 实现，而不是每个厂商写一个。
 *
 * 【预设模板的商业意义】
 * 预设列表本身就是分发位：用户只需粘贴 Key 即可用上某个服务，
 * 后续可在此处追加推荐位、返佣链接位，无需改动其他代码。
 *
 * 【使用方法】
 *   // 从预设创建一套配置
 *   val config = ApiPresets.DEEPSEEK.createConfig(apiKey = "sk-xxx")
 *
 *   // 与 proto 互转（持久化用）
 *   val proto = config.toProto()
 *   val config = ApiProviderConfig.fromProto(proto)
 *
 * 【注意事项】
 * 1. temperature / topP / maxTokens 为 0 时表示「不发送该字段，用服务端默认值」，
 *    这样默认配置不会与服务端策略打架。
 * 2. baseUrl 存的是**不含末尾斜杠**的地址，拼路径时统一用 buildChatCompletionsUrl()，
 *    避免 https://x.com/v1/ + /chat/completions 出现双斜杠。
 * 3. http（非 https）地址在 Android 9+ 默认被禁止明文传输，Ollama 等本地服务
 *    需要用户设备允许明文流量，已在文档与界面提示中说明。
 */

package com.encourage.app.data.api

import com.encourage.app.proto.ApiProviderConfig as ApiProviderConfigProto
import java.util.UUID

/**
 * 一套外部 API 配置。
 *
 * @property id 稳定唯一 id，用于激活切换与删除，重命名不影响。
 * @property name 用户可读名称，例如「DeepSeek 官方」。
 * @property baseUrl 服务根地址，例如 `https://api.deepseek.com/v1`（不含末尾斜杠）。
 * @property apiKey 鉴权密钥，可为空（Ollama 等本地服务不需要）。
 * @property modelId 模型标识，例如 `deepseek-chat`。
 * @property temperature 采样温度，0 表示不发送、用服务端默认。
 * @property topP 核采样，0 表示不发送、用服务端默认。
 * @property maxTokens 最大输出 token 数，0 表示不发送、用服务端默认。
 * @property systemPrompt 随请求发送的系统提示词，可为空。
 * @property enabled 是否启用；停用的配置不参与推理路由。
 */
data class ApiProviderConfig(
  val id: String = UUID.randomUUID().toString(),
  val name: String = "",
  val baseUrl: String = "",
  val apiKey: String = "",
  val modelId: String = "",
  val temperature: Float = 0f,
  val topP: Float = 0f,
  val maxTokens: Int = 0,
  val systemPrompt: String = "",
  val enabled: Boolean = true,
) {

  /**
   * 拼接出聊天补全接口的完整地址。
   *
   * 兼容两种填写习惯：
   * - BaseURL 已带 `/v1`：`https://api.deepseek.com/v1` → `.../v1/chat/completions`
   * - BaseURL 不带版本：`https://api.deepseek.com` → `.../v1/chat/completions`
   */
  fun buildChatCompletionsUrl(): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) {
      return ""
    }
    val withVersion =
      if (trimmed.endsWith("/v1", ignoreCase = true)) {
        trimmed
      } else {
        "$trimmed/v1"
      }
    return "$withVersion/chat/completions"
  }

  /** 配置是否可用（缺 BaseURL 或模型名的一律不可用）。 */
  fun isValid(): Boolean =
    baseUrl.trim().isNotEmpty() && modelId.trim().isNotEmpty()

  /**
   * 给用户看的短标签，用于聊天气泡上的「加速器 / 来源」角标。
   *
   * 优先用用户自己起的名字，其次退回模型 ID，两者都为空时给一个兜底值。
   */
  fun displayLabel(): String {
    val custom = name.trim()
    if (custom.isNotEmpty()) {
      return custom
    }
    val model = modelId.trim()
    if (model.isNotEmpty()) {
      return model
    }
    return "云端"
  }

  /** 转成 proto 以便写入 DataStore。 */
  fun toProto(): ApiProviderConfigProto =
    ApiProviderConfigProto.newBuilder()
      .setId(id)
      .setName(name)
      .setBaseUrl(baseUrl)
      .setApiKey(apiKey)
      .setModelId(modelId)
      .setTemperature(temperature)
      .setTopP(topP)
      .setMaxTokens(maxTokens)
      .setSystemPrompt(systemPrompt)
      .setEnabled(enabled)
      .build()

  companion object {
    /** 从 proto 还原配置。 */
    fun fromProto(proto: ApiProviderConfigProto): ApiProviderConfig =
      ApiProviderConfig(
        id = proto.id,
        name = proto.name,
        baseUrl = proto.baseUrl,
        apiKey = proto.apiKey,
        modelId = proto.modelId,
        temperature = proto.temperature,
        topP = proto.topP,
        maxTokens = proto.maxTokens,
        systemPrompt = proto.systemPrompt,
        enabled = proto.enabled,
      )
  }
}

/**
 * 服务商预设模板。
 *
 * 用户从预设创建时只需填 Key，其余字段自动带出。
 *
 * @property displayName 展示名（品牌名，不做翻译）。
 * @property baseUrl 该服务的 OpenAI 兼容根地址。
 * @property defaultModelId 建议的模型标识。
 * @property keyHint 申请 Key 的地址，界面上作为提示展示。
 */
data class ApiPreset(
  val displayName: String,
  val baseUrl: String,
  val defaultModelId: String,
  val keyHint: String = "",
) {
  /** 用该预设创建一套配置（只差 Key）。 */
  fun createConfig(apiKey: String = ""): ApiProviderConfig =
    ApiProviderConfig(
      name = displayName,
      baseUrl = baseUrl,
      apiKey = apiKey,
      modelId = defaultModelId,
    )
}

/**
 * 内置推荐服务预设列表。
 *
 * 新增服务商只需在这里加一行，界面与路由逻辑都无需改动。
 * 排序按国内用户常用程度：DeepSeek、通义、Kimi、智谱、硅基流动、OpenAI、Ollama。
 */
object ApiPresets {

  val DEEPSEEK =
    ApiPreset(
      displayName = "DeepSeek",
      baseUrl = "https://api.deepseek.com/v1",
      defaultModelId = "deepseek-chat",
      keyHint = "platform.deepseek.com",
    )

  val QWEN =
    ApiPreset(
      displayName = "通义千问",
      baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
      defaultModelId = "qwen-plus",
      keyHint = "bailian.console.aliyun.com",
    )

  val KIMI =
    ApiPreset(
      displayName = "Kimi",
      baseUrl = "https://api.moonshot.cn/v1",
      defaultModelId = "moonshot-v1-8k",
      keyHint = "platform.moonshot.cn",
    )

  val GLM =
    ApiPreset(
      displayName = "智谱 GLM",
      baseUrl = "https://open.bigmodel.cn/api/paas/v4",
      defaultModelId = "glm-4-flash",
      keyHint = "open.bigmodel.cn",
    )

  val SILICONFLOW =
    ApiPreset(
      displayName = "硅基流动",
      baseUrl = "https://api.siliconflow.cn/v1",
      defaultModelId = "Qwen/Qwen2.5-7B-Instruct",
      keyHint = "cloud.siliconflow.cn",
    )

  val OPENAI =
    ApiPreset(
      displayName = "OpenAI",
      baseUrl = "https://api.openai.com/v1",
      defaultModelId = "gpt-4o-mini",
      keyHint = "platform.openai.com",
    )

  val OLLAMA =
    ApiPreset(
      displayName = "Ollama（本机）",
      baseUrl = "http://localhost:11434/v1",
      defaultModelId = "qwen2.5:7b",
      keyHint = "本机 11434 端口，需允许明文流量",
    )

  /** 全部预设，按常用程度排序。 */
  val ALL: List<ApiPreset> = listOf(DEEPSEEK, QWEN, KIMI, GLM, SILICONFLOW, OPENAI, OLLAMA)
}
