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

package com.encourage.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.encourage.app.AppLifecycleProvider
import com.encourage.app.BenchmarkResultsSerializer
import com.encourage.app.BuildConfig
import com.encourage.app.CutoutsSerializer
import com.encourage.app.GalleryLifecycleProvider
import com.encourage.app.SettingsSerializer
import com.encourage.app.SkillsSerializer
import com.encourage.app.UserDataSerializer
import com.encourage.app.data.DataStoreRepository
import com.encourage.app.data.DefaultDataStoreRepository
import com.encourage.app.data.DefaultDownloadRepository
import com.encourage.app.data.DownloadRepository
import com.encourage.app.data.backupReplaceCorruptionHandler
import com.encourage.app.data.conversation.ConversationProfileRepository
import com.encourage.app.proto.BenchmarkResults
import com.encourage.app.proto.CutoutCollection
import com.encourage.app.proto.Settings
import com.encourage.app.proto.Skills
import com.encourage.app.proto.UserData
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

  // Provides the SettingsSerializer
  @Provides
  @Singleton
  fun provideSettingsSerializer(): Serializer<Settings> {
    return SettingsSerializer
  }

  // Provides the CutoutSerializer
  @Provides
  @Singleton
  fun provideCutoutSerializer(): Serializer<CutoutCollection> {
    return CutoutsSerializer
  }

  // Provides the UserDataSerializer
  @Provides
  @Singleton
  fun provideUserDataSerializer(): Serializer<UserData> {
    return UserDataSerializer
  }

  // Provides the BenchmarkResultsSerializer
  @Provides
  @Singleton
  fun provideBenchmarkResultsSerializer(): Serializer<BenchmarkResults> {
    return BenchmarkResultsSerializer
  }

  // Provides the SkillsSerializer
  @Provides
  @Singleton
  fun provideSkillsSerializer(): Serializer<Skills> {
    return SkillsSerializer
  }

  // Provides DataStore<Settings>
  @Provides
  @Singleton
  fun provideSettingsDataStore(
    @ApplicationContext context: Context,
    settingsSerializer: Serializer<Settings>,
  ): DataStore<Settings> {
    return DataStoreFactory.create(
      serializer = settingsSerializer,
      // 【P0】损坏兜底：备份坏文件 + 回落到默认设置，避免开屏即崩。
      corruptionHandler =
        backupReplaceCorruptionHandler(context, "settings.pb") { Settings.getDefaultInstance() },
      produceFile = { context.dataStoreFile("settings.pb") },
    )
  }

  // Provides DataStore<CutoutCollection>
  @Provides
  @Singleton
  fun provideCutoutsDataStore(
    @ApplicationContext context: Context,
    cutoutsSerializer: Serializer<CutoutCollection>,
  ): DataStore<CutoutCollection> {
    return DataStoreFactory.create(
      serializer = cutoutsSerializer,
      // 【P0】损坏兜底：抠图集合损坏时回落到空集合。
      corruptionHandler =
        backupReplaceCorruptionHandler(context, "cutouts.pb") {
          CutoutCollection.getDefaultInstance()
        },
      produceFile = { context.dataStoreFile("cutouts.pb") },
    )
  }

  // Provides DataStore<UserData>
  @Provides
  @Singleton
  fun provideUserDataDataStore(
    @ApplicationContext context: Context,
    userDataSerializer: Serializer<UserData>,
  ): DataStore<UserData> {
    return DataStoreFactory.create(
      serializer = userDataSerializer,
      // 【P0】损坏兜底：user_data.pb 装着全部会话与设置，损坏时先备份再回落默认值，
      // 保证 App 能启动、用户还能取回损坏前的文件。
      corruptionHandler =
        backupReplaceCorruptionHandler(context, "user_data.pb") { UserData.getDefaultInstance() },
      produceFile = { context.dataStoreFile("user_data.pb") },
    )
  }

  // Provides DataStore<BenchmarkResults>
  @Provides
  @Singleton
  fun provideBenchmarkResultsDataStore(
    @ApplicationContext context: Context,
    benchmarkResultsSerializer: Serializer<BenchmarkResults>,
  ): DataStore<BenchmarkResults> {
    return DataStoreFactory.create(
      serializer = benchmarkResultsSerializer,
      // 【P0】损坏兜底：跑分记录损坏时回落到空结果（可重跑）。
      corruptionHandler =
        backupReplaceCorruptionHandler(context, "benchmark_results.pb") {
          BenchmarkResults.getDefaultInstance()
        },
      produceFile = { context.dataStoreFile("benchmark_results.pb") },
    )
  }

  // Provides DataStore<Skills>
  @Provides
  @Singleton
  fun provideSkillsDataStore(
    @ApplicationContext context: Context,
    skillsSerializer: Serializer<Skills>,
  ): DataStore<Skills> {
    return DataStoreFactory.create(
      serializer = skillsSerializer,
      // 【P0】损坏兜底：技能清单损坏时先备份，再回落到默认实例。
      corruptionHandler =
        backupReplaceCorruptionHandler(context, "skills.pb") { Skills.getDefaultInstance() },
      produceFile = { context.dataStoreFile("skills.pb") },
    )
  }

  // Provides AppLifecycleProvider
  @Provides
  @Singleton
  fun provideAppLifecycleProvider(): AppLifecycleProvider {
    return GalleryLifecycleProvider()
  }

  // Provides DataStoreRepository
  @Provides
  @Singleton
  fun provideDataStoreRepository(
    dataStore: DataStore<Settings>,
    userDataDataStore: DataStore<UserData>,
    cutoutsDataStore: DataStore<CutoutCollection>,
    benchmarkResultsStore: DataStore<BenchmarkResults>,
    skillsDataStore: DataStore<Skills>,
  ): DataStoreRepository {
    return DefaultDataStoreRepository(
      dataStore,
      userDataDataStore,
      cutoutsDataStore,
      benchmarkResultsStore,
      skillsDataStore,
    )
  }

  // Provides DownloadRepository
  @Provides
  @Singleton
  fun provideDownloadRepository(
    @ApplicationContext context: Context,
    lifecycleProvider: AppLifecycleProvider,
  ): DownloadRepository {
    return DefaultDownloadRepository(context, lifecycleProvider)
  }

  // Provides ConversationProfileRepository（会话列表改造 T01：复用 UserData DataStore，方案 A）
  @Provides
  @Singleton
  fun provideConversationProfileRepository(
    @ApplicationContext context: Context,
    userDataDataStore: DataStore<UserData>,
  ): ConversationProfileRepository {
    return ConversationProfileRepository(userDataDataStore, context)
  }

  @Provides
  @Singleton
  fun provideMoshi(): Moshi {
    return Moshi.Builder().build()
  }
}
