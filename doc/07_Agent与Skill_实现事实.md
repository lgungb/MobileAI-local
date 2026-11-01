# 07 · Agent 与 Skill：实现事实

> 本文全部来自源码，附 `文件:行号`。**只讲现在是什么样，不讲应该是什么样。**
> 原 `doc/演进设计/Agent与Skill_实现原理与重新设计.md` 的"实现原理"部分已并入本文，
> "重新设计"部分属未落地设想，已删除（如需重启相关规划，见 `06_重构方案.md` 的排除清单）。

---

## 1. 先破除一个混淆：项目里有两个「Agent」

| 代号 | 是什么 | 位置 |
| --- | --- | --- |
| **(A) Agent Chat 任务** | 一个**带工具能力**的对话任务，系统提示词写死，靠 LiteRT-LM 原生 function calling 调工具 | 任务 id `llm_agent_chat`，`customtasks/agentchat/AgentChatTaskModule.kt:57-97` |
| **(B) AgentDefinition** | **只是一段系统提示词 + 元数据**，没有任何能力声明 | `data/agents/AgentDefinition.kt:17-32`，内置 4 个在 `assets/agents/` |

**关系**：(B) 是 (A) 的「人设皮肤」。选一个 Agent 只是把它的 `systemPrompt` 覆盖到会话上
（`AgentChatScreen.kt:725-734`），**工具集完全不变**。

`assets/agents/coach.json` 的全部字段只有：
`id / name / description / author / version / systemPrompt`。
**没有 `skills:[…]`、没有 `tools:[…]`、没有 `modelId`。** 这是所有能力限制的根源。

---

## 2. Skill 是什么：数据 + 目录，不是工具

Skill **不注册进 `ToolsProvider`**，它是「被模型按需加载的提示词/脚本包」。

```
assets/skills/<name>/          ← 内置（只读）
filesDir/skills/<name>/        ← 用户自建 / SAF 导入
  SKILL.md                     ← 必需
  scripts/index.html           ← 可选：WebView 界面
  scripts/index.js             ← 可选：真正干活的代码
  assets/*                     ← 可选
```

`SKILL.md` 是 front-matter + 正文（解析见 `SkillManager.kt:961-1039`）：

```markdown
---
name: text-spinner
description: 在我头上旋转给定的文字。
---

# 使用说明
你必须使用 `run_js` 工具，传入以下精确参数：
- data: JSON 字符串，包含字段 label…
```

持久化字段（`proto/skill.proto:28-58`）：
`name / description / instructions / built_in / skill_url / import_dir_name / selected /
require_secret / require_secret_description / homepage / user_modified_selection`。

> `selected` 很关键：**只有"选中"的 skill 才会进入模型可见的技能菜单**。

---

## 3. ⭐ Skill 的执行是三个环节拼起来的（最容易误解）

| 环节 | 机制 | 代码 |
| --- | --- | --- |
| ① **菜单** | 选中 skill 的 `name`+`description` 填入 system prompt 的 `___SKILLS___` 占位符 | `SkillExtensions.kt:29-35` → `AgentChatTaskModule.kt:177` |
| ② **按需加载全文** | 模型调 `load_skill` → 返回该 skill 的 `instructions` 全文，作为 tool result 进对话 | `LoadSkillTool.kt:34-67` |
| ③ **执行动作** | 按 instructions 指示，调 `run_js`（WebView 跑 JS）或 `run_intent`（系统动作） | `RunJsTool.kt`、`RunIntentTool.kt` |

system prompt 里写死的流程（`AgentChatTaskModule.kt:76-84`，FLOW A）：

```
① 找最相关的 skill  →  ② 用 load_skill 读它的指令  →  ③ 按指令执行
```

**① 是"渐进式披露"**：菜单里只有名字和一句话描述（每个约 30 token），全文要等模型主动
`load_skill` 才进上下文。在 16K~32K 窗口的端侧模型上，这是必要条件，不是可选优化。

---

