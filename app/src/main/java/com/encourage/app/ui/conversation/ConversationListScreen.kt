/*
 * Encourage — 会话列表页（会话列表改造 T02 / N4）
 *
 * 【功能说明】
 * 微信式会话列表，供「对话」Tab（type ∈ {CHAT, IMAGE, AUDIO}）与「功能」Tab（type = AGENT）
 * 共用，靠 [types] 参数过滤区分：
 * - 列表按 lastUsed 倒序（数据源已排序）；
 * - 点击条目回调 [onProfileClick]（导航由 T03 接入，本页不自行跳转）；
 * - 左滑 / 长按 → 二次确认后删除（文案明确「连聊天记录一起删」）；
 * - 空态引导去「模型」Tab（[onNavigateToModels]）；
 * - 右下角悬浮「+」新建会话（[onCreateNew]，T06 补的入口）。
 *
 * 【设计要点】
 * 1. 复用 ui/common/EmptyState。列表项复用 ConversationListItem。
 * 2. 健壮性：空列表 / 字段为空 / 时间戳为 0 均有兜底；绝不高空断言。
 * 3. 孤儿记录处理：迁移可能产生 modelName 为空的记录，此类记录无法打开（无模型可路由），
 *    故在列表过滤阶段剔除，避免出现点不动的死条目。
 * 4. 删除确认对话框状态由本页持有（UI 关注点），确认后才调用 VM 删除。
 */

package com.encourage.app.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.encourage.app.R
import com.encourage.app.data.conversation.ConversationProfile
import com.encourage.app.data.conversation.ConversationType
import com.encourage.app.ui.common.EmptyState
import com.encourage.app.ui.common.EmptyStateButtonConfig

/**
 * 会话列表页。
 *
 * @param types 需要展示的会话类型集合（对话 Tab 传 {CHAT, IMAGE, AUDIO}；功能 Tab 传 {AGENT}）。
 * @param onProfileClick 点击某条记录（T03 接导航）。
 * @param onNavigateToModels 空态「去模型」回调（T03 接 Tab 切换）。
 * @param onCreateNew 点击右下角悬浮「+」：用当前已下载模型新建一条记录并进入会话。
 *   导航层负责「选模型 → 建记录 → 跳转」，本页只负责触发与展示，不感知模型状态。
 *   没有任何已下载模型时由导航层引导去模型管理页，绝不崩溃。
 * @param modifier 外部修饰。
 * @param titleResId 顶栏标题文案资源。
 * @param viewModel 列表 ViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
  types: Set<ConversationType>,
  onProfileClick: (ConversationProfile) -> Unit,
  onNavigateToModels: () -> Unit,
  onCreateNew: () -> Unit,
  modifier: Modifier = Modifier,
  @StringRes titleResId: Int = R.string.bottom_nav_tab_chat,
  viewModel: ConversationListViewModel = hiltViewModel(),
) {
  val allProfiles by viewModel.profiles.collectAsState()
  val profiles =
    remember(allProfiles, types) {
      allProfiles.filter { profile ->
        // 孤儿记录兜底：无模型名的记录无法打开，直接剔除。
        profile.modelName.isNotBlank() && types.contains(profile.type)
      }
    }
  // 待确认删除的记录（null = 不显示对话框）。
  var pendingDelete by remember { mutableStateOf<ConversationProfile?>(null) }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = { TopAppBar(title = { Text(stringResource(titleResId)) }) },
    // 【新建入口】右下角悬浮「+」：这是本页唯一的「开一个新会话」入口。
    // 之前列表页只能看、只能删，新装用户进来是空列表且无从下手 —— 这个按钮补上这个缺口。
    floatingActionButton = {
      FloatingActionButton(onClick = onCreateNew) {
        Icon(
          imageVector = Icons.Rounded.Add,
          contentDescription = stringResource(R.string.conversation_new_cd),
        )
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      if (profiles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          EmptyState(
            icon = Icons.AutoMirrored.Rounded.Chat,
            titleResId = R.string.conversation_list_empty_title,
            descriptionResId = R.string.conversation_list_empty_message,
            buttonConfig =
              EmptyStateButtonConfig(
                buttonLabelResId = R.string.conversation_list_empty_action,
                onButtonClick = onNavigateToModels,
              ),
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(
            items = profiles,
            key = { profile -> profile.id.ifBlank { profile.hashCode().toString() } },
          ) { profile ->
            ConversationListItem(
              profile = profile,
              onClick = {
                // 先刷新「最后使用时间」，再交给导航层（保证返回列表时该记录置顶）。
                viewModel.touchLastUsed(profile.id)
                onProfileClick(profile)
              },
              onRequestDelete = { pendingDelete = profile },
            )
          }
        }
      }
    }
  }

  val target = pendingDelete
  if (target != null) {
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text(stringResource(R.string.conversation_delete_profile_title)) },
      text = { Text(stringResource(R.string.conversation_delete_profile_message)) },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteProfile(target.id)
            pendingDelete = null
          }
        ) {
          Text(stringResource(R.string.conversation_delete_profile_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) {
          Text(stringResource(R.string.conversation_delete_profile_cancel))
        }
      },
    )
  }
}
