plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    // Applied by :app only when app/google-services.json is present.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
