plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

// CI sets these so every build is signed with the same permanent key (needed for in-place updates).
val kxkoKeystore: String? = System.getenv("KXKO_KEYSTORE_FILE")
val kxkoBuildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.kxko.dailybriefing"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kxko.dailybriefing"
        minSdk = 23
        targetSdk = 37
        versionCode = 100 + kxkoBuildNumber
        versionName = "2.3.$kxkoBuildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (kxkoKeystore != null) {
            create("kxko") {
                storeFile = file(kxkoKeystore)
                storePassword = System.getenv("KXKO_KEYSTORE_PASSWORD")
                keyAlias = "kxko"
                keyPassword = System.getenv("KXKO_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            if (kxkoKeystore != null) signingConfig = signingConfigs.getByName("kxko")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("androidx.media:media:1.7.0")
}