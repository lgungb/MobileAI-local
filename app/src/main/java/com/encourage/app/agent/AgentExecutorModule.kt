/*
 * Copyright 2026 Google LLC
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

package com.encourage.app.agent

import com.encourage.app.data.tasks.TaskScheduler
import com.encourage.app.data.tasks.TimedTaskRepository
import com.encourage.app.skills.NoOpSkillsProvider
import com.encourage.app.tools.RuntimeToolDispatcher
import com.encourage.app.tools.RuntimeToolsProvider
import com.encourage.app.tools.TimedTaskTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AgentExecutorModule {
  @Provides
  @Singleton
  @AiChatExecutor
  fun provideAiChatExecutor(
    repository: TimedTaskRepository,
    scheduler: TaskScheduler,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider =
        RuntimeToolsProvider(
          initialTools = listOf(TimedTaskTool(repository = repository, scheduler = scheduler)),
        ),
      toolDispatcher = RuntimeToolDispatcher(),
    )
  }

  @Provides
  @Singleton
  fun provideDefaultExecutor(@AiChatExecutor executor: AgentRuntimeExecutor): AgentRuntimeExecutor {
    return executor
  }
}
