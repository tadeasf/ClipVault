// Android-only glue for the `sync` flavor. Its manifest is the only place network permissions live.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "cz.tadeasf.clipvault.sync.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sync-core"))
    implementation(project(":shared"))
    implementation(project(":ui")) // the SyncController contract only
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.sqldelight.androidDriver)
    api(libs.androidx.work.runtime) // periodic and expedited catch-up while the service is stopped
    api(libs.zxing.core) // QR: generated here, decoded by the app's camera scanner

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)
}
