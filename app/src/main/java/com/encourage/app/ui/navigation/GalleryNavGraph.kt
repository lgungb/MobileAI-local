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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
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
import com.encourage.app.R
import com.encourage.app.customtasks.common.CustomTaskData
import com.encourage.app.customtasks.common.CustomTaskDataForBuiltinTask
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.Model
import com.encourage.app.data.ModelDownloadStatusType
import com.encourage.app.data.Task
import com.encourage.app.data.conversation.ConversationProfile
import com.encourage.app.data.conversation.ConversationType
import com.encourage.app.data.conversation.PresetPrompt
import com.encourage.app.data.isLegacyTasks
import com.encourage.app.firebaseAnalytics
import com.encourage.app.ui.benchmark.BenchmarkScreen
import com.encourage.app.ui.common.EmptyState
import com.encourage.app.ui.common.EmptyStateButtonConfig
import com.encourage.app.ui.common.ErrorDialog
import com.encourage.app.ui.common.ModelPageAppBar
import com.encourage.app.ui.common.chat.ModelDownloadStatusInfoPanel
import com.encourage.app.ui.conversation.ConversationListScreen
import com.encourage.app.ui.conversation.ConversationListViewModel
import com.encourage.app.ui.conversation.NewConversationDialog
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

  // 【T03】会话列表 VM（Activity 作用域；「模型」Tab 新建记录用）。
  val conversationListViewModel: ConversationListViewModel = hiltViewModel()
  val scope = rememberCoroutineScope()

  // 【新建对话】加号点击后弹出「选提示词 → 选模型」对话框的状态。
  // newConversationTaskId / newConversationType 记录目标能力与记录类型；
  // 为 null 表示未在新建流程中（不弹窗）。
  var newConversationTaskId by remember { mutableStateOf<String?>(null) }
  var newConversationType by remember { mutableStateOf<ConversationType?>(null) }

  // 【N6】统一「解析默认模型」策略：优先用户设置的全局默认模型（已下载时），
  // 否则回退该任务下已下载（SUCCEEDED）的第一个，再回退该任务的第一个模型。
  val pickDefaultModel: (Task) -> Model? = { targetTask ->
    modelManagerViewModel.resolveDefaultModelForTask(targetTask)
  }

  // 【T03/T04】统一「进入会话」入口：带上 profileId 与 type。
  // 【T04】识图 / 语音 / 对话统一收敛到 llm_chat（通过 type 区分输入能力）；
  // Agent 记录仍走原生 llm_agent_chat（依赖 Skills / MCP，不能收敛为普通对话）。
  val openProfile: (ConversationProfile) -> Unit = { profile ->
    // 【N7】模型锁定：点对话不切换后台模型，始终用全局默认模型。
    // profile 只提供提示词和采样参数，不绑定模型。
    val defaultModelId = modelManagerViewModel.uiState.value.defaultModelId
    val modelName = if (defaultModelId.isNotBlank()) {
      defaultModelId
    } else {
      profile.modelName
    }
    if (modelName.isBlank()) {
      Log.w(TAG, "Profile '${profile.id}' has no model; navigation ignored.")
    } else {
      val routeTaskId =
        if (profile.type == ConversationType.AGENT) {
          profile.taskId.ifBlank { BuiltInTaskId.LLM_AGENT_CHAT }
        } else {
          BuiltInTaskId.LLM_CHAT
        }
      try {
        navController.navigate(
          "$ROUTE_MODEL/$routeTaskId/$modelName" +
            "?profileId=${profile.id}&type=${profile.type.name}"
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to open conversation for profile '${profile.id}'.", e)
      }
    }
  }

  // 打开某个能力的「模型选择列表」（复用既有 model_list 流程）。
  val openTaskModelList: (Task) -> Unit = { task ->
    when (task.id) {
      BuiltInTaskId.LLM_ASK_IMAGE,
      BuiltInTaskId.LLM_ASK_AUDIO -> {
        // 设计 §7：识图 / 语音 → 新建一条记录并【直接进全屏会话】（不经过 model_list）。
        val type =
          if (task.id == BuiltInTaskId.LLM_ASK_IMAGE) {
            ConversationType.IMAGE
          } else {
            ConversationType.AUDIO
          }
        val model = pickDefaultModel(task)
        if (model == null) {
          // 无任何模型：引导去模型管理页下载，绝不崩溃 / 白屏。
          Log.w(TAG, "No model available for task '${task.id}'; navigating to model manager.")
          try {
            navController.navigate(ROUTE_MODEL_MANAGER)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to model manager for task '${task.id}'.", e)
          }
        } else {
          scope.launch {
            try {
              val profile =
                conversationListViewModel.createProfileAndReturn(
                  taskId = task.id,
                  modelName = model.name,
                  type = type,
                  displayName = model.displayName.ifBlank { model.name },
                )
              if (profile != null) {
                openProfile(profile)
              }
            } catch (e: Exception) {
              Log.e(TAG, "Failed to create profile for task '${task.id}'.", e)
            }
          }
        }
      }
      else -> {
        // 【N6】实验类任务（Tiny Garden / Mobile Actions）：直接用全局默认模型进对话，
        // 不再弹模型选择列表。用户若想切模型，需去模型管理页「设为默认」。
        val model = pickDefaultModel(task)
        if (model == null) {
          Log.w(TAG, "No model available for task '${task.id}'; navigating to model manager.")
          try {
            navController.navigate(ROUTE_MODEL_MANAGER)
          } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to model manager for task '${task.id}'.", e)
          }
        } else {
          try {
            navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
          } catch (e: Exception) {
            Log.e(TAG, "Failed to open task '${task.id}' with model '${model.name}'.", e)
          }
        }
      }
    }
  }

  // 【T06】会话列表右下角「+」：弹出「选提示词 → 选模型」对话框，选定后再建记录进会话。
  //
  // 为什么改成弹窗：此前加号会「直接用默认模型建一条记录并跳转」，没有给用户选择提示词、
  // 模型的机会，且每次点击都新建一条记录（容易误触堆积）。改为两步选择流程：
  //   1. 选一个常用提示词（角色设定）；
  //   2. 选模型（默认模型置顶，非默认需二次确认耗时）；
  //   三个采样参数一律用模型默认设置。
  // 选定后由 [startNewConversation] 真正建记录并进入会话。
  //
  // @param taskId 目标能力（对话 Tab 传 llm_chat；功能 Tab 传 llm_agent_chat）。
  // @param type 记录类型（CHAT / AGENT），决定记录落在哪个列表里。
  val createNewConversation: (String, ConversationType) -> Unit = { taskId, type ->
    val targetTask = modelManagerUiState.tasks.find { it.id == taskId }
    if (targetTask == null) {
      // 任务尚未装配完成（冷启动竞态）：只记日志，不弹窗、不崩溃。
      Log.w(TAG, "Task '$taskId' is not ready; cannot create a new conversation.")
    } else {
      // 进入新建流程：记录目标能力与类型，弹出选择对话框。
      newConversationTaskId = taskId
      newConversationType = type
    }
  }

  // 【新建对话】用户在弹窗里选定了提示词与模型后，真正建记录并进入会话。
  //
  // 与 [openProfile] 的差别：这里**用用户选定的模型**进入会话（不强制全局默认模型），
  // 因为用户明确要「选模型」；提示词作为记录级 systemPrompt 落库。
  // 采样参数 configValues 传空 map = 使用模型默认设置。
  val startNewConversation: (PresetPrompt, Model) -> Unit = { prompt, model ->
    val taskId = newConversationTaskId
    val type = newConversationType
    newConversationTaskId = null
    newConversationType = null
    if (taskId != null && type != null) {
      scope.launch {
        try {
          val profile =
            conversationListViewModel.createProfileAndReturn(
              taskId = taskId,
              modelName = model.name,
              type = type,
              systemPrompt = prompt.prompt,
              configValues = emptyMap(),
              alias = prompt.title,
              displayName = model.displayName.ifBlank { model.name },
            )
          if (profile != null) {
            // 直接用所选模型进入对话（不经过 openProfile 的默认模型覆盖逻辑）。
            try {
              navController.navigate(
                "$ROUTE_MODEL/$taskId/${model.name}" +
                  "?profileId=${profile.id}&type=${type.name}"
              )
            } catch (e: Exception) {
              Log.e(TAG, "Failed to open new conversation for profile '${profile.id}'.", e)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to create a new conversation for task '$taskId'.", e)
        }
      }
    } else {
      Log.w(TAG, "startNewConversation called without pending task/type; ignored.")
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    NavHost(
      navController = navController,
      startDestination = AppRoutes.TAB_CHAT,
      enterTransition = { EnterTransition.None },
      exitTransition = { ExitTransition.None },
    ) {
      // Tab 1：对话（会话列表，展示 CHAT / IMAGE / AUDIO 记录）。
      composable(route = AppRoutes.TAB_CHAT) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_CHAT, onTabSelected = onTabSelected) {
          ConversationListScreen(
            types = setOf(ConversationType.CHAT, ConversationType.IMAGE, ConversationType.AUDIO),
            titleResId = R.string.bottom_nav_tab_chat,
            onProfileClick = openProfile,
            onNavigateToModels = { onTabSelected(BottomTab.MODELS) },
            onCreateNew = {
              createNewConversation(BuiltInTaskId.LLM_CHAT, ConversationType.CHAT)
            },
          )
        }
      }

      // Tab 2：功能（会话列表，仅展示 AGENT 记录）。
      composable(route = AppRoutes.TAB_AGENT) {
        TopLevelTabScaffold(currentRoute = AppRoutes.TAB_AGENT, onTabSelected = onTabSelected) {
          ConversationListScreen(
            types = setOf(ConversationType.AGENT),
            titleResId = R.string.bottom_nav_tab_agent,
            onProfileClick = openProfile,
            onNavigateToModels = { onTabSelected(BottomTab.MODELS) },
            onCreateNew = {
              createNewConversation(BuiltInTaskId.LLM_AGENT_CHAT, ConversationType.AGENT)
            },
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
      // 【T03】新增可选 query 参数 profileId / type（缺省可解析，兼容既有深链与旧 URL 形态）。
      composable(
        route = "$ROUTE_MODEL/{taskId}/{modelName}?profileId={profileId}&type={type}&query={query}",
        arguments =
          listOf(
            navArgument("taskId") { type = NavType.StringType },
            navArgument("modelName") { type = NavType.StringType },
            navArgument("profileId") {
              type = NavType.StringType
              nullable = true
              defaultValue = null
            },
            navArgument("type") {
              type = NavType.StringType
              nullable = true
              defaultValue = null
            },
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
        // 【T03】记录绑定参数（T04 消费；当前仅透传）。
        val profileId = backStackEntry.arguments?.getString("profileId")
        val conversationType = backStackEntry.arguments?.getString("type")
        val initialModel = modelManagerViewModel.getModelByName(name = modelName)
        if (initialModel == null) {
          // 【T04】入口模型缺失（未下载 / 记录指向的模型已删）时给出空态，绝不白屏。
          Log.w(TAG, "Model '$modelName' not found for route_model; showing fallback empty state.")
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
              icon = Icons.Rounded.Download,
              titleResId = R.string.conversation_profile_model_missing,
              descriptionResId = R.string.bottom_nav_no_model_title,
              buttonConfig =
                EmptyStateButtonConfig(
                  buttonLabelResId = R.string.bottom_nav_no_model_action,
                  onButtonClick = { onTabSelected(BottomTab.MODELS) },
                ),
            )
          }
        } else {
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
                    // 【T03】透传记录绑定参数给会话页（T04 消费）。
                    profileId = profileId,
                    conversationType = conversationType,
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
                    // 【T05-① 资源生命周期】退页**不再**销毁模型（保活）。
                    // 释放时机收敛为：系统低内存 / 删除模型 / 切换模型。
                    enableModelListAnimation = false
                    navController.navigateUp()
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

    // 【新建对话】加号触发的「选提示词 → 选模型」对话框（覆盖在 NavHost 之上）。
    val pendingNewTaskId = newConversationTaskId
    if (pendingNewTaskId != null) {
      val targetTask = modelManagerUiState.tasks.find { it.id == pendingNewTaskId }
      if (targetTask != null) {
        val defaultModel = pickDefaultModel(targetTask)
        // 可选模型：该任务下的全部模型；已下载的排前面，默认模型最前。
        val availableModels =
          targetTask.models.sortedBy { model ->
            when {
              model.name == defaultModel?.name -> 0
              modelManagerUiState.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED -> 1
              else -> 2
            }
          }
        NewConversationDialog(
          defaultModel = defaultModel,
          availableModels = availableModels,
          isDownloaded = { model ->
            modelManagerUiState.modelDownloadStatus[model.name]?.status ==
              ModelDownloadStatusType.SUCCEEDED
          },
          onDismiss = {
            newConversationTaskId = null
            newConversationType = null
          },
          onConfirm = { prompt, model -> startNewConversation(prompt, model) },
        )
      } else {
        // 目标任务仍不可用：关闭流程，避免空弹窗。
        newConversationTaskId = null
        newConversationType = null
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
          // 【N6】全局默认模型：识图/语音等单轮页也不再在标题栏选模型。
          hideModelSelector = true,
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
