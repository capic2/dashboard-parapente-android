plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.capic.dashboardparapente"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.capic.dashboardparapente"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile)
                    storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").get()
                    keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").get()
                    keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").get()
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
}
