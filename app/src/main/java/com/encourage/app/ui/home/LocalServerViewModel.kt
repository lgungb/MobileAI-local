/*
 * Encourage — 本地 API 服务 ViewModel（M4 / 需求 B2）
 *
 * 【功能说明】
 * 本地 API 服务设置页的状态与操作：开关、端口、Token、局域网访问。
 * 通过 LocalServerRepository 读写 DataStore；服务启停由 LocalServerService
 * 监听配置自动完成，ViewModel 只管配置。
 *
 * 【设计要点】
 * 1. 默认仅绑定回环（127.0.0.1），本机使用零风险；
 *    打开「局域网访问」才绑定 0.0.0.0（此时强烈建议设置 Token）。
 * 2. Token 留空 = 不鉴权，仅建议在可信网络使用。
 */

package com.encourage.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.data.server.LocalServerRepository
import com.encourage.app.data.server.LocalServerSettings
import com.encourage.app.data.server.LocalApiServer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 本地 API 服务设置的状态与操作入口。 */
@HiltViewModel
class LocalServerViewModel
@Inject
constructor(
  private val repository: LocalServerRepository,
  private val apiServer: LocalApiServer,
) : ViewModel() {

  /** 当前配置。 */
  val settings: StateFlow<LocalServerSettings> =
    repository.settings.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = LocalServerSettings(),
    )

  /** 服务是否正在运行（显示状态用；LocalApiServer.isRunning 是 @Volatile 字段，轮询读取）。 */
  val isRunning: StateFlow<Boolean> =
    flow {
      while (true) {
        emit(apiServer.isRunning)
        kotlinx.coroutines.delay(1_000)
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), apiServer.isRunning)

  /** 切换总开关。 */
  fun setEnabled(enabled: Boolean) {
    viewModelScope.launch { repository.update { it.copy(enabled = enabled) } }
  }

  /** 设置端口。 */
  fun setPort(port: Int) {
    viewModelScope.launch { repository.update { it.copy(port = port) } }
  }

  /** 设置 Token（空 = 不鉴权）。 */
  fun setToken(token: String) {
    viewModelScope.launch { repository.update { it.copy(token = token.trim()) } }
  }

  /** 设置是否允许局域网访问（绑定 0.0.0.0）。 */
  fun setBindAllInterfaces(bind: Boolean) {
    viewModelScope.launch { repository.update { it.copy(bindAllInterfaces = bind) } }
  }

  /** 立即重启服务（配置变更后手动生效兜底）。 */
  fun restart() {
    viewModelScope.launch {
      val s = repository.settings.first()
      if (s.enabled) apiServer.start(s) else apiServer.stop()
    }
  }
}
