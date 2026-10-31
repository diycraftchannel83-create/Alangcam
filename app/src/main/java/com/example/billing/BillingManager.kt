package com.example.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BillingUiState(
    val isConnected: Boolean = false,
    val isPremium: Boolean = false,
    val creditSeconds: Long = 0L,
    val creditPrice: String = "—",
    val credit2Price: String = "—",
    val credit5Price: String = "—",
    val message: String? = null
)

/**
 * Play Billing client. Purchases are verified and credited by Firebase Cloud Functions.
 * The client never writes a credit balance locally.
 */
class BillingManager(context: Context, private val creditRepository: FirebaseCreditRepository = FirebaseCreditRepository()) : PurchasesUpdatedListener {
    companion object {
        private const val LEGACY_WATERMARK_PRODUCT_ID = "remove_watermark"
        const val RECORDING_CREDIT_PRODUCT_ID = "1._1000_credit"
        const val RECORDING_CREDIT_SECONDS = 2L * 60L * 60L
        const val RECORDING_CREDIT_2H_PRODUCT_ID = "2._2000_credit"
        const val RECORDING_CREDIT_5H_PRODUCT_ID = "5._5000_credit"
        const val RECORDING_CREDIT_2H_SECONDS = 4L * 60L * 60L
        const val RECORDING_CREDIT_5H_SECONDS = 11L * 60L * 60L
        private const val TAG = "BillingManager"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("camriderpro_billing", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processingTokens = mutableSetOf<String>()

    private val _state = MutableStateFlow(
        BillingUiState(isPremium = prefs.getBoolean("premium_owned", false))
    )
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    private val productDetails = mutableMapOf<String, ProductDetails>()
    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch {
            try {
                creditRepository.signInAnonymouslyIfNeeded()
                creditRepository.wallet.collect { wallet ->
                    if (wallet != null) {
                        _state.value = _state.value.copy(
                            creditSeconds = wallet.creditSeconds.coerceAtLeast(0L),
                            isPremium = _state.value.isPremium || wallet.totalPurchasedSeconds > 0L
                        )
                        if (wallet.totalPurchasedSeconds > 0L) {
                            prefs.edit().putBoolean("premium_owned", true).apply()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase wallet initialization failed", e)
                _state.value = _state.value.copy(
                    message = "Saldo kredit belum dapat disinkronkan. Periksa koneksi internet dan Firebase."
                )
            }
        }
    }

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
        val ids = listOf(
            RECORDING_CREDIT_PRODUCT_ID,
            RECORDING_CREDIT_2H_PRODUCT_ID,
            RECORDING_CREDIT_5H_PRODUCT_ID
        )
        val products = ids.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                detailsResult.productDetailsList.forEach { productDetails[it.productId] = it }
                _state.value = _state.value.copy(
                    creditPrice = priceFor(RECORDING_CREDIT_PRODUCT_ID),
                    credit2Price = priceFor(RECORDING_CREDIT_2H_PRODUCT_ID),
                    credit5Price = priceFor(RECORDING_CREDIT_5H_PRODUCT_ID),
                    isConnected = true
                )
            } else {
                _state.value = _state.value.copy(message = "Produk belum tersedia: ${result.debugMessage}")
            }
        }
    }

    private fun priceFor(productId: String): String =
        productDetails[productId]?.oneTimePurchaseOfferDetailsList
            ?.firstOrNull()?.formattedPrice ?: "Belum tersedia"

    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetails[productId]
        if (!billingClient.isReady || details == null) {
            _state.value = _state.value.copy(
                message = "Produk belum siap. Pastikan produk aktif di Play Console."
            )
            connect()
            return
        }
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
        if (offer == null) {
            _state.value = _state.value.copy(message = "Penawaran pembelian belum tersedia.")
            return
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()
        )
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
            else -> _state.value = _state.value.copy(message = "Pembelian gagal: ${result.debugMessage}")
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            _state.value = _state.value.copy(message = "Pembayaran masih menunggu konfirmasi. Kredit belum diberikan.")
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        if (LEGACY_WATERMARK_PRODUCT_ID in purchase.products) {
            prefs.edit().putBoolean("premium_owned", true).apply()
            _state.value = _state.value.copy(
                isPremium = true,
                message = "Watermark sudah terbuka dari pembelian sebelumnya."
            )
            if (!purchase.isAcknowledged) {
                billingClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken).build()
                ) { }
            }
            return
        }

        val productId = purchase.products.firstOrNull { it in setOf(
            RECORDING_CREDIT_PRODUCT_ID,
            RECORDING_CREDIT_2H_PRODUCT_ID,
            RECORDING_CREDIT_5H_PRODUCT_ID
        ) } ?: return

        val token = purchase.purchaseToken
        if (!processingTokens.add(token)) return
        scope.launch {
            try {
                // A server-verified and idempotent grant must succeed before consuming the Play token.
                creditRepository.signInAnonymouslyIfNeeded()
                creditRepository.verifyCreditPurchase(productId, token)
                prefs.edit().putBoolean("premium_owned", true).apply()
                _state.value = _state.value.copy(
                    isPremium = true,
                    message = "Pembelian berhasil diverifikasi. Kredit sedang disinkronkan ke saldo akun."
                )
                val consume = ConsumeParams.newBuilder().setPurchaseToken(token).build()
                billingClient.consumeAsync(consume) { consumeResult, _ ->
                    if (consumeResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        _state.value = _state.value.copy(
                            message = "Kredit sudah diverifikasi server. Penyelesaian transaksi Play akan dicoba kembali."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Purchase verification failed", e)
                _state.value = _state.value.copy(
                    message = "Pembelian belum dapat diverifikasi. Kredit tidak ditambahkan; tekan Pulihkan Pembelian setelah koneksi pulih."
                )
            } finally {
                processingTokens.remove(token)
            }
        }
    }

    /** Deprecated: credit deductions must now be performed by server recording sessions. */
    @Deprecated("Use Firebase recording session heartbeat; local credit deductions are disabled.")
    fun deductRecordingSeconds(seconds: Long) {
        // Intentionally no-op. Firestore is the single source of truth for credit balance.
    }

    fun close() {
        scope.cancel()
        creditRepository.stopObserving()
        if (billingClient.isReady) billingClient.endConnection()
    }
}
