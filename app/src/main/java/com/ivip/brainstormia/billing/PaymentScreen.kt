package com.ivip.brainstormia.billing

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val billingViewModel = (context.applicationContext as BrainstormiaApplication).billingViewModel

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Observar o estado de produtos e compras
    val products by billingViewModel.products.collectAsState()
    val isPremiumUser by billingViewModel.isPremiumUser.collectAsState()

    // Navegar quando o usuário se tornar premium
    LaunchedEffect(isPremiumUser) {
        if (isPremiumUser) {
            delay(1000) // Dar tempo para o usuário ver a mensagem de sucesso
            onPurchaseComplete()
        }
    }

    // Carregar produtos se necessário
    LaunchedEffect(Unit) {
        if (products.isEmpty()) {
            billingViewModel.queryAvailableProducts()
        }
    }

    // Cores específicas do tema
    val backgroundColor = if (isDarkTheme) BackgroundColorDark else BackgroundColor
    val cardColor = if (isDarkTheme) Color(0xFF121212) else Color.White
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
                // Logo
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

                // Título e descrição
                Text(
                    text = stringResource(R.string.premium_app_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Lista de planos
                Text(
                    text = stringResource(R.string.choose_plan),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (products.isEmpty()) {
                    // Estado de carregamento
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
                    // Filtramos para mostrar apenas as assinaturas e compras pontuais
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

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { billingViewModel.retryConnection() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkTheme) Color(0xFF333333) else PrimaryColor
                            )
                        ) {
                            Text(stringResource(R.string.try_again))
                        }
                    } else {
                        subscriptionProducts.forEach { product ->
                            SubscriptionPlanCard(
                                product = product,
                                onSelectPlan = {
                                    if (currentActivity != null) {
                                        billingViewModel.launchBillingFlow(currentActivity, product)
                                    } else {
                                        // Preparar a mensagem de erro aqui para evitar chamar stringResource
                                        // fora de um contexto @Composable
                                        val errorMessage = context.getString(R.string.purchase_error)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(errorMessage)
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
    // Determinar preço e período
    val price = if (product.productType == "subs") {
        product.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
    } else {
        product.oneTimePurchaseOfferDetails?.formattedPrice
    } ?: stringResource(R.string.price_unavailable)

    val period = if (product.productType == "subs") {
        when {
            product.productId.contains("mensal", ignoreCase = true) -> stringResource(R.string.per_month)
            product.productId.contains("anual", ignoreCase = true) -> stringResource(R.string.per_year)
            else -> ""
        }
    } else ""

    // Determinar nome do plano
    val planName = when {
        product.productId.contains("mensal", ignoreCase = true) -> stringResource(R.string.monthly_plan)
        product.productId.contains("anual", ignoreCase = true) -> stringResource(R.string.annual_plan)
        product.productId.contains("vital", ignoreCase = true) -> stringResource(R.string.lifetime_plan)
        else -> product.name
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectPlan() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (product.productId.contains("anual", ignoreCase = true))
                highlightColor.copy(alpha = 0.5f)
            else
                if (isDarkTheme) Color.Gray.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = planName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (product.productId.contains("anual", ignoreCase = true)) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = highlightColor,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp)
                        )
                    }

                    // Usar um formato de string para preço + período
                    Text(
                        text = stringResource(R.string.price_format, price, period),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = highlightColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recursos específicos de cada plano
            when {
                product.productId.contains("mensal", ignoreCase = true) -> {
                    PlanFeatureItem(text = stringResource(R.string.feature_premium_models), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_conversation_export), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_priority_support), isDarkTheme = isDarkTheme, textColor = textColor)
                }
                product.productId.contains("anual", ignoreCase = true) -> {
                    PlanFeatureItem(text = stringResource(R.string.feature_premium_models), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_conversation_export), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_priority_support), isDarkTheme = isDarkTheme, textColor = textColor)
                }
                product.productId.contains("vital", ignoreCase = true) -> {
                    PlanFeatureItem(text = stringResource(R.string.feature_lifetime_access), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_conversation_export), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_priority_support), isDarkTheme = isDarkTheme, textColor = textColor)
                    PlanFeatureItem(text = stringResource(R.string.feature_no_recurring_fee), isDarkTheme = isDarkTheme, textColor = textColor)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSelectPlan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkTheme) BrainGold else BrainGold,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_plan),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun PlanFeatureItem(
    text: String,
    isDarkTheme: Boolean,
    textColor: Color
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (isDarkTheme) Color.Gray else Color.DarkGray,
            modifier = Modifier.size(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = text,
            fontSize = 14.sp,
            color = textColor.copy(alpha = 0.8f)
        )
    }
}