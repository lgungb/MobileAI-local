/*
 * Encourage — Agent 管理器 ViewModel（M5 / 需求 F1）
 */

package com.encourage.app.customtasks.agentchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.encourage.app.data.agents.AgentDefinition
import com.encourage.app.data.agents.AgentEntry
import com.encourage.app.data.agents.AgentFileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AgentManagerViewModel
@Inject
constructor(private val repository: AgentFileRepository) : ViewModel() {

  val agents: StateFlow<List<AgentEntry>> =
    repository.agents.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy.asStateFlow()

  /** 一次性提示消息（导入失败等），消费后置空。 */
  private val _notice = MutableStateFlow<String?>(null)
  val notice: StateFlow<String?> = _notice.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch { repository.refresh() }
  }

  fun newId(): String = repository.newId()

  fun userAgentsDir(): String = repository.userAgentsDir.absolutePath

  fun save(definition: AgentDefinition, onSaved: (AgentEntry) -> Unit = {}) {
    viewModelScope.launch {
      _busy.value = true
      val entry = repository.save(definition)
      _busy.value = false
      if (entry != null) {
        onSaved(entry)
      } else {
        _notice.value = "保存失败"
      }
    }
  }

  fun delete(id: String) {
    viewModelScope.launch {
      _busy.value = true
      val ok = repository.delete(id)
      _busy.value = false
      if (!ok) {
        _notice.value = "删除失败"
      }
    }
  }

  fun importFromUrl(url: String, onImported: (AgentEntry) -> Unit = {}) {
    viewModelScope.launch {
      _busy.value = true
      val entry = repository.importFromUrl(url) { message -> _notice.value = message }
      _busy.value = false
      if (entry != null) {
        onImported(entry)
      }
    }
  }

  fun duplicate(entry: AgentEntry, onSaved: (AgentEntry) -> Unit = {}) {
    viewModelScope.launch {
      _busy.value = true
      val newEntry = repository.duplicate(entry)
      _busy.value = false
      if (newEntry != null) {
        onSaved(newEntry)
      } else {
        _notice.value = "复制失败"
      }
    }
  }

  fun consumeNotice() {
    _notice.value = null
  }
}
