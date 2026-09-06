/*
 * Encourage — 定时任务仓库（M3 / 需求 E1 E2）
 *
 * 【功能说明】
 * 定时任务（闹钟 / 提醒）的读写仓库：CRUD + 响应式列表。
 * 底层是 DataStore<Settings>（settings.proto 的 repeated TimedTask），
 * 与 ApiProviderRepository / VoiceSettingsRepository 同款模式。
 *
 * 【与 AlarmManager 的关系】
 * 本仓库只管「数据」：任务的新增、修改、删除、启停。
 * AlarmManager 的调度与取消由 TaskScheduler（M3-4）负责：
 * 每次任务数据变化后，调度器重新对齐所有启用任务的闹钟。
 *
 * 【设计要点】
 * 1. 只存「下一次触发时间」，重复任务在触发后由调度器推进到下一天（循环调度）。
 * 2. 重复规则用星期位掩码：周一=1 … 周日=64（对应 Calendar.MONDAY..SUNDAY 的值 2..8，
 *    转换时做 2 的幂次映射），0 表示仅一次。
 * 3. 暂停（enabled=false）的任务保留记录，只是不参与调度。
 */

package com.encourage.app.data.tasks

import androidx.datastore.core.DataStore
import com.encourage.app.proto.Settings
import com.encourage.app.proto.TimedTask
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/** 一条定时任务的领域模型。 */
data class TimedTaskModel(
  val id: String = UUID.randomUUID().toString(),
  val title: String = "",
  val content: String = "",
  /** 下一次触发的 Unix 毫秒时间戳。 */
  val triggerTimeMs: Long = 0L,
  /** 星期位掩码：周一=1 … 周日=64；0 = 仅一次。 */
  val repeatDaysMask: Int = 0,
  val enabled: Boolean = true,
  val createdAtMs: Long = System.currentTimeMillis(),
  /** 【N5】可选 AI 提示词：触发后调本地/外部 AI 生成回复并朗读/通知。 */
  val aiPrompt: String = "",
) {
  /** 是否重复任务（至少覆盖一个星期几）。 */
  val isRepeating: Boolean get() = repeatDaysMask != 0

  /** 是否已配置 AI 触发（提示词非空）。 */
  val hasAi: Boolean get() = aiPrompt.isNotBlank()

  /** 星期几的位掩码值（用于生成闹钟，Calendar.MONDAY=2 … SUNDAY=8）。 */
  fun dayOfWeekMaskBit(calendarDay: Int): Boolean {
    // Calendar.MONDAY=2 → 1<<0; ... Calendar.SUNDAY=8 → 1<<6
    val bit = 1 shl (calendarDay - 2)
    return repeatDaysMask and bit != 0
  }

  fun toProto(): TimedTask =
    TimedTask.newBuilder()
      .setId(id)
      .setTitle(title)
      .setContent(content)
      .setTriggerTimeMs(triggerTimeMs)
      .setRepeatDaysMask(repeatDaysMask)
      .setEnabled(enabled)
      .setCreatedAtMs(createdAtMs)
      .setAiPrompt(aiPrompt)
      .build()

  companion object {
    fun fromProto(proto: TimedTask): TimedTaskModel =
      TimedTaskModel(
        id = proto.id,
        title = proto.title,
        content = proto.content,
        triggerTimeMs = proto.triggerTimeMs,
        repeatDaysMask = proto.repeatDaysMask,
        enabled = proto.enabled,
        createdAtMs = proto.createdAtMs,
        aiPrompt = proto.aiPrompt,
      )
  }
}

/** 定时任务的读写仓库（DataStore<Settings>）。 */
@Singleton
class TimedTaskRepository
@Inject
constructor(private val settingsDataStore: DataStore<Settings>) {

  /** 全部定时任务（按创建时间倒序）。 */
  val tasks: Flow<List<TimedTaskModel>> =
    settingsDataStore.data.map { proto ->
      proto.timedTasksList
        .map { TimedTaskModel.fromProto(it) }
        .sortedByDescending { it.createdAtMs }
    }

  /** 新增或更新任务。 */
  suspend fun upsert(task: TimedTaskModel) {
    settingsDataStore.updateData { proto ->
      val builder = proto.toBuilder()
      val idx = proto.timedTasksList.indexOfFirst { it.id == task.id }
      if (idx >= 0) {
        builder.setTimedTasks(idx, task.toProto())
      } else {
        builder.addTimedTasks(task.toProto())
      }
      builder.build()
    }
  }

  /** 删除任务。 */
  suspend fun delete(id: String) {
    settingsDataStore.updateData { proto ->
      val remaining = proto.timedTasksList.filterNot { it.id == id }
      proto.toBuilder().clearTimedTasks().addAllTimedTasks(remaining).build()
    }
  }

  /** 暂停 / 恢复任务。 */
  suspend fun setEnabled(id: String, enabled: Boolean) {
    settingsDataStore.updateData { proto ->
      val idx = proto.timedTasksList.indexOfFirst { it.id == id }
      if (idx < 0) {
        proto
      } else {
        proto.toBuilder().setTimedTasks(idx, proto.timedTasksList[idx].toBuilder().setEnabled(enabled)).build()
      }
    }
  }

  /** 取单个任务；不存在返回 null。 */
  suspend fun getById(id: String): TimedTaskModel? {
    return settingsDataStore.data
      .map { settings -> settings.timedTasksList.firstOrNull { it.id == id } }
      .firstOrNull()
      ?.let { TimedTaskModel.fromProto(it) }
  }
}
