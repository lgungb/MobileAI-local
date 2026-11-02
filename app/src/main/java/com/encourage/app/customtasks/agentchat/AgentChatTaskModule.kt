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

package com.encourage.app.customtasks.agentchat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.encourage.app.R
import com.encourage.app.agent.AgentChatExecutor
import com.encourage.app.agent.AgentRuntimeConfig
import com.encourage.app.agent.AgentRuntimeExecutor
import com.encourage.app.agent.DefaultAgentRuntimeExecutor
import com.encourage.app.agent.PromptExpander
import com.encourage.app.customtasks.common.CustomTask
import com.encourage.app.customtasks.common.CustomTaskDataForBuiltinTask
import com.encourage.app.data.BuiltInTaskId
import com.encourage.app.data.Category
import com.encourage.app.data.Model
import com.encourage.app.data.Task
import com.encourage.app.data.backupReplaceCorruptionHandler
import com.encourage.app.proto.McpServers
import com.encourage.app.skills.SkillManager
import com.encourage.app.skills.SkillsProvider
import com.encourage.app.skills.formatSelectedSkills
import com.encourage.app.tools.RuntimeToolDispatcher
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGAgentChatTask"

// The default system prompt for the agent chat task with both skills and MCP tools.
//
// 【B2 修复】工具名一律写「蛇形」（snake_case）。
// LiteRT-LM 0.16.0 的 ReflectionTool 会把 Kotlin 方法名做 camelToSnakeCase 转换
// （反编译确认），所以注册给模型的实际名字是 `load_skill` / `run_mcp_tool` / `run_js` /
// `run_intent`。提示词里写 `runMcpTool` 模型就会照着调一个不存在的工具 → MCP 完全不可用。
//
// 【B3 修复】找不到技能/工具时**改用普通对话回答**，不再要求模型停下或输出固定话术。
// 原提示词写着"If nothing is found, output 'No skills or tools found' and stop"，
// 结果一句「你好」也被拒答 —— 这是提示词让模型别答，不是模型能力问题。
const val DEFAULT_SYSTEM_PROMPT =
  """
  You are an AI assistant that helps users by answering questions and completing tasks. Some tasks are best done with a Skill or an MCP Tool; ordinary questions need neither.

  CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

  1. EVALUATE AND ROUTE:
     Determine if the request should be handled by a "Skill" (requires loading instructions) or directly by an "MCP Tool".
     - If it is a Skill: Go to Step 2.
     - If it is an MCP Tool: Go to Step 4.
     - If nothing matches: answer the user directly in plain conversation. NEVER refuse or stop just because no skill or tool matched.

  --- SKILLS ---
  ___SKILLS___

  --- MCP TOOLS ---
  ___TOOLS___

  ==================================================
  FLOW A: SKILL EXECUTION
  ==================================================

  2. Find the most relevant skill from the --- SKILLS --- list. You MUST NOT use `run_intent` or `run_mcp_tool` under any circumstances at this step.

  3. Use the `load_skill` tool to read its instructions. Follow the skill's instructions exactly to complete the task.
     - You MUST NOT output any intermediate thoughts or status updates. No exceptions!
     - Output ONLY the final result when successful. It should contain a one-sentence summary of the action taken and the final result of the skill.
     - Stop here once Flow A is complete.

  ==================================================
  FLOW B: MCP TOOL DIRECT EXECUTION
  ==================================================

  4. Find the most relevant tool from the --- MCP TOOLS --- list.

  5. Call the `run_mcp_tool` tool with the following parameters:
     - `toolName`: The name of the tool to run. Use the exact name from the list above. Do not hallucinate the name. Pay attention to casing and plurals.
     - `input`: The input JSON object that matches the tool's expected input schema.

  6. Output ONLY the final result returned by the tool. You MUST NOT output any intermediate thoughts or status updates. No exceptions!
  """

val DEFAULT_SYSTEM_PROMPT_TRIMMED = DEFAULT_SYSTEM_PROMPT.trimIndent()

// The default system prompt for the agent chat task with only skills.
//
// 【B3 修复】第 4 步原本是「找不到相关技能就输出 No relevant skills found 然后停」，
// 导致没接 MCP 时连「你好」都被拒答。现在改成「照常回答」。
const val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY =
  """
  You are an AI assistant that helps users by answering questions and completing tasks. Some tasks are best done with a Skill; ordinary questions need none.

  CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

  1. First, find the most relevant skill from the following list:

  ___SKILLS___

  After this step you MUST go to next step. You MUST NOT use `run_intent` under any circumstances at this step.

  2. If a relevant skill exists, use the `load_skill` tool to read its instructions. You MUST NOT use `run_intent` under any circumstances at this step.

  3. Follow the skill's instructions exactly to complete the task. You MUST NOT output any intermediate thoughts or status updates. No exceptions! Output ONLY the final result when successful. It should contain one-sentence summary of the action taken, and the final result of the skill.

  4. If no relevant skill is found, just answer the user's request directly in plain conversation. Never refuse, never stop early, and never reply with "No relevant skills found".
  """

val DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED = DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY.trimIndent()

