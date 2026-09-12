/*
 * Encourage — 会话列表项（会话列表改造 T02 / N5）
 *
 * 【功能说明】
 * 微信式会话列表的单个条目：左侧类型头像 + 别名（主标题）+ 模型·类型 + 最后消息摘要 + 时间。
 * 支持两种删除入口（二选一即可触发删除确认）：
 *   - 左滑（内容左移露出红色「删除」背景，超过阈值即请求删除确认）；
 *   - 长按。
 *
 * 【设计要点】
 * 1. 点击 / 长按用 combinedClickable；左滑用 detectHorizontalDragGestures（只吃横向手势，
 *    不与列表纵向滚动冲突），均使用稳定 API，规避 SwipeToDismissBox 的版本漂移风险。
 * 2. 字段兜底：别名 / 摘要为空用占位文案；时间戳 <= 0 不渲染时间；绝不 !!。
 */

package com.encourage.app.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.encourage.app.R
import com.encourage.app.data.conversation.ConversationProfile
import com.encourage.app.data.conversation.ConversationType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 左滑可露出的背景宽度（触发删除确认的滑动阈值按此宽度的 50% 计）。 */
private val SwipeRevealWidth = 96.dp

/**
 * 单个会话列表项。
 *
 * @param profile 记录数据。
 * @param onClick 点击回调（T03 接导航，本组件不自行导航）。
 * @param onRequestDelete 请求删除回调（由父级弹二次确认；本组件不直接删除）。
 * @param modifier 外部修饰。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListItem(
  profile: ConversationProfile,
  onClick: () -> Unit,
  onRequestDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val revealPx = with(LocalDensity.current) { SwipeRevealWidth.toPx() }
  var offsetX by remember(profile.id) { mutableFloatStateOf(0f) }

  val alias = profile.alias.ifBlank { stringResource(R.string.conversation_profile_unnamed) }
  val typeLabel = stringResource(profile.type.labelRes())
  val modelText =
    profile.modelName.ifBlank { stringResource(R.string.conversation_profile_unnamed_model) }
  val preview = profile.lastMessagePreview
  val yesterdayLabel = stringResource(R.string.conversation_time_yesterday)
  val timeText =
    remember(profile.lastUsedAtMs, yesterdayLabel) {
      formatConversationTime(profile.lastUsedAtMs, yesterdayLabel)
    }

  Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
    // 背景：左滑时露出「删除」提示。
    Box(
      modifier =
        Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer),
      contentAlignment = Alignment.CenterEnd,
    ) {
      Row(
        modifier = Modifier.padding(end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          Icons.Rounded.Delete,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
          stringResource(R.string.conversation_delete_action),
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }

    // 前景内容：点击/长按/左滑。
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .offset { IntOffset(offsetX.roundToInt(), 0) }
          .background(MaterialTheme.colorScheme.surface)
          .pointerInput(profile.id) {
            detectHorizontalDragGestures(
              onDragEnd = {
                if (offsetX <= -revealPx * 0.5f) {
                  onRequestDelete()
                }
                offsetX = 0f
              },
              onDragCancel = { offsetX = 0f },
              onHorizontalDrag = { change, dragAmount ->
                change.consume()
                offsetX = (offsetX + dragAmount).coerceIn(-revealPx, 0f)
              },
            )
          }
          .combinedClickable(onClick = onClick, onLongClick = onRequestDelete)
          .padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 类型头像。
      Box(
        modifier =
          Modifier.size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          profile.type.icon(),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )
      }
      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            alias,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (timeText.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              timeText,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
            )
          }
        }
        Text(
          "$modelText · $typeLabel",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (preview.isNotBlank()) {
          Text(
            preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/** 会话类型 → 展示图标。 */
private fun ConversationType.icon(): ImageVector =
  when (this) {
    ConversationType.CHAT -> Icons.AutoMirrored.Rounded.Chat
    ConversationType.IMAGE -> Icons.Rounded.Image
    ConversationType.AUDIO -> Icons.Rounded.Mic
    ConversationType.AGENT -> Icons.Rounded.SmartToy
  }

/** 会话类型 → 文案资源。 */
@StringRes
private fun ConversationType.labelRes(): Int =
  when (this) {
    ConversationType.CHAT -> R.string.conversation_type_chat
    ConversationType.IMAGE -> R.string.conversation_type_image
    ConversationType.AUDIO -> R.string.conversation_type_audio
    ConversationType.AGENT -> R.string.conversation_type_agent
  }

/**
 * 时间展示：今天显示时刻（HH:mm），昨天显示「昨天」，同年显示「MM-dd」，更早显示「yyyy-MM-dd」。
 *
 * @param ms Unix 毫秒时间戳；`<= 0` 时返回空串（不渲染）。
 * @param yesterdayLabel 「昨天」的本地化文案。
 */
private fun formatConversationTime(ms: Long, yesterdayLabel: String): String {
  if (ms <= 0L) return ""
  val now = Calendar.getInstance()
  val then = Calendar.getInstance().apply { timeInMillis = ms }
  val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
  val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
  return when {
    sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    isYesterday(now, then) -> yesterdayLabel
    sameYear -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))
    else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
  }
}

/** 判断 [then] 是否为 [now] 的前一天。 */
private fun isYesterday(now: Calendar, then: Calendar): Boolean {
  val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
  return yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
    yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}
