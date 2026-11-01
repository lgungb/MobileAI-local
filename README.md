# SeAI_Encourage — Encourage（端侧 AI 助手）

> **一句话定位**：跑在你手机上的、**完全本地的 AI 助手**——不用联网、不上传数据，
> 对话、识图、语音、朗读都发生在你的设备上，**隐私永不离开手机**。

## 这是干什么的

Encourage 是一个**端侧优先（on-device / edge AI）**的 Android AI 应用。核心是把大语言模型（Gemma 等）
**直接装进手机、跑在骁龙 NPU 上**，而不是发到云端。同时它不只是一个聊天框，而是把多种 AI 能力
收敛进一个可对话、可自动化、可扩展的助手：

| 你会怎么用它 | 背后能力 |
|---|---|
| 像跟人聊天一样问任何问题 | 本地大模型自由对话（流式、思考折叠、历史记录、导出） |
| 给它看一张图、问图里是什么 | 图像问答 |
| 说一段话让它转成文字 | 语音转录 |
| 让它把回复**读出来**（用你喜欢的声音/语速/音调） | 双引擎 TTS（系统 + 离线语音包），长文本可完整读完 |
| "明天 8 点叫我起床" 让它自动设闹钟提醒 | 定时任务 + AI 创建 + 通知朗读 |
| 让预设的「教练/编程/翻译/导师」角色干活 | Agent（智能体）+ Skill（技能）+ MCP 工具 |
| 手机**开着当本地 API 服务器**，供电脑/其他设备调用 | 本地 OpenAI 兼容 HTTP 接口 |
| 可选接入你自己的云端 API Key（DeepSeek/通义/Kimi…） | OpenAI 兼容多套云端配置 |

### 核心卖点

- 🔒 **隐私不泄露**：默认 100% 本地推理，数据不出手机；云端仅是**显式开启**的增强项。
- 🚀 **离线可用**：没网也能对话、识图、朗读。
- ⚡ **真机 NPU**：针对骁龙（SM8750 等）NPU 优化，端侧推理快。
- 🧩 **功能全且可扩展**：聊天/识图/语音/朗读/定时/AI 角色/技能/本地 API，一个 App 收拢。
- 🤖 **Agent + Skill + MCP**：内置可换角色，支持导入/编辑自定义 Agent 与 Skill，可接 MCP 工具。
- 🎛️ **特调记录**：每条会话可保存自己的模型、角色提示词与采样参数，互不干扰。

> 本工程是原 **Google AI Edge Gallery** 的 **Encourage fork**：已去 Google 化、换自有品牌
> `com.encourage.app`，保留 Apache 2.0 开源许可。

## 界面长什么样

进入 App 是**微信式底部 5 Tab**：

```
对话        功能        实验        模型        系统
会话列表    Agent 列表   Prompt Lab  能力+模型管理  帮助/设置/…
   ↓ 点任意一条
全屏会话页（底栏自动隐藏）
```

- **对话 / 功能**：是会话列表（像微信聊天列表）。右下角「+」新建一条，点条目进入全屏会话。
- 每条会话记录保存了**自己的**模型、角色提示词、采样参数——同一模型可以有多套完全不同的"特调"。
- 记录持久化在本地，重启保留；删除记录会连带删除它名下的聊天记录。

## 当前状态（重要）

功能是**完整可用**的，但**数据与设置层存在结构性缺陷**，是本项目已知 bug 的主要来源：

| 症状 | 根因（详见 `doc/05_数据与存储.md`） |
| --- | --- |
| 会话越多越卡，发一条消息顿一下 | 全部会话/消息存在**同一份** `user_data.pb`，每次保存全量重写 |
| 数据文件一坏就无限闪退，只能清数据 | 5 个 DataStore 都是裸配置，**没有损坏兜底** |
| 设置改了没生效 / 重启回退 / 技能勾选自己变回去 | 同步 `runBlocking` 读写 + "读快照后整体覆盖"丢更新 |

修复方案已定，见 **`doc/06_重构方案.md`**（P0 止血 → P1 换地基 → P2 收敛）。

## 快速构建（离线）

```bash
cd D:\AndroidWork\SeAI_Encourage

# 需 JDK 21；若系统未配置 JAVA_HOME：
export JAVA_HOME="C:/Program Files/Java/jdk-21.0.12.1"   # bash
# set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1       # cmd

./gradlew.bat assembleRelease --offline
./gradlew.bat --stop        # 构建完立刻释放守护进程内存
```

产物：`app/build/outputs/apk/release/app-release*.apk`（文件名可能带版本后缀）。

> 约定：本机环境一律 `--offline` 构建；**不要删除** `%USERPROFILE%\.gradle\caches`、
> `%USERPROFILE%\.gradle\wrapper\dists` 等缓存，也不要触发联网全量下载。
> 构建内存上限已在 `gradle.properties` 限制，**不要上调**。
> 构建**单线执行**：多人/多进程同时构建会抢文件锁，表现为日志停在半路、无 BUILD SUCCESSFUL。

## 工程说明（交接给下一个开发者 / AI）

- **包名 / applicationId**：`com.encourage.app`（是应用标识，**不要改**）
- **License**：Apache 2.0（见根目录 `LICENSE`，保留 Google 版权原样）
- **工程名**：`SeAI-Encourage`（见 `settings.gradle.kts`）
- **版本号纪律**：每完成一次可构建的提交，`versionCode +1`、`versionName` 末位 +1；
  纯文档提交不升版本号。

## 目录地图

| 路径 | 说明 |
| --- | --- |
| `app/` | Android 单模块（`settings.gradle.kts` 只有 `include(":app")`） |
| `app/src/main/java/com/encourage/app/` | Kotlin 源码（Compose + Hilt） |
| `app/src/main/assets/` | agents / skills / tinygarden / `model_allowlist.json` 等内置资源 |
| `app/src/main/proto/` | Protobuf 定义（`settings.proto` 含 `UserData`，`chat_history.proto` 含会话） |
| `app/libs/` | sherpa-onnx 本地 AAR |
| `gradle/` | wrapper + `libs.versions.toml`（版本目录） |
| `doc/` | 中文说明文档（见下） |
| `LICENSE` | Apache 2.0 |

## 文档入口

**事实类（可直接信）**

- `doc/00_索引.md` — 总索引：当前形态 / 技术栈 / 文档地图 / 协作约定
- `doc/01_架构说明.md` — 代码怎么组织、关键链路、每个模块作用
- `doc/02_构建与工具链.md` — 构建命令、离线与内存约定、JDK/Gradle/依赖位置
- `doc/03_界面与操作流程.md` — 5 个 Tab 各有什么、每个功能怎么用
- `doc/04_功能清单与实现状态.md` — 每项能力做没做、做到哪；已知技术债
- `doc/05_数据与存储.md` — 存储结构、写盘粒度、已知缺陷、**故障症状→根因对照表**
- `doc/08_界面地图与数据流.md` — **界面总图 + 路由表 + 复用关系 + 数据流全链路**（改功能先看这篇）
- `doc/07_Agent与Skill_实现事实.md` — Agent/Skill 真实机制、工具能力边界（含源码行号）

**问题与方案**

- `doc/06_重构方案.md` — 只换数据层的重构方案（含验收与回滚）
- `doc/待优化.md` — bug 与质量缺陷清单（按严重程度，含已修记录）

> 顶层不再保留 gallery 仓库的 `skills/`、`mcp/`、`model_allowlists/`、`model_allowlist.json`：
> 它们不被 gradle 构建引用，APK 使用的资源已在 `app/src/main/assets/` 内置副本。详见 `doc/01`。
