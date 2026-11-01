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
        const val RECORDING_CREDIT_PRODUCT_ID = "credit_1000"
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
    private var isConnecting = false

    private val _state = MutableStateFlow(
        BillingUiState(isPremium = false)
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
                            isPremium = wallet.premiumOwned
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firebase wallet initialization failed", e)
                _state.value = _state.value.copy(
                    message = "Saldo kredit belum dapat disinkronkan. Periksa koneksi internet dan konfigurasi backend."
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
        if (isConnecting) return
        isConnecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                isConnecting = false
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
                isConnecting = false
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
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        // One-time product offer tokens can be nullable in newer Play Billing APIs.
        // Only set the token when Google Play actually provides one.
        offer.offerToken?.let { token -> paramsBuilder.setOfferToken(token) }
        val params = paramsBuilder.build()
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
            val token = purchase.purchaseToken
            if (!processingTokens.add(token)) return
            scope.launch {
                try {
                    creditRepository.signInAnonymouslyIfNeeded()
                    val verified = creditRepository.verifyWatermarkPurchase(LEGACY_WATERMARK_PRODUCT_ID, token)
                    if (!verified) throw IllegalStateException("Watermark entitlement was not confirmed by the server.")
                    prefs.edit().putBoolean("premium_owned", true).apply()
                    _state.value = _state.value.copy(
                        isPremium = true,
                        message = "Pembelian Remove Watermark berhasil diverifikasi."
                    )
                    if (!purchase.isAcknowledged) {
                        billingClient.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
                        ) { result ->
                            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                                _state.value = _state.value.copy(message = "Watermark aktif. Konfirmasi transaksi Play akan dicoba kembali.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watermark purchase verification failed", e)
                    _state.value = _state.value.copy(message = "Pembelian Remove Watermark belum dapat diverifikasi.")
                } finally {
                    processingTokens.remove(token)
                }
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
                val verification = creditRepository.verifyCreditPurchase(productId, token)
                val creditsAdded = (verification["creditsAdded"] as? Number)?.toInt() ?: 0
                _state.value = _state.value.copy(
                    message = if (creditsAdded > 0) "Berhasil! $creditsAdded Credit telah ditambahkan ke saldo." else "Transaksi sudah diproses. Saldo telah disinkronkan."
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
        // Intentionally no-op. The Apps Script wallet is the single source of truth for credit balance.
    }

    fun close() {
        scope.cancel()
        creditRepository.close()
        if (billingClient.isReady) billingClient.endConnection()
    }
}
