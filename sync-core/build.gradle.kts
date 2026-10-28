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
        // commonTest also runs on a device: the handshake vectors and J-PAKE must hold on ART too.
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
    }
    sourceSets {
        // BouncyCastle-backed crypto shared by both JVM-family targets (same manual wiring as :shared).
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)
        // Tests that read resources through the JVM class loader (handshake vectors) run on all three:
        // jvm, Android host and the device.
        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(jvmAndAndroidTest)
        getByName("androidHostTest").dependsOn(jvmAndAndroidTest)
        getByName("androidDeviceTest").dependsOn(jvmAndAndroidTest)
        jvmAndAndroidMain.dependencies {
            // Every handshake and pairing primitive (docs/sync/handshake.md section 2), lightweight API only.
            implementation(libs.bouncycastle.prov)
        }
        commonMain.dependencies {
            implementation(project(":shared"))
            // Wire format (docs/sync/protocol.md section 2); JSON only for debug output.
            implementation(libs.kotlinx.serializationCbor)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            // Property-based convergence tests; engine-free (protocol.md section 13). JVM only: the
            // property tests live in jvmTest, and its deps would clash in the device test APK.
            implementation(libs.kotest.property)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testRunner)
            implementation(libs.androidx.testExt.junit)
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
