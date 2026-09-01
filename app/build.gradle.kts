plugins {
    id("com.android.application")
}

val stableDebugKeystore = System.getenv("IRIS_TEST_KEYSTORE_PATH")

android {
    namespace = "com.skyking0007.irishdrviewfinder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.skyking0007.irishdrviewfinder"
        minSdk = 29
        targetSdk = 37
        versionCode = 21
        versionName = "1.0-v1.4.16"
    }

    signingConfigs {
        if (!stableDebugKeystore.isNullOrBlank()) {
            create("stableDebug") {
                storeFile = file(stableDebugKeystore)
                storePassword = "IrisHdrTest2026"
                keyAlias = "iris-hdr-test"
                keyPassword = "IrisHdrTest2026"
                storeType = "PKCS12"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            if (!stableDebugKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }
}
