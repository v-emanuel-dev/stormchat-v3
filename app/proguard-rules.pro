# ============================================================================
# REGRAS PROGUARD CONSERVADORAS - BRAINSTORMIA v9.9
# ============================================================================
# 📁 Arquivo: proguard-rules.pro
# 🎯 Objetivo: Minificação segura sem quebrar funcionalidades críticas
# 🚀 Implementação: Por fases (ver documentação)
# ============================================================================

# ============================================================================
# 🔧 CONFIGURAÇÕES BÁSICAS E ATRIBUTOS (CRÍTICO)
# ============================================================================

# Manter todos os atributos importantes
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations

# Configurações de otimização seguras
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# ============================================================================
# 🔐 GOOGLE SERVICES E PLAY SERVICES (CRÍTICO PARA LOGIN)
# ============================================================================

# Google Sign-In (PROTEÇÃO TOTAL)
-keep class com.google.android.gms.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.** { *; }
-keep class com.google.api.client.auth.** { *; }
-keep class com.google.auth.** { *; }
-keep class com.google.api.client.http.** { *; }
-keep class com.google.http.client.** { *; }

# Google Drive APIs
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.services.drive.model.** { *; }

# Suprimir warnings do Google Services
-dontwarn com.google.android.gms.**
-dontwarn com.google.api.client.**
-dontwarn com.google.auth.**
-dontwarn com.google.api.services.**

# ============================================================================
# 🔥 FIREBASE (TODAS AS FUNCIONALIDADES)
# ============================================================================

# Firebase Core
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.internal.firebase** { *; }

# Firebase Auth (CRÍTICO)
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.auth.internal.** { *; }

# Firebase Firestore
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.cloud.firestore.** { *; }

# Firebase Analytics
-keep class com.google.firebase.analytics.** { *; }

# Firebase Crashlytics
-keep class com.google.firebase.crashlytics.** { *; }

# Firebase Messaging
-keep class com.google.firebase.messaging.** { *; }

# Firebase Storage
-keep class com.google.firebase.storage.** { *; }

-dontwarn com.google.firebase.**

# ============================================================================
# 💰 ANDROID BILLING (CRÍTICO PARA MONETIZAÇÃO)
# ============================================================================

-keep class com.android.billingclient.** { *; }
-keep class com.android.billingclient.api.** { *; }
-keep class com.android.vending.billing.** { *; }
-dontwarn com.android.billingclient.**

# ============================================================================
# 🤖 APIS DE IA (OPENAI, ANTHROPIC, GOOGLE AI)
# ============================================================================

# OpenAI Client (PROTEÇÃO TOTAL)
-keep class com.aallam.openai.** { *; }
-keep class com.aallam.openai.api.** { *; }
-keep class com.aallam.openai.client.** { *; }
-dontwarn com.aallam.openai.**

# Google Generative AI
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# ============================================================================
# 🌐 NETWORKING E JSON
# ============================================================================

# Ktor Client (PROTEÇÃO COMPLETA)
-keep class io.ktor.** { *; }
-keep class io.ktor.client.** { *; }
-keep class io.ktor.serialization.** { *; }
-dontwarn io.ktor.**

# OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# JSON/GSON
-keep class com.google.gson.** { *; }
-keep class org.json.** { *; }
-dontwarn com.google.gson.**

# Kotlinx Serialization
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ============================================================================
# 📱 ANDROIDX E COMPOSE (BASE DO APP)
# ============================================================================

# Room Database (SUPER CRÍTICO)
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers @androidx.room.Entity class * {
    <init>(...);
}
-dontwarn androidx.room.**

# Lifecycle
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-dontwarn androidx.lifecycle.**

# Compose (PROTEÇÃO EXTENSIVA)
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.runtime.CompositionLocal** { *; }
-keep class androidx.compose.runtime.State { *; }
-keep class androidx.compose.runtime.MutableState { *; }
-dontwarn androidx.compose.**

# Navigation
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# Work Manager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# DataStore
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.core.** { *; }
-dontwarn androidx.datastore.**

# ============================================================================
# 📄 PDF E DOCUMENTOS
# ============================================================================

# Apache PDFBox (PROTEÇÃO TOTAL)
-keep class org.apache.pdfbox.** { *; }
-keep class com.tom.roush.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**
-dontwarn com.tom.roush.pdfbox.**

# Apache POI (Excel/Word)
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**

# ============================================================================
# 🖼️ ML KIT E IMAGENS
# ============================================================================

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Coil (Image Loading)
-keep class coil.** { *; }
-dontwarn coil.**

# ============================================================================
# 📝 MARKDOWN E UI
# ============================================================================

# Markwon
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ============================================================================
# 🏗️ SUAS CLASSES DE DOMÍNIO E MODELOS
# ============================================================================

