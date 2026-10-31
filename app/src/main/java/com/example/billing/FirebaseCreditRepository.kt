package com.example.billing

import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CreditWallet(
    val creditSeconds: Long = 0L,
    val totalPurchasedSeconds: Long = 0L,
    val totalUsedSeconds: Long = 0L,
    val premiumOwned: Boolean = false
)

/** Apps Script owns the wallet; Firebase Authentication only provides verifiable user ID tokens. */
class AppsScriptCreditRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val _wallet = MutableStateFlow<CreditWallet?>(null)
    val wallet: StateFlow<CreditWallet?> = _wallet.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun startObserving() {
        if (observerJob?.isActive == true) return
        observerJob = scope.launch {
            while (isActive) {
                try { refreshWallet() } catch (_: Exception) {
                    // Retain the last server snapshot; never create a local balance.
                }
                delay(20_000L)
            }
        }
    }

    suspend fun verifyCreditPurchase(productId: String, purchaseToken: String): Map<*, *> =
        callApi("verifyCreditPurchase", JSONObject()
            .put("productId", productId).put("purchaseToken", purchaseToken)).toMap()

    suspend fun verifyWatermarkPurchase(productId: String, purchaseToken: String): Boolean {
        val data = callApi("verifyWatermarkPurchase", JSONObject()
            .put("productId", productId).put("purchaseToken", purchaseToken))
        refreshWallet()
        return data.optBoolean("premiumOwned", false)
    }

    suspend fun startCreditRecording(sessionId: String): Long {
        val data = callApi("startCreditRecording", JSONObject().put("sessionId", sessionId))
        refreshWallet()
        return data.optLong("remainingSeconds", 0L)
    }

    suspend fun heartbeatCreditRecording(sessionId: String): Map<*, *> {
        val data = callApi("heartbeatCreditRecording", JSONObject().put("sessionId", sessionId))
        _wallet.value = _wallet.value?.copy(
            creditSeconds = data.optLong("remainingSeconds", _wallet.value?.creditSeconds ?: 0L)
        )
        return data.toMap()
    }

    suspend fun finishCreditRecording(sessionId: String) {
        callApi("finishCreditRecording", JSONObject().put("sessionId", sessionId))
        refreshWallet()
    }

    suspend fun signInAnonymouslyIfNeeded() {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        refreshWallet()
        startObserving()
    }

    suspend fun refreshWallet() {
        val data = callApi("getCreditBalance", JSONObject())
        _wallet.value = CreditWallet(
            creditSeconds = data.optLong("creditSeconds", 0L),
            totalPurchasedSeconds = data.optLong("totalPurchasedSeconds", 0L),
            totalUsedSeconds = data.optLong("totalUsedSeconds", 0L),
            premiumOwned = data.optBoolean("premiumOwned", false)
        )
    }

    fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
        _wallet.value = null
    }

    fun close() {
        stopObserving()
        scope.cancel()
    }

    private suspend fun callApi(action: String, payload: JSONObject): JSONObject {
        val endpoint = BuildConfig.CREDIT_API_URL.trim()
        check(endpoint.startsWith("https://") && endpoint.contains("/exec")) {
            "Backend URL belum dikonfigurasi. Isi CREDIT_API_URL dengan URL deployment Google Apps Script (/exec)."
        }
        val user = auth.currentUser ?: throw IllegalStateException("Firebase user is not signed in.")
        val idToken = user.getIdToken(false).await().token
            ?: throw IllegalStateException("Tidak dapat memperoleh token autentikasi.")
        val body = JSONObject(payload.toString()).put("action", action).put("idToken", idToken)
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(endpoint).post(body).build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Backend HTTP \${response.code}: \${response.message}")
                }
                val envelope = JSONObject(raw)
                if (!envelope.optBoolean("ok", false)) {
                    throw IllegalStateException(envelope.optString("error", "Backend request failed."))
                }
                envelope.optJSONObject("data") ?: JSONObject()
            }
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = opt(key)
        }
        return result
    }
}