## 4. 工具集：全局写死 4 个

`AgentToolsImpl.getAvailableTools()`（`AgentTools.kt:80-82`）固定返回：

| 工具 | Kotlin 函数名 | 作用 | alwaysAllow |
| --- | --- | --- | --- |
| `LoadSkillTool` | `loadSkill` | 把 skill 的 instructions 拉进上下文 | 是 |
| `RunMcpTool` | `runMcpTool` | 调已连接的 MCP server 工具 | 是（另有权限确认） |
| `RunJsTool` | `runJs` | 在聊天 WebView 里跑 skill 的 JS | 是 |
| `RunIntentTool` | `runIntent` | 拉起系统 Intent | 是（部分需权限） |

工具**在模型初始化时注册进 LiteRT-LM 引擎**（`DefaultAgentRuntimeExecutor.kt:85`），
schema 由 `@Tool` / `@ToolParam` 注解生成。

> **命名规则（2026-11-02 核实）**：模型看到的工具名 = Kotlin 方法名做 `camelToSnakeCase`。
> 依据：反编译 `com.google.ai.edge.litertlm:0.16.0` 的 `ReflectionTool`，方法名经过
> `ToolKt.access$camelToSnakeCase(kFunction.getName())` 再写入 schema。
> 所以 `loadSkill` → `load_skill`、`runMcpTool` → `run_mcp_tool`、`runJs` → `run_js`、`runIntent` → `run_intent`。
> **提示词里必须写蛇形名**，写成 camelCase 模型就调不到（见 §7 B2）。

---

## 5. 工具调用循环：引擎驱动，Kotlin 侧没有轮次上限

```
用户发消息
 → LlmChatViewModelBase.generateResponse()       (LlmChatViewModel.kt:427)
 → DefaultAgentRuntimeExecutor.executeStream()   (DefaultAgentRuntimeExecutor.kt:94-182)
 → runtimeHelper.runInference(...)  ← 单次调用   (:145-177)
      └─ LiteRT-LM 引擎内部 ReAct 循环：
           模型发起工具调用 → 执行 Kotlin 工具 → 结果回灌 → 继续生成
           直到产出最终文本
```

- **是原生 function calling，不是文本解析**（`ToolDefinition.kt:39` 直接 `extends ToolSet`）。
- **Kotlin 侧没有 while 轮次上限**。唯一保护：JS 工具 **60 秒超时**（`AgentChatScreen.kt:359`）、
  等 `ai_edge_gallery_get_result` 最多 **10 秒**（`:428`）。
- 轮次上界实际由引擎与 `maxOutputTokens` 决定 ⟹ **小模型陷入工具循环会一直转到 token 耗尽**。
- 合规靠两个手段：`enableConversationConstrainedDecoding=true`（约束解码）
  + **Agent 场景强制 `TopK=1`**（贪婪解码，`AgentChatSamplingParamsManager.kt:116-118`）。

---

## 6. 四个工具的能力边界（决定性事实）

### 6.1 `run_js` = Android WebView（Chromium），不是精简 JS 引擎

- `javaScriptEnabled=true`、`allowFileAccess=true`（`GalleryWebView.kt:154,156`），
  `WebViewAssetLoader` 暴露 `assets/` 与 `filesDir/`（`:67-69`）。
- **与 App 的唯一桥**：JS 侧必须定义

  ```js
  window['ai_edge_gallery_get_result'] = async (data, secret) => { …; return JSON字符串 }
  ```

  完成后调 `@JavascriptInterface AiEdgeGallery.onResultReady(result)` 回传
  （`AgentChatScreen.kt:422-433,476`）。

- **能做到**：完整浏览器能力——网络请求、本地计算（`crypto.subtle`、Canvas）、渲染任意 HTML/CSS/JS。
- **做不到**：访问 Android 传感器 / 原生 API（只能靠"回传字符串"这一条路）。

### 6.2 `run_intent` = 硬编码白名单，只有 6 个动作

`IntentHandler.kt:72-83`：

| action | 说明 | 权限 |
| --- | --- | --- |
| `send_email` | 拉起系统邮件选择器 | 无 |
| `send_sms` | 拉起系统短信 | 无 |
| `create_calendar_event` | 建日历事件 | 无 |
| `read_calendar_events` | 读日历 | **需 `READ_CALENDAR`** |
| `get_current_date_and_time` | 取当前时间 | 无 |
| `schedule_notification` | 本地闹钟 + 通知（App 自有 AlarmManager） | 无 |

**超出白名单即失败**（回退提示"试试用 skill 方式"）。

### 6.3 架构里**没有**的能力（不要假设可用）

读剪贴板、读写通讯录、定位/传感器、直接访问 App 数据库、任意系统 API ——
**代码里没有对应工具**。要用必须新增工具（改 Kotlin 代码）。

---

## 7. 已知缺陷（2026-11-02 逐条核对，均未修）

> 判定标准：能指到具体代码行为、且能说清"用户会看到什么"。只写推测的不进本节。
> 面向用户的讲解版见 `HUMAN_技能与工具_人话手册.md` §4。

### B1【P0】技能菜单在三条路径上被整段抹掉

**机制**：`___SKILLS___` / `___TOOLS___` 的替换只发生在两处——
`AgentChatTaskModule.kt:172-181`（首次初始化）与 `AgentChatScreen.kt:817-826`（页面"重置会话"）。
但 `LlmChatViewModel.resetSession()` 本身**不做替换**，它只是把字符串交给执行器。
`PromptExpander.formatSystemInstructions()` 是纯 `replace`，模板里没有占位符就什么都不做、也不报错
（`agent/PromptExpander.kt:29-35`）。

**三条绕过路径**：

| 触发 | 路径 | 结果 |
| --- | --- | --- |
| 在 Agent 页选 Agent | `AgentChatScreen.kt:727-732` → `applySystemPromptChange` | 系统提示词 = `agent.systemPrompt`（纯人设，无占位符） |
| 手动编辑系统提示词 | `ModelPageAppBar.kt:280` → `applySystemPromptChange`（`LlmChatViewModel.kt:496-503`） | 同上 |
| 打开写过提示词的「特调记录」 | `LlmChatViewModel.bindProfile:275-308` → `applySystemPromptToEngine`；未初始化时经 `ModelManagerViewModel.kt:706` 的 `pendingSystemInstruction` 进 `initializeModelFn` | 同上 |

**用户可见现象**：技能数量徽标照常显示，但模型完全不知道有技能；要么直接编答案，
要么猜一个技能名调 `load_skill` 拿到 `"Skill not found"`，反复重试 → 表现为"卡很久且什么都没干"。

**次级问题**：编辑器「恢复默认」（`ConfigDialog.kt:252-257`）只把模板文本填回输入框，
保存后仍走"原文"路径 → 模型看到字面量 `___SKILLS___`。要恢复必须再点一次"重置会话"。

### B2【P0】提示词里的 MCP 工具名写错

`AgentChatTaskModule.kt:80` 与 `:93` 写的是 `runMcpTool`，实际注册名是 `run_mcp_tool`（见 §4 命名规则）。
模型照提示词调用 → 引擎无此函数 → MCP 路径直接不可用。

### B3【P1】没有 MCP 工具时，提示词禁止模型正常回答

`getEffectiveBaseSystemPrompt(..., hasMcpTools=false)` 返回 `DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY`
（`AgentChatTaskModule.kt:277-283`），其第 4 步为：

> If no relevant skill is found, output "No relevant skills found" and stop.

任何与技能无关的普通提问都会命中这条 → 模型只回一句 `No relevant skills found`。
这不是模型能力问题，是提示词显式禁止作答。

### B4【P2】"是否默认提示词"用字符串全等判定

