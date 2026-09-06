/*
 * Encourage — Agent 定义（M5 / 需求 F1）
 *
 * 【设计说明】
 * 一个 Agent = 一个标准 JSON 文件，便于查看、编辑、导入导出（文件路径透明）。
 * 内置 Agent 打包在 assets/agents/ 下，用户自建 / 从 URL 导入的存放在
 * filesDir/agents/ 下；两者在界面上用「内置 / 自定义」徽标区分。
 */

package com.encourage.app.data.agents

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException

/** Agent 定义文件的结构。 */
data class AgentDefinition(
  /** 唯一 id（UUID）。内置 Agent 用固定 id。 */
  val id: String = "",
  /** 显示名。 */
  val name: String = "",
  /** 一句话描述（列表展示用）。 */
  val description: String = "",
  /** 系统提示词（应用 Agent 时写入会话）。 */
  val systemPrompt: String = "",
  /** 作者（可选）。 */
  val author: String = "",
  /** 文件格式版本。 */
  val version: Int = 1,
  /** 内置 Agent 只读；用户自建 / 导入的可编辑删除。 */
  val builtin: Boolean = false,
)

/** Agent JSON 序列化 / 反序列化助手。 */
object AgentJson {
  private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  /** 解析 Agent JSON 文本；格式非法时返回 null。 */
  fun parse(text: String): AgentDefinition? =
    try {
      val def = gson.fromJson(text, AgentDefinition::class.java)
      if (def != null && def.name.isNotBlank() && def.systemPrompt.isNotBlank()) def else null
    } catch (_: JsonSyntaxException) {
      null
    } catch (_: IllegalStateException) {
      null
    }

  /** 序列化为格式化 JSON（导出 / 落盘共用）。 */
  fun toJson(def: AgentDefinition): String = gson.toJson(def)
}
