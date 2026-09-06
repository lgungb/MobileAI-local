/*
 * Encourage — 功能开关展示区（M3 / 商业化预留）
 *
 * 【功能说明】
 * 设置界面中的「功能开关」区块：表驱动地列出 [FeatureFlags.ALL] 里的全部开关位，
 * 每个显示名称、说明与当前开放状态。
 *
 * 【为什么是只读展示】
 * 现阶段所有能力全部免费开放（常量全为 true），没有用户可切换的状态，
 * 因此这里只做「告知」，不做开关。将来订阅制上线、某开关位变为付费能力时：
 * 1. 把 [FeatureFlags] 里对应常量改为从订阅状态 / 远程配置读取；
 * 2. 本区块自动显示「已锁定」状态（表驱动，无需改 UI 代码）。
 *
 * 【使用方法】
 *   // 在设置对话框内
 *   FeatureFlagSection()
 */

package com.encourage.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.encourage.app.R
import com.encourage.app.data.FeatureFlag
import com.encourage.app.data.FeatureFlags

/**
 * 设置界面中的「功能开关」区块（只读展示）。
 *
 * 表驱动：遍历 [FeatureFlags.ALL]，无需在 UI 层维护开关列表。
 */
@Composable
fun FeatureFlagSection(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      stringResource(R.string.feature_flags_title),
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      stringResource(R.string.feature_flags_subtitle),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (flag in FeatureFlags.ALL) {
      FeatureFlagRow(flag)
      HorizontalDivider()
    }
  }
}

/** 单个开关位的行：名称 + 说明 + 状态徽标（开放 / 锁定）。 */
@Composable
private fun FeatureFlagRow(flag: FeatureFlag) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(stringResource(flag.labelRes), style = MaterialTheme.typography.bodyMedium)
      Text(
        stringResource(flag.descriptionRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (flag.enabled) {
      Icon(
        Icons.Rounded.CheckCircle,
        contentDescription = stringResource(R.string.feature_flag_enabled),
        tint = MaterialTheme.colorScheme.primary,
      )
    } else {
      Icon(
        Icons.Rounded.Lock,
        contentDescription = stringResource(R.string.feature_flag_locked),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
