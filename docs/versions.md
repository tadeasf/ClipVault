# Pinned versions

Source of truth is `gradle/libs.versions.toml`; this file records why each version was picked and
where it was verified (2026-10-28). Update both together.

| Component | Version | Verified at | Note |
|---|---|---|---|
| Gradle wrapper | 9.5.1 | KMP wizard output | AGP 9.1 needs Gradle >= 9.3.1 |
| Kotlin | 2.4.21 | https://github.com/JetBrains/kotlin/releases | KGP 2.4.2x tested with AGP 8.5.2-9.3.1 (https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) |
| AGP | 9.1.1 | KMP wizard output | Inside Kotlin's tested range; 9.4.x is not |
| Compose Multiplatform | 1.12.2 | https://github.com/JetBrains/compose-multiplatform/blob/master/CHANGELOG.md | |
| CMP material3 | 1.12.0-alpha03 | KMP wizard output | Wizard pins an alpha |
| androidx lifecycle (JB) | 2.11.0 | KMP wizard output | |
| kotlinx-coroutines | 1.11.0 | KMP wizard output | |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | local SDK | targetSdk 37 means `ACCESS_LOCAL_NETWORK` is enforced for `sync` |
| JVM toolchain | 21 (Azul) | `gradle/gradle-daemon-jvm.properties` | bytecode target JVM 11 as generated |

Added in later steps (verified in `docs/design.md` section 4, pinned when first used):
SQLDelight 2.4.1, AndroidX Paging 3.5.3, Ktor 3.6.0, kotlinx-serialization 1.12.0, Kover 0.9.11,
sqlcipher-android 4.20.0, sqlite-jdbc-crypt 3.53.4.0, Tink 1.24.0, BouncyCastle 1.86, JmDNS 3.6.4,
zxing core 3.5.4, dbus-java 5.2.2.
