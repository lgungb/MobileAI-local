# SeAI_Encourage（Encourage Android 迁移精简版）

本项目是原「Super Encourage me/gallery」Android 工程的**迁移精简版**：只保留可离线独立构建的最小工程，去掉 `Android/`、`Android/src/` 两层目录壳与全部构建缓存，并补齐中文说明文档。

- **包名 / applicationId**：`com.encourage.app`（未改动）
- **License**：Apache 2.0（见根目录 `LICENSE`，保留 Google 版权原样）
- **工程名**：`SeAI-Encourage`（见 `settings.gradle.kts`）

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
| `doc/` | 中文说明文档（架构、构建、界面） |
| `LICENSE` | Apache 2.0 |

## 文档入口

- `doc/00_索引.md`：总索引（技术栈 / 工具 / 文档地图 / AI 协作指南）
- `doc/01_架构说明.md`：代码怎么组织、关键链路、每个模块作用
- `doc/02_构建与工具链.md`：构建命令、离线注意事项、JDK/Gradle/依赖位置
- `doc/03_界面与操作流程.md`：现有哪些功能页面、怎么操作

> 顶层不再保留 gallery 仓库的 `skills/`、`mcp/`、`model_allowlists/`、`model_allowlist.json`：
> 它们不被 gradle 构建引用，APK 使用的资源已在 `app/src/main/assets/` 内置副本。详见 `doc/01`。
