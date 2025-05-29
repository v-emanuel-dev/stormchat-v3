// =========================
// root build.gradle.kts
// =========================

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Google Services plugin (single version)
        classpath("com.google.gms:google-services:4.4.2")
        // Crashlytics Gradle plugin
        classpath("com.google.firebase:firebase-crashlytics-gradle:2.9.9")
    }
}

plugins {
    // Android Gradle Plugin + Kotlin (única versão)
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library")    version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false

    // Outros plugins (sem aplicar aqui)
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
}

// Tarefa opcional de limpeza
tasks.register<Delete>("clean") { delete(rootProject.buildDir) }