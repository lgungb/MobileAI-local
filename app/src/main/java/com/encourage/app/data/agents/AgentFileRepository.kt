/*
 * Encourage — Agent 文件仓库（M5 / 需求 F1）
 *
 * 【职责】
 * - 内置 Agent：assets/agents/ 目录下的 json 文件，只读；
 * - 用户 Agent：filesDir/agents/ 目录下的 json 文件，可新建 / 编辑 / 删除；
 * - URL 导入：下载 JSON 并校验后落盘；
 * - 通过 StateFlow 对界面暴露合并后的列表（内置在前）。
 */

package com.encourage.app.data.agents

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "AGAgentFileRepo"
private const val ASSETS_DIR = "agents"
private const val USER_DIR = "agents"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val MAX_IMPORT_BYTES = 512 * 1024

/** 解析后的 Agent 文件（含来源信息）。 */
data class AgentEntry(val definition: AgentDefinition, val filePath: String)

@Singleton
class AgentFileRepository
@Inject
constructor(@ApplicationContext private val context: Context) {

  private val _agents = MutableStateFlow<List<AgentEntry>>(emptyList())
  val agents: StateFlow<List<AgentEntry>> = _agents.asStateFlow()

  /** 用户 Agent 存放目录（对用户透明，可在详情里查看）。 */
  val userAgentsDir: File
    get() = File(context.filesDir, USER_DIR).apply { mkdirs() }

  fun newId(): String = UUID.randomUUID().toString()

  /** 重新扫描 assets 与用户目录。 */
  suspend fun refresh() {
    val entries =
      withContext(Dispatchers.IO) {
        loadBuiltinAgents() + loadUserAgents()
      }
    _agents.update { entries }
  }

  /** 保存（新建或更新）一个用户 Agent；内置 Agent 不允许覆盖。 */
  suspend fun save(definition: AgentDefinition): AgentEntry? {
    if (definition.builtin) {
      Log.w(TAG, "Refusing to overwrite builtin agent '${definition.name}'")
      return null
    }
    return withContext(Dispatchers.IO) {
      val id = definition.id.ifEmpty { newId() }
      val def = definition.copy(id = id, builtin = false)
      val file = File(userAgentsDir, "$id.json")
      try {
        file.writeText(AgentJson.toJson(def))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to save agent '$id'", e)
        return@withContext null
      }
      refresh()
      AgentEntry(def, file.absolutePath)
    }
  }

  /** 删除一个用户 Agent；内置 Agent 不允许删除。 */
  suspend fun delete(id: String): Boolean {
    return withContext(Dispatchers.IO) {
      val file = File(userAgentsDir, "$id.json")
      val ok = file.exists() && file.delete()
      if (ok) {
        refresh()
      } else {
        Log.w(TAG, "Delete failed for agent '$id'")
      }
      ok
    }
  }

  /**
   * 从 URL 导入 Agent（F1：支持网络下载的 Agent 包）。
   * 返回 null 时通过 [error] 给出原因（网络 / 格式）。
   */
  suspend fun importFromUrl(url: String, error: (String) -> Unit): AgentEntry? {
    return withContext(Dispatchers.IO) {
      val text =
        try {
          val conn = URL(url).openConnection() as HttpURLConnection
          conn.connectTimeout = CONNECT_TIMEOUT_MS
          conn.readTimeout = READ_TIMEOUT_MS
          conn.instanceFollowRedirects = true
          conn.inputStream.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > MAX_IMPORT_BYTES) {
              error.invoke("文件过大")
              return@withContext null
            }
            String(bytes, Charsets.UTF_8)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to download agent from $url", e)
          error.invoke(e.message ?: "网络错误")
          return@withContext null
        }

      val def = AgentJson.parse(text)
      if (def == null) {
        error.invoke("JSON 格式无效")
        return@withContext null
      }
      val entry = save(def.copy(id = "", builtin = false))
      if (entry == null) {
        error.invoke("保存失败")
      }
      entry
    }
  }

  /** 复制内置 Agent 为可编辑副本。 */
  suspend fun duplicate(entry: AgentEntry): AgentEntry? =
    save(
      entry.definition.copy(
        id = "",
        name = entry.definition.name + " (copy)",
        builtin = false,
      )
    )

  // ------------------------------------------------------------------------

  private fun loadBuiltinAgents(): List<AgentEntry> {
    val names =
      try {
        context.assets.list(ASSETS_DIR)?.toList() ?: emptyList()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to list builtin agents", e)
        emptyList()
      }
    return names
      .filter { it.endsWith(".json") }
      .sorted()
      .mapNotNull { fileName ->
        try {
          val text = context.assets.open("$ASSETS_DIR/$fileName").bufferedReader().use { it.readText() }
          val def = AgentJson.parse(text)?.copy(builtin = true) ?: return@mapNotNull null
          AgentEntry(def, "assets/$ASSETS_DIR/$fileName")
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load builtin agent $fileName", e)
          null
        }
      }
  }

  private fun loadUserAgents(): List<AgentEntry> {
    return userAgentsDir
      .listFiles { file -> file.isFile && file.extension == "json" }
      ?.mapNotNull { file ->
        try {
          val def =
            AgentJson.parse(file.readText())?.copy(builtin = false) ?: return@mapNotNull null
          AgentEntry(def, file.absolutePath)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load agent ${file.name}", e)
          null
        }
      }
      ?.sortedBy { it.definition.name }
      ?: emptyList()
  }
}
