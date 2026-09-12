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

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.encourage.app.GalleryEvent
import com.encourage.app.customtasks.common.CustomTaskData
import com.encourage.app.customtasks.common.CustomTaskDataForBuiltinTask
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.ModelDownloadStatusType
import com.encourage.app.data.Task
import com.encourage.app.data.isLegacyTasks
import com.encourage.app.firebaseAnalytics
import com.encourage.app.ui.benchmark.BenchmarkScreen
import com.encourage.app.ui.common.ErrorDialog
import com.encourage.app.ui.common.ModelPageAppBar
import com.encourage.app.ui.common.chat.ModelDownloadStatusInfoPanel
import com.encourage.app.ui.home.PromoScreenGm4
import com.encourage.app.ui.modelmanager.GlobalModelManager
import com.encourage.app.ui.modelmanager.ModelHubScreen
import com.encourage.app.ui.modelmanager.ModelInitializationStatusType
import com.encourage.app.ui.modelmanager.ModelManager
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.notifications.NotificationsScreen
import com.encourage.app.ui.system.SystemScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGGalleryNavGraph"
private const val ROUTE_MODEL_LIST = "model_list"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val PROMO_ID = "gm4"
private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100

private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS,
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

private fun AnimatedContentTransitionScope<*>.slideUpEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Up,
  )
}

private fun AnimatedContentTransitionScope<*>.slideDownExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Down,
  )
}

