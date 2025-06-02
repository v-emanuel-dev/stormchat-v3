import java.io.FileInputStream
import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
} else {
    println("Warning: local.properties not found. API Key will be missing from BuildConfig.")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"

    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// Configuração para resolver o conflito de versões do Kotlin
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
        force("org.jetbrains.kotlin:kotlin-reflect:1.9.22")
    }
}

android {
    namespace = "com.ivip.brainstormia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ivip.brainstormia"
        minSdk = 26
        targetSdk = 35
        versionCode = 101
        versionName = "10.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API Keys existentes
        val apiKeyOpenaiFromProperties = localProperties.getProperty("apiKeyOpenai") ?: ""
        if (apiKeyOpenaiFromProperties.isBlank()) {
            println("Warning: 'apiKeyOpenai' not found in local.properties. BuildConfig field will be empty.")
        }

        val apiKeyGoogleFromProperties = localProperties.getProperty("apiKeyGoogle") ?: ""
        if (apiKeyGoogleFromProperties.isBlank()) {
            println("Warning: 'apiKeyGoogle' not found in local.properties. BuildConfig field will be empty.")
        }

        val apiKeyAnthropicFromProperties = localProperties.getProperty("apiKeyAnthropic") ?: ""
        if (apiKeyAnthropicFromProperties.isBlank()) {
            println("Warning: 'apiKeyAnthropic' not found in local.properties. BuildConfig field will be empty.")
        }

        buildConfigField("String", "OPENAI_API_KEY", "\"${apiKeyOpenaiFromProperties}\"")
        buildConfigField("String", "GOOGLE_API_KEY", "\"${apiKeyGoogleFromProperties}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${apiKeyAnthropicFromProperties}\"")

        // Versão da API
        buildConfigField("String", "API_VERSION", "\"1.0\"")

        // Multidex
        multiDexEnabled = true
    }

    // ============================================================================
    // CONFIGURAÇÃO DE BUILD TYPES COM VARIÁVEIS DE AMBIENTE
    // ============================================================================
    buildTypes {
        debug {
            // Configurações de desenvolvimento
            val devApiUrl = localProperties.getProperty("api.url.dev") ?: "https://stormchat-678f9f5a3073.herokuapp.com"
            val devWebhookSecret = localProperties.getProperty("webhook.secret.dev") ?: "dev_secret_key_for_testing"

            buildConfigField("String", "API_BASE_URL", "\"$devApiUrl\"")
            buildConfigField("String", "WEBHOOK_SECRET", "\"$devWebhookSecret\"")
            buildConfigField("boolean", "ENABLE_LOGS", "true")

            // Debug sempre sem minificação para desenvolvimento
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            isJniDebuggable = true

            // Sufixo para identificar builds de debug
            //applicationIdSuffix = ".debug"
            //versionNameSuffix = "-DEBUG"
        }

        create("staging") {
            // Configurações de staging/homologação
            val stagingApiUrl = localProperties.getProperty("api.url.staging")
                ?: System.getenv("STAGING_API_URL")
                ?: "https://staging-api.brainstormia.com"
            val stagingWebhookSecret = localProperties.getProperty("webhook.secret.staging")
                ?: System.getenv("STAGING_WEBHOOK_SECRET")
                ?: ""

            buildConfigField("String", "API_BASE_URL", "\"$stagingApiUrl\"")
            buildConfigField("String", "WEBHOOK_SECRET", "\"$stagingWebhookSecret\"")
            buildConfigField("boolean", "ENABLE_LOGS", "true")

            // Staging com minificação mas ainda debugável
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = true
            isJniDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Sufixo para identificar builds de staging
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-STAGING"
        }

        release {
            // Configurações de produção
            val prodApiUrl = localProperties.getProperty("api.url.prod")
                ?: System.getenv("PROD_API_URL")
                ?: "https://api.brainstormia.com"
            val prodWebhookSecret = localProperties.getProperty("webhook.secret.prod")
                ?: System.getenv("WEBHOOK_SECRET")
                ?: ""

            buildConfigField("String", "API_BASE_URL", "\"$prodApiUrl\"")
            buildConfigField("String", "WEBHOOK_SECRET", "\"$prodWebhookSecret\"")
            buildConfigField("boolean", "ENABLE_LOGS", "false")

            // Release com todas as otimizações
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Configuração de assinatura (você precisa configurar isso)
            // signingConfig = signingConfigs.getByName("release")
        }
    }

