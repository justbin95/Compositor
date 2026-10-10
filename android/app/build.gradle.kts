plugins {
    id("com.android.application")
}

android {
    namespace = "com.wonderassembly.compositor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wonderassembly.compositor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
