/*
 * Encourage — 语音设置界面（M3 / 需求 D1 D3）
 *
 * 【功能说明】
 * 设置界面中的「语音」区块，以及独立的语音设置对话框。
 * 用户在这里选择朗读引擎、语速、音调、音色角色，并可试听效果。
 *
 * 【交互设计要点】
 * 1. **试听按钮走当前引擎**：用户调完滑块立即点试听，听到的就是将来的朗读效果。
 * 2. **滑块松手才落盘**：拖动过程中只更新本地预览，onValueChangeFinished 才写 DataStore，
 *    避免拖动一次写一次（DataStore 写是 I/O 操作）。
 * 3. **离线引擎选项在未接入时可见但标注**：Sherpa-ONNX 引擎（M3-6）未就绪时，
 *    选择它会回退系统 TTS 并在界面提示「需要下载离线语音包」。
 * 4. **多角色选项是离线引擎专属**：系统 TTS 没有稳定的角色 API，角色表里的
 *    非默认角色全部标注「需离线引擎」，选择后朗读自动回退默认角色。
 *
 * 【文件组成】
 * - [VoiceSettingsSection]       设置对话框内的区块（摘要 + 管理入口）
 * - [VoiceSettingsDialog]        完整的语音设置对话框
 *
 * 【使用方法】
 *   // 在设置对话框内
 *   VoiceSettingsSection(viewModel = voiceSettingsViewModel) { showVoiceSettings = true }
 *
 *   // 独立弹出设置页
 *   if (showVoiceSettings) {
 *     VoiceSettingsDialog(viewModel = voiceSettingsViewModel) { showVoiceSettings = false }
 *   }
 */

package com.encourage.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import com.encourage.app.R
import com.encourage.app.data.speech.VoiceRoles
import com.encourage.app.data.speech.VoiceSettings
import com.encourage.app.proto.VoiceEngine
import com.encourage.app.speech.OfflineTtsEngine
import com.encourage.app.speech.VoiceEngineSnapshot
import com.encourage.app.speech.VoicePackState
import com.encourage.app.speech.VoiceSettingsSnapshot
import com.encourage.app.speech.rememberSpeechManager
import kotlinx.coroutines.flow.collect

/**
 * 设置界面中的「语音」区块：引擎 + 语速 + 音调的摘要，点击进入完整设置页。
 */
@Composable
fun VoiceSettingsSection(
  viewModel: VoiceSettingsViewModel = hiltViewModel(),
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val settings by viewModel.settings.collectAsState()
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.voice_settings_title),
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
          stringResource(R.string.voice_settings_summary, engineLabel(settings.engine)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(onClick = onOpenSettings) {
        Text(stringResource(R.string.voice_settings_open))
      }
    }
  }
}

