import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val envProps = Properties().apply {
    listOf(".env", ".env.codex").forEach { path ->
        rootProject.projectDir.resolve(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
}

android {
    namespace = "com.codex.wear"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/sidekick-wear-keystore.jks")
            storePassword = "sidekick"
            keyAlias = "key0"
            keyPassword = "sidekick"
        }
    }

    defaultConfig {
        applicationId = "com.codex.wear"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "DEFAULT_CODEX_BASE_URL", "\"${envProps.getProperty("DEFAULT_CODEX_BASE_URL", "wss://dusk.catfish-basilisk.ts.net/codex")}\"")
        buildConfigField("String", "DEFAULT_CODEX_AUTH_TOKEN", "\"\"")
        buildConfigField("String", "DEFAULT_STT_AUTH_TOKEN", "\"\"")
        buildConfigField("boolean", "SCREENSHOT_MODE", "false")
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_CODEX_AUTH_TOKEN", "\"${envProps.getProperty("DEFAULT_CODEX_AUTH_TOKEN", "")}\"")
            buildConfigField("String", "DEFAULT_STT_AUTH_TOKEN", "\"${envProps.getProperty("DEFAULT_STT_AUTH_TOKEN", "")}\"")
        }
        create("screenshot") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "DEFAULT_CODEX_AUTH_TOKEN", "\"\"")
            buildConfigField("String", "DEFAULT_STT_AUTH_TOKEN", "\"\"")
            buildConfigField("boolean", "SCREENSHOT_MODE", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation.core)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.google.guava)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
