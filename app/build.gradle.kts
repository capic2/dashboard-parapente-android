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
        versionCode = providers.gradleProperty("VERSION_CODE").orElse("1").get().toInt()
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE")
                .orElse(providers.gradleProperty("RELEASE_STORE_FILE"))
                .orNull
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile)
                    storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
                        .orElse(providers.gradleProperty("RELEASE_STORE_PASSWORD"))
                        .get()
                    keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
                        .orElse(providers.gradleProperty("RELEASE_KEY_ALIAS"))
                        .get()
                    keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                        .orElse(providers.gradleProperty("RELEASE_KEY_PASSWORD"))
                        .get()
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
