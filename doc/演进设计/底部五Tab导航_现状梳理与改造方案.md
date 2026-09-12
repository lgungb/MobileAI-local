# 底部五 Tab 导航 · 现状梳理与改造方案

> 目标：进入 App 后直接看到**底部 5 个导航栏（微信式）**：
> **对话 / 功能 / 实验 / 模型 / 系统**。
> 本文先做**现状梳理**（每个界面窗口的作用 + 对应实现 .kt 文件），再给**改造方案**与待拍板项。

---

## 一、工程基础事实（先对齐）

| 项 | 事实 |
|---|---|
| 技术栈 | Android 原生 · Kotlin · Jetpack Compose · Material 3 |
| 界面文件类型 | **`.kt`（Kotlin / Compose）**，不是 HarmonyOS 的 `.ets`；项目里没有 `.ks` 文件 |
| 包名 | `com.encourage.app`（不可改） |
| 单 Activity | `MainActivity` → Compose 根 `GalleryApp` → `GalleryNavHost` |
| 源码根 | `app/src/main/java/com/encourage/app/` |
| 构建 | `gradlew.bat assembleRelease --offline`（JDK 21） |
| 现状导航 | **NavHost + 侧边抽屉（Drawer）**，不是底部栏 |

### 关键机制：这套 App 是「任务驱动（Task 驱动）」的

所有 AI 界面都不是写死的页面，而是注册成一个 **CustomTask**，由 Hilt 注入
`Set<CustomTask>`，在 `ModelManagerViewModel` 里装配成 `uiState.tasks`。

```
点击入口(navigateToTaskScreen(task))
  → 路由 model_list  (ModelManager：该任务下选模型)
  → 路由 route_model/{taskId}/{modelName}
  → customTask.MainScreen()   ← 真正跑起来的 AI 界面
```

- 任务顺序：`ModelManagerViewModel.PREDEFINED_LLM_TASK_ORDER`
- 任务 ID 常量：`data/Tasks.kt` → `object BuiltInTaskId`
- 对话页顶部已有**模型切换**（`ui/common/ModelPageAppBar.kt`）与**来源切换**（本地/云端）

> 结论：**"直接进入某个对话界面"这件事，本质是跳过"选模型"这步，直接推
> `route_model/{taskId}/{modelName}`**（模型取"已下载的第一个"，没有则引导去模型页）。

---

## 二、界面窗口清单（作用 → 实现文件 → 当前入口）

### 2.1 主框架与导航

| 窗口 / 组件 | 作用 | 实现文件 | 当前入口 |
|---|---|---|---|
| 应用根 | 组合根、注入本地 API Server 的模型桥接 | `GalleryApp.kt` | — |
| 导航图 / 路由表 | 定义 6 条路由与转场动画、深链处理 | `ui/navigation/GalleryNavGraph.kt` | — |
| 首页 | 应用标题 + 分类 Tab（LLM / 传统ML / 实验性）+ 任务卡片 + 侧边抽屉 | `ui/home/HomeScreen.kt`（56KB 主文件） | 启动默认页 `homepage` |
| 侧边抽屉 | 三组导航（主要 / 智能 / 系统） | `HomeScreen.kt` 内 `ModalNavigationDrawer`（L294~427） | 首页左上角或右滑 |
| 顶部标题条 | 首页 App 名称与动画 | `GalleryAppTopBar.kt`、`HomeScreen.AppTitle` | — |

### 2.2 五个 Tab 对应的能力窗口

