plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

base {
    archivesName.set("kotatsu-v0.6.1")
}

android {
    namespace = "com.gezimos.katapult"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.alexindigo.kotatsu"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "0.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":paper-ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidsvg)
    implementation(project(":weather-api"))
    implementation(project(":weather-mudita"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// The source-policy test reads sibling modules too, beyond :app's runtime classpath.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(rootProject.subprojects.map { module ->
        module.fileTree("src/main") {
            include("**/*.kt", "**/*.java", "res/layout*/**/*.xml", "res/values*/**/*.xml")
        }
    }).withPropertyName("appearanceSources").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
