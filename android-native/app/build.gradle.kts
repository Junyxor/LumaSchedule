plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lumaschedule.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumaschedule.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-native"
    }

    val keyPath = System.getenv("LUMA_KEYSTORE_PATH")
    if (!keyPath.isNullOrBlank()) {
        signingConfigs {
            create("luma") {
                storeFile = file(keyPath)
                storePassword = System.getenv("LUMA_STORE_PASSWORD")
                keyAlias = System.getenv("LUMA_KEY_ALIAS")
                keyPassword = System.getenv("LUMA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("luma")
        }
    }

    sourceSets {
        getByName("main") {
            // Vite output and the build-time synchronized adapter snapshot are packaged directly.
            assets.srcDir("../../dist")
            assets.srcDir("../../vendor")
            res.srcDir("../../native/android/res")
            // The Shiguang login Activity is already plain Android Kotlin and has no Tauri dependency.
            java.srcDir("../../native/android/shiguang")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
    }

    buildFeatures { buildConfig = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

// Deliberately empty: Android framework APIs + Kotlin stdlib only.
// No Compose, AndroidX, OkHttp, Room, Retrofit, WorkManager or third-party runtime SDKs.
dependencies {}
