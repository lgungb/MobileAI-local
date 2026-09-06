/*
 * Encourage — 任务中心界面（M3 / 需求 E2）
 *
 * 【功能说明】
 * 任务中心的完整 UI：任务列表（启用状态 / 下次触发时间 / 重复规则）、
 * 新增与编辑表单（名称、内容、触发时刻、星期重复）、暂停 / 删除 / 立即执行。
 *
 * 【交互设计要点】
 * 1. 列表项展示关键信息：名称、下次触发时间（人性化格式）、重复徽标（「每天」「工作日」
 *    「周一, 周三」…）、启用开关。
 * 2. 「立即执行」把触发时间改为 1 秒后，复用完整触发链路（通知 + 朗读），便于测试。
 * 3. 新建任务默认 1 小时后触发、仅一次；用户可勾选星期变为重复任务。
 * 4. 表单里触发时间用「小时:分钟」输入（TimePicker 需额外依赖，避免引入，
 *    用两列数字输入 + 星期多选，逻辑简单可靠）。
 *
 * 【使用方法】
 *   // 在设置对话框内
 *   TaskCenterSection(onOpenCenter = { showTaskCenter = true })
 *
 *   if (showTaskCenter) {
 *     TaskCenterDialog(onDismissed = { showTaskCenter = false })
 *   }
 */

package com.encourage.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.tasks.TimedTaskModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 设置界面中的「任务中心」入口区块。 */
@Composable
fun TaskCenterSection(
  viewModel: TaskCenterViewModel = hiltViewModel(),
  onOpenCenter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tasks by viewModel.tasks.collectAsState()
  val enabledCount = tasks.count { it.enabled }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.task_center_title),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
          stringResource(R.string.task_center_summary, enabledCount),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(onClick = onOpenCenter) {
        Text(stringResource(R.string.task_center_open))
      }
    }
  }
}

/** 任务中心对话框：任务列表 + 新建入口。 */
@Composable
fun TaskCenterDialog(
  viewModel: TaskCenterViewModel = hiltViewModel(),
  onDismissed: () -> Unit,
) {
  val tasks by viewModel.tasks.collectAsState()
  var showEditor by remember { mutableStateOf(false) }
  var editingTask by remember { mutableStateOf<TimedTaskModel?>(null) }

  Dialog(onDismissRequest = onDismissed) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            stringResource(R.string.task_center_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { editingTask = TimedTaskModel(); showEditor = true }) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.task_add))
          }
        }

        // 精确闹钟权限提示（Android 12+ 未授权时提醒会延迟）。
        Text(
          stringResource(R.string.task_center_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (tasks.isEmpty()) {
          Text(
            stringResource(R.string.task_center_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        } else {
          for (task in tasks) {
            TaskRow(
              task = task,
              onToggle = { viewModel.setEnabled(task.id, it) },
              onEdit = { editingTask = task; showEditor = true },
              onDelete = { viewModel.delete(task.id) },
              onRunNow = { viewModel.runNow(task) },
            )
            HorizontalDivider()
          }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.close)) }
        }
      }
    }
  }

  if (showEditor) {
    TaskEditorDialog(
      initial = editingTask ?: TimedTaskModel(),
      onDismissed = { showEditor = false; editingTask = null },
      onSaved = { task ->
        viewModel.upsert(task)
        showEditor = false
        editingTask = null
      },
    )
  }
}

/** 单条任务的行。 */
@Composable
private fun TaskRow(
  task: TimedTaskModel,
  onToggle: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onRunNow: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          task.title.ifBlank { stringResource(R.string.task_default_title) },
          style = MaterialTheme.typography.bodyLarge,
        )
        Text(
          formatTriggerTime(task),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          formatRepeat(task),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
        if (task.hasAi) {
          Text(
            stringResource(R.string.task_ai_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }
      Switch(checked = task.enabled, onCheckedChange = onToggle)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      TextButton(onClick = onEdit) { Text(stringResource(R.string.task_edit)) }
      TextButton(onClick = onRunNow) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 2.dp))
        Text(stringResource(R.string.task_run_now))
      }
      TextButton(onClick = onDelete) { Text(stringResource(R.string.task_delete)) }
    }
  }
}

