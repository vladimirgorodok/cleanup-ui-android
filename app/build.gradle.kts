plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystore = file(
    System.getenv("CLEANUP_STORE_FILE") ?: "${rootDir}/../cleanup-ui-release.jks"
)

android {
    namespace = "ru.opencode.cleanup"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.opencode.cleanup"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("CLEANUP_STORE_PASSWORD") ?: "cleanup"
                keyAlias = System.getenv("CLEANUP_KEY_ALIAS") ?: "cleanup"
                keyPassword = System.getenv("CLEANUP_KEY_PASSWORD") ?: "cleanup"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
