import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.rollentrainer"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.rollentrainer"
        minSdk = 26
        targetSdk = 35
        // CI sets WATTWERK_BUILD (see .github/workflows/build.yml): the build number keeps counting on from
        // the earlier private repository, so an installed app takes every new APK as an update.
        val build = System.getenv("WATTWERK_BUILD")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = "0.1.${build ?: 0}"
    }

    signingConfigs {
        // A fixed key in the repo, so every CI build installs as an update over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Geodata packs (assets/geo/<route>.wwgeo) stay uncompressed in the APK: the renderer reads them
    // with ranged reads through AssetManager.openFd, which only works on stored (uncompressed) assets.
    androidResources {
        noCompress += "wwgeo"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
}
