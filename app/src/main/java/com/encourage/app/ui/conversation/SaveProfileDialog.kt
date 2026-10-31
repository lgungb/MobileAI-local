/*
 * Encourage — 「另存为 Agent 分身」对话框（N7）
 *
 * 【功能说明】
 * 当用户在对话页点保存按钮时，把当前对话的模型底色 + 提示词 + 采样参数
 * 另存为一条「Agent 分身」记录（= 微信里的一个联系人）。
 *
 * 【设计要点】
 * 1. 弹窗内说明温度 / TopK / TopP 三个采样参数的作用，帮助用户理解调参影响。
 * 2. 支持重命名分身。
 * 3. 纯 UI，无副作用；确认时把用户输入通过 [onConfirm] 回传。
 */

package com.encourage.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「另存为 Agent 分身」对话框。
 *
 * @param suggestedAlias 预填的分身名（通常为「原别名 副本」或模型名）。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认回调，参数为用户最终输入的分身名（已 trim）。
 */
@Composable
fun SaveProfileDialog(
  suggestedAlias: String,
  onDismiss: () -> Unit,
  onConfirm: (alias: String) -> Unit,
) {
  var alias by remember { mutableStateOf(suggestedAlias) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("保存为 Agent 分身") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "把当前对话的提示词和采样参数存为一个分身，下次直接从列表进入。",
          style = MaterialTheme.typography.bodySmall,
        )
        // 三个采样参数说明
        Text(
          text = "采样参数说明（改了不用重启模型，下轮对话生效）：",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "• 温度（Temperature）：越高越随机、越有创意但也越不靠谱；越低越严谨、越保守。",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(start = 8.dp),
        )
        Text(
          text = "• Top-K：从概率最高的 K 个词里挑；K 小更收敛、更稳，K 大更发散。",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(start = 8.dp),
        )
        Text(
          text = "• Top-P：只从累计概率前 P 的词里挑；P 小更聚焦高频词，P 大更允许冷门词。",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(start = 8.dp),
        )
        OutlinedTextField(
          value = alias,
          onValueChange = { alias = it },
          label = { Text("分身名称") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(alias.trim()) }) {
        Text("保存")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("取消")
      }
    },
  )
}
