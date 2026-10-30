import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release signing key, kept out of git (README, "Making a release"). Phones refuse to install an unsigned APK.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

// A release build takes its version from its tag (scripts/Build-Release.ps1 -Version 1.2.3).
val releaseVersion = providers.gradleProperty("releaseVersion").orNull

// 1.2.3 -> 10203: grows with every release, as Android requires for an update to install over the old app.
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split('.').map(String::toInt)
    return major * 10_000 + minor * 100 + patch
}

android {
    namespace = "com.mobilecontroller.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mobilecontroller.client"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 1
        versionName = releaseVersion ?: "1.0"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // The settings overlay; the controller surface itself is a View and never composes.
        compose = true
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
}
