buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9 compiles Kotlin itself (built-in Kotlin); this only raises the bundled KGP/compiler
        // from 2.2.10 to the current stable release, as the AGP 9 release notes prescribe.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
