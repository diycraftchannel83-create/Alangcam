import {initializeApp} from "firebase-admin/app";
import {getFirestore, FieldValue, Timestamp} from "firebase-admin/firestore";
import {onCall, HttpsError} from "firebase-functions/v2/https";
import {defineSecret} from "firebase-functions/params";
import {google} from "googleapis";
import {createHash} from "crypto";

initializeApp();
const db = getFirestore();
const playServiceAccount = defineSecret("PLAY_SERVICE_ACCOUNT_JSON");
const packageName = "com.winsmediatama.camriderpro";
const FUNCTION_REGION = "asia-southeast2";
const MAX_HEARTBEAT_GAP_SECONDS = 45;
const MAX_CHARGE_PER_HEARTBEAT_SECONDS = 30;

const PRODUCTS: Record<string, {credits: number; seconds: number}> = {
  "credit_1000": {credits: 1000, seconds: 2 * 60 * 60},
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

/** Verify the purchase with Google Play before crediting the server wallet. */
export const verifyCreditPurchase = onCall(
  {region: FUNCTION_REGION, secrets: [playServiceAccount], timeoutSeconds: 60, maxInstances: 5},
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
    if (purchase.purchaseState !== 0 || purchase.productId !== productId) {
      throw new HttpsError("failed-precondition", "Purchase is not completed or product does not match.");
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
      tx.create(tokenRef, {
        uid, productId, purchaseTime: purchase.purchaseTimeMillis ?? null,
        purchaseType: purchase.purchaseType ?? null,
        isPromoCode: purchase.purchaseType === 1,
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.create(txRef, {
        uid, type: "purchase", productId,
        source: purchase.purchaseType === 1 ? "promo" : "google_play",
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

    return {...result, creditsAdded: packageInfo.credits, secondsAdded: packageInfo.seconds, isPromoCode: purchase.purchaseType === 1};
  }
);

/** Verify the permanent Remove Watermark entitlement through Google Play. */
export const verifyWatermarkPurchase = onCall(
  {region: FUNCTION_REGION, secrets: [playServiceAccount], timeoutSeconds: 60, maxInstances: 5},
  async (request) => {
    const uid = requireUid(request);
    const productId = String(request.data?.productId ?? "");
    const purchaseToken = String(request.data?.purchaseToken ?? "");
    if (productId !== "remove_watermark" || purchaseToken.length < 20) {
      throw new HttpsError("invalid-argument", "Invalid watermark product or purchase token.");
    }

    const publisher = await playPublisher();
    const response = await publisher.purchases.products.get({
      packageName,
      productId,
      token: purchaseToken,
    });
    const purchase = response.data;
    if (purchase.purchaseState !== 0 || purchase.productId !== productId) {
      throw new HttpsError("failed-precondition", "Watermark purchase is not completed or product does not match.");
    }

    const tokenHash = hashToken(purchaseToken);
    const tokenRef = db.collection("purchase_tokens").doc(tokenHash);
    const walletRef = db.collection("credit_wallets").doc(uid);
    const transactionRef = db.collection("credit_transactions").doc(tokenHash);

    return db.runTransaction(async (tx) => {
      const prior = await tx.get(tokenRef);
      if (prior.exists) {
        if (prior.get("uid") !== uid || prior.get("productId") !== productId) {
          throw new HttpsError("already-exists", "Purchase token already belongs to another account.");
        }
        const wallet = await tx.get(walletRef);
        return {premiumOwned: wallet.get("premiumOwned") === true, alreadyProcessed: true};
      }

      const wallet = await tx.get(walletRef);
      tx.create(tokenRef, {
        uid, productId, purchaseTime: purchase.purchaseTimeMillis ?? null,
        purchaseType: purchase.purchaseType ?? null,
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.create(transactionRef, {
        uid, type: "premium_purchase", productId,
        source: purchase.purchaseType === 1 ? "promo" : "google_play",
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.set(walletRef, {
        uid,
        creditSeconds: wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0,
        totalPurchasedSeconds: wallet.exists ? Number(wallet.get("totalPurchasedSeconds") ?? 0) : 0,
        totalUsedSeconds: wallet.exists ? Number(wallet.get("totalUsedSeconds") ?? 0) : 0,
        premiumOwned: true,
        updatedAt: FieldValue.serverTimestamp(),
      }, {merge: true});
      return {premiumOwned: true, alreadyProcessed: false};
    });
  }
);

/** Create an authenticated recording session; the server owns all timing and billing. */
export const startCreditRecording = onCall(
  {region: FUNCTION_REGION, maxInstances: 10},
  async (request) => {
    const uid = requireUid(request);
    const sessionId = String(request.data?.sessionId ?? "");
    if (!/^[A-Za-z0-9_-]{16,80}$/.test(sessionId)) {
      throw new HttpsError("invalid-argument", "Invalid session ID.");
    }
    const now = Timestamp.now();
    const sessionRef = db.collection("recording_sessions").doc(sessionId);
    const walletRef = db.collection("credit_wallets").doc(uid);

    return db.runTransaction(async (tx) => {
      const existing = await tx.get(sessionRef);
      if (existing.exists) {
        if (existing.get("uid") !== uid) throw new HttpsError("permission-denied", "Session mismatch.");
        if (existing.get("status") !== "active") throw new HttpsError("failed-precondition", "Session is not active.");
        return {sessionId, remainingSeconds: existing.get("remainingSeconds") ?? 0};
      }
      const activeSessions = await tx.get(
        db.collection("recording_sessions")
          .where("uid", "==", uid)
          .where("status", "==", "active")
          .limit(10)
      );
      const liveSessions = activeSessions.docs.filter((doc) => {
        const lastHeartbeat = doc.get("lastHeartbeatAt") as Timestamp | undefined;
        const stale = !lastHeartbeat ||
          now.toMillis() - lastHeartbeat.toMillis() > MAX_HEARTBEAT_GAP_SECONDS * 1000;
        if (stale) {
          tx.update(doc.ref, {status: "expired", finishedAt: now});
          return false;
        }
        return doc.id !== sessionId;
      });
      if (liveSessions.length > 0) {
        throw new HttpsError("already-exists", "Another recording session is already active for this account.");
      }
      const wallet = await tx.get(walletRef);
      const balance = wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0;
      if (balance <= 0) throw new HttpsError("failed-precondition", "No recording credits.");
      tx.create(sessionRef, {
        uid, startedAt: now, lastHeartbeatAt: now,
        remainingSeconds: balance, status: "active",
      });
      return {sessionId, remainingSeconds: balance};
    });
  }
);

/**
 * Charge time using server timestamps only. Client-provided elapsed seconds are never trusted.
 * The app should call this every ~15 seconds. A gap over 45 seconds expires the session.
 */
export const heartbeatCreditRecording = onCall(
  {region: FUNCTION_REGION, maxInstances: 10},
  async (request) => {
    const uid = requireUid(request);
    const sessionId = String(request.data?.sessionId ?? "");
    if (!/^[A-Za-z0-9_-]{16,80}$/.test(sessionId)) {
      throw new HttpsError("invalid-argument", "Invalid session ID.");
    }
    const now = Timestamp.now();
    const sessionRef = db.collection("recording_sessions").doc(sessionId);
    const walletRef = db.collection("credit_wallets").doc(uid);

    return db.runTransaction(async (tx) => {
      const session = await tx.get(sessionRef);
      if (!session.exists || session.get("uid") !== uid || session.get("status") !== "active") {
        throw new HttpsError("failed-precondition", "Recording session is not active.");
      }
      const last = session.get("lastHeartbeatAt") as Timestamp | undefined;
      if (!last) throw new HttpsError("failed-precondition", "Session timestamp is missing.");
      const elapsed = Math.max(0, Math.floor((now.toMillis() - last.toMillis()) / 1000));
      const expired = elapsed > MAX_HEARTBEAT_GAP_SECONDS;
      const charge = Math.min(elapsed, MAX_CHARGE_PER_HEARTBEAT_SECONDS);
      const wallet = await tx.get(walletRef);
      const balance = wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0;
      if (balance < charge) {
        tx.update(sessionRef, {status: "exhausted", finishedAt: now, remainingSeconds: balance});
        return {chargedSeconds: 0, remainingSeconds: balance, expired: true, exhausted: true};
      }
      const remaining = balance - charge;
      if (charge > 0) {
        tx.update(walletRef, {
          creditSeconds: remaining,
          totalUsedSeconds: FieldValue.increment(charge),
          updatedAt: FieldValue.serverTimestamp(),
        });
        tx.create(db.collection("credit_transactions").doc(
          sessionId + "_" + now.toMillis()
        ), {
          uid, type: "usage", sessionId, creditSeconds: -charge,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
      tx.update(sessionRef, {
        lastHeartbeatAt: now,
        remainingSeconds: remaining,
        status: expired ? "expired" : "active",
        ...(expired ? {finishedAt: now} : {}),
      });
      return {chargedSeconds: charge, remainingSeconds: remaining, expired};
    });
  }
);

/** Final server-side charge when the user stops recording normally. */
export const finishCreditRecording = onCall(
  {region: FUNCTION_REGION, maxInstances: 10},
  async (request) => {
    const uid = requireUid(request);
    const sessionId = String(request.data?.sessionId ?? "");
    if (!/^[A-Za-z0-9_-]{16,80}$/.test(sessionId)) {
      throw new HttpsError("invalid-argument", "Invalid session ID.");
    }
    const now = Timestamp.now();
    const sessionRef = db.collection("recording_sessions").doc(sessionId);
    const walletRef = db.collection("credit_wallets").doc(uid);

    return db.runTransaction(async (tx) => {
      const session = await tx.get(sessionRef);
      if (!session.exists || session.get("uid") !== uid) {
        throw new HttpsError("not-found", "Recording session not found.");
      }
      if (session.get("status") !== "active") {
        return {finished: true, remainingSeconds: session.get("remainingSeconds") ?? 0};
      }
      const last = session.get("lastHeartbeatAt") as Timestamp | undefined;
      const elapsed = last ? Math.max(0, Math.floor((now.toMillis() - last.toMillis()) / 1000)) : 0;
      const charge = Math.min(elapsed, MAX_CHARGE_PER_HEARTBEAT_SECONDS);
      const wallet = await tx.get(walletRef);
      const balance = wallet.exists ? Number(wallet.get("creditSeconds") ?? 0) : 0;
      if (balance < charge) throw new HttpsError("resource-exhausted", "Recording credits exhausted.");
      const remaining = balance - charge;
      if (charge > 0) {
        tx.update(walletRef, {
          creditSeconds: remaining,
          totalUsedSeconds: FieldValue.increment(charge),
          updatedAt: FieldValue.serverTimestamp(),
        });
        tx.create(db.collection("credit_transactions").doc(sessionId + "_finish"), {
          uid, type: "usage", sessionId, creditSeconds: -charge,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
      tx.update(sessionRef, {
        status: "finished", finishedAt: now,
        lastHeartbeatAt: now, remainingSeconds: remaining,
      });
      return {finished: true, chargedSeconds: charge, remainingSeconds: remaining};
    });
  }
);
