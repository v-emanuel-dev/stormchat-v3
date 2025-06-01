package com.ivip.brainstormia.billing

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.android.billingclient.api.queryPurchasesAsync
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import com.ivip.brainstormia.api.ApiClient

/**
 * ViewModel que gerencia a integração com Google Play Billing para assinaturas e compras.
 * Esta classe é desenhada para funcionar como um Singleton via BrainstormiaApplication.
 */
class BillingViewModel private constructor(application: Application) : AndroidViewModel(application), PurchasesUpdatedListener {
    private val TAG = "BillingViewModel"

    // Client de faturamento do Google Play
    private val billingClient = BillingClient.newBuilder(application)
        .enablePendingPurchases()
        .setListener(this)
        .build()

    // Estado da verificação de premium
    private val _isPremiumUser = MutableStateFlow(false)
    val isPremiumUser = _isPremiumUser.asStateFlow()

    private val _isPremiumLoading = MutableStateFlow(false)
    val isPremiumLoading = _isPremiumLoading.asStateFlow()

    private val _userPlanType = MutableStateFlow<String?>(null)
    val userPlanType = _userPlanType.asStateFlow()

    // Lista de produtos disponíveis
    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products = _products.asStateFlow()

    // Estado da compra em andamento
    private val _purchaseInProgress = MutableStateFlow(false)
    val purchaseInProgress = _purchaseInProgress.asStateFlow()

    // Controle de tentativas de conexão
    private val _connectionAttempts = MutableStateFlow(0)
    private val MAX_CONNECTION_ATTEMPTS = 5

    // Reconexão
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    // Controle de verificação ativa
    private var activeCheckJob: Job? = null
    private val isInitializing = AtomicBoolean(true)

    // IDs de produtos
    private val SUBSCRIPTION_IDS = listOf("mensal", "anual")
    private val INAPP_IDS = listOf("vital")

    // Cache de verificação - MODIFICADO
    private var cachedUserId: String? = null // Novo: armazena o ID do usuário em cache
    private var lastVerifiedTimestamp = 0L
    private val CACHE_VALIDITY_PERIOD = 15000L // Reduzido de 30s para 15s para atualizações mais frequentes
    private val isInitialCheckComplete = AtomicBoolean(false)

    // Produto atual sendo comprado
    private var currentProductDetails: ProductDetails? = null

    init {
        Log.d(TAG, "Inicializando BillingViewModel (Singleton)")
        // Inicialização realizada em duas fases para evitar race conditions
        viewModelScope.launch {
            loadPremiumStatusLocally() // Primeiro carrega do cache local para resposta imediata
            startBillingConnection()   // Depois conecta ao serviço de billing
            isInitializing.set(false)  // Marca inicialização como concluída
        }
    }

    /**
     * Inicia ou restaura a conexão com o serviço de faturamento do Google Play.
     */
    private fun startBillingConnection() {
        // Se já está conectado, apenas consulta produtos e verificar status
        if (billingClient.isReady) {
            Log.i(TAG, "BillingClient já está pronto. Consultando produtos e compras.")
            queryAvailableProducts()
            if (!isInitialCheckComplete.get()) {
                checkUserSubscription() // Fonte da verdade
                isInitialCheckComplete.set(true)
            }
            return
        }

        // Verifica se excedeu tentativas máximas
        if (_connectionAttempts.value >= MAX_CONNECTION_ATTEMPTS && !_purchaseInProgress.value) {
            Log.w(TAG, "Máximo de tentativas de conexão atingido (${MAX_CONNECTION_ATTEMPTS}).")
            _isPremiumLoading.value = false // Importante: Encerrar o loading
            return
        }

        // Incrementa contador de tentativas
        _connectionAttempts.value++

        Log.i(TAG, "Iniciando conexão com BillingClient (tentativa: ${_connectionAttempts.value})")

        // Inicia a conexão com o serviço de faturamento
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "onBillingSetupFinished - Resposta: ${billingResult.responseCode}, Mensagem: ${billingResult.debugMessage}")

                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Conectado com sucesso ao BillingClient.")
                    _connectionAttempts.value = 0
                    queryAvailableProducts()

