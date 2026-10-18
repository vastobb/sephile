buildscript {
    extra {
        set("compileSdk", 34)
        set("minSdk", 24)
        set("targetSdk", 34)
        set("agpVersion", "8.5.0")
        set("kotlinVersion", "2.0.20")
        set("composeBom", "2024.10.00")
        set("ndkVersion", "27.0.12077973")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:${extra["agpVersion"]}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${extra["kotlinVersion"]}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

tasks.register("clean", Delete) {
    delete(rootProject.buildDir)
}
