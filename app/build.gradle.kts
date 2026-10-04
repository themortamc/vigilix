plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vigilix.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vigilix.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.6.0"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    // Firma de release OPCIONAL: solo se usa si las 4 variables de entorno están definidas
    // (por ejemplo, desde GitHub Secrets). Sin ellas, assembleRelease genera un APK sin firmar
    // en lugar de fallar.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")
    val releaseStorePass = System.getenv("KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("KEY_ALIAS")
    val releaseKeyPass = System.getenv("KEY_PASSWORD")
    val hasReleaseSigning = releaseKeystore != null && releaseStorePass != null &&
        releaseKeyAlias != null && releaseKeyPass != null

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    buildFeatures {
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // Huella digital para desbloquear la bóveda (opcional para el usuario).
    implementation("androidx.biometric:biometric:1.1.0")
}
