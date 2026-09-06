# SeAI_Encourage — Encourage（端侧 AI 助手）

> **一句话定位**：跑在你手机上的、**完全本地的 AI 助手**——不用联网、不上传数据，
> 对话、翻译、图像分析、语音朗读都发生在你的设备上，**隐私永不离开手机**。

## 这是干什么的

Encourage 是一个**端侧优先（on-device / edge AI）**的 Android AI 应用。核心是把大语言模型（Gemma 等）
**直接装进手机、跑在骁龙 NPU 上**，而不是发到云端。同时它不只是一个聊天框，而是把多种 AI 能力
收敛进一个可对话、可自动化、可扩展的助手：

| 你会怎么用它 | 背后能力 |
|---|---|
| 像跟人聊天一样问任何问题 | 本地大模型自由对话（流式、思考折叠） |
| 让它**翻译**一段话 / 一份文本 | 内置翻译 Agent + 提示词实验室 |
| 给它看一张图、问图里是什么 | 图像问答（Ask Image） |
| 说一段话让它转成文字 | 语音转录（Audio Scribe） |
| 让它把回复**读出来**（用你喜欢的声音/语速/音调） | 双引擎 TTS（系统 + 离线语音包） |
| "明天 8 点叫我起床" 让它自动设闹钟提醒 | 定时任务 + AI 创建 + 通知朗读 |
| 让预设的「教练/编程/翻译/导师」角色干活 | Agent（智能体）+ Skill（技能）+ MCP 工具 |
| 手机号**开着当本地 API 服务器**，供电脑/其他设备调用 | 本地 OpenAI 兼容 HTTP 接口 |
| 可选接入你自己的云端 API Key（DeepSeek/通义/Kimi…） | OpenAI 兼容多套云端配置 |

### 核心卖点
- 🔒 **隐私不泄露**：默认 100% 本地推理，数据不出手机；云端仅是**显式开启**的增强项。
- 🚀 **离线可用**：没网也能对话、翻译、分析、朗读。
- ⚡ **真机 NPU**：针对骁龙（SM8750 等）NPU 优化，端侧推理快。
- 🧩 **功能全且可扩展**：聊天/翻译/识图/听写/语音/定时/AI 角色/技能/本地 API，一个 App 收拢。
- 🤖 **Agent + Skill + MCP**：内置可换角色，支持导入/编辑自定义 Agent 与 Skill，可接 MCP 工具。

> 本工程是原 **Google AI Edge Gallery** 的 **Encourage fork**：已去 Google 化、换自有品牌
> `com.encourage.app`，保留 Apache 2.0 开源许可，并做了**中文说明工程化精简**（只留可独立构建的最小集）。

## 工程说明（交接给下一个开发者 / AI）

- **包名 / applicationId**：`com.encourage.app`
- **License**：Apache 2.0（见根目录 `LICENSE`，保留 Google 版权原样）
- **工程名**：`SeAI-Encourage`（见 `settings.gradle.kts`）
- 技术栈 / 构建 / 界面 / 功能清单见下表「文档入口」。

## 快速构建（离线）

```bash
cd D:\AndroidWork\SeAI_Encourage
# 需 JDK 21；若系统未配置 JAVA_HOME：
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1
gradlew.bat assembleRelease --offline
```

产物：`app\build\outputs\apk\release\app-release.apk`

> 约定：本机环境一律 `--offline` 构建；**不要删除** `%USERPROFILE%\.gradle\caches`、
> `%USERPROFILE%\.gradle\wrapper\dists` 等缓存，也不要触发联网全量下载。

## 目录地图

| 路径 | 说明 |
| --- | --- |
| `app/` | Android 单模块（`include(":app")`） |
| `app/src/main/java/com/encourage/app/` | Kotlin 源码 |
| `app/src/main/assets/` | agents / skills / tinygarden / model_allowlist.json 等内置资源 |
| `app/libs/` | sherpa-onnx 本地 AAR |
| `gradle/` | wrapper + `libs.versions.toml`（版本目录） |
| `doc/` | 中文说明文档：00~04=现状/架构/构建/界面/功能状态；`doc/演进设计/`=待开发功能设计基线 |
| `LICENSE` | Apache 2.0 |

## 文档入口

- `doc/00_索引.md`：总索引（技术栈 / 工具 / 文档地图 / AI 协作指南）
- `doc/01_架构说明.md`：代码怎么组织、关键链路、每个模块作用
- `doc/02_构建与工具链.md`：构建命令、离线注意事项、JDK/Gradle/依赖位置
- `doc/03_界面与操作流程.md`：现有哪些功能页面、怎么操作
- `doc/04_功能清单与实现状态.md`：每项能力做没做、做到哪；未满足项怎么补
- `doc/演进设计/`：**待开发功能设计基线**（统一入口方案 + 界面规范），开工前先读

> 顶层不再保留 gallery 仓库的 `skills/`、`mcp/`、`model_allowlists/`、`model_allowlist.json`：
> 它们不被 gradle 构建引用，APK 使用的资源已在 `app/src/main/assets/` 内置副本。详见 `doc/01`。
