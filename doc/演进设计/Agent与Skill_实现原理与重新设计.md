# Agent 与 Skill · 实现原理与重新设计

> 本文分两部分：
> **第一部分**讲清现状实现原理（全部来自源码，附 `文件:行号`）；
> **第二部分**基于端侧约束给出重新设计方向与新增清单。
>
> 本文是**设计文档，不含代码改动**。所有改动建议都标注了"是否必须改代码"。

---

# 第一部分 · 实现原理

## 1. 一个必须先破除的混淆：项目里有两个「Agent」

| 代号 | 是什么 | 位置 |
| --- | --- | --- |
| **(A) Agent Chat 任务** | 一个**带工具能力**的对话任务，系统提示词写死，靠 LiteRT-LM 原生 function calling 调工具 | 任务 id `llm_agent_chat`，`customtasks/agentchat/AgentChatTaskModule.kt:57-97` |
| **(B) AgentDefinition** | **只是一段系统提示词 + 元数据**，没有任何能力声明 | `data/agents/AgentDefinition.kt:17-32`，内置 4 个在 `assets/agents/` |

**关系**：(B) 是 (A) 的「人设皮肤」。选一个 Agent，只是把它的 `systemPrompt` 覆盖到会话上
（`AgentChatScreen.kt:725-734`），**工具集完全不变**。

`assets/agents/coach.json` 的全部字段：

```json
{
  "id": "builtin-coach",
  "name": "鼓励教练",
  "description": "在你低落、拖延、自我怀疑时给你打气…",
  "author": "Encourage",
  "version": 1,
  "systemPrompt": "你是 Encourage 应用里的「鼓励教练」…"
}
```

**没有 `skills:[…]`、没有 `tools:[…]`、没有 `modelId`。** 这是后面所有设计限制的根源。

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

`SKILL.md` 格式（解析见 `SkillManager.kt:961-1039`）：

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

> `selected` 字段很关键：**只有"选中"的 skill 才会进入模型可见的技能菜单**。

## 3. ⭐ Skill 的三种执行机制（并存，不是单一机制）

这是最容易被误解的地方。Skill 实际由**三个环节组合**完成：

| 环节 | 机制 | 代码 |
| --- | --- | --- |
| ① **菜单** | 选中 skill 的 `name`+`description` 填入 system prompt 的 `___SKILLS___` 占位符 | `SkillExtensions.kt:29-35` → `AgentChatTaskModule.kt:177` |
| ② **按需加载全文** | 模型调 `load_skill` 工具 → 返回该 skill 的 `instructions` 全文，作为 tool result 进对话 | `LoadSkillTool.kt:34-67` |
| ③ **执行动作** | 按 instructions 指示，调 `run_js`（WebView 跑 JS）或 `run_intent`（系统动作） | `RunJsTool.kt`、`RunIntentTool.kt` |

system prompt 里写死的流程（`AgentChatTaskModule.kt:76-84`，FLOW A）：

```
① 找最相关的 skill  →  ② 用 load_skill 读它的指令  →  ③ 按指令执行
```

**① 是"渐进式披露"的关键设计**：菜单里只有名字和一句话描述（每个约 30 token），
全文要等模型主动 `load_skill` 才进上下文。
**这让技能数量可以很多而不炸上下文窗口**——在 16K~32K 窗口的端侧模型上是必要的，不是可选的优化。

## 4. 工具集：全局写死 4 个

`AgentToolsImpl.getAvailableTools()`（`AgentTools.kt:80-82`）固定返回：

| 工具 | Kotlin 函数名 | 作用 | alwaysAllow |
| --- | --- | --- | --- |
| `LoadSkillTool` | `loadSkill` | 把 skill 的 instructions 拉进上下文 | 是 |
| `RunMcpTool` | `runMcpTool` | 调已连接的 MCP server 工具 | 是（另有权限确认） |
| `RunJsTool` | `runJs` | 在聊天 WebView 里跑 skill 的 JS | 是 |
| `RunIntentTool` | `runIntent` | 拉起系统 Intent | 是（部分需权限） |

工具**在模型初始化时注册进 LiteRT-LM 引擎**（`DefaultAgentRuntimeExecutor.kt:85`），
schema 由 `@Tool` / `@ToolParam` 注解生成，**Kotlin 函数名即模型看到的工具名**。

## 5. 工具调用循环：由引擎驱动，Kotlin 侧没有轮次上限

```
用户发消息
 → LlmChatViewModelBase.generateResponse()            (LlmChatViewModel.kt:427)
 → DefaultAgentRuntimeExecutor.executeStream()        (DefaultAgentRuntimeExecutor.kt:94-182)
 → runtimeHelper.runInference(...)  ← 单次调用        (:145-177)
      └─ LiteRT-LM 引擎内部 ReAct 循环：
           模型发起工具调用 → 执行 Kotlin 工具 → 结果回灌 → 继续生成
           直到产出最终文本
```

