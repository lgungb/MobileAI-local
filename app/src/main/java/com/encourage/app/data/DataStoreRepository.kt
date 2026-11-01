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

package com.encourage.app.data

import androidx.datastore.core.DataStore
import com.encourage.app.proto.AccessTokenData
import com.encourage.app.proto.BenchmarkResult
import com.encourage.app.proto.BenchmarkResults
import com.encourage.app.proto.Cutout
import com.encourage.app.proto.CutoutCollection
import com.encourage.app.proto.ImportedModel
import com.encourage.app.proto.Settings
import com.encourage.app.proto.Skill
import com.encourage.app.proto.Skills
import com.encourage.app.proto.Theme
import com.encourage.app.proto.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// TODO(b/423700720): Change to async (suspend) functions
interface DataStoreRepository {
  fun saveTextInputHistory(history: List<String>)

  fun readTextInputHistory(): List<String>

  fun saveTheme(theme: Theme)

  fun readTheme(): Theme

  /**
   * Saves the user's preference for whether Firebase Analytics data collection is enabled (`true`)
   * or disabled (`false`).
   *
   * Note that this preference is stored internally on the Settings proto as
   * `disable_firebase_analytics = !enabled`. This ensures that when the proto field is unset (its
   * default value being `false`), data collection remains enabled by default across new installs
   * and upgrades until explicitly toggled off by the user.
   *
   * @param enabled `true` to enable Firebase Analytics data collection; `false` to disable it.
   */
  fun saveFirebaseAnalytics(enabled: Boolean)

  /**
   * Reads that current setting for whether Firebase Analytics data collection is enabled.
   *
   * @return `true` if analytics is enabled or has not been explicitly disabled by the user; `false`
   *   otherwise.
   */
  fun readFirebaseAnalytics(): Boolean

  fun saveSecret(key: String, value: String)

  fun readSecret(key: String): String?

  fun deleteSecret(key: String)

  fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)

  fun clearAccessTokenData()

  fun readAccessTokenData(): AccessTokenData?

  fun saveImportedModels(importedModels: List<ImportedModel>)

  fun readImportedModels(): List<ImportedModel>

  /**
   * 【P0 数据一致性】原子地移除一个已导入模型。
   *
   * 旧写法是「`readImportedModels()` 取快照 → 改列表 → `saveImportedModels(全量)`」，
   * 在并发窗口内会把别人刚写入的模型一起覆盖掉（用户感知为「模型自己消失了」）。
   * 这里把读取和修改都放进同一个 [DataStore.updateData] 事务里完成。
   *
   * @param fileName 已导入模型的文件名（与 `ImportedModel.fileName` 对应）。
   */
  fun removeImportedModel(fileName: String)

  /**
   * 【P0 数据一致性】原子地写入/替换一个已导入模型（同名先删后加，保持追加在末尾的顺序）。
   *
   * @param fileName 用于判重的文件名。
   * @param importedModel 要写入的新记录。
   */
  fun upsertImportedModel(fileName: String, importedModel: ImportedModel)

  fun isTosAccepted(): Boolean

  fun acceptTos()

  fun isGemmaTermsOfUseAccepted(): Boolean

  fun acceptGemmaTermsOfUse()

  fun getHasRunTinyGarden(): Boolean

  fun setHasRunTinyGarden(hasRun: Boolean)

  fun addCutout(cutout: Cutout)

  fun getAllCutouts(): List<Cutout>

  fun setCutout(newCutout: Cutout)

  fun setCutouts(cutouts: List<Cutout>)

  fun setHasSeenBenchmarkComparisonHelp(seen: Boolean)

  fun getHasSeenBenchmarkComparisonHelp(): Boolean

  fun addBenchmarkResult(result: BenchmarkResult)

  fun getAllBenchmarkResults(): List<BenchmarkResult>

  fun deleteBenchmarkResult(index: Int)

  fun addSkill(skill: Skill)

  fun setSkills(skills: List<Skill>)

  fun setSkillSelected(skill: Skill, selected: Boolean)

  fun setAllSkillsSelected(selected: Boolean)

  fun getAllSkills(): List<Skill>

  /**
   * 【P0 数据一致性】原子地替换一条技能记录。
   *
   * 旧写法「`getAllSkills()` → map → `setSkills(全量)`」会在并发时把同一时刻新加/新勾选的
   * 技能覆盖回去（用户感知为「技能勾选自己变回去了」）。改为在同一个
   * [DataStore.updateData] 事务内完成查找与替换。
   *
   * @param oldName 被替换技能的旧名字；找不到时不做任何修改。
   * @param updatedSkill 替换后的技能记录。
   */
  fun replaceSkill(oldName: String, updatedSkill: Skill)

  fun deleteSkill(name: String)

  suspend fun deleteSkills(names: Set<String>)

  /** Records that a promo with the specified ID has been viewed. */
  fun addViewedPromoId(promoId: String)

  /** Removes a viewed promo record. */
  fun removeViewedPromoId(promoId: String)

  /** Returns whether a promo with the specified ID has been viewed. */
  fun hasViewedPromo(promoId: String): Boolean

  /** Persists the globally-selected default model id (empty string clears it). */
  fun saveDefaultModelId(modelId: String)

  /** Returns the globally-selected default model id, or empty string if none set. */
  fun readDefaultModelId(): String

  /**
   * 【N8】持久化某个模型的默认运行后端（accelerator）。
   * @param modelName Model.name；@param accelerator Accelerator.label（CPU/GPU/NPU/TPU）。
   * accelerator 传空串表示清除该模型的记忆，回退模型自身默认后端。
   */
  fun saveModelAccelerator(modelName: String, accelerator: String)

  /** 【N8】读取某个模型记忆的后端；无记忆时返回空串（走模型自身默认）。 */
  fun readModelAccelerator(modelName: String): String

  /**
   * 【N9】一次性读出「所有模型 → 记忆后端」的映射（modelName → Accelerator.label）。
   *
   * 为什么需要它：模型列表每次刷新都会走 `Model.preProcess()` 把 `configValues` 重置成默认值，
   * 以前只靠初始化模型时逐个读回后端，导致「列表显示默认后端、引擎却在用记忆后端」。
   * 改为在装配模型列表时一次性读取并回填，显示与实际才能始终一致。
   */
  fun readAllModelAccelerators(): Map<String, String>
}

