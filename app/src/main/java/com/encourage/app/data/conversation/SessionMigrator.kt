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

package com.encourage.app.data.conversation

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import com.encourage.app.proto.UserData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "SessionMigrator"

/** 迁移完成标记在 `UserData.secrets` 里的键。 */
private const val MIGRATION_FLAG = "session_file_migration_v1"

/** 备份目录（相对 `filesDir`）。 */
private const val BACKUP_DIR = "migration_backup"

/** 最多保留几份 user_data.pb 备份。 */
private const val MAX_BACKUPS = 2

/**
 * 迁移结果。
 *
 * @property migrated 本次成功写成独立文件的会话数。
 * @property skipped 已存在文件或 id 为空而跳过的会话数（可安全重复执行）。
 * @property failed 写入失败的会话数；大于 0 时不会写入完成标记，下次会重试。
 * @property backupPath 迁移前备份的 `user_data.pb` 路径；没备份则 null。
 * @property alreadyDone 是否此前已经迁移过（本次什么都没做）。
 */
data class SessionMigrationResult(
  val migrated: Int,
  val skipped: Int,
  val failed: Int,
  val backupPath: String?,
  val alreadyDone: Boolean,
)

/**
 * 【P1 会话文件化】把 `UserData.chat_sessions` 里的会话拆成独立文件。
 *
 * 安全策略（写这些之前先看一遍，改动别破坏它）：
 * 1. **先备份再动数据**：迁移前把整个 `user_data.pb` 复制到 `files/migration_backup/`，
 *    只保留最近 [MAX_BACKUPS] 份。最坏情况把备份覆盖回去即可回到旧版本。
 * 2. **只写不删**：迁移**不会**清空 `UserData.chat_sessions`。切读路径是后续独立一步
 *    （P1b），在那之前旧字段始终是权威数据源，随时可以回退。
 * 3. **可重复执行**：已有独立文件的会话直接跳过，失败不写完成标记，下次续跑。
 * 4. **失败不阻塞启动**：所有异常只记日志，调用方拿到 failed 计数自行决定是否提示。
 */
