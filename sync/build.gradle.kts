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
}
