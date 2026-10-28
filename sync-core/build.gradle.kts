import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Sync protocol, crypto, pairing, transport, discovery. Never a dependency of the vault flavor.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()
    android {
        namespace = "cz.tadeasf.clipvault.synccore"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            // Wire format (docs/sync/protocol.md section 2); JSON only for debug output.
            implementation(libs.kotlinx.serializationCbor)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            // Property-based convergence tests; multiplatform and engine-free (protocol.md section 13).
            implementation(libs.kotest.property)
        }
    }
}

sqldelight {
    databases {
        // Sync bookkeeping (protocol.md section 12), separate from ClipDatabase so the vault flavor holds no sync state.
        create("SyncDatabase") {
            packageName.set("cz.tadeasf.clipvault.sync.db")
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                packages("cz.tadeasf.clipvault.sync.db") // SQLDelight-generated
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
