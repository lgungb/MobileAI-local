/*
 * Encourage 应用事件定义（无埋点版本）。
 *
 * 【功能说明】
 * 原版基于 Firebase Analytics 上报用户事件，去谷歌化（M0）后已全部移除，
 * 本文件仅保留 GalleryEvent 枚举与调用点兼容，不进行任何数据上报。
 *
 * 【使用方法】
 * 业务代码可继续引用 GalleryEvent（如 logErrorToFirebase(event, ...)），
 * 调用为空操作，无需删改调用点；将来若接入自选统计方案，
 * 在 Utils.kt 的 logErrorToFirebase 中恢复实现即可。
 */

package com.encourage.app

import android.os.Bundle

/** 应用内可观测的事件类型（当前仅作为日志语义标识，不上报）。 */
enum class GalleryEvent(val id: String) {
  CAPABILITY_SELECT(id = "capability_select"),
  MODEL_DOWNLOAD(id = "model_download"),
  GENERATE_ACTION(id = "generate_action"),
  BUTTON_CLICKED(id = "button_clicked"),
  SKILL_MANAGEMENT(id = "skill_management"),
  SKILL_EXECUTION(id = "skill_execution"),
  CHAT_HISTORY(id = "chat_history"),
  MCP_MANAGEMENT(id = "mcp_management"),
  MCP_EXECUTION(id = "mcp_execution"),
  MODEL_CONFIG_CHANGE(id = "model_config_change"),
}

/** 无操作统计存根：保留 Firebase Analytics 的调用签名，但不执行任何上报。 */
class NoOpAnalytics {
  fun logEvent(name: String, bundle: Bundle?) {
    // 空实现：去谷歌化后无事件上报。
  }
}

/**
 * 全局统计句柄（无操作版）。
 *
 * 值恒为 null，业务代码中所有 `firebaseAnalytics?.logEvent(...)` 调用
 * 均可原样编译、运行时被安全跳过。
 * 将来若接入自选统计方案（如自建埋点 SDK），将此属性替换为真实实现即可。
 */
val firebaseAnalytics: NoOpAnalytics? = null
