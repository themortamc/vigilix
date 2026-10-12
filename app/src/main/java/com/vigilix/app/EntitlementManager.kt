package com.vigilix.app

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gestor de entitlements (suscripciones). Usa Play Billing v6 + RevenueCat fallback.
 */
class EntitlementManager private constructor(context: Context) {

    companion object {
        const val PRO_MONTHLY = "vigilix_pro_monthly"
        const val PRO_YEARLY = "vigilix_pro_yearly"

        @Volatile private var INSTANCE: EntitlementManager? = null

        fun getInstance(context: Context): EntitlementManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: EntitlementManager(context.applicationContext).also { INSTANCE = it }
        }
    }

    private val context: Context = context.applicationContext
    private val _isPro = MutableLiveData<Boolean>()
    private val _billingReady = MutableLiveData<Boolean>()

    val isProLiveData: LiveData<Boolean> = _isPro
    val billingReady: LiveData<Boolean> = _billingReady

    private var isProCached = false
    private var billingClient: BillingClient? = null
    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases)
        }
    }

    init {
        val client = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
        billingClient = client

        startConnection()
    }

    private fun startConnection() {
        billingClient!!.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingReady.postValue(true)
                    queryPurchases()
                } else {
                    _billingReady.postValue(false)
                    // Fallback a RevenueCat si está configurado
                }
            }

            override fun onBillingServiceDisconnected() {
                _billingReady.postValue(false)
            }
        })
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient!!.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            }
        }
    }

    /** Fuerza una consulta de compras al Play Store (para "Restaurar compras"). */
    fun refreshPurchases() {
        queryPurchases()
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val nowPro = purchases.any { purchase ->
            (purchase.products.contains(PRO_MONTHLY) || purchase.products.contains(PRO_YEARLY)) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        isProCached = nowPro
        _isPro.postValue(nowPro)
    }

    val isPro: Boolean
        get() = isProCached

    /** Inicia flujo de compra del plan mensual. */
    fun launchPurchase(activity: android.app.Activity, onResult: (Boolean) -> Unit) {
        launchPurchaseFlow(activity, PRO_MONTHLY, onResult)
    }

    /** Inicia flujo de compra del plan anual. */
    fun launchPurchaseYearly(activity: android.app.Activity, onResult: (Boolean) -> Unit) {
        launchPurchaseFlow(activity, PRO_YEARLY, onResult)
    }

    private fun launchPurchaseFlow(activity: android.app.Activity, productId: String, onResult: (Boolean) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()))
            .build()

        billingClient!!.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList[0]
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()))
                    .build()
                activity.runOnUiThread {
                    val result = billingClient!!.launchBillingFlow(activity, flowParams)
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        onResult(false)
                    }
                }
            } else {
                onResult(false)
            }
        }
    }
}