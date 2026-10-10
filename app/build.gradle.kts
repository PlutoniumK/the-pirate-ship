plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jingalala.pirateship"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jingalala.pirateship"
        // KitKat floor (old car head units). Uses ONLY framework Activity + WebView — no androidx
        // (appcompat/webkit force minSdk 21), so this genuinely installs on 4.4.
        minSdk = 19
        targetSdk = 35
        // Auto-increment (minutes since epoch) so a newly sideloaded APK always outranks the
        // installed one → in-place "Update". INTERNAL only, never shown. Signed with the same
        // dedicated Pirate Ship key forever, so updates verify. Never collides with The Cap.
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "1.0.0"   // human version; bump by hand.

        // Car head units are ARM. 32-bit (armeabi-v7a) is mandatory for genuine KitKat units;
        // arm64 for newer ones. (libVLC 3.x adds its native libs for these at the player step.)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Dedicated, committed keystore so EVERY Pirate Ship build is signed with the SAME key →
    // sideloaded updates install in place. Separate from The Cap's key (different app entirely).
    signingConfigs {
        create("app") {
            storeFile = file("pirateship-signing.keystore")
            storePassword = "android"
            keyAlias = "pirateship"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("app") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
        buildConfig = true
    }
}

dependencies {
    // Intentionally no androidx here (it would force minSdk 21 and lose KitKat). Framework
    // Activity + WebView only. libVLC 3.3.5 (minSdk 17) is added at the player step.
}