- **是原生 function calling，不是文本解析**（`ToolDefinition.kt:39` 直接 `extends ToolSet`）。
- **Kotlin 侧没有 while 轮次上限**。唯一的保护是：JS 工具 **60 秒超时**（`AgentChatScreen.kt:359`）、
  等待 `ai_edge_gallery_get_result` 最多 **10 秒**（`:428`）。
- **轮次上界实际由引擎与 `maxOutputTokens` 决定** → 小模型若陷入工具循环，会一直转到 token 耗尽。
- 合规性靠两个手段而非解析兜底：`enableConversationConstrainedDecoding=true`（约束解码）
  + **Agent 场景强制 `TopK=1`**（贪婪解码，`AgentChatSamplingParamsManager.kt:116-118`）。

## 6. 四个工具的能力边界（决定性事实）

### 6.1 `run_js` = Android WebView（Chromium），不是精简 JS 引擎

- `javaScriptEnabled=true`、`allowFileAccess=true`（`GalleryWebView.kt:154,156`），
  `WebViewAssetLoader` 暴露 `assets/` 与 `filesDir/`（`:67-69`）。
- **与 App 的唯一桥**：JS 侧必须定义
  ```js
  window['ai_edge_gallery_get_result'] = async (data, secret) => { …; return JSON字符串 }
  ```
  完成后调 `@JavascriptInterface AiEdgeGallery.onResultReady(result)` 回传（`AgentChatScreen.kt:422-433,476`）。
- **能做到**：完整浏览器能力——网络请求、本地计算（`crypto.subtle`、Canvas）、渲染任意 HTML/CSS/JS。
  `query-wikipedia` / `interactive-map` 就是靠 WebView 发网络请求。
- **做不到**：访问 Android 传感器 / 原生 API（只能通过"回传字符串"这一条路）。

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

**超出白名单即失败**（会回退提示"试试用 skill 方式"）。

### 6.3 现有架构**没有**的能力（不要假设可用）

读剪贴板、读写通讯录、定位/传感器、直接访问 App 数据库、任意系统 API ——
**代码里没有对应工具**。要用必须新增工具（改代码）。

---

# 第二部分 · 重新设计

## 7. 端侧约束：为什么这决定了设计

| 约束 | 数值/事实 | 对设计的含义 |
| --- | --- | --- |
| 模型规模 | 2B~4B 级（Gemma / FunctionGemma） | function calling 弱，**多跳调用成功率低** |
| 上下文窗口 | 16K~32K | 渐进式披露是**必需**；tool result 必须短 |
| 推理速度 | 端侧 token/s 低 | **每一次工具跳转 = 一次完整推理**，用户等待显著变长 |
| 网络 | 应当默认离线可用 | 联网 skill 必须显式标注 |
| 工具面 | 仅 `run_js` / `run_intent`(6 个) | 不新增工具就无法访问新的系统能力 |
| 循环保护 | Kotlin 侧无轮次上限 | 必须避免"容易让模型反复调用"的 skill 形态 |

## 8. 核心结论：什么 Skill「既做得到、又好用」

### 8.1 六条判断标准

1. **单跳优先** —— 理想情况一次工具调用完成。`load_skill → run_js` 已经两跳，
   再加第三跳（如再调 `run_intent`）失败率会陡增。
2. **确定性输入输出** —— 模型只需填几个明确参数。小模型最擅长这个。
3. **结果短** —— 回灌上下文的内容要短。返回一屏 HTML 会把窗口吃掉。
4. **本地可算** —— 能在 WebView 里算完就不要联网（离线可用 + 快 + 无 CORS 风险）。
5. **幂等/无副作用** —— 失败可安全重试。
6. **模型自己做不好的事** —— 见下一条，这是最容易被忽略的。

### 8.2 ⚠️ 最重要的原则：很多需求**不该**做成 Skill

Skill 的价值是**补模型做不到的事**。以下需求**不该**做成 skill，因为普通对话直接就能做，
做成 skill 反而多一次工具跳转、拉长等待、增加失败点：

| 需求 | 该不该做 skill | 原因 |
| --- | --- | --- |
| 总结、改写、润色、翻译 | ❌ **不要** | 模型本职，走普通对话即可 |
| 起名、写文案、头脑风暴 | ❌ **不要** | 同上 |
| 解释概念、给建议 | ❌ **不要** | 同上 |
| **精确计算**（哈希/单位换算/日期差） | ✅ **要** | 小模型算数不可靠 |
| **访问设备能力**（日历/通知/短信） | ✅ **要** | 模型无法直接碰系统 |
| **渲染交互界面**（图表/地图/卡片） | ✅ **要** | WebView 的强项，纯文本做不到 |
| **取实时数据**（天气/汇率/百科） | ✅ **要** | 端侧模型无实时知识 |

