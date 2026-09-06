/*
 * Encourage — 外部 API 服务配置界面（M2 / 需求 B1）
 *
 * 【功能说明】
 * 设置界面中的「外部 API 服务」区块，以及独立的配置管理对话框。
 * 用户在这里管理多套第三方大模型 API：增删改、切换当前使用的一套、从预设快速创建。
 *
 * 【交互设计要点】
 * 1. **默认关闭**：总开关「使用外部 API」默认关闭，本地端侧模型优先。
 *    本应用的核心卖点是隐私不出手机，联网必须是用户主动开启的增强能力。
 * 2. **联网提示写在开关下面**，而不是藏在二级页面里——避免用户"不知不觉"就把数据发了出去。
 * 3. 点击配置行即切换当前使用的一套；正在使用的那套显示「当前使用」徽标。
 * 4. 采样参数默认留空，交由服务端策略决定，避免默认值与服务商打架。
 *
 * 【文件组成】
 * - [ApiProviderSection]      嵌入设置对话框的区块（开关 + 摘要 + 管理入口）
 * - [ApiProviderManagerDialog] 配置管理对话框（列表 / 新增 / 编辑 / 删除 / 预设）
 * - [AddOrEditApiProviderDialog] 单条配置的编辑表单
 * - [PresetList]              内置服务预设列表
 *
 * 【使用方法】
 *   // 在设置对话框内
 *   ApiProviderSection(viewModel = apiProviderViewModel) { showApiManager = true }
 *
 *   // 独立弹出管理页
 *   if (showApiManager) {
 *     ApiProviderManagerDialog(viewModel = apiProviderViewModel) { showApiManager = false }
 *   }
 */

package com.encourage.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.api.ApiPreset
import com.encourage.app.data.api.ApiPresets
import com.encourage.app.data.api.ApiProviderConfig
import com.encourage.app.data.api.ConnectionTestResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 设置界面中的「外部 API 服务」区块。
 *
 * @param viewModel 配置 ViewModel，通常由 hiltViewModel() 提供。
 * @param onManageClicked 点击「管理」时的回调，由调用方弹出管理对话框。
 */
@Composable
fun ApiProviderSection(
  viewModel: ApiProviderViewModel = hiltViewModel(),
  onManageClicked: () -> Unit,
) {
  val useRemote by viewModel.useRemoteApi.collectAsState()
  val active by viewModel.activeConfig.collectAsState()

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      stringResource(R.string.api_provider_section_title),
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        stringResource(R.string.api_provider_use_remote_title),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
      )
      Switch(checked = useRemote, onCheckedChange = { viewModel.setUseRemoteApi(it) })
    }

    Text(
      stringResource(R.string.api_provider_use_remote_desc),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
      active?.name?.takeIf { it.isNotEmpty() }?.let { "$it · ${active?.modelId}" }
        ?: stringResource(R.string.api_provider_none),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(onClick = onManageClicked) {
      Text(stringResource(R.string.api_provider_manage))
    }
  }
}

/**
 * 外部 API 配置管理对话框。
 *
 * 内含配置列表、新增按钮，以及内置服务预设列表。
 */
@Composable
fun ApiProviderManagerDialog(
  viewModel: ApiProviderViewModel = hiltViewModel(),
  onDismissed: () -> Unit,
) {
  val configs by viewModel.configs.collectAsState()
  val active by viewModel.activeConfig.collectAsState()
  val testingId by viewModel.testingId.collectAsState()
  val testResults by viewModel.testResults.collectAsState()

  // 正在编辑的配置；非 null 时弹出编辑表单。
  var editingConfig by remember { mutableStateOf<ApiProviderConfig?>(null) }

  Dialog(onDismissRequest = onDismissed) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          stringResource(R.string.api_provider_section_title),
          style = MaterialTheme.typography.titleLarge,
        )

        if (configs.isEmpty()) {
          Text(
            stringResource(R.string.api_provider_empty_list),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          for (config in configs) {
            ApiProviderRow(
              config = config,
              isActive = active?.id == config.id,
              isTesting = testingId == config.id,
              testResult = testResults[config.id],
              onActivate = { viewModel.setActive(config.id) },
              onEdit = { editingConfig = config },
              onDelete = { viewModel.delete(config.id) },
              onTest = { viewModel.testConnection(config) },
            )
            HorizontalDivider()
          }
        }

        Button(
          onClick = { editingConfig = ApiProviderConfig() },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = null)
          Text(stringResource(R.string.api_provider_add))
        }

        Text(
          stringResource(R.string.api_provider_preset_title),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        PresetList { preset -> editingConfig = preset.createConfig() }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.close)) }
        }
      }
    }
  }

  editingConfig?.let { config ->
    AddOrEditApiProviderDialog(
      initial = config,
      onDismissed = { editingConfig = null },
      onSaved = { saved ->
        viewModel.save(saved)
        editingConfig = null
      },
      onTest = { viewModel.runConnectionTest(it) },
    )
  }
}

