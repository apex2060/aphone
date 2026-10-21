import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

val releaseVersionName = (findProperty("versionName") as String?)
    ?.trim()
    ?.removePrefix("v")
    ?.ifBlank { null }
    ?: "0.4"
val releaseVersionCode = (findProperty("versionCode") as String?)?.trim()?.toIntOrNull() ?: 1

base {
    archivesName.set("sipspike")
}

android {
    namespace = "com.twentytwo.phone.sipspike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.twentytwo.phone.sipspike"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
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
            isMinifyEnabled = false
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

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Fails the spike release build when the stable signing keystore is not configured."
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
                "Spike release signing is not configured. Refusing to build an unsigned APK."
            )
        }
    }
}

afterEvaluate {
    tasks.matching { it.name != "checkReleaseSigning" && it.name.endsWith("Release") }
        .configureEach { dependsOn("checkReleaseSigning") }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.zxing:core:3.5.3")
}
