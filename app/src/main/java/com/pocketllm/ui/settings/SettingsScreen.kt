package com.pocketllm.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.Graph
import com.pocketllm.R
import com.pocketllm.ui.components.AppChip
import com.pocketllm.ui.components.SecondaryAction
import com.pocketllm.ui.components.StatusNotice
import com.pocketllm.ui.components.ThinCard
import com.pocketllm.core.formatMb
import com.pocketllm.core.formatRam
import com.pocketllm.data.SettingsState
import com.pocketllm.data.ThinkingLevel
import com.pocketllm.ui.theme.SectionLabel
import com.pocketllm.ui.theme.Starfield
import java.util.Locale
import kotlin.math.roundToInt

private val GPU_LAYER_CHOICES = listOf(0, 8, 16, 32, 64, 100)

@Composable
private fun themeLabel(theme: String): String = when (theme) {
    SettingsState.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
    SettingsState.THEME_DARK -> stringResource(R.string.settings_theme_dark)
    else -> stringResource(R.string.settings_theme_system)
}

@Composable
private fun minReplyLabel(value: Int): String =
    if (value == 0) stringResource(R.string.settings_min_reply_off)
    else stringResource(R.string.settings_min_reply_n, value)

@Composable
private fun performancePreferenceLabel(value: String): String = when (value) {
    "balanced" -> stringResource(R.string.settings_perf_balanced)
    "show_all" -> stringResource(R.string.settings_perf_all)
    else -> stringResource(R.string.settings_perf_recommended)
}

@Composable
private fun memoryModeLabel(value: String): String = when (value) {
    "balanced" -> stringResource(R.string.settings_mem_balanced)
    "prioritize" -> stringResource(R.string.settings_mem_prioritize)
    else -> stringResource(R.string.settings_mem_preserve)
}