/** 单条配置的行：点击切换使用，右侧提供测试、编辑与删除。 */
@Composable
private fun ApiProviderRow(
  config: ApiProviderConfig,
  isActive: Boolean,
  isTesting: Boolean,
  testResult: ConnectionTestResult?,
  onActivate: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onTest: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onActivate).padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(config.name.ifEmpty { config.modelId }, style = MaterialTheme.typography.bodyLarge)
        Text(
          config.modelId,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (config.baseUrl.startsWith("http://", ignoreCase = true)) {
          Text(
            stringResource(R.string.api_provider_cleartext_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      if (isActive) {
        Text(
          stringResource(R.string.api_provider_active),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 6.dp),
        )
      }
      OutlinedButton(onClick = onTest, enabled = !isTesting) {
        Text(stringResource(R.string.api_provider_test))
      }
      IconButton(onClick = onEdit) {
        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.api_provider_edit))
      }
      IconButton(onClick = onDelete) {
        Icon(
          Icons.Rounded.Delete,
          contentDescription = stringResource(R.string.api_provider_delete_cd),
        )
      }
    }

    // 自检结果：测试中与有结果时各显示一行，测试完就留在那里，关闭对话框前不清除。
    if (isTesting) {
      TestResultRow(
        icon = {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        },
        text = stringResource(R.string.api_provider_testing),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else if (testResult != null) {
      when (testResult) {
        is ConnectionTestResult.Success ->
          TestResultRow(
            icon = { Icon(Icons.Rounded.Check, contentDescription = null) },
            text =
              stringResource(
                R.string.api_provider_test_success,
                testResult.latencyMs,
                testResult.sample.take(40).ifBlank { "—" },
              ),
            color = MaterialTheme.colorScheme.primary,
          )
        is ConnectionTestResult.Failure ->
          TestResultRow(
            icon = { Icon(Icons.Rounded.Error, contentDescription = null) },
            text = testResult.message,
            color = MaterialTheme.colorScheme.error,
          )
      }
    }
  }
}

/** 自检结果行：一个小图标（或转圈）+ 一句说明。 */
@Composable
private fun TestResultRow(
  icon: @Composable () -> Unit,
  text: String,
  color: Color,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
  }
}

