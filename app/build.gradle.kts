import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
}

// Release signing. The key and its passwords never enter the repository: they are read from
// keystore.properties in the project folder (gitignored, see docs/RELEASING.md), which points to a
// keystore kept elsewhere. Without that file a release build comes out unsigned
// (app-release-unsigned.apk), which Android refuses to install: visible, never silently debug-signed.
val keystoreProps = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreProps.exists()) keystoreProps.inputStream().use { load(it) } }

android {
    namespace = "com.yonizilber.flownics"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yonizilber.flownics"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.exists()) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile") ?: error("keystore.properties: storeFile is missing"))
                storePassword = keystore.getProperty("storePassword") ?: error("keystore.properties: storePassword is missing")
                keyAlias = keystore.getProperty("keyAlias") ?: error("keystore.properties: keyAlias is missing")
                keyPassword = keystore.getProperty("keyPassword") ?: error("keystore.properties: keyPassword is missing")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8 shrinks and obfuscates the release APK. This is not a secret store (there are no
            // secrets in the APK by design: every user brings their own AI key), but it keeps the
            // shipped app small and harder to tamper with.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // The migration test reads the exported Room schemas as assets.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    androidResources {
        // Generates the per-app language list (Settings > App > Language on Android 13+).
        generateLocaleConfig = true
    }
}

ksp {
    // Room writes each schema version to app/schemas so migrations can be tested and reviewed.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Official Claude client (Gemini goes through Retrofit above, as in Lectura).
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