/** 完整语音设置对话框。 */
@Composable
fun VoiceSettingsDialog(
  viewModel: VoiceSettingsViewModel = hiltViewModel(),
  onDismissed: () -> Unit,
) {
  val settings by viewModel.settings.collectAsState()
  val packState by viewModel.packState.collectAsState()
  // 语音包是否已就绪（model.onnx 存在）。
  val packInstalled = viewModel.isDefaultPackInstalled()
  val context = LocalContext.current
  // 【Bug 修复】离线引擎生命周期统一由 DisposableEffect 管理：
  // 旧实现用 remember(packInstalled) + DisposableEffect(packInstalled)，onDispose 闭包
  // 捕获的是变量引用而非值——packInstalled 变化时 remember 先重建新引擎，onDispose
  // 再执行时释放的却是新引擎，导致旧引擎泄漏、新引擎被误释放，后续 speak 操作
  // 已释放对象 → native SIGSEGV 闪退。
  // 新方案：DisposableEffect 内创建引擎并赋值给外部状态，onDispose 释放闭包捕获的
  // 局部变量 engine（即本次 Effect 创建的那个引擎），保证创建与释放一一对应。
  var offlineTtsEngine by remember { mutableStateOf<OfflineTtsEngine?>(null) }
  DisposableEffect(packInstalled) {
    val engine = if (packInstalled) OfflineTtsEngine(context) else null
    offlineTtsEngine = engine
    onDispose {
      engine?.release()
    }
  }
  val speechManager =
    rememberSpeechManager(
      voiceSettingsProvider = {
        VoiceSettingsSnapshot(
          rate = settings.rate,
          pitch = settings.pitch,
          engine =
            if (settings.usesOfflineEngine) VoiceEngineSnapshot.SHERPA_ONNX
            else VoiceEngineSnapshot.SYSTEM,
          role = settings.role,
        )
      },
      offlineTtsEngine = offlineTtsEngine,
    )

  // 【N6-A】系统 TTS 最近一次失败原因（响应式），来源 SpeechManager.lastErrorFlow。
  // 过去只有 initFailed 一个布尔值，失败原因完全不可见，只能盲修；现在界面能直接显示。
  var systemTtsError by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(speechManager) {
    speechManager.lastErrorFlow.collect { systemTtsError = it }
  }

  // 滑块本地预览：拖动过程中只改本地值（跟手），松手（onValueChangeFinished）才落盘。
  // 设置从 DataStore 恢复时用 key(settings.rate / settings.pitch) 强制重建本地状态。
  var previewRate by remember(settings.rate) { mutableStateOf(settings.rate) }
  var previewPitch by remember(settings.pitch) { mutableStateOf(settings.pitch) }

  Dialog(onDismissRequest = onDismissed) {
    Card(shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(
          stringResource(R.string.voice_settings_title),
          style = MaterialTheme.typography.titleLarge,
        )

        // 引擎来源。
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            stringResource(R.string.voice_settings_engine),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
          )
          // 【Bug B 修复】本地导入目录选择器：OpenDocumentTree 返回可持久化的 tree URI，
          // ViewModel 用 DocumentFile.fromTreeUri 递归拷贝整个目录。
          // 旧实现用 OpenMultipleDocuments + fromSingleUri 无法枚举目录内容，导入必失败。
          val importLauncher =
            rememberLauncherForActivityResult(
              ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
              if (uri != null) viewModel.importDefaultPack(uri)
            }
          EngineSelector(
            settings = settings,
            onSelect = { viewModel.setEngine(it) },
            packState = packState,
            packInstalled = packInstalled,
            onDownloadPack = { viewModel.downloadDefaultPack() },
            onCancelPack = { viewModel.cancelPackDownload() },
            onImportPack = { importLauncher.launch(null) },
            onClearPack = { viewModel.clearVoicePack() },
          )
          // 【Bug A】离线引擎加载/合成失败时展示具体原因，避免「选了离线引擎却没声音」且无提示。
          var offlineError by remember { mutableStateOf<String?>(null) }
          LaunchedEffect(offlineTtsEngine) {
            offlineTtsEngine?.lastErrorFlow?.collect { offlineError = it }
          }
          if (settings.usesOfflineEngine && offlineError != null) {
            Text(
              offlineError.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          // 【N6-A】系统 TTS 失败原因：
          // 1) systemTtsError 由 speechManager.lastErrorFlow 驱动，实时反映最近一次失败；
          // 2) 这里不再限制「仅当选择了系统引擎时」才提示 —— 离线引擎初始化失败会回退
          //    系统 TTS，系统 TTS 同样不可用时就会出现「两个引擎都出不了声」的情况，
          //    必须让用户看见；
          // 3) 附带展示引擎返回的具体原因（initFailureReason），便于现场排查，
          //    而不是只看到一句「不可用」。
          if (systemTtsError != null || speechManager.initFailed) {
            Text(
              stringResource(R.string.voice_system_tts_error),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
            // 具体原因（如 ERROR 状态码翻译），仅在非空时展示。
            val reason = systemTtsError ?: speechManager.initFailureReason
            if (!reason.isNullOrBlank()) {
              Text(
                stringResource(R.string.voice_tts_reason, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            val context = LocalContext.current
            OutlinedButton(
              onClick = {
                // 系统 TTS 设置页。用 runCatching 兜底：少数定制 ROM 没有该入口，
                // 否则会直接抛 ActivityNotFoundException 崩溃。
                runCatching {
                  context.startActivity(
                    Intent("com.android.settings.TTS_SETTINGS")
                  )
                }
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.voice_open_tts_settings))
            }
          }
        }

        HorizontalDivider()

        // 语速。
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              stringResource(R.string.voice_settings_rate),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
              modifier = Modifier.weight(1f),
            )
            Text(
              "%.1fx".format(previewRate),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Slider(
            value = previewRate,
            onValueChange = { previewRate = it },
            onValueChangeFinished = { viewModel.setRate(previewRate) },
            valueRange = 0.5f..2.0f,
          )
        }

        // 音调。
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              stringResource(R.string.voice_settings_pitch),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
              modifier = Modifier.weight(1f),
            )
            Text(
              "%.1fx".format(previewPitch),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Slider(
            value = previewPitch,
            onValueChange = { previewPitch = it },
            onValueChangeFinished = { viewModel.setPitch(previewPitch) },
            valueRange = 0.5f..2.0f,
          )
        }

        HorizontalDivider()

        // 音色角色。
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            stringResource(R.string.voice_settings_role),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
          )
          RoleSelector(settings = settings, onSelect = { viewModel.setRole(it) })
          if (VoiceRoles.byId(settings.role).requiresOfflineEngine) {
            Text(
              stringResource(R.string.voice_settings_role_offline_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // 试听。
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = {
              speechManager.speakWithSettings(sampleText(settings.role))
              // 【N6-A】同步读取一次错误：speak() 内部是同步更新 lastError 的，
              // 因此这里能立刻拿到「静默失败」的原因并展示，而不是点了没反应。
              systemTtsError = speechManager.lastError
            },
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              Icons.AutoMirrored.Rounded.VolumeUp,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Text(stringResource(R.string.voice_settings_preview), modifier = Modifier.padding(start = 6.dp))
          }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismissed) { Text(stringResource(R.string.close)) }
        }
      }
    }
  }
}

