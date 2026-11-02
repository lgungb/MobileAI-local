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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.encourage.app.data.Model
import com.encourage.app.data.server.DEFAULT_SERVER_PORT
import com.encourage.app.ui.modelmanager.ModelManagerViewModel

/** 设置界面中的「本地 API 服务」区块。 */
@Composable
fun LocalServerSection(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: LocalServerViewModel = hiltViewModel(),
  modifier: Modifier = Modifier,
) {
  val settings by viewModel.settings.collectAsState()
  val running by viewModel.isRunning.collectAsState()

  // 【N10】可选模型 = 已下载的本地 LLM 模型。
  // 这里 collect 一次 uiState 是为了让「下载完成后下拉框自动多出一项」，不用重进设置页。
  val managerUiState by modelManagerViewModel.uiState.collectAsState()
  val availableModels =
    remember(managerUiState.tasks, managerUiState.modelDownloadStatus) {
      modelManagerViewModel.getAllDownloadedModels()
    }

  // 自动模式下实际生效的模型（= 列表第一个），用于状态提示与 curl 示例。
  val effectiveModel =
    availableModels.firstOrNull { it.name == settings.modelName }
      ?: if (settings.modelName.isEmpty()) availableModels.firstOrNull() else null
  val effectiveModelName = effectiveModel?.name.orEmpty()

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // 标题 + 总开关。
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.local_server_title),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
          // 【修复】running 状态的文案带 %1$d 端口占位符，原先没传参 → 直接显示 "运行中 · 端口 %1$d"。
          // 这里传 effectivePort（已按 1024~65535 做合法性回退），显示的即真正监听的端口。
          if (running) {
            stringResource(R.string.local_server_status_running, settings.effectivePort)
          } else {
            stringResource(R.string.local_server_status_stopped)
          },
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

      // 【N10】对外提供的模型（锁定模型）—— 外部客户端只能用这一个。
      ModelPickerRow(
        availableModels = availableModels,
        lockedName = settings.modelName,
        effectiveName = effectiveModelName,
        onSelect = { viewModel.setModelName(it) },
      )

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
          curlExample(settings.effectivePort, settings.token, effectiveModelName),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(10.dp),
        )
      }
    }
  }
}

/**
 * 模型选择器（下拉单行）。
 *
 * 【为什么不用普通 TextField】可选项来自「已下载模型」，是有限集合，用户不该手打模型名：
 * 打错了在服务侧就是 404，界面上还看不出哪里错了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerRow(
  availableModels: List<Model>,
  lockedName: String,
  effectiveName: String,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val autoLabel = stringResource(R.string.local_server_model_auto)
  val selectedLabel =
    availableModels.firstOrNull { it.name == lockedName }?.let { it.displayName.ifEmpty { it.name } }
      ?: autoLabel

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
      OutlinedTextField(
        value = selectedLabel,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.local_server_model)) },
        modifier =
          Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
      )
      ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
          text = { Text(autoLabel) },
          onClick = {
            onSelect("")
            expanded = false
          },
        )
        for (model in availableModels) {
          DropdownMenuItem(
            text = { Text(model.displayName.ifEmpty { model.name }) },
            onClick = {
              onSelect(model.name)
              expanded = false
            },
          )
        }
      }
    }

    if (availableModels.isEmpty()) {
      // 一个模型都没下载时，自动模式也没东西可服务 —— 明确说清怎么解决。
      Text(
        stringResource(R.string.local_server_model_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    } else {
      Text(
        if (effectiveName.isEmpty()) {
          stringResource(R.string.local_server_model_hint_empty)
        } else {
          stringResource(R.string.local_server_model_hint, effectiveName)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** curl 使用示例（模型名用当前生效的模型，避免示例本身就被服务端 404 掉）。 */
private fun curlExample(port: Int, token: String, modelName: String): String {
  val auth = if (token.isNotEmpty()) "  -H \"Authorization: Bearer $token\" \\\n" else ""
  val model = modelName.ifEmpty { "your-model-id" }
  return "curl http://127.0.0.1:$port/v1/chat/completions \\\n" +
    "  -H \"Content-Type: application/json\" \\\n" +
    auth +
    "  -d '{\"model\": \"$model\", \"messages\": [{\"role\": \"user\", \"content\": \"你好\"}], \"stream\": false}'"
}