/** 新建 / 编辑任务表单。 */
@Composable
private fun TaskEditorDialog(
  initial: TimedTaskModel,
  onDismissed: () -> Unit,
  onSaved: (TimedTaskModel) -> Unit,
) {
  var title by remember { mutableStateOf(initial.title) }
  var content by remember { mutableStateOf(initial.content) }
  // 【N5】AI 提示词：非空时触发后调用本地/外部 AI 生成回复并朗读/通知。
  var aiPrompt by remember { mutableStateOf(initial.aiPrompt) }
  // 触发时刻（默认：新建时 1 小时后；编辑时取原触发时间）。
  val defaultHourMinute = remember {
    val cal = Calendar.getInstance().apply {
      timeInMillis = if (initial.triggerTimeMs > 0) initial.triggerTimeMs else System.currentTimeMillis() + 3_600_000L
    }
    cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
  }
  var hourText by remember { mutableStateOf(defaultHourMinute.first.toString()) }
  var minuteText by remember { mutableStateOf(defaultHourMinute.second.toString()) }
  // 星期多选：位掩码（位 0..6 = 周一..周日），Int 重新赋值即可触发重组。
  var repeatMask by remember { mutableStateOf(initial.repeatDaysMask) }

  val weekdays =
    listOf(
      R.string.task_mon to 0,
      R.string.task_tue to 1,
      R.string.task_wed to 2,
      R.string.task_thu to 3,
      R.string.task_fri to 4,
      R.string.task_sat to 5,
      R.string.task_sun to 6,
    )

  Dialog(onDismissRequest = onDismissed) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          stringResource(if (initial.triggerTimeMs > 0) R.string.task_edit_title else R.string.task_add_title),
          style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text(stringResource(R.string.task_title_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = content,
          onValueChange = { content = it },
          label = { Text(stringResource(R.string.task_content_label)) },
          modifier = Modifier.fillMaxWidth(),
        )
        // 【N5】AI 触发提示词。
        OutlinedTextField(
          value = aiPrompt,
          onValueChange = { aiPrompt = it },
          label = { Text(stringResource(R.string.task_ai_prompt_label)) },
          supportingText = { Text(stringResource(R.string.task_ai_prompt_hint)) },
          minLines = 2,
          maxLines = 4,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = hourText,
            onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.task_hour_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = minuteText,
            onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.task_minute_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }

        // 星期多选（全不选 = 仅一次）。
        Text(
          stringResource(R.string.task_repeat_label),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
          for ((labelRes, idx) in weekdays) {
            val checked = repeatMask and (1 shl idx) != 0
            OutlinedButton(
              onClick = { repeatMask = repeatMask xor (1 shl idx) },
              modifier = Modifier.weight(1f),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
              Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        if (repeatMask == 0) {
          Text(
            stringResource(R.string.task_repeat_once_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) }
          Button(
            onClick = {
              val hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
              val minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
              val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // 时间已过则顺延到明天。
                if (timeInMillis <= System.currentTimeMillis()) {
                  add(Calendar.DATE, 1)
                }
              }
              onSaved(
                initial.copy(
                  title = title.trim(),
                  content = content.trim(),
                  aiPrompt = aiPrompt.trim(),
                  triggerTimeMs = cal.timeInMillis,
                  repeatDaysMask = repeatMask,
                )
              )
            }
          ) {
            Text(stringResource(R.string.save))
          }
        }
      }
    }
  }
}

/** 人性化的触发时间展示。 */
private fun formatTriggerTime(task: TimedTaskModel): String {
  if (task.triggerTimeMs <= 0) return ""
  val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
  return formatter.format(Date(task.triggerTimeMs))
}

/** 重复规则的展示文案。 */
private fun formatRepeat(task: TimedTaskModel): String {
  if (!task.isRepeating) {
    return "仅一次"
  }
  val names =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
  val selected = (0..6).filter { task.repeatDaysMask and (1 shl it) != 0 }.map { names[it] }
  return when {
    selected.size == 7 -> "每天"
    selected.size == 5 && selected == listOf("周一", "周二", "周三", "周四", "周五") -> "工作日"
    else -> selected.joinToString(", ")
  }
}