/** 引擎来源选择：系统 TTS / 离线引擎，附语音包下载管理。 */
@Composable
private fun EngineSelector(
  settings: VoiceSettings,
  onSelect: (VoiceEngine) -> Unit,
  packState: VoicePackState,
  packInstalled: Boolean,
  onDownloadPack: () -> Unit,
  onCancelPack: () -> Unit,
  onImportPack: () -> Unit,
  onClearPack: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    EngineRow(
      title = stringResource(R.string.voice_engine_system),
      desc = stringResource(R.string.voice_engine_system_desc),
      selected = !settings.usesOfflineEngine,
      onClick = { onSelect(VoiceEngine.VOICE_ENGINE_SYSTEM) },
    )
    EngineRow(
      title = stringResource(R.string.voice_engine_offline),
      desc = stringResource(R.string.voice_engine_offline_desc),
      selected = settings.usesOfflineEngine,
      onClick = { onSelect(VoiceEngine.VOICE_ENGINE_SHERPA_ONNX) },
    )
    // 离线引擎选中时，展示语音包下载状态。
    if (settings.usesOfflineEngine) {
      VoicePackStatusCard(
        packState = packState,
        packInstalled = packInstalled,
        onDownloadPack = onDownloadPack,
        onCancelPack = onCancelPack,
        onImportPack = onImportPack,
        onClearPack = onClearPack,
      )
    }
  }
}

