plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reynaldi.cppstudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reynaldi.cppstudio"
        minSdk = 28            // Android 9 Pie
        // targetSdk 28 keeps the OS from blocking execution of a locally
        // installed toolchain (Android 10+ restricts that for targetSdk 29+).
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug") // replace with your own key for publishing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.tukaani:xz:1.9")   // to unpack .tar.xz toolchain packages
}
