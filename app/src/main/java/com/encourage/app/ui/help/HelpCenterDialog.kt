/*
 * Copyright 2026 Encourage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.encourage.app.ui.help

/*
 * Encourage — 帮助中心（H4 / 全局帮助体系）
 *
 * 【功能说明】
 * 应用内帮助中心对话框：快速上手、Agent 对话、云端模型、本地 API 服务器、
 * 任务中心、模型与跑分、常见问题、隐私说明八个分组，纯静态内容。
 *
 * 【使用方法】
 *   var showHelpCenter by remember { mutableStateOf(false) }
 *   if (showHelpCenter) {
 *     HelpCenterDialog(onDismissed = { showHelpCenter = false })
 *   }
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.encourage.app.R

/** 一个帮助分组的数据（图标 + 标题 + 正文）。 */
private data class HelpSection(val icon: ImageVector, val titleRes: Int, val bodyRes: Int)

/** 帮助中心全屏对话框。 */
@Composable
fun HelpCenterDialog(onDismissed: () -> Unit) {
  val sections =
    listOf(
      HelpSection(Icons.Rounded.Bolt, R.string.help_quick_title, R.string.help_quick_body),
      HelpSection(Icons.Rounded.SmartToy, R.string.help_agent_title, R.string.help_agent_body),
      HelpSection(Icons.Rounded.Cloud, R.string.help_cloud_title, R.string.help_cloud_body),
      HelpSection(Icons.Rounded.Dns, R.string.help_server_title, R.string.help_server_body),
      HelpSection(Icons.Rounded.Alarm, R.string.help_task_title, R.string.help_task_body),
      HelpSection(Icons.Rounded.Speed, R.string.help_benchmark_title, R.string.help_benchmark_body),
      HelpSection(Icons.Rounded.HelpOutline, R.string.help_faq_title, R.string.help_faq_body),
      HelpSection(Icons.Rounded.Lock, R.string.help_privacy_title, R.string.help_privacy_body),
    )

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        // 标题栏。
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            stringResource(R.string.help_center_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onDismissed) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
          }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 分组内容。
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          for (section in sections) {
            HelpSectionItem(section = section)
          }
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }
  }
}

/** 单个帮助分组：圆形图标 + 标题 + 正文。 */
@Composable
private fun HelpSectionItem(section: HelpSection, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth()) {
    Box(
      modifier =
        Modifier.size(38.dp)
          .background(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = CircleShape,
          ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        section.icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(20.dp),
      )
    }
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        stringResource(section.titleRes),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        stringResource(section.bodyRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