//    // Configuração de flavors (opcional)
//    flavorDimensions += "environment"
//    productFlavors {
//        create("dev") {
//            dimension = "environment"
//            applicationIdSuffix = ".dev"
//            versionNameSuffix = "-DEV"
//
//            // Pode sobrescrever configurações específicas aqui
//            buildConfigField("boolean", "IS_DEV_FLAVOR", "true")
//        }
//
//        create("prod") {
//            dimension = "environment"
//
//            // Versão de produção sem sufixos
//            buildConfigField("boolean", "IS_DEV_FLAVOR", "false")
//        }
//    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Configuração de packaging para resolver conflitos META-INF
    packaging {
        resources {
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/LICENSE")
            excludes.add("META-INF/LICENSE.txt")
            excludes.add("META-INF/license.txt")
            excludes.add("META-INF/NOTICE")
            excludes.add("META-INF/NOTICE.txt")
            excludes.add("META-INF/notice.txt")
            excludes.add("META-INF/ASL2.0")
            excludes.add("META-INF/*.kotlin_module")
            excludes.add("META-INF/INDEX.LIST")
            excludes.add("META-INF/io.netty.versions.properties")

            pickFirsts.add("mozilla/public-suffix-list.txt")
        }
    }
}

dependencies {

    /* ---------- AndroidX / Compose ---------- */
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)

    /* ---------- Data & Room ---------- */
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences.core.android)

    /* ---------- Firebase (UM ÚNICO BOM) ---------- */
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")

    /* ---------- Google Sign‑In ---------- */
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    /* ---------- Google Drive ---------- */
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents", module = "httpclient")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.20.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    /* ---------- Networking ---------- */
    implementation("io.ktor:ktor-client-content-negotiation:2.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.3")
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    /* ---------- JSON & Serialization ---------- */
    implementation("org.json:json:20250517")
    implementation("com.google.code.gson:gson:2.10.1")

    /* ---------- Billing ---------- */
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    /* ---------- UI & Markdown ---------- */
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")

    /* ---------- Image Loading ---------- */
    implementation("io.coil-kt:coil-compose:2.4.0")

    /* ---------- Work Manager ---------- */
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    /* ---------- AI APIs ---------- */
    implementation("com.aallam.openai:openai-client:3.6.2")
    implementation(libs.generativeai) // Google Generative AI

    /* ---------- Document Processing ---------- */
    implementation("org.apache.pdfbox:pdfbox:2.0.27")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")

    /* ---------- ML Kit ---------- */
    implementation("com.google.mlkit:image-labeling:17.0.9")

    /* ---------- Multidex ---------- */
    implementation("androidx.multidex:multidex:2.0.1")

    /* ---------- HTTP Components (Unificado) ---------- */
    implementation("org.apache.httpcomponents:httpclient:4.5.14") {
        exclude(group = "org.apache.httpcomponents", module = "httpcore")
    }
    implementation("org.apache.httpcomponents:httpcore:4.4.16")

    /* ---------- DataStore ---------- */
    implementation("androidx.datastore:datastore-preferences:1.1.6")

    /* ---------- Backend ---------- */
    implementation("androidx.security:security-crypto:1.1.0-alpha07")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    /* ---------- Tests ---------- */
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Tarefa para verificar configurações
tasks.register("printBuildConfig") {
    doLast {
        println("=== Build Configurations ===")
        android.buildTypes.forEach { buildType ->
            println("\nBuild Type: ${buildType.name}")
            buildType.buildConfigFields.forEach { (key, field) ->
                println("  $key = ${field.value}")
            }
        }
    }
}