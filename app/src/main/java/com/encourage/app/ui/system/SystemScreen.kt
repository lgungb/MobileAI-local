/*
 * Copyright 2025 Google LLC
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

package com.encourage.app.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.encourage.app.GalleryTopAppBar
import com.encourage.app.R
import com.encourage.app.ui.common.HubListItem
import com.encourage.app.ui.filemanager.FileManagerDialog
import com.encourage.app.ui.help.HelpCenterDialog
import com.encourage.app.ui.home.SettingsDialog
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.tasks.TaskCenterDialog

/**
 * 「系统」Tab（第 5 个底部 Tab）。
 *
 * 展示 5 项：帮助中心、设置、通知、任务中心、文件管理。
 * 其中帮助中心 / 设置 / 任务中心 / 文件管理沿用 Dialog 形式（与旧首页抽屉实现一致）；
 * 通知进入独立的 `notifications` 路由（二级页，隐藏底栏）。
 *
 * @param modelManagerViewModel 设置对话框需要读取主题与模型相关状态。
 * @param onNotificationsClicked 点击「通知」时回调（由导航层打开通知页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onNotificationsClicked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showTaskCenter by remember { mutableStateOf(false) }
  var showHelpCenter by remember { mutableStateOf(false) }
  var showFileManager by remember { mutableStateOf(false) }

  Scaffold(
    modifier = modifier,
    topBar = { GalleryTopAppBar(title = stringResource(R.string.bottom_nav_tab_system)) },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      HubListItem(
        icon = Icons.AutoMirrored.Rounded.HelpOutline,
        label = stringResource(R.string.drawer_help_label),
        description = stringResource(R.string.drawer_help_description),
        onClick = { showHelpCenter = true },
        modifier = Modifier.fillMaxWidth(),
      )
      HubListItem(
        icon = Icons.Rounded.Settings,
        label = stringResource(R.string.drawer_settings_label),
        description = stringResource(R.string.drawer_settings_description),
        onClick = { showSettingsDialog = true },
        modifier = Modifier.fillMaxWidth(),
      )
      HubListItem(
        icon = Icons.Rounded.Notifications,
        label = stringResource(R.string.drawer_notifications_label),
        description = stringResource(R.string.drawer_notifications_description),
        onClick = onNotificationsClicked,
        modifier = Modifier.fillMaxWidth(),
      )
      HubListItem(
        icon = Icons.Rounded.TaskAlt,
        label = stringResource(R.string.drawer_task_center_label),
        description = stringResource(R.string.drawer_task_center_description),
        onClick = { showTaskCenter = true },
        modifier = Modifier.fillMaxWidth(),
      )
      HubListItem(
        icon = Icons.Rounded.Folder,
        label = stringResource(R.string.drawer_file_manager_label),
        description = stringResource(R.string.drawer_file_manager_description),
        onClick = { showFileManager = true },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  // 设置对话框。
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }

  // 任务中心对话框。
  if (showTaskCenter) {
    TaskCenterDialog(onDismissed = { showTaskCenter = false })
  }

  // 帮助中心对话框。
  if (showHelpCenter) {
    HelpCenterDialog(onDismissed = { showHelpCenter = false })
  }

  // 文件管理对话框。
  if (showFileManager) {
    FileManagerDialog(onDismissed = { showFileManager = false })
  }
}
