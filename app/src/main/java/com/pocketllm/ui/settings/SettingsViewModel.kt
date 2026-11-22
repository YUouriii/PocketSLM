package com.pocketllm.ui.settings

import androidx.lifecycle.ViewModel
import com.pocketllm.Graph
import com.pocketllm.core.DeviceProfile
import com.pocketllm.data.Catalog
import com.pocketllm.data.InstalledModel
import com.pocketllm.data.ModelCapabilities
import com.pocketllm.data.Settings
import com.pocketllm.data.SettingsState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

class SettingsViewModel : ViewModel() {

    private val settings: Settings = Graph.settings

    val state: StateFlow<SettingsState> = settings.state

    val device: DeviceProfile = Graph.device

    val activeModel: StateFlow<InstalledModel?> =
        combine(settings.state, Graph.models.installed) { s, installed ->
            installed.firstOrNull { it.id == s.activeModelId }
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = Graph.models.installed.value.firstOrNull { it.id == settings.current.activeModelId },
        )

    val activeCapabilities: StateFlow<ModelCapabilities> =
        combine(settings.state, Graph.models.installed) { s, installed ->
            val active = installed.firstOrNull { it.id == s.activeModelId }
            Catalog.capabilitiesOf(active?.id)
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = Catalog.capabilitiesOf(
                Graph.models.installed.value.firstOrNull { it.id == settings.current.activeModelId }?.id,
            ),
        )

    fun update(transform: (SettingsState) -> SettingsState) = settings.update(transform)
}

