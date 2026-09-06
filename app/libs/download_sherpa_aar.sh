#!/usr/bin/env bash
# =============================================================================
# Sherpa-ONNX 离线 TTS 本地 AAR 下载脚本（国内环境首选）
#
# 背景：sherpa-onnx 只发布在 JitPack（不在 Maven Central），JitPack 在国内访问
# 不稳定。为满足「构建不依赖外部网络」的国内诉求，本工程默认使用本地 AAR：
#   app/libs/sherpa-onnx.aar
# app/build.gradle.kts 检测到该文件存在时即用本地 AAR，不再从 JitPack 拉取。
#
# 用法（在能访问 jitpack.io 的机器上执行一次）：
#   bash app/libs/download_sherpa_aar.sh
# 产物：app/libs/sherpa-onnx.aar（约 20~30MB，含 arm64-v8a 的 sherpa-onnx-jni.so）
#
# 若连 jitpack.io 也不可达（极端情况），可任选其一：
#   1. 用能访问外网的机器执行本脚本，再把 AAR 拷回本项目；
#   2. 从镜像源下载同名 AAR 放到 app/libs/ 并命名为 sherpa-onnx.aar。
# =============================================================================

set -euo pipefail

VERSION="v1.13.4"
# JitPack 现成 AAR（坐标 com.github.k2-fsa:sherpa-onnx，v 前缀对应 git tag）
AAR_URL="https://jitpack.io/com/github/k2-fsa/sherpa-onnx/${VERSION}/sherpa-onnx-${VERSION}.aar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS_DIR="$SCRIPT_DIR"

command -v curl >/dev/null || { echo "缺少 curl"; exit 1; }

echo "==> 下载 sherpa-onnx ${VERSION} 的 Android AAR"
echo "    $AAR_URL"
curl -fL --retry 3 -o "$LIBS_DIR/sherpa-onnx.aar" "$AAR_URL"

echo "==> 完成：$LIBS_DIR/sherpa-onnx.aar"
ls -lh "$LIBS_DIR/sherpa-onnx.aar"
echo ""
echo "AAR 就位后，直接 ./gradlew assembleRelease 即可（构建不再访问 JitPack）。"
