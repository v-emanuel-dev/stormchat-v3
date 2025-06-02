package com.ivip.brainstormia

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
// import com.google.firebase.analytics.ktx.analytics // Not strictly needed if using getInstance
import com.google.firebase.crashlytics.FirebaseCrashlytics
// import com.google.firebase.ktx.Firebase // Not strictly needed if using getInstance
import com.google.firebase.messaging.FirebaseMessaging
import com.ivip.brainstormia.billing.BillingViewModel // Correct import for BillingViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

// Ensure ExportViewModel and ExportState are imported from their correct file, NOT defined here
// import com.ivip.brainstormia.ExportViewModel // This should be from the file where it's defined
// import com.ivip.brainstormia.ExportState // This should be from the file where it's defined

class BrainstormiaApplication : Application() {

    // ViewModels should be initialized here or lazily
    // Make sure they are public if accessed directly from activities/viewmodels,
    // or provide getter methods.
    // The 'internal set' is fine if they are only set within this module.

    // lateinit var chatViewModel: ChatViewModel // Keep if initialized in onCreate
    // lateinit var exportViewModel: ExportViewModel // Keep if initialized in onCreate

    // Correct lazy initialization for ViewModels if preferred
    val chatViewModel: ChatViewModel by lazy {
        Log.d("BrainstormiaApp", "Initializing ChatViewModel singleton")
        ChatViewModel(this)
    }

    val exportViewModel: ExportViewModel by lazy {
        Log.d("BrainstormiaApp", "Initializing ExportViewModel singleton")
        ExportViewModel(this)
    }

    val billingViewModel: BillingViewModel by lazy {
        Log.d("BrainstormiaApp", "Initializing BillingViewModel singleton")
        BillingViewModel.getInstance(this)
    }

    // The appDriveService from ExportViewModel is an internal detail of that ViewModel.
    // If BrainstormiaApplication needs direct access to DriveService functionality (it shouldn't for folder ID),
    // it would instantiate its own services.DriveService.
    // The error "Unresolved reference 'getFolderIdInternalOnly'" on line 149 of BrainstormiaApplication.kt
    // suggests 'appDriveService' was being accessed directly here, which is incorrect.
    // That logic belongs in ExportViewModel or BackupWorker.

    override fun onCreate() {
        super.onCreate()

        FirebaseApp.initializeApp(this)
        FirebaseAnalytics.getInstance(this)
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        createNotificationChannel()
        setupNotifications()
        PDFBoxResourceLoader.init(applicationContext)

        val loggedInUser = FirebaseAuth.getInstance().currentUser
        if (loggedInUser != null) {
            Log.d("BrainstormiaApp", "Usuário logado encontrado. Iniciando verificação premium...")
            // Use um pequeno atraso para garantir que todos os serviços estejam inicializados
            Handler(Looper.getMainLooper()).postDelayed({
                // 1. Verificação inicial
                billingViewModel.handleUserChanged()

                // 2. Segunda verificação após sistema estar estabilizado
                Handler(Looper.getMainLooper()).postDelayed({
                    billingViewModel.checkPremiumStatus(forceRefresh = true)

                    // 3. Verificação final para garantir
                    Handler(Looper.getMainLooper()).postDelayed({
                        billingViewModel.checkPremiumStatus(forceRefresh = true)
                    }, 1500)
                }, 1000)
            }, 500)
        }

        // NOVO: Limpeza do cache premium no inicio do app
        val sharedPrefs = getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val currentUserId = firebaseUser?.uid
        val cachedUser = sharedPrefs.getString("cached_user_id", null)

        // Se não há usuário logado ou o usuário mudou, limpar o estado premium
        if (firebaseUser == null || currentUserId != cachedUser) {
            Log.d("BrainstormiaApp", "Usuário atual ($currentUserId) diferente do cache ($cachedUser). Limpando cache de premium.")
            sharedPrefs.edit().apply {
                putBoolean("is_premium", false)
                remove("plan_type")
                putLong("last_updated_local", 0)
                apply()
            }
        }

        // Eagerly initialize ViewModels if not using by lazy for all
        // chatViewModel = ChatViewModel(this)
        // exportViewModel = ExportViewModel(this)
        // The by lazy properties will be initialized on first access.
        Log.d("BrainstormiaApp", "BrainstormiaApplication onCreate completed.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_backup_name) // Use getString
            val descriptionText = getString(R.string.notification_channel_backup_description) // Use getString
            val importance = NotificationManager.IMPORTANCE_HIGH // Or IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("BrainstormiaApp", "Notification channel created: $CHANNEL_ID")
        }
    }

    private fun setupNotifications() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d("FCM_TOKEN", "FCM Token obtained: $token")
                    val prefs = getSharedPreferences("brainstormia_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("fcm_token", token).apply()
                } else {
                    Log.e("FCM_TOKEN", "Failed to get FCM token: ${task.exception}")
                }
            }
        } catch (e: Exception) {
            Log.e("BrainstormiaApp", "Error setting up notifications", e)
        }
    }

    fun handleSubscriptionCancellationNotification() {
        Log.d("BrainstormiaApp", "Processing subscription cancellation notification")
    }

    companion object {
        const val CHANNEL_ID = "brainstormia_notification_channel"
        // NOTIFICATION_TYPE constants are not used in the provided code snippet, can be removed if not used elsewhere
        // const val NOTIFICATION_TYPE_CHAT = "chat"
        // const val NOTIFICATION_TYPE_SUBSCRIPTION = "subscription"
    }
}