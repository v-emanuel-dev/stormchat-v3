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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel simplificado para gerenciar assinaturas premium
 * Usa o Backend como única fonte de verdade
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

    init {
        Log.d(TAG, "Inicializando BillingViewModel")
        viewModelScope.launch {
            // Carrega cache local imediatamente
            loadCachedPremiumStatus()
            // Conecta ao Google Play Billing
            connectToBillingService()
            // Verifica status atual
            checkPremiumStatus()
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
                        // Não definir como inicializado
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
     * Verifica status premium do usuário
     * ÚNICA fonte de verdade: Backend
     */
    fun checkPremiumStatus(forceRefresh: Boolean = false) {
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
                    Log.d(TAG, "Iniciando verificação premium sincronizada")
                    lastCheckTime = System.currentTimeMillis()

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
                        // 1. Verificar no Backend (fonte de verdade)
                        val isPremium = verifyWithBackend()

                        if (isPremium != null) {
                            // Backend respondeu com sucesso
                            updatePremiumStatus(isPremium.first, isPremium.second)

                            // Atualiza Firebase com dados do backend
                            updateFirebaseStatus(isPremium.first, isPremium.second)
                        } else {
                            // 2. Se backend falhou, tenta Firebase como fallback
                            Log.w(TAG, "Backend indisponível, usando Firebase como fallback")
                            val firebaseStatus = verifyWithFirebase()
                            updatePremiumStatus(firebaseStatus.first, firebaseStatus.second)
                        }

                    } catch (e: CancellationException) {
                        Log.d(TAG, "Verificação cancelada")
                        throw e // Re-throw para manter o cancelamento
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao verificar premium: ${e.message}", e)
                        // Em caso de erro, mantém o último estado conhecido
                    } finally {
                        _isPremiumLoading.value = false
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Job de verificação cancelado")
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
                updatePremiumStatus(response.hasAccess, response.subscriptionType)
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
        _isPremiumUser.value = isPremium
        _userPlanType.value = planType
        lastVerificationTime = System.currentTimeMillis()

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
                    checkPremiumStatus(forceRefresh = true)
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
     * Callback de compras atualizadas
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        Log.d(TAG, "onPurchasesUpdated: ${billingResult.responseCode}")

        try {
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    purchases?.forEach { purchase ->
                        Log.i(TAG, "✅ Compra bem-sucedida: ${purchase.orderId}")

                        // Verificar se a compra é válida
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
                    // Força verificação do status
                    checkPremiumStatus(forceRefresh = true)
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
     * Processa uma compra
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
                    val orderId = purchase.orderId
                    if (orderId.isNullOrBlank()) {
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
                            orderId = orderId
                        )
                    }

                    if (success == true) {
                        Log.i(TAG, "✅ Backend confirmou compra")
                        updatePremiumStatus(true, planType)

                        // Atualiza Firebase também
                        updateFirebaseStatus(true, planType, orderId, purchase.purchaseTime, productId)
                    } else {
                        Log.e(TAG, "❌ Backend rejeitou compra ou timeout")
                    }

                    // Sempre reconhecer a compra para evitar problemas
                    acknowledgePurchase(purchase)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erro ao processar compra: ${e.message}", e)
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
        val documentId = currentUser.uid  // ← MUDANÇA CRÍTICA

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = hashMapOf(
                    "isPremium" to isPremium,
                    "planType" to planType,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "userId" to currentUser.uid,
                    "userEmail" to (currentUser.email ?: "no-email")  // ← Salvar email como campo, não chave
                )

                // Adiciona dados de compra se disponíveis
                orderId?.let { data["orderId"] = it }
                purchaseTime?.let { data["purchaseTime"] = it }
                productId?.let { data["productId"] = it }

                Firebase.firestore
                    .collection("premium_users")
                    .document(documentId)  // ← Agora usa UID consistentemente
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
                .putString("cached_uid", currentUid)  // ✅ Salvar UID, não email
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
     * Força atualização do status
     */
    fun forceRefreshPremiumStatus() {
        checkPremiumStatus(forceRefresh = true)
    }

    /**
     * Chamado quando usuário faz login/logout
     */
    fun handleUserChanged() {
        Log.i(TAG, "Usuário mudou, verificando status...")

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            Log.i(TAG, "Usuário logado: UID=${currentUser.uid}, Email=${currentUser.email}")
            Log.i(TAG, "Nome: ${currentUser.displayName}")
            Log.i(TAG, "Provedores: ${currentUser.providerData.map { it.providerId }}")
            // Loga o token JWT
            viewModelScope.launch {
                try {
                    val tokenResult = currentUser.getIdToken(false).await()
                    val jwt = tokenResult?.token
                    if (jwt != null) {
                        Log.i(TAG, "JWT Token: $jwt")
                    } else {
                        Log.e(TAG, "Não foi possível obter o token JWT.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao obter token JWT", e)
                }
            }
        } else {
            Log.w(TAG, "Nenhum usuário autenticado.")
        }

        // Reseta cache para forçar nova verificação
        cachedUserId = null
        lastVerificationTime = 0

        // Chama checkPremiumStatus uma única vez com forceRefresh
        checkPremiumStatus(forceRefresh = true)
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
}