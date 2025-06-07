package com.ivip.brainstormia.billing

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.PurchasesResponseListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.ivip.brainstormia.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel simplificado para gerenciar assinaturas premium
 * Usa o Backend como única fonte de verdade com fallback para Google Play
 */
class BillingViewModel private constructor(application: Application) :
    AndroidViewModel(application), PurchasesUpdatedListener {

    private val TAG = "BillingViewModel"

    // Estados observáveis
    private val _isPremiumUser = MutableStateFlow(false)
    val isPremiumUser = _isPremiumUser.asStateFlow()

    private val _isPremiumLoading = MutableStateFlow(false)
    val isPremiumLoading = _isPremiumLoading.asStateFlow()

    private val _userPlanType = MutableStateFlow<String?>(null)
    val userPlanType = _userPlanType.asStateFlow()

    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products = _products.asStateFlow()

    private val _purchaseInProgress = MutableStateFlow(false)
    val purchaseInProgress = _purchaseInProgress.asStateFlow()

    // Billing Client
    private val billingClient = BillingClient.newBuilder(application)
        .enablePendingPurchases()
        .setListener(this)
        .build()

    // Controle de inicialização
    private val isInitialized = AtomicBoolean(false)
    private var currentCheckJob: Job? = null

    // Cache
    private var cachedUserId: String? = null
    private var lastVerificationTime = 0L
    private val CACHE_DURATION = 30_000L // 30 segundos

    // IDs dos produtos
    private val SUBSCRIPTION_IDS = listOf("mensal", "anual")
    private val INAPP_IDS = listOf("vital")

    // API Client
    private val apiClient = ApiClient()

    // Controle de verificação com debounce
    private var checkPremiumJob: Job? = null
    private val checkPremiumMutex = Mutex()
    private var lastCheckTime = 0L
    private val CHECK_DEBOUNCE_TIME = 1000L // 1 segundo de debounce

    // ✅ NOVA VARIÁVEL: Proteção temporária após compra (evita verificações automáticas)
    private var recentPurchaseProtection = 0L
    private val PURCHASE_PROTECTION_DURATION = 5 * 60 * 1000L // 5 minutos

    // Lista para compras pendentes que falharam na verificação
    private val pendingPurchaseSyncs = mutableListOf<PendingPurchaseSync>()

    init {
        Log.d(TAG, "Inicializando BillingViewModel")
        viewModelScope.launch {
            // Carrega cache local imediatamente
            loadCachedPremiumStatus()
            // Carrega compras pendentes de sincronização
            loadPendingSyncsFromPrefs()
            // Limpa compras pendentes antigas
            cleanupOldPendingPurchases()
            // Conecta ao Google Play Billing
            connectToBillingService()
            // Verifica status atual (segura)
            safeCheckPremiumStatus("init")
            // Iniciar verificação de compras pendentes
            retryPendingSyncs()
        }
    }

    /**
     * Conecta ao serviço de billing do Google Play
     */
    private fun connectToBillingService() {
        if (billingClient.isReady) {
            Log.d(TAG, "BillingClient já está conectado")
            queryProducts()
            return
        }

        Log.d(TAG, "Iniciando conexão com BillingClient...")

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        Log.i(TAG, "✅ Conectado ao BillingClient")
                        isInitialized.set(true)
                        queryProducts()
                    }
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                        Log.e(TAG, "❌ Billing indisponível no dispositivo")
                    }
                    BillingClient.BillingResponseCode.ERROR -> {
                        Log.e(TAG, "❌ Erro genérico no BillingClient")
                    }
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                        Log.e(TAG, "❌ Serviço Google Play indisponível")
                    }
                    else -> {
                        Log.e(TAG, "❌ Erro desconhecido ao conectar: ${billingResult.debugMessage}")
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "⚠️ Desconectado do BillingClient")
                isInitialized.set(false)

                // Tentar reconectar após um delay
                viewModelScope.launch {
                    delay(3000)
                    if (!billingClient.isReady) {
                        Log.d(TAG, "Tentando reconectar ao BillingClient...")
                        connectToBillingService()
                    }
                }
            }
        })
    }

    /**
     * Consulta produtos disponíveis
     */
    fun queryProducts() {
        if (!billingClient.isReady) {
            Log.w(TAG, "BillingClient não está pronto. Tentando conectar...")
            connectToBillingService()
            return
        }

        // Limpar lista de produtos antes de começar
        _products.value = emptyList()

        Log.d(TAG, "=== INICIANDO CONSULTA DE PRODUTOS ===")
        Log.d(TAG, "Assinaturas para consultar: $SUBSCRIPTION_IDS")
        Log.d(TAG, "Produtos in-app para consultar: $INAPP_IDS")

        // Consultas separadas por tipo de produto
        querySubscriptionProducts()
        queryInAppProducts()
    }

    /**
     * ✅ CORREÇÃO: Verificação premium com fallback inteligente
     */
    fun checkPremiumStatus(forceRefresh: Boolean = false, caller: String = "unknown") {
        Log.d(TAG, "🔍 checkPremiumStatus chamado por: $caller (forceRefresh=$forceRefresh)")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "Usuário não autenticado")
            resetPremiumStatus()
            return
        }

        // Cancela verificação anterior se existir
        checkPremiumJob?.cancel()

        // Inicia nova verificação com debounce
        checkPremiumJob = viewModelScope.launch {
            try {
                // Implementa debounce
                val now = System.currentTimeMillis()
                if (!forceRefresh && (now - lastCheckTime) < CHECK_DEBOUNCE_TIME) {
                    Log.d(TAG, "Verificação muito recente, aplicando debounce")
                    delay(CHECK_DEBOUNCE_TIME - (now - lastCheckTime))
                }

                // Garante que apenas uma verificação aconteça por vez
                checkPremiumMutex.withLock {
                    Log.d(TAG, "Iniciando verificação premium sincronizada (caller: $caller)")
                    lastCheckTime = System.currentTimeMillis()

                    // ✅ PROTEÇÃO TEMPORAL: Compra recente detectada
                    val protectionTimeLeft = (recentPurchaseProtection + PURCHASE_PROTECTION_DURATION) - now
                    if (protectionTimeLeft > 0) {
                        Log.w(TAG, "🛡️ PROTEÇÃO TEMPORAL ATIVA: Compra recente detectada. Mantendo status premium. (${protectionTimeLeft / 1000}s restantes)")
                        if (!_isPremiumUser.value) {
                            updatePremiumStatus(true, _userPlanType.value ?: "Premium")
                        }
                        return@withLock
                    }

                    // Se há compras pendentes salvas, não sobrescrever o status premium
                    if (hasRecentPendingPurchases() && _isPremiumUser.value && !forceRefresh) {
                        Log.w(TAG, "🛡️ PROTEÇÃO PENDENTE: Há compras pendentes. Não sobrescrevendo status premium.")
                        Log.d(TAG, "Status atual mantido: Premium=${_isPremiumUser.value}, Plano=${_userPlanType.value}")
                        return@withLock
                    }

                    // Verifica se mudou de usuário
                    if (currentUser.uid != cachedUserId) {
                        Log.i(TAG, "Usuário mudou: ${cachedUserId} -> ${currentUser.uid}")
                        cachedUserId = currentUser.uid
                        lastVerificationTime = 0
                    }

                    // Usa cache se ainda válido
                    if (!forceRefresh && isCacheValid()) {
                        Log.d(TAG, "Usando cache válido")
                        return@withLock
                    }

                    _isPremiumLoading.value = true

                    try {
                        // ✅ NOVA ESTRATÉGIA: Verificação com fallback inteligente
                        Log.d(TAG, "🔍 Iniciando verificação premium com fallback...")

                        // 1. Primeiro tentar o backend
                        val backendResult = verifyWithBackend()

                        if (backendResult != null) {
                            val (backendPremium, backendPlanType) = backendResult
                            Log.d(TAG, "✅ Backend respondeu: Premium=$backendPremium")

                            if (backendPremium) {
                                // Backend confirma premium - confiar nele
                                updatePremiumStatus(backendPremium, backendPlanType)
                                updateFirebaseStatus(backendPremium, backendPlanType)
                                return@withLock
                            } else {
                                // Backend nega premium - verificar Google Play antes de aceitar
                                Log.d(TAG, "❌ Backend nega premium, verificando Google Play como fallback...")

                                val googlePlayHasPremium = checkGooglePlayForActivePurchases()

                                if (googlePlayHasPremium.first) {
                                    Log.w(TAG, "⚠️ DISCREPÂNCIA DETECTADA:")
                                    Log.w(TAG, "   Backend: Premium=false")
                                    Log.w(TAG, "   Google Play: Premium=true (${googlePlayHasPremium.second})")
                                    Log.w(TAG, "🛡️ CONFIANDO NO GOOGLE PLAY e tentando ressincronizar...")

                                    // Confiar no Google Play e tentar ressincronizar
                                    updatePremiumStatus(true, googlePlayHasPremium.second)
                                    updateFirebaseStatus(true, googlePlayHasPremium.second)

                                    // Tentar ressincronizar em background
                                    viewModelScope.launch {
                                        processExistingPurchases()
                                    }
                                } else {
                                    // Ambos negam - usuário realmente não é premium
                                    Log.d(TAG, "✅ Backend e Google Play confirmam: usuário não é premium")
                                    updatePremiumStatus(false, null)
                                    updateFirebaseStatus(false, null)
                                }
                            }
                        } else {
                            // Backend indisponível - usar apenas Google Play
                            Log.w(TAG, "❌ Backend indisponível, usando apenas Google Play")

                            val googlePlayResult = checkGooglePlayForActivePurchases()
                            updatePremiumStatus(googlePlayResult.first, googlePlayResult.second)

                            if (googlePlayResult.first) {
                                updateFirebaseStatus(googlePlayResult.first, googlePlayResult.second)
                            }
                        }

                    } catch (e: CancellationException) {
                        Log.d(TAG, "Verificação cancelada")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao verificar premium: ${e.message}", e)

                        // ✅ FALLBACK DE EMERGÊNCIA: Em caso de erro, verificar Google Play
                        try {
                            Log.w(TAG, "🆘 Fallback de emergência: verificando apenas Google Play")
                            val googlePlayResult = checkGooglePlayForActivePurchases()
                            if (googlePlayResult.first) {
                                Log.i(TAG, "🛡️ Google Play confirma premium em fallback de emergência")
                                updatePremiumStatus(googlePlayResult.first, googlePlayResult.second)
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "Falha total na verificação premium: ${e2.message}")
                        }
                    } finally {
                        _isPremiumLoading.value = false
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Job de verificação cancelado")
            }
        }
    }

    /**
     * ✅ CORREÇÃO: Verifica se há compras ativas no Google Play - Separado em duas funções
     */
    private suspend fun checkGooglePlayForActivePurchases(): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                if (!billingClient.isReady) {
                    Log.w(TAG, "BillingClient não está pronto para verificação de compras ativas")
                    return@withContext Pair(false, null)
                }

                Log.d(TAG, "🔍 Verificando compras ativas no Google Play...")

                // Primeiro verificar assinaturas
                val subscriptionResult = checkSubscriptionPurchases()
                if (subscriptionResult.first) {
                    return@withContext subscriptionResult
                }

                // Se não encontrou assinaturas, verificar compras únicas
                val inAppResult = checkInAppPurchases()
                return@withContext inAppResult

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar compras ativas no Google Play: ${e.message}", e)
                return@withContext Pair(false, null)
            }
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Verifica apenas assinaturas
     */
    private suspend fun checkSubscriptionPurchases(): Pair<Boolean, String?> {
        val subscriptionsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        return suspendQueryPurchases(subscriptionsParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val activeSubs = purchasesList.filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                if (activeSubs.isNotEmpty()) {
                    val purchase = activeSubs.first()
                    val productId = purchase.products.firstOrNull()
                    val planType = determinePlanType(productId)

                    Log.i(TAG, "✅ Google Play: Assinatura ativa encontrada - $productId")
                    Log.d(TAG, "   Purchase Token: ${purchase.purchaseToken.take(20)}...")
                    Log.d(TAG, "   Order ID: ${purchase.orderId}")
                    Log.d(TAG, "   Purchase Time: ${purchase.purchaseTime}")
                    Log.d(TAG, "   Acknowledged: ${purchase.isAcknowledged}")

                    Pair(true, planType)
                } else {
                    Log.d(TAG, "❌ Google Play: Nenhuma assinatura ativa encontrada")
                    Pair(false, null)
                }
            } else {
                Log.w(TAG, "Erro ao consultar assinaturas: ${billingResult.debugMessage}")
                Pair(false, null)
            }
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Verifica apenas compras únicas (in-app)
     */
    private suspend fun checkInAppPurchases(): Pair<Boolean, String?> {
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        return suspendQueryPurchases(inAppParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val activeInApp = purchasesList.filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                if (activeInApp.isNotEmpty()) {
                    val purchase = activeInApp.first()
                    val productId = purchase.products.firstOrNull()
                    val planType = determinePlanType(productId)

                    Log.i(TAG, "✅ Google Play: Compra única ativa encontrada - $productId")
                    Log.d(TAG, "   Purchase Token: ${purchase.purchaseToken.take(20)}...")
                    Log.d(TAG, "   Order ID: ${purchase.orderId}")

                    Pair(true, planType)
                } else {
                    Log.d(TAG, "❌ Google Play: Nenhuma compra única ativa encontrada")
                    Pair(false, null)
                }
            } else {
                Log.w(TAG, "Erro ao consultar compras in-app: ${billingResult.debugMessage}")
                Pair(false, null)
            }
        }
    }

    /**
     * ✅ FUNÇÃO HELPER: Converte callback em suspend function
     */
    private suspend fun suspendQueryPurchases(
        params: QueryPurchasesParams,
        callback: (BillingResult, List<Purchase>) -> Pair<Boolean, String?>
    ): Pair<Boolean, String?> {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
                val result = callback(billingResult, purchasesList)
                continuation.resumeWith(Result.success(result))
            }
        }
    }

    private suspend fun verifyWithBackend(): Pair<Boolean, String?>? {
        return try {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return null

            // Obtém token JWT
            val tokenResult = withTimeoutOrNull(5000L) {
                currentUser.getIdToken(false).await()
            }

            if (tokenResult?.token == null) {
                Log.e(TAG, "Não foi possível obter token JWT")
                return null
            }

            // Valida no backend
            val response = withTimeoutOrNull(10000L) {
                apiClient.validatePremiumStatus(tokenResult.token!!)
            }

            if (response != null) {
                Log.i(TAG, "✅ Backend: Premium=${response.hasAccess}, Plano=${response.subscriptionType}")
                Pair(response.hasAccess, response.subscriptionType)
            } else {
                Log.e(TAG, "Timeout na validação do backend")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar com backend: ${e.message}")
            null
        }
    }

    /**
     * Verifica premium no Firebase (fallback)
     */
    private suspend fun verifyWithFirebase(): Pair<Boolean, String?> {
        return try {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return Pair(false, null)

            // ✅ CORREÇÃO: Usar UID ao invés de email
            val documentId = currentUser.uid  // ← MUDANÇA CRÍTICA

            val doc = Firebase.firestore
                .collection("premium_users")
                .document(documentId)  // ← Agora usa UID consistentemente
                .get()
                .await()

            val isPremium = doc.getBoolean("isPremium") ?: false
            val planType = doc.getString("planType")

            Log.i(TAG, "📱 Firebase: Premium=$isPremium, Plano=$planType (UID: $documentId)")
            Pair(isPremium, planType)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar Firebase: ${e.message}")
            Pair(false, null)
        }
    }

    /**
     * Atualiza status premium local
     */
    private fun updatePremiumStatus(isPremium: Boolean, planType: String?) {
        val wasNotPremium = !_isPremiumUser.value

        _isPremiumUser.value = isPremium
        _userPlanType.value = planType
        lastVerificationTime = System.currentTimeMillis()

        // ✅ ATIVAR PROTEÇÃO: Se mudou para premium, ativar proteção temporal
        if (isPremium && wasNotPremium) {
            recentPurchaseProtection = System.currentTimeMillis()
            Log.i(TAG, "🛡️ Proteção temporal ativada por ${PURCHASE_PROTECTION_DURATION / 60000} minutos")
        }

        // Salva cache local
        saveCacheToDisk(isPremium, planType)

        Log.i(TAG, "📊 Status atualizado: Premium=$isPremium, Plano=$planType")
    }

    /**
     * Inicia fluxo de compra
     */
    fun launchBillingFlow(activity: android.app.Activity, productDetails: ProductDetails) {
        Log.d(TAG, "Tentando iniciar fluxo de compra para: ${productDetails.productId}")

        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient não está pronto para compra")
            connectToBillingService()
            return
        }

        if (_purchaseInProgress.value) {
            Log.w(TAG, "Compra já em andamento, ignorando nova tentativa")
            return
        }

        try {
            _purchaseInProgress.value = true

            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .apply {
                        // Para assinaturas, precisa do offerToken
                        if (productDetails.productType == BillingClient.ProductType.SUBS) {
                            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
                            if (offerToken != null) {
                                setOfferToken(offerToken)
                                Log.d(TAG, "Usando offerToken para assinatura: ${offerToken.take(20)}...")
                            } else {
                                Log.e(TAG, "❌ OfferToken não encontrado para assinatura ${productDetails.productId}")
                                _purchaseInProgress.value = false
                                return
                            }
                        }
                    }
                    .build()
            )

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            Log.d(TAG, "Lançando fluxo de compra...")
            val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)

            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    Log.d(TAG, "✅ Fluxo de compra iniciado com sucesso")
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    Log.d(TAG, "❌ Usuário cancelou o fluxo de compra")
                    _purchaseInProgress.value = false
                }
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                    Log.e(TAG, "❌ Billing indisponível para compra")
                    _purchaseInProgress.value = false
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    Log.i(TAG, "⚠️ Item já pertence ao usuário")
                    _purchaseInProgress.value = false
                    // Força verificação do status
                    checkPremiumStatus(forceRefresh = true, caller = "launchBillingFlow-already_owned")
                }
                else -> {
                    Log.e(TAG, "❌ Erro ao iniciar compra: ${billingResult.debugMessage}")
                    _purchaseInProgress.value = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exceção ao lançar fluxo de compra", e)
            _purchaseInProgress.value = false
        }
    }

    /**
     * ✅ CORREÇÃO: Callback de compras atualizadas com processamento de compras existentes
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        Log.d(TAG, "onPurchasesUpdated: ${billingResult.responseCode}")

        try {
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    purchases?.forEach { purchase ->
                        Log.i(TAG, "✅ Compra bem-sucedida: ${purchase.orderId}")
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            handlePurchase(purchase)
                        } else {
                            Log.w(TAG, "⚠️ Compra com estado inválido: ${purchase.purchaseState}")
                        }
                    }
                }

                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    Log.i(TAG, "❌ Compra cancelada pelo usuário")
                }

                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    Log.i(TAG, "⚠️ Item já pertence ao usuário")

                    // ✅ CORREÇÃO CRÍTICA: Verificar e processar compras existentes
                    Log.i(TAG, "🔍 Processando compras existentes...")
                    viewModelScope.launch {
                        processExistingPurchases()
                    }

                    // Forçar verificação de status após processar compras existentes
                    checkPremiumStatus(forceRefresh = true, caller = "onPurchasesUpdated-already_owned")
                }

                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                    Log.e(TAG, "❌ Billing indisponível durante compra")
                }

                BillingClient.BillingResponseCode.ERROR -> {
                    Log.e(TAG, "❌ Erro genérico durante compra: ${billingResult.debugMessage}")
                }

                else -> {
                    Log.e(TAG, "❌ Erro desconhecido na compra: ${billingResult.debugMessage}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exceção em onPurchasesUpdated", e)
        } finally {
            _purchaseInProgress.value = false
        }
    }

    /**
     * ✅ CORREÇÃO: Processa compras existentes - Agora usa as funções separadas
     */
    private suspend fun processExistingPurchases() {
        if (!billingClient.isReady) {
            Log.w(TAG, "BillingClient não está pronto para verificar compras existentes")
            return
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.w(TAG, "Usuário não autenticado para processar compras existentes")
            return
        }

        try {
            Log.d(TAG, "🔍 Verificando compras existentes no Google Play...")

            // Primeiro verificar assinaturas
            val subscriptionResult = processExistingSubscriptions(currentUser)
            if (subscriptionResult) {
                Log.d(TAG, "✅ Processamento de compras existentes concluído (assinatura encontrada)")
                return
            }

            // Se não encontrou assinaturas, verificar compras únicas
            val inAppResult = processExistingInAppPurchases(currentUser)
            if (inAppResult) {
                Log.d(TAG, "✅ Processamento de compras existentes concluído (compra única encontrada)")
                return
            }

            Log.d(TAG, "✅ Processamento de compras existentes concluído (nenhuma compra ativa)")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar compras existentes", e)
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Processa assinaturas existentes
     */
    private suspend fun processExistingSubscriptions(currentUser: com.google.firebase.auth.FirebaseUser): Boolean {
        val subscriptionsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = suspendQueryPurchases(subscriptionsParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val activePurchases = purchasesList.filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                Log.d(TAG, "Encontradas ${activePurchases.size} assinaturas ativas")

                if (activePurchases.isNotEmpty()) {
                    val purchase = activePurchases.first()
                    val productId = purchase.products.firstOrNull()

                    if (productId != null) {
                        val planType = determinePlanType(productId)
                        Log.i(TAG, "📦 Processando assinatura existente: $productId -> $planType")

                        // Atualizar status imediatamente baseado no Google Play
                        updatePremiumStatus(true, planType)

                        // Tentar sincronizar com o backend em background
                        syncPurchaseWithBackend(currentUser, purchase, productId, planType)

                        // Reconhecer a compra se ainda não foi reconhecida
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }

                        return@suspendQueryPurchases Pair(true, planType)
                    }
                }
            }
            Pair(false, null)
        }

        return result.first
    }

    /**
     * ✅ NOVA FUNÇÃO: Processa compras únicas existentes
     */
    private suspend fun processExistingInAppPurchases(currentUser: com.google.firebase.auth.FirebaseUser): Boolean {
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val result = suspendQueryPurchases(inAppParams) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val activePurchases = purchasesList.filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                Log.d(TAG, "Encontradas ${activePurchases.size} compras únicas ativas")

                if (activePurchases.isNotEmpty()) {
                    val purchase = activePurchases.first()
                    val productId = purchase.products.firstOrNull()

                    if (productId != null) {
                        val planType = determinePlanType(productId)
                        Log.i(TAG, "📦 Processando compra única existente: $productId -> $planType")

                        // Atualizar status baseado no Google Play
                        updatePremiumStatus(true, planType)

                        // Tentar sincronizar com o backend em background
                        syncPurchaseWithBackend(currentUser, purchase, productId, planType)

                        // Reconhecer a compra se necessário
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }

                        return@suspendQueryPurchases Pair(true, planType)
                    }
                }
            }
            Pair(false, null)
        }

        return result.first
    }

    /**
     * ✅ NOVA FUNÇÃO: Sincroniza compra com backend (extraída para reutilização)
     */
    private fun syncPurchaseWithBackend(
        currentUser: com.google.firebase.auth.FirebaseUser,
        purchase: Purchase,
        productId: String,
        planType: String
    ) {
        viewModelScope.launch {
            try {
                val tokenResult = withTimeoutOrNull(5000L) {
                    currentUser.getIdToken(false).await()
                }

                if (tokenResult?.token != null) {
                    Log.d(TAG, "🔄 Tentando sincronizar compra existente com backend...")

                    val success = withTimeoutOrNull(10000L) {
                        apiClient.setPremiumStatus(
                            uid = currentUser.uid,
                            purchaseToken = purchase.purchaseToken,
                            productId = productId,
                            planType = planType,
                            userToken = tokenResult.token!!,
                            orderId = purchase.orderId ?: ""
                        )
                    }

                    if (success == true) {
                        Log.i(TAG, "✅ Compra existente sincronizada com backend")
                    } else {
                        Log.w(TAG, "❌ Falha ao sincronizar com backend, mas status já atualizado baseado no Google Play")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao sincronizar com backend: ${e.message}")
            }
        }
    }

    /**
     * ✅ CORREÇÃO: Verificar a função handlePurchase para usar purchase.orderId
     */
    private fun handlePurchase(purchase: Purchase) {
        try {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
                Log.w(TAG, "Compra não está no estado PURCHASED: ${purchase.purchaseState}")
                return
            }

            val productId = purchase.products.firstOrNull()
            if (productId.isNullOrBlank()) {
                Log.e(TAG, "❌ ProductId inválido na compra")
                acknowledgePurchase(purchase)
                return
            }

            val planType = determinePlanType(productId)
            Log.d(TAG, "Processando compra: produto=$productId, plano=$planType")

            viewModelScope.launch {
                try {
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    if (currentUser == null) {
                        Log.e(TAG, "❌ Usuário não autenticado durante processamento de compra")
                        acknowledgePurchase(purchase)
                        return@launch
                    }

                    // Validação do orderId
                    val purchaseOrderId = purchase.orderId
                    if (purchaseOrderId.isNullOrBlank()) {
                        Log.e(TAG, "❌ OrderId inválido!")
                        acknowledgePurchase(purchase)
                        return@launch
                    }

                    Log.d(TAG, "Obtendo token JWT para processar compra...")

                    // Obtém token JWT com timeout
                    val tokenResult = withTimeoutOrNull(10000L) {
                        currentUser.getIdToken(false).await()
                    }

                    if (tokenResult?.token == null) {
                        Log.e(TAG, "❌ Não foi possível obter token JWT")

                        // ✅ CORREÇÃO: Aplicar fallback para salvamento local
                        Log.w(TAG, "⚠️ Aplicando fallback local para compra confirmada (sem JWT)")
                        updatePremiumStatus(true, planType)
                        updateFirebaseStatus(true, planType, purchaseOrderId, purchase.purchaseTime, productId)

                        // Salvar compra para sincronização posterior
                        savePendingPurchaseSync(purchase.purchaseToken, productId, planType, purchaseOrderId)

                        acknowledgePurchase(purchase)
                        return@launch
                    }

                    val userToken = tokenResult.token!!

                    // Envia para backend
                    Log.i(TAG, "📤 Enviando compra para backend...")
                    val success = withTimeoutOrNull(15000L) {
                        apiClient.setPremiumStatus(
                            uid = currentUser.uid,
                            purchaseToken = purchase.purchaseToken,
                            productId = productId,
                            planType = planType,
                            userToken = userToken,
                            orderId = purchaseOrderId
                        )
                    }

                    if (success == true) {
                        Log.i(TAG, "✅ Backend confirmou compra")
                        updatePremiumStatus(true, planType)

                        // Atualiza Firebase também
                        updateFirebaseStatus(true, planType, purchaseOrderId, purchase.purchaseTime, productId)
                    } else {
                        Log.e(TAG, "❌ Backend rejeitou compra ou timeout")

                        // ✅ CORREÇÃO: Aplicar fallback local mesmo com falha do backend
                        Log.w(TAG, "⚠️ Aplicando fallback local para compra confirmada (backend falhou)")
                        updatePremiumStatus(true, planType)
                        updateFirebaseStatus(true, planType, purchaseOrderId, purchase.purchaseTime, productId)

                        // Salvar compra para sincronização posterior
                        savePendingPurchaseSync(purchase.purchaseToken, productId, planType, purchaseOrderId)
                    }

                    // Sempre reconhecer a compra para evitar problemas
                    acknowledgePurchase(purchase)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erro ao processar compra: ${e.message}", e)

                    // ✅ CORREÇÃO: Aplicar fallback local em caso de exceção
                    Log.w(TAG, "⚠️ Aplicando fallback local para compra confirmada (exception)")
                    updatePremiumStatus(true, planType)
                    updateFirebaseStatus(true, planType, purchase.orderId, purchase.purchaseTime, productId)

                    // Salvar compra para sincronização posterior
                    savePendingPurchaseSync(purchase.purchaseToken, productId, planType, purchase.orderId)

                    // Em caso de erro, ainda reconhece a compra para evitar problemas
                    acknowledgePurchase(purchase)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exceção em handlePurchase", e)
            acknowledgePurchase(purchase)
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Salvar compra para sincronização posterior
     */
    private fun savePendingPurchaseSync(
        purchaseToken: String,
        productId: String,
        planType: String,
        orderId: String?
    ) {
        try {
            // Criar objeto de sincronização pendente
            val pendingSync = PendingPurchaseSync(
                purchaseToken = purchaseToken,
                productId = productId,
                planType = planType,
                orderId = orderId,
                timestamp = System.currentTimeMillis(),
                attempts = 0
            )

            // Adicionar à lista em memória
            pendingPurchaseSyncs.add(pendingSync)

            // Salvar em SharedPreferences para persistência
            savePendingSyncToPrefs(pendingSync)

            Log.d(TAG, "💾 Compra salva para sincronização posterior: $productId")

            // Agendar tentativa após 1 minuto
            viewModelScope.launch {
                delay(60000) // 1 minuto
                retryPendingSyncs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar compra para sincronização posterior", e)
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Salvar sincronização pendente em SharedPreferences
     */
    private fun savePendingSyncToPrefs(pendingSync: PendingPurchaseSync) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("pending_syncs", Context.MODE_PRIVATE)
            val pendingSyncs = prefs.getStringSet("pending_purchase_syncs", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

            // Converter para JSON e salvar no conjunto
            val json = pendingSync.toJson()
            pendingSyncs.add(json)

            prefs.edit()
                .putStringSet("pending_purchase_syncs", pendingSyncs)
                .apply()

            Log.d(TAG, "💾 Compra pendente salva em SharedPreferences")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar em SharedPreferences", e)
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Carregar sincronizações pendentes de SharedPreferences
     */
    private fun loadPendingSyncsFromPrefs() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("pending_syncs", Context.MODE_PRIVATE)
            val pendingSyncs = prefs.getStringSet("pending_purchase_syncs", mutableSetOf()) ?: mutableSetOf()

            pendingPurchaseSyncs.clear()

            for (json in pendingSyncs) {
                try {
                    val sync = PendingPurchaseSync.fromJson(json)
                    pendingPurchaseSyncs.add(sync)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao deserializar compra pendente", e)
                }
            }

            Log.d(TAG, "📂 Carregadas ${pendingPurchaseSyncs.size} compras pendentes de sincronização")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar compras pendentes", e)
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Verificar se deve fazer verificação automática (respeita proteções)
     */
    fun shouldCheckPremiumStatus(): Boolean {
        val hasPendingPurchases = hasRecentPendingPurchases()
        val hasTemporalProtection = (System.currentTimeMillis() - recentPurchaseProtection) < PURCHASE_PROTECTION_DURATION
        val isPremium = _isPremiumUser.value

        return !(hasPendingPurchases && isPremium) && !(hasTemporalProtection && isPremium)
    }

    /**
     * ✅ NOVA FUNÇÃO: Verificação segura (só executa se permitido pelas proteções)
     */
    fun safeCheckPremiumStatus(caller: String = "safeCheck") {
        if (shouldCheckPremiumStatus()) {
            Log.d(TAG, "✅ Verificação segura permitida para: $caller")
            checkPremiumStatus(caller = caller)
        } else {
            Log.d(TAG, "🛡️ Verificação segura bloqueada para: $caller (proteções ativas)")
        }
    }

    private fun hasRecentPendingPurchases(): Boolean {
        if (pendingPurchaseSyncs.isEmpty()) {
            return false
        }

        val now = System.currentTimeMillis()
        val recentThreshold = 10 * 60 * 1000L // 10 minutos

        val recentPurchases = pendingPurchaseSyncs.any { pendingSync ->
            (now - pendingSync.timestamp) < recentThreshold
        }

        if (recentPurchases) {
            Log.d(TAG, "🛡️ Encontradas ${pendingPurchaseSyncs.size} compras pendentes recentes")
            pendingPurchaseSyncs.forEach { sync ->
                val ageMinutes = (now - sync.timestamp) / (60 * 1000)
                Log.d(TAG, "   - ${sync.productId}: ${ageMinutes}min atrás (tentativas: ${sync.attempts})")
            }
        }

        return recentPurchases
    }

    /**
     * ✅ NOVA FUNÇÃO: Limpar compras pendentes antigas (limpeza automática)
     */
    private fun cleanupOldPendingPurchases() {
        val now = System.currentTimeMillis()
        val maxAge = 24 * 60 * 60 * 1000L // 24 horas

        val sizeBefore = pendingPurchaseSyncs.size
        pendingPurchaseSyncs.removeAll { pendingSync ->
            val age = now - pendingSync.timestamp
            val isOld = age > maxAge

            if (isOld) {
                Log.d(TAG, "🗑️ Removendo compra pendente antiga: ${pendingSync.productId} (${age / (60 * 60 * 1000)}h)")
            }

            isOld
        }

        if (sizeBefore != pendingPurchaseSyncs.size) {
            updatePendingSyncsInPrefs()
            Log.d(TAG, "🧹 Limpeza: ${sizeBefore - pendingPurchaseSyncs.size} compras antigas removidas")
        }
    }

    fun retryPendingSyncs() {
        viewModelScope.launch {
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    Log.d(TAG, "Usuário não autenticado para sincronização pendente")
                    return@launch
                }

                if (pendingPurchaseSyncs.isEmpty()) {
                    Log.d(TAG, "Nenhuma compra pendente para sincronizar")
                    return@launch
                }

                Log.d(TAG, "🔄 Tentando sincronizar ${pendingPurchaseSyncs.size} compras pendentes...")

                // Tentar sincronizar cada compra
                val iterator = pendingPurchaseSyncs.iterator()
                while (iterator.hasNext()) {
                    val pendingSync = iterator.next()

                    // Incrementar tentativas
                    pendingSync.attempts++

                    // Obter token JWT
                    val tokenResult = withTimeoutOrNull(5000L) {
                        currentUser.getIdToken(false).await()
                    }

                    if (tokenResult?.token == null) {
                        Log.e(TAG, "Não foi possível obter token JWT para sincronização pendente")
                        continue
                    }

                    val syncOrderId = pendingSync.orderId ?: ""

                    // Tentar enviar para o backend
                    val success = withTimeoutOrNull(10000L) {
                        apiClient.setPremiumStatus(
                            uid = currentUser.uid,
                            purchaseToken = pendingSync.purchaseToken,
                            productId = pendingSync.productId,
                            planType = pendingSync.planType,
                            userToken = tokenResult.token!!,
                            orderId = syncOrderId
                        )
                    }

                    if (success == true) {
                        Log.i(TAG, "✅ Compra pendente sincronizada com sucesso: ${pendingSync.productId}")
                        iterator.remove()
                    } else {
                        Log.w(TAG, "❌ Falha na sincronização da compra pendente: ${pendingSync.productId} (tentativa ${pendingSync.attempts})")

                        // Remover após muitas tentativas falhadas
                        if (pendingSync.attempts >= 5) {
                            Log.w(TAG, "🗑️ Removendo compra pendente após 5 tentativas: ${pendingSync.productId}")
                            iterator.remove()
                        }
                    }
                }

                // Atualizar SharedPreferences
                updatePendingSyncsInPrefs()

                if (pendingPurchaseSyncs.isNotEmpty()) {
                    Log.d(TAG, "📅 Agendando próxima tentativa de sincronização em 5 minutos")
                    // Agendar próxima tentativa em 5 minutos
                    delay(300000) // 5 minutos
                    retryPendingSyncs()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar compras pendentes", e)
            }
        }
    }

    /**
     * ✅ NOVA FUNÇÃO: Atualizar sincronizações pendentes em SharedPreferences
     */
    private fun updatePendingSyncsInPrefs() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("pending_syncs", Context.MODE_PRIVATE)
            val newSet = mutableSetOf<String>()

            for (sync in pendingPurchaseSyncs) {
                newSet.add(sync.toJson())
            }

            prefs.edit()
                .putStringSet("pending_purchase_syncs", newSet)
                .apply()

            Log.d(TAG, "💾 Atualizado SharedPreferences com ${newSet.size} compras pendentes")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar SharedPreferences", e)
        }
    }

    /**
     * Reconhece compra no Google Play
     */
    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "✅ Compra reconhecida")
            } else {
                Log.e(TAG, "❌ Erro ao reconhecer: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Determina tipo de plano baseado no productId
     */
    private fun determinePlanType(productId: String?): String {
        return when (productId?.lowercase()) {
            "mensal" -> "Monthly plan"
            "anual" -> "Annual Plan"
            "vital", "vitalicio" -> "Lifetime"
            else -> "Premium"
        }
    }

    /**
     * Atualiza status no Firebase
     */
    private fun updateFirebaseStatus(
        isPremium: Boolean,
        planType: String?,
        orderId: String? = null,
        purchaseTime: Long? = null,
        productId: String? = null
    ) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return

        // ✅ CORREÇÃO: Usar UID ao invés de email
        val documentId = currentUser.uid

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = hashMapOf(
                    "isPremium" to isPremium,
                    "planType" to planType,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "userId" to currentUser.uid,
                    "userEmail" to (currentUser.email ?: "no-email")
                )

                // Adiciona dados de compra se disponíveis
                orderId?.let { data["orderId"] = it }
                purchaseTime?.let { data["purchaseTime"] = it }
                productId?.let { data["productId"] = it }

                Firebase.firestore
                    .collection("premium_users")
                    .document(documentId)
                    .set(data, SetOptions.merge())
                    .await()

                Log.i(TAG, "✅ Firebase atualizado para UID: $documentId")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar Firebase: ${e.message}")
            }
        }
    }

    /**
     * Carrega status premium do cache
     */
    private fun loadCachedPremiumStatus() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
            val cachedUid = prefs.getString("cached_uid", null)
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid

            // ✅ Verifica se é o mesmo usuário (por UID, não email)
            if (cachedUid == currentUid && currentUid != null) {
                val isPremium = prefs.getBoolean("is_premium", false)
                val planType = prefs.getString("plan_type", null)

                _isPremiumUser.value = isPremium
                _userPlanType.value = planType
                cachedUserId = cachedUid

                Log.d(TAG, "Cache carregado para UID $currentUid: Premium=$isPremium, Plano=$planType")
            } else {
                Log.d(TAG, "Cache invalidado: UID mudou de $cachedUid para $currentUid")
                // Limpar cache inválido
                clearCache()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar cache: ${e.message}")
            clearCache()
        }
    }

    /**
     * Salva status premium no cache
     */
    private fun saveCacheToDisk(isPremium: Boolean, planType: String?) {
        try {
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

            getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("cached_uid", currentUid)
                .putBoolean("is_premium", isPremium)
                .putString("plan_type", planType)
                .putLong("last_check", System.currentTimeMillis())
                .apply()

            Log.d(TAG, "Cache salvo para UID $currentUid: Premium=$isPremium")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar cache: ${e.message}")
        }
    }

    private fun clearCache() {
        try {
            getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            cachedUserId = null
            lastVerificationTime = 0

            Log.d(TAG, "Cache limpo")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar cache: ${e.message}")
        }
    }

    /**
     * Verifica se o cache ainda é válido
     */
    private fun isCacheValid(): Boolean {
        val now = System.currentTimeMillis()
        val cacheAge = now - lastVerificationTime
        val isValid = cacheAge < CACHE_DURATION && cachedUserId == FirebaseAuth.getInstance().currentUser?.uid

        if (isValid) {
            Log.d(TAG, "Cache válido: idade=${cacheAge}ms, usuário=${cachedUserId}")
        }

        return isValid
    }

    /**
     * Reseta status premium (logout)
     */
    private fun resetPremiumStatus() {
        _isPremiumUser.value = false
        _userPlanType.value = null
        cachedUserId = null
        lastVerificationTime = 0

        // Limpa cache
        getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Força atualização do status (ignora proteção de compras pendentes)
     */
    fun forceRefreshPremiumStatus() {
        checkPremiumStatus(forceRefresh = true, caller = "forceRefreshPremiumStatus")
    }

    /**
     * ✅ NOVA FUNÇÃO: Força verificação completa (ignora proteção e cache)
     */
    fun forceCompleteRefresh() {
        Log.i(TAG, "🔄 Forçando verificação completa (ignorando TODAS as proteções)")

        // Limpar TODAS as proteções
        recentPurchaseProtection = 0L
        pendingPurchaseSyncs.clear()
        lastVerificationTime = 0
        cachedUserId = null

        // Cancelar job anterior
        checkPremiumJob?.cancel()

        // Limpar cache em SharedPreferences
        try {
            getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            Log.d(TAG, "Cache SharedPreferences limpo")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar cache", e)
        }

        // Forçar verificação imediata
        checkPremiumStatus(forceRefresh = true, caller = "forceCompleteRefresh_aggressive")
    }
    fun forceBackendSync() {
        Log.i(TAG, "🔄 Forçando sincronização com backend")

        viewModelScope.launch {
            try {
                // Limpar proteções temporariamente
                val originalProtection = recentPurchaseProtection
                val originalPending = pendingPurchaseSyncs.toList()

                recentPurchaseProtection = 0L
                pendingPurchaseSyncs.clear()

                // Verificar com backend
                val backendResult = verifyWithBackend()

                if (backendResult != null) {
                    val (isPremium, planType) = backendResult
                    Log.i(TAG, "🔄 Backend sync result: Premium=$isPremium, Plan=$planType")

                    // Atualizar status FORÇADAMENTE
                    updatePremiumStatus(isPremium, planType)
                    updateFirebaseStatus(isPremium, planType)

                    Log.i(TAG, "✅ Status forçado para: Premium=${_isPremiumUser.value}")
                } else {
                    Log.w(TAG, "❌ Backend sync falhou")
                    // Restaurar proteções se necessário
                    recentPurchaseProtection = originalProtection
                    pendingPurchaseSyncs.addAll(originalPending)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro na sincronização forçada com backend", e)
            }
        }
    }

    /**
     * ✅ CORREÇÃO: Chamado quando usuário faz login/logout com sincronização melhorada
     */
    fun handleUserChanged() {
        Log.i(TAG, "Usuário mudou, verificando status...")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            Log.i(TAG, "Usuário logado: UID=${currentUser.uid}, Email=${currentUser.email}")
            Log.i(TAG, "Nome: ${currentUser.displayName}")
            Log.i(TAG, "Provedores: ${currentUser.providerData.map { it.providerId }}")

            // Reseta cache para forçar nova verificação
            cachedUserId = null
            lastVerificationTime = 0

            // Primeiro verifica o status premium
            checkPremiumStatus(forceRefresh = true, caller = "handleUserChanged")

            // ✅ NOVO: Depois sincroniza compras existentes com um delay
            viewModelScope.launch {
                delay(2000) // Aguarda verificação inicial

                // Se após a verificação inicial o usuário ainda não for premium,
                // mas o Google Play indica compras ativas, fazer sincronização
                if (!_isPremiumUser.value) {
                    Log.d(TAG, "🔍 Usuário não premium após verificação inicial, verificando compras do Google Play...")
                    processExistingPurchases()
                }
            }

            // Verificar compras pendentes
            retryPendingSyncs()
        } else {
            Log.w(TAG, "Nenhum usuário autenticado.")
        }
    }

    /**
     * Exibe o token JWT no logcat de forma confiável
     */
    fun logJwtToken() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.e("JWT_TOKEN", "❌ Usuário não autenticado")
            return
        }

        viewModelScope.launch {
            try {
                // Obter token (use false para token em cache, true para forçar renovação)
                val tokenResult = currentUser.getIdToken(false).await()
                val token = tokenResult?.token

                if (token == null) {
                    Log.e("JWT_TOKEN", "❌ Token nulo")
                    return@launch
                }

                // Informações básicas
                Log.e("JWT_TOKEN", "✅ TOKEN OBTIDO COM SUCESSO")
                Log.e("JWT_TOKEN", "User ID: ${currentUser.uid}")
                Log.e("JWT_TOKEN", "Email: ${currentUser.email}")
                Log.e("JWT_TOKEN", "Token Length: ${token.length}")

                // Dividir token em partes pequenas (200 caracteres) para evitar truncamento
                val chunkSize = 200
                val totalChunks = (token.length + chunkSize - 1) / chunkSize

                Log.e("JWT_TOKEN", "===== INICIO TOKEN (${totalChunks} partes) =====")

                for (i in 0 until token.length step chunkSize) {
                    val end = minOf(i + chunkSize, token.length)
                    val chunk = token.substring(i, end)
                    val partNumber = (i / chunkSize) + 1

                    // Usar TAG curto e Log.e para máxima visibilidade
                    Log.e("JWT_$partNumber", chunk)
                }

                Log.e("JWT_TOKEN", "===== FIM TOKEN =====")

                // OPCIONAL: Salvar em arquivo se necessário
                saveTokenToFile(token)

            } catch (e: Exception) {
                Log.e("JWT_TOKEN", "❌ Erro ao obter token: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Salva o token em um arquivo para análise posterior (OPCIONAL)
     */
    private fun saveTokenToFile(token: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val file = File(context.filesDir, "jwt_token_${System.currentTimeMillis()}.txt")
            file.writeText(token)
            Log.e("JWT_TOKEN", "✅ Token salvo em: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("JWT_TOKEN", "Erro ao salvar token em arquivo: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentCheckJob?.cancel()
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: BillingViewModel? = null

        fun getInstance(application: Application): BillingViewModel {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingViewModel(application).also { INSTANCE = it }
            }
        }
    }

    /**
     * Consulta produtos de assinatura (SUBS)
     */
    private fun querySubscriptionProducts() {
        if (SUBSCRIPTION_IDS.isEmpty()) {
            Log.d(TAG, "Nenhuma assinatura para consultar")
            return
        }

        try {
            val subscriptionProductList = SUBSCRIPTION_IDS.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(subscriptionProductList)
                .build()

            Log.d(TAG, "📦 Consultando ${subscriptionProductList.size} assinaturas...")

            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                handleProductQueryResult(billingResult, productDetailsList, "SUBS")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exceção ao consultar assinaturas", e)
        }
    }

    /**
     * Consulta produtos de compra única (INAPP)
     */
    private fun queryInAppProducts() {
        if (INAPP_IDS.isEmpty()) {
            Log.d(TAG, "Nenhum produto in-app para consultar")
            return
        }

        try {
            val inAppProductList = INAPP_IDS.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }

            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(inAppProductList)
                .build()

            Log.d(TAG, "🛒 Consultando ${inAppProductList.size} produtos in-app...")

            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                handleProductQueryResult(billingResult, productDetailsList, "INAPP")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exceção ao consultar produtos in-app", e)
        }
    }

    /**
     * Processa o resultado da consulta de produtos
     */
    private fun handleProductQueryResult(
        billingResult: BillingResult,
        productDetailsList: List<ProductDetails>?,
        productType: String
    ) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val validProducts = productDetailsList?.filter { it != null } ?: emptyList()

                Log.i(TAG, "✅ ${validProducts.size} produtos $productType carregados com sucesso")

                // Log detalhado dos produtos carregados
                validProducts.forEach { product ->
                    Log.d(TAG, "Produto $productType: ${product.productId} - ${product.name}")
                }

                // Adicionar produtos à lista existente ao invés de sobrescrever
                val currentProducts = _products.value.toMutableList()
                currentProducts.addAll(validProducts)
                _products.value = currentProducts

                Log.i(TAG, "📦 Total de produtos disponíveis: ${_products.value.size}")

            }
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                Log.e(TAG, "❌ Serviço indisponível ao carregar produtos $productType")
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                Log.e(TAG, "❌ Billing indisponível ao carregar produtos $productType")
            }
            else -> {
                Log.e(TAG, "❌ Erro ao carregar produtos $productType: ${billingResult.debugMessage}")
            }
        }
    }

    fun debugPremiumStatus() {
        Log.d(TAG, "=== DEBUG PREMIUM STATUS ===")
        Log.d(TAG, "Current Premium Status: ${_isPremiumUser.value}")
        Log.d(TAG, "Current Plan Type: ${_userPlanType.value}")
        Log.d(TAG, "Recent Purchase Protection: ${(System.currentTimeMillis() - recentPurchaseProtection) < PURCHASE_PROTECTION_DURATION}")
        Log.d(TAG, "Has Pending Purchases: ${hasRecentPendingPurchases()}")
        Log.d(TAG, "Should Check Premium: ${shouldCheckPremiumStatus()}")
        Log.d(TAG, "Cached User ID: $cachedUserId")
        Log.d(TAG, "Current User ID: ${FirebaseAuth.getInstance().currentUser?.uid}")
        Log.d(TAG, "========================")
    }

    /**
     * ✅ CLASSE: Modelo para armazenar compras pendentes de sincronização
     */
    data class PendingPurchaseSync(
        val purchaseToken: String,
        val productId: String,
        val planType: String,
        val orderId: String?,
        val timestamp: Long,
        var attempts: Int
    ) {
        fun toJson(): String {
            return "{\"purchaseToken\":\"$purchaseToken\",\"productId\":\"$productId\"," +
                    "\"planType\":\"$planType\",\"orderId\":${orderId?.let { "\"$it\"" } ?: "null"}," +
                    "\"timestamp\":$timestamp,\"attempts\":$attempts}"
        }

        companion object {
            fun fromJson(json: String): PendingPurchaseSync {
                // Implementação simplificada para conversão de JSON
                // Em um app real, use uma biblioteca como Gson ou Moshi
                val tokenMatch = "\"purchaseToken\":\"([^\"]+)\"".toRegex().find(json)
                val productIdMatch = "\"productId\":\"([^\"]+)\"".toRegex().find(json)
                val planTypeMatch = "\"planType\":\"([^\"]+)\"".toRegex().find(json)
                val orderIdMatch = "\"orderId\":\"([^\"]+)\"".toRegex().find(json)
                val timestampMatch = "\"timestamp\":([0-9]+)".toRegex().find(json)
                val attemptsMatch = "\"attempts\":([0-9]+)".toRegex().find(json)

                val token = tokenMatch?.groupValues?.get(1) ?: throw Exception("Token não encontrado")
                val productId = productIdMatch?.groupValues?.get(1) ?: throw Exception("ProductId não encontrado")
                val planType = planTypeMatch?.groupValues?.get(1) ?: throw Exception("PlanType não encontrado")
                val orderId = orderIdMatch?.groupValues?.get(1)
                val timestamp = timestampMatch?.groupValues?.get(1)?.toLong() ?: System.currentTimeMillis()
                val attempts = attemptsMatch?.groupValues?.get(1)?.toInt() ?: 0

                return PendingPurchaseSync(
                    purchaseToken = token,
                    productId = productId,
                    planType = planType,
                    orderId = orderId,
                    timestamp = timestamp,
                    attempts = attempts
                )
            }
        }
    }
}