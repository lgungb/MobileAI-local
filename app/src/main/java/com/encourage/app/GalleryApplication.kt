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
import com.encourage.app.data.conversation.SessionMigrator
import com.encourage.app.data.server.LocalServerService
import com.encourage.app.notifications.NotificationScheduleManager
import com.encourage.app.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager
  @Inject lateinit var localServerService: LocalServerService
  @Inject lateinit var sessionMigrator: SessionMigrator

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()

    // 设置全局未捕获异常处理器：捕获 Java 层崩溃，记录日志后启动崩溃显示页，
    // 不直接闪退，让用户能看到完整堆栈并截图反馈。
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      val errorInfo =
        "线程: ${thread.name}\n" +
          "异常: ${throwable.javaClass.name}: ${throwable.message}\n" +
          throwable.stackTraceToString().take(8000)
      DebugLog.fatal("UncaughtException", errorInfo)
      Log.e(TAG, "Uncaught exception in thread: ${thread.name}", throwable)
      var crashPageStarted = false
      try {
        CrashDisplayActivity.start(this@GalleryApplication, errorInfo)
        crashPageStarted = true
      } catch (e: Throwable) {
        // 崩溃显示页也启动失败（例如崩溃发生在系统资源极度紧张时），交回系统默认处理器。
        defaultHandler?.uncaughtException(thread, throwable)
      }
      if (crashPageStarted) {
        // 【不要阻塞崩溃线程】原先这里是 Thread.sleep(500) 后再杀进程。
        // 若崩溃发生在主线程，sleep 会把主线程睡住，崩溃页反而拿不到 Looper 调度；
        // 改为独立线程延时退出，崩溃线程立刻返回，让崩溃页有机会绘制。
        Thread(
          {
            try {
              Thread.sleep(800)
            } catch (ignored: InterruptedException) {
              // 被中断就直接往下走，仍然要保证进程退出
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            Runtime.getRuntime().exit(10)
          },
          "crash-exit",
        )
          .start()
      }
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

    // 【P1b 会话文件化 · 切换】把文件层正式提升为会话的权威数据源。
    //
    // 顺序（改这段之前务必读一遍 SessionMigrator.cutoverIfNeeded 的注释）：
    //   1. 强制重跑一次迁移，补齐上一版只写旧字段的会话；
    //   2. 有失败就中止，保持旧路径，下次启动重试（数据不完整时绝不清理）；
    //   3. 用旧字段的 id 集合清掉「已删除但文件还在」的孤儿，防止切换后它们复活；
    //   4. 落 cutover 标记，保证只跑一次。
    //
    // 切换之后：会话读写都走 filesDir/sessions/，UserData.chat_sessions 只作为
    // 回退快照保留（不再更新）。备份在 files/migration_backup/。
    applicationScope.launch {
      try {
        val switched = sessionMigrator.cutoverIfNeeded()
        if (switched) {
          DebugLog.i(TAG, "会话存储已切换到文件层（读写主路径已切换）。")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Session file migration failed.", e)
      }
    }

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // 去谷歌化（M0）：移除 FirebaseApp 初始化与统计开关设置，无任何统计上报。
  }

  companion object {
    private const val TAG = "GalleryApplication"
  }
}
