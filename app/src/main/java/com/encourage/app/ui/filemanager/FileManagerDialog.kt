/*
 * Encourage — 文件管理器界面（M6-H6）
 *
 * 【功能说明】
 * 全屏对话框形式的安全文件管理器：用户通过系统「选择文件夹」授权一个根目录，
 * 之后可浏览该目录树、进入子目录、返回上级，并对文件/文件夹执行
 * 打开 / 分享 / 重命名 / 删除 / 新建文件夹。
 *
 * 【交互设计要点】
 * 1. 首次使用需先授权根目录（OpenDocumentTree），授权持久化，之后直接打开。
 * 2. 列表文件夹在前、文件在后，均按名称排序；点击文件夹进入，点击文件弹操作菜单。
 * 3. 顶部工具栏：返回上级 / 路径显示 / 回根目录 / 新建文件夹。
 *
 * 【使用方法】
 *   if (showFileManager) {
 *     FileManagerDialog(onDismissed = { showFileManager = false })
 *   }
 */

package com.encourage.app.ui.filemanager

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.filemanager.FileEntry

/** 文件管理器全屏对话框。 */
@Composable
fun FileManagerDialog(
  viewModel: FileManagerViewModel = hiltViewModel(),
  onDismissed: () -> Unit,
) {
  val authorized by viewModel.authorized.collectAsState()
  val entries by viewModel.entries.collectAsState()
  val currentPath by viewModel.currentPath.collectAsState()
  val message by viewModel.message.collectAsState()

  val context = LocalContext.current
  val treeLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        // 持久化授权，重启后仍可访问。
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
              android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        viewModel.authorize(uri)
      }
    }

  // 操作弹窗状态。
  var targetFile by remember { mutableStateOf<FileEntry?>(null) }
  var menuFor by remember { mutableStateOf<FileEntry?>(null) }
  var showRename by remember { mutableStateOf<FileEntry?>(null) }
  var showDelete by remember { mutableStateOf<FileEntry?>(null) }
  var showNewFolder by remember { mutableStateOf(false) }
  // 【N4】文本编辑器：查看 / 编辑 txt 文件。
  var textEditTarget by remember { mutableStateOf<FileEntry?>(null) }
  var textEditorState by remember { mutableStateOf<TextEditorState>(TextEditorState.Closed) }

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 标题 + 关闭。
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            stringResource(R.string.file_manager_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.close)) }
        }

        if (!authorized) {
          // 未授权：引导选择根目录。
          Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              stringResource(R.string.file_manager_need_auth),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 16.dp),
            )
            Button(onClick = { treeLauncher.launch(null) }) {
              Text(stringResource(R.string.file_manager_choose_root))
            }
          }
        } else {
          // 已授权：工具栏 + 列表。
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.navigateUp() }) {
              Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            IconButton(onClick = { viewModel.goToRoot() }) {
              Icon(Icons.Rounded.Home, contentDescription = null)
            }
            IconButton(onClick = { viewModel.refreshCurrent() }) {
              Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
            Text(
              currentPath.ifEmpty { "/" },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showNewFolder = true }) {
              Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

          if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                stringResource(R.string.file_manager_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              items(entries, key = { it.uri.toString() }) { entry ->
                FileRow(
                  entry = entry,
                  onClick = {
                    if (entry.isDirectory) {
                      viewModel.navigateInto(entry)
                    } else if (isTextFile(entry)) {
                      // 文本文件直接进入查看 / 编辑。
                      textEditTarget = entry
                      textEditorState = TextEditorState.Loading
                    } else {
                      menuFor = entry
                    }
                  },
                  onMore = { menuFor = entry },
                )
                HorizontalDivider()
              }
            }
          }
        }

        // 文件操作菜单。
        menuFor?.let { file ->
          FileActionMenu(
            entry = file,
            onDismiss = { menuFor = null },
            onOpen = { viewModel.open(file); menuFor = null },
            onShare = { viewModel.share(file); menuFor = null },
            onEdit = {
              if (isTextFile(file)) {
                textEditTarget = file
                textEditorState = TextEditorState.Loading
              }
              menuFor = null
            },
            onRename = { showRename = file; menuFor = null },
            onDelete = { showDelete = file; menuFor = null },
          )
        }

        // 一次性提示。
        message?.let {
          AlertDialog(
            onDismissRequest = { viewModel.consumeMessage() },
            confirmButton = {
              TextButton(onClick = { viewModel.consumeMessage() }) {
                Text(stringResource(R.string.ok))
              }
            },
            text = {
              Text(
                stringResource(
                  when (it) {
                    "create_failed" -> R.string.file_manager_create_failed
                    "rename_failed" -> R.string.file_manager_rename_failed
                    "delete_failed" -> R.string.file_manager_delete_failed
                    else -> R.string.file_manager_op_failed
                  }
                )
              )
            },
          )
        }
      }
    }
  }

  // 新建文件夹对话框。
  if (showNewFolder) {
    NameInputDialog(
      title = stringResource(R.string.file_manager_new_folder),
      confirmLabel = stringResource(R.string.ok),
      initial = "",
      onDismissed = { showNewFolder = false },
      onConfirmed = { name ->
        viewModel.createDirectory(name)
        showNewFolder = false
      },
    )
  }

  // 重命名对话框。
  showRename?.let { file ->
    NameInputDialog(
      title = stringResource(R.string.file_manager_rename),
      confirmLabel = stringResource(R.string.ok),
      initial = file.name,
      onDismissed = { showRename = null },
      onConfirmed = { name ->
        if (name.isNotBlank() && name != file.name) viewModel.rename(file, name)
        showRename = null
      },
    )
  }

  // 删除确认对话框。
  showDelete?.let { file ->
    AlertDialog(
      onDismissRequest = { showDelete = null },
      title = { Text(stringResource(R.string.file_manager_delete_confirm)) },
      text = { Text(file.name) },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.delete(file)
            showDelete = null
          },
        ) {
          Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDelete = null }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  // 【N4】文本查看 / 编辑对话框。
  textEditTarget?.let { file ->
    TextEditorDialog(
      entry = file,
      state = textEditorState,
      onLoad = { viewModel.readTextFile(file) { content -> textEditorState = TextEditorState.Loaded(content) } },
      onSave = { newContent ->
        viewModel.writeTextFile(file, newContent) { ok ->
          textEditorState = TextEditorState.Saved(ok)
        }
      },
      onDismissed = {
        textEditTarget = null
        textEditorState = TextEditorState.Closed
      },
    )
  }
}

