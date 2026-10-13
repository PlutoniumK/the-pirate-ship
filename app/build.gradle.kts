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
        versionName = "1.3.0"   // human version; bump by hand.

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
    // Intentionally no appcompat/webkit androidx (they force minSdk 21 and lose KitKat).
    // libVLC 3.x = minSdk 17 → KitKat-safe. The universal player: bundles its own decoders, so it
    // software-decodes almost anything (MKV / HEVC 10-bit / AV1 + AC3/EAC3/DTS + ASS/PGS subs) and
    // uses MediaCodec HW decode when the head unit's chip exposes it.
    implementation("org.videolan.android:libvlc-all:3.3.5")
    // androidx.core 1.13.1 keeps minSdk 19 (unlike appcompat) — needed only for FileProvider,
    // so the self-updater can hand the downloaded APK to the installer on API 24+.
    implementation("androidx.core:core:1.13.1")
}
