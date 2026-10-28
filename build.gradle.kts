plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kover) apply false
}

// docs/design.md section 12: a local test run never reaches a Secret Service. Every JVM test task gets a
// session bus address that cannot connect, so SecretServiceKeyring.detect() returns null. Keyring
// integration is tested only in CI (a fresh runner, which sets CI=true).
subprojects {
    tasks.withType<Test>().configureEach {
        if (System.getenv("CI") != "true") {
            environment("DBUS_SESSION_BUS_ADDRESS", "unix:path=/nonexistent/clipvault-local-tests-have-no-session-bus")
        }
    }
}
