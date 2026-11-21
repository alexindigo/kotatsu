plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.alexindigo.kotatsu.fakemuditacalendar"
    buildFeatures { buildConfig = true }
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.alexindigo.kotatsu.fakemuditacalendar"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
