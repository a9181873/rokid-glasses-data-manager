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
        versionCode = 6
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // APK 不應內嵌建置機器的本機專案路徑。
            vcsInfo {
                include = false
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
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
