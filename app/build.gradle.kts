plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.geto.notifhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geto.notifhub"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Kunci tetap supaya APK versi baru bisa menimpa versi lama di HP.
    // Hanya untuk pemakaian pribadi; jangan dipakai untuk rilis publik.
    signingConfigs {
        getByName("debug") {
            storeFile = file("notifhub-debug.jks")
            storePassword = "notifhub"
            keyAlias = "notifhub"
            keyPassword = "notifhub"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
