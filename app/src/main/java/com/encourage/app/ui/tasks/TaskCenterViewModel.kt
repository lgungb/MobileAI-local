/*
 * Encourage — 任务中心 ViewModel（M3 / 需求 E2）
 *
 * 【功能说明】
 * 任务中心页的状态与操作：任务列表、新增、编辑、暂停/恢复、删除、立即执行。
 *
 * 【设计要点】
 * 1. 数据响应式：tasks 直接订阅 TimedTaskRepository 的 Flow，
 *    任务触发推进后列表自动刷新。
 * 2. 增删改都会同步调 TaskScheduler 更新 AlarmManager 闹钟（数据与调度分离）。
 * 3. 立即执行：把任务的触发时间设为「现在 +1 秒」并重新调度，
 *    由接收器走完整的「通知 + 朗读 + 推进」链路，逻辑不重复。
 */

package com.encourage.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.data.tasks.TaskScheduler
import com.encourage.app.data.tasks.TimedTaskModel
import com.encourage.app.data.tasks.TimedTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 任务中心的状态与操作入口。 */
@HiltViewModel
class TaskCenterViewModel
@Inject
constructor(
  private val repository: TimedTaskRepository,
  private val scheduler: TaskScheduler,
) : ViewModel() {

  /** 全部定时任务（按创建时间倒序）。 */
  val tasks: StateFlow<List<TimedTaskModel>> =
    repository.tasks.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = emptyList(),
    )

  /** 新增或更新任务，并同步调度闹钟。 */
  fun upsert(task: TimedTaskModel) {
    viewModelScope.launch {
      repository.upsert(task)
      scheduler.scheduleTask(task)
    }
  }

  /** 删除任务并取消闹钟。 */
  fun delete(id: String) {
    viewModelScope.launch {
      val task = repository.getById(id)
      if (task != null) {
        scheduler.cancelTask(task)
      }
      repository.delete(id)
    }
  }

  /** 暂停 / 恢复任务。 */
  fun setEnabled(id: String, enabled: Boolean) {
    viewModelScope.launch {
      repository.setEnabled(id, enabled)
      val task = repository.getById(id)
      if (task != null) {
        if (enabled) {
          scheduler.scheduleTask(task)
        } else {
          scheduler.cancelTask(task)
        }
      }
    }
  }

  /** 立即执行：触发时间设为 1 秒后并调度，走完整通知 + 朗读链路。 */
  fun runNow(task: TimedTaskModel) {
    viewModelScope.launch {
      repository.upsert(task.copy(triggerTimeMs = System.currentTimeMillis() + 1_000))
      scheduler.scheduleTask(task.copy(triggerTimeMs = System.currentTimeMillis() + 1_000))
    }
  }
}
