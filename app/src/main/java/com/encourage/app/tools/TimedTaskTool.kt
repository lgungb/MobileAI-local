/*
 * Encourage — 定时任务工具（M3 / 需求 E1）
 *
 * 【功能说明】
 * 暴露给端侧 LLM 的 Function Calling 工具：让模型在对话中直接创建定时任务。
 * 例如用户说「明天早上 8 点叫我起床」，模型调用 createTimedTask 并传入
 * 标题、内容、触发时间，本工具落库并调度 AlarmManager。
 *
 * 【参数设计】
 * 模型只传「一句话能说清」的参数：标题、内容、触发时刻（ISO 8601 或 HH:mm + 日期说明）、
 * 以及可选的星期重复（"daily" / "weekday" / "weekly"）。
 * 时间解析放在工具内部（parseTriggerTime），模型不需要理解日历计算。
 *
 * 【使用方法】
 * 通过 Hilt 注册到 AgentExecutorModule 的 RuntimeToolsProvider 初始工具列表。
 */

package com.encourage.app.tools

import android.content.Context
import android.util.Log
import com.encourage.app.data.tasks.TaskScheduler
import com.encourage.app.data.tasks.TimedTaskModel
import com.encourage.app.data.tasks.TimedTaskRepository
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGTimedTaskTool"

/** 让端侧 LLM 创建定时任务的工具。 */
class TimedTaskTool
@Inject
constructor(
  private val repository: TimedTaskRepository,
  private val scheduler: TaskScheduler,
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  /**
   * 创建一个定时任务（闹钟 / 提醒）。
   *
   * @param title 任务标题，例如「叫我起床」。
   * @param content 触发时朗读并通知的内容，例如「该起床啦」。
   * @param time 触发时刻，支持两种格式：
   *   - "HH:mm"（如 "08:00"，今天的该时刻；若已过则顺延到明天）
   *   - "yyyy-MM-dd HH:mm"（如 "2026-09-01 08:00"，精确到某一天）
   * @param repeat "none"（仅一次，默认）/"daily"（每天）/"weekday"（工作日）
   *   /"weekly"（每周同一天，取 time 的星期几）。
   * @param aiPrompt 可选 AI 提示词。非空时，任务触发后会调用本地/外部 AI 生成回复并朗读/通知。
   */
  @Tool(description = "Create a timed task (alarm or reminder) that will notify and speak at a future time.")
  fun createTimedTask(
    @ToolParam(description = "Task title, e.g. 'wake me up'.") title: String,
    @ToolParam(description = "Content to speak and notify when triggered.") content: String,
    @ToolParam(description = "Trigger time. Formats: 'HH:mm' (today, or tomorrow if passed) or 'yyyy-MM-dd HH:mm'.") time: String,
    @ToolParam(description = "Repeat rule: 'none' (once), 'daily', 'weekday', or 'weekly'.") repeat: String = "none",
    @ToolParam(description = "Optional AI prompt. If set, on trigger the app asks local/remote AI and speaks its reply.") aiPrompt: String = "",
  ): Map<String, String> {
    Log.d(TAG, "createTimedTask: title=$title content=$content time=$time repeat=$repeat aiPrompt=$aiPrompt")
    return runBlocking(Dispatchers.Default) {
      val triggerMs = parseTriggerTime(time)
      if (triggerMs == null) {
        return@runBlocking mapOf("status" to "failed", "error" to "无法解析时间，请用 HH:mm 或 yyyy-MM-dd HH:mm 格式")
      }
      val repeatMask = parseRepeat(repeat, triggerMs)
      val task =
        TimedTaskModel(
          title = title.trim(),
          content = content.trim(),
          aiPrompt = aiPrompt.trim(),
          triggerTimeMs = triggerMs,
          repeatDaysMask = repeatMask,
          enabled = true,
        )
      try {
        repository.upsert(task)
        scheduler.scheduleTask(task)
        mapOf("status" to "ok", "task_id" to task.id, "trigger_time" to formatTime(triggerMs))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create timed task", e)
        mapOf("status" to "failed", "error" to e.message.orEmpty())
      }
    }
  }

  /** 解析触发时间；两种格式，失败返回 null。 */
  private fun parseTriggerTime(time: String): Long? {
    val trimmed = time.trim()
    return try {
      val cal = Calendar.getInstance()
      when {
        // HH:mm
        Regex("^\\d{1,2}:\\d{1,2}$").matches(trimmed) -> {
          val parts = trimmed.split(":")
          cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
          cal.set(Calendar.MINUTE, parts[1].toInt())
          cal.set(Calendar.SECOND, 0)
          cal.set(Calendar.MILLISECOND, 0)
          // 已过则顺延到明天。
          if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DATE, 1)
          }
          cal.timeInMillis
        }
        // yyyy-MM-dd HH:mm
        Regex("^\\d{4}-\\d{2}-\\d{2} \\d{1,2}:\\d{2}$").matches(trimmed) -> {
          val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
          formatter.parse(trimmed)?.time ?: return null
        }
        else -> return null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse trigger time: $time", e)
      null
    }
  }

  /** 解析重复规则；weekly 取触发时刻的星期几。 */
  private fun parseRepeat(repeat: String, triggerMs: Long): Int {
    return when (repeat.trim().lowercase(Locale.US)) {
      "daily" -> 0b1111111
      "weekday" -> 0b0011111
      "weekly" -> {
        val day = Calendar.getInstance().apply { timeInMillis = triggerMs }.get(Calendar.DAY_OF_WEEK)
        1 shl (day - 2)
      }
      else -> 0
    }
  }

  private fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(ms)
}
