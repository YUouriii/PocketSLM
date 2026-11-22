package com.pocketllm.ui.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.R
import com.pocketllm.ui.components.AppChip
import com.pocketllm.ui.components.ConfirmDialog
import com.pocketllm.ui.components.StatusNotice
import com.pocketllm.ui.components.ThinCard
import com.pocketllm.core.DeviceProfile
import com.pocketllm.core.formatMb
import com.pocketllm.core.formatRam
import com.pocketllm.data.CatalogModel
import com.pocketllm.data.DownloadProgress
import com.pocketllm.data.Fit
import com.pocketllm.data.HfRepo
import com.pocketllm.data.InstalledModel
import com.pocketllm.data.ModelCapabilities
import com.pocketllm.selection.PerformanceCategory
import com.pocketllm.selection.PerformanceRecommendation
import com.pocketllm.ui.theme.IconSize
import com.pocketllm.ui.theme.SectionLabel
import com.pocketllm.ui.theme.Spacing
import com.pocketllm.ui.theme.Starfield

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text(stringResource(R.string.tab_models), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.models_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Starfield(Modifier.matchParentSize())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            item { DeviceCard(ui.device) }

            item {
                FilterTabs(selected = ui.filter, onSelect = vm::setFilter)
            }

            item {
                SearchBox(
                    value = ui.query,
                    onValueChange = vm::setQuery,
                    onClear = vm::clearSearch,
                    onSubmit = vm::search,
                )
            }

            if (ui.searchRan) {
                if (ui.searching) {
                    item { SearchingRow(stringResource(R.string.models_searching)) }
                }
                ui.searchError?.let { message ->
                    item { StatusNotice(title = message, error = true) }
                }
                if (!ui.searching && ui.results.isEmpty() && ui.searchError == null) {
                    item { HintText(stringResource(R.string.models_no_results)) }
                }
                if (ui.results.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.models_section_search), Modifier.padding(start = 4.dp, top = 8.dp)) }
                }
                items(ui.results, key = { "repo:${it.id}" }) { repo ->
                    RepoRow(repo = repo, onClick = { vm.openRepo(repo) })
                }
            } else {
                val installed = ui.installed
                if (ui.filter == ModelFilter.INSTALLED) {
                    if (installed.isEmpty()) {
                        item { EmptyNote(stringResource(R.string.models_installed_empty)) }
                    }
                    items(installed, key = { "installed:${it.id}" }) { model ->
                        InstalledCard(
                            model = model,
                            active = ui.activeId == model.id,
                            downloading = vm.isDownloading(model),
                            onUse = { vm.use(model) },
                            onDelete = { vm.askDelete(model) },
                            performance = ui.performance[model.id],
                        )
                    }
                } else {
                    if (installed.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.models_section_installed), Modifier.padding(start = 4.dp, top = 8.dp)) }
                        items(installed, key = { "installed:${it.id}" }) { model ->
                            InstalledCard(
                                model = model,
                                active = ui.activeId == model.id,
                                onUse = { vm.use(model) },
                                onDelete = { vm.askDelete(model) },
                                performance = ui.performance[model.id],
                            )
                        }
                    }

                    item { SectionLabel(stringResource(R.string.models_section_catalog), Modifier.padding(start = 4.dp, top = 8.dp)) }

                    val catalog = vm.catalogFor(ui.filter)
                    if (catalog.isEmpty()) {
                        item { HintText(stringResource(R.string.models_no_results)) }
                    }
                    items(catalog, key = { "catalog:${it.id}" }) { model ->
                        CatalogCard(
                            model = model,
                            fit = ui.fitOf(model),
                            installed = ui.installed.firstOrNull { it.id == model.id },
                            progress = vm.progressFor(model.id),
                            active = ui.activeId == model.id,
                            onDownload = { vm.downloadCatalog(model) },
                            onUse = { vm.installedFor(model.id, model.file)?.let(vm::use) },
                            onCancel = { vm.cancelDownload(model.id) },
                            performance = ui.performance[model.id],
                        )
                    }
                }
            }
        }
        }
    }

    ui.detail?.let { detail ->
        RepoDialog(
            state = detail,
            onDismiss = vm::closeRepo,
            onPick = { file -> vm.downloadRepoFile(detail.repo, file) },
        )
    }

    ui.deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.models_delete_title),
            body = stringResource(R.string.models_delete_body, target.displayName),
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
            onConfirm = vm::confirmDelete,
            onDismiss = vm::cancelDelete,
            icon = Icons.Rounded.DeleteOutline,
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceProfile) {
    val scheme = MaterialTheme.colorScheme
    ThinCard(shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                SectionLabel(stringResource(R.string.models_device))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.models_free_storage, formatMb(device.freeStorageMb)),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                device.model,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(
                    R.string.models_device_line,
                    formatRam(device.totalRamMb),
                    device.cores,
                    device.abi,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterTabs(selected: ModelFilter, onSelect: (ModelFilter) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            listOf(ModelFilter.RECOMMENDED, ModelFilter.ALL).forEach { filter ->
                AppChip(
                    text = filterLabel(filter),
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    modifier = Modifier.weight(1f),
                    centerText = true,
                )
            }
        }
    }
}