| # | 能力 | 作用 | 实现文件（.kt） | 任务 ID |
|---|---|---|---|---|
| 1 | **AI 对话** | 端侧大模型自由对话：流式、思考折叠、历史、导出、顶部切模型、顶部切来源 | `ui/llmchat/LlmChatScreen.kt` + `LlmChatViewModel.kt` + `LlmChatTaskModule.kt` | `llm_chat` |
| 2 | **Agent Skills 对话** | 智能体对话：切换内置 Agent、导入自定义 Agent、Skill 管理、MCP 工具调用 | `customtasks/agentchat/AgentChatScreen.kt` + `AgentChatViewModel.kt` + `AgentChatTaskModule.kt`（同目录另有 20 个管理器/BottomSheet） | `llm_agent_chat` |
| 3 | **Prompt Lab** | 单轮提示词实验室：四类模板（自由 / 改写语气 / 摘要 / 代码） | `ui/llmsingleturn/LlmSingleTurnScreen.kt` + `PromptTemplatesPanel.kt` + `ResponsePanel.kt` + `LlmSingleTurnTaskModule.kt` | `llm_prompt_lab` |
| 4a | **Ask Image**（识图） | 给图提问 / 图像理解 | `ui/llmchat/LlmChatTaskModule.kt`（L313 附近注册） | `llm_ask_image` |
| 4b | **Audio Scribe**（听写） | 语音转录 / 音频问答 | 同 `LlmChatTaskModule.kt`；支持 `llmSupportAudio` 的模型 | `llm_ask_audio` |
| 4c | **Tiny Garden**（AI 小花园） | 游戏化对话任务（依赖 FunctionGemma，国内模型难下载 → 当前点开不可用） | `customtasks/tinygarden/TinyGardenTask.kt` + `TinyGardenViewModel.kt` | `llm_tiny_garden` |
| 4d | **Mobile Action**（手机操控） | 语音/对话驱动手机操作（Intent），部分需辅助功能权限 | `customtasks/mobileactions/`（含 `MobileActionsChallengeDialog.kt` 在 `ui/home/`） | `llm_mobile_actions` |
| 4e | **模型管理 / 下载 / 导入** | 模型列表（LLM/传统ML/实验性）、下载（WorkManager+通知）、导入 `.task`/`.litertlm`、跑分、HF 详情 | `ui/modelmanager/GlobalModelManager.kt`、`ModelManager.kt`（按任务选模型）、`ModelImportDialog.kt`、`HfModelDetailsSheet.kt`、`ui/benchmark/BenchmarkScreen.kt` | 路由 `model_manager` |

### 2.3 系统类窗口（原抽屉「智能 / 系统」组）

| 窗口 | 作用 | 实现文件 | 当前实现形态 |
|---|---|---|---|
| **任务中心** | 定时提醒 / 每日鼓励：增删改、按星期重复、AI 提示词、立即运行 | `ui/tasks/TaskCenter.kt` + `TaskCenterViewModel.kt`；调度 `data/tasks/TaskScheduler.kt` | **全屏 Dialog**（`TaskCenterDialog`） |
| **文件管理** | SAF 浏览/编辑/分享/删除模型与 skill 文件 | `ui/filemanager/FileManagerDialog.kt` + `FileManagerViewModel.kt` | Dialog |
| **通知** | 已安排通知列表、可删除 | `ui/notifications/NotificationsScreen.kt` | **独立路由** `notifications` |
| **设置** | 主题 / 语音(TTS) / 云端 API / 本地 API Server / Feature Flag / 清数据 / 协议 | `ui/home/SettingsDialog.kt`（主体）、`VoiceSettings.kt`、`ApiProviderSettings.kt`、`LocalServerSection.kt`、`FeatureFlagSection.kt` | Dialog |
| **帮助中心** | 使用指南、FAQ、隐私说明 | `ui/help/HelpCenterDialog.kt` | Dialog |

### 2.4 其他支撑文件

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | 单 Activity、Splash、edge-to-edge、深链 Intent |
| `CrashDisplayActivity.kt` | 全局崩溃展示页（独立 Activity） |
| `ui/common/ModelPageAppBar.kt` | 对话页顶部：模型切换 + 来源切换 + 返回 |
| `ui/common/ErrorDialog.kt` / `MemoryWarning.kt` | 错误与内存告警（健壮性基建） |
| `customtasks/common/CustomTask.kt` | 任务抽象接口（各能力实现的统一契约） |

---

## 三、目标结构：底部 5 Tab 映射

