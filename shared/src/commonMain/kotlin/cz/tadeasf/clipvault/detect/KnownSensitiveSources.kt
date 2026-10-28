package cz.tadeasf.clipvault.detect

import cz.tadeasf.clipvault.model.ClipHints

/**
 * Data only: apps and windows whose clips are treated as PASSWORD regardless of content.
 * Add entries here, not in the detector.
 */
object KnownSensitiveSources {
    /** Android package names of password managers and authenticators. */
    val packages: Set<String> = setOf(
        "com.x8bit.bitwarden", // Bitwarden
        "com.bitwarden.authenticator", // Bitwarden Authenticator
        "com.kunzisoft.keepass.free", // KeePassDX
        "com.kunzisoft.keepass.libre", // KeePassDX (F-Droid)
        "com.kunzisoft.keepass.pro", // KeePassDX Pro
        "proton.android.pass", // Proton Pass
        "proton.android.pass.fdroid", // Proton Pass (F-Droid)
        "com.onepassword.android", // 1Password 8
        "com.agilebits.onepassword", // 1Password 7
        "com.beemdevelopment.aegis", // Aegis
        "org.shadowice.flocke.andotp", // andOTP
    )

    /** Case-insensitive substrings of desktop window titles. */
    val windowTitles: List<String> = listOf(
        "KeePassXC",
        "KeePass",
        "Bitwarden",
        "1Password",
        "Proton Pass",
        "Aegis",
    )

    /** True when the copying app asked for the clip not to be kept, or is a password manager. */
    fun matches(hints: ClipHints): Boolean {
        val title = hints.sourceWindowTitle
        return hints.isSensitiveFlag ||
            hints.sourcePackage in packages ||
            (title != null && windowTitles.any { title.contains(it, ignoreCase = true) })
    }
}
