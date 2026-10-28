import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// clipvault-agent: headless daemon + CLI. CopyQ is the desktop front end (design section 11).
// The Compose plugin stays for jpackage packaging (and the small pairing/status window in step 8).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":sync-core"))
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.dbus.java.core) // GNOME extension fallback capture
    implementation(libs.dbus.java.transport)
    implementation(libs.zxing.core) // pairing QR in the terminal and as PNG (pairing.md section 5)
    implementation(libs.jmdns) // pairing=1 advertisement and `pair --join` discovery
    runtimeOnly(libs.slf4j.nop) // dbus-java logs through slf4j; without a provider it prints a warning on every start

    // Only the runtime, which the Compose compiler plugin insists on. compose.desktop.currentOs (Skia natives,
    // ~40 MB) comes back with the pairing window in step 8.
    implementation(libs.compose.runtime)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutinesTest)
}

compose.desktop {
    application {
        mainClass = "cz.tadeasf.clipvault.agent.MainKt"

        nativeDistributions {
            // AppImage = jpackage app-image directory; scripts/package-linux.sh builds the .rpm from it with rpmbuild
            // (to put the CLI on PATH), patches jpackage's .deb, and wraps the AppImage.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "clipvault-agent"
            packageVersion = "0.4.0"
            description = "ClipVault agent: encrypted clipboard history with CopyQ as the desktop front end"
            vendor = "ClipVault"
            licenseFile.set(rootProject.file("LICENSE"))
            // From :suggestRuntimeModules. jdk.security.auth is what dbus-java's EXTERNAL (uid) auth needs.
            modules("java.instrument", "java.sql", "jdk.security.auth", "jdk.unsupported")
            linux {
                shortcut = false
                debMaintainer = "tadeasf <tadeasfort@proton.me>"
                rpmLicenseType = "MIT"
            }
        }
    }
}