| Tab | 名称 | 直接跳转目标 | 落地方式 |
|---|---|---|---|
| 1 | **对话** | AI Chat（`llm_chat`） | 直达 `route_model/llm_chat/{当前模型}`，顶部已有模型切换 |
| 2 | **功能** | Agent Skills 对话（`llm_agent_chat`） | 直达 `route_model/llm_agent_chat/{当前模型}` |
| 3 | **实验** | Prompt Lab（`llm_prompt_lab`） | 直达 `route_model/llm_prompt_lab/{当前模型}` |
| 4 | **模型** | 5 项：Audio Scribe / Ask Image / Tiny Garden / Mobile Action / 模型下载与导入 | 前 4 项点击进入对应任务的**模型选择列表**（已下载优先）；第 5 项进 `GlobalModelManager`（浏览/下载/导入/跑分） |
| 5 | **系统** | 帮助中心 / 设置 / 通知 / 任务中心 / 文件管理 | 聚合页 5 项；前四项沿用现有 Dialog/路由实现 |

### 改造要点（技术侧）

1. **新增底部导航容器**：`Scaffold(bottomBar = NavigationBar{...})`，5 个 `NavigationBarItem`。
2. **Tab 与路由的对应关系**：推荐"5 个 Tab = 5 条顶层路由"，切换 Tab 用
   `navigate(route){ popUpTo(顶层起点){saveState=true}; launchSingleTop=true; restoreState=true }`
   （微信式：Tab 之间不堆栈，各 Tab 保留自己的栈）。
3. **隐藏底部栏的场景**：进入具体对话页/跑分页等二级页时隐藏（避免和输入框抢空间）。
4. **无模型时的兜底（已定）**：点 Tab 1/2/3 或 Tab 4 的能力项，若一个已下载模型都没有 →
   **友好提示 + 引导去 Tab 4「模型」页下载**（Snackbar / Dialog），**不崩溃、不白屏、不卡死**。
5. **抽屉（已定）**：整体移除，导航唯一化。

### 预计影响文件

| 文件 | 改动类型 |
|---|---|
| `ui/navigation/GalleryNavGraph.kt` | 改：新增 5 条顶层路由 + 底部栏容器 |
| `ui/navigation/BottomNavBar.kt` | 新增：底部导航栏组件 |
| `ui/system/SystemScreen.kt` | 新增：系统聚合页（4 项） |
| `ui/modelmanager/GlobalModelManager.kt` | 改：顶部加 4 个能力入口卡片 |
| `ui/home/HomeScreen.kt` | 改（大）：去掉/剥离抽屉，首屏降级或改造 |
| `res/values/strings.xml` + `values-zh/strings.xml` | 改：新增 Tab 文案（中英双语对齐） |
| `data/Tasks.kt` | 可能改：Tab 常量 |

---

## 四、已确认决策（2026-09-12 用户拍板）

| # | 问题 | **决策** |
|---|---|---|
| Q1 | 原首页（分类 Tab + 任务卡片列表）怎么处理？ | **不再作为启动页**；其能力入口**精简为 5 项**，落到 Tab 4「模型」页（Audio Scribe / Ask Image / Tiny Garden / Mobile Action / 模型下载与导入） |
| Q2 | 左侧抽屉是否保留？ | **移除**（`HomeScreen.kt` 的 `ModalNavigationDrawer` 整体删除） |
| Q3 | Tab 4 里 4 个能力项点击后行为？ | **先进入该任务的模型选择列表**（保留现有"先选模型"流程），但**列表优先显示已下载的模型** |
| Q4 | 「系统」页除四项外的「文件管理」？ | **并入系统页，共 5 项**：帮助中心 / 设置 / 通知 / 任务中心 / 文件管理 |

---

## 五、工程约定（本次协作新增，长期有效）

1. **每次构建成功后做版本控制**：`git commit`（中文提交信息，格式沿用现有 `feat:` / `fix:` 前缀）。
2. **构建内存上限**：`gradle.properties` 内 Gradle daemon `-Xmx2048m` 已有；
   将补 Kotlin 编译守护进程独立上限 + 限制并行 worker 数，防内存爆掉。
3. **不递归遍历目录**：逐层查看，控制上下文。
4. **思考与沟通用中文。**
5. **健壮性**：出错必须有提示、不卡死；线程 / 协程做好取消与异常兜底
   （参考 `customtasks/common/SteadinessMonitor.kt`、`ui/common/ErrorDialog.kt`）。
