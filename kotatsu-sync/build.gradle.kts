plugins { alias(libs.plugins.android.application) }

android {
    sourceSets.getByName("androidTest").kotlin.directories += rootProject.file("test-fixtures/src/androidTest/java").path
    namespace = "com.alexindigo.kotatsu.sync"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.alexindigo.kotatsu.sync"
        minSdk = 30
        targetSdk = 36
        versionCode = 11
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { debug { applicationIdSuffix = ".debug" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    constraints { implementation("org.jetbrains:annotations:23.0.0") }
    testImplementation(libs.junit)
    // Match Notes' instrumented classpath, whose versions are in the offline cache.
    androidTestImplementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui)
    androidTestImplementation(libs.androidx.compose.foundation)
    androidTestImplementation(libs.androidx.compose.material3)
    androidTestImplementation(libs.androidx.compose.material.icons)
    androidTestImplementation(libs.androidsvg)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