/**
 * 应用导航宿主。
 *
 * 顶层为微信式底部 5 Tab（对话 / 功能 / 实验 / 模型 / 系统），仅顶层路由显示底栏；
 * 进入二级页（`model_list` / `route_model/{taskId}/{modelName}` / `benchmark/{modelName}` /
 * `model_manager` / `notifications`）时隐藏底栏。Tab 之间互不堆栈，各 Tab 保留自身返回栈状态。
 */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val context = LocalContext.current
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // 首次启动品牌引导页（保留原有行为：仅未看过时展示一次）。
  // 【健壮性】hasViewedPromo() 内部是 runBlocking 读取 DataStore，直接放在 remember 里会阻塞
  // 主线程、拖慢冷启动。改为异步读取：读取完成前用与启动页一致的背景遮罩盖住，避免闪屏。
  var promoViewed by remember { mutableStateOf<Boolean?>(null) }
  var promoDismissed by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    promoViewed =
      try {
        withContext(Dispatchers.IO) {
          modelManagerViewModel.dataStoreRepository.hasViewedPromo(promoId = PROMO_ID)
        }
      } catch (e: Exception) {
        // 读取失败时按「已看过」处理，避免遮罩/引导页卡住导致无法进入应用。
        Log.e(TAG, "Failed to read promo viewed state.", e)
        true
      }
  }

  // 通知运行时权限（Android 13+）：去首页化后，把原先挂在首页的主动申请逻辑上移到导航层，
  // 保证「任务中心 / 定时通知」仍能拿到权限。
  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      /* 权限结果无需额外处理 */
    }
  LaunchedEffect(Unit) {
    try {
      delay(2000)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to request notification permission.", e)
    }
  }

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // 底部 Tab 切换：微信式——Tab 间互不堆栈，各 Tab 保留自身返回栈状态。
  val onTabSelected: (BottomTab) -> Unit = { tab ->
    try {
      navController.navigate(tab.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to switch to tab '${tab.route}'.", e)
    }
  }

  // 打开某个能力的「模型选择列表」（复用既有 model_list 流程）。
  val openTaskModelList: (Task) -> Unit = { task ->
    try {
      pickedTask = task
      enableModelListAnimation = true
      navController.navigate(ROUTE_MODEL_LIST)
      firebaseAnalytics?.logEvent(
        GalleryEvent.CAPABILITY_SELECT.id,
        Bundle().apply { putString("capability_name", task.id) },
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open model list for task '${task.id}'.", e)
    }
  }

  // 顶层对话 Tab 内能力选择器 -> 其它任务的对话页（复用当前模型）。
  val navigateToTaskConversationFromTab: (Task) -> Unit = { targetTask ->
    val modelName = modelManagerViewModel.getSelectedModel()?.name
    if (modelName.isNullOrEmpty()) {
      // 兜底：模型尚未就绪时不做跳转，避免拼出非法路由。
      Log.w(TAG, "No selected model; ignoring navigation to task '${targetTask.id}'.")
    } else {
      try {
        navController.navigate("$ROUTE_MODEL/${targetTask.id}/$modelName")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to navigate to conversation for task '${targetTask.id}'.", e)
      }
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    NavHost(
      navController = navController,
      startDestination = AppRoutes.TAB_CHAT,
      enterTransition = { EnterTransition.None },
      exitTransition = { ExitTransition.None },
    ) {
      // Tab 1：对话（AI Chat）。
      composable(route = AppRoutes.TAB_CHAT) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_CHAT, onTabSelected = onTabSelected) {
          TaskTabScreen(
            taskId = BuiltInTaskId.LLM_CHAT,
            modelManagerViewModel = modelManagerViewModel,
            onNavigateToModelsTab = { onTabSelected(BottomTab.MODELS) },
            onNavigateToTask = navigateToTaskConversationFromTab,
          )
        }
      }

      // Tab 2：功能（Agent Skills）。
      composable(route = AppRoutes.TAB_AGENT) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_AGENT, onTabSelected = onTabSelected) {
          TaskTabScreen(
            taskId = BuiltInTaskId.LLM_AGENT_CHAT,
            modelManagerViewModel = modelManagerViewModel,
            onNavigateToModelsTab = { onTabSelected(BottomTab.MODELS) },
            onNavigateToTask = navigateToTaskConversationFromTab,
          )
        }
      }

      // Tab 3：实验（Prompt Lab）。
      composable(route = AppRoutes.TAB_LAB) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_LAB, onTabSelected = onTabSelected) {
          TaskTabScreen(
            taskId = BuiltInTaskId.LLM_PROMPT_LAB,
            modelManagerViewModel = modelManagerViewModel,
            onNavigateToModelsTab = { onTabSelected(BottomTab.MODELS) },
            onNavigateToTask = navigateToTaskConversationFromTab,
          )
        }
      }

      // Tab 4：模型（5 项能力 + 模型下载与导入）。
      composable(route = AppRoutes.TAB_MODELS) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_MODELS, onTabSelected = onTabSelected) {
          ModelHubScreen(
            modelManagerViewModel = modelManagerViewModel,
            onTaskSelected = openTaskModelList,
            onModelsClicked = { navController.navigate(ROUTE_MODEL_MANAGER) },
          )
        }
      }

      // Tab 5：系统（帮助中心 / 设置 / 通知 / 任务中心 / 文件管理）。
      composable(route = AppRoutes.TAB_SYSTEM) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_SYSTEM, onTabSelected = onTabSelected) {
          SystemScreen(
            modelManagerViewModel = modelManagerViewModel,
            onNotificationsClicked = { navController.navigate(ROUTE_NOTIFICATIONS) },
          )
        }
      }

      // 二级页：按任务选择模型（隐藏底栏）。
      composable(
        route = ROUTE_MODEL_LIST,
        enterTransition = {
          if (AppRoutes.isTopLevelRoute(initialState.destination.route)) {
            slideEnter()
          } else {
            EnterTransition.None
          }
        },
        exitTransition = {
          if (AppRoutes.isTopLevelRoute(targetState.destination.route)) {
            slideExit()
          } else {
            ExitTransition.None
          }
        },
      ) {
        val task = pickedTask
        if (task == null) {
          // 兜底：未选中任务时不应进入此页，记录日志并返回上一级，避免空白页。
          Log.e(TAG, "model_list opened without a picked task; navigating up.")
          LaunchedEffect(Unit) { navController.navigateUp() }
        } else {
          ModelManager(
            viewModel = modelManagerViewModel,
            task = task,
            enableAnimation = enableModelListAnimation,
            onModelClicked = { model ->
              navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
            },
            onBenchmarkClicked = { model ->
              firebaseAnalytics?.logEvent(
                GalleryEvent.CAPABILITY_SELECT.id,
                Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
              )
              navController.navigate("$ROUTE_BENCHMARK/${model.name}")
            },
            navigateUp = {
              enableModelListAnimation = false
              navController.navigateUp()
            },
          )
        }
      }

      // 二级页：具体任务的对话界面（隐藏底栏）。
      composable(
        route = "$ROUTE_MODEL/{taskId}/{modelName}?query={query}",
        arguments =
          listOf(
            navArgument("taskId") { type = NavType.StringType },
            navArgument("modelName") { type = NavType.StringType },
            navArgument("query") {
              type = NavType.StringType
              nullable = true
              defaultValue = null
            },
          ),
        enterTransition = { slideEnter() },
        exitTransition = { slideExit() },
      ) { backStackEntry ->
        val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
        val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
        val queryParam = backStackEntry.arguments?.getString("query")
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
          LaunchedEffect(modelName) { modelManagerViewModel.selectModel(initialModel) }

          val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
          if (customTask != null) {
            if (isLegacyTasks(customTask.task.id)) {
              customTask.MainScreen(
                data =
                  CustomTaskDataForBuiltinTask(
                    modelManagerViewModel = modelManagerViewModel,
                    onNavUp = {
                      enableModelListAnimation = false
                      navController.navigateUp()
                    },
                    initialQuery = queryParam,
                    // 【N6 统一入口】能力选择器点击后导航到对应任务的对话页，复用当前模型。
                    onNavigateToTask = { targetTask ->
                      navController.navigate("$ROUTE_MODEL/${targetTask.id}/$modelName") {
                        popUpTo(AppRoutes.TAB_CHAT) { inclusive = false }
                      }
                    },
                  )
              )
            } else {
              var disableAppBarControls by remember { mutableStateOf(false) }
              var hideTopBar by remember { mutableStateOf(false) }
              var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
              CustomTaskScreen(
                task = customTask.task,
                modelManagerViewModel = modelManagerViewModel,
                onNavigateUp = {
                  if (customNavigateUpCallback != null) {
                    customNavigateUpCallback?.invoke()
                  } else {
                    enableModelListAnimation = false
                    navController.navigateUp()

                    // clean up all models.
                    for (curModel in customTask.task.models) {
                      val instanceToCleanUp = curModel.instance
                      scope.launch(Dispatchers.Default) {
                        try {
                          modelManagerViewModel.cleanupModel(
                            context = context,
                            task = customTask.task,
                            model = curModel,
                            instanceToCleanUp = instanceToCleanUp,
                          )
                        } catch (e: Exception) {
                          // 【健壮性】清理失败不应中断返回流程或导致崩溃，仅记录日志。
                          Log.e(
                            TAG,
                            "Failed to clean up model '${curModel.name}' for task '${customTask.task.id}'.",
                            e,
                          )
                        }
                      }
                    }
                  }
                },
                disableAppBarControls = disableAppBarControls,
                hideTopBar = hideTopBar,
                useThemeColor = customTask.task.useThemeColor,
              ) { bottomPadding ->
                customTask.MainScreen(
                  data =
                    CustomTaskData(
                      modelManagerViewModel = modelManagerViewModel,
                      bottomPadding = bottomPadding,
                      setAppBarControlsDisabled = { disableAppBarControls = it },
                      setTopBarVisible = { hideTopBar = !it },
                      setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                    )
                )
              }
            }
          }
        }
      }

      // 二级页：全局模型管理器（隐藏底栏）。
      composable(
        route = ROUTE_MODEL_MANAGER,
        enterTransition = {
          if (
            initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
              initialState.destination.route?.startsWith(ROUTE_MODEL) == true
          ) {
            null
          } else {
            slideUpEnter()
          }
        },
        exitTransition = {
          if (
            targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
              targetState.destination.route?.startsWith(ROUTE_MODEL) == true
          ) {
            null
          } else {
            slideDownExit()
          }
        },
      ) { backStackEntry ->
        GlobalModelManager(
          viewModel = modelManagerViewModel,
          navigateUp = {
            enableModelListAnimation = false
            navController.navigateUp()
          },
          onModelSelected = { task, model ->
            navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
          },
          onBenchmarkClicked = { model ->
            firebaseAnalytics?.logEvent(
              GalleryEvent.CAPABILITY_SELECT.id,
              Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
            )
            navController.navigate("$ROUTE_BENCHMARK/${model.name}")
          },
        )
      }

      // 二级页：通知（隐藏底栏）。
      composable(
        route = ROUTE_NOTIFICATIONS,
        enterTransition = { slideUpEnter() },
        exitTransition = { slideDownExit() },
      ) {
        NotificationsScreen(navigateUp = { navController.navigateUp() })
      }

      // 二级页：跑分（隐藏底栏）。
      composable(
        route = "$ROUTE_BENCHMARK/{modelName}",
        arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
        enterTransition = { slideEnter() },
        exitTransition = { slideExit() },
      ) { backStackEntry ->
        val modelName = backStackEntry.arguments?.getString("modelName") ?: ""

        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          BenchmarkScreen(
            initialModel = model,
            modelManagerViewModel = modelManagerViewModel,
            onBackClicked = {
              enableModelListAnimation = false
              navController.navigateUp()
            },
          )
        }
      }
    }

    // 首次启动品牌引导页覆盖层（画在 NavHost 之上，铺满整屏）。
    when (promoViewed) {
      null -> {
        // 【健壮性】引导页状态尚未从 DataStore 读回：用与启动页一致的背景色遮罩盖住，
        // 避免「先闪一下主界面、再弹出引导页」的闪屏。
        Box(
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        )
      }
      false -> {
        if (!promoDismissed) {
          PromoScreenGm4(
            onDismiss = {
              try {
                modelManagerViewModel.dataStoreRepository.addViewedPromoId(promoId = PROMO_ID)
              } catch (e: Exception) {
                Log.e(TAG, "Failed to persist promo viewed state.", e)
              }
              promoDismissed = true
            }
          )
        }
      }
      true -> {
        // 已看过引导页：无需展示。
      }
    }
  }

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  // Wait until the model manager has been initialized and the tasks are available.
  if (data != null && modelManagerUiState.tasks.isNotEmpty()) {
    intent.data = null
    val uriStr = data.toString()
    Log.d(TAG, "navigation link clicked: $data")
    // 1. Precise model deep links: com.encourage.app://model/<taskId>/<modelName>
    if (uriStr.startsWith("com.encourage.app://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        val queryStr = data.getQueryParameter("query")
        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          val route =
            if (!queryStr.isNullOrEmpty()) {
              "$ROUTE_MODEL/${taskId}/${model.name}?query=${Uri.encode(queryStr)}"
            } else {
              "$ROUTE_MODEL/${taskId}/${model.name}"
            }
          navController.navigate(route)
        }
      } else {
        Log.e(TAG, "Malformed deep link URI received: $data")
      }
    } else if (uriStr == "com.encourage.app://global_model_manager") {
      navController.navigate(ROUTE_MODEL_MANAGER)
    } else {
      // 2. Dynamic task-level deep links: com.encourage.app://<taskId>
      val host = data.host
      if (host != null) {
        val queryStr = data.getQueryParameter("query")
        val task = modelManagerUiState.tasks.find { it.id == host }
        if (task != null) {
          // Pick the first successfully downloaded model or the default active model for this task
          val defaultModel =
            task.models.firstOrNull { model ->
              modelManagerUiState.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED
            } ?: task.models.firstOrNull()

          if (defaultModel != null) {
            val route =
              if (!queryStr.isNullOrEmpty()) {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}?query=${Uri.encode(queryStr)}"
              } else {
                "$ROUTE_MODEL/${task.id}/${defaultModel.name}"
              }
            navController.navigate(route)
          } else {
            Log.e(TAG, "No available model found for task: $host")
          }
        }
      }
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
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
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          shouldShowHistoryButton = false,
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                try {
                  modelManagerViewModel.cleanupModel(
                    context = context,
                    task = task,
                    model = prevModel,
                    instanceToCleanUp = instanceToCleanUp,
                  )
                } catch (e: Exception) {
                  // 【健壮性】切换模型时的清理失败不应影响新模型的选择，仅记录日志。
                  Log.e(TAG, "Failed to clean up previous model '${prevModel.name}'.", e)
                }
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        // Convert measured pixel height to Dp
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}