@Composable
private fun ThinkingLevel.description(): String = when (this) {
    ThinkingLevel.OFF -> stringResource(R.string.thinking_off_desc)
    ThinkingLevel.LOW -> stringResource(R.string.thinking_low_desc)
    ThinkingLevel.MEDIUM -> stringResource(R.string.thinking_medium_desc)
    ThinkingLevel.HIGH -> stringResource(R.string.thinking_high_desc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val device = vm.device
    val caps by vm.activeCapabilities.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.settings_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Starfield(Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
            SettingsSection(stringResource(R.string.settings_generation)) {
                SettingLabel(stringResource(R.string.settings_system_prompt))
                OutlinedTextField(
                    value = ui.systemPrompt,
                    onValueChange = { text -> vm.update { it.copy(systemPrompt = text) } },
                    placeholder = { Text(stringResource(R.string.settings_system_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )

                if (caps.reasoning) {
                    SettingLabel(stringResource(R.string.settings_thinking))
                    ChoiceRow(
                        options = SettingsState.THINKING_LEVELS.map { it to it.label },
                        selected = ui.thinkingLevel,
                        onSelect = { level -> vm.update { it.copy(thinkingLevel = level) } },
                    )
                    HelperText(ui.thinkingLevel.description())
                } else {
                    HelperText(stringResource(R.string.settings_thinking_unavailable))
                }

                SliderRow(
                    label = stringResource(R.string.settings_threads),
                    value = ui.threads.toFloat(),
                    valueText = "${ui.threads}",
                    range = 1f..device.cores.toFloat().coerceAtLeast(2f),
                    steps = (device.cores - 2).coerceAtLeast(0),
                    onChange = { v -> vm.update { it.copy(threads = v.roundToInt()) } },
                    help = stringResource(R.string.settings_threads_help, device.suggestedThreads()),
                )
                if (ui.threads != device.suggestedThreads()) {
                    SecondaryAction(
                        text = stringResource(
                            R.string.settings_use_recommended_threads,
                            device.suggestedThreads(),
                        ),
                        onClick = {
                            vm.update { it.copy(threads = device.suggestedThreads()) }
                        },
                    )
                }

                val kvNames = SettingsState.KV_CHOICES.associateWith { kv ->
                    when (kv) {
                        SettingsState.KV_AUTO -> stringResource(R.string.kv_auto)
                        SettingsState.KV_Q8_0 -> stringResource(R.string.kv_q8_0)
                        SettingsState.KV_Q4_0 -> stringResource(R.string.kv_q4_0)
                        else -> stringResource(R.string.kv_f16)
                    }
                }
                SettingLabel(stringResource(R.string.settings_kv_precision))
                ChoiceRow(
                    options = kvNames.toList(),
                    selected = ui.kvQuant,
                    onSelect = { v -> vm.update { it.copy(kvQuant = v) } },
                )
                HelperText(stringResource(R.string.settings_kv_precision_help))

                val probe by Graph.vulkanProbe.collectAsStateWithLifecycle()
                SettingLabel(stringResource(R.string.settings_gpu_layers))
                if (probe.compatible) {
                    ChoiceRow(
                        options = GPU_LAYER_CHOICES.map { it to "$it" },
                        selected = ui.gpuLayers,
                        onSelect = { v -> vm.update { it.copy(gpuLayers = v) } },
                    )
                    HelperText(stringResource(R.string.settings_gpu_available, probe.device, probe.api))
                } else {
                    val detail = probe.missing.ifBlank { probe.reason.ifBlank { "unknown" } }
                    StatusNotice(
                        title = stringResource(R.string.settings_gpu_unavailable),
                        detail = stringResource(
                            R.string.settings_gpu_unavailable_detail,
                            probe.device,
                            detail,
                        ),
                    )
                }

                SettingLabel(stringResource(R.string.settings_context))
                ChoiceRow(
                    options = SettingsState.CONTEXT_CHOICES.map { it to "$it" },
                    selected = ui.contextSize,
                    onSelect = { size -> vm.update { it.copy(contextSize = size) } },
                )
                HelperText(stringResource(R.string.settings_context_help))

                SliderRow(
                    label = stringResource(R.string.settings_temperature),
                    value = ui.temperature,
                    valueText = String.format(Locale.US, "%.2f", ui.temperature),
                    range = 0f..1.5f,
                    onChange = { v -> vm.update { it.copy(temperature = v) } },
                )

                SliderRow(
                    label = stringResource(R.string.settings_top_p),
                    value = ui.topP,
                    valueText = String.format(Locale.US, "%.2f", ui.topP),
                    range = 0.1f..1f,
                    onChange = { v -> vm.update { it.copy(topP = v) } },
                )

                SliderRow(
                    label = stringResource(R.string.settings_max_tokens),
                    value = ui.maxTokens.toFloat(),
                    valueText = "${ui.maxTokens}",
                    range = 64f..2048f,
                    onChange = { v -> vm.update { it.copy(maxTokens = (v / 32).roundToInt() * 32) } },
                )

                SettingLabel(stringResource(R.string.settings_min_reply))
                ChoiceRow(
                    options = SettingsState.MIN_REPLY_CHOICES.map { it to minReplyLabel(it) },
                    selected = ui.minReplyTokens,
                    onSelect = { value -> vm.update { it.copy(minReplyTokens = value) } },
                )
                HelperText(stringResource(R.string.settings_min_reply_help))

                HelperText(stringResource(R.string.settings_apply))
            }

            SettingsSection(stringResource(R.string.settings_appearance)) {
                SettingLabel(stringResource(R.string.settings_theme))
                ChoiceRow(
                    options = SettingsState.THEMES.map { it to themeLabel(it) },
                    selected = ui.theme,
                    onSelect = { theme -> vm.update { it.copy(theme = theme) } },
                )
                HelperText(stringResource(R.string.settings_theme_help))
            }

            SettingsSection(stringResource(R.string.settings_interface)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_keep_screen_on),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = ui.keepScreenOn,
                        onCheckedChange = { checked -> vm.update { it.copy(keepScreenOn = checked) } },
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_performance_memory)) {
                SettingLabel(stringResource(R.string.settings_performance_pref))
                ChoiceRow(
                    options = listOf(
                        "recommended" to performancePreferenceLabel("recommended"),
                        "balanced" to performancePreferenceLabel("balanced"),
                        "show_all" to performancePreferenceLabel("show_all"),
                    ),
                    selected = ui.performancePreference,
                    onSelect = { value -> vm.update { it.copy(performancePreference = value) } },
                )
                HelperText(stringResource(R.string.settings_perf_help))

                SettingLabel(stringResource(R.string.settings_memory_mode))
                ChoiceRow(
                    options = listOf(
                        "preserve" to memoryModeLabel("preserve"),
                        "balanced" to memoryModeLabel("balanced"),
                        "prioritize" to memoryModeLabel("prioritize"),
                    ),
                    selected = ui.memoryMode,
                    onSelect = { value -> vm.update { it.copy(memoryMode = value) } },
                )
                HelperText(stringResource(R.string.settings_memory_help))
            }

            SettingsSection(stringResource(R.string.settings_access)) {
                SettingLabel(stringResource(R.string.settings_token))
                OutlinedTextField(
                    value = ui.hfToken,
                    onValueChange = { text -> vm.update { it.copy(hfToken = text.trim()) } },
                    placeholder = { Text(stringResource(R.string.settings_token_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )
                HelperText(stringResource(R.string.settings_token_help))
            }

            SettingsSection(stringResource(R.string.settings_about)) {
                Text(
                    device.model,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${formatRam(device.totalRamMb)} RAM · ${device.cores} cores · ${device.abi}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    "API ${device.sdkInt} · ${formatMb(device.freeStorageMb)} free",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            Text(
                "PocketSLM · ${stringResource(R.string.settings_footer)}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    // Match the quiet filled fields used by the composer and model search.
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.onSurface,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        ThinCard(shape = RoundedCornerShape(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            AppChip(
                text = label,
                selected = value == selected,
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    help: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = scheme.onSurface,
                activeTrackColor = scheme.onSurface,
                inactiveTrackColor = scheme.surfaceVariant,
                activeTickColor = scheme.onSurface,
                inactiveTickColor = scheme.outline,
            ),
        )
        if (help != null) {
            HelperText(help)
        }
    }
}