@Composable
private fun filterLabel(filter: ModelFilter): String = when (filter) {
    ModelFilter.RECOMMENDED -> stringResource(R.string.models_filter_recommended)
    ModelFilter.ALL -> stringResource(R.string.models_filter_all)
    ModelFilter.INSTALLED -> stringResource(R.string.models_filter_installed)
}

@Composable
private fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(stringResource(R.string.models_search_hint), style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.Medium),
                )
            },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = scheme.surfaceVariant,
                unfocusedContainerColor = scheme.surfaceVariant,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = scheme.onSurface,
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
                focusedPlaceholderColor = scheme.onSurfaceVariant,
                unfocusedPlaceholderColor = scheme.onSurfaceVariant,
            ),
        )
        if (value.isNotBlank()) {
            TextButton(
                onClick = onSubmit,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.models_search_action))
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun SearchingRow(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(IconSize.Small),
            strokeWidth = 1.5.dp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyNote(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun FitBadge(fit: Fit) {
    val scheme = MaterialTheme.colorScheme
    val label = when (fit) {
        Fit.GOOD -> stringResource(R.string.models_fit_good)
        Fit.TIGHT -> stringResource(R.string.models_fit_tight)
        Fit.TOO_BIG -> stringResource(R.string.models_fit_too_big)
    }
    val filled = fit == Fit.GOOD
    Surface(
        color = if (filled) scheme.primary else Color.Transparent,
        shape = CircleShape,
        border = if (filled) null else BorderStroke(1.dp, scheme.outline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CapabilityRow(capabilities: ModelCapabilities) {
    val scheme = MaterialTheme.colorScheme
    val items = buildList {
        if (capabilities.reasoning) add(stringResource(R.string.capability_reasoning) to Icons.Rounded.Psychology)
        if (capabilities.vision) add(stringResource(R.string.capability_vision) to Icons.Rounded.PhotoCamera)
        if (capabilities.audio) add(stringResource(R.string.capability_audio) to Icons.Rounded.Mic)
        if (capabilities.coding) add(stringResource(R.string.capability_coding) to Icons.Rounded.Code)
    }
    if (items.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (label, icon) ->
            Surface(
                color = scheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivePill() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(IconSize.Small),
            )
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.models_active), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CatalogCard(
    model: CatalogModel,
    fit: Fit,
    installed: InstalledModel?,
    progress: DownloadProgress?,
    active: Boolean,
    onDownload: () -> Unit,
    onUse: () -> Unit,
    onCancel: () -> Unit,
    performance: PerformanceRecommendation? = null,
) {
    val scheme = MaterialTheme.colorScheme
    ThinCard(shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${model.publisher} · ${model.params} · ${model.quant} · " +
                            stringResource(com.pocketllm.data.Catalog.languageLabelRes(model.id)),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FitBadge(fit)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            CapabilityRow(model.capabilities)
            if (performance != null) PerformanceLine(performance)

            Spacer(Modifier.height(14.dp))

            when {
                progress != null && progress.status != DownloadProgress.Status.DONE -> {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = scheme.onSurface,
                        trackColor = scheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (progress.status == DownloadProgress.Status.FAILED) {
                                stringResource(R.string.models_download_failed)
                            } else {
                                stringResource(
                                    R.string.models_downloading,
                                    bytesLabel(progress.bytes),
                                    bytesLabel(progress.total),
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.models_cancel))
                        }
                    }
                }

                installed != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatMb(installed.sizeMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            ActivePill()
                        } else {
                            Button(onClick = onUse, shape = CircleShape) {
                                Text(stringResource(R.string.models_use))
                            }
                        }
                    }
                }

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatMb(model.sizeMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = onDownload,
                            enabled = fit != Fit.TOO_BIG,
                            shape = CircleShape,
                        ) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.models_download))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.models_license, model.license),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun performanceCategoryLabel(category: PerformanceCategory): String = when (category) {
    PerformanceCategory.SMOOTH -> stringResource(R.string.perf_smooth)
    PerformanceCategory.USABLE -> stringResource(R.string.perf_usable)
    PerformanceCategory.SLOW -> stringResource(R.string.perf_slow)
    PerformanceCategory.UNKNOWN -> stringResource(R.string.perf_unknown)
}

@Composable
private fun PerformanceLine(recommendation: PerformanceRecommendation) {
    val scheme = MaterialTheme.colorScheme
    if (recommendation.category == PerformanceCategory.UNKNOWN) {
        Text(
            stringResource(R.string.perf_unknown),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        return
    }
    val parts = mutableListOf<String>()
    recommendation.tokensPerSecond?.let { parts += "${(it * 10).toLong() / 10.0} tok/s" }
    parts += performanceCategoryLabel(recommendation.category)
    recommendation.ttftMs?.let { parts += "TTFT ${it} ms" }
    recommendation.memoryBytes?.let {
        parts += "${formatMb((it / (1024 * 1024)).coerceAtMost(Long.MAX_VALUE))} " +
            stringResource(R.string.perf_ram)
    }
    parts += stringResource(
        if (recommendation.isMeasured) R.string.perf_measured else R.string.perf_estimated,
    )
    if (recommendation.approximate) parts += stringResource(R.string.perf_approx)
    parts += stringResource(
        when (recommendation.confidence) {
            com.pocketllm.selection.Confidence.HIGH -> R.string.perf_confidence_high
            com.pocketllm.selection.Confidence.MEDIUM -> R.string.perf_confidence_medium
            com.pocketllm.selection.Confidence.LOW -> R.string.perf_confidence_low
        },
    )
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun InstalledCard(
    model: InstalledModel,
    active: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    downloading: Boolean = false,
    performance: PerformanceRecommendation? = null,
) {
    val scheme = MaterialTheme.colorScheme
    ThinCard(shape = RoundedCornerShape(20.dp), padding = null) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        model.params.takeIf { it.isNotBlank() },
                        model.quant,
                        formatMb(model.sizeMb),
                        stringResource(com.pocketllm.data.Catalog.languageLabelRes(model.id)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                if (performance != null) PerformanceLine(performance)
                run {
                    val caps = com.pocketllm.data.Catalog.capabilitiesOf(model.id)
                    if (caps.any) {
                        Spacer(Modifier.height(6.dp))
                        CapabilityRow(caps)
                    }
                }
            }
            if (downloading) {
                Text(
                    stringResource(R.string.models_downloading_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            } else if (active) {
                ActivePill()
            } else {
                Button(onClick = onUse, shape = CircleShape) {
                    Text(stringResource(R.string.models_use))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.models_delete),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.Medium),
                )
            }
        }
    }
}

@Composable
private fun RepoRow(repo: HfRepo, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    repo.id,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("♥ ${repo.likes}  ↓ ${repo.downloads}")
                        repo.license?.let { append("  ·  $it") }
                        if (repo.gated) append("  ·  gated")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.Medium),
            )
        }
    }
}

@Composable
private fun RepoDialog(
    state: RepoDetailState,
    onDismiss: () -> Unit,
    onPick: (com.pocketllm.data.HfFile) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(state.repo.id, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                when {
                    state.loading -> Text(stringResource(R.string.models_preparing))
                    state.error != null -> Text(state.error, color = scheme.error)
                    state.files.isEmpty() -> Text(stringResource(R.string.models_no_files))
                    else -> {
                        if (state.repo.gated) {
                            Text(
                                stringResource(R.string.models_gated),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        state.files.forEach { file ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(file.name, style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        bytesLabel(file.sizeMb * 1024 * 1024),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { onPick(file) }) {
                                        Text(stringResource(R.string.models_download))
                                    }
                                }
                                HorizontalDivider(color = scheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
