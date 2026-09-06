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

import android.content.Context
import com.encourage.app.data.DataStoreRepository
import com.encourage.app.skills.SkillsProvider
import com.encourage.app.tools.CallJsSkillResultImage
import com.encourage.app.tools.CallJsSkillResultWebview
import com.encourage.app.tools.LoadSkillTool
import com.encourage.app.tools.RunIntentTool
import com.encourage.app.tools.RunJsTool
import com.encourage.app.tools.RunMcpTool
import com.encourage.app.tools.ToolAction
import com.encourage.app.tools.ToolDefinition
import com.encourage.app.tools.ToolsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking

interface AgentTools : ToolsProvider {
  var context: Context
  var skillsProvider: SkillsProvider
  var dataStoreRepository: DataStoreRepository
  var mcpManagerViewModel: McpManagerViewModel
  var taskId: String
  val receiveActionChannel: ReceiveChannel<ToolAction>
  val sendActionChannel: SendChannel<ToolAction>
  var resultImageToShow: CallJsSkillResultImage?
  var resultWebviewToShow: CallJsSkillResultWebview?

  fun sendToolAction(action: ToolAction)
}

open class AgentToolsImpl : AgentTools {
  override lateinit var context: Context
  override lateinit var skillsProvider: SkillsProvider
  override lateinit var dataStoreRepository: DataStoreRepository
  override lateinit var mcpManagerViewModel: McpManagerViewModel
  override lateinit var taskId: String

  private val _actionChannel = Channel<ToolAction>(Channel.UNLIMITED)
  override val receiveActionChannel: ReceiveChannel<ToolAction> = _actionChannel
  override val sendActionChannel: SendChannel<ToolAction> = _actionChannel

  private val activeTools = mutableListOf<ToolDefinition>()

  val loadSkillTool by lazy { LoadSkillTool(skillsProvider = skillsProvider) }

  val runMcpTool by lazy {
    RunMcpTool(
      mcpServersProvider = mcpManagerViewModel,
      skillsProvider = skillsProvider,
      taskId = taskId,
    )
  }

  val runJsTool by lazy {
    RunJsTool(skillsProvider = skillsProvider, dataStoreRepository = dataStoreRepository)
  }

  val runIntentTool by lazy { RunIntentTool(context = context, skillsProvider = skillsProvider) }

  override fun getAvailableTools(): List<ToolDefinition> {
    return listOf(loadSkillTool, runMcpTool, runJsTool, runIntentTool) + activeTools
  }

  override fun registerTool(tool: ToolDefinition) {
    if (!activeTools.contains(tool)) {
      activeTools.add(tool)
    }
  }

  override fun unregisterTool(tool: ToolDefinition) {
    activeTools.remove(tool)
  }

  override var resultImageToShow: CallJsSkillResultImage?
    get() = runJsTool.resultImageToShow
    set(value) {
      runJsTool.resultImageToShow = value
    }

  override var resultWebviewToShow: CallJsSkillResultWebview?
    get() = runJsTool.resultWebviewToShow
    set(value) {
      runJsTool.resultWebviewToShow = value
    }

  override fun sendToolAction(action: ToolAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }
}
