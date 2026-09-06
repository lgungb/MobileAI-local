/*
 * Encourage — 定时任务调度器（M3 / 需求 E1 E2 D2）
 *
 * 【功能说明】
 * 把 TimedTaskRepository 里的任务落到 AlarmManager 精确闹钟上：
 * - 调度：为任务的「下一次触发时间」注册一次性闹钟（重复任务在触发后推进到下一天）；
 * - 触发：BroadcastReceiver 收到闹钟 → 发系统通知 + 按语音设置朗读任务内容（D2 联动）；
 * - 重启恢复：BootReceiver 已存在（notifications 包），M3 起开机时调用 rescheduleAll() 恢复闹钟。
 *
 * 【设计要点】
 * 1. **调度与数据分离**：本类只负责 AlarmManager；数据读写全部走 TimedTaskRepository。
 *    任务中心每次增删改后调用 [scheduleTask] / [cancelTask]，无需感知内部实现。
 * 2. **重复任务的推进**：触发后由接收器里重新计算下一次触发时间（按星期位掩码找
 *    下一个匹配的星期几），再写回仓库并重新调度 —— 永不“过期”的循环闹钟。
 * 3. **精确闹钟权限**：Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限。
 *    未授权时降级为 setWindow（宽限 10 秒），保证功能可用但不强制用户授权。
 * 4. 通知走应用默认渠道（Android 13+ 需要 POST_NOTIFICATIONS 运行时权限，由任务中心引导）。
 */

package com.encourage.app.data.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.encourage.app.R
import com.encourage.app.data.speech.VoiceSettings
import com.encourage.app.data.speech.VoiceSettingsRepository
import com.encourage.app.speech.SpeechManager
import com.encourage.app.speech.VoiceSettingsSnapshot
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "AGTaskScheduler"

/** 定时任务闹钟的广播 Action。 */
const val ACTION_TASK_ALARM = "com.encourage.app.action.TASK_ALARM"

/** 任务闹钟使用的通知渠道。 */
const val TASK_CHANNEL_ID = "encourage_timed_tasks"
const val TASK_CHANNEL_NAME = "Encourage 定时任务"

/** 定时任务调度器：AlarmManager 的封装。 */
@Singleton
class TaskScheduler
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val repository: TimedTaskRepository,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val alarmManager: AlarmManager
    get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  /** 注册（或更新）一条任务的闹钟。 */
  fun scheduleTask(task: TimedTaskModel) {
    if (!task.enabled || task.triggerTimeMs <= 0L) return
    val triggerAt = task.triggerTimeMs
    val pi = buildPendingIntent(context, task)
    val exact = canScheduleExactAlarms()
    if (exact) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    } else {
      // 未授予精确闹钟权限：宽限 10 秒窗口，保证不丢但允许系统批量。
      alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10_000L, pi)
    }
    Log.d(TAG, "Scheduled task ${task.id} @ $triggerAt (exact=$exact)")
  }

  /** 取消一条任务的闹钟。 */
  fun cancelTask(task: TimedTaskModel) {
    alarmManager.cancel(buildPendingIntent(context, task))
    Log.d(TAG, "Cancelled task ${task.id}")
  }

  /** 重调度全部启用任务（开机 / 数据修复后调用）。 */
  fun rescheduleAll() {
    scope.launch {
      repository.tasks.firstOrNull()?.forEach { task -> scheduleTask(task) }
    }
  }

  /** Android 12+ 的精确闹钟能力。 */
  fun canScheduleExactAlarms(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      alarmManager.canScheduleExactAlarms()
    } else {
      true
    }

  private fun buildPendingIntent(context: Context, task: TimedTaskModel): PendingIntent {
    val intent =
      Intent(context, TaskAlarmReceiver::class.java).apply {
        action = ACTION_TASK_ALARM
        putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, task.id)
      }
    return PendingIntent.getBroadcast(
      context,
      task.id.hashCode(),
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  companion object {
    /** 供 BootReceiver 调用：开机恢复所有定时任务闹钟。 */
    @JvmStatic
    fun rescheduleOnBoot(context: Context) {
      val entryPoint =
        EntryPointAccessors.fromApplication(
          context.applicationContext,
          TaskSchedulerEntryPoint::class.java,
        )
      entryPoint.taskScheduler().rescheduleAll()
    }
  }
}

/** Hilt 入口，供没有注入点的组件（如 BootReceiver）获取调度器与仓库。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface TaskSchedulerEntryPoint {
  fun taskScheduler(): TaskScheduler
  fun taskRepository(): TimedTaskRepository
  fun taskAiInvoker(): TaskAiInvoker
}

/**
 * 定时任务触发接收器：闹钟到点后发通知 + 朗读，并把重复任务推进到下一次。
 *
 * 注意：onReceive 在 BroadcastReceiver 的短生命周期里执行，不能做耗时操作；
 * 重调度写入 DataStore 用独立协程（SupervisorJob 不随 Receiver 结束）。
 */
class TaskAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
    Log.d(TAG, "Task alarm fired: $taskId")
    goAsync()

    val appContext = context.applicationContext
    val entryPoint =
      EntryPointAccessors.fromApplication(appContext, TaskSchedulerEntryPoint::class.java)
    val scheduler = entryPoint.taskScheduler()
    val repository = entryPoint.taskRepository()

    // 读取任务 → 通知 + 朗读 → 推进下一次。
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
      val task = repository.getById(taskId)
      if (task == null || !task.enabled) {
        // 任务被删 / 暂停了：直接取消闹钟。
        if (task != null) scheduler.cancelTask(task)
        return@launch
      }

      // 【N5】若任务配置了 AI 提示词，触发时先调用本地/外部 AI 生成回复，
      // 把 AI 返回的文本作为通知内容并朗读；AI 不可用则回退用任务原始 content。
      var effectiveContent = task.content
      if (task.hasAi) {
        val aiResult = entryPoint.taskAiInvoker().invoke(prompt = task.aiPrompt, contextText = task.content)
        if (!aiResult.reply.isNullOrBlank()) {
          effectiveContent = aiResult.reply
          Log.d(TAG, "Task $taskId AI reply (${aiResult.source}): ${aiResult.reply.length} chars")
        } else {
          Log.w(TAG, "Task $taskId AI unavailable; falling back to static content.")
        }
      }

      showNotification(context, task, effectiveContent)
      speakTask(appContext, effectiveContent)

      val next = advanceToNextTrigger(task)
      repository.upsert(next)
      if (next.enabled) scheduler.scheduleTask(next)
      Log.d(TAG, "Task $taskId advanced to $next")
    }
  }

  /** 通知。 */
  private fun showNotification(context: Context, task: TimedTaskModel, content: String) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(TASK_CHANNEL_ID, TASK_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
      notificationManager.createNotificationChannel(channel)
    }
    // 点击通知回到应用主界面。
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val contentPi =
      launchIntent?.let {
        PendingIntent.getActivity(context, task.id.hashCode(), it, PendingIntent.FLAG_IMMUTABLE)
      }
    val notification =
      NotificationCompat.Builder(context, TASK_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(task.title.ifBlank { context.getString(R.string.task_default_title) })
        .setContentText(content.ifBlank { context.getString(R.string.task_default_content) })
        .setStyle(NotificationCompat.BigTextStyle().bigText(content.ifBlank { context.getString(R.string.task_default_content) }))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(contentPi)
        .build()
    notificationManager.notify(task.id.hashCode(), notification)
  }

  /** 按语音设置朗读任务内容（D2 联动）。 */
  private fun speakTask(context: Context, content: String) {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return
    // 触发场景不持有 Compose 生命周期，直接用 Application 级 SpeechManager。
    // 语音设置通过 VoiceSettingsRepository 的 DataStore 读取（流式，读一次当前值）。
    try {
      val speechManager = createSpeechManager(context)
      speechManager.speakWithSettings(trimmed)
      // 让朗读在 Receiver 结束后仍有时间播放：触发后延迟释放。
      Handler(Looper.getMainLooper()).postDelayed({
        speechManager.shutdown()
      }, 60_000L)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to speak task", e)
    }
  }

  private fun createSpeechManager(context: Context): SpeechManager {
    val entryPoint =
      EntryPointAccessors.fromApplication(context, VoiceSettingsEntryPoint::class.java)
    val repo = entryPoint.voiceSettingsRepository()
    return SpeechManager(
      context,
      voiceSettingsProvider = {
        // 读取一次当前语音设置（DataStore 首次值在 IO 线程回读）。
        try {
          val s: VoiceSettings =
            runBlocking(Dispatchers.IO) {
              repo.flow.first()
            }
          VoiceSettingsSnapshot(rate = s.rate, pitch = s.pitch)
        } catch (e: Exception) {
          VoiceSettingsSnapshot()
        }
      },
    )
  }

  /** 把任务推进到下一次触发时间。 */
  private fun advanceToNextTrigger(task: TimedTaskModel): TimedTaskModel {
    if (!task.isRepeating) {
      // 一次性任务：触发即停用（保留记录，便于任务中心展示历史）。
      return task.copy(enabled = false)
    }
    val now = System.currentTimeMillis()
    val next = nextMatchForMask(task.repeatDaysMask, task.triggerTimeMs, now)
    return task.copy(triggerTimeMs = next)
  }

  companion object {
    const val EXTRA_TASK_ID = "task_id"
  }
}

/** 依据星期位掩码计算下一次触发时间（保留原时分秒，从明天起找第一个匹配日）。 */
fun nextMatchForMask(mask: Int, originalTriggerMs: Long, nowMs: Long): Long {
  val cal = Calendar.getInstance().apply { timeInMillis = originalTriggerMs }
  val hour = cal.get(Calendar.HOUR_OF_DAY)
  val minute = cal.get(Calendar.MINUTE)
  val second = cal.get(Calendar.SECOND)

  // 从「明天」开始逐日检查，命中掩码即返回（保证一定在将来，语义干净）。
  val candidate = Calendar.getInstance().apply {
    timeInMillis = nowMs
    add(Calendar.DATE, 1)
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, second)
    set(Calendar.MILLISECOND, 0)
  }
  repeat(8) {
    val day = candidate.get(Calendar.DAY_OF_WEEK) // 2..8
    val bit = 1 shl (day - 2)
    if (mask and bit != 0) {
      return candidate.timeInMillis
    }
    candidate.add(Calendar.DATE, 1)
  }
  // 理论上不可达（掩码非 0 时 8 天内必有匹配日），兜底返回。
  return candidate.timeInMillis
}

/** 语音设置 Hilt 入口（供触发接收器读取语速 / 音调）。 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface VoiceSettingsEntryPoint {
  fun voiceSettingsRepository(): VoiceSettingsRepository
}
