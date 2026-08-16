plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.symbiosis.kenji"
    compileSdk = 35
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "dev.symbiosis.kenji"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }
    buildTypes {
        release {
            // Official Kenji ships unminified. R8 + JNA needs desktop AWT
            // stubs we do not have; minify here only delayed a working APK.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildFeatures { buildConfig = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Official APK + CMake/JNA both ship these names.
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libjnidispatch.so"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
