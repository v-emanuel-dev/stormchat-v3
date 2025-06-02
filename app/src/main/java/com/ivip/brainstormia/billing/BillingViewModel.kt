package com.ivip.brainstormia.billing

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.ivip.brainstormia.api.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
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

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "✅ Conectado ao BillingClient")
                    queryProducts()
                    isInitialized.set(true)
                } else {
                    Log.e(TAG, "❌ Erro ao conectar: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "⚠️ Desconectado do BillingClient")
                isInitialized.set(false)
            }
        })
    }

    /**
     * Consulta produtos disponíveis
     */
    fun queryProducts() {
        val productList = mutableListOf<QueryProductDetailsParams.Product>()

        // Adiciona assinaturas
        SUBSCRIPTION_IDS.forEach { id ->
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }

        // Adiciona compras únicas
        INAPP_IDS.forEach { id ->
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _products.value = productDetailsList ?: emptyList()
                Log.i(TAG, "✅ ${productDetailsList?.size ?: 0} produtos carregados")
            } else {
                Log.e(TAG, "❌ Erro ao carregar produtos: ${billingResult.debugMessage}")
            }
        }
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

        // Verifica se mudou de usuário
        if (currentUser.uid != cachedUserId) {
            Log.i(TAG, "Usuário mudou: ${cachedUserId} -> ${currentUser.uid}")
            cachedUserId = currentUser.uid
            lastVerificationTime = 0
        }

        // Usa cache se ainda válido
        if (!forceRefresh && isCacheValid()) {
            Log.d(TAG, "Usando cache válido")
            return
        }

        // Cancela verificação anterior
        currentCheckJob?.cancel()

        // Inicia nova verificação
        currentCheckJob = viewModelScope.launch {
            try {
                _isPremiumLoading.value = true

                // 1. Verificar no Backend (fonte de verdade)
                val isPremium = verifyWithBackend()

                // 2. Se falhou, tenta Firebase como fallback
                if (isPremium == null) {
                    Log.w(TAG, "Backend indisponível, usando Firebase como fallback")
                    val firebaseStatus = verifyWithFirebase()
                    updatePremiumStatus(firebaseStatus.first, firebaseStatus.second)
                } else {
                    // Atualiza Firebase com dados do backend
                    updateFirebaseStatus(isPremium.first, isPremium.second)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar premium: ${e.message}", e)
                // Em caso de erro, mantém o último estado conhecido
            } finally {
                _isPremiumLoading.value = false
            }
        }
    }

    /**
     * Verifica premium no Backend (fonte de verdade)
     */
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
            val userEmail = currentUser.email ?: currentUser.uid

            val doc = Firebase.firestore
                .collection("premium_users")
                .document(userEmail)
                .get()
                .await()

            val isPremium = doc.getBoolean("isPremium") ?: false
            val planType = doc.getString("planType")

            Log.i(TAG, "📱 Firebase: Premium=$isPremium, Plano=$planType")
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
        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient não está pronto")
            connectToBillingService()
            return
        }

        if (_purchaseInProgress.value) {
            Log.w(TAG, "Compra já em andamento")
            return
        }

        _purchaseInProgress.value = true

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .apply {
                    // Para assinaturas, precisa do offerToken
                    if (productDetails.productType == BillingClient.ProductType.SUBS) {
                        productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let {
                            setOfferToken(it)
                        }
                    }
                }
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)

        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Erro ao iniciar compra: ${billingResult.debugMessage}")
            _purchaseInProgress.value = false
        }
    }

    /**
     * Callback de compras atualizadas
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        _purchaseInProgress.value = false

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    Log.i(TAG, "✅ Compra bem-sucedida: ${purchase.orderId}")
                    handlePurchase(purchase)
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

            else -> {
                Log.e(TAG, "❌ Erro na compra: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Processa uma compra
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return
        }

        val productId = purchase.products.firstOrNull() ?: return
        val planType = determinePlanType(productId)

        viewModelScope.launch {
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    Log.e(TAG, "Usuário não autenticado")
                    return@launch
                }

                // Validação do orderId
                val orderId = purchase.orderId
                if (orderId.isNullOrBlank()) {
                    Log.e(TAG, "❌ OrderId inválido!")
                    acknowledgePurchase(purchase)
                    return@launch
                }

                // Obtém token JWT
                val tokenResult = currentUser.getIdToken(false).await()
                val userToken = tokenResult.token ?: throw Exception("Token JWT nulo")

                // Envia para backend
                Log.i(TAG, "📤 Enviando compra para backend...")
                val success = apiClient.setPremiumStatus(
                    uid = currentUser.uid,
                    purchaseToken = purchase.purchaseToken,
                    productId = productId,
                    planType = planType,
                    userToken = userToken,
                    orderId = orderId
                )

                if (success) {
                    Log.i(TAG, "✅ Backend confirmou compra")
                    updatePremiumStatus(true, planType)

                    // Atualiza Firebase também
                    updateFirebaseStatus(true, planType, orderId, purchase.purchaseTime, productId)

                    // Reconhece a compra
                    acknowledgePurchase(purchase)
                } else {
                    Log.e(TAG, "❌ Backend rejeitou compra")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar compra: ${e.message}", e)
                // Em caso de erro, ainda reconhece a compra para evitar problemas
                acknowledgePurchase(purchase)
            }
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
        val userEmail = currentUser.email ?: currentUser.uid

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = hashMapOf(
                    "isPremium" to isPremium,
                    "planType" to planType,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "userId" to currentUser.uid,
                    "userEmail" to userEmail
                )

                // Adiciona dados de compra se disponíveis
                orderId?.let { data["orderId"] = it }
                purchaseTime?.let { data["purchaseTime"] = it }
                productId?.let { data["productId"] = it }

                Firebase.firestore
                    .collection("premium_users")
                    .document(userEmail)
                    .set(data, SetOptions.merge())
                    .await()

                Log.i(TAG, "✅ Firebase atualizado")

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

            // Verifica se é o mesmo usuário
            if (cachedUid == currentUid && currentUid != null) {
                val isPremium = prefs.getBoolean("is_premium", false)
                val planType = prefs.getString("plan_type", null)

                _isPremiumUser.value = isPremium
                _userPlanType.value = planType
                cachedUserId = cachedUid

                Log.d(TAG, "Cache carregado: Premium=$isPremium, Plano=$planType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar cache: ${e.message}")
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

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar cache: ${e.message}")
        }
    }

    /**
     * Verifica se o cache ainda é válido
     */
    private fun isCacheValid(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastVerificationTime) < CACHE_DURATION
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
        resetPremiumStatus()
        checkPremiumStatus(forceRefresh = true)
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
}