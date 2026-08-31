plugins {
    id("com.android.application")
}

android {
    namespace = "com.skyking0007.irishdrviewfinder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.skyking0007.irishdrviewfinder"
        minSdk = 29
        targetSdk = 37
        versionCode = 11
        versionName = "1.0-v1.4.6"
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
}
