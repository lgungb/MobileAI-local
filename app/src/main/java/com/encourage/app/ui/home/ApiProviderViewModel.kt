/*
 * Encourage — 外部 API 配置 ViewModel（M2 / 需求 B1）
 *
 * 【功能说明】
 * 为设置界面提供外部 API 配置的读写能力与可观察状态，界面只与它打交道，不直接碰 DataStore。
 *
 * 【状态设计】
 * 三个状态流都用 stateIn 转成 StateFlow，并配 WhileSubscribed(5000)，
 * 这样界面退出后 5 秒内重新进入（例如旋转屏幕）不会重新读盘。
 *
 * 【使用方法】
 *   val viewModel: ApiProviderViewModel = hiltViewModel()
 *   val configs by viewModel.configs.collectAsState()
 *   viewModel.setActive(config.id)
 */

package com.encourage.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.data.api.ApiProviderConfig
import com.encourage.app.data.api.ApiProviderRepository
import com.encourage.app.data.api.ConnectionTestResult
import com.encourage.app.data.api.RemoteOpenAICompatProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 外部 API 配置界面的状态与操作入口。
 *
 * 除了增删改查，还负责「测试连接」：把网络请求放在这里而不是 Composable 里，
 * 是为了让旋转屏幕、退出重进都不会丢失正在进行的测试结果。
 */
@HiltViewModel
class ApiProviderViewModel
@Inject
constructor(
  private val repository: ApiProviderRepository,
  private val remoteProvider: RemoteOpenAICompatProvider,
) : ViewModel() {

  /** 已保存的全部配置。 */
  val configs: StateFlow<List<ApiProviderConfig>> =
    repository.configs.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = emptyList(),
    )

  /** 当前生效的配置；未启用外部 API 时为 null。 */
  val activeConfig: StateFlow<ApiProviderConfig?> =
    repository.activeConfig.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  /** 是否启用外部 API 推理（false = 只用本地模型）。 */
  val useRemoteApi: StateFlow<Boolean> =
    repository.useRemoteApi.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = false,
    )

  /** 新增或更新一条配置（同 id 即更新）。 */
  fun save(config: ApiProviderConfig) {
    viewModelScope.launch { repository.save(config) }
  }

  /** 删除一条配置。 */
  fun delete(id: String) {
    viewModelScope.launch { repository.delete(id) }
  }

  /** 切换当前使用的配置；传 null 表示回到本地模型。 */
  fun setActive(id: String?) {
    viewModelScope.launch { repository.setActive(id) }
  }

  /** 打开或关闭「使用外部 API」总开关。 */
  fun setUseRemoteApi(enabled: Boolean) {
    viewModelScope.launch { repository.setUseRemoteApi(enabled) }
  }

  // ---------------------------------------------------------------------------
  // 连通性自检（M2-5a）
  // ---------------------------------------------------------------------------

  /** 正在进行自检的配置 id；null 表示当前没有自检在跑。 */
  private val _testingId = MutableStateFlow<String?>(null)
  val testingId: StateFlow<String?> = _testingId.asStateFlow()

  /** 最近一次自检结果，按配置 id 存放，关闭对话框前一直保留。 */
  private val _testResults = MutableStateFlow<Map<String, ConnectionTestResult>>(emptyMap())
  val testResults: StateFlow<Map<String, ConnectionTestResult>> = _testResults.asStateFlow()

  /** 当前自检任务，用于取消（用户不想等了直接退出对话框）。 */
  private var testJob: Job? = null

  /** 直接执行一次自检并返回结果，供还没保存的表单配置使用。 */
  suspend fun runConnectionTest(config: ApiProviderConfig): ConnectionTestResult =
    remoteProvider.testConnection(config)

  /**
   * 对已保存的配置发起一次自检，结果写入 [testResults] 供列表展示。
   *
   * 重复点击同一条时会取消上一次 —— 配置改了之后旧结果已经没有意义。
   */
  fun testConnection(config: ApiProviderConfig) {
    testJob?.cancel()
    _testingId.value = config.id
    testJob =
      viewModelScope.launch {
        val result = remoteProvider.testConnection(config)
        _testResults.value = _testResults.value + (config.id to result)
        _testingId.value = null
      }
  }

  /** 清掉某条配置的测试结果（用户改动了配置字段时调用）。 */
  fun clearTestResult(id: String) {
    _testResults.value = _testResults.value - id
  }

  override fun onCleared() {
    testJob?.cancel()
    super.onCleared()
  }
}
