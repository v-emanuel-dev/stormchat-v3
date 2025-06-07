package com.ivip.brainstormia.billing

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.ivip.brainstormia.BrainstormiaApplication
import com.ivip.brainstormia.R
import com.ivip.brainstormia.theme.BackgroundColor
import com.ivip.brainstormia.theme.BackgroundColorDark
import com.ivip.brainstormia.theme.BrainGold
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(
    onNavigateBack: () -> Unit,
    onPurchaseComplete: () -> Unit,
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
    val currentActivity = context as? android.app.Activity

    val billingViewModel = remember {
        try {
            (context.applicationContext as? BrainstormiaApplication)?.billingViewModel
        } catch (e: Exception) {
            Log.e("PaymentScreen", "Erro ao obter BillingViewModel", e)
            null
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val products by (billingViewModel?.products?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val isPremiumUser by (billingViewModel?.isPremiumUser?.collectAsState() ?: remember { mutableStateOf(false) })
    val purchaseInProgress by (billingViewModel?.purchaseInProgress?.collectAsState() ?: remember { mutableStateOf(false) })

    if (billingViewModel == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkTheme) Color(0xFF121212) else BackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Erro",
                    modifier = Modifier.size(64.dp),
                    tint = Color.Red
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sistema de pagamentos indisponível",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isDarkTheme) Color.White else Color.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tente novamente mais tarde",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) Color.LightGray else Color.DarkGray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onNavigateBack,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor)
                ) {
                    Text("Voltar", color = Color.White)
                }
            }
        }
        return
    }

    // ✅ INÍCIO DA CORREÇÃO APLICADA
    // Este LaunchedEffect observa a mudança no estado de 'purchaseInProgress' e 'isPremiumUser'
    // para garantir uma reação robusta e no momento certo.
    LaunchedEffect(purchaseInProgress, isPremiumUser) {
        snapshotFlow { purchaseInProgress }
            .filter { inProgress -> !inProgress } // Filtra para o momento exato em que a compra termina
            .collect {
                // Neste ponto, o fluxo de compra acabou. Agora verificamos o resultado.
                if (isPremiumUser) {
                    // Se o usuário for premium (mesmo que temporariamente), mostramos o sucesso.
                    Log.d("PaymentScreen", "Compra finalizada. Usuário é premium. Navegando para a tela de sucesso.")
                    snackbarHostState.showSnackbar(
                        message = "Compra realizada com sucesso!",
                        duration = SnackbarDuration.Short
                    )
                    delay(1500) // Tempo para o usuário ver a mensagem.
                    onPurchaseComplete()
                } else {
                    Log.d("PaymentScreen", "Compra finalizada, mas o status do usuário não é premium.")
                }
            }
    }
    // ✅ FIM DA CORREÇÃO APLICADA

    LaunchedEffect(Unit) {
        try {
            if (products.isEmpty()) {
                Log.d("PaymentScreen", "Carregando produtos...")
                billingViewModel.queryProducts()
                delay(10000)
                if (products.isEmpty()) {
                    snackbarHostState.showSnackbar(
                        message = "Não foi possível carregar os planos. Tente novamente.",
                        duration = SnackbarDuration.Long
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("PaymentScreen", "Erro ao carregar produtos", e)
            snackbarHostState.showSnackbar(
                message = "Erro ao carregar planos: ${e.localizedMessage}",
                duration = SnackbarDuration.Long
            )
        }
    }

    val backgroundColor = if (isDarkTheme) BackgroundColorDark else BackgroundColor
    val textColor = if (isDarkTheme) TextColorLight else TextColorDark
    val textSecondaryColor = if (isDarkTheme) TextColorLight.copy(alpha = 0.7f) else TextColorDark.copy(alpha = 0.9f)
    val highlightColor = if (isDarkTheme) BrainGold else PrimaryColor

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.payment_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDarkTheme) Color(0xFF1E1E1E) else PrimaryColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkTheme) Color(0xFF121212) else backgroundColor)
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(if (isDarkTheme) Color(0xFF333333) else PrimaryColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_bolt_foreground),
                        contentDescription = stringResource(R.string.logo_description),
                        modifier = Modifier.size(70.dp),
                        colorFilter = ColorFilter.tint(if (isDarkTheme) Color(0xFFFFD700) else Color.Black)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.premium_app_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.choose_plan),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (products.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = if (isDarkTheme) BrainGold else PrimaryColor
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.loading_plans),
                            color = textSecondaryColor
                        )
                    }
                } else {
                    val subscriptionProducts = products.filter {
                        it.productType == "subs" || it.productId == "vital"
                    }.sortedBy { prod ->
                        when {
                            prod.productId.contains("mensal") -> 1
                            prod.productId.contains("anual") -> 2
                            prod.productId.contains("vital") -> 3
                            else -> 4
                        }
                    }

                    if (subscriptionProducts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_plans_available),
                            color = textSecondaryColor,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        subscriptionProducts.forEach { product ->
                            SubscriptionPlanCard(
                                product = product,
                                onSelectPlan = {
                                    try {
                                        if (currentActivity != null && !purchaseInProgress) {
                                            Log.d("PaymentScreen", "Iniciando compra do produto: ${product.productId}")
                                            billingViewModel.launchBillingFlow(currentActivity, product)
                                        } else {
                                            val errorMessage = if (currentActivity == null) {
                                                "Erro: Atividade não disponível"
                                            } else {
                                                "Aguarde, compra em andamento..."
                                            }
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMessage)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("PaymentScreen", "Erro ao iniciar compra", e)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Erro ao iniciar compra: ${e.localizedMessage}",
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    }
                                },
                                isDarkTheme = isDarkTheme,
                                textColor = textColor,
                                secondaryTextColor = textSecondaryColor,
                                highlightColor = highlightColor
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionPlanCard(
    product: ProductDetails,
    onSelectPlan: () -> Unit,
    isDarkTheme: Boolean,
    textColor: Color,
    secondaryTextColor: Color,
    highlightColor: Color
) {
    val cardBackgroundColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White
    val borderColor = if (isDarkTheme) highlightColor.copy(alpha = 0.3f) else highlightColor.copy(alpha = 0.5f)

    val productName = when {
        product.productId.contains("mensal", ignoreCase = true) -> "Plano Mensal"
        product.productId.contains("anual", ignoreCase = true) -> "Plano Anual"
        product.productId.contains("vital", ignoreCase = true) -> "Plano Vitalício"
        else -> product.name.takeIf { it.isNotEmpty() } ?: "Plano Premium"
    }

    val price = when (product.productType) {
        BillingClient.ProductType.SUBS -> {
            product.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "Preço não disponível"
        }
        BillingClient.ProductType.INAPP -> {
            product.oneTimePurchaseOfferDetails?.formattedPrice ?: "Preço não disponível"
        }
        else -> "Preço não disponível"
    }

    val description = when {
        product.productId.contains("mensal", ignoreCase = true) -> "Acesso completo por 1 mês\nRenovação automática"
        product.productId.contains("anual", ignoreCase = true) -> "Acesso completo por 1 ano\nEconomize com o plano anual"
        product.productId.contains("vital", ignoreCase = true) -> "Acesso vitalício\nPagamento único, para sempre"
        else -> "Acesso completo aos recursos premium"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onSelectPlan() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
        border = BorderStroke(width = 1.dp, color = borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 4.dp else 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = productName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = price,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = highlightColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryTextColor,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BenefitItem("✓ Acesso a todos os modelos de IA", textColor)
                BenefitItem("✓ Geração de imagens ilimitada", textColor)
                BenefitItem("✓ Upload de arquivos", textColor)
                BenefitItem("✓ Backup automático", textColor)
                BenefitItem("✓ Suporte prioritário", textColor)
                if (product.productId.contains("vital", ignoreCase = true)) {
                    BenefitItem("✓ Sem renovações necessárias", highlightColor)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onSelectPlan,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = highlightColor,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = "Selecionar Plano",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun BenefitItem(
    text: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}