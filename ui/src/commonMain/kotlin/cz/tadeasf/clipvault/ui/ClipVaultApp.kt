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

private sealed interface Screen {
    data object History : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
}

/**
 * The whole app UI. [sourceIcon] draws a source-app icon (Android only); [syncSection] is the
 * flavor's sync block in settings, null when sync is not part of the build.
 */
@Composable
fun ClipVaultApp(
    env: AppEnvironment,
    sourceIcon: @Composable (String) -> Unit = {},
    syncSection: (@Composable () -> Unit)? = null,
) {
    val theme by env.settings.settings.collectAsState()
    ClipVaultTheme(theme.theme) {
        var screen by remember { mutableStateOf<Screen>(Screen.History) }
        PlatformBackHandler(enabled = screen != Screen.History) { screen = Screen.History }
        Surface {
            when (val current = screen) {
                Screen.History -> HistoryScreen(
                    viewModel = viewModel { HistoryViewModel(env.repository, { env.platform.copyToClipboard(it.content, it.sensitive) }) },
                    platform = env.platform,
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
                    syncSection = syncSection,
                    back = { screen = Screen.History },
                )
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
