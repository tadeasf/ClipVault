import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
android {
    namespace = "cz.tadeasf.clipvault"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "cz.tadeasf.clipvault"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "distribution"
    productFlavors {
        // No network permission, no sync code. Never weaken this flavor to make `sync` easier.
        create("vault") {
            dimension = "distribution"
        }
        create("sync") {
            dimension = "distribution"
            applicationIdSuffix = ".sync"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Test-only: release + R8, debug-signed so instrumented tests can run against it
        // (`-Pclipvault.testBuildType=minifiedCheck`). r8-check.pro keeps the sync code reachable, as the
        // step 8 UI will, so R8 really processes BouncyCastle instead of dropping it as unused.
        create("minifiedCheck") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFile("r8-check.pro")
            testProguardFile("r8-check-test.pro")
        }
    }
    testBuildType = providers.gradleProperty("clipvault.testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// After `android {}`: the flavor configurations only exist once productFlavors are declared.
dependencies {
    implementation(project(":ui"))
    "syncImplementation"(project(":sync"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx) // Drawable.toBitmap for source-app icons
    implementation(libs.compose.material3)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    androidTestImplementation(libs.androidx.testRunner)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.compose.uiTestJunit4)
    debugImplementation(libs.compose.uiTestManifest)
}

/** Fails if a vault variant's merged manifest asks for network access or its classpath carries sync code. */
abstract class VerifyVaultOffline : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val runtimeComponents: SetProperty<String>

    @TaskAction
    fun verify() {
        val permission = Regex("""android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_WIFI_MULTICAST_STATE|ACCESS_LOCAL_NETWORK|NEARBY_WIFI_DEVICES)\b""")
        val leaked = permission.findAll(mergedManifest.get().asFile.readText()).map { it.value }.toSet()
        check(leaked.isEmpty()) { "vault manifest declares network permissions: $leaked" }

        val syncCode = runtimeComponents.get().filter {
            it == "project :sync" || it == "project :sync-core" || it.startsWith("io.ktor:") || it.startsWith("org.jmdns:")
        }
        check(syncCode.isEmpty()) { "vault runtime classpath contains sync code: $syncCode" }
    }
}

fun ResolvedComponentResult.closure(seen: MutableSet<String> = mutableSetOf()): Set<String> {
    if (seen.add(id.displayName)) {
        dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { it.selected.closure(seen) }
    }
    return seen
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "vault")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyVaultOffline>("verify${name}Offline") {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            runtimeComponents.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { it.closure() })
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
