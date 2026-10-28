package cz.tadeasf.clipvault.ui

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; screens show their own back buttons. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
