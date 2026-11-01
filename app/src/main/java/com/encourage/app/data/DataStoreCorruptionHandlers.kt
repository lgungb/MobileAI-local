/*
 * Copyright 2026 Google LLC
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

package com.encourage.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.File

private const val TAG = "DataStoreCorruption"

/** 每个 DataStore 最多保留几份损坏备份，避免反复损坏时把用户空间占满。 */
private const val MAX_BACKUPS_PER_STORE = 3

/**
 * 【P0 止血】构造一个「先备份、再重置」的 DataStore 损坏兜底 handler。
 *
 * 背景：项目里 5 个 DataStore 原先都没设置 `corruptionHandler`。`.pb` 文件一旦解析失败
 * （写入过程中被系统杀掉、磁盘异常、字段号被改错等），每次启动都会在同一个位置抛
 * `CorruptionException`，表现就是「开屏即崩、清数据才能恢复」。
 *
 * 这个 handler 做两件事：
 * 1. 把损坏文件复制成 `xxx.pb.corrupt-<时间戳>`，用户数据还有救回的余地；
 * 2. 返回该类型的默认实例，让 App 能正常启动（对应功能回到初始状态，但不会崩）。
 *
 * 注意：备份发生在 [ReplaceFileCorruptionHandler] 的 `produceNewData` 回调里，
 * 此时原文件仍在磁盘上；备份失败只记日志，绝不因为备份失败而让启动失败。
 *
 * @param context 用于定位 `files/datastore/` 目录。
 * @param fileName 目标 DataStore 文件名，例如 `user_data.pb`。
 * @param produceNewData 返回该 proto 的默认实例。
 */
fun <T> backupReplaceCorruptionHandler(
  context: Context,
  fileName: String,
  produceNewData: () -> T,
): ReplaceFileCorruptionHandler<T> =
  backupReplaceCorruptionHandler(
    source = context.dataStoreFile(fileName),
    fileName = fileName,
    produceNewData = produceNewData,
  )

/**
 * 同上，但由调用方直接给出文件路径。
 *
 * 有些 store 并不在 `files/datastore/` 下（例如 `NotificationScheduleManager` 把
 * `scheduled_notifications.pb` 直接放在 `filesDir`）。用 [context] 版本会按
 * `dataStoreFile()` 去找而找不到文件 → 备份静默失效、只剩默认值兜底。
 *
 * @param source 该 DataStore 实际落盘的文件。
 * @param fileName 仅用于备份文件命名与日志（一般等于 `source.name`）。
 */
fun <T> backupReplaceCorruptionHandler(
  source: File,
  fileName: String,
  produceNewData: () -> T,
): ReplaceFileCorruptionHandler<T> =
  ReplaceFileCorruptionHandler {
    backupCorruptedFile(source = source, fileName = fileName)
    produceNewData()
  }

/**
 * 把损坏的 DataStore 文件另存一份，并清理超出 [MAX_BACKUPS_PER_STORE] 的旧备份。
 *
 * 全程 try-catch：备份只是「尽力而为」，不能影响启动流程。
 */
private fun backupCorruptedFile(source: File, fileName: String) {
  try {
    if (!source.exists()) {
      Log.w(TAG, "Corrupted '$fileName' not found on disk; skip backup.")
      return
    }
    val backupName = "$fileName.corrupt-${System.currentTimeMillis()}"
    val backup = File(source.parentFile, backupName)
    source.copyTo(target = backup, overwrite = true)
    Log.w(TAG, "DataStore '$fileName' is corrupted; backed up to '$backupName'.")
    trimOldBackups(directory = source.parentFile, fileName = fileName)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to back up corrupted DataStore file '$fileName'.", e)
  }
}

/** 只保留最近的若干份 `<fileName>.corrupt-*` 备份，旧的删掉。 */
private fun trimOldBackups(directory: File?, fileName: String) {
  try {
    val backups =
      directory
        ?.listFiles { file -> file.isFile && file.name.startsWith("$fileName.corrupt-") }
        ?.sortedByDescending { it.lastModified() }
        ?: return
    if (backups.size <= MAX_BACKUPS_PER_STORE) return
    backups.drop(MAX_BACKUPS_PER_STORE).forEach { stale ->
      if (stale.delete()) {
        Log.d(TAG, "Removed stale backup '${stale.name}'.")
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to trim stale backups for '$fileName'.", e)
  }
}
