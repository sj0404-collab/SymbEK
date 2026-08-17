plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * ПОСТОЯННЫЙ КЛЮЧ ПОДПИСИ.
 *
 * Раньше release подписывался debug-ключом. У него две беды: он
 * генерируется на машине сборки, то есть у КАЖДОЙ сборки CI ключ свой,
 * и Android отказывается ставить обновление поверх APK с другой
 * подписью - «приложение не установлено». Приходилось сносить и терять
 * настройки.
 *
 * Ключ лежит в секретах репозитория (KEYSTORE_B64), а не в git.
 * Срок - 30 лет, alias kenji.
 */
val keystoreFile: File? = System.getenv("KENJI_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "dev.symbiosis.kenji"
    compileSdk = 35
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "dev.symbiosis.kenji"
        minSdk = 29
        targetSdk = 35
        // Версия. versionCode обязан расти, иначе Android откажется
        // ставить обновление поверх. В CI подставляется номер сборки,
        // локально остаётся базовое значение.
        versionCode = (System.getenv("KENJI_VERSION_CODE") ?: "6").toInt()
        versionName = System.getenv("KENJI_VERSION_NAME") ?: "0.4.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }
    signingConfigs {
        create("kenji") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storePassword = System.getenv("KENJI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KENJI_KEY_ALIAS")
                keyPassword = System.getenv("KENJI_KEY_PASSWORD")
            }
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
            // Свой ключ, когда он есть; debug - только для локальной
            // сборки без секретов, такой APK поверх не встанет.
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("kenji")
            } else {
                signingConfigs.getByName("debug")
            }
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
