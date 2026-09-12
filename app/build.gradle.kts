/*
 * Copyright 2025 Google LLC
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

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  // alias(libs.plugins.oss.licenses)  // disabled: incompatible with AGP 8.13
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

android {
  namespace = "com.encourage.app"
  /*
   * Encourage（N6-A）：compileSdk 由 37（Android 17）下调为 36（Android 16）。
   *
   * 理由：
   * 1. 用户真机为 Android 16 / API 36。用高于设备 API 的 37 编译时，凡是 API 37
   *    新增的符号都能「编译通过」，但一跑到 API 36 的真机上就是 NoSuchMethodError，
   *    这类崩溃在编译期完全不可见，排查成本极高。
   * 2. 与设备 API 对齐后，编译期可见性 == 运行期可见性，任何越界调用会直接报编译错误，
   *    把「真机崩溃」提前成「构建失败」，最稳。
   * 3. 本机已安装 android-36 平台（见 $ANDROID_HOME/platforms），下调不需要联网下载，
   *    满足离线构建要求。
   *
   * 注意：AGP 8.13 的 compileSdk 新 DSL —— compileSdk { version = release(N) }；
   * N 为稳定 API 时不能带 minorApiLevel（那是给预览版用的），因此这里去掉了
   * 原先 release(37) { minorApiLevel = 0 } 的 lambda。
   */
  compileSdk { this.version = release(36) }

  defaultConfig {
    applicationId = "com.encourage.app"
    minSdk = 31
    /*
     * Encourage（N6-A）：targetSdk 由 37 下调为 36，与 compileSdk / 用户真机（API 36）对齐。
     * - targetSdk 37 > 设备 API 36 时，系统会按设备 API（36）的行为规则运行本应用，
     *   本身不至于崩溃，但会导致「编译期认定的行为」与「真机实际行为」出现偏差；
     * - 对齐后所有 Android 16 的行为变更以「已适配」的方式生效，语义明确、可预期。
     * 若后续需要上架 Google Play，按其当年的 targetSdk 要求再统一上调即可。
     */
    targetSdk = 36
    versionCode = 42
    versionName = "1.0.20"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.encourage.app.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    buildConfigField("String", "FEEDBACK_API_KEY", "\"\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Only package arm64-v8a (all Android 12+ devices are 64-bit ARM).
    // This reduces APK size from ~128MB to ~45MB.
    ndk {
      abiFilters += "arm64-v8a"
    }
  }

  // Force extract native libs to filesystem so LiteRT NPU backend can find them
  // via nativeLibraryDir. AGP 8+ defaults to false (libs stay in APK).
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }

  // Force kotlinx-coroutines 1.9.0 to match litertlm 0.16.0 dependency.
  // Compose BOM may pull an older version causing NoSuchMethodError: SendChannel.close$default
  configurations.all {
    resolutionStrategy {
      force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
      force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  // 显式声明：原由 Firebase 传递引入，去谷歌化后需直接依赖（SkillManager 使用）。
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  // implementation(libs.play.services.oss.licenses)  // disabled with plugin
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.cors)

  // 语音包下载解压（.tar.bz2 → bzip2 解压）。
  implementation(libs.commons.compress)

  // ===== Sherpa-ONNX 离线 TTS（M3-6）=====
  // 国内构建策略：直接使用 app/libs/sherpa-onnx-1.13.4.aar（本地、构建完全不依赖
  // 外部网络，最适合国内环境）。该 AAR 来自官方 GitHub Releases，含 arm64-v8a
  // 的 libsherpa-onnx-jni.so 等 native 库；配合上方 ndk { abiFilters = arm64-v8a }
  // 只打包 arm64 的 so，避免 APK 膨胀。
  // 注：同目录 sherpa-onnx-1.13.4-rknn.aar 是瑞芯微 NPU 专用版（带 librknnrt.so），
  // 仅适用于 Rockchip 平台；本机为高通骁龙（SM8750），必须用标准版。
  implementation(files("libs/sherpa-onnx-1.13.4.aar"))
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        create("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}
