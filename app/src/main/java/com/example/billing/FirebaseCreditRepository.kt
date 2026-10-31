package com.example.billing

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CreditWallet(
    val creditSeconds: Long = 0L,
    val totalPurchasedSeconds: Long = 0L,
    val totalUsedSeconds: Long = 0L
)

/**
 * Read-only client view of the server-owned credit wallet.
 * All balance mutations are performed by authenticated Cloud Functions.
 */
class FirebaseCreditRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    private val _wallet = MutableStateFlow<CreditWallet?>(null)
    val wallet: StateFlow<CreditWallet?> = _wallet.asStateFlow()
    private var registration: ListenerRegistration? = null

    fun startObserving() {
        val uid = auth.currentUser?.uid ?: run {
            _wallet.value = null
            return
        }
        registration?.remove()
        registration = firestore.collection("credit_wallets").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    _wallet.value = null
                    return@addSnapshotListener
                }
                _wallet.value = CreditWallet(
                    creditSeconds = snapshot.getLong("creditSeconds") ?: 0L,
                    totalPurchasedSeconds = snapshot.getLong("totalPurchasedSeconds") ?: 0L,
                    totalUsedSeconds = snapshot.getLong("totalUsedSeconds") ?: 0L
                )
            }
    }

    suspend fun verifyCreditPurchase(productId: String, purchaseToken: String): Map<*, *> {
        val result = functions.getHttpsCallable("verifyCreditPurchase")
            .call(mapOf("productId" to productId, "purchaseToken" to purchaseToken)).await()
        @Suppress("UNCHECKED_CAST")
        return result.data as? Map<*, *> ?: emptyMap<Any, Any>()
    }

    suspend fun startCreditRecording(sessionId: String): Long {
        val result = functions.getHttpsCallable("startCreditRecording")
            .call(mapOf("sessionId" to sessionId)).await()
        val data = result.data as? Map<*, *> ?: emptyMap<Any, Any>()
        return (data["remainingSeconds"] as? Number)?.toLong() ?: 0L
    }

    suspend fun reportCreditRecordingUsage(sessionId: String, elapsedSeconds: Long): Long {
        val result = functions.getHttpsCallable("reportCreditRecordingUsage")
            .call(mapOf("sessionId" to sessionId, "elapsedSeconds" to elapsedSeconds)).await()
        val data = result.data as? Map<*, *> ?: emptyMap<Any, Any>()
        return (data["remainingSeconds"] as? Number)?.toLong() ?: 0L
    }

    suspend fun finishCreditRecording(sessionId: String) {
        functions.getHttpsCallable("finishCreditRecording")
            .call(mapOf("sessionId" to sessionId)).await()
    }

    suspend fun signInAnonymouslyIfNeeded() {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        startObserving()
    }

    fun stopObserving() {
        registration?.remove()
        registration = null
        _wallet.value = null
    }
}
