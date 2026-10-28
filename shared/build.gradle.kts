import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Domain + storage layer. No UI, no network code: the vault flavor depends on this.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()
    android {
        namespace = "cz.tadeasf.clipvault.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // Instrumented only: SQLCipher's native libs and AndroidKeyStore don't exist on a host JVM (Robolectric).
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    sourceSets {
        // JDK APIs (MessageDigest, javax.crypto) shared by both JVM-family targets. Manual wiring because the
        // hierarchy template's withAndroidTarget() doesn't match the new AGP KMP target (see gradle.properties).
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            api(libs.androidx.paging.common)
            implementation(libs.sqldelight.paging)
            implementation(libs.kotlinx.coroutinesCore)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.androidDriver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        jvmMain.dependencies {
            // String notation: the Kotlin DSL only accepts an exclude block there. Excluded on the
            // dependency itself so consumers (:desktopApp) don't get xerial's copy of org.sqlite either.
            implementation(libs.sqldelight.sqliteDriver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc") // replaced by the SQLCipher-capable fork
            }
            implementation(libs.sqlite.jdbc.crypt)
            implementation(libs.bouncycastle.prov)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testRunner)
            implementation(libs.androidx.testExt.junit)
        }
    }
}

sqldelight {
    databases {
        create("ClipDatabase") {
            packageName.set("cz.tadeasf.clipvault.db")
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                packages("cz.tadeasf.clipvault.db") // SQLDelight-generated
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
