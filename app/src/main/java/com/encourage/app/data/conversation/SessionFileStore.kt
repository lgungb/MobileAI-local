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
import android.util.Base64
import android.util.Log
import com.encourage.app.proto.ChatSessionProto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "SessionFileStore"

/** 会话文件所在目录名（相对 `filesDir`）。 */
private const val SESSIONS_DIR = "sessions"

/** 索引文件名。 */
private const val INDEX_FILE = "index.tsv"

/**
 * 【P1 会话文件化】会话索引条目。
 *
 * 会话列表只需要这些字段，不必把每个会话的全部消息都读出来反序列化。
 * 索引用一行一条的 TSV 存储（标题做 Base64 编码，避免出现制表符/换行破坏结构），
 * 这样不引入新的 proto message、也不依赖任何 JSON 适配器。
 *
 * @property sessionId 会话 id。
 * @property title 会话标题（原始文本，读写时自动 Base64 编解码）。
 * @property timestampMs 会话创建/业务时间戳（沿用 [ChatSessionProto.timestampMs]）。
 * @property originalModel 生成该会话的模型名。
 * @property taskId 所属任务 id。
 * @property profileId 所属「特调记录」id；空串表示未绑定。
 * @property messageCount 消息条数（用于列表展示与排序辅助）。
 * @property updatedAtMs 最后一次写入时间（毫秒），列表默认按它倒序。
 * @property sizeBytes 会话文件大小，便于排查「哪个会话特别大」。
 */
data class SessionIndexEntry(
  val sessionId: String,
  val title: String,
  val timestampMs: Long,
  val originalModel: String,
  val taskId: String,
  val profileId: String,
  val messageCount: Int,
  val updatedAtMs: Long,
  val sizeBytes: Long,
)

/**
 * 【P1 会话文件化】单会话单文件存储。
 *
 * 背景：会话原先全部塞在 `UserData.chat_sessions` 这一个 repeated 字段里，
 * 每保存一条会话都要「清空全部 → 重新加回全部 → 整份序列化写盘」，写盘成本是
 * O(全部会话 × 全部消息)。会话一多，每次落盘窗口拉长，被杀/断电的机会也变大。
 *
 * 本类的目标：把写盘成本降到 **O(单条会话)**。
 *
 * 存储结构：
 * ```
 * filesDir/sessions/
 *   index.tsv            每行一条会话元数据（Base64 标题 + 若干字段）
 *   s_<id>-<hash>.pb     单条会话的 ChatSessionProto 二进制
 * ```
 *
 * 三条硬规则：
 * 1. **原子写**：先写 `.tmp`，再 rename 覆盖，避免写到一半留下半截文件；
 * 2. **索引串行化**：用 [indexMutex] 保证并发的索引更新不会互相覆盖；
 * 3. **读取容错**：任何解析失败都返回 null / 跳过该行并记日志，绝不抛给调用方。
 *
 * 注意：本类**不负责**与旧的 `UserData.chat_sessions` 同步，切换主读写路径由
 * `SessionMigrator` + 上层调用点完成（见 `doc/06_重构方案.md` P1）。
 */
@Singleton
class SessionFileStore @Inject constructor(@ApplicationContext private val context: Context) {

  private val indexMutex = Mutex()

  /** 首次加载索引时用的锁，保证并发调用只读一次盘。 */
  private val loadMutex = Mutex()

  /** 索引是否已从磁盘读入内存。 */
  @Volatile private var indexLoaded = false

  private val _indexState = MutableStateFlow<List<SessionIndexEntry>>(emptyList())

  /**
   * 会话索引的内存镜像，按 [SessionIndexEntry.updatedAtMs] 倒序。
   *
   * UI 直接订阅它即可：会话列表**不需要**为每条会话读一次 `.pb` 文件，
   * 只有在用户点开某条会话时才调用 [readSession] 取完整内容。
   * 写/删会话后本 Flow 会立即推送新值，所以列表是响应式的。
   *
   * 初始值为空列表，调用方需确保 [ensureIndexLoaded] 已被调用（`ChatViewModel` 初始化时触发）。
   */
  val indexState: StateFlow<List<SessionIndexEntry>> = _indexState.asStateFlow()

