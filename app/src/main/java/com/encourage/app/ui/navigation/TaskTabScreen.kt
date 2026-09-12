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

package com.encourage.app.ui.navigation

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
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
import com.encourage.app.R
import com.encourage.app.customtasks.common.CustomTaskData
import com.encourage.app.customtasks.common.CustomTaskDataForBuiltinTask
import com.encourage.app.data.ModelDownloadStatusType
import com.encourage.app.data.isLegacyTasks
import com.encourage.app.ui.common.EmptyState
import com.encourage.app.ui.common.EmptyStateButtonConfig
import com.encourage.app.ui.modelmanager.ModelManagerViewModel

private const val TAG = "AGTaskTabScreen"

/**
 * 顶层对话类 Tab（对话 / 功能 / 实验）的通用载体。
 *
 * 进入后自动选用「已下载的第一个模型」，直接渲染对应任务的对话界面。整个过程带有完整兜底：
 * - 任务未加载完 / 该任务没有任何模型 -> 友好空态 + 引导前往「模型」Tab，绝不白屏或崩溃。
 * - 一个已下载模型都没有 -> 额外弹出可关闭的提示对话框，引导前往「模型」Tab 下载。
 *
 * @param taskId 目标任务 id（如 `llm_chat`）。
 * @param modelManagerViewModel 模型管理 ViewModel。
 * @param onNavigateToModelsTab 跳转到「模型」Tab 的回调。
 */
@Composable
fun TaskTabScreen(
  taskId: String,
  modelManagerViewModel: ModelManagerViewModel,
  onNavigateToModelsTab: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val task = remember(uiState.tasks, taskId) { uiState.tasks.find { it.id == taskId } }

  // 默认模型：优先已下载（SUCCEEDED）的第一个，其次回退到该任务的第一个模型。
  val defaultModel =
    remember(uiState.tasks, uiState.modelDownloadStatus, taskId) {
      val curTask = uiState.tasks.find { it.id == taskId }
      if (curTask == null) {
        null
      } else {
        curTask.models.firstOrNull { model ->
          uiState.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
        } ?: curTask.models.firstOrNull()
      }
    }

  val hasDownloadedModel =
    remember(uiState.tasks, uiState.modelDownloadStatus) {
      modelManagerViewModel.getAllDownloadedModels().isNotEmpty()
    }

  // 任务或模型缺失：给出友好空态，绝不白屏 / 崩溃。
  if (task == null || defaultModel == null) {
    Log.w(TAG, "Task '$taskId' is not ready (task=$task, defaultModel=$defaultModel).")
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      EmptyState(
        icon = Icons.Rounded.Download,
        titleResId = R.string.bottom_nav_no_model_title,
        descriptionResId = R.string.bottom_nav_no_model_description,
        buttonConfig =
          EmptyStateButtonConfig(
            buttonLabelResId = R.string.bottom_nav_no_model_action,
            onButtonClick = onNavigateToModelsTab,
          ),
      )
    }
    return
  }

  // 选定模型，供对话页使用（协程内做异常兜底）。
  LaunchedEffect(defaultModel.name) {
    try {
      modelManagerViewModel.selectModel(defaultModel)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to select model '${defaultModel.name}' for task '$taskId'.", e)
    }
  }

  // 一个已下载模型都没有时，弹出一次可关闭的引导对话框（仍保留对话页，用户也可就地下载）。
  var showNoModelDialog by remember { mutableStateOf(false) }
  LaunchedEffect(taskId, hasDownloadedModel) {
    if (!hasDownloadedModel) {
      showNoModelDialog = true
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
    if (customTask == null) {
      Log.e(TAG, "No CustomTask found for taskId='$taskId'.")
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
          icon = Icons.Rounded.Download,
          titleResId = R.string.bottom_nav_task_unavailable_title,
          descriptionResId = R.string.bottom_nav_task_unavailable_description,
          buttonConfig =
            EmptyStateButtonConfig(
              buttonLabelResId = R.string.bottom_nav_no_model_action,
              onButtonClick = onNavigateToModelsTab,
            ),
        )
      }
    } else if (isLegacyTasks(taskId)) {
      customTask.MainScreen(
        data =
          CustomTaskDataForBuiltinTask(
            modelManagerViewModel = modelManagerViewModel,
            // 顶层 Tab 是导航栈的根，没有「返回上一级」的目标：仅记录日志，避免误返回。
            onNavUp = { Log.d(TAG, "navigateUp ignored on top-level tab '$taskId'.") },
            initialQuery = null,
          )
      )
    } else {
      // 兜底：非内置（legacy）任务也应保证不崩溃（当前三个顶层 Tab 均为内置任务）。
      Log.w(TAG, "Task '$taskId' is not a legacy task; using generic CustomTaskData.")
      customTask.MainScreen(
        data =
          CustomTaskData(
            modelManagerViewModel = modelManagerViewModel,
            setCustomNavigateUpCallback = {},
          )
      )
    }
  }

  if (showNoModelDialog) {
    AlertDialog(
      onDismissRequest = { showNoModelDialog = false },
      title = { Text(stringResource(R.string.bottom_nav_no_model_dialog_title)) },
      text = { Text(stringResource(R.string.bottom_nav_no_model_dialog_message)) },
      confirmButton = {
        TextButton(
          onClick = {
            showNoModelDialog = false
            onNavigateToModelsTab()
          }
        ) {
          Text(stringResource(R.string.bottom_nav_no_model_dialog_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { showNoModelDialog = false }) {
          Text(stringResource(R.string.bottom_nav_no_model_dialog_cancel))
        }
      },
    )
  }
}
