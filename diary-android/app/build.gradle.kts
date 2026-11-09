plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.dayplan.diary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dayplan.diary"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    // A fixed key kept in the repo so each new APK installs over the previous one.
    signingConfigs {
        create("app") {
            storeFile = file("app.keystore")
            storePassword = "calendar123"
            keyAlias = "app"
            keyPassword = "calendar123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        debug { signingConfig = signingConfigs.getByName("app") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
