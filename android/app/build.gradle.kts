import java.util.Properties
import java.io.FileInputStream

val properties = Properties().apply {
    val secretsFile = rootProject.file("secrets.properties")
    if (secretsFile.exists()) {
        load(FileInputStream(secretsFile))
    }
}


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "1.9.22"
}

android {
    namespace = "com.solita.pulse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.solita.pulse"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val porcupineAccessKey: String = project.findProperty("PORCUPINE_ACCESS_KEY") as? String ?: ""
        buildConfigField("String", "PORCUPINE_ACCESS_KEY", "\"$porcupineAccessKey\"")

    }

    buildFeatures {
        buildConfig = true // This enables BuildConfig generation
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.ui)
    implementation(libs.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.material.icons.extended)
    implementation (libs.androidx.ui.v160)
    implementation (libs.androidx.material3.v120)
    implementation (libs.androidx.foundation)
    implementation (libs.androidx.activity.compose.v172)
    implementation (libs.ui.tooling)
    implementation(libs.porcupine.android)
    implementation(libs.kotlinx.coroutines.android) // Use the latest version
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.accompanist.permissions)
}