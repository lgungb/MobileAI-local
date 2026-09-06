/*
 * Encourage — Agent 管理器 BottomSheet（M5 / 需求 F1）
 *
 * 【功能】
 * - 列出内置 + 自定义 Agent（来源徽标区分）；
 * - 内置 Agent：只读查看，可「创建副本」后编辑；
 * - 自定义 Agent：新建 / 编辑 / 删除 / 从 URL 导入；
 * - 「使用」把 Agent 的系统提示词应用到当前 Agent Chat 会话
 *   （由 AgentChatScreen 回调 applySystemPromptChange 完成，自动重置会话）。
 */

package com.encourage.app.customtasks.agentchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.agents.AgentDefinition
import com.encourage.app.data.agents.AgentEntry

/** 编辑器打开的目标：null 表示新建。 */
private data class AgentEditTarget(val id: String?, val name: String, val description: String, val systemPrompt: String, val builtin: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentManagerBottomSheet(
  onDismiss: () -> Unit,
  onUseAgent: (AgentDefinition) -> Unit,
  viewModel: AgentManagerViewModel = hiltViewModel(),
) {
  val agents by viewModel.agents.collectAsState()
  val busy by viewModel.busy.collectAsState()
  val notice by viewModel.notice.collectAsState()
  var editTarget by remember { mutableStateOf<AgentEditTarget?>(null) }
  var showUrlDialog by remember { mutableStateOf(false) }

  LaunchedEffect(notice) {
    if (notice != null) {
      viewModel.consumeNotice()
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
      // 标题行 + 新建 / URL 导入。
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          text = stringResource(R.string.agent_manager_title),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { showUrlDialog = true }, enabled = !busy) {
          Icon(Icons.Rounded.CloudDownload, contentDescription = stringResource(R.string.agent_import_url))
        }
        IconButton(onClick = { editTarget = AgentEditTarget(null, "", "", "", false) }, enabled = !busy) {
          Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.agent_new))
        }
      }
      Text(
        text = stringResource(R.string.agent_manager_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(12.dp))

      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = agents, key = { it.definition.id }) { entry ->
          AgentItem(
            entry = entry,
            enabled = !busy,
            onUse = {
              onUseAgent(entry.definition)
              onDismiss()
            },
            onEdit = { def ->
              editTarget =
                AgentEditTarget(def.id, def.name, def.description, def.systemPrompt, def.builtin)
            },
            onDuplicate = { viewModel.duplicate(entry) },
            onDelete = { viewModel.delete(entry.definition.id) },
          )
        }
      }
    }
  }

  if (editTarget != null) {
    AgentEditDialog(
      target = editTarget!!,
      busy = busy,
      onDismiss = { editTarget = null },
      onSave = { name, description, systemPrompt ->
        val id = editTarget?.id ?: viewModel.newId()
        viewModel.save(
          AgentDefinition(
            id = id,
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            builtin = false,
          )
        )
        editTarget = null
      },
    )
  }

  if (showUrlDialog) {
    AgentUrlImportDialog(
      busy = busy,
      onDismiss = { showUrlDialog = false },
      onImport = { url ->
        showUrlDialog = false
        viewModel.importFromUrl(url)
      },
    )
  }
}

@Composable
private fun AgentItem(
  entry: AgentEntry,
  enabled: Boolean,
  onUse: () -> Unit,
  onEdit: (AgentDefinition) -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val def = entry.definition

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainer,
    onClick = { expanded = !expanded },
    enabled = enabled,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = def.name,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        Surface(
          shape = MaterialTheme.shapes.small,
          color =
            if (def.builtin) {
              MaterialTheme.colorScheme.secondaryContainer
            } else {
              MaterialTheme.colorScheme.tertiaryContainer
            },
        ) {
          Text(
            text =
              stringResource(
                if (def.builtin) R.string.agent_builtin_badge else R.string.agent_custom_badge
              ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
      if (def.description.isNotEmpty()) {
        Text(
          text = def.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (expanded) {
        Text(
          text = stringResource(R.string.agent_file_path_label) + "：" + entry.filePath,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Button(onClick = onUse, enabled = enabled) {
            Text(stringResource(R.string.agent_entry_use))
          }
          if (def.builtin) {
            TextButton(onClick = onDuplicate, enabled = enabled) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = null)
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.agent_entry_duplicate))
            }
          } else {
            TextButton(onClick = { onEdit(def) }, enabled = enabled) {
              Icon(Icons.Rounded.Edit, contentDescription = null)
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.agent_entry_edit))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
              Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.agent_entry_delete),
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
        Text(
          text = stringResource(R.string.agent_use_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun AgentEditDialog(
  target: AgentEditTarget,
  busy: Boolean,
  onDismiss: () -> Unit,
  onSave: (name: String, description: String, systemPrompt: String) -> Unit,
) {
  var name by remember { mutableStateOf(target.name) }
  var description by remember { mutableStateOf(target.description) }
  var systemPrompt by remember { mutableStateOf(target.systemPrompt) }
  val valid = name.isNotBlank() && systemPrompt.isNotBlank() && !busy

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        stringResource(
          if (target.id == null) R.string.agent_new else R.string.agent_entry_edit
        )
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(stringResource(R.string.agent_name_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text(stringResource(R.string.agent_desc_label)) },
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = systemPrompt,
          onValueChange = { systemPrompt = it },
          label = { Text(stringResource(R.string.agent_prompt_label)) },
          minLines = 4,
          maxLines = 10,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name.trim(), description.trim(), systemPrompt) }, enabled = valid) {
        Text(stringResource(R.string.agent_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
    },
  )
}

@Composable
private fun AgentUrlImportDialog(
  busy: Boolean,
  onDismiss: () -> Unit,
  onImport: (String) -> Unit,
) {
  var url by remember { mutableStateOf("") }

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.agent_import_url)) },
    text = {
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.agent_url_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(onClick = { onImport(url.trim()) }, enabled = url.isNotBlank() && !busy) {
        Text(stringResource(R.string.agent_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
    },
  )
}
