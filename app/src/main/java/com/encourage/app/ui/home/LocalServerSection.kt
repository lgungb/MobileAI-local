/*
 * Encourage — 本地 API 服务设置界面（M4 / 需求 B2）
 *
 * 【功能说明】
 * 设置对话框内的「本地 API 服务」区块：总开关、端口、Token、局域网访问、
 * 运行状态与 curl 使用示例。
 *
 * 【交互设计要点】
 * 1. 开关即启停：打开后服务立刻监听，设置页显示运行状态（端口 / 网卡）；
 * 2. 端口用数字输入，范围 1~65535，非法输入保存时回退默认 8010；
 * 3. Token 留空 = 不鉴权，界面提示「仅建议在可信网络使用」；
 * 4. 打开「局域网访问」时若 Token 为空，显示安全警告（建议设置 Token）；
 * 5. curl 示例直接展示，用户复制即可用。
 *
 * 【使用方法】
 *   // 在设置对话框内
 *   LocalServerSection(viewModel = localServerViewModel)
 */

package com.encourage.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.server.DEFAULT_SERVER_PORT

/** 设置界面中的「本地 API 服务」区块。 */
@Composable
fun LocalServerSection(
  viewModel: LocalServerViewModel = hiltViewModel(),
  modifier: Modifier = Modifier,
) {
  val settings by viewModel.settings.collectAsState()
  val running by viewModel.isRunning.collectAsState()

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // 标题 + 总开关。
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.local_server_title),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
          stringResource(
            if (running) R.string.local_server_status_running
            else R.string.local_server_status_stopped
          ),
          style = MaterialTheme.typography.bodySmall,
          color =
            if (running) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = settings.enabled, onCheckedChange = { viewModel.setEnabled(it) })
    }

    if (settings.enabled) {
      HorizontalDivider()

      // 端口。
      var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
      OutlinedTextField(
        value = portText,
        onValueChange = { input ->
          portText = input.filter(Char::isDigit).take(5)
          portText.toIntOrNull()?.let { viewModel.setPort(it) }
        },
        label = { Text(stringResource(R.string.local_server_port)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      // Token。
      var tokenText by remember(settings.token) { mutableStateOf(settings.token) }
      OutlinedTextField(
        value = tokenText,
        onValueChange = { tokenText = it; viewModel.setToken(it) },
        label = { Text(stringResource(R.string.local_server_token)) },
        supportingText = { Text(stringResource(R.string.local_server_token_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      // 局域网访问。
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.local_server_lan), style = MaterialTheme.typography.bodyMedium)
          Text(
            stringResource(R.string.local_server_lan_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.bindAllInterfaces,
          onCheckedChange = { viewModel.setBindAllInterfaces(it) },
        )
      }

      // 局域网 + 无 Token → 安全警告。
      if (settings.bindAllInterfaces && settings.token.isEmpty()) {
        Text(
          stringResource(R.string.local_server_lan_no_token_warn),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      HorizontalDivider()

      // 使用说明（curl 示例）。
      Text(
        stringResource(R.string.local_server_usage_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      Card(modifier = Modifier.fillMaxWidth()) {
        Text(
          curlExample(settings.port, settings.token),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(10.dp),
        )
      }
    }
  }
}

/** curl 使用示例。 */
private fun curlExample(port: Int, token: String): String {
  val auth = if (token.isNotEmpty()) "  -H \"Authorization: Bearer $token\" \\\n" else ""
  return "curl http://127.0.0.1:$port/v1/chat/completions \\\n" +
    "  -H \"Content-Type: application/json\" \\\n" +
    auth +
    "  -d '{\"model\": \"gemma\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}], \"stream\": false}'"
}
