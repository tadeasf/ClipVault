package cz.tadeasf.clipvault

import androidx.compose.runtime.Composable
import cz.tadeasf.clipvault.sync.android.SyncFeature

@Composable
fun FlavorApp() = App(SyncFeature.status())
