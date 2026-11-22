package com.pocketllm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketllm.R
import com.pocketllm.ui.chat.ChatScreen
import com.pocketllm.ui.models.ModelsScreen
import com.pocketllm.ui.settings.SettingsScreen
import com.pocketllm.ui.theme.IconSize

private enum class Tab { CHAT, MODELS, SETTINGS }

@Composable
fun PocketLlmRoot(initialTab: String? = null) {
    var tab by rememberSaveable {
        mutableStateOf(
            when (initialTab) {
                "models" -> Tab.MODELS
                "settings" -> Tab.SETTINGS
                else -> Tab.CHAT
            },
        )
    }
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // Drives the nav bar slide: it steps aside while the software keyboard is up.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(
            color = scheme.background,
            contentColor = scheme.onBackground,
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    Tab.CHAT -> ChatScreen()
                    Tab.MODELS -> ModelsScreen()
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }
        AnimatedVisibility(
            visible = !imeVisible,
            enter = slideInVertically(initialOffsetY = { it }) +
                expandVertically(expandFrom = Alignment.Bottom),
            exit = slideOutVertically(targetOffsetY = { it }) +
                shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column {
                HorizontalDivider(color = scheme.outlineVariant)
                NavigationBar(
                    containerColor = scheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = tab == Tab.CHAT,
                        onClick = { tab = Tab.CHAT },
                        colors = navColors(),
                        icon = {
                            Icon(
                                Icons.Rounded.ChatBubble,
                                contentDescription = null,
                                modifier = Modifier.size(IconSize.Nav),
                            )
                        },
                        label = { Text(stringResource(R.string.tab_chat)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.MODELS,
                        onClick = { tab = Tab.MODELS },
                        colors = navColors(),
                        icon = {
                            Icon(
                                Icons.Rounded.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(IconSize.Nav),
                            )
                        },
                        label = { Text(stringResource(R.string.tab_models)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        colors = navColors(),
                        icon = {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(IconSize.Nav),
                            )
                        },
                        label = { Text(stringResource(R.string.tab_settings)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onSurface,
    selectedTextColor = MaterialTheme.colorScheme.onSurface,
    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
