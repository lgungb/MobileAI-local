/*
 * Encourage — 外部 API 配置仓库（M2 / 需求 B1）
 *
 * 【功能说明】
 * 持久化用户配置的多套外部大模型 API，并管理「当前用哪一套」「是否启用外部 API」。
 *
 * 【默认行为：本地优先】
 * `use_remote_api` 默认为 false，即**默认始终走本地端侧模型**。
 * 原因：本应用的核心卖点是「100% 端侧、隐私不出手机」，
 * 联网属于用户主动选择的增强能力，必须显式开关（需求"意见与风险·逻辑"一节明确要求）。
 * 因此 activeConfig 只有在 useRemoteApi 为 true 时才会有值。
 *
 * 【存储位置】
 * 全部落在 Settings proto（settings.pb）里：
 *   - repeated ApiProviderConfig api_providers   配置列表
 *   - string active_api_provider_id              当前激活的配置 id
 *   - bool   use_remote_api                      总开关
 *
 * 【使用方法】
 *   // 读取全部配置（随 DataStore 变化自动推送）
 *   val configs: Flow<List<ApiProviderConfig>> = repository.configs
 *
 *   // 读取当前生效的配置；未启用外部 API 时恒为 null
 *   val active: Flow<ApiProviderConfig?> = repository.activeConfig
 *
 *   // 新增或修改（同 id 即覆盖）
 *   repository.save(config)
 *
 *   // 删除；若删的正是激活项，自动关闭总开关，避免留下悬空引用
 *   repository.delete(config.id)
 *
 * 【注意事项】
 * 所有写操作都是 suspend，需在协程中调用；DataStore 更新本身是原子的。
 */

package com.encourage.app.data.api

import androidx.datastore.core.DataStore
import com.encourage.app.proto.Settings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/** 读写外部 API 配置的仓库。apiKey 落盘前经 [ApiSecretCipher] 加密（M5-3）。 */
@Singleton
class ApiProviderRepository
@Inject
constructor(
  private val settingsDataStore: DataStore<Settings>,
  private val cipher: ApiSecretCipher,
) {

  /** 全部配置（按保存顺序），apiKey 已解密为可用明文。 */
  val configs: Flow<List<ApiProviderConfig>> =
    settingsDataStore.data.map { settings ->
      settings.apiProvidersList.map { decryptConfig(ApiProviderConfig.fromProto(it)) }
    }

  /** 是否启用外部 API 推理（false = 只用本地模型）。 */
  val useRemoteApi: Flow<Boolean> = settingsDataStore.data.map { it.useRemoteApi }

  /**
   * 当前生效的配置。
   *
   * 仅在总开关打开、且激活 id 能匹配到一条启用的配置时才非空；
   * 这样调用方只需判空即可决定走本地还是远端，不必再分别读两个字段。
   */
  val activeConfig: Flow<ApiProviderConfig?> =
    settingsDataStore.data.map { settings ->
      if (!settings.useRemoteApi) {
        return@map null
      }
      val activeId = settings.activeApiProviderId
      if (activeId.isEmpty()) {
        return@map null
      }
      settings.apiProvidersList
        .firstOrNull { it.id == activeId && it.enabled }
        ?.let { decryptConfig(ApiProviderConfig.fromProto(it)) }
    }

  /** 按 id 取一条配置（一次性读取，不订阅）。 */
  suspend fun getById(id: String): ApiProviderConfig? = configs.map { list -> list.firstOrNull { it.id == id } }.firstOrNull()

  /**
   * 新增或更新一条配置（以 id 为键）。
   *
   * @return 保存后的配置原样返回，便于调用方拿到自动生成的 id。
   */
  suspend fun save(config: ApiProviderConfig): ApiProviderConfig {
    settingsDataStore.updateData { settings ->
      val builder = settings.toBuilder()
      val index = builder.apiProvidersList.indexOfFirst { it.id == config.id }
      // 【M5-3】apiKey 落盘前加密（Keystore AES-GCM，密文带 enc1: 前缀）。
      val proto =
        config.toProto().toBuilder().setApiKey(cipher.encrypt(config.apiKey)).build()
      if (index >= 0) {
        builder.setApiProviders(index, proto)
      } else {
        builder.addApiProviders(proto)
      }
      builder.build()
    }
    return config
  }

  /** 删除一条配置。若删的正是当前激活项，自动关闭总开关。 */
  suspend fun delete(id: String) {
    settingsDataStore.updateData { settings ->
      val builder = settings.toBuilder()
      val index = builder.apiProvidersList.indexOfFirst { it.id == id }
      if (index < 0) {
        return@updateData settings
      }
      builder.removeApiProviders(index)
      if (settings.activeApiProviderId == id) {
        builder.activeApiProviderId = ""
        builder.useRemoteApi = false
      }
      builder.build()
    }
  }

  /**
   * 设置当前激活的配置，并同时打开总开关。
   *
   * @param id 配置 id；传 null 或空串表示退回本地模型。
   */
  suspend fun setActive(id: String?) {
    settingsDataStore.updateData { settings ->
      val builder = settings.toBuilder()
      if (id.isNullOrEmpty()) {
        builder.activeApiProviderId = ""
        builder.useRemoteApi = false
      } else {
        builder.activeApiProviderId = id
        builder.useRemoteApi = true
      }
      builder.build()
    }
  }

  /**
   * 打开或关闭「使用外部 API」总开关。
   *
   * 打开时若还没有激活项，会自动选第一条可用配置，避免出现「开关开了但无处可去」。
   */
  suspend fun setUseRemoteApi(enabled: Boolean) {
    settingsDataStore.updateData { settings ->
      val builder = settings.toBuilder()
      if (!enabled) {
        builder.useRemoteApi = false
      } else {
        val hasActive =
          settings.activeApiProviderId.isNotEmpty() &&
            settings.apiProvidersList.any { it.id == settings.activeApiProviderId && it.enabled }
        if (!hasActive) {
          val fallback = settings.apiProvidersList.firstOrNull { it.enabled }
          builder.activeApiProviderId = fallback?.id.orEmpty()
        }
        builder.useRemoteApi = builder.activeApiProviderId.isNotEmpty()
      }
      builder.build()
    }
  }

  /** 读取路径统一解密：无 enc1: 前缀的历史明文原样通过，下次保存时自动升级为密文。 */
  private fun decryptConfig(config: ApiProviderConfig): ApiProviderConfig =
    if (config.apiKey.isEmpty()) {
      config
    } else {
      config.copy(apiKey = cipher.decrypt(config.apiKey))
    }
}
