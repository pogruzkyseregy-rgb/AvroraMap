plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.avrora.map"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.avrora.map"
        minSdk = 24
        targetSdk = 35
        // номер сборки растёт сам с каждым запуском GitHub Actions
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Постоянный ключ подписи: обновления ставятся поверх, без удаления
    val keyFile = file("avrora.jks")
    val keyPass = System.getenv("KEYSTORE_PASSWORD")
    signingConfigs {
        create("avrora") {
            storeFile = keyFile
            storePassword = keyPass
            keyAlias = "avrora"
            keyPassword = keyPass
        }
    }
    buildTypes {
        getByName("debug") {
            if (keyFile.exists() && !keyPass.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("avrora")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
