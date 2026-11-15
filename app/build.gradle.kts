plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.phonecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.phonecast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    packaging {
        jniLibs.pickFirsts += "**/libjingle_peerconnection_so.so"
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
