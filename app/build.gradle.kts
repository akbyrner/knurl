import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Machine-specific values live in local.properties, which git ignores.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.detent"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.detent"
        minSdk = 29 // MediaStore.loadThumbnail for album art
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The bundled librespot binary is arm64 only.
        ndk { abiFilters += "arm64-v8a" }
        // Public identifier of the Spotify app registration. Not a secret; the sign-in uses PKCE, so no client secret is needed or shipped.
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProps.getProperty("spotify.clientId", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // librespot is an executable shipped as a .so; it has to be unpacked to nativeLibraryDir to be run.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
}
