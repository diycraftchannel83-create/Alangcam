import {initializeApp} from "firebase-admin/app";
import {getFirestore, FieldValue} from "firebase-admin/firestore";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import {defineSecret} from "firebase-functions/params";
import {google} from "googleapis";
import {createHash} from "crypto";

initializeApp();
const db = getFirestore();
const playServiceAccount = defineSecret("PLAY_SERVICE_ACCOUNT_JSON");
const packageName = "com.winsmediatama.camriderpro";

const PRODUCTS: Record<string, {credits: number; seconds: number}> = {
  "1._1000_credit": {credits: 1000, seconds: 2 * 60 * 60},
  "2._2000_credit": {credits: 2000, seconds: 4 * 60 * 60},
  "5._5000_credit": {credits: 5000, seconds: 11 * 60 * 60},
};

function requireUid(request: {auth?: {uid: string} | null}): string {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return request.auth.uid;
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

async function playPublisher() {
  const credentials = JSON.parse(playServiceAccount.value());
  const auth = new google.auth.GoogleAuth({
    credentials,
    scopes: ["https://www.googleapis.com/auth/androidpublisher"],
  });
  return google.androidpublisher({version: "v3", auth});
}

/**
 * Verify an INAPP purchase against Google Play before adding the wallet balance.
 * A purchase token is globally unique; its SHA-256 hash is used as an idempotency key.
 */
export const verifyCreditPurchase = onCall(
  {secrets: [playServiceAccount], timeoutSeconds: 60},
  async (request) => {
    const uid = requireUid(request);
    const productId = String(request.data?.productId ?? "");
    const purchaseToken = String(request.data?.purchaseToken ?? "");
    if (!PRODUCTS[productId] || purchaseToken.length < 20) {
      throw new HttpsError("invalid-argument", "Invalid product or purchase token.");
    }

    const publisher = await playPublisher();
    const response = await publisher.purchases.products.get({
      packageName,
      productId,
      token: purchaseToken,
    });
    const purchase = response.data;
    if (purchase.purchaseState !== 0) {
      throw new HttpsError("failed-precondition", "Purchase is not completed.");
    }
    if (purchase.acknowledgementState !== 1 && purchase.consumptionState !== 1) {
      // Delivery is granted before the purchase is consumed; the app retries consume.
    }

    const tokenHash = hashToken(purchaseToken);
    const tokenRef = db.collection("purchase_tokens").doc(tokenHash);
    const walletRef = db.collection("credit_wallets").doc(uid);
    const txRef = db.collection("credit_transactions").doc(tokenHash);
    const packageInfo = PRODUCTS[productId];

    const result = await db.runTransaction(async (tx) => {
      const prior = await tx.get(tokenRef);
      if (prior.exists) {
        if (prior.get("uid") !== uid || prior.get("productId") !== productId) {
          throw new HttpsError("already-exists", "Purchase token already redeemed.");
        }
        const wallet = await tx.get(walletRef);
        return {alreadyProcessed: true, creditSeconds: wallet.get("creditSeconds") ?? 0};
      }

      const wallet = await tx.get(walletRef);
      const current = wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0;
      const purchasedTotal = wallet.exists ? Number(wallet.get("totalPurchasedSeconds") ?? 0) : 0;
      tx.set(tokenRef, {
        uid, productId, purchaseTime: purchase.purchaseTimeMillis ?? null,
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.set(txRef, {
        uid, type: "purchase", productId,
        credits: packageInfo.credits, creditSeconds: packageInfo.seconds,
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.set(walletRef, {
        uid,
        creditSeconds: current + packageInfo.seconds,
        totalPurchasedSeconds: purchasedTotal + packageInfo.seconds,
        totalUsedSeconds: wallet.exists ? Number(wallet.get("totalUsedSeconds") ?? 0) : 0,
        updatedAt: FieldValue.serverTimestamp(),
      }, {merge: true});
      return {alreadyProcessed: false, creditSeconds: current + packageInfo.seconds};
    });

    return {...result, creditsAdded: packageInfo.credits, secondsAdded: packageInfo.seconds};
  }
);

/**
 * Atomically reserve and debit the wallet when starting a paid recording.
 * Client must supply a random idempotency key and report elapsed usage with the session.
 */
export const startCreditRecording = onCall(async (request) => {
  const uid = requireUid(request);
  const sessionId = String(request.data?.sessionId ?? "");
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(sessionId)) {
    throw new HttpsError("invalid-argument", "Invalid session ID.");
  }
  const sessionRef = db.collection("recording_sessions").doc(sessionId);
  const walletRef = db.collection("credit_wallets").doc(uid);

  return db.runTransaction(async (tx) => {
    const existing = await tx.get(sessionRef);
    if (existing.exists) {
      if (existing.get("uid") !== uid) throw new HttpsError("permission-denied", "Session mismatch.");
      return {sessionId, remainingSeconds: existing.get("remainingSeconds") ?? 0};
    }
    const wallet = await tx.get(walletRef);
    const balance = wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0;
    if (balance <= 0) throw new HttpsError("failed-precondition", "No recording credits.");
    tx.create(sessionRef, {
      uid, startedAt: FieldValue.serverTimestamp(),
      lastHeartbeatAt: FieldValue.serverTimestamp(),
      remainingSeconds: balance, status: "active",
    });
    return {sessionId, remainingSeconds: balance};
  });
});

/**
 * Reports usage as a monotonic cumulative duration. Each session is billed only for
 * the increase since lastReportedSeconds; retries cannot double-charge.
 */
export const reportCreditRecordingUsage = onCall(async (request) => {
  const uid = requireUid(request);
  const sessionId = String(request.data?.sessionId ?? "");
  const reportedSeconds = Number(request.data?.elapsedSeconds);
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(sessionId) ||
      !Number.isSafeInteger(reportedSeconds) || reportedSeconds < 0) {
    throw new HttpsError("invalid-argument", "Invalid recording usage.");
  }
  const sessionRef = db.collection("recording_sessions").doc(sessionId);
  const walletRef = db.collection("credit_wallets").doc(uid);
  const txRef = db.collection("credit_transactions").doc(sessionId + "_" + reportedSeconds);

  return db.runTransaction(async (tx) => {
    const session = await tx.get(sessionRef);
    if (!session.exists || session.get("uid") !== uid || session.get("status") !== "active") {
      throw new HttpsError("failed-precondition", "Recording session is not active.");
    }
    const previous = Number(session.get("lastReportedSeconds") ?? 0);
    if (reportedSeconds <= previous) return {chargedSeconds: 0, remainingSeconds: session.get("remainingSeconds")};
    const charge = reportedSeconds - previous;
    const wallet = await tx.get(walletRef);
    const balance = wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0;
    if (balance < charge) throw new HttpsError("resource-exhausted", "Recording credits exhausted.");
    const remaining = balance - charge;
    tx.update(walletRef, {
      creditSeconds: remaining,
      totalUsedSeconds: FieldValue.increment(charge),
      updatedAt: FieldValue.serverTimestamp(),
    });
    tx.update(sessionRef, {
      lastReportedSeconds: reportedSeconds,
      remainingSeconds: remaining,
      lastHeartbeatAt: FieldValue.serverTimestamp(),
    });
    tx.set(txRef, {
      uid, type: "usage", sessionId, creditSeconds: -charge,
      createdAt: FieldValue.serverTimestamp(),
    });
    return {chargedSeconds: charge, remainingSeconds: remaining};
  });
});

export const finishCreditRecording = onCall(async (request) => {
  const uid = requireUid(request);
  const sessionId = String(request.data?.sessionId ?? "");
  const sessionRef = db.collection("recording_sessions").doc(sessionId);
  await db.runTransaction(async (tx) => {
    const session = await tx.get(sessionRef);
    if (!session.exists || session.get("uid") !== uid) return;
    if (session.get("status") === "active") {
      tx.update(sessionRef, {status: "finished", finishedAt: FieldValue.serverTimestamp()});
    }
  });
  return {finished: true};
});