一句话：**"需要模型之外的资源"才值得做成 Skill。**

### 8.3 可行性矩阵

| 类型 | 可行性 | 好用度 | 说明 |
| --- | --- | --- | --- |
| 纯本地计算（哈希、单位换算、二维码、密码生成） | ✅ 高 | ⭐⭐⭐ | 单跳 `run_js`，本地算，结果短 |
| 本地数据渲染（图表、仪表盘、卡片） | ✅ 高 | ⭐⭐⭐ | WebView 天生强项，纯文本做不到 |
| 系统动作（日历/通知/短信/邮件） | ✅ 高 | ⭐⭐ | 走 `run_intent` 白名单，单跳；部分需权限 |
| 联网查询（百科/天气/汇率） | 🟡 中 | ⭐⭐ | WebView 可发请求，但依赖网络 + 目标站点可用性 |
| 多步链（先查再算再写） | ❌ 低 | ⭐ | 小模型多跳失败率高，**不建议** |
| 需原生 API（剪贴板/通讯录/定位） | ❌ 不可行 | — | 无对应工具，必须改代码 |

## 9. 🔴 必须先修的三个缺陷（否则新增 Agent 会踩坑）

### 缺陷 1：选自定义 Agent 会让 Skill 路由**静默失效**（最严重）

- 系统提示词优先级：会话记录 > **选中 Agent 的 systemPrompt** > 任务默认 prompt。
- `___SKILLS___` / `___TOOLS___` 占位符**只写在任务默认 prompt 里**。
- 而 `AgentChatTaskModule.kt:270-276` 对"非默认 prompt"是**直接透传**的。

**后果**：一旦选中 `coach` / `coder` / 任何自定义 Agent，占位符不会被替换，
模型看不到有哪些技能可用 → **技能和 MCP 全部静默失效**。

**两个修法**：
- **(a) 改代码（推荐）**：非默认 prompt 也自动追加技能/工具路由段。
- **(b) 不改代码（过渡）**：约定**所有自定义 Agent 的 `systemPrompt` 末尾必须自带**技能路由段。
  新增 Agent 时必须手动复制这段说明。

### 缺陷 2：切换判定用字符串全等，极其脆弱

`isDefaultSystemPrompt` 拿 `==` 比较默认提示词全文（`AgentChatTaskModule.kt:264-267`）。
**改动默认文案里任何一个字，切换逻辑就会失效。** 建议改为标记位（如 proto 字段）而非文本比较。

### 缺陷 3：`agent_id` / `skill_ids` 字段预留但从未写入

`conversation_profile.proto:56,58` 已有这两个字段，但"使用 Agent"只改内存里的 prompt，
**不持久化** → 退出会话再进来，Agent 选择丢失。
注意：会话记录已经支持绑定 profile 了，这两个字段是现成的挂载点。

## 10. Agent 重新设计建议

### 10.1 结构调整：从"纯提示词"升级为"提示词 + 能力声明"

建议给 `AgentDefinition` 增加（**需改代码**）：

| 新字段 | 作用 |
| --- | --- |
| `skills: List<String>` | 声明该 Agent 可用哪些 skill（空 = 全部） |
| `icon` / `color` | 列表可辨识度（现在 4 个 Agent 混在一起难区分） |
| `category` | 分类（工作 / 学习 / 生活 / 编程） |
| `modelHint` | 建议模型（仅提示，不强制） |

**收益**：既省上下文（只注入相关 skill 菜单），又提高路由准确率（模型选择面变小）。

**短期可先做的**：用现有字段实现"软声明"——在 `systemPrompt` 里写
"你只使用以下技能：xxx、yyy"。零代码改动，但依赖模型自觉。

### 10.2 建议新增的 Agent（纯数据，成本极低）

现有 4 个：鼓励教练 / 编程助手 / 翻译专家 / 学习导师。它们都没绑定能力，只换人设。

| 建议新增 | 人设要点 | 依赖技能 |
| --- | --- | --- |
| **日程管家** | 帮用户排期、建提醒、读日历 | 日历类（白名单内） |
| **写作助手** | 帮写/改文案，**不需要 skill**，纯提示词 | 无 |
| **数据整理员** | 把用户给的数据整理成表格/图表 | 图表 skill |
| **出题官** | 根据材料出练习题并批改 | 无 |
| **翻译精修** | 比现有 translator 更强调"信达雅"与术语一致性 | 无 |

> 注意：**纯人设类 Agent 越多越好，因为它们零成本**（只是一段提示词，不进上下文除非被选中）。
> 真正稀缺的是"有能力的 Agent"，而能力来自 Skill。

