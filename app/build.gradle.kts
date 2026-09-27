plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (GitHub Actions secrets),
// so the upload key never lives in the repo. Without them, release builds
// are simply left unsigned.
val uploadKeystore: String? = System.getenv("SNOOZY_KEYSTORE")

android {
    namespace = "com.snoozy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.snoozy.app"
        minSdk = 26
        targetSdk = 36
        // Play needs a higher versionCode for every upload; CI passes one in.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("SNOOZY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SNOOZY_KEY_ALIAS")
                keyPassword = System.getenv("SNOOZY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