`isDefaultSystemPrompt()`（`AgentChatTaskModule.kt:271-274`）只与两个常量做 `==`。
用户手动改过一个字、或以后改了默认提示词文案，判定即失效，MCP 开关不再影响提示词选择。

### B5【P2】`agent_id` / `skill_ids` 字段预留但从未写入

`conversation_profile.proto` 里有字段，全仓无写入点。记录无法真正绑定 Agent 与技能集，
每次进会话只能靠全局的"当前勾选"。

### B6【P1】工具循环没有轮次上限

`DefaultAgentRuntimeExecutor.executeStream()` 只调用一次 `runInference`（`:145-177`），
ReAct 循环在引擎内部。Kotlin 侧唯二保护是 JS 60 秒超时（`AgentChatScreen.kt:359`）
与等待 JS 函数就绪 10 秒（`:428`）。小模型反复调用工具时会一直转到 token 耗尽。

### B7【P2，隐患未复现】工具依赖 `lateinit` 赋值时序

`AgentToolsImpl` 的 `context / taskId / dataStoreRepository / mcpManagerViewModel` 都是 `lateinit`，
由 Composable 体在 `AgentChatScreen.kt:139-143` 赋值；而 `runMcpTool` 是 `by lazy` 且捕获 `taskId`。
若在赋值前触发 `getAvailableTools()`（例如模型初始化早于首帧组合），会抛
`UninitializedPropertyAccessException`。当前未复现，属结构性隐患。

---

## 8. 上下文预算实测（2B~4B / 16K）

`Consts.kt:42` `DEFAULT_MAX_TOKEN = 16384`；`LlmChatModelHelper.kt:89-90` 把它作为 `EngineConfig.maxNumTokens`。

| 项目 | 字符数 | 估算 token | 来源 |
| --- | --- | --- | --- |
| 完整系统提示词（英文） | 2096 | ~500 | `AgentChatTaskModule.kt:58-98` |
| 仅技能版提示词 | 1105 | ~280 | `:103-120` |
| 技能菜单（12 条，中文） | 794 | ~600–900 | `SkillExtensions.kt:29-35` |
| 4 个工具 schema | — | ~300 | LiteRT-LM 生成 |
| `learn-something-new/SKILL.md` | 7651 字节 | ~2500–3500 | `assets/skills/` |
| `mood-tracker/SKILL.md` | 4993 字节 | ~1600–2200 | 同上 |

**结论**：默认满配（完整提示词 + 12 条菜单 + 4 工具）≈ 1400–1700 token，
一次 `load_skill` 再吃 1600–3500。16K 下大约 **3~6 轮**就会触发引擎截断，
而截断最先伤到的通常是系统提示词与工具定义——表现为"上一轮能调、下一轮不调"。

---

## 9. 最小修法（未实施，供排期）

按代价从低到高，前三项可以一次做完：

1. **收口提示词拼装**（修 B1）：把 `formatSelectedSkills` + `getToolsPrompt` 的替换下沉到
   `LlmChatViewModel.resetSession()` 之前，做成唯一出口；任何入口传进来的模板都先展开再下发给执行器。
   同时让 `getEffectiveBaseSystemPrompt` 在"用户给了自定义提示词"时**追加**技能与工具段落，
   而不是整体替换。
2. **改提示词文案**（修 B2/B3）：`runMcpTool` → `run_mcp_tool`；
   把"找不到就停止"改成"找不到技能就用普通对话回答"。
3. **给循环加上限**（修 B6）：在 `executeStream` 侧统计工具事件数，超过 N 次（建议 6）强制收尾。
4. **顺手清理**（修 B4/B5/B7）：改成"模板是否包含占位符"判定；落实 `agent_id/skill_ids` 写入；
   把 `AgentToolsImpl` 的 `lateinit` 改成构造注入。

> 验收方式：勾 2 个技能 → 选一个 Agent → 说"用二维码技能把 example.com 做成二维码"。
> 预期日志出现 `AGLoadSkillTool: load skill. Skill content:` 且正文非 `Skill not found`。