# Data classes e modelos (PROTEÇÃO TOTAL)
-keep class com.ivip.brainstormia.data.** { *; }
-keep class com.ivip.brainstormia.model.** { *; }
-keep class com.ivip.brainstormia.entity.** { *; }
-keep class com.ivip.brainstormia.data.model.** { *; }
-keep class com.ivip.brainstormia.data.entities.** { *; }
-keep class com.ivip.brainstormia.data.dto.** { *; }
-keep class com.ivip.brainstormia.domain.model.** { *; }

# Repository patterns
-keep class com.ivip.brainstormia.data.repository.** { *; }
-keep class com.ivip.brainstormia.domain.repository.** { *; }

# Use cases (Clean Architecture)
-keep class com.ivip.brainstormia.domain.usecase.** { *; }

# ViewModels
-keep class com.ivip.brainstormia.viewmodel.** { *; }
-keep class com.ivip.brainstormia.ui.viewmodel.** { *; }

# States e Events (para Compose)
-keep class com.ivip.brainstormia.ui.state.** { *; }
-keep class com.ivip.brainstormia.ui.event.** { *; }

# Constantes importantes
-keep class com.ivip.brainstormia.util.Constants { *; }
-keep class com.ivip.brainstormia.core.constants.** { *; }

# BuildConfig (CRÍTICO - API KEYS)
-keep class com.ivip.brainstormia.BuildConfig {
    public static final java.lang.String OPENAI_API_KEY;
    public static final java.lang.String GOOGLE_API_KEY;
    public static final java.lang.String ANTHROPIC_API_KEY;
    public static final java.lang.String APPLICATION_ID;
    public static final java.lang.String VERSION_NAME;
    public static final int VERSION_CODE;
}

# ============================================================================
# 🔄 HTTP COMPONENTS E NETWORKING ADICIONAL
# ============================================================================

# Apache HTTP Components
-keep class org.apache.http.** { *; }
-keep class org.apache.httpcomponents.** { *; }
-dontwarn org.apache.http.**
-dontwarn org.apache.httpcomponents.**

# Para resolver conflitos META-INF
-dontwarn org.apache.http.client.methods.**
-dontwarn org.apache.http.impl.client.**

# Mozilla Public Suffix (do packaging)
-keep class mozilla.publicSuffix.** { *; }
-dontwarn mozilla.publicSuffix.**

# ============================================================================
# 🔧 REFLECTION E DYNAMIC CALLS
# ============================================================================

# Kotlin essentials
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.reflect.**

# Classes que usam reflection
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Enums (IMPORTANTE)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Callbacks e listeners
-keepclassmembers class * {
    public void on*(...);
    public void set*(...);
    public ** get*();
}

# ============================================================================
# 📱 MULTIDEX
# ============================================================================

-keep class androidx.multidex.** { *; }
-dontwarn androidx.multidex.**

# ============================================================================
# ⚠️ WARNINGS SUPPRESSION
# ============================================================================

-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.annotation.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.Platform$Java8
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.beans.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**

# ============================================================================
# 🔥 FIREBASE AUTH - PROTEÇÃO COMPLETA E ESPECÍFICA
# ============================================================================

# Firebase Auth - Todas as classes internas (CRÍTICO)
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.auth.internal.** { *; }
-keep class com.google.firebase.auth.api.** { *; }

# Firebase Auth - Callbacks e Listeners (PROBLEMA COMUM)
-keep class * extends com.google.firebase.auth.AuthResult { *; }
-keep class * implements com.google.firebase.auth.AuthResult { *; }
-keep interface com.google.firebase.auth.** { *; }

# Firebase Auth - Reflection e anotações
-keepclassmembers class com.google.firebase.auth.** {
    <init>(...);
    <methods>;
    <fields>;
}

# Firebase Auth - Task callbacks (MUITO IMPORTANTE)
-keep class com.google.android.gms.tasks.** { *; }
-keep interface com.google.android.gms.tasks.** { *; }
-keepclassmembers class * {
    *** onComplete(com.google.android.gms.tasks.Task);
    *** onSuccess(java.lang.Object);
    *** onFailure(java.lang.Exception);
}

# Firebase Auth - User info classes
-keep class com.google.firebase.auth.FirebaseUser { *; }
-keep class com.google.firebase.auth.UserInfo { *; }
-keep class com.google.firebase.auth.AdditionalUserInfo { *; }
-keep class com.google.firebase.auth.FirebaseUserMetadata { *; }

# Firebase Auth - Credential classes
-keep class com.google.firebase.auth.AuthCredential { *; }
-keep class com.google.firebase.auth.EmailAuthCredential { *; }
-keep class com.google.firebase.auth.EmailAuthProvider { *; }