## 11. Skill 重新设计建议

### 11.1 结构建议

现行 `SKILL.md` 格式够用，建议补两个 frontmatter 字段（**需小改解析代码**）：

| 新字段 | 作用 |
| --- | --- |
| `needs-network: true` | 显式标注联网需求，UI 可提示"需联网" |
| `estimated-seconds: N` | 预估耗时，让用户知道要等 |

**零代码就能做的改进**：把这两个信息**写进 `description`**（如"查百科（需联网，约 3 秒）"），
因为它会被注入技能菜单，模型和用户都能看到。

### 11.2 建议新增清单（按推荐优先级，全部为纯数据：只加目录）

#### A 档：本地计算类（单跳 `run_js`，离线可用，结果短）——最推荐

| 技能 | 功能 | 为什么值得做 |
| --- | --- | --- |
| `unit-convert` | 单位换算（长度/重量/面积/温度/存储） | 模型换算易错；确定性高 |
| `date-diff` | 日期计算（距今天数、工作日数、年龄） | 模型算日期不可靠 |
| `password-generate` | 生成随机强密码 | 需真随机，模型做不到 |
| `uuid-generate` | 生成 UUID / 随机串 | 同上 |
| `percent-calc` | 百分比 / 折扣 / 涨跌幅 | 商业场景高频，模型易算错 |
| `qr-code` | （已有，可增强批量/带 logo） | 已有基础 |

#### B 档：本地渲染类（WebView 强项，纯文本做不到）

| 技能 | 功能 | 为什么值得做 |
| --- | --- | --- |
| `bar-chart` | 把一组数据画成柱状/折线图 | 对话里可视化数据，价值直观 |
| `countdown-timer` | 倒计时 / 番茄钟界面 | 有交互界面，非纯文本 |
| `table-render` | 把 JSON 渲染成可读表格 | 整理类任务的展示层 |

#### C 档：系统动作类（`run_intent` 白名单内，可能需权限）

| 技能 | 功能 | 备注 |
| --- | --- | --- |
| `quick-reminder` | 简化版"X 分钟后提醒我" | 走 `schedule_notification`，已有基础可增强 |
| `sms-draft` | 起草并拉起短信 | 白名单有 `send_sms` |
| `calendar-today` | 播报今天日程 | 需 `READ_CALENDAR` 授权 |

#### D 档：联网类（可选，需标注联网）

| 技能 | 功能 | 风险 |
| --- | --- | --- |
| `exchange-rate` | 汇率换算 | 依赖第三方接口可用性 |
| `weather` | 天气查询 | 同上 |
| `dict-lookup` | 词典释义 | 同上 |

### 11.3 **不建议做**的 Skill（明确排除）

- 任何"总结/改写/翻译/起名"类 —— 见 8.2，做成 skill 是负优化。
- 任何需要**三跳以上**的组合任务（如"查天气 → 生成图表 → 发邮件"）。
- 任何依赖**剪贴板/通讯录/定位**的 —— 无工具，做不了。

## 12. 落地路径（分三阶段，按代价从低到高）

| 阶段 | 内容 | 是否改代码 | 风险 |
| --- | --- | --- | --- |
| **阶段 1** | 新增 Agent（纯 JSON）+ 新增 A/B 档 Skill（纯目录） | ❌ **不需要** | 低 |
| **阶段 2** | 修复缺陷 1 与缺陷 3（占位符路由 + `agent_id` 持久化） | ✅ 需要 | 中（涉及提示词拼装） |
| **阶段 3** | 给 `AgentDefinition` 加能力声明字段 + 给 Skill 加 `needs-network` | ✅ 需要 | 中（涉及 proto 与 UI） |

**建议从阶段 1 开始**：零代码就能验证"新增 skill 是否真的被模型用起来"，
这个假设是整个设计的基础，应该先证伪或证实。

## 13. 待验证的关键假设

以下都是**设计成立的前提，但尚未在真机验证**：

1. 端侧 2B 模型能否稳定完成 `load_skill → run_js` 两跳？（现有 12 个 skill 的实际成功率未知）
2. 技能菜单里放 20+ 个 skill（只 name+description）时，模型的选择准确率如何？
3. A 档"纯计算类"skill 是否真的比模型直接算更好——**会不会模型直接算就够用了**？
4. WebView 内发起的网络请求在目标站点上的成功率（CORS、UA、反爬）。

> 建议在阶段 1 之后先做一轮真机小样本测试（挑 3 个 A 档 skill，各测 5 次），
> 用实测成功率决定是否值得铺开。

---

*本文档基于 2026-09-14 的源码事实编写。若后续修改了 `AgentChatTaskModule` 的提示词拼装
或 `AgentTools` 的工具集，请同步更新第 5、6、9 节。*
