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

package com.encourage.app.agent

/** Utility class that assembles the full working context before passing it to the engine. */
class PromptExpander {
  /**
   * Formats the system instruction template with key-value substitutions and returns the final
   * system instructions.
   */
  fun formatSystemInstructions(
    template: String,
    substitutions: Map<String, String> = emptyMap(),
  ): String {
    var result = template
    for ((key, value) in substitutions) {
      result = result.replace(key, value)
    }

    return result
  }

  /**
   * 【B1 修复】展开系统提示词，把「技能清单 / MCP 工具清单」注入进去。
   *
   * 原实现只有 [formatSystemInstructions] 的哑替换，**模板里没有占位符时静默什么都不做**。
   * 而「选 Agent → 用它的提示词」「用户手改系统提示词」「打开写过提示词的特调记录」
   * 这三条路径传进来的都是用户原文，里面根本没有 `___SKILLS___`，
   * 于是技能清单被整段丢掉 —— 模型看不到任何技能名，只能瞎猜 `load_skill` 参数，
   * 表现就是「卡很久然后 Skill not found」。
   *
   * 这里改成两条路都覆盖：
   * 1. 模板含占位符 → 原地替换（原行为，默认提示词走这条）；
   * 2. 模板不含占位符 → 在末尾追加带固定标记的区块（用户自定义提示词走这条）。
   *
   * 追加前先检查标记是否已存在，避免「提示词里已经抄了一份技能清单」时重复注入，
   * 白吃本来就紧张的上下文预算。
   */
  fun expand(
    template: String,
    skillsBlock: String? = null,
    toolsBlock: String? = null,
  ): String {
    val skills = skillsBlock?.trim().orEmpty()
    val tools = toolsBlock?.trim().orEmpty()

    val hadSkillsPlaceholder = template.contains(SKILLS_PLACEHOLDER)
    val hadToolsPlaceholder = template.contains(TOOLS_PLACEHOLDER)

    var result = template
    if (hadSkillsPlaceholder) {
      result = result.replace(SKILLS_PLACEHOLDER, skills)
    }
    if (hadToolsPlaceholder) {
      result = result.replace(TOOLS_PLACEHOLDER, tools)
    }

    // 没有占位符 = 用户自己的提示词，把清单追加到末尾，否则模型完全不知道有这些能力。
    if (!hadSkillsPlaceholder && skills.isNotEmpty() && !result.contains(SKILLS_ANCHOR)) {
      result = result.trimEnd() + "\n\n" + SKILLS_ANCHOR + "\n" + skills
    }
    if (!hadToolsPlaceholder && tools.isNotEmpty() && !result.contains(TOOLS_ANCHOR)) {
      result = result.trimEnd() + "\n\n" + TOOLS_ANCHOR + "\n" + tools
    }

    return result
  }

  companion object {
    const val SKILLS_PLACEHOLDER = "___SKILLS___"
    const val TOOLS_PLACEHOLDER = "___TOOLS___"

    /** 区块标题。默认提示词里已有同名标题，可用来判定「是否已经注入过」。 */
    const val SKILLS_ANCHOR = "--- SKILLS ---"
    const val TOOLS_ANCHOR = "--- MCP TOOLS ---"
  }
}
