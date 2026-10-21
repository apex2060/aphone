import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes these. A local build keeps the defaults.
//   ./gradlew assembleRelease -PversionName=1.0.1 -PversionCode=42
val releaseVersionName = (findProperty("versionName") as String?)
    ?.trim()
    ?.removePrefix("v")
    ?.ifBlank { null }
    ?: "1.0.0"
val releaseVersionCode = (findProperty("versionCode") as String?)?.trim()?.toIntOrNull() ?: 1

android {
    namespace = "com.apex2060.softphone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apex2060.softphone"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        // Phones only: dropping x86 builds of the WebRTC libs roughly halves the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
    val keystoreStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val keystoreAlias = System.getenv("ANDROID_KEY_ALIAS")
    val keystoreKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseKeystore = !keystorePath.isNullOrBlank()
        && !keystoreStorePassword.isNullOrBlank()
        && !keystoreAlias.isNullOrBlank()
        && !keystoreKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystoreStorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never fall back to the debug key. checkReleaseSigning fails the build when
            // the stable keystore is missing, so an unsigned APK is not produced either.
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Fails the release build when the stable signing keystore is not configured."
    doLast {
        val path = System.getenv("ANDROID_KEYSTORE_FILE")
        val missing = buildList {
            if (path.isNullOrBlank()) add("ANDROID_KEYSTORE_FILE")
            if (System.getenv("ANDROID_KEYSTORE_PASSWORD").isNullOrBlank()) add("ANDROID_KEYSTORE_PASSWORD")
            if (System.getenv("ANDROID_KEY_ALIAS").isNullOrBlank()) add("ANDROID_KEY_ALIAS")
            if (System.getenv("ANDROID_KEY_PASSWORD").isNullOrBlank()) add("ANDROID_KEY_PASSWORD")
        }
        val fileMissing = !path.isNullOrBlank() && !project.file(path).isFile
        if (missing.isNotEmpty() || fileMissing) {
            throw org.gradle.api.GradleException(
                buildString {
                    appendLine("Release signing is not configured. Refusing to build an unsigned or debug-signed release APK.")
                    if (missing.isNotEmpty()) {
                        appendLine("Missing environment variable(s): ${missing.joinToString(", ")}.")
                    }
                    if (fileMissing) appendLine("Keystore file does not exist: $path")
                    appendLine("CI reads repository secrets ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD.")
                    appendLine("Locally, point ANDROID_KEYSTORE_FILE at the keystore and set the password and alias variables.")
                }.trim()
            )
        }
    }
}

afterEvaluate {
    tasks.matching { it.name != "checkReleaseSigning" && (it.name.endsWith("Release") || it.name.endsWith("ReleaseAndroidTest")) }
        .configureEach {
            dependsOn("checkReleaseSigning")
        }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.plivo.endpoint:endpoint:3.2.2-beta")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // QR scanning: CameraX + zxing core (no Google Play services dependency).
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.zxing:core:3.5.3")
}