# Firebase Auth - Exception classes (para error handling)
-keep class com.google.firebase.auth.FirebaseAuthException { *; }
-keep class com.google.firebase.auth.FirebaseAuthInvalidCredentialsException { *; }
-keep class com.google.firebase.auth.FirebaseAuthInvalidUserException { *; }
-keep class com.google.firebase.auth.FirebaseAuthUserCollisionException { *; }

# ============================================================================
# MANTER classes que interagem com Firebase Auth
# ============================================================================
-keepnames class com.ivip.brainstormia.**.*Repository { *; }
-keepnames class com.ivip.brainstormia.**.*Manager { *; }
-keepnames class com.ivip.brainstormia.**.*ViewModel { *; }
-keepnames class com.ivip.brainstormia.**.*AuthService { *; }
-keepnames class com.ivip.brainstormia.**.*LoginService { *; }
-keepnames class com.ivip.brainstormia.**.*FirebaseService { *; }

# MANTER classes que implementam interfaces Firebase/Google
-keepnames class * implements com.google.android.gms.tasks.OnCompleteListener { *; }
-keepnames class * implements com.google.android.gms.tasks.OnSuccessListener { *; }
-keepnames class * implements com.google.android.gms.tasks.OnFailureListener { *; }

# MANTER classes de autenticação e login
-keepnames class com.ivip.brainstormia.**.*Auth* { *; }
-keepnames class com.ivip.brainstormia.**.*Login* { *; }
-keepnames class com.ivip.brainstormia.**.*User* { *; }

# MANTER classes que podem usar reflection
-keepnames class com.ivip.brainstormia.data.** { *; }
-keepnames class com.ivip.brainstormia.domain.** { *; }

# Manter stack traces legíveis
-keepattributes SourceFile,LineNumberTable

# ============================================================================
# 🆘 FALLBACK RULES (DESCOMENTE SE ALGO QUEBRAR)
# ============================================================================

# Se Firebase Auth quebrar específicamente, descomente:
-keep class com.google.firebase.auth.internal.** { *; }

# Se Google Drive API quebrar, descomente:
# -keep class com.google.api.services.drive.model.** { *; }

# Se Billing quebrar, descomente:
# -keep class com.android.billingclient.api.** { *; }

# Se OpenAI quebrar, descomente:
# -keep class com.aallam.openai.api.** { *; }
# -keep class com.aallam.openai.client.** { *; }

# Se Compose quebrar, descomente:
# -keep class androidx.compose.runtime.internal.** { *; }

# Se Room quebrar, descomente:
# -keep class androidx.room.util.** { *; }

# ============================================================================
# 📊 FIM DAS REGRAS - BRAINSTORMIA v9.9
# ============================================================================
# ✅ Total de classes protegidas: ~50+ bibliotecas críticas
# 🎯 Foco: Funcionalidades críticas 100% protegidas
# 🚀 Implementação: Testar por fases conforme documentação
# ============================================================================

# ============================================================================
# PATCH URGENTE - ADICIONAR AO FINAL DO proguard-rules.pro
# ============================================================================
# 🚨 Correção para classes ausentes do PDFBox Android
# 📁 ADICIONAR estas linhas ao final do seu proguard-rules.pro atual
# ============================================================================

# ============================================================================
# 📄 CORREÇÃO ESPECÍFICA PDFBOX - CLASSES AUSENTES
# ============================================================================

# Classes JP2 (JPEG 2000) que são opcionais no PDFBox Android
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# Classes Java AWT que não existem no Android
-dontwarn java.awt.**
-dontwarn java.awt.geom.**
-dontwarn java.awt.image.**

# Outras classes desktop que o PDFBox referencia mas não usa no Android
-dontwarn javax.swing.**
-dontwarn javax.imageio.**
-dontwarn java.beans.**

# ============================================================================
# 📄 PDFBOX ANDROID - REGRAS ESPECÍFICAS ADICIONAIS
# ============================================================================

# GraphBuilder (usado pelo PDFBox)
-dontwarn com.graphbuilder.**
-keep class com.graphbuilder.** { *; }

# PDFBox Android - classes específicas que podem dar problema
-keep class com.tom.roush.pdfbox.filter.** { *; }
-keep class com.tom.roush.pdfbox.cos.** { *; }

# ============================================================================
# 🔧 OUTRAS CLASSES COMUNS QUE PODEM DAR PROBLEMA
# ============================================================================

# Apache Commons (usado por várias libs)
-dontwarn org.apache.commons.**

# SLF4J Logger
-dontwarn org.slf4j.**

# Bouncy Castle (criptografia)
-dontwarn org.bouncycastle.**

# ============================================================================
# 🆘 IGNORE CLASSES AUSENTES (ÚLTIMO RECURSO)
# ============================================================================

# Se ainda der problema, descomente estas linhas:
# -ignorewarnings
# -dontshrink