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

package com.encourage.app.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 标记当前组合是否处于「底部五 Tab 的顶层页面」。
 *
 * 为什么需要它：顶层 Tab 是导航栈的根，系统返回键应当交给系统/NavHost 处理
 * （Tab1 退出 App；其它 Tab 回到起始 Tab），而不应被页面内部的 `BackHandler` 吞掉。
 * 二级页（`route_model/...`、`benchmark/...` 等）里同一批组件仍保持原有返回语义。
 *
 * 由 `com.encourage.app.ui.navigation.TopLevelTabScaffold` 在顶层 Tab 内提供为 `true`，
 * 默认 `false`（即二级页/其它场景）。
 */
val LocalIsTopLevelTab = staticCompositionLocalOf { false }