@Singleton
class SessionMigrator
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val userDataDataStore: DataStore<UserData>,
  private val sessionFileStore: SessionFileStore,
) {

  /**
   * 执行（或在已完成后跳过）迁移。
   *
   * 建议调用点：App 启动后的第一个后台协程里 `viewModelScope.launch(Dispatchers.IO)`，
   * 不要放在 `Application.onCreate` 主线程上。
   */
  suspend fun migrateIfNeeded(): SessionMigrationResult = withContext(Dispatchers.IO) {
    val userData =
      try {
        userDataDataStore.data.first()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to read UserData before migration.", e)
        return@withContext SessionMigrationResult(0, 0, 0, null, alreadyDone = false)
      }

    if (userData.secretsMap[MIGRATION_FLAG] == "done") {
      Log.d(TAG, "Session file migration already done; skip.")
      return@withContext SessionMigrationResult(0, 0, 0, null, alreadyDone = true)
    }

    val sessions = userData.chatSessionsList
    if (sessions.isEmpty()) {
      Log.d(TAG, "No legacy sessions to migrate; mark as done.")
      markMigrationDone()
      return@withContext SessionMigrationResult(0, 0, 0, null, alreadyDone = false)
    }

    // 先备份：后续无论成功失败，用户都有一条回退路径。
    val backup = backupUserDataFile()

    // 读取一次现有索引：用于「增量」判断，避免每次重跑都把全部会话重写一遍。
    val existingById = sessionFileStore.listSessions().associateBy { it.sessionId }

    var migrated = 0
    var skipped = 0
    var failed = 0
    for (session in sessions) {
      if (session.sessionId.isBlank()) {
        skipped++
        continue
      }
      // 索引里已有且时间戳不比旧字段旧 → 文件是最新的，跳过。
      // 反之（文件缺失、或旧字段更新过）则以 UserData 为准覆盖写入。
      val prior = existingById[session.sessionId]
      if (prior != null && prior.timestampMs >= session.timestampMs) {
        skipped++
        continue
      }
      if (sessionFileStore.writeSession(session)) {
        migrated++
      } else {
        failed++
        Log.w(TAG, "Failed to migrate session '${session.sessionId}'.")
      }
    }

    // 只有全部成功才落完成标记；有失败就留待下次重试（已写的会被 skip）。
    if (failed == 0) {
      markMigrationDone()
    }

    Log.d(
      TAG,
      "Session migration finished: migrated=$migrated, skipped=$skipped, failed=$failed, " +
        "backup=${backup?.absolutePath ?: "none"}",
    )
    SessionMigrationResult(
      migrated = migrated,
      skipped = skipped,
      failed = failed,
      backupPath = backup?.absolutePath,
      alreadyDone = false,
    )
  }

  /**
   * 清理「索引里有、但 [liveSessionIds] 里已不存在」的孤儿会话文件。
   *
   * ⚠️ **不要在 P1b 切换读取路径之前自动调用**：那会把「只存在于新文件里的会话」误删。
   * 正确时机是切换读取路径的那一刻，用旧字段的 sessionId 集合调用一次，
   * 把「旧路径删掉、文件还留着」的会话清掉，避免切换后它们复活。
   *
   * @return 清理掉的条目数。
   */
  suspend fun pruneOrphans(liveSessionIds: Set<String>): Int = withContext(Dispatchers.IO) {
    var removed = 0
    sessionFileStore
      .listSessions()
      .filter { it.sessionId !in liveSessionIds }
      .forEach { entry ->
        // 文件可能已被删掉，但索引条目仍需清除，所以这里不判断返回值。
        sessionFileStore.deleteSession(entry.sessionId)
        removed++
      }
    if (removed > 0) {
      Log.d(TAG, "Pruned $removed orphan session file(s).")
    }
    removed
  }

  /** 清除完成标记，仅用于调试 / 重新演练迁移流程。 */
  suspend fun resetMigrationFlag() {
    try {
      userDataDataStore.updateData { userData ->
        userData.toBuilder().removeSecrets(MIGRATION_FLAG).build()
      }
      Log.d(TAG, "Migration flag cleared.")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clear migration flag.", e)
    }
  }

  private suspend fun markMigrationDone() {
    try {
      userDataDataStore.updateData { userData ->
        userData.toBuilder().putSecrets(MIGRATION_FLAG, "done").build()
      }
    } catch (e: Exception) {
      // 标记写失败不影响已迁移的文件，下次会重跑（文件已存在会被 skip）。
      Log.e(TAG, "Failed to persist migration flag.", e)
    }
  }

  /**
   * 备份 `user_data.pb`。返回备份文件；源文件不存在或复制失败返回 null。
   *
   * 备份失败**不会**中断迁移，但会在日志里明确写出来。
   */
  private fun backupUserDataFile(): File? {
    return try {
      val source = context.dataStoreFile("user_data.pb")
      if (!source.exists()) {
        Log.w(TAG, "user_data.pb not found; skip backup.")
        return null
      }
      val dir = File(context.filesDir, BACKUP_DIR).apply { mkdirs() }
      val dest = File(dir, "user_data-${System.currentTimeMillis()}.pb")
      source.copyTo(target = dest, overwrite = true)
      Log.d(TAG, "Backed up user_data.pb to '${dest.absolutePath}'.")
      // 只保留最近几份，避免反复迁移演练把用户空间吃满。
      dir
        .listFiles { file -> file.isFile && file.name.startsWith("user_data-") }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(MAX_BACKUPS)
        ?.forEach { stale -> stale.delete() }
      dest
    } catch (e: Exception) {
      Log.e(TAG, "Failed to back up user_data.pb before migration.", e)
      null
    }
  }
}