/** Repository for managing data using Proto DataStore. */
class DefaultDataStoreRepository(
  private val dataStore: DataStore<Settings>,
  private val userDataDataStore: DataStore<UserData>,
  private val cutoutDataStore: DataStore<CutoutCollection>,
  private val benchmarkResultsDataStore: DataStore<BenchmarkResults>,
  private val skillsDataStore: DataStore<Skills>,
) : DataStoreRepository {
  override fun saveTextInputHistory(history: List<String>) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().clearTextInputHistory().addAllTextInputHistory(history).build()
      }
    }
  }

  override fun readTextInputHistory(): List<String> {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.textInputHistoryList
    }
  }

  override fun saveTheme(theme: Theme) {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setTheme(theme).build() }
    }
  }

  override fun readTheme(): Theme {
    return runBlocking {
      val settings = dataStore.data.first()
      val curTheme = settings.theme
      // Use "auto" as the default theme.
      if (curTheme == Theme.THEME_UNSPECIFIED) Theme.THEME_AUTO else curTheme
    }
  }

  /**
   * Persists the inverted value (`!enabled`) into `settings.disableFirebaseAnalytics` within proto
   * DataStore.
   */
  override fun saveFirebaseAnalytics(enabled: Boolean) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setDisableFirebaseAnalytics(!enabled).build()
      }
    }
  }

  /**
   * Reads `settings.disableFirebaseAnalytics` from proto DataStore and returns the inverted value
   * so that `false` (default uninitialized value) evaluates to `true` (enabled).
   */
  override fun readFirebaseAnalytics(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      !settings.disableFirebaseAnalytics
    }
  }

  override fun saveSecret(key: String, value: String) {
    runBlocking {
      userDataDataStore.updateData { userData ->
        userData.toBuilder().putSecrets(key, value).build()
      }
    }
  }

  override fun readSecret(key: String): String? {
    return runBlocking { userDataDataStore.data.first().secretsMap[key] }
  }

  override fun deleteSecret(key: String) {
    runBlocking {
      userDataDataStore.updateData { userData -> userData.toBuilder().removeSecrets(key).build() }
    }
  }

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    runBlocking {
      // Clear the entry in old data store.
      dataStore.updateData { settings ->
        settings.toBuilder().setAccessTokenData(AccessTokenData.getDefaultInstance()).build()
      }

      userDataDataStore.updateData { userData ->
        userData
          .toBuilder()
          .setAccessTokenData(
            AccessTokenData.newBuilder()
              .setAccessToken(accessToken)
              .setRefreshToken(refreshToken)
              .setExpiresAtMs(expiresAt)
              .build()
          )
          .build()
      }
    }
  }

  override fun clearAccessTokenData() {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().clearAccessTokenData().build() }
      userDataDataStore.updateData { userData ->
        userData.toBuilder().clearAccessTokenData().build()
      }
    }
  }

  override fun readAccessTokenData(): AccessTokenData? {
    return runBlocking {
      val userData = userDataDataStore.data.first()
      userData.accessTokenData
    }
  }

  override fun saveImportedModels(importedModels: List<ImportedModel>) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().clearImportedModel().addAllImportedModel(importedModels).build()
      }
    }
  }

  override fun readImportedModels(): List<ImportedModel> {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.importedModelList
    }
  }

  override fun removeImportedModel(fileName: String) {
    runBlocking {
      dataStore.updateData { settings ->
        val remaining = settings.importedModelList.filterNot { it.fileName == fileName }
        // 没有命中就不要触发一次无意义的写盘（写盘本身是有成本的）。
        if (remaining.size == settings.importedModelCount) {
          settings
        } else {
          settings.toBuilder().clearImportedModel().addAllImportedModel(remaining).build()
        }
      }
    }
  }

  override fun upsertImportedModel(fileName: String, importedModel: ImportedModel) {
    runBlocking {
      dataStore.updateData { settings ->
        val updated = settings.importedModelList.filterNot { it.fileName == fileName } + importedModel
        settings.toBuilder().clearImportedModel().addAllImportedModel(updated).build()
      }
    }
  }

  override fun isTosAccepted(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.isTosAccepted
    }
  }

  override fun acceptTos() {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setIsTosAccepted(true).build() }
    }
  }

  override fun isGemmaTermsOfUseAccepted(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.isGemmaTermsAccepted
    }
  }

  override fun acceptGemmaTermsOfUse() {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setIsGemmaTermsAccepted(true).build()
      }
    }
  }

  override fun getHasRunTinyGarden(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.hasRunTinyGarden
    }
  }

  override fun setHasRunTinyGarden(hasRun: Boolean) {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setHasRunTinyGarden(hasRun).build() }
    }
  }

  override fun addCutout(cutout: Cutout) {
    runBlocking {
      cutoutDataStore.updateData { cutouts -> cutouts.toBuilder().addCutout(cutout).build() }
    }
  }

  override fun getAllCutouts(): List<Cutout> {
    return runBlocking { cutoutDataStore.data.first().cutoutList }
  }

  override fun setCutout(newCutout: Cutout) {
    runBlocking {
      cutoutDataStore.updateData { cutouts ->
        var index = -1
        for (i in 0..<cutouts.cutoutCount) {
          val cutout = cutouts.cutoutList.get(i)
          if (cutout.id == newCutout.id) {
            index = i
            break
          }
        }
        if (index >= 0) {
          cutouts.toBuilder().setCutout(index, newCutout).build()
        } else {
          cutouts
        }
      }
    }
  }

  override fun setCutouts(cutouts: List<Cutout>) {
    runBlocking {
      cutoutDataStore.updateData { CutoutCollection.newBuilder().addAllCutout(cutouts).build() }
    }
  }

  override fun setHasSeenBenchmarkComparisonHelp(seen: Boolean) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setHasSeenBenchmarkComparisonHelp(seen).build()
      }
    }
  }

  override fun getHasSeenBenchmarkComparisonHelp(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.hasSeenBenchmarkComparisonHelp
    }
  }

  override fun addBenchmarkResult(result: BenchmarkResult) {
    runBlocking {
      benchmarkResultsDataStore.updateData { results ->
        results.toBuilder().addResult(0, result).build()
      }
    }
  }

  override fun getAllBenchmarkResults(): List<BenchmarkResult> {
    return runBlocking { benchmarkResultsDataStore.data.first().resultList }
  }

  override fun deleteBenchmarkResult(index: Int) {
    runBlocking {
      benchmarkResultsDataStore.updateData { results ->
        val newResults = results.toBuilder().removeResult(index).build()
        newResults
      }
    }
  }

  override fun addSkill(skill: Skill) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills = buildList {
          add(skill)
          addAll(skills.skillList)
        }
        skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
      }
    }
  }

  override fun setSkills(skills: List<Skill>) {
    runBlocking {
      skillsDataStore.updateData { curSkills ->
        curSkills.toBuilder().clearSkill().addAllSkill(skills).build()
      }
    }
  }

  override fun setSkillSelected(skill: Skill, selected: Boolean) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills =
          skills.skillList.map { curSkill ->
            if (curSkill.name == skill.name) {
              curSkill.toBuilder().setSelected(selected).setUserModifiedSelection(true).build()
            } else {
              curSkill
            }
          }
        Skills.newBuilder().addAllSkill(newSkills).build()
      }
    }
  }

  override fun setAllSkillsSelected(selected: Boolean) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills =
          skills.skillList.map { curSkill ->
            curSkill.toBuilder().setSelected(selected).setUserModifiedSelection(true).build()
          }
        Skills.newBuilder().addAllSkill(newSkills).build()
      }
    }
  }

  override fun getAllSkills(): List<Skill> {
    return runBlocking { skillsDataStore.data.first().skillList }
  }

  override fun replaceSkill(oldName: String, updatedSkill: Skill) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val current = skills.skillList
        if (current.none { it.name == oldName }) {
          // 没命中就原样返回，避免触发一次无意义的写盘。
          skills
        } else {
          val newSkills = current.map { if (it.name == oldName) updatedSkill else it }
          skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
        }
      }
    }
  }

  override fun deleteSkill(name: String) {
    runBlocking {
      skillsDataStore.updateData { skills ->
        val newSkills = skills.skillList.filter { it.name != name }
        Skills.newBuilder().addAllSkill(newSkills).build()
      }
    }
  }

  override suspend fun deleteSkills(names: Set<String>) {
    skillsDataStore.updateData { skills ->
      val newSkills = skills.skillList.filter { it.name !in names }
      skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
    }
  }

  override fun addViewedPromoId(promoId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        if (settings.viewedPromoIdList.contains(promoId)) {
          settings
        } else {
          settings.toBuilder().addViewedPromoId(promoId).build()
        }
      }
    }
  }

  override fun removeViewedPromoId(promoId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        val newList = settings.viewedPromoIdList.filter { it != promoId }
        settings.toBuilder().clearViewedPromoId().addAllViewedPromoId(newList).build()
      }
    }
  }

  override fun hasViewedPromo(promoId: String): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.viewedPromoIdList.contains(promoId)
    }
  }

  override fun saveDefaultModelId(modelId: String) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().setDefaultModelId(modelId).build()
      }
    }
  }

  override fun readDefaultModelId(): String {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.defaultModelId
    }
  }

  override fun saveModelAccelerator(modelName: String, accelerator: String) {
    runBlocking {
      dataStore.updateData { settings ->
        val newMap = settings.modelAcceleratorsMap.toMutableMap()
        if (accelerator.isEmpty()) {
          newMap.remove(modelName)
        } else {
          newMap[modelName] = accelerator
        }
        settings.toBuilder().putAllModelAccelerators(newMap).build()
      }
    }
  }

  override fun readModelAccelerator(modelName: String): String {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.modelAcceleratorsMap[modelName] ?: ""
    }
  }

  override fun readAllModelAccelerators(): Map<String, String> {
    return runBlocking {
      // 复制成不可变 Map，避免把 DataStore 内部结构泄漏给调用方。
      dataStore.data.first().modelAcceleratorsMap.toMap()
    }
  }
}
