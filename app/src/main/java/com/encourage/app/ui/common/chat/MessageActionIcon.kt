/*
 * Encourage — 消息操作小图标按钮（M1）
 *
 * 【功能说明】
 * 对话气泡下方的紧凑型图标按钮（复制 / 朗读 / 删除），用于承载单条消息的操作。
 * 与已有的 MessageActionButton（带文字标签的大按钮）互补：
 *   - MessageActionButton：强操作，如「重新运行」「基准测试」，需要文字说明；
 *   - MessageActionIcon：轻操作，图标即可表意，节省界面空间。
 *
 * 【使用方法】
 *   MessageActionIcon(
 *     icon = Icons.Rounded.ContentCopy,
 *     contentDescription = stringResource(R.string.copy),
 *     onClick = { copyToClipboard(message.content) },
 *   )
 */

package com.encourage.app.ui.common.chat

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/**
 * 消息操作小图标按钮。
 *
 * @param icon 按钮图标。
 * @param contentDescription 无障碍描述，必须提供（界面上无文字标签）。
 * @param onClick 点击回调。
 */
@Composable
fun MessageActionIcon(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  IconButton(onClick = onClick, modifier = modifier.size(28.dp)) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      modifier = Modifier.size(18.dp),
    )
  }
}
