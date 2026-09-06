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

import android.app.Application
import android.util.Log
import com.encourage.app.data.DataStoreRepository
import com.encourage.app.data.server.LocalServerService
import com.encourage.app.notifications.NotificationScheduleManager
import com.encourage.app.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager
  @Inject lateinit var localServerService: LocalServerService

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()

    // 设置全局未捕获异常处理器，捕获 Java 层崩溃并记录到 DebugLog
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      DebugLog.fatal("UncaughtException", "线程: ${thread.name}\n${throwable.stackTraceToString().take(1000)}")
      Log.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)
      // 延迟 1 秒让日志写入，然后交给默认处理器处理崩溃
      try { Thread.sleep(1000) } catch (_: InterruptedException) {}
      defaultHandler?.uncaughtException(thread, throwable)
    }

    DebugLog.i(TAG, "应用启动，版本: ${BuildConfig.VERSION_NAME}")

    // Set ADSP_LIBRARY_PATH so the Hexagon NPU/DSP can find
    // libQnnHtpV79Skel.so via FastRPC. The NPU runs on a separate RTOS and
    // cannot access the app's private install dir by default.
    try {
      val nativeLibDir = applicationInfo.nativeLibraryDir
      android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, true)
      DebugLog.i(TAG, "ADSP_LIBRARY_PATH 设置成功: $nativeLibDir")
      Log.d(TAG, "ADSP_LIBRARY_PATH set to: $nativeLibDir")
    } catch (e: Exception) {
      DebugLog.e(TAG, "设置 ADSP_LIBRARY_PATH 失败", e)
      Log.w(TAG, "Failed to set ADSP_LIBRARY_PATH", e)
    }

    // 检查 native 库是否存在
    try {
      val libDir = java.io.File(applicationInfo.nativeLibraryDir)
      val libs = libDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
      DebugLog.d(TAG, "nativeLibraryDir 库列表 (${libs.size}个): ${libs.joinToString(", ")}")
      val npuLibs = listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnHtpV79Skel.so", "libQnnSystem.so")
      val missing = npuLibs.filter { !libs.contains(it) }
      if (missing.isEmpty()) {
        DebugLog.i(TAG, "✅ 所有 NPU 库已就位")
      } else {
        DebugLog.e(TAG, "❌ 缺少 NPU 库: ${missing.joinToString(", ")}")
      }
    } catch (e: Exception) {
      DebugLog.e(TAG, "检查 native 库失败", e)
    }

    // Initialize the notification schedule manager to load the scheduled notifications from the
    // disk.
    notificationScheduleManager.initialize()

    // M4：本地 API Server 自动启停（监听配置 Flow）。
    localServerService.initialize(applicationScope)

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // 去谷歌化（M0）：移除 FirebaseApp 初始化与统计开关设置，无任何统计上报。
  }

  companion object {
    private const val TAG = "GalleryApplication"
  }
}