  private val sessionsDir: File
    get() = File(context.filesDir, SESSIONS_DIR).apply { if (!exists()) mkdirs() }

  private val indexFile: File
    get() = File(sessionsDir, INDEX_FILE)

  /** 会话 id → 安全的文件名。保留可读前缀，后缀拼 hash 防止不同 id 归一化后撞名。 */
  private fun fileFor(sessionId: String): File {
    val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    // 用 Long 承载 32 位哈希再转 16 进制：避免 UInt 相关 API 的实验性标注问题。
    val hash = sessionId.hashCode().toLong().and(0xFFFFFFFFL).toString(16)
    return File(sessionsDir, "s_${safe.ifEmpty { "session" }}-$hash.pb")
  }

  /**
   * 读取单条会话。
   *
   * @return 会话内容；文件不存在或解析失败时返回 null（调用方可回退到旧字段）。
   */
  suspend fun readSession(sessionId: String): ChatSessionProto? =
    withContext(Dispatchers.IO) {
      val file = fileFor(sessionId)
      if (!file.exists()) return@withContext null
      try {
        ChatSessionProto.parseFrom(file.readBytes())
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse session file '${file.name}'.", e)
        null
      }
    }

  /** 该会话是否已有独立文件。 */
  suspend fun exists(sessionId: String): Boolean =
    withContext(Dispatchers.IO) { fileFor(sessionId).exists() }

