/*
 * Encourage — Prompt Lab 自定义模板选项仓库（M1 / 需求 H3）
 *
 * 【功能说明】
 * 持久化并读取用户在 Prompt Lab 中自行添加的模板选项。
 * 例如「语气改写」模板内置了 正式/随意/友好/礼貌/热情/简洁 六种语气，
 * 用户还想加一个「高亢」，这个新增项就保存在这里，下次打开应用依然存在。
 *
 * 【为什么需要它】
 * 内置选项写在 PromptTemplateType 枚举里（编译期常量），无法在运行时增删；
 * 而需求要求「语气可添加自建值、摘要样式自定义、代码语言自定义」，
 * 因此把用户新增的部分单独落在 DataStore，读取时与内置项合并展示。
 *
 * 【存储格式】
 * 复用 UserData 的 custom_prompt_options（map<string, string>）：
 *   key   = "<模板类型>|<编辑器 key>"，例如 "REWRITE_TONE|tone"
 *   value = 用户新增的选项，多个之间用换行符 \n 分隔（保留用户原始大小写）
 *
 * 【使用方法】
 *   // 读取（自动随 DataStore 变化推送）
 *   val all: Flow<Map<String, List<String>>> = repository.allCustomOptionsFlow()
 *
 *   // 新增（返回 false 表示内容非法或已存在）
 *   val ok = repository.addCustomOption("REWRITE_TONE", "tone", "高亢")
 *
 *   // 删除
 *   repository.removeCustomOption("REWRITE_TONE", "tone", "高亢")
 *
 * 【注意事项】
 * 1. 本类不依赖任何 UI 类，模板类型以字符串传入，保证数据层不被界面层反向依赖。
 * 2. 选项内容禁止包含换行符（分隔依据），新增时会被自动过滤掉换行。
 * 3. 单次模板的选项个数上限由 MAX_OPTIONS_PER_EDITOR 控制，避免无限增长。
 */

package com.encourage.app.data

import androidx.datastore.core.DataStore
import com.encourage.app.proto.UserData
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 单个模板编辑器最多允许的自定义选项数量。 */
private const val MAX_OPTIONS_PER_EDITOR = 30

/** 单个选项的最大字符数。 */
private const val MAX_OPTION_LENGTH = 40

/**
 * 生成自定义选项在 DataStore 中的存储键。
 *
 * @param templateTypeName 模板类型名，例如 "REWRITE_TONE"。
 * @param editorKey 编辑器 key，例如 "tone"。
 */
fun customOptionKey(templateTypeName: String, editorKey: String): String = "$templateTypeName|$editorKey"

/** 读写 Prompt Lab 自定义模板选项的仓库。 */
@Singleton
class PromptOptionRepository
@Inject
constructor(private val userDataDataStore: DataStore<UserData>) {

  /**
   * 读取全部自定义选项。
   *
   * @return key 为 [customOptionKey] 生成的存储键，value 为该编辑器下的自定义选项列表（保持添加顺序）。
   */
  fun allCustomOptionsFlow(): Flow<Map<String, List<String>>> =
    userDataDataStore.data.map { userData ->
      userData.customPromptOptionsMap.mapValues { (_, raw) -> decodeOptions(raw) }
    }

  /**
   * 读取某个模板编辑器的自定义选项。
   *
   * @param templateTypeName 模板类型名，例如 "REWRITE_TONE"。
   * @param editorKey 编辑器 key，例如 "tone"。
   */
  fun customOptionsFlow(templateTypeName: String, editorKey: String): Flow<List<String>> {
    val key = customOptionKey(templateTypeName, editorKey)
    return userDataDataStore.data.map { userData ->
      decodeOptions(userData.customPromptOptionsMap[key].orEmpty())
    }
  }

  /**
   * 新增一个自定义选项。
   *
   * @return true 表示新增成功；false 表示内容为空、超长、超过数量上限或已存在同名选项。
   */
  suspend fun addCustomOption(templateTypeName: String, editorKey: String, value: String): Boolean {
    val option = sanitizeOption(value)
    if (option.isEmpty()) {
      return false
    }
    val key = customOptionKey(templateTypeName, editorKey)
    var added = false
    userDataDataStore.updateData { userData ->
      val current = decodeOptions(userData.customPromptOptionsMap[key].orEmpty())
      if (current.size >= MAX_OPTIONS_PER_EDITOR || current.any { it.equals(option, ignoreCase = true) }) {
        added = false
        userData
      } else {
        added = true
        val updated = current + option
        userData.toBuilder().putCustomPromptOptions(key, encodeOptions(updated)).build()
      }
    }
    return added
  }

  /** 删除一个自定义选项（不存在则无操作）。 */
  suspend fun removeCustomOption(templateTypeName: String, editorKey: String, value: String) {
    val option = sanitizeOption(value)
    if (option.isEmpty()) {
      return
    }
    val key = customOptionKey(templateTypeName, editorKey)
    userDataDataStore.updateData { userData ->
      val current = decodeOptions(userData.customPromptOptionsMap[key].orEmpty())
      val updated = current.filterNot { it.equals(option, ignoreCase = true) }
      if (updated.size == current.size) {
        userData
      } else if (updated.isEmpty()) {
        userData.toBuilder().removeCustomPromptOptions(key).build()
      } else {
        userData.toBuilder().putCustomPromptOptions(key, encodeOptions(updated)).build()
      }
    }
  }

  /** 清洗用户输入：去首尾空格、截断长度、去掉会破坏存储格式的换行符。 */
  private fun sanitizeOption(value: String): String =
    value
      .replace("\n", " ")
      .replace("\r", " ")
      .trim()
      .let { if (it.length > MAX_OPTION_LENGTH) it.take(MAX_OPTION_LENGTH) else it }

  private fun decodeOptions(raw: String): List<String> = raw.split("\n").filter { it.isNotBlank() }

  private fun encodeOptions(options: List<String>): String = options.joinToString("\n")
}
