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

package com.encourage.app.customtasks.agentchat

import androidx.datastore.core.DataStore
import com.encourage.app.agent.AgentChatExecutor
import com.encourage.app.agent.AgentRuntimeExecutor
import com.encourage.app.data.SystemPromptRepository
import com.encourage.app.data.api.ApiProviderRepository
import com.encourage.app.data.api.RemoteOpenAICompatProvider
import com.encourage.app.data.conversation.ConversationProfileRepository
import com.encourage.app.proto.UserData
import com.encourage.app.ui.llmchat.LlmChatViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Agent 任务（带工具 / Skill / MCP 的对话）用的 ViewModel。
 *
 * 【M2】与 [LlmChatViewModel] 一样把云端依赖透传给基类，
 * 这样 Agent 对话在设置里打开云端 API 后同样走远端模型。
 * 注意 Agent 场景依赖本地工具执行能力，云端模型不一定能正确产出工具调用，
 * 这一版不做区分，交由用户自己判断。
 *
 * 【T04】新增透传 [ConversationProfileRepository]，使 Agent 会话也支持记录级提示词绑定与
 * saveSession 的 profileId 归属。
 */
@HiltViewModel
class AgentChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  userDataDataStore: DataStore<UserData>,
  @AgentChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  apiProviderRepository: ApiProviderRepository,
  remoteProvider: RemoteOpenAICompatProvider,
  conversationProfileRepository: ConversationProfileRepository,
) :
LlmChatViewModel(
  systemPromptRepository,
  userDataDataStore,
  runtimeExecutor,
  apiProviderRepository,
  remoteProvider,
  conversationProfileRepository,
)
