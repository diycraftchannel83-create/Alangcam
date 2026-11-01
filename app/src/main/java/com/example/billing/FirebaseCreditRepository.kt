package com.example.billing

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class CreditWallet(
    val creditSeconds: Long = 0L,
    val totalPurchasedSeconds: Long = 0L,
    val totalUsedSeconds: Long = 0L,
    val premiumOwned: Boolean = false
)

/**
 * Firebase-backed wallet repository.
 *
 * Wallet writes and Google Play purchase verification are performed only by
 * authenticated Cloud Functions. The Android client can read its own wallet,
 * but never writes balances directly.
 */
class FirebaseCreditRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-southeast2")
) {
    private val _wallet = MutableStateFlow<CreditWallet?>(null)
    val wallet: StateFlow<CreditWallet?> = _wallet.asStateFlow()
    private var walletListener: ListenerRegistration? = null

    suspend fun signInAnonymouslyIfNeeded() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
        startObserving()
        refreshWallet()
    }

    fun startObserving() {
        val uid = auth.currentUser?.uid ?: return
        if (walletListener != null) return
        walletListener = firestore.collection("credit_wallets")
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                _wallet.value = CreditWallet(
                    creditSeconds = snapshot.getLong("creditSeconds") ?: 0L,
                    totalPurchasedSeconds = snapshot.getLong("totalPurchasedSeconds") ?: 0L,
                    totalUsedSeconds = snapshot.getLong("totalUsedSeconds") ?: 0L,
                    premiumOwned = snapshot.getBoolean("premiumOwned") ?: false
                )
            }
    }

    suspend fun refreshWallet() {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Firebase user is not signed in.")
        val snapshot = firestore.collection("credit_wallets").document(uid).get().await()
        _wallet.value = if (!snapshot.exists()) {
            CreditWallet()
        } else {
            CreditWallet(
                creditSeconds = snapshot.getLong("creditSeconds") ?: 0L,
                totalPurchasedSeconds = snapshot.getLong("totalPurchasedSeconds") ?: 0L,
                totalUsedSeconds = snapshot.getLong("totalUsedSeconds") ?: 0L,
                premiumOwned = snapshot.getBoolean("premiumOwned") ?: false
            )
        }
    }

    suspend fun verifyCreditPurchase(productId: String, purchaseToken: String): Map<*, *> {
        val result = call("verifyCreditPurchase", mapOf(
            "productId" to productId,
            "purchaseToken" to purchaseToken
        ))
        refreshWallet()
        return result
    }

    suspend fun verifyWatermarkPurchase(productId: String, purchaseToken: String): Boolean {
        val result = call("verifyWatermarkPurchase", mapOf(
            "productId" to productId,
            "purchaseToken" to purchaseToken
        ))
        refreshWallet()
        return result["premiumOwned"] == true
    }

    suspend fun startCreditRecording(sessionId: String): Long {
        val result = call("startCreditRecording", mapOf("sessionId" to sessionId))
        refreshWallet()
        return (result["remainingSeconds"] as? Number)?.toLong() ?: 0L
    }

    suspend fun heartbeatCreditRecording(sessionId: String): Map<*, *> {
        val result = call("heartbeatCreditRecording", mapOf("sessionId" to sessionId))
        val remaining = (result["remainingSeconds"] as? Number)?.toLong()
        if (remaining != null) {
            _wallet.value = _wallet.value?.copy(creditSeconds = remaining)
        }
        return result
    }

    suspend fun finishCreditRecording(sessionId: String) {
        call("finishCreditRecording", mapOf("sessionId" to sessionId))
        refreshWallet()
    }

    fun stopObserving() {
        walletListener?.remove()
        walletListener = null
        _wallet.value = null
    }

    fun close() {
        stopObserving()
    }

    private suspend fun call(name: String, data: Map<String, Any>): Map<*, *> {
        val result = functions.getHttpsCallable(name).call(data).await()
        @Suppress("UNCHECKED_CAST")
        return result.data as? Map<*, *> ?: emptyMap<String, Any>()
    }
}
