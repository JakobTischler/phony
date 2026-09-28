plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every GitHub build gets a higher number, so a new APK installs over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val keystorePath: String? = System.getenv("PHONY_KEYSTORE_FILE")

android {
    namespace = "com.hughhowey.phony"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hughhowey.phony"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PHONY_KEYSTORE_PASSWORD")
                keyAlias = "phony"
                keyPassword = System.getenv("PHONY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