/** 离线语音包状态卡片：未下载 / 下载中 / 解压中 / 就绪 / 失败。 */
@Composable
private fun VoicePackStatusCard(
  packState: VoicePackState,
  packInstalled: Boolean,
  onDownloadPack: () -> Unit,
  onCancelPack: () -> Unit,
  onImportPack: () -> Unit,
  onClearPack: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      when {
        // 已就绪（以文件存在为准）。
        packInstalled ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              stringResource(R.string.voice_pack_ready),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearPack) {
              Text(stringResource(R.string.voice_pack_clear))
            }
          }

        // 下载中。
        packState is VoicePackState.Downloading -> {
          Text(
            stringResource(
              R.string.voice_pack_downloading,
              (packState.fraction * 100).toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
          )
          LinearProgressIndicator(
            progress = { packState.fraction },
            modifier = Modifier.fillMaxWidth(),
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancelPack) { Text(stringResource(R.string.cancel)) }
          }
        }

        // 解压中。
        packState is VoicePackState.Unzipping -> {
          Text(
            stringResource(R.string.voice_pack_unzipping),
            style = MaterialTheme.typography.bodySmall,
          )
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 下载失败。
        packState is VoicePackState.Failed -> {
          Text(
            stringResource(R.string.voice_pack_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          // 【Bug B】显示具体失败原因（如导入后还缺哪些文件），方便用户补齐。
          if (packState.message.isNotBlank()) {
            Text(
              packState.message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          Button(onClick = onDownloadPack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.voice_pack_retry))
          }
          // 本地导入兜底：网络下载失败时用户可手动放置模型文件。
          OutlinedButton(onClick = onImportPack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
              stringResource(R.string.voice_pack_import),
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }

        // 未下载。
        else -> {
          Text(
            stringResource(R.string.voice_pack_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          // 【N2】说明下载来源（ModelScope / hf-mirror / GitHub）。
          Text(
            stringResource(R.string.voice_pack_sources),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          // 【智能引导】明确本地导入所需的文件清单，避免用户只放 model.onnx。
          Text(
            stringResource(R.string.voice_pack_files_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(onClick = onDownloadPack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.voice_pack_download_btn))
          }
          OutlinedButton(onClick = onImportPack, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
              stringResource(R.string.voice_pack_import),
              modifier = Modifier.padding(start = 6.dp),
            )
          }
        }
      }
    }
  }
}

/** 单个引擎选项行。 */
@Composable
private fun EngineRow(
  title: String,
  desc: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (selected) MaterialTheme.colorScheme.secondaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
      ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
          desc,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (selected) {
        Text(
          stringResource(R.string.voice_settings_current),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

/** 音色角色下拉选择。 */
@Composable
private fun RoleSelector(settings: VoiceSettings, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val current = VoiceRoles.byId(settings.role)
  Column {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Text(
        stringResource(current.labelRes),
        modifier = Modifier.weight(1f),
        textAlign = TextAlign.Start,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      for (role in VoiceRoles.ALL) {
        DropdownMenuItem(
          text = { Text(stringResource(role.labelRes)) },
          onClick = {
            onSelect(role.id)
            expanded = false
          },
        )
      }
    }
  }
}

/** 试听用的示例文本：不同角色用不同内容的句子，便于听出差异。 */
private fun sampleText(roleId: String): String =
  when (roleId) {
    VoiceRoles.DEFAULT -> "你好，我是 Encourage，你的随身端侧助手。"
    "female_gentle" -> "你好呀，今天想聊点什么呢？"
    "male_natural" -> "你好，有什么需要帮忙的吗？"
    "child_clear" -> "你好，我们一起玩吧！"
    else -> "你好，我是 Encourage。"
  }

/** 引擎的显示名（设置区块摘要用）。 */
@Composable
private fun engineLabel(engine: VoiceEngine): String =
  stringResource(
    if (engine == VoiceEngine.VOICE_ENGINE_SHERPA_ONNX) R.string.voice_engine_offline
    else R.string.voice_engine_system
  )
