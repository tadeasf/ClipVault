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
| kotlinx-coroutines | 1.11.0 | KMP wizard output | also `kotlinx-coroutines-test` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | local SDK | targetSdk 37 means `ACCESS_LOCAL_NETWORK` is enforced for `sync` |
| JVM toolchain | 21 (Azul) | `gradle/gradle-daemon-jvm.properties` | bytecode target JVM 11 as generated |
| SQLDelight | 2.4.1 | https://github.com/sqldelight/sqldelight/releases | plugin + android-driver, sqlite-driver, androidx-paging3-extensions |
| AndroidX Paging | 3.5.3 | Google Maven `paging-common` metadata (2026-10-12) | jvm variant published as `paging-common-desktop` |
| androidx.sqlite | 2.7.0 | sqlcipher-android 4.20.0 POM dependency | the version sqlcipher-android is built against |
| sqlcipher-android | 4.20.0 | Maven Central (2026-10-27), https://github.com/sqlcipher/sqlcipher-android | `.so` built with `ENABLE_FTS5` |
| sqlite-jdbc-crypt | 3.53.4.0 | Maven Central (2026-07-26), https://github.com/Willena/sqlite-jdbc-crypt | replaces `org.xerial:sqlite-jdbc` under sqlite-driver |
| BouncyCastle bcprov | 1.86 | Maven Central (2026-09-11) | Argon2id for the desktop key-file fallback |
| kotlinx-serialization-json | 1.12.0 | https://github.com/Kotlin/kotlinx.serialization/releases | JSON content detection and pretty/minify; runtime library only, no compiler plugin. Builds and tests with Kotlin 2.4.21 |
| dbus-java (core + native-unixsocket transport) | 5.2.2 | Maven Central (2026-09-26), https://github.com/hypfvieh/dbus-java | Secret Service keyring; also for the tray and GlobalShortcuts later. Brings slf4j-api (no provider bound yet) |
| Kover | 0.9.11 | https://github.com/Kotlin/kotlinx-kover (2026-09-29) | host-JVM tests only |

Added in later steps (verified in `docs/design.md` section 4, pinned when first used):
Ktor 3.6.0, Tink 1.24.0, JmDNS 3.6.4, zxing core 3.5.4.
