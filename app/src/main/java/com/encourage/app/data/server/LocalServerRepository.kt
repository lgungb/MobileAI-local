/*
 * Encourage — 本地 API Server 配置仓库（M4 / 需求 B2）
 *
 * 【功能说明】
 * 本地 API 服务（OpenAI 兼容端点）的配置读写：开关、端口、鉴权 Token、网卡绑定。
 * 底层是 DataStore<Settings>（settings.proto 的 LocalServerConfig）。
 *
 * 【设计要点】
 * 1. 默认端口 8010（避开常用 8000/8080 冲突，也避开 Android 的保留端口段）。
 * 2. 默认仅绑定回环（127.0.0.1）：本机应用自用，零风险。
 *    需要局域网调用时才手动打开「允许局域网访问」。
 * 3. Token 为空 = 不鉴权；非空时所有请求必须带 Authorization: Bearer <token>。
 * 4. 本仓库只管理配置；服务的启停由 LocalApiServer（M4-2）监听本配置的 Flow 自动完成。
 */

package com.encourage.app.data.server

import androidx.datastore.core.DataStore
import com.encourage.app.proto.LocalServerConfig
import com.encourage.app.proto.Settings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 本地 API 服务的默认端口。 */
const val DEFAULT_SERVER_PORT = 8010

/** 最小可用端口（1024，普通 App 无法绑定 1024 以下的特权端口）。 */
const val MIN_SERVER_PORT = 1024

/** 本地 API 服务配置的领域模型。 */
data class LocalServerSettings(
  val enabled: Boolean = false,
  val port: Int = DEFAULT_SERVER_PORT,
  val token: String = "",
  val bindAllInterfaces: Boolean = false,
  /**
   * 对外提供的模型（锁定的模型，值为 [com.encourage.app.data.Model.name]）。
   *
   * 空 = 自动：取已下载 LLM 模型列表里的第一个。
   * 非空 = 服务只对外提供这一个模型，请求体里 model 名不一致会被拒绝（404 model_not_found）。
   */
  val modelName: String = "",
) {
  // 端口合法性：1024~65535 之间才有效，否则回退默认端口（避开特权端口，Android 上 <1024 绑定会 Permission denied）。
  val effectivePort: Int get() = if (port in MIN_SERVER_PORT..65535) port else DEFAULT_SERVER_PORT
}

/** 本地 API 服务配置的读写仓库（DataStore<Settings>）。 */
@Singleton
class LocalServerRepository
@Inject
constructor(private val settingsDataStore: DataStore<Settings>) {

  /** 服务配置的响应式流。 */
  val settings: Flow<LocalServerSettings> =
    settingsDataStore.data.map { proto -> proto.localServer.toDomain() }

  /** 更新配置。 */
  suspend fun update(transform: (LocalServerSettings) -> LocalServerSettings) {
    settingsDataStore.updateData { proto ->
      val current = proto.localServer.toDomain()
      val next = transform(current)
      val builder =
        LocalServerConfig.newBuilder()
          .setEnabled(next.enabled)
          .setPort(next.effectivePort)
          .setToken(next.token)
          .setBindAllInterfaces(next.bindAllInterfaces)
          // 【N10】锁定模型：空串表示「自动」，proto 里也存空串，不会与「未设置」混淆。
          .setModelName(next.modelName)
      proto.toBuilder().setLocalServer(builder).build()
    }
  }
}

/**
 * proto → 领域模型的唯一映射。
 *
 * 【为什么抽出来】原先 `settings` 流和 `update()` 各写一遍同样的 4 个字段映射，
 * 加字段时漏改一处就会出现「界面改了、服务读到的还是旧值」的幽灵 bug。
 */
private fun LocalServerConfig.toDomain(): LocalServerSettings =
  LocalServerSettings(
    enabled = enabled,
    port = if (port > 0) port else DEFAULT_SERVER_PORT,
    token = token,
    bindAllInterfaces = bindAllInterfaces,
    modelName = modelName,
  )
