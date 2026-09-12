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

package com.encourage.app.ui.llmsingleturn

import androidx.hilt.navigation.compose.hiltViewModel

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.os.bundleOf
import com.encourage.app.GalleryEvent
import com.encourage.app.R
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.ModelDownloadStatusType
import com.encourage.app.firebaseAnalytics
import com.encourage.app.ui.common.EmptyState
import com.encourage.app.ui.common.ErrorDialog
import com.encourage.app.ui.common.LocalIsTopLevelTab
import com.encourage.app.ui.common.ModelPageAppBar
import com.encourage.app.ui.common.chat.ModelDownloadStatusInfoPanel
import com.encourage.app.ui.modelmanager.ModelInitializationStatusType
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.theme.customColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGLlmSingleTurnScreen"

@Composable
fun LlmSingleTurnScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmSingleTurnViewModel = hiltViewModel(),
) {
  // 【健壮性】去掉 `!!`：任务未就绪（模型清单尚未装配完成）时给出友好空态，绝不崩溃。
  val task = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_PROMPT_LAB)
  if (task == null) {
    Log.e(TAG, "Task '${BuiltInTaskId.LLM_PROMPT_LAB}' not found; showing empty state.")
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      EmptyState(
        icon = Icons.Rounded.Download,
        titleResId = R.string.bottom_nav_task_unavailable_title,
        descriptionResId = R.string.bottom_nav_task_unavailable_description,
      )
    }
    return
  }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }

  val handleNavigateUp = {
    navigatingUp = true
    navigateUp()

    // clean up all models.
    // 【T05-① 刻意保留】Prompt Lab 是「多模型横向对比」页：横向 pager 会同时持有多个模型实例，
    // 内存语义与「会话列表 + 单模型保活」完全不同，退页释放反而会让对比结果反复重建。
    // 本轮保持原样，留作后续专项评估，不随会话页一起改为保活。
    scope.launch(Dispatchers.Default) {
      for (model in task.models) {
        modelManagerViewModel.cleanupModel(context = context, task = task, model = model)
      }
    }
  }

  // Handle system's edge swipe.
  //
  // 顶层 Tab（实验）场景：本页是导航栈根，返回键交给系统/NavHost 处理，避免被吞掉、
  // 也避免误触发 handleNavigateUp() 去清理模型。二级页（route_model/...）行为保持不变。
  val isTopLevelTab = LocalIsTopLevelTab.current
  BackHandler(enabled = !isTopLevelTab) {
    val modelInitializationStatus =
      modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelInitializing =
      modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING
    if (!isModelInitializing && !uiState.inProgress) {
      handleNavigateUp()
    }
  }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from LlmsingleTurnScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      ModelPageAppBar(
        task = task,
        model = selectedModel,
        modelManagerViewModel = modelManagerViewModel,
        inProgress = uiState.inProgress,
        modelPreparing = uiState.preparing,
        onConfigChanged = { _, _ -> },
        onBackClicked = { handleNavigateUp() },
        onModelSelected = { prevModel, newSelectedModel ->
          scope.launch(Dispatchers.Default) {
            if (prevModel.name != newSelectedModel.name) {
              // Clean up prev model.
              modelManagerViewModel.cleanupModel(context = context, task = task, model = prevModel)
            }

            // Update selected model.
            modelManagerViewModel.selectModel(model = newSelectedModel)
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.padding(
          top = innerPadding.calculateTopPadding(),
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val modelDownloaded = curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      // Main UI after model is downloaded.
      var mainUiVisible by remember { mutableStateOf(modelDownloaded) }
      LaunchedEffect(modelDownloaded) { mainUiVisible = modelDownloaded }
      val animatedAlpha by animateFloatAsState(targetValue = if (mainUiVisible) 1.0f else 0f)
      Box(
        contentAlignment = Alignment.BottomCenter,
        modifier =
          Modifier.fillMaxSize()
            // Just hide the UI without removing it from the screen so that the scroll syncing
            // from ResponsePanel still works.
            .graphicsLayer { alpha = animatedAlpha },
      ) {
        VerticalSplitView(
          modifier = Modifier.fillMaxSize(),
          topView = {
            PromptTemplatesPanel(
              model = selectedModel,
              viewModel = viewModel,
              modelManagerViewModel = modelManagerViewModel,
              onSend = { fullPrompt ->
                viewModel.generateResponse(task = task, model = selectedModel, input = fullPrompt)

                firebaseAnalytics?.logEvent(
                  GalleryEvent.GENERATE_ACTION.id,
                  bundleOf("capability_name" to task.id, "model_id" to selectedModel.name),
                )
              },
              onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
              modifier = Modifier.fillMaxSize(),
            )
          },
          bottomView = {
            Box(
              contentAlignment = Alignment.BottomCenter,
              modifier =
                Modifier.fillMaxSize().background(MaterialTheme.customColors.agentBubbleBgColor),
            ) {
              if (task.models.indexOf(selectedModel) >= 0) {
                ResponsePanel(
                  task = task,
                  model = selectedModel,
                  viewModel = viewModel,
                  modelManagerViewModel = modelManagerViewModel,
                  modifier =
                    Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()),
                )
              }
            }
          },
        )
      }

      // Download button for the selected model when the model is not downloaded.
      //
      // Put this after the main UI so that it's layered on top.
      AnimatedVisibility(
        visible = !modelDownloaded,
        // Block pointer input to prevent user from interacting with the main UI below.
        modifier = Modifier.pointerInput(Unit) {},
        enter = scaleIn(initialScale = 0.9f) + fadeIn(),
        exit = scaleOut(targetScale = 0.9f) + fadeOut(),
      ) {
        ModelDownloadStatusInfoPanel(
          model = selectedModel,
          task = task,
          modelManagerViewModel = modelManagerViewModel,
        )
      }

      if (showErrorDialog) {
        ErrorDialog(
          error = modelInitializationStatus?.error ?: "",
          onDismiss = { showErrorDialog = false },
        )
      }
    }
  }
}
