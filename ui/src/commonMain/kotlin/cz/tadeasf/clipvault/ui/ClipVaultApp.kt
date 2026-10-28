package cz.tadeasf.clipvault.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.tadeasf.clipvault.settings.ThemeMode
import cz.tadeasf.clipvault.ui.detail.DetailScreen
import cz.tadeasf.clipvault.ui.detail.DetailViewModel
import cz.tadeasf.clipvault.ui.history.HistoryScreen
import cz.tadeasf.clipvault.ui.history.HistoryViewModel
import cz.tadeasf.clipvault.ui.settings.SettingsScreen
import cz.tadeasf.clipvault.ui.settings.SettingsViewModel
import cz.tadeasf.clipvault.ui.sync.PairingScreen
import cz.tadeasf.clipvault.ui.sync.PairingViewModel
import cz.tadeasf.clipvault.ui.sync.SyncSettingsSection
import cz.tadeasf.clipvault.ui.sync.SyncSettingsViewModel
import cz.tadeasf.clipvault.ui.sync.SyncUi

private sealed interface Screen {
    data object History : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
    data object Pairing : Screen
}

/**
 * The whole app UI. [sourceIcon] draws a source-app icon (Android only); [sync] is the sync
 * flavor's controller and permissions, null when sync is not part of the build.
 */
@Composable
fun ClipVaultApp(
    env: AppEnvironment,
    sourceIcon: @Composable (String) -> Unit = {},
    sync: SyncUi? = null,
) {
    val theme by env.settings.settings.collectAsState()
    ClipVaultTheme(theme.theme) {
        var screen by remember { mutableStateOf<Screen>(Screen.History) }
        PlatformBackHandler(enabled = screen != Screen.History) {
            screen = if (screen == Screen.Pairing) Screen.Settings else Screen.History
        }
        Surface {
            when (val current = screen) {
                Screen.History -> HistoryScreen(
                    viewModel = viewModel { HistoryViewModel(env.repository, { env.platform.copyToClipboard(it.content, it.sensitive) }) },
                    platform = env.platform,
                    syncState = sync?.controller?.state,
                    sourceIcon = sourceIcon,
                    navigate = { id -> screen = if (id == null) Screen.Settings else Screen.Detail(id) },
                )
                is Screen.Detail -> DetailScreen(
                    viewModel = viewModel(key = current.id) { DetailViewModel(current.id, env.repository, env.ingest, env.platform) },
                    back = { screen = Screen.History },
                )
                Screen.Settings -> SettingsScreen(
                    viewModel = viewModel { SettingsViewModel(env.settings, env.platform) },
                    appInfo = env.platform.appInfo,
                    syncSection = sync?.let { sync ->
                        {
                            SyncSettingsSection(
                                viewModel = viewModel { SyncSettingsViewModel(sync.controller, sync.permissions) },
                                openPairing = { screen = Screen.Pairing },
                            )
                        }
                    },
                    back = { screen = Screen.History },
                )
                Screen.Pairing -> sync?.let {
                    PairingScreen(
                        viewModel = viewModel { PairingViewModel(it.controller, it.permissions) },
                        scanner = it.scanner,
                        back = { screen = Screen.Settings },
                    )
                }
            }
        }
    }
}

@Composable
fun ClipVaultTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