/** 新增或编辑一条配置的表单对话框。 */
@Composable
private fun AddOrEditApiProviderDialog(
  initial: ApiProviderConfig,
  onDismissed: () -> Unit,
  onSaved: (ApiProviderConfig) -> Unit,
  onTest: suspend (ApiProviderConfig) -> ConnectionTestResult,
) {
  var name by remember { mutableStateOf(initial.name) }
  var baseUrl by remember { mutableStateOf(initial.baseUrl) }
  var apiKey by remember { mutableStateOf(initial.apiKey) }
  var modelId by remember { mutableStateOf(initial.modelId) }
  var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
  var temperature by remember { mutableStateOf(if (initial.temperature > 0f) initial.temperature.toString() else "") }
  var topP by remember { mutableStateOf(if (initial.topP > 0f) initial.topP.toString() else "") }
  var maxTokens by remember { mutableStateOf(if (initial.maxTokens > 0) initial.maxTokens.toString() else "") }
  var showError by remember { mutableStateOf(false) }

  // 自检状态与句柄。放在这里而不是 ViewModel 里，是因为表单里的配置还没保存，
  // 一旦保存失败或用户取消，这些中间状态也应该随之消失。
  var testState by remember { mutableStateOf<ConnectionTestResult?>(null) }
  var isTesting by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  var testJob by remember { mutableStateOf<Job?>(null) }

  /** 把表单当前值组装成配置对象；保存与自检都用它，保证测的就是要存的。 */
  fun buildConfig(): ApiProviderConfig {
    val trimmedUrl = baseUrl.trim()
    val trimmedModel = modelId.trim()
    return initial.copy(
      name = name.trim().ifEmpty { trimmedModel },
      baseUrl = trimmedUrl,
      apiKey = apiKey.trim(),
      modelId = trimmedModel,
      systemPrompt = systemPrompt,
      temperature = temperature.toFloatOrNull() ?: 0f,
      topP = topP.toFloatOrNull() ?: 0f,
      maxTokens = maxTokens.toIntOrNull() ?: 0,
    )
  }

  fun runTest() {
    val trimmedUrl = baseUrl.trim()
    val trimmedModel = modelId.trim()
    if (trimmedUrl.isEmpty() || trimmedModel.isEmpty()) {
      showError = true
      return
    }
    testJob?.cancel()
    isTesting = true
    testState = null
    testJob =
      scope.launch {
        testState = onTest(buildConfig())
        isTesting = false
      }
  }

  Dialog(onDismissRequest = onDismissed) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          if (initial.name.isEmpty() && initial.baseUrl.isEmpty()) {
            stringResource(R.string.api_provider_add)
          } else {
            stringResource(R.string.api_provider_edit)
          },
          style = MaterialTheme.typography.titleLarge,
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(stringResource(R.string.api_provider_name_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = baseUrl,
          onValueChange = {
            baseUrl = it
            testState = null
          },
          label = { Text(stringResource(R.string.api_provider_base_url_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            testState = null
          },
          label = { Text(stringResource(R.string.api_provider_api_key_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = modelId,
          onValueChange = {
            modelId = it
            testState = null
          },
          label = { Text(stringResource(R.string.api_provider_model_id_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = systemPrompt,
          onValueChange = { systemPrompt = it },
          label = { Text(stringResource(R.string.api_provider_system_prompt_label)) },
          modifier = Modifier.fillMaxWidth(),
        )

        Text(
          stringResource(R.string.api_provider_advanced_title),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
          stringResource(R.string.api_provider_advanced_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = temperature,
            onValueChange = { temperature = it },
            label = { Text(stringResource(R.string.api_provider_temperature_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = topP,
            onValueChange = { topP = it },
            label = { Text(stringResource(R.string.api_provider_top_p_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        OutlinedTextField(
          value = maxTokens,
          onValueChange = { maxTokens = it },
          label = { Text(stringResource(R.string.api_provider_max_tokens_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        if (showError) {
          Text(
            stringResource(R.string.api_provider_invalid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        // 自检结果与按钮。放在「保存」左边，让用户养成「先测再存」的习惯。
        if (isTesting) {
          TestResultRow(
            icon = {
              CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            },
            text = stringResource(R.string.api_provider_testing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          // 用 let 而不是 if (testState != null)：testState 是 var，
          // 智能转换在多线程场景下不成立，when 会要求补 null 分支。
          testState?.let { result ->
            when (result) {
              is ConnectionTestResult.Success ->
                TestResultRow(
                  icon = { Icon(Icons.Rounded.Check, contentDescription = null) },
                  text =
                    stringResource(
                      R.string.api_provider_test_success,
                      result.latencyMs,
                      result.sample.take(40).ifBlank { "—" },
                    ),
                  color = MaterialTheme.colorScheme.primary,
                )
              is ConnectionTestResult.Failure ->
                TestResultRow(
                  icon = { Icon(Icons.Rounded.Error, contentDescription = null) },
                  text = result.message,
                  color = MaterialTheme.colorScheme.error,
                )
            }
          }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) }
          OutlinedButton(onClick = { runTest() }, enabled = !isTesting) {
            Text(stringResource(R.string.api_provider_test))
          }
          Button(
            onClick = {
              val trimmedUrl = baseUrl.trim()
              val trimmedModel = modelId.trim()
              if (trimmedUrl.isEmpty() || trimmedModel.isEmpty()) {
                showError = true
                return@Button
              }
              onSaved(buildConfig())
            }
          ) {
            Text(stringResource(R.string.save))
          }
        }
      }
    }
  }
}

/** 内置服务预设列表，点击即用该预设打开编辑表单。 */
@Composable
private fun PresetList(onPicked: (ApiPreset) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (preset in ApiPresets.ALL) {
      Row(
        modifier =
          Modifier.fillMaxWidth().clickable { onPicked(preset) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(preset.displayName, style = MaterialTheme.typography.bodyLarge)
          Text(
            preset.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(Icons.Rounded.Add, contentDescription = null)
      }
    }
  }
}
