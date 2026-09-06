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

/** 本地 API 服务配置的领域模型。 */
data class LocalServerSettings(
  val enabled: Boolean = false,
  val port: Int = DEFAULT_SERVER_PORT,
  val token: String = "",
  val bindAllInterfaces: Boolean = false,
) {
  val effectivePort: Int get() = if (port in 1..65535) port else DEFAULT_SERVER_PORT
}

/** 本地 API 服务配置的读写仓库（DataStore<Settings>）。 */
@Singleton
class LocalServerRepository
@Inject
constructor(private val settingsDataStore: DataStore<Settings>) {

  /** 服务配置的响应式流。 */
  val settings: Flow<LocalServerSettings> =
    settingsDataStore.data.map { proto ->
      val cfg = proto.localServer
      LocalServerSettings(
        enabled = cfg.enabled,
        port = if (cfg.port > 0) cfg.port else DEFAULT_SERVER_PORT,
        token = cfg.token,
        bindAllInterfaces = cfg.bindAllInterfaces,
      )
    }

  /** 更新配置。 */
  suspend fun update(transform: (LocalServerSettings) -> LocalServerSettings) {
    settingsDataStore.updateData { proto ->
      val current =
        LocalServerSettings(
          enabled = proto.localServer.enabled,
          port = if (proto.localServer.port > 0) proto.localServer.port else DEFAULT_SERVER_PORT,
          token = proto.localServer.token,
          bindAllInterfaces = proto.localServer.bindAllInterfaces,
        )
      val next = transform(current)
      val builder =
        LocalServerConfig.newBuilder()
          .setEnabled(next.enabled)
          .setPort(next.effectivePort)
          .setToken(next.token)
          .setBindAllInterfaces(next.bindAllInterfaces)
      proto.toBuilder().setLocalServer(builder).build()
    }
  }
}
