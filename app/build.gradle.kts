plugins {
    id("com.android.application")
}

android {
    namespace = "com.skyking0007.irishdrviewfinder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.skyking0007.irishdrviewfinder.v1411v2"
        minSdk = 29
        targetSdk = 37
        versionCode = 61
        versionName = "1.0-v1.4.11-v2.43"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        noCompress += listOf("tflite")
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:2.1.5")
}
