plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kendang.voicechanger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dtxpads.voicechanger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Mesin saraf RVC di dalam APK (tanpa server)
    // Pengkode MP3 (LAME) - Android tidak punya encoder MP3 bawaan
    implementation("com.github.naman14:TAndroidLame:1.1") {
        exclude(group = "com.android.support") // bentrok dengan androidx
    }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
