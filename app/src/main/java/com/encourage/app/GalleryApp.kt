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

package com.encourage.app

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.encourage.app.data.server.LocalApiServer
import com.encourage.app.data.tasks.TaskAiInvoker
import com.encourage.app.ui.modelmanager.ModelManagerViewModel
import com.encourage.app.ui.navigation.GalleryNavHost
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "AGGalleryApp"

/** Top level composable representing the main screen of the application. */
@Composable
fun GalleryApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  // 【M4】本地 API Server 的模型桥接：ModelManagerViewModel 是组合级组件，
  // 服务层（Singleton）拿不到它，这里在组合期把「取当前模型」的函数挂上去。
  // 模型选择策略：已下载的 LLM 模型里取第一个（服务设置页将来可细化）。
  val context = LocalContext.current
  val localApiServer = remember {
    EntryPointAccessors.fromApplication(
      context.applicationContext,
      LocalApiServerEntryPoint::class.java,
    ).localApiServer()
  }
  // 【N5】定时任务 AI 触发也用同一份「当前本地模型」桥接。
  val taskAiInvoker = remember {
    EntryPointAccessors.fromApplication(
      context.applicationContext,
      LocalApiServerEntryPoint::class.java,
    ).taskAiInvoker()
  }
  LaunchedEffect(localApiServer, taskAiInvoker) {
    val provider = { modelManagerViewModel.getAllDownloadedModels().firstOrNull() }
    localApiServer.modelProvider = provider
    taskAiInvoker.modelProvider = provider
  }

  // 【T05-① 资源生命周期】低内存时才释放模型实例。
  //
  // 【设计要点】
  // - 退页不再清理（模型保活：用户切走再回来无需重新加载）；
  // - 单纯转后台**也不**释放 —— 否则保活就失去意义（切回来又要重载几个 GB）；
  // - 仅在系统发出中度以上内存压力（TRIM_MEMORY_MODERATE 及以上）或 onLowMemory() 时释放，
  //   这是「保活」与「不拖垮系统」之间的平衡点。
  //
  // 说明：注册在 applicationContext 上，生命周期与进程一致；DisposableEffect 负责反注册，
  // 避免组合销毁后泄漏回调。
  DisposableEffect(modelManagerViewModel) {
    val appContext = context.applicationContext
    val callbacks =
      object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
          if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Log.w(TAG, "onTrimMemory(level=$level): releasing all model instances.")
            try {
              modelManagerViewModel.cleanupAllModels()
            } catch (e: Exception) {
              Log.e(TAG, "cleanupAllModels failed on trim memory.", e)
            }
          }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
          // 配置变化与模型内存无关，无需处理。
        }

        override fun onLowMemory() {
          Log.w(TAG, "onLowMemory(): releasing all model instances.")
          try {
            modelManagerViewModel.cleanupAllModels()
          } catch (e: Exception) {
            Log.e(TAG, "cleanupAllModels failed on low memory.", e)
          }
        }
      }
    appContext.registerComponentCallbacks(callbacks)
    onDispose { appContext.unregisterComponentCallbacks(callbacks) }
  }

  GalleryNavHost(navController = navController, modelManagerViewModel = modelManagerViewModel)
}

/** Hilt 入口：组合层取 LocalApiServer 单例。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalApiServerEntryPoint {
  fun localApiServer(): LocalApiServer
  fun taskAiInvoker(): TaskAiInvoker
}
