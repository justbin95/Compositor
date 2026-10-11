plugins {
    id("com.android.library")
}

android {
    namespace = "com.wonderassembly.compositor.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
