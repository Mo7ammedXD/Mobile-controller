plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mobilecontroller.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mobilecontroller.client"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

dependencies {
    testImplementation(libs.junit)
}
