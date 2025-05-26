package com.ivip.brainstormia

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.crashlytics.FirebaseCrashlytics

class AuthDiagnostics(private val context: Context) {
    private val tag = "googlelogin"
    private val crashlytics = FirebaseCrashlytics.getInstance()

    fun runDiagnostics() {
        Log.d(tag, "AuthDiagnostics: Iniciando diagnóstico de autenticação")
        crashlytics.log("Iniciando diagnóstico de autenticação")

        try {
            // Verificar Play Services
            checkPlayServices()

            // Verificar conectividade
            checkNetworkConnectivity()

            // Verificar assinaturas do aplicativo
            checkAppSignatures()

            Log.d(tag, "AuthDiagnostics: Diagnóstico de autenticação concluído com sucesso")
            crashlytics.log("Diagnóstico de autenticação concluído com sucesso")
        } catch (e: Exception) {
            Log.e(tag, "AuthDiagnostics: Erro durante diagnóstico de autenticação", e)
            crashlytics.log("Erro durante diagnóstico de autenticação: ${e.message}")
            crashlytics.recordException(e)
        }
    }

    private fun checkPlayServices() {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        val isAvailable = resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS

        if (isAvailable) {
            Log.d(tag, "AuthDiagnostics: Google Play Services disponível")
            crashlytics.log("Google Play Services disponível")
        } else {
            val errorMessage = availability.getErrorString(resultCode)
            Log.e(tag, "AuthDiagnostics: Google Play Services não disponível: $errorMessage (código $resultCode)")
            crashlytics.log("Google Play Services não disponível: $errorMessage")
            crashlytics.setCustomKey("play_services_error_code", resultCode)
            crashlytics.setCustomKey("play_services_error_message", errorMessage)
        }
    }

    private fun checkNetworkConnectivity() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        var isConnected = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            isConnected = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            isConnected = connectivityManager.activeNetworkInfo?.isConnected == true
        }

        Log.d(tag, "AuthDiagnostics: Conectividade de rede: $isConnected")
        crashlytics.log("Diagnóstico de conectividade: ${if (isConnected) "conectado" else "desconectado"}")
        crashlytics.setCustomKey("network_connected", isConnected)
    }

    private fun checkAppSignatures() {
        try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                // Use safe call for signingInfo
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            // The existing signatures?.firstOrNull() should correctly handle if 'signatures' is null
            // or if it's an empty array.
            signatures?.firstOrNull()?.let { signature ->
                val sha1 = getSignatureHash(signature, "SHA-1")
                Log.d(tag, "AuthDiagnostics: App SHA-1: $sha1")
                crashlytics.log("App SHA-1: $sha1")
                crashlytics.setCustomKey("app_sha1", sha1)
            }
        } catch (e: Exception) {
            Log.e(tag, "AuthDiagnostics: Falha ao verificar assinaturas", e)
            crashlytics.log("Falha ao verificar assinaturas do app: ${e.message}")
            crashlytics.recordException(e)
        }
    }

    private fun getSignatureHash(signature: android.content.pm.Signature, algorithm: String): String {
        val messageDigest = java.security.MessageDigest.getInstance(algorithm)
        messageDigest.update(signature.toByteArray())
        val digest = messageDigest.digest()

        return digest.joinToString(":") {
            String.format("%02x", it)
        }
    }
}