/** 文本查看 / 编辑对话框的 UI 状态。 */
private sealed class TextEditorState {
  data object Closed : TextEditorState()
  data object Loading : TextEditorState()
  data class Loaded(val content: String?) : TextEditorState()
  data class Saved(val ok: Boolean) : TextEditorState()
}

/** 是否为可编辑的文本文件（.txt / .md / .log / .json 等常见文本扩展名）。 */
private fun isTextFile(entry: FileEntry): Boolean {
  val name = entry.name.lowercase()
  return name.endsWith(".txt") ||
    name.endsWith(".md") ||
    name.endsWith(".log") ||
    name.endsWith(".json") ||
    name.endsWith(".xml") ||
    name.endsWith(".csv") ||
    name.endsWith(".kt") ||
    name.endsWith(".java") ||
    name.endsWith(".py") ||
    name.endsWith(".js") ||
    name.endsWith(".html") ||
    name.endsWith(".css") ||
    entry.mimeType?.startsWith("text/") == true
}

/** 文本文件查看 / 编辑对话框：查看为主，可编辑并保存。 */
@Composable
private fun TextEditorDialog(
  entry: FileEntry,
  state: TextEditorState,
  onLoad: () -> Unit,
  onSave: (String) -> Unit,
  onDismissed: () -> Unit,
) {
  var text by remember(entry.uri) { mutableStateOf("") }
  var savedTip by remember { mutableStateOf<String?>(null) }
  // 非 Composable 上下文（onClick / LaunchedEffect）中无法调用 stringResource，改用 context。
  val textContext = LocalContext.current

  // 状态到达 Loaded 时填充内容（首次加载）。
  LaunchedEffectLoad(state) { content -> text = content }
  // 首次进入触发加载。
  androidx.compose.runtime.LaunchedEffect(entry.uri) {
    if (state is TextEditorState.Loading) onLoad()
  }

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 标题 + 操作。
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            entry.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
          )
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.close)) }
        }

        when (state) {
          TextEditorState.Closed -> {}
          TextEditorState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(stringResource(R.string.file_manager_reading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          is TextEditorState.Loaded -> {
            if (state.content == null) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                  stringResource(R.string.file_manager_read_failed),
                  color = MaterialTheme.colorScheme.error,
                )
              }
            } else {
              OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text(stringResource(R.string.file_manager_edit_hint)) },
              )
              savedTip?.let {
                Text(
                  it,
                  style = MaterialTheme.typography.bodySmall,
                  color = if (savedTip == stringResource(R.string.file_manager_saved)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                  modifier = Modifier.padding(top = 4.dp),
                )
              }
              Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) }
                Button(
                  onClick = {
                    onSave(text)
                    savedTip = textContext.getString(R.string.file_manager_saving)
                  },
                  enabled = entry.canWrite,
                ) {
                  Text(stringResource(R.string.file_manager_save))
                }
              }
            }
          }
          is TextEditorState.Saved -> {
            // 保存结果提示，短暂显示后关闭。
            androidx.compose.runtime.LaunchedEffect(state.ok) {
              savedTip =
                if (state.ok) textContext.getString(R.string.file_manager_saved)
                else textContext.getString(R.string.file_manager_save_failed)
              kotlinx.coroutines.delay(900)
              onDismissed()
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                if (state.ok) stringResource(R.string.file_manager_saved)
                else stringResource(R.string.file_manager_save_failed),
                color =
                  if (state.ok) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }
    }
  }
}

/** 在状态变为 Loaded 时回填编辑器文本。 */
@Composable
private fun LaunchedEffectLoad(state: TextEditorState, onContent: (String) -> Unit) {
  androidx.compose.runtime.LaunchedEffect(state) {
    if (state is TextEditorState.Loaded && state.content != null) {
      onContent(state.content)
    }
  }
}

/** 单个文件/文件夹行。 */
@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit, onMore: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      fileIcon(entry),
      contentDescription = null,
      tint =
        if (entry.isDirectory) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(32.dp).padding(end = 12.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(entry.name, style = MaterialTheme.typography.bodyLarge)
      if (!entry.isDirectory && entry.size > 0) {
        Text(
          Formatter.formatFileSize(LocalContext.current, entry.size),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (entry.isDirectory) {
      Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      IconButton(onClick = onMore) {
        Icon(Icons.Rounded.MoreVert, contentDescription = null)
      }
    }
  }
}

/** 文件类型图标（粗分：图片 / 文档 / 其它）。 */
@Composable
private fun fileIcon(entry: FileEntry) =
  when {
    entry.isDirectory -> Icons.Rounded.Folder
    entry.mimeType?.startsWith("image/") == true -> Icons.Rounded.Image
    entry.mimeType?.startsWith("text/") == true ||
      entry.mimeType?.contains("pdf") == true ||
      entry.mimeType?.contains("document") == true -> Icons.Rounded.Description
    else -> Icons.Rounded.InsertDriveFile
  }

/** 文件操作菜单。 */
@Composable
private fun FileActionMenu(
  entry: FileEntry,
  onDismiss: () -> Unit,
  onOpen: () -> Unit,
  onShare: () -> Unit,
  onEdit: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
) {
  DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
    DropdownMenuItem(
      text = { Text(stringResource(R.string.file_manager_open)) },
      leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
      onClick = onOpen,
    )
    if (isTextFile(entry)) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.file_manager_edit)) },
        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        onClick = onEdit,
      )
    }
    DropdownMenuItem(
      text = { Text(stringResource(R.string.file_manager_share)) },
      leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
      onClick = onShare,
    )
    if (entry.canWrite) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.file_manager_rename)) },
        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        onClick = onRename,
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.file_manager_delete), color = MaterialTheme.colorScheme.error) },
        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        onClick = onDelete,
      )
    }
  }
}

/** 文本输入对话框（新建目录 / 重命名共用）。 */
@Composable
private fun NameInputDialog(
  title: String,
  confirmLabel: String,
  initial: String,
  onDismissed: () -> Unit,
  onConfirmed: (String) -> Unit,
) {
  var value by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismissed,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirmed(value.trim()) }, enabled = value.isNotBlank()) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismissed) { Text(stringResource(R.string.cancel)) }
    },
  )
}