class AgentChatTask
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val skillsProvider: SkillsProvider,
  private val agentTools: AgentTools,
  @AgentChatExecutor private val executor: AgentRuntimeExecutor,
) : CustomTask {
  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = context.getString(R.string.task_label_agent_skills),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_agent_skills),
      shortDescription = context.getString(R.string.task_short_desc_agent_skills),
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt = DEFAULT_SYSTEM_PROMPT_TRIMMED,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    val initialSystemPrompt = systemInstruction?.toString() ?: task.defaultSystemPrompt
    coroutineScope.launch(Dispatchers.Default) {
      val skillsJob = launch {
        agentTools.skillsProvider.loadSkills(SkillManagerViewModel.DEFAULT_DISABLED_SKILLS)
      }
      // 【B1】`mcpManagerViewModel` 是 lateinit：首次从「模型」Tab 直接进 Agent 能力时，
      // 界面组合可能还没跑完。这里兜住，读不到就当没有 MCP 工具，不能因此让模型初始化失败。
      val mcpJob = launch {
        try {
          agentTools.mcpManagerViewModel.loadMcpServers()
        } catch (e: Exception) {
          Log.w(TAG, "loadMcpServers skipped: MCP manager not ready yet.", e)
        }
      }
      skillsJob.join()
      mcpJob.join()

      // 【B1】统一出口：技能菜单与 MCP 工具清单都在这里注入，不再各写一份。
      val finalSystemPrompt =
        buildAgentSystemPrompt(
          rawPrompt = initialSystemPrompt,
          skillsProvider = agentTools.skillsProvider,
          toolsPrompt = agentTools.toolsPromptOrEmpty(),
        )

      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = agentTools.sendActionChannel,
          supportImage = model.llmSupportImage,
          supportAudio = model.llmSupportAudio,
          enableConversationConstrainedDecoding = true,
          systemInstruction = finalSystemPrompt,
        )

      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      initialQuery = myData.initialQuery,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @Singleton
  fun provideAgentTools(skillManager: SkillManager): AgentTools {
    return AgentToolsImpl().apply { skillsProvider = skillManager }
  }

  @Provides
  @Singleton
  @AgentChatExecutor
  fun provideAgentChatExecutor(
    skillManager: SkillManager,
    agentTools: AgentTools,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = skillManager,
      toolsProvider = agentTools,
      toolDispatcher = RuntimeToolDispatcher(),
    )
  }

  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    skillManager: SkillManager,
    agentTools: AgentTools,
    @AgentChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return AgentChatTask(context, skillManager, agentTools, executor)
  }

  @Provides
  @Singleton
  fun provideMcpServersDataStore(@ApplicationContext context: Context): DataStore<McpServers> {
    return DataStoreFactory.create(
      serializer = McpServersSerializer,
      // 【P0 兜底】MCP 服务器清单损坏时先备份再回落空清单。
      // 没有这个 handler 时，文件一坏就是「开屏无限崩溃、只能清数据」。
      corruptionHandler =
        backupReplaceCorruptionHandler(context, "mcp_servers.pb") {
          McpServers.getDefaultInstance()
        },
      produceFile = { context.dataStoreFile("mcp_servers.pb") },
    )
  }
}

// Check whether the system prompt is the default one.
//
// 【B3 迁移】历史版本把「找不到就停」的旧提示词存进了用户数据，升级后如果不识别它，
// 会被当成「用户自定义提示词」原样沿用 —— 用户升级完照样被拒答。
// 这里用旧版独有的固定话术做特征识别，命中即视为默认提示词，自动切换到新版本。
private val LEGACY_DEFAULT_PROMPT_MARKERS =
  listOf(
    "\"No skills or tools found\" and stop",
    "\"No relevant skills found\" and stop",
  )

fun isDefaultSystemPrompt(prompt: String): Boolean {
  if (prompt == DEFAULT_SYSTEM_PROMPT_TRIMMED ||
    prompt == DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
  ) {
    return true
  }
  return LEGACY_DEFAULT_PROMPT_MARKERS.any { prompt.contains(it) }
}

// Returns the effective default system prompt depending on whether MCP tools are enabled.
fun getEffectiveBaseSystemPrompt(currentPrompt: String, hasMcpTools: Boolean): String {
  return if (isDefaultSystemPrompt(currentPrompt)) {
    if (hasMcpTools) DEFAULT_SYSTEM_PROMPT_TRIMMED else DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY_TRIMMED
  } else {
    currentPrompt
  }
}

/**
 * 【B1 修复】构造 Agent 会话的最终系统提示词 —— **唯一出口**。
 *
 * 这条链路上曾经有三处各写一遍注入逻辑（模型初始化 / 页面重置 / 选 Agent），
 * 结果只有前两处写了，第三条路径把技能菜单整段丢掉。
 * 现在所有调用方都必须走这里，新加入口也绕不过去。
 *
 * @param rawPrompt 用户看到的原始提示词（可能含 `___SKILLS___` / `___TOOLS___` 占位符，
 *   也可能是用户自己写的、根本没有占位符的文本）。
 * @param skillsProvider 技能来源，取当前「已勾选」的技能。
 * @param toolsPrompt 已格式化的 MCP 工具清单；空串表示没有可用 MCP 工具。
 */
suspend fun buildAgentSystemPrompt(
  rawPrompt: String,
  skillsProvider: SkillsProvider,
  toolsPrompt: String,
): String {
  val base = getEffectiveBaseSystemPrompt(rawPrompt, toolsPrompt.isNotEmpty())
  val skills = withContext(Dispatchers.Default) { skillsProvider.getAvailableSkills() }
  return PromptExpander()
    .expand(
      template = base,
      skillsBlock = formatSelectedSkills(skills),
      toolsBlock = toolsPrompt,
    )
}
