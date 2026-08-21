plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val otaVersionCode = System.getenv("CAMERA_VERSION_CODE")?.toIntOrNull() ?: 1
val otaVersionName = System.getenv("CAMERA_VERSION_NAME") ?: "0.1.0-phase01-dev"

android {
    namespace = "com.sahid.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sahid.camera"
        minSdk = 28
        targetSdk = 36
        versionCode = otaVersionCode
        versionName = otaVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "OTA_MANIFEST_URL",
            "\"https://github.com/sahid-code404/Camera-Computaional/releases/download/phase01-latest/update.json\"",
        )
    }

    signingConfigs {
        create("phase01Dev") {
            storeFile = rootProject.file("keystore/phase01-dev.jks")
            storePassword = "camera-phase01-dev"
            keyAlias = "camera-phase01"
            keyPassword = "camera-phase01-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("phase01Dev")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":camera-core"))
    implementation(project(":aurora-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
