/*
 * Encourage — 「另存为特调」对话框（会话列表改造 T02 / N7）
 *
 * 【功能说明】
 * 当用户修改了系统提示词 / 采样参数（topK、temperature 等）时，弹出本对话框询问
 * 「是否另存为新的特调配置」，允许用户修改记录别名后确认。
 *
 * 【设计要点】
 * 1. 本组件只做「对话框 UI + 回调」，**不接数据、不接导航**；接入点在 T04
 *    （LlmChatViewModel / ChatView 检测到提示词或参数变更时调用）。
 * 2. 纯 UI，无副作用：确认时把用户输入（已 trim）通过 [onConfirm] 回传。
 */

package com.encourage.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.encourage.app.R

/**
 * 「另存为新的特调配置」对话框。
 *
 * @param suggestedAlias 预填的别名（通常为「原别名 副本」）。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认回调，参数为用户最终输入的别名（已 trim，可能为空串）。
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
    title = { Text(stringResource(R.string.conversation_save_as_new_profile_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.conversation_save_as_new_profile_message))
        OutlinedTextField(
          value = alias,
          onValueChange = { alias = it },
          label = { Text(stringResource(R.string.conversation_save_as_new_profile_alias_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(alias.trim()) }) {
        Text(stringResource(R.string.conversation_save_as_new_profile_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.conversation_save_as_new_profile_cancel))
      }
    },
  )
}
