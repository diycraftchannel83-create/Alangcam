package com.example.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BillingUiState(
    val isConnected: Boolean = false,
    val isPremium: Boolean = false,
    val creditSeconds: Long = 0L,
    val premiumPrice: String = "—",
    val creditPrice: String = "—",
    val message: String? = null
)

/**
 * Client-side Play Billing integration.
 *
 * Product IDs must exactly match the one-time products configured in Play Console.
 * For production-grade fraud protection and reliable cross-device consumable balances,
 * validate purchase tokens and maintain credit balances on a trusted backend.
 */
class BillingManager(context: Context) : PurchasesUpdatedListener {
    companion object {
        const val PREMIUM_PRODUCT_ID = "camriderpro_premium_unlock"
        const val RECORDING_CREDIT_PRODUCT_ID = "camriderpro_record_4h"
        const val RECORDING_CREDIT_SECONDS = 4L * 60L * 60L
        private const val PREFS_NAME = "camriderpro_billing"
        private const val KEY_PREMIUM = "premium_owned"
        private const val KEY_CREDIT_SECONDS = "recording_credit_seconds"
        private const val KEY_PROCESSED_CREDIT_TOKENS = "processed_credit_tokens"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        BillingUiState(
            isPremium = prefs.getBoolean(KEY_PREMIUM, false),
            creditSeconds = prefs.getLong(KEY_CREDIT_SECONDS, 0L).coerceAtLeast(0L)
        )
    )
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (billingClient.isReady) {
            queryProducts()
            queryPurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.value = _state.value.copy(isConnected = true, message = null)
                    queryProducts()
                    queryPurchases()
                } else {
                    _state.value = _state.value.copy(
                        isConnected = false,
                        message = "Google Play Billing tidak tersedia: ${result.debugMessage}"
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.value = _state.value.copy(isConnected = false)
            }
        })
    }

    private fun queryProducts() {
        val products = listOf(PREMIUM_PRODUCT_ID, RECORDING_CREDIT_PRODUCT_ID).map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                detailsResult.productDetailsList.forEach { productDetails[it.productId] = it }
                val premiumPrice = priceFor(PREMIUM_PRODUCT_ID)
                val creditPrice = priceFor(RECORDING_CREDIT_PRODUCT_ID)
                _state.value = _state.value.copy(
                    premiumPrice = premiumPrice,
                    creditPrice = creditPrice,
                    isConnected = true
                )
            } else {
                _state.value = _state.value.copy(message = "Produk belum tersedia: ${result.debugMessage}")
            }
        }
    }

    private fun priceFor(productId: String): String {
        return productDetails[productId]
            ?.oneTimePurchaseOfferDetailsList
            ?.firstOrNull()
            ?.formattedPrice ?: "Belum tersedia"
    }

    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetails[productId]
        if (!billingClient.isReady || details == null) {
            _state.value = _state.value.copy(
                message = "Produk belum siap. Pastikan produk sudah dibuat dan diaktifkan di Play Console."
            )
            connect()
            return
        }
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
        if (offer == null) {
            _state.value = _state.value.copy(message = "Penawaran pembelian produk belum tersedia.")
            return
        }
        val offerToken = offer.offerToken
        if (offerToken.isNullOrBlank()) {
            _state.value = _state.value.copy(message = "Token penawaran pembelian tidak tersedia. Coba lagi beberapa saat.")
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = _state.value.copy(message = "Tidak dapat membuka pembayaran: ${result.debugMessage}")
        }
    }

    fun refreshPurchases() {
        if (!billingClient.isReady) connect() else queryPurchases()
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::processPurchase)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when {
            result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null ->
                purchases.forEach(::processPurchase)
            result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED ->
                _state.value = _state.value.copy(message = "Pembelian dibatalkan.")
            else ->
                _state.value = _state.value.copy(message = "Pembelian gagal: ${result.debugMessage}")
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                _state.value = _state.value.copy(message = "Pembayaran masih menunggu konfirmasi. Fitur belum diberikan.")
            }
            return
        }

        if (PREMIUM_PRODUCT_ID in purchase.products) {
            prefs.edit().putBoolean(KEY_PREMIUM, true).apply()
            _state.value = _state.value.copy(isPremium = true, message = "Premium Lifetime aktif. Watermark dihapus.")
            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(params) { ackResult ->
                    if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        _state.value = _state.value.copy(message = "Premium aktif; konfirmasi transaksi akan dicoba kembali.")
                    }
                }
            }
        }

        if (RECORDING_CREDIT_PRODUCT_ID in purchase.products) {
            val token = purchase.purchaseToken
            val processed = prefs.getStringSet(KEY_PROCESSED_CREDIT_TOKENS, emptySet())?.toMutableSet()
                ?: mutableSetOf()
            if (token !in processed) {
                processed.add(token)
                val newBalance = prefs.getLong(KEY_CREDIT_SECONDS, 0L) + RECORDING_CREDIT_SECONDS
                prefs.edit()
                    .putStringSet(KEY_PROCESSED_CREDIT_TOKENS, processed)
                    .putLong(KEY_CREDIT_SECONDS, newBalance)
                    .apply()
                _state.value = _state.value.copy(
                    creditSeconds = newBalance,
                    message = "Kredit rekam 4 jam berhasil ditambahkan."
                )
            }

            // Persist the grant before consuming so a failed consume can be retried without
            // granting the same purchase token twice on the next purchase query.
            val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(token).build()
            billingClient.consumeAsync(consumeParams) { consumeResult, _ ->
                if (consumeResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    _state.value = _state.value.copy(
                        message = "Kredit tersimpan. Penyelesaian transaksi akan dicoba kembali saat aplikasi dibuka."
                    )
                }
            }
        }
    }

    fun deductRecordingSeconds(seconds: Long) {
        if (seconds <= 0L) return
        val newBalance = (prefs.getLong(KEY_CREDIT_SECONDS, 0L) - seconds).coerceAtLeast(0L)
        prefs.edit().putLong(KEY_CREDIT_SECONDS, newBalance).apply()
        _state.value = _state.value.copy(creditSeconds = newBalance)
    }

    fun close() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
