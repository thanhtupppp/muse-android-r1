plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.tutu.muser1"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.tutu.muser1"
        minSdk = 22
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0-headless"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
