/*
 * Encourage — 保存会话对话框（M1）
 *
 * 【功能说明】
 * 导出会话前让用户确认（并可以修改）文件名。
 * 文件名由模型自动生成的会话标题转换而来，用户可直接保存也可改成自己想要的名字。
 *
 * 【设计说明】
 * - 文件名输入框不显示 ".md" 后缀，保存时由调用方自动补全，避免用户误删后缀。
 * - 空文件名时禁用保存按钮，防止写入无名文件。
 * - 采用 AlertDialog 而非自定义弹窗：与系统风格一致，且无需额外的状态管理。
 *
 * 【使用方法】
 *   SaveConversationDialog(
 *     initialFileName = "与 Gemma 的对话",
 *     onDismissed = { /* 取消 */ },
 *     onConfirmed = { fileName -> /* 拿到文件名去启动系统保存流程 */ },
 *   )
 */

package com.encourage.app.ui.common.chat

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
import androidx.compose.ui.res.stringResource
import com.encourage.app.R

/**
 * 保存会话对话框。
 *
 * @param initialFileName 建议的文件名（不含扩展名），通常来自模型自动生成的标题。
 * @param onDismissed 取消或关闭对话框时回调。
 * @param onConfirmed 点击保存时回调，参数为最终文件名（不含 ".md" 后缀）。
 */
@Composable
fun SaveConversationDialog(
  initialFileName: String,
  onDismissed: () -> Unit,
  onConfirmed: (String) -> Unit,
) {
  var fileName by remember(initialFileName) { mutableStateOf(initialFileName) }

  AlertDialog(
    onDismissRequest = onDismissed,
    title = { Text(stringResource(R.string.export_dialog_title)) },
    text = {
      OutlinedTextField(
        value = fileName,
        onValueChange = { fileName = it },
        label = { Text(stringResource(R.string.export_dialog_file_name_label)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        // 说明：扩展名会自动补全为 .md。
        supportingText = { Text(stringResource(R.string.export_dialog_extension_hint)) },
      )
    },
    confirmButton = {
      TextButton(
        enabled = fileName.isNotBlank(),
        onClick = { onConfirmed(fileName.trim()) },
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) } },
  )
}
