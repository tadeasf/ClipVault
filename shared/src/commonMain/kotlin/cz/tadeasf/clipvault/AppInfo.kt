package cz.tadeasf.clipvault

const val APP_NAME = "ClipVault"

fun appTitle(platform: Platform = getPlatform()): String = "$APP_NAME on ${platform.name}"
