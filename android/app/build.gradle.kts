plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.gravador_tela"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.example.gravador_tela"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // ✅ FFmpegKit (fork disponível no Maven Central)
    implementation("com.mrljdx:ffmpeg-kit-full:6.1.4")
     // ❌ FORÇAR exclusão do plugin problemático, caso seja puxado como dependência transitiva
    configurations.all {
        exclude(group = "com.github.arthenica", module = "ffmpeg-kit-android-min-gpl")
        exclude(group = "com.arthenica", module = "ffmpeg-kit-min-gpl")
        // Adicione outras variações se necessário
    }
}

flutter {
    source = "../.."
}