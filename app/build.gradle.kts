plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.autobangchien"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.autobangchien"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Khoá ký cố định để cài đè được bản mới lên bản cũ (không phải gỡ app mỗi lần).
    signingConfigs {
        create("fixed") {
            storeFile = file("debug.keystore")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
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
    implementation("org.opencv:opencv:4.14.0")
}
