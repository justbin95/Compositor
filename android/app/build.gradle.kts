plugins {
    id("com.android.application")
}

val sharedDebugKeystorePath = System.getenv("COMPOSITOR_DEBUG_KEYSTORE")
val sharedDebugStorePassword = System.getenv("COMPOSITOR_DEBUG_KEYSTORE_PASSWORD")
val sharedDebugKeyAlias = System.getenv("COMPOSITOR_DEBUG_KEY_ALIAS")
val sharedDebugKeyPassword = System.getenv("COMPOSITOR_DEBUG_KEY_PASSWORD")
val sharedDebugSigningValues = listOf(
    sharedDebugKeystorePath,
    sharedDebugStorePassword,
    sharedDebugKeyAlias,
    sharedDebugKeyPassword
)
val useSharedDebugSigning = sharedDebugSigningValues.any { !it.isNullOrBlank() }
val useInstallerCompatibilityPackaging = providers.gradleProperty("compositor.installerCompatibility")
    .map { it.toBooleanStrict() }.getOrElse(true)
require(!useSharedDebugSigning || sharedDebugSigningValues.all { !it.isNullOrBlank() }) {
    "Set COMPOSITOR_DEBUG_KEYSTORE, COMPOSITOR_DEBUG_KEYSTORE_PASSWORD, COMPOSITOR_DEBUG_KEY_ALIAS, and COMPOSITOR_DEBUG_KEY_PASSWORD together."
}

android {
    namespace = "com.wonderassembly.compositor"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.wonderassembly.compositor"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    if (useSharedDebugSigning) {
        signingConfigs {
            create("sharedDebug") {
                storeFile = file(sharedDebugKeystorePath!!)
                storePassword = sharedDebugStorePassword
                keyAlias = sharedDebugKeyAlias
                keyPassword = sharedDebugKeyPassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
    }

    signingConfigs.getByName(if (useSharedDebugSigning) "sharedDebug" else "debug") {
        // Keep the certificate fixed while supporting both JAR-based scanners and platform verifiers.
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
        enableV4Signing = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs.useLegacyPackaging = useInstallerCompatibilityPackaging
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":editor-core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
