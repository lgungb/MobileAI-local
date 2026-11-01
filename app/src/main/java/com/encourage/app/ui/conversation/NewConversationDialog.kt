/*
 * Encourage — 新建对话对话框（加号入口）
 *
 * 【功能说明】
 * 「对话」Tab 右下角「+」点击后弹出，引导用户按两步新建一条「特调记录」：
 *   第一步：选一个常用提示词（角色设定，带标题分组）；
 *   第二步：选模型（默认模型置顶高亮；选非默认模型时提示耗时，需二次确认）。
 * 三个采样参数（top-k / top-p / temperature）一律使用模型默认设置，不做自定义。
 *
 * 【设计要点】
 * 1. 纯 UI、无副作用：选定结果通过 [onConfirm] 回传，由导航层负责建记录 + 跳转；
 * 2. 默认模型与可选模型由调用方注入（本组件不感知 ModelManagerViewModel）；
 * 3. 非默认模型二次确认：给出「确认使用」与「继续用默认模型」两个按钮，符合用户要求。
 */

package com.encourage.app.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.encourage.app.data.Model
import com.encourage.app.data.conversation.PresetPrompt
import com.encourage.app.data.conversation.PresetPrompts

/**
 * 新建对话对话框。
 *
 * @param defaultModel 默认模型（置顶、高亮）；为 null 时列表里没有「默认」标记。
 * @param availableModels 可选模型列表（含默认模型）。
 * @param isDownloaded 判断某个模型是否已下载（未下载的模型置灰，不可选）。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认回调：参数为 (选中的提示词, 选中的模型)。
 */
@Composable
fun NewConversationDialog(
  defaultModel: Model?,
  availableModels: List<Model>,
  isDownloaded: (Model) -> Boolean,
  onDismiss: () -> Unit,
  onConfirm: (PresetPrompt, Model) -> Unit,
) {
  // 步骤 0 = 选提示词；步骤 1 = 选模型。
  var step by remember { mutableStateOf(0) }
  var selectedPrompt by remember { mutableStateOf<PresetPrompt?>(null) }
  var selectedModel by remember { mutableStateOf(defaultModel) }
  // 是否正在展示「非默认模型」的二次确认框。
  var pendingNonDefaultModel by remember { mutableStateOf<Model?>(null) }

  when (step) {
    0 -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择提示词") },
        text = {
          Column(
            modifier =
              Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            for ((category, prompts) in PresetPrompts.grouped()) {
              Text(
                text = category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
              )
              for (p in prompts) {
                val selected = selectedPrompt == p
                Row(
                  modifier =
                    Modifier.fillMaxWidth().clickable { selectedPrompt = p }.padding(vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  RadioButton(selected = selected, onClick = { selectedPrompt = p })
                  Spacer(modifier = Modifier.width(4.dp))
                  Column {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium)
                    if (p.prompt.isNotEmpty()) {
                      Text(
                        p.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                      )
                    }
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              step = 1
              // 进入选模型步骤时，模型默认选中默认模型。
            }
          ) {
            Text("下一步")
          }
        },
        dismissButton = {
          TextButton(onClick = onDismiss) { Text("取消") }
        },
      )
    }
    1 -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
          Column(
            modifier =
              Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = "三个采样参数（Top-K / Top-P / 温度）将使用模型的默认设置。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            for (model in availableModels) {
              val isDefault = model.name == defaultModel?.name
              val downloaded = isDownloaded(model)
              val selected = selectedModel?.name == model.name
              Row(
                modifier =
                  Modifier.fillMaxWidth()
                    .clickable(enabled = downloaded) { selectedModel = model }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(
                  selected = selected,
                  onClick = { if (downloaded) selectedModel = model },
                  enabled = downloaded,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      model.displayName.ifBlank { model.name },
                      style = MaterialTheme.typography.bodyMedium,
                      color =
                        if (downloaded) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isDefault) {
                      Spacer(modifier = Modifier.width(6.dp))
                      Text(
                        "默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                    if (!downloaded) {
                      Spacer(modifier = Modifier.width(6.dp))
                      Text(
                        "未下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                      )
                    }
                  }
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(
            onClick = {
              val prompt = selectedPrompt ?: PresetPrompts.DEFAULT
              val model = selectedModel ?: defaultModel
              if (model != null) {
                // 非默认模型 → 二次确认（提示耗时）。
                if (defaultModel != null && model.name != defaultModel.name) {
                  pendingNonDefaultModel = model
                } else {
                  onConfirm(prompt, model)
                }
              } else {
                onDismiss()
              }
            }
          ) {
            Text("开始对话")
          }
        },
        dismissButton = {
          TextButton(onClick = { step = 0 }) { Text("上一步") }
        },
      )
    }
  }

  // 非默认模型二次确认框。
  val pendingModel = pendingNonDefaultModel
  if (pendingModel != null) {
    AlertDialog(
      onDismissRequest = { pendingNonDefaultModel = null },
      title = { Text("切换模型耗时提示") },
      text = {
        Text(
          "你选择了非默认模型「${pendingModel.displayName.ifBlank { pendingModel.name }}」。\n\n" +
            "首次使用该模型需要下载 / 加载，可能需要较长时间。是否继续？"
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            val prompt = selectedPrompt ?: PresetPrompts.DEFAULT
            pendingNonDefaultModel = null
            onConfirm(prompt, pendingModel)
          }
        ) {
          Text("确认使用")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            // 继续用默认模型。
            pendingNonDefaultModel = null
            selectedModel = defaultModel
            val prompt = selectedPrompt ?: PresetPrompts.DEFAULT
            if (defaultModel != null) {
              onConfirm(prompt, defaultModel)
            }
          }
        ) {
          Text("继续用默认模型")
        }
      },
    )
  }
}
