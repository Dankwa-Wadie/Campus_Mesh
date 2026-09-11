plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.campusmesh.android" // Keep namespace; only applicationId changes for Play Store identity
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "edu.gctu.campusmesh"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release signing. Supply the four values either as Gradle properties (e.g. in
    // ~/.gradle/gradle.properties, which is outside the repo) or as environment variables in CI:
    //
    //   CM_KEYSTORE_FILE / CM_KEYSTORE_PASSWORD / CM_KEY_ALIAS / CM_KEY_PASSWORD
    //
    // When they're absent the release build stays unsigned, so local `assembleRelease` still works
    // - but note that an unsigned APK cannot be installed by Android, and OTA signature
    // verification has nothing to compare against. Releases must be built with these set.
    val keystorePath = (findProperty("CM_KEYSTORE_FILE") as String?) ?: System.getenv("CM_KEYSTORE_FILE")
    val keystorePassword = (findProperty("CM_KEYSTORE_PASSWORD") as String?) ?: System.getenv("CM_KEYSTORE_PASSWORD")
    val keystoreAlias = (findProperty("CM_KEY_ALIAS") as String?) ?: System.getenv("CM_KEY_ALIAS")
    val keystoreKeyPassword = (findProperty("CM_KEY_PASSWORD") as String?) ?: System.getenv("CM_KEY_PASSWORD")
    val hasSigningConfig = !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keystoreAlias.isNullOrBlank() &&
        !keystoreKeyPassword.isNullOrBlank() &&
        file(keystorePath).exists()

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Campus Mesh: no release signing config found - assembleRelease will produce " +
                    "an UNSIGNED APK that Android cannot install. Set CM_KEYSTORE_FILE, " +
                    "CM_KEYSTORE_PASSWORD, CM_KEY_ALIAS and CM_KEY_PASSWORD to sign it."
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
    testOptions {
        // Robolectric needs the merged resource table to resolve R.string/R.plurals lookups made
        // by code under test. Without this, any test touching a resource fails with
        // Resources$NotFoundException - which is what had NotificationManagerTest and
        // CommandProcessorTest disabled (the @Ignore comments blamed Mockito; that was a
        // misdiagnosis - mockito-inline was already on the test classpath).
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        // The baseline freezes today's known issues so they can't grow; abortOnError means a
        // NEW one fails the build instead of scrolling past in CI, which is what the previous
        // abortOnError=false/checkReleaseBuilds=false pair prevented. (The old baseline had
        // drifted badly - 42 of its 54 entries no longer existed.)
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = true

        // Translation completeness is real work, but it is a content task tracked separately -
        // it should not sit in the same queue as crash and security defects, and 361 of them
        // would bury everything else in the baseline.
        disable += setOf("MissingTranslation", "ImpliedQuantity", "MissingQuantity")

        // Classes of defect that must never be merely baselined: each one is a crash or a
        // security hole on some device (a missing runtime-permission check, an API used below
        // minSdk, a manifest class that doesn't exist, an unprotected broadcast receiver).
        error += setOf(
            "MissingPermission",
            "NewApi",
            "MissingClass",
            "UnspecifiedRegisterReceiverFlag"
        )
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // Ktor Server for Web Gateway
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)

    // Arti (Tor in Rust) Android bridge - custom build from latest source
    // Built with rustls, 16KB page size support, and onio//un service client
    // Native libraries are in src/tor/jniLibs/ (extracted from arti-custom.aar)
    // Only included in tor flavor to reduce APK size for standard builds
    // Note: AAR is kept in libs/ for reference, but libraries loaded from jniLibs/

    // Google Play Services Location
    implementation(libs.gms.location)

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // EXIF orientation handling for images
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
