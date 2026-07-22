plugins {
    id("com.android.application")
}

android {
    namespace = "tw.dky.rokidfiles"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.dky.rokidfiles"
        minSdk = 28
        targetSdk = 32
        versionCode = 4
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 此 APK 專供目前為 Android 12/API 32 的 Rokid YodaOS 側載，不送 Google Play。
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
