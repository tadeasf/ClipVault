package cz.tadeasf.clipvault

import androidx.compose.runtime.Composable

const val FLAVOR_NAME = "vault"

/** The vault flavor has no sync at all, so settings show no sync section. */
fun flavorSyncSection(): (@Composable () -> Unit)? = null
