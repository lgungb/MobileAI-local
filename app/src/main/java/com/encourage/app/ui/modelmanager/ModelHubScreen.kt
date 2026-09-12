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

package com.encourage.app.ui.modelmanager

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.encourage.app.GalleryTopAppBar
import com.encourage.app.R
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.Task
import com.encourage.app.ui.common.HubListItem

private const val TAG = "AGModelHubScreen"

/** 「模型」Tab 的能力项顺序（与需求一致）。 */
private val MODEL_HUB_TASK_ORDER =
  listOf(
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

/**
 * 「模型」Tab（第 4 个底部 Tab）。
 *
 * 展示 5 项：Audio Scribe、Ask Image、Tiny Garden、Mobile Action、模型下载与导入。
 * - 前 4 项点击后进入该任务的模型选择列表（复用 `model_list` 流程）。
 * - 第 5 项进入全局模型管理器（下载/导入/删除）。
 *
 * @param modelManagerViewModel 模型管理 ViewModel。
 * @param onTaskSelected 选中某个能力任务时回调（由导航层打开模型选择列表）。
 * @param onModelsClicked 点击「模型下载与导入」时回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onTaskSelected: (Task) -> Unit,
  onModelsClicked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val tasks = uiState.tasks
  val isLoading = uiState.loadingModelAllowlist

  Scaffold(
    modifier = modifier,
    topBar = { GalleryTopAppBar(title = stringResource(R.string.bottom_nav_tab_models)) },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      if (isLoading && tasks.isEmpty()) {
        // 模型清单尚未就绪：显示加载指示，避免出现空白页。
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      } else {
        Column(
          modifier =
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          for (taskId in MODEL_HUB_TASK_ORDER) {
            val task =
              tasks.find { it.id == taskId }
                ?: run {
                  // 兜底：任务缺失（例如被裁剪/加载失败）时跳过并记录日志，绝不崩溃。
                  Log.w(TAG, "Model hub task '$taskId' not found in current task list.")
                  null
                }
            if (task == null) {
              continue
            }
            HubListItem(
              icon = resolveTaskIcon(task),
              label = task.label,
              description = task.shortDescription.ifBlank { task.description },
              onClick = { onTaskSelected(task) },
              modifier = Modifier.fillMaxWidth(),
            )
          }

          // 模型下载与导入（全局模型管理器）。
          HubListItem(
            icon = Icons.AutoMirrored.Rounded.ListAlt,
            label = stringResource(R.string.model_hub_download_import_label),
            description = stringResource(R.string.model_hub_download_import_description),
            onClick = onModelsClicked,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

/** 安全地解析任务图标：优先 ImageVector，其次资源 id，最后回退到通用图标。 */
@Composable
private fun resolveTaskIcon(task: Task): ImageVector {
  return task.icon
    ?: task.iconVectorResourceId?.let { ImageVector.vectorResource(it) }
    ?: Icons.Rounded.TaskAlt
}