  /**
   * 写入单条会话（原子写 + 同步更新索引）。
   *
   * @return 写入成功返回 true；失败返回 false 并记日志，调用方应保留旧数据。
   */
  suspend fun writeSession(session: ChatSessionProto): Boolean =
    withContext(Dispatchers.IO) {
      if (session.sessionId.isBlank()) {
        Log.w(TAG, "Refuse to write session with blank id.")
        return@withContext false
      }
      val target = fileFor(session.sessionId)
      val tmp = File(target.parentFile, "${target.name}.tmp")
      try {
        tmp.writeBytes(session.toByteArray())
        if (!tmp.renameTo(target)) {
          // 某些文件系统上 rename 不能覆盖已存在文件，退回「先删再改名」。
          target.delete()
          if (!tmp.renameTo(target)) {
            Log.e(TAG, "Failed to move temp file into place for '${target.name}'.")
            tmp.delete()
            return@withContext false
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to write session file '${target.name}'.", e)
        tmp.delete()
        return@withContext false
      }

      val entry =
        SessionIndexEntry(
          sessionId = session.sessionId,
          title = session.title,
          timestampMs = session.timestampMs,
          originalModel = session.originalModel,
          taskId = session.taskId,
          profileId = session.profileId,
          messageCount = session.messagesCount,
          updatedAtMs = System.currentTimeMillis(),
          sizeBytes = target.length(),
        )
      upsertIndex(entry)
      true
    }

  /**
   * 删除单条会话（文件 + 索引条目）。
   *
   * @return 文件原本存在且已删除返回 true。
   */
  suspend fun deleteSession(sessionId: String): Boolean =
    withContext(Dispatchers.IO) {
      val file = fileFor(sessionId)
      val removed = if (file.exists()) file.delete() else false
      removeFromIndex(sessionId)
      removed
    }

  /**
   * 确保索引已从磁盘加载进内存。可重复调用，只有第一次真正读盘。
   *
   * 建议在 `ChatViewModel` 的 `init` 里于 IO 协程调用一次。
   */
  suspend fun ensureIndexLoaded() {
    if (indexLoaded) return
    loadMutex.withLock {
      if (indexLoaded) return@withLock
      val entries = indexMutex.withLock { readIndexLocked() }
      _indexState.value = entries.sortedByDescending { it.updatedAtMs }
      indexLoaded = true
    }
  }

  /**
   * 读取全部索引条目，按最后更新时间倒序。
   *
   * 索引某一行解析失败只跳过该行（并记日志），不影响整个列表。
   */
  suspend fun listSessions(): List<SessionIndexEntry> {
    ensureIndexLoaded()
    return _indexState.value
  }

  /**
   * 索引条目 → **只有元数据、不含任何消息**的 [ChatSessionProto]。
   *
   * 历史侧边栏只展示标题，用这个对象就够；点开某条时再 [readSession] 取全文，
   * 避免为了画一个列表把所有会话的正文都反序列化一遍。
   */
  fun toLiteProto(entry: SessionIndexEntry): ChatSessionProto =
    ChatSessionProto.newBuilder()
      .setSessionId(entry.sessionId)
      .setTitle(entry.title)
      .setTimestampMs(entry.timestampMs)
      .setOriginalModel(entry.originalModel)
      .setTaskId(entry.taskId)
      .setProfileId(entry.profileId)
      .build()

  /**
   * 读取全部会话的完整内容（导出 Markdown / 备份用）。
   *
   * 单条读失败只跳过该条，不中断整体；返回结果按 [SessionIndexEntry.updatedAtMs] 倒序。
   */
  suspend fun readAllSessions(): List<ChatSessionProto> {
    val entries = listSessions()
    return entries.mapNotNull { entry ->
      readSession(entry.sessionId) ?: run {
        Log.w(TAG, "Skip unreadable session '${entry.sessionId}' on full read.")
        null
      }
    }
  }

  // ---------------------------------------------------------------- 索引内部实现

  /** 调用方必须已持有 [indexMutex]。 */
  private fun readIndexLocked(): List<SessionIndexEntry> {
    val file = indexFile
    if (!file.exists()) return emptyList()
    return try {
      file.readLines().mapNotNull { line ->
        if (line.isBlank()) null else parseEntry(line)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read session index.", e)
      emptyList()
    }
  }

  private suspend fun upsertIndex(entry: SessionIndexEntry) {
    ensureIndexLoaded()
    indexMutex.withLock {
      val current = _indexState.value.filterNot { it.sessionId == entry.sessionId } + entry
      val sorted = current.sortedByDescending { it.updatedAtMs }
      writeIndexLocked(sorted)
      _indexState.value = sorted
    }
  }

  private suspend fun removeFromIndex(sessionId: String) {
    ensureIndexLoaded()
    indexMutex.withLock {
      val current = _indexState.value
      val remaining = current.filterNot { it.sessionId == sessionId }
      if (remaining.size != current.size) {
        writeIndexLocked(remaining)
        _indexState.value = remaining
      }
    }
  }

  private fun writeIndexLocked(entries: List<SessionIndexEntry>) {
    val tmp = File(sessionsDir, "$INDEX_FILE.tmp")
    try {
      tmp.writeText(entries.joinToString(separator = "\n") { encodeEntry(it) })
      if (!tmp.renameTo(indexFile)) {
        indexFile.delete()
        if (!tmp.renameTo(indexFile)) {
          Log.e(TAG, "Failed to move temp index into place.")
          tmp.delete()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write session index.", e)
      tmp.delete()
    }
  }

  private fun encodeEntry(entry: SessionIndexEntry): String {
    val encodedTitle =
      Base64.encodeToString(entry.title.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return listOf(
        entry.sessionId,
        encodedTitle,
        entry.timestampMs.toString(),
        entry.originalModel,
        entry.taskId,
        entry.profileId,
        entry.messageCount.toString(),
        entry.updatedAtMs.toString(),
        entry.sizeBytes.toString(),
      )
      .joinToString(separator = "\t")
  }

  private fun parseEntry(line: String): SessionIndexEntry? {
    val parts = line.split('\t')
    if (parts.size < 9) {
      Log.w(TAG, "Skip malformed index line (${parts.size} fields).")
      return null
    }
    return try {
      SessionIndexEntry(
        sessionId = parts[0],
        title =
          String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8),
        timestampMs = parts[2].toLongOrNull() ?: 0L,
        originalModel = parts[3],
        taskId = parts[4],
        profileId = parts[5],
        messageCount = parts[6].toIntOrNull() ?: 0,
        updatedAtMs = parts[7].toLongOrNull() ?: 0L,
        sizeBytes = parts[8].toLongOrNull() ?: 0L,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Skip unparseable index line.", e)
      null
    }
  }
}