                    if (!isInitialCheckComplete.get()) {
                        checkUserSubscription() // Fonte da verdade
                        isInitialCheckComplete.set(true)
                    }
                } else {
                    Log.e(TAG, "Erro na conexão com BillingClient: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                    _isPremiumLoading.value = false // Importante: Encerrar o loading em caso de erro

                    if (_connectionAttempts.value < MAX_CONNECTION_ATTEMPTS) {
                        scheduleReconnection()
                    } else {
                        Log.w(TAG, "Máximo de tentativas de reconexão atingido.")
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Conexão com BillingClient perdida.")
                scheduleReconnection()
            }
        })
    }

    /**
     * Agenda uma reconexão com backoff exponencial para evitar sobrecarga.
     */
    private fun scheduleReconnection() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }

        if (_connectionAttempts.value >= MAX_CONNECTION_ATTEMPTS && !_purchaseInProgress.value) {
            Log.w(TAG, "Não agendando reconexão: máximo de tentativas.")
            _isPremiumLoading.value = false // Importante: Encerrar o loading
            return
        }

        // Calcula delay com backoff exponencial
        val delayMs = 1000L * (2.0.pow(_connectionAttempts.value.coerceAtMost(6) - 1)).toLong()
        Log.d(TAG, "Agendando reconexão em $delayMs ms")

        reconnectRunnable = Runnable {
            if (!billingClient.isReady) startBillingConnection()
        }

        handler.postDelayed(reconnectRunnable!!, delayMs)
    }

    /**
     * Consulta os produtos disponíveis no Google Play.
     */
    fun queryAvailableProducts() {
        if (!billingClient.isReady) {
            Log.w(TAG, "queryAvailableProducts: BillingClient não está pronto.")
            startBillingConnection()
            return
        }

        Log.i(TAG, "queryAvailableProducts: Consultando produtos...")
        val combinedProductList = mutableListOf<ProductDetails>()

        // Consulta assinaturas
        val subscriptionProductQueryList = SUBSCRIPTION_IDS.mapNotNull { id ->
            if (id.isBlank()) null
            else QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        if (subscriptionProductQueryList.isNotEmpty()) {
            val subsParams = QueryProductDetailsParams.newBuilder()
                .setProductList(subscriptionProductQueryList)
                .build()

            Log.d(TAG, "Consultando SUBS com IDs: ${SUBSCRIPTION_IDS.joinToString()}")

            billingClient.queryProductDetailsAsync(subsParams) { resSubs, subsList ->
                Log.d(TAG, "queryProductDetailsAsync SUBS CALLBACK: Resposta=${resSubs.responseCode}, Tamanho=${subsList?.size ?: "null"}")

                if (resSubs.responseCode == BillingClient.BillingResponseCode.OK && subsList != null) {
                    combinedProductList.addAll(subsList)
                } else {
                    Log.e(TAG, "Erro SUBS: ${resSubs.responseCode} - ${resSubs.debugMessage}")
                }

                queryInAppProducts(combinedProductList)
            }
        } else {
            Log.d(TAG, "Nenhum ID SUBS. Prosseguindo para INAPP.")
            queryInAppProducts(combinedProductList)
        }
    }

    /**
     * Consulta produtos de compra única.
     */
    private fun queryInAppProducts(currentCombinedList: MutableList<ProductDetails>) {
        val inAppProductQueryList = INAPP_IDS.mapNotNull { id ->
            if (id.isBlank()) null
            else QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        if (inAppProductQueryList.isNotEmpty()) {
            val inAppParams = QueryProductDetailsParams.newBuilder()
                .setProductList(inAppProductQueryList)
                .build()

            Log.d(TAG, "Consultando INAPP com IDs: ${INAPP_IDS.joinToString()}")

            billingClient.queryProductDetailsAsync(inAppParams) { resInApp, inAppList ->
                Log.d(TAG, "queryProductDetailsAsync INAPP CALLBACK: Resposta=${resInApp.responseCode}, Tamanho=${inAppList?.size ?: "null"}")

                if (resInApp.responseCode == BillingClient.BillingResponseCode.OK && inAppList != null) {
                    currentCombinedList.addAll(inAppList)
                } else {
                    Log.e(TAG, "Erro INAPP: ${resInApp.responseCode} - ${resInApp.debugMessage}")
                }

                processFinalProductList(currentCombinedList)
            }
        } else {
            Log.d(TAG, "Nenhum ID INAPP. Processando lista atual.")
            processFinalProductList(currentCombinedList)
        }
    }

    /**
     * Processa a lista final de produtos e atualiza o estado.
     */
    private fun processFinalProductList(finalList: List<ProductDetails>) {
        if (finalList.isNotEmpty()) {
            Log.i(TAG, "Processando lista final (${finalList.size}):")

            finalList.forEach { p ->
                val price = if (p.productType == BillingClient.ProductType.SUBS)
                    p.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                else
                    p.oneTimePurchaseOfferDetails?.formattedPrice

                Log.i(TAG, "  - ID: ${p.productId}, Nome: ${p.name}, Tipo: ${p.productType}, Preço: $price")
            }

            // Ordenar produtos para exibição
            _products.value = finalList.sortedBy { prod ->
                when {
                    prod.productId.contains("mensal") -> 1
                    prod.productId.contains("anual") -> 2
                    prod.productId.equals("vital", ignoreCase = true) -> 3
                    else -> 4
                }
            }
        } else {
            Log.w(TAG, "Lista final de produtos vazia.")
            _products.value = emptyList()
        }
    }

    /**
     * Tenta reconexão manual com o serviço de faturamento.
     */
    fun retryConnection() {
        Log.i(TAG, "Tentativa manual de reconexão e recarga de produtos solicitada.")
        _connectionAttempts.value = 0
        handler.removeCallbacksAndMessages(null)
        startBillingConnection()
    }

    /**
     * Inicia o fluxo de compra para um produto.
     */
    fun launchBillingFlow(activity: android.app.Activity, productDetails: ProductDetails) {
        if (!billingClient.isReady) {
            Log.e(TAG, "launchBillingFlow: BillingClient não está pronto.")
            _purchaseInProgress.value = false
            retryConnection()
            return
        }

        if (_purchaseInProgress.value) {
            Log.w(TAG, "launchBillingFlow: Compra já em andamento.")
            return
        }

        // Armazenar o produto atual sendo comprado
        currentProductDetails = productDetails

        _purchaseInProgress.value = true
        Log.i(TAG, "Iniciando fluxo de compra para ${productDetails.productId}")

        val productDetailsParamsList = mutableListOf<BillingFlowParams.ProductDetailsParams>()

        if (productDetails.productType == BillingClient.ProductType.SUBS) {
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken

            if (offerToken.isNullOrBlank()) {
                Log.e(TAG, "Erro CRÍTICO: offerToken não encontrado para ${productDetails.productId}. A compra não pode prosseguir.")
                _purchaseInProgress.value = false
                currentProductDetails = null // Limpar referência ao produto
                return
            }

            Log.d(TAG, "Usando offerToken: $offerToken para ${productDetails.productId}")

            productDetailsParamsList.add(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            )
        } else { // INAPP
            productDetailsParamsList.add(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .build()
            )
        }

        if (productDetailsParamsList.isEmpty()) {
            Log.e(TAG, "Nenhum ProductDetailsParams construído para ${productDetails.productId}.")
            _purchaseInProgress.value = false
            currentProductDetails = null // Limpar referência ao produto
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)

        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Erro ao iniciar fluxo de cobrança para ${productDetails.productId}: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            _purchaseInProgress.value = false
            currentProductDetails = null // Limpar referência ao produto
        } else {
            Log.i(TAG, "Fluxo de cobrança iniciado com sucesso para ${productDetails.productId}")
        }
    }

    /**
     * Callback recebido quando há atualização de compras pelo Google Play.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        Log.i(TAG, "onPurchasesUpdated: Código de Resposta=${billingResult.responseCode}, Mensagem=${billingResult.debugMessage}")
        _purchaseInProgress.value = false

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    Log.i(TAG, "Compras atualizadas com sucesso (${purchases.size} itens). Processando...")

                    purchases.forEach { purchase ->
                        Log.d(TAG, "Detalhes da compra: OrderId=${purchase.orderId}, Produtos=${purchase.products.joinToString()}, Estado=${purchase.purchaseState}, Token=${purchase.purchaseToken}, É Reconhecida=${purchase.isAcknowledged}")
                        handlePurchase(purchase)
                    }

                    // Após processar uma compra bem-sucedida, força atualização imediata do cache
                    lastVerifiedTimestamp = 0
                    checkUserSubscription()
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Compra cancelada pelo usuário.")
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "Usuário já possui este item/assinatura. Verificando e resolvendo status...")

                // Consulta especificamente as compras atuais para ver se realmente existe
                viewModelScope.launch {
                    try {
                        val productId = currentProductDetails?.productId

                        if (productId != null) {
                            Log.d(TAG, "Verificando compra existente para produto: $productId")

                            // Verificar SUBS
                            val subsResult = billingClient.queryPurchasesAsync(
                                QueryPurchasesParams.newBuilder()
                                    .setProductType(BillingClient.ProductType.SUBS)
                                    .build()
                            )

                            // Obter o ID do usuário atual
                            val currentUser = FirebaseAuth.getInstance().currentUser
                            val userId = currentUser?.uid
                            val userEmail = currentUser?.email

                            Log.d(TAG, "Verificando para usuário: $userEmail (ID: $userId)")

                            // Log de todas as compras encontradas para diagnóstico
                            subsResult.purchasesList.forEach { purchase ->
                                Log.d(TAG, "Compra encontrada: ${purchase.orderId}, produtos: ${purchase.products.joinToString()}, " +
                                        "token: ${purchase.purchaseToken}, accountIdentifiers: ${purchase.accountIdentifiers}, " +
                                        "package: ${purchase.packageName}")
                            }

                            // Verificar se existe alguma assinatura ativa do item sendo comprado E com dados de compra válidos
                            val hasActiveSub = subsResult.purchasesList.any { purchase ->
                                purchase.products.contains(productId) &&
                                        isSubscriptionActive(purchase) &&
                                        (purchase.accountIdentifiers?.obfuscatedAccountId == userId || // Verificar se é do mesmo usuário
                                                purchase.accountIdentifiers?.obfuscatedProfileId == userEmail)
                            }

                            if (hasActiveSub) {
                                val purchase = subsResult.purchasesList.first {
                                    it.products.contains(productId) && isSubscriptionActive(it)
                                }

                                Log.i(TAG, "Assinatura $productId realmente encontrada e ativa para este usuário.")
                                handlePurchase(purchase)
                            } else {
                                Log.w(TAG, "ALERTA: Código ITEM_ALREADY_OWNED recebido, mas não encontramos assinatura ativa" +
                                        " para $productId que pertença ao usuário $userEmail. Possível erro no Google Play Billing!")

                                // Você pode notificar o usuário ou tentar uma abordagem alternativa aqui
                                // Talvez verifique diretamente no Firebase se o usuário tem um registro de compra
                            }
                        } else {
                            Log.w(TAG, "Código ITEM_ALREADY_OWNED recebido, mas não há produto atual armazenado.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao verificar assinaturas após ITEM_ALREADY_OWNED: ${e.message}", e)
                    }
                }

                // Força atualização imediata do cache
                lastVerifiedTimestamp = 0
                checkUserSubscription()
            }

            else -> {
                Log.e(TAG, "Erro na atualização de compras: ${billingResult.responseCode} - ${billingResult.debugMessage}")

                if (billingResult.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ||
                    billingResult.responseCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE) {
                    scheduleReconnection()
                }
            }
        }

        // Limpar a referência ao produto após o processamento
        currentProductDetails = null
    }

    /**
     * Processa uma compra recebida.
     */
    private fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "Processando compra: ${purchase.products.joinToString()}, estado: ${purchase.purchaseState}")

        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            val productId = purchase.products.firstOrNull()
            val planType = determinePlanType(productId)
            val currentUser = FirebaseAuth.getInstance().currentUser

            if (currentUser == null) {
                Log.e(TAG, "❌ Usuário não autenticado para processar compra")
                return
            }

            Log.i(TAG, "🔄 Enviando compra para backend: $productId -> $planType")

            viewModelScope.launch {
                try {
                    // Obter token JWT
                    val tokenResult = currentUser.getIdToken(false).await()
                    val userToken = tokenResult.token

                    if (userToken == null) {
                        Log.e(TAG, "❌ Não foi possível obter token JWT")
                        return@launch
                    }

                    // Enviar para backend
                    val apiClient = ApiClient()
                    val success = apiClient.setPremiumStatus(
                        uid = currentUser.uid,
                        purchaseToken = purchase.purchaseToken,
                        productId = productId ?: "",
                        planType = planType,
                        userToken = userToken
                    )

                    withContext(Dispatchers.Main) {
                        if (success) {
                            Log.i(TAG, "✅ Backend confirmou compra com sucesso")
                            _isPremiumUser.value = true
                            _userPlanType.value = planType
                            savePremiumStatusLocally(true, planType)
                        } else {
                            Log.e(TAG, "❌ Backend rejeitou a compra")
                            _isPremiumUser.value = false
                            _userPlanType.value = null
                        }
                    }

                    // Reconhecer compra local
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erro ao enviar compra para backend: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        // Em caso de erro, usar verificação local como fallback
                        _isPremiumUser.value = true
                        _userPlanType.value = planType
                        savePremiumStatusLocally(true, planType)
                    }
                }
            }
        }
    }

    /**
     * Determina o tipo de plano com base no ID do produto.
     */
    private fun determinePlanType(productId: String?): String {
        Log.d(TAG, "Determining plan type for productId: $productId")
        return when {
            productId == null -> "Desconhecido"
            productId.equals("mensal", ignoreCase = true) -> "Monthly plan"
            productId.equals("anual", ignoreCase = true) -> "Annual Plan"
            productId.equals("vital", ignoreCase = true) -> "Lifetime"
            // Suporte para o ID legado "vitalicio" que ainda aparece em compras antigas
            productId.equals("vitalicio", ignoreCase = true) -> {
                Log.i(TAG, "ID de produto legado detectado (vitalicio). Convertendo para tipo de plano 'Lifetime'")
                "Lifetime"
            }
            else -> {
                Log.w(TAG, "Tipo de plano não reconhecido para productId: $productId. Usando 'Premium'.")
                "Premium"
            }
        }
    }

    /**
     * Reconhece uma compra no Google Play.
     */
    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.purchaseToken.isNullOrBlank()) {
            Log.e(TAG, "Token de compra nulo ou vazio. OrderId: ${purchase.orderId}")
            return
        }

        Log.i(TAG, "Reconhecendo compra: ${purchase.orderId}")

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { ackResult ->
            if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Compra RECONHECIDA com sucesso: ${purchase.orderId}")
            } else {
                Log.e(TAG, "Erro ao RECONHECER compra ${purchase.orderId}: ${ackResult.responseCode} - ${ackResult.debugMessage}")
            }
        }
    }

    /**
     * Verifica se uma assinatura está ativa.
     */
    private fun isSubscriptionActive(purchase: Purchase): Boolean {
        // Compra vitalícia: basta verificar se foi comprada
        if (purchase.products.any { it.equals("vital", ignoreCase = true) }) {
            return purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }

        // Para assinaturas: verificar se está ativa ou dentro do período de graça
        return purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                (purchase.isAutoRenewing || (purchase.purchaseTime + GRACE_PERIOD_MS > System.currentTimeMillis()))
    }

    // Período de graça após término da assinatura (2 dias)
    private val GRACE_PERIOD_MS = 2 * 24 * 60 * 60 * 1000L

    /**
     * Verifica o status de assinatura do usuário.
     * Implementação com proteção contra concorrência e sensível ao usuário.
     */
    fun checkUserSubscription() {
        // Se estamos inicializando, aguarde
        if (isInitializing.get()) {
            Log.d(TAG, "checkUserSubscription durante inicialização, adiando verificação")
            viewModelScope.launch {
                delay(300) // Reduzido para esperar menos (era 1000ms)
                if (!isInitializing.get()) {
                    checkUserSubscription()
                }
            }
            return
        }

        // Obter o ID do usuário atual
        val currentUser = FirebaseAuth.getInstance().currentUser
        val currentUserId = currentUser?.uid

        // Se não há usuário logado, definir como não premium e terminar
        if (currentUser == null) {
            _isPremiumUser.value = false
            _userPlanType.value = null
            _isPremiumLoading.value = false
            cachedUserId = null // Limpar o cache
            return
        }

        // IMPORTANTE: Verificar se o usuário mudou e invalidar o cache se necessário
        if (currentUserId != cachedUserId) {
            Log.i(TAG, "Usuário mudou de ${cachedUserId} para ${currentUserId}. Invalidando cache.")
            cachedUserId = currentUserId
            lastVerifiedTimestamp = 0 // Forçar nova verificação
            // Resetar estados imediatamente para não mostrar dados do usuário anterior
            _isPremiumUser.value = false
            _userPlanType.value = null
        }

        // Cancela qualquer verificação em andamento para evitar conflitos
        synchronized(this) {
            activeCheckJob?.cancel()
        }

        // Se já temos uma verificação recente para o mesmo usuário, use o cache
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVerifiedTimestamp < CACHE_VALIDITY_PERIOD && lastVerifiedTimestamp > 0) {
            Log.d(TAG, "Usando cache de status premium para usuário $currentUserId (verificado há ${(currentTime - lastVerifiedTimestamp)/1000}s)")
            return
        }

        // Inicia o loading apenas se não estamos usando o cache
        _isPremiumLoading.value = true

        // Verifica o status atual do usuário
        val currentUserEmail = currentUser.email ?: currentUser.uid
        Log.i(TAG, "--- Iniciando checkUserSubscription (Fonte da Verdade) para usuário: $currentUserEmail ---")

        // Inicia a verificação de forma assíncrona com timeout
        activeCheckJob = viewModelScope.launch {
            try {
                // Executa a verificação com timeout reduzido
                val verificationSuccess = withTimeoutOrNull(3000) { // Era 4000ms
                    try {
                        performUserStatusVerification(currentUserEmail)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro na verificação: ${e.message}", e)
                        false
                    }
                }

                // Se chegou ao timeout, apenas use o que temos
                if (verificationSuccess == null) {
                    Log.w(TAG, "Verificação de assinatura atingiu timeout. Usando último estado conhecido.")
                }

                // Atualiza o timestamp da última verificação
                lastVerifiedTimestamp = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar assinatura: ${e.message}", e)
            } finally {
                // Garante que o estado de loading seja finalizado
                _isPremiumLoading.value = false
            }
        }
    }

    /**
     * Função que realiza a verificação propriamente dita (otimizada).
     */
    private suspend fun performUserStatusVerification(currentUserEmail: String) = coroutineScope {
        // Executar verificações em paralelo para maior eficiência
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        val localDataDeferred = async(Dispatchers.IO) {
            val prefs = getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
            val isPremiumLocal = prefs.getBoolean("is_premium", false)
            val planTypeLocal = prefs.getString("plan_type", null)
            val savedUserId = prefs.getString("cached_user_id", "")

            // Verificar se o ID do usuário corresponde
            if (savedUserId != currentUserId) {
                Log.d(TAG, "ID do usuário em cache não corresponde, ignorando dados locais")
                Pair(false, null)
            } else {
                Pair(isPremiumLocal, planTypeLocal)
            }
        }

        val firebaseDataDeferred = async(Dispatchers.IO) {
            try {
                val db = Firebase.firestore
                withTimeoutOrNull(1500) { // Timeout reduzido para maior eficiência
                    val document = db.collection("premium_users").document(currentUserEmail).get().await()
                    val registeredIsPremium = document.getBoolean("isPremium") ?: false
                    val registeredOrderId = document.getString("orderId")
                    val registeredPlanType = document.getString("planType")
                    Triple(registeredIsPremium, registeredOrderId, registeredPlanType)
                } ?: Triple(false, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar dados do Firebase: ${e.message}", e)
                Triple(false, null, null)
            }
        }

        // Usar dados locais para resposta imediata enquanto aguarda Firebase
        val (localIsPremium, localPlanType) = localDataDeferred.await()
        if (localIsPremium) {
            withContext(Dispatchers.Main) {
                _isPremiumUser.value = true
                _userPlanType.value = localPlanType
            }
        }

        // Continua com a verificação no Firebase
        val (firebaseIsPremium, firebaseOrderId, firebasePlanType) = firebaseDataDeferred.await()

        // Verificar as compras no billing client para confirmar o status
        if (billingClient.isReady) {
            checkBillingPurchasesOptimized(currentUserEmail, firebaseIsPremium, firebaseOrderId, firebasePlanType)
        } else {
            withContext(Dispatchers.Main) {
                // Em caso de cliente não pronto, confiamos no Firebase
                _isPremiumUser.value = firebaseIsPremium
                _userPlanType.value = firebasePlanType
                savePremiumStatusLocally(firebaseIsPremium, firebasePlanType)
                _isPremiumLoading.value = false
            }
        }
    }

    /**
     * Versão otimizada para verificação de compras no Billing Client.
     */
    private suspend fun checkBillingPurchasesOptimized(
        userEmail: String,
        firebaseIsPremium: Boolean,
        registeredOrderId: String?,
        registeredPlanType: String?
    ) {
        try {
            // Para reduzir o tempo, verificar primeiro assinaturas, que são mais comuns
            val subsResult = withTimeoutOrNull(1500) {
                billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
                )
            }

            var foundMatchingPurchase = false
            var isActivePremiumResult = false
            var activePlanTypeResult: String? = null

            // Verificar assinaturas
            if (subsResult != null && subsResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val subPurchases = subsResult.purchasesList

                // Procurar assinatura ativa que corresponda ao orderId registrado
                if (!registeredOrderId.isNullOrBlank()) {
                    val purchase = subPurchases.find { it.orderId == registeredOrderId }
                    if (purchase != null && isSubscriptionActive(purchase)) {
                        isActivePremiumResult = true
                        activePlanTypeResult = determinePlanType(purchase.products.firstOrNull())
                        foundMatchingPurchase = true
                        Log.d(TAG, "Assinatura correspondente encontrada para $registeredOrderId: ${purchase.products.firstOrNull()}")
                    }
                }

                // Ou qualquer assinatura ativa
                if (!foundMatchingPurchase && firebaseIsPremium) {
                    val activeSub = subPurchases.find { isSubscriptionActive(it) }
                    if (activeSub != null) {
                        isActivePremiumResult = true
                        activePlanTypeResult = determinePlanType(activeSub.products.firstOrNull())
                        foundMatchingPurchase = true
                        Log.d(TAG, "Assinatura ativa encontrada: ${activeSub.products.firstOrNull()}")
                    }
                }
            }

            // Se não encontrou assinatura, verificar compras únicas
            if (!foundMatchingPurchase) {
                val inAppResult = withTimeoutOrNull(1500) {
                    billingClient.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
                    )
                }

                if (inAppResult != null && inAppResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val inAppPurchases = inAppResult.purchasesList

                    // Procurar compra que corresponda ao orderId registrado
                    if (!registeredOrderId.isNullOrBlank()) {
                        val purchase = inAppPurchases.find { it.orderId == registeredOrderId }
                        if (purchase != null && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            isActivePremiumResult = true
                            activePlanTypeResult = determinePlanType(purchase.products.firstOrNull())
                            foundMatchingPurchase = true
                            Log.d(TAG, "Compra única correspondente encontrada para $registeredOrderId: ${purchase.products.firstOrNull()}")
                        }
                    }

                    // Ou qualquer compra vitalícia válida
                    if (!foundMatchingPurchase && firebaseIsPremium && registeredPlanType == "Lifetime") {
                        val vitalPurchase = inAppPurchases.find { p ->
                            p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                                    p.products.any { it.equals("vital", ignoreCase = true) }
                        }

                        if (vitalPurchase != null) {
                            isActivePremiumResult = true
                            activePlanTypeResult = determinePlanType(vitalPurchase.products.firstOrNull())
                            foundMatchingPurchase = true
                            Log.d(TAG, "Compra vitalícia válida encontrada: ${vitalPurchase.products.firstOrNull()}")
                        }
                    }
                }
            }

            // Atualizar UI com resultado final
            withContext(Dispatchers.Main) {
                if (isActivePremiumResult) {
                    _isPremiumUser.value = true
                    _userPlanType.value = activePlanTypeResult
                    savePremiumStatusLocally(true, activePlanTypeResult)
                    Log.d(TAG, "Status final: Premium=true, Plano=$activePlanTypeResult (verificado através de compra)")
                } else if (firebaseIsPremium) {
                    _isPremiumUser.value = true
                    _userPlanType.value = registeredPlanType
                    savePremiumStatusLocally(true, registeredPlanType)
                    Log.d(TAG, "Status final: Premium=true, Plano=$registeredPlanType (verificado através do Firebase)")
                } else {
                    _isPremiumUser.value = false
                    _userPlanType.value = null
                    savePremiumStatusLocally(false, null)
                    Log.d(TAG, "Status final: Premium=false, Plano=null")
                }

                _isPremiumLoading.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar compras: ${e.message}", e)

            withContext(Dispatchers.Main) {
                // Em caso de erro, confiamos no Firebase
                _isPremiumUser.value = firebaseIsPremium
                _userPlanType.value = registeredPlanType
                _isPremiumLoading.value = false
            }
        }
    }

    /**
     * Salva o status premium do usuário no Firebase.
     */
    private fun saveUserStatusToFirebase(isPremium: Boolean, planType: String?, orderId: String?, purchaseTime: Long?, productId: String?) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val userEmail = currentUser.email ?: currentUser.uid
        Log.i(TAG, "Salvando Firebase para $userEmail: Premium=$isPremium, Plano=$planType, productId=$productId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = Firebase.firestore
                val userDocRef = db.collection("premium_users").document(userEmail)

                val userData = mutableMapOf<String, Any?>(
                    "isPremium" to isPremium,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "planType" to if (isPremium) planType else null,
                    "orderId" to if (isPremium) orderId else null,
                    "purchaseTime" to if (isPremium) purchaseTime else null,
                    "productId" to if (isPremium) productId else null,
                    "userEmail" to userEmail,  // Sempre armazenar o email do usuário
                    "userId" to currentUser.uid // Sempre armazenar o ID do usuário
                )

                Log.d(TAG, "Firebase Save Data: $userData")
                userDocRef.set(userData, SetOptions.merge())
                    .addOnSuccessListener { Log.i(TAG, "Firebase Save Success para usuário $userEmail.") }
                    .addOnFailureListener { e -> Log.e(TAG, "Firebase Save Error para usuário $userEmail: ${e.message}", e) }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Save Exception para usuário $userEmail: ${e.message}", e)
            }
        }
    }

    /**
     * Carrega o status premium do usuário do armazenamento local.
     * Modificado para ser sensível ao usuário.
     */
    private fun loadPremiumStatusLocally() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                val currentUserId = currentUser?.uid

                val prefs = getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
                val cachedUserId = prefs.getString("cached_user_id", null)

                // Verificar se o ID do usuário em cache corresponde ao atual
                if (cachedUserId != currentUserId) {
                    Log.d(TAG, "ID de usuário em cache ($cachedUserId) diferente do atual ($currentUserId). Ignorando dados locais.")
                    this@BillingViewModel.cachedUserId = currentUserId
                    return@launch
                }

                val isPremiumLocal = prefs.getBoolean("is_premium", false)
                val planTypeLocal = prefs.getString("plan_type", null)
                val lastUpdated = prefs.getLong("last_updated_local", 0L)

                Log.d(TAG, "Carregado localmente: Premium=$isPremiumLocal, Plano=$planTypeLocal, Última atualização=${lastUpdated}")

                // Carregar imediatamente o estado dos dados locais para resposta rápida
                if (isPremiumLocal) {
                    _isPremiumUser.value = isPremiumLocal
                    _userPlanType.value = planTypeLocal
                    this@BillingViewModel.cachedUserId = cachedUserId

                    // Se os dados são muito antigos, marcar para revalidação mas manter estado atual
                    if (System.currentTimeMillis() - lastUpdated > 3 * 60 * 60 * 1000L) { // 3 horas (era 24h)
                        Log.d(TAG, "Dados locais antigos, revalidando...")
                        lastVerifiedTimestamp = 0 // Força verificação
                    } else {
                        // Dados recentes, considerar verificado
                        lastVerifiedTimestamp = lastUpdated
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao carregar dados locais", e)
                // A verificação completa ainda será executada
            }
        }
    }

    /**
     * Salva o status premium do usuário no armazenamento local.
     * Modificado para armazenar o ID do usuário.
     */
    private fun savePremiumStatusLocally(isPremium: Boolean, planType: String? = null) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

        try {
            val prefs = getApplication<Application>().getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_premium", isPremium)
                if (planType != null) putString("plan_type", planType) else remove("plan_type")
                // Salvar o ID do usuário atual junto com as preferências
                if (currentUserId != null) putString("cached_user_id", currentUserId) else remove("cached_user_id")
                putLong("last_updated_local", System.currentTimeMillis())
                apply()
            }
            Log.i(TAG, "Salvo localmente: Premium=$isPremium, Plano=$planType, UserId=$currentUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Erro save local", e)
        }
    }

    /**
     * Força a atualização do status premium.
     * Otimizado para ser mais rápido.
     */
    fun forceRefreshPremiumStatus() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Forçando atualização de status premium...")
                _isPremiumLoading.value = true

                // Invalidar cache
                lastVerifiedTimestamp = 0

                // Garantir que qualquer verificação anterior seja cancelada
                synchronized(this@BillingViewModel) {
                    activeCheckJob?.cancel()
                    activeCheckJob?.invokeOnCompletion {
                        viewModelScope.coroutineContext[Job]?.cancelChildren()
                    }
                }

                // Iniciar nova verificação com timeout reduzido
                withTimeoutOrNull(2000) { // Era 3000ms
                    checkUserSubscription()
                }

                // Reduzido para 500ms de espera mínima (era 800ms)
                delay(500)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar status premium: ${e.message}", e)
            } finally {
                // Sempre resetar o estado de loading após no máximo 2.5 segundos
                delay(2000)
                _isPremiumLoading.value = false
            }
        }
    }

    /**
     * Deve ser chamado após login/logout.
     */
    fun handleUserChanged() {
        Log.i(TAG, "handleUserChanged: Usuário alterado, forçando verificação de premium")

        // Resetar o ID em cache para forçar nova verificação
        cachedUserId = null
        lastVerifiedTimestamp = 0
        // Redefinir os estados imediatamente
        _isPremiumUser.value = false
        _userPlanType.value = null

        // MODIFICAÇÃO: Usar um escopo de corrotina separado para evitar cancelamentos
        val verificationJob = viewModelScope.launch {
            // Pequeno atraso para garantir que os serviços do Firebase estejam prontos
            delay(200)

            try {
                // Forçar verificação completa com alta prioridade
                // Usar withContext para manter o job ativo mesmo se outros jobs forem cancelados
                withContext(Dispatchers.IO) {
                    forceRefreshPremiumStatus(highPriority = true)
                }

                // Segunda verificação após um intervalo para garantir
                delay(1000)
                withContext(Dispatchers.IO) {
                    checkUserSubscription()
                }

                // Terceira verificação para garantir
                delay(2000)
                withContext(Dispatchers.IO) {
                    checkUserSubscription()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro durante verificação premium após login: ${e.message}", e)
                // Tentar mais uma vez mesmo após erro
                delay(500)
                checkUserSubscription()
            }
        }

        // Dar tempo para que a verificação termine antes de retornar
        runBlocking {
            try {
                withTimeoutOrNull(500) {
                    verificationJob.join()
                }
            } catch (e: Exception) {
                // Ignorar timeout
            }
        }
    }

    fun forceRefreshPremiumStatus(highPriority: Boolean = false) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Forçando atualização de status premium (prioridade alta: $highPriority)...")
                _isPremiumLoading.value = true

                // Invalidar cache
                lastVerifiedTimestamp = 0

                // Garantir que qualquer verificação anterior seja cancelada
                synchronized(this@BillingViewModel) {
                    activeCheckJob?.cancel()
                    activeCheckJob?.invokeOnCompletion {
                        viewModelScope.coroutineContext[Job]?.cancelChildren()
                    }
                }

                // Iniciar nova verificação com timeout reduzido para alta prioridade
                val timeoutMs = if (highPriority) 1500L else 2000L
                withTimeoutOrNull(timeoutMs) {
                    checkUserSubscription()
                }

                // Tempo de espera mínimo reduzido para prioridade alta
                delay(if (highPriority) 300 else 500)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar status premium: ${e.message}", e)
            } finally {
                // Garantir que o loading termine em um tempo razoável
                delay(if (highPriority) 1000 else 2000)
                _isPremiumLoading.value = false
            }
        }
    }

    fun checkForCancellation() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Verificando possível cancelamento de assinatura...")

                // Forçar verificação completa
                lastVerifiedTimestamp = 0

                _isPremiumLoading.value = true

                // Garante que qualquer verificação anterior seja cancelada
                synchronized(this@BillingViewModel) {
                    activeCheckJob?.cancel()
                }

                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    _isPremiumUser.value = false
                    _userPlanType.value = null
                    _isPremiumLoading.value = false
                    return@launch
                }

                val userEmail = currentUser.email ?: currentUser.uid

                // Verificar no Firebase primeiro
                val db = Firebase.firestore
                val docRef = db.collection("premium_users").document(userEmail)

                try {
                    val document = docRef.get().await()
                    val isPremiumFirebase = document.getBoolean("isPremium") ?: false

                    if (!isPremiumFirebase && _isPremiumUser.value) {
                        Log.w(TAG, "Possível cancelamento detectado no Firebase!")
                        // Verificar com Play para confirmar
                        checkUserSubscription()
                    } else {
                        // Verificar também com o Play para garantir sincronia
                        checkUserSubscription()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro verificando cancelamento no Firebase: ${e.message}", e)
                    // Se falhar, ainda tentamos verificar com o Play
                    checkUserSubscription()
                }

                // Garantir que o loading seja finalizado após um tempo
                delay(3000)
                _isPremiumLoading.value = false

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao verificar cancelamento: ${e.message}", e)
                _isPremiumLoading.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        activeCheckJob?.cancel()
        if (billingClient.isReady) {
            Log.d(TAG, "Fechando conexão com BillingClient")
            billingClient.endConnection()
        }
    }

    companion object {
        private var INSTANCE: BillingViewModel? = null

        @Synchronized
        fun getInstance(application: Application): BillingViewModel {
            return INSTANCE ?: BillingViewModel(application).also {
                INSTANCE = it
            }
        }
    }
}