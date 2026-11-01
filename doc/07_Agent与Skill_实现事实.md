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
schema 由 `@Tool` / `@ToolParam` 注解生成，**Kotlin 函数名即模型看到的工具名**（改名会破坏模型调用）。

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

## 7. 已知缺陷（未修）

| # | 缺陷 | 位置 | 影响 |
| --- | --- | --- | --- |
| 1 | 选自定义 Agent 会让 Skill 路由**静默失效** | Agent 切换逻辑 | 用户以为在用自己的 Agent，实际技能没生效 |
| 2 | 切换判定用字符串全等，极其脆弱 | Agent 选择判定处 | 改名/多语言即失效 |
| 3 | `agent_id` / `skill_ids` 字段预留但从未写入 | `conversation_profile.proto` | 记录无法真正绑定 Agent 与技能集 |

> 这三项属"已知未修"，改动前先确认是否已在新提交里解决（本文档随代码更新）。
