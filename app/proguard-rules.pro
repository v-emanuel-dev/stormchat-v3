# Mantém Google Sign‑In, Tasks e base de Play Services
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# Mantém APIs específicas de Sign‑In/Credentials
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.auth.api.identity.** { *; }

# Firebase Auth (métodos públicos) + anotações
-keep class com.google.firebase.auth.** { public *; }
-keepattributes *Annotation*, Signature, EnclosingMethod, InnerClasses
