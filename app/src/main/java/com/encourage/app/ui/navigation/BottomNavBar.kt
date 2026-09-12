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

package com.encourage.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.encourage.app.R
import com.encourage.app.ui.common.LocalIsTopLevelTab

/**
 * 顶层导航路由常量（微信式底部 5 Tab）。
 *
 * 约定：只有这 5 个顶层路由才显示底部导航栏；进入二级页（对话页 `route_model/{taskId}/{modelName}`、
 * 跑分 `benchmark/{modelName}`、按任务选模型 `model_list`、模型管理 `model_manager`、通知
 * `notifications`）时必须隐藏底栏，避免与对话输入框抢空间。
 */
object AppRoutes {
  const val TAB_CHAT = "tab_chat"
  const val TAB_AGENT = "tab_agent"
  const val TAB_LAB = "tab_lab"
  const val TAB_MODELS = "tab_models"
  const val TAB_SYSTEM = "tab_system"

  /** 5 个顶层 Tab 路由集合。 */
  val TOP_LEVEL_ROUTES: Set<String> =
    linkedSetOf(TAB_CHAT, TAB_AGENT, TAB_LAB, TAB_MODELS, TAB_SYSTEM)

  /** 判断给定路由是否为顶层 Tab（用于决定是否显示底栏）。 */
  fun isTopLevelRoute(route: String?): Boolean = route != null && TOP_LEVEL_ROUTES.contains(route)
}

/**
 * 底部导航的 5 个 Tab 定义，枚举顺序即展示顺序。
 *
 * @property route 对应的顶层导航路由。
 * @property labelRes 底栏文案资源。
 * @property icon 底栏图标。
 */
enum class BottomTab(
  val route: String,
  @StringRes val labelRes: Int,
  val icon: ImageVector,
) {
  CHAT(AppRoutes.TAB_CHAT, R.string.bottom_nav_tab_chat, Icons.AutoMirrored.Rounded.Chat),
  AGENT(AppRoutes.TAB_AGENT, R.string.bottom_nav_tab_agent, Icons.Rounded.SmartToy),
  LAB(AppRoutes.TAB_LAB, R.string.bottom_nav_tab_lab, Icons.Rounded.Science),
  MODELS(AppRoutes.TAB_MODELS, R.string.bottom_nav_tab_models, Icons.AutoMirrored.Rounded.ListAlt),
  SYSTEM(AppRoutes.TAB_SYSTEM, R.string.bottom_nav_tab_system, Icons.Rounded.Settings),
  ;

  companion object {
    /** 根据路由反查对应 Tab；找不到时返回 null。 */
    fun fromRoute(route: String?): BottomTab? = entries.firstOrNull { it.route == route }
  }
}

/**
 * 底部导航栏（微信式）。
 *
 * @param currentRoute 当前所在路由，用于高亮选中项。
 * @param onTabSelected 用户点击某个 Tab 时回调（未选中项才会回调）。
 */
@Composable
fun BottomNavBar(
  currentRoute: String?,
  onTabSelected: (BottomTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  NavigationBar(modifier = modifier) {
    for (tab in BottomTab.entries) {
      val selected = tab.route == currentRoute
      NavigationBarItem(
        selected = selected,
        onClick = {
          // 已选中的 Tab 再次点击不做任何事，避免无意义的入栈/重建。
          if (!selected) {
            onTabSelected(tab)
          }
        },
        icon = { Icon(imageVector = tab.icon, contentDescription = null) },
        label = { Text(text = stringResource(tab.labelRes), maxLines = 1) },
      )
    }
  }
}

/**
 * 顶层 Tab 的统一外壳：内容区占满剩余空间，底部固定一枚底栏。
 *
 * 采用 `Column + weight(1f)` 的方式把底栏高度从内容区扣除，这样对话页的输入框、二级内容
 * 都不会被底栏遮挡，也无需各页面自行计算 bottom padding。
 *
 * @param currentRoute 当前路由（用于高亮底栏选中项）。
 * @param onTabSelected Tab 点击回调。
 * @param content 单个 Tab 的内容。
 */
@Composable
fun TopLevelTabScaffold(
  currentRoute: String?,
  onTabSelected: (BottomTab) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      // 告知内容区「当前处于顶层 Tab」：页面内部的 BackHandler 会据此让出系统返回键。
      CompositionLocalProvider(LocalIsTopLevelTab provides true) { content() }
    }
    BottomNavBar(currentRoute = currentRoute, onTabSelected = onTabSelected)
  }
}
