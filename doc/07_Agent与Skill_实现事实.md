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

## 7. 已知缺陷（2026-11-02 核对；B1/B2/B3 已于 `v65` 修复）

> 判定标准：能指到具体代码行为、且能说清"用户会看到什么"。只写推测的不进本节。
> 面向用户的讲解版见 `HUMAN_技能与工具_人话手册.md` §4。
>
> **状态图例**：✅ 已修（附修法与验证点）｜⬜ 未修｜🔍 隐患未复现

### ✅ B1【P0，已修】技能菜单在三条路径上被整段抹掉

**机制**：`___SKILLS___` / `___TOOLS___` 的替换只发生在两处——
`AgentChatTaskModule.kt`（首次初始化）与 `AgentChatScreen.kt`（页面"重置会话"）。
但 `LlmChatViewModel.resetSession()` 本身**不做替换**，它只是把字符串交给执行器。
`PromptExpander.formatSystemInstructions()` 是纯 `replace`，模板里没有占位符就什么都不做、也不报错。

**三条绕过路径**：

| 触发 | 路径 | 结果 |
| --- | --- | --- |
| 在 Agent 页选 Agent | `AgentChatScreen.kt` → `applySystemPromptChange` | 系统提示词 = `agent.systemPrompt`（纯人设，无占位符） |
| 手动编辑系统提示词 | `ModelPageAppBar.kt` → `applySystemPromptChange` | 同上 |
| 打开写过提示词的「特调记录」 | `LlmChatViewModel.bindProfile` → `applySystemPromptToEngine`；未初始化时经 `pendingSystemInstruction` 进 `initializeModelFn` | 同上 |

**用户可见现象**：技能数量徽标照常显示，但模型完全不知道有技能；要么直接编答案，
要么猜一个技能名调 `load_skill` 拿到 `"Skill not found"`，反复重试 → 表现为"卡很久且什么都没干"。

**修法（v65）—— 两层收口，任何入口都绕不过去**：

1. **`PromptExpander.expand()` 兼容无占位符**：模板含占位符 → 原地替换；
   不含 → 在末尾追加 `--- SKILLS ---` / `--- MCP TOOLS ---` 区块。
   追加前检查锚点是否已存在，避免重复注入（16K 下 token 很贵）。
2. **`LlmChatViewModel.resetSession()` 成为唯一出口**：所有传入的系统提示词先过
   `decorateSystemInstruction()`，由 Agent 页注册的 `AgentSessionBinder` 补齐技能与工具清单。
   同时把**工具动作通道**与**约束解码**也由 binder 补上 —— 原先只有"重置会话"一条路径传了这两样，
   其余路径全丢，这也是"点了没反应、一直转圈"的来源。
3. **拼装逻辑单一实现**：`buildAgentSystemPrompt()` 由模型初始化、页面重置、ViewModel 收口三处共用。
4. **比对口径对齐**：`applySystemPromptToEngine` 改用展开后的文本比对，
   否则每次进页面都误判"不一致"而白重建一次会话（几秒卡顿）。

**验证点**：勾 2 个技能 → 选一个 Agent → 问"用二维码技能把 example.com 做成二维码"。
logcat 应出现 `AGLoadSkillTool: load skill.` 且正文不是 `Skill not found`；
`AGLlmChatViewModel` 不应出现 `Rebind system prompt ...` 的重复刷屏。

### ✅ B2【P0，已修】提示词里的 MCP 工具名写错

原文写 `runMcpTool`，实际注册名是 `run_mcp_tool`（LiteRT-LM 0.16.0 的 `ReflectionTool`
会做 `camelToSnakeCase`，见 §4）。模型照提示词调用 → 引擎无此函数 → MCP 路径直接不可用。

**修法（v65）**：提示词内两处 `runMcpTool` 改为 `run_mcp_tool`。
同时把"工具名一律写蛇形"写进 `AgentChatTaskModule.kt` 的注释，防止以后又改回驼峰。

### ✅ B3【P1，已修】没有 MCP 工具时，提示词禁止模型正常回答

`getEffectiveBaseSystemPrompt(..., hasMcpTools=false)` 返回 `DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY`，
其第 4 步原为：

> If no relevant skill is found, output "No relevant skills found" and stop.

任何与技能无关的普通提问都会命中这条 → 模型只回一句 `No relevant skills found`。
这不是模型能力问题，是提示词显式禁止作答。

**修法（v65）**：两版提示词的第 1 / 第 4 步都改成
"没有匹配就用普通对话直接回答，不得拒答、不得提前停止"。
并在开头补一句"普通问题不需要技能"，降低小模型"为了走流程而卡住"的概率。

**迁移**：历史版本把旧提示词存进了用户数据。`isDefaultSystemPrompt()` 现在用旧版独有的
固定话术（`"No relevant skills found" and stop` 等）做特征识别，命中即视为默认提示词，
升级后自动切到新版 —— 否则用户装上新包照样被拒答。

### ⬜ B4【P2，未修】"是否默认提示词"用字符串全等判定

`isDefaultSystemPrompt()` 只与两个常量做 `==`（现已补上旧版话术特征匹配，但本质仍是字符串比较）。
用户手动改过一个字、或以后改了默认提示词文案，判定即失效，MCP 开关不再影响提示词选择。
**建议**：改成"模板是否包含占位符"判定，语义更稳。

### ⬜ B5【P2，未修】`agent_id` / `skill_ids` 字段预留但从未写入

`conversation_profile.proto` 里有字段，全仓无写入点。记录无法真正绑定 Agent 与技能集，
每次进会话只能靠全局的"当前勾选"。

### ⬜ B6【P1，未修】工具循环没有轮次上限

`DefaultAgentRuntimeExecutor.executeStream()` 只调用一次 `runInference`，ReAct 循环在引擎内部。
Kotlin 侧唯二保护是 JS 60 秒超时与等待 JS 函数就绪 10 秒。小模型反复调用工具时会一直转到 token 耗尽。

### 🔍 B7【P2，隐患未复现】工具依赖 `lateinit` 赋值时序

`AgentToolsImpl` 的 `context / taskId / dataStoreRepository / mcpManagerViewModel` 都是 `lateinit`，
由 Composable 体在 `AgentChatScreen.kt` 赋值；而 `runMcpTool` 是 `by lazy` 且捕获 `taskId`。
若在赋值前触发 `getAvailableTools()` 会抛 `UninitializedPropertyAccessException`。
**已做的止血**：v65 新增 `AgentTools.toolsPromptOrEmpty()`，所有非界面入口改走它，
读不到就当没有 MCP 工具，不再抛异常。根因（构造注入）仍未改。

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

## 9. 修法进度

### 9.1 已实施（v65 / 2026-11-02）

1. **收口提示词拼装**（修 B1）：`PromptExpander.expand()` 支持无占位符追加；
   `LlmChatViewModel.resetSession()` 成为唯一出口；`buildAgentSystemPrompt()` 单一实现。
   顺带把 **动作通道** 与 **约束解码** 也纳入收口（原先只有"重置会话"一条路径传）。
2. **改提示词文案**（修 B2/B3）：`runMcpTool` → `run_mcp_tool`；"找不到就停" → "照常回答"；
   并加旧版提示词特征识别做迁移。

### 9.2 待做（优先级从高到低）

3. **给循环加上限**（修 B6）：在 `executeStream` 侧统计工具事件数，超过 N 次（建议 6）强制收尾。
   这是当前唯一还会"烧到 token 耗尽"的问题。
4. **改成占位符判定**（修 B4）：`isDefaultSystemPrompt` 用 "包含 `___SKILLS___`" 替代字符串全等。
5. **落实 `agent_id/skill_ids` 写入**（修 B5），把 Agent 与技能集绑定到会话记录。
6. **`AgentToolsImpl` 改构造注入**（修 B7），彻底消除 `lateinit` 时序隐患。

> 验收方式见 §7 B1 末尾的"验证点"。
