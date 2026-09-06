/*
 * Encourage — Prompt Lab 单选项下拉按钮（M1 增强：支持自定义选项）
 *
 * 【功能说明】
 * Prompt Lab 顶部那排「语气：正式 ▾」样式的下拉选择器。
 * 一个按钮对应模板配置里的一个 PromptTemplateSingleSelectInputEditor。
 *
 * 【本次增强（需求 H3）】
 * 原实现只能展示枚举里写死的内置选项；现在额外支持：
 *   1. 展示用户自定义选项（extraOptions），与内置项混排在同一个菜单里；
 *   2. 菜单底部提供「添加自定义…」，弹出输入框，确认后回调 onAddCustomOption；
 *   3. 自定义项右侧有删除图标，点击回调 onRemoveCustomOption。
 * 内置项不可删除，避免用户误删后无法恢复。
 *
 * 【使用方法】
 *   SingleSelectButton(
 *     config = inputEditor as PromptTemplateSingleSelectInputEditor,
 *     onSelected = { inputEditorValues[inputEditor.key] = it },
 *     extraOptions = customOptions.map { SelectOption(key = it, labelFallback = it) },
 *     onAddCustomOption = { viewModel.addCustomPromptOption(type, inputEditor.key, it) },
 *     onRemoveCustomOption = { viewModel.removeCustomPromptOption(type, inputEditor.key, it) },
 *   )
 *
 * 【注意事项】
 * 自定义选项的 key 就是用户输入的原文，会直接拼进最终提示词，
 * 因此 genFullPrompt 里对 tone/style 做的 lowercase() 处理对中文是无害的。
 */

package com.encourage.app.ui.llmsingleturn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.encourage.app.R

/**
 * 单选项下拉按钮。
 *
 * @param config 模板里的单选项编辑器配置（内置选项 + 默认选中项）。
 * @param onSelected 选中某项时的回调，参数为该选项的 key。
 * @param modifier 外部修饰符。
 * @param extraOptions 用户自定义选项，追加在内置选项之后展示。
 * @param onAddCustomOption 用户确认新增自定义选项时回调，参数为选项名。
 * @param onRemoveCustomOption 用户删除某个自定义选项时回调，参数为选项名。
 */
@Composable
fun SingleSelectButton(
  config: PromptTemplateSingleSelectInputEditor,
  onSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
  extraOptions: List<SelectOption> = listOf(),
  onAddCustomOption: (String) -> Unit = {},
  onRemoveCustomOption: (String) -> Unit = {},
) {
  var showMenu by remember { mutableStateOf(false) }
  var showAddDialog by remember { mutableStateOf(false) }
  var selectedOptionKey by remember { mutableStateOf(config.defaultOptionKey) }

  LaunchedEffect(config) { selectedOptionKey = config.defaultOptionKey }

  val allOptions = remember(config, extraOptions) { config.options + extraOptions }
  val selectedOption = allOptions.find { it.key == selectedOptionKey }

  val cdRemoveCustomOption = stringResource(R.string.cd_remove_custom_option)
  val selectedOptionLabel =
    selectedOption?.let { if (it.labelRes != 0) stringResource(it.labelRes) else it.labelFallback }
      ?: stringResource(R.string.prompt_lab_custom_option_none)
  val label = stringResource(config.labelRes)

  Box(modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      modifier =
        Modifier.clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.secondaryContainer)
          .clickable { showMenu = true }
          .padding(vertical = 4.dp, horizontal = 6.dp)
          .padding(start = 8.dp),
    ) {
      Text("$label: $selectedOptionLabel", style = MaterialTheme.typography.labelLarge)
      Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
    }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
      // 内置选项：只读，不可删除。
      for (option in config.options) {
        val optionLabel =
          if (option.labelRes != 0) stringResource(option.labelRes) else option.labelFallback
        DropdownMenuItem(
          text = { Text(optionLabel) },
          onClick = {
            selectedOptionKey = option.key
            showMenu = false
            onSelected(option.key)
          },
        )
      }

      // 用户自定义选项：可删除。
      if (extraOptions.isNotEmpty()) {
        HorizontalDivider()
        for (option in extraOptions) {
          DropdownMenuItem(
            text = { Text(option.labelFallback.ifEmpty { option.key }) },
            onClick = {
              selectedOptionKey = option.key
              showMenu = false
              onSelected(option.key)
            },
            trailingIcon = {
              IconButton(
                onClick = {
                  // 删除后若当前正选中它，回退到默认选项，避免出现空选中态。
                  if (selectedOptionKey == option.key) {
                    selectedOptionKey = config.defaultOptionKey
                    onSelected(config.defaultOptionKey)
                  }
                  onRemoveCustomOption(option.key)
                }
              ) {
                Icon(Icons.Rounded.Delete, contentDescription = cdRemoveCustomOption)
              }
            },
          )
        }
      }

      // 添加自定义选项入口。
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text(stringResource(R.string.prompt_lab_add_custom_option)) },
        onClick = {
          showMenu = false
          showAddDialog = true
        },
        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
      )
    }
  }

  if (showAddDialog) {
    AddCustomOptionDialog(
      existingKeys = allOptions.map { it.key },
      onDismiss = { showAddDialog = false },
      onConfirm = { value ->
        showAddDialog = false
        onAddCustomOption(value)
        // 新增后直接选中，省去再点一次菜单。
        selectedOptionKey = value
        onSelected(value)
      },
    )
  }
}

/**
 * 「添加自定义选项」输入弹窗。
 *
 * @param existingKeys 已存在的选项 key，用于查重提示。
 * @param onDismiss 关闭弹窗。
 * @param onConfirm 确认新增，参数为去重空格后的选项名（已保证非空、不重复）。
 */
@Composable
private fun AddCustomOptionDialog(
  existingKeys: List<String>,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var text by remember { mutableStateOf("") }
  val trimmed = text.trim()
  val isDuplicate = trimmed.isNotEmpty() && existingKeys.any { it.equals(trimmed, ignoreCase = true) }
  val canConfirm = trimmed.isNotEmpty() && !isDuplicate

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.prompt_lab_add_custom_option)) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.prompt_lab_custom_option_name_label)) },
        singleLine = true,
        isError = isDuplicate,
        supportingText = {
          if (isDuplicate) {
            Text(stringResource(R.string.prompt_lab_custom_option_duplicate))
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(enabled = canConfirm, onClick = { onConfirm(trimmed) }) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
