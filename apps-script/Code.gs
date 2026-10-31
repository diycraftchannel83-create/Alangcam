/**
 * CamRiderPro credit backend for Google Apps Script (V8).
 *
 * Required Script Properties:
 *   SPREADSHEET_ID
 *   FIREBASE_WEB_API_KEY
 *   PLAY_SERVICE_ACCOUNT_JSON
 *
 * Deploy as Web App: Execute as Me; access Anyone.
 * Every API request must include a valid Firebase Authentication ID token.
 */

const CONFIG = Object.freeze({
  PACKAGE_NAME: "com.winsmediatama.camriderpro",
  MAX_HEARTBEAT_GAP_SECONDS: 45,
  MAX_CHARGE_PER_HEARTBEAT_SECONDS: 30,
  PRODUCTS: {
    "1._1000_credit": { credits: 1000, seconds: 7200 },
    "2._2000_credit": { credits: 2000, seconds: 14400 },
    "5._5000_credit": { credits: 5000, seconds: 39600 }
  },
  WATERMARK_PRODUCT_IDS: ["remove_watermark"]
});

const HEADERS = {
  wallets: ["uid", "creditSeconds", "totalPurchasedSeconds", "totalUsedSeconds", "premiumOwned", "updatedAt"],
  purchases: ["tokenHash", "uid", "productId", "credits", "seconds", "purchaseTime", "createdAt"],
  sessions: ["sessionId", "uid", "status", "startedAt", "lastHeartbeatAt", "finishedAt", "remainingSeconds"],
  transactions: ["createdAt", "uid", "type", "productId", "credits", "creditSeconds", "sessionId", "tokenHash"]
};

function doGet() {
  return json_({ ok: true, service: "CamRiderPro Credit API", version: 1 });
}

function doPost(e) {
  try {
    const body = e && e.postData && e.postData.contents ? JSON.parse(e.postData.contents) : {};
    const uid = verifyFirebaseIdToken_(String(body.idToken || ""));
    const action = String(body.action || "");
    let result;

    switch (action) {
      case "getCreditBalance":
        result = getCreditBalance_(uid);
        break;
      case "verifyCreditPurchase":
        result = verifyCreditPurchase_(uid, body);
        break;
      case "verifyWatermarkPurchase":
        result = verifyWatermarkPurchase_(uid, body);
        break;
      case "startCreditRecording":
        result = startCreditRecording_(uid, body);
        break;
      case "heartbeatCreditRecording":
        result = heartbeatCreditRecording_(uid, body);
        break;
      case "finishCreditRecording":
        result = finishCreditRecording_(uid, body);
        break;
      default:
        throw new Error("Unknown action.");
    }
    return json_({ ok: true, data: result });
  } catch (err) {
    console.error("CamRiderPro API error: " + (err && err.stack ? err.stack : err));
    return json_({
      ok: false,
      error: err && err.message ? err.message : "Request failed."
    });
  }
}

function setupDatabase() {
  const ss = getSpreadsheet_();
  Object.keys(HEADERS).forEach(function(name) {
    let sheet = ss.getSheetByName(name);
    if (!sheet) sheet = ss.insertSheet(name);
    if (sheet.getLastRow() === 0) {
      sheet.getRange(1, 1, 1, HEADERS[name].length).setValues([HEADERS[name]]);
      sheet.setFrozenRows(1);
    }
  });
  return "CamRiderPro sheets are ready.";
}

function verifyFirebaseIdToken_(idToken) {
  if (!idToken) throw new Error("Authentication token is missing.");
  const apiKey = requiredProperty_("FIREBASE_WEB_API_KEY");
  const response = UrlFetchApp.fetch(
    "https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" + encodeURIComponent(apiKey),
    {
      method: "post",
      contentType: "application/json",
      payload: JSON.stringify({ idToken: idToken }),
      muteHttpExceptions: true
    }
  );
  const code = response.getResponseCode();
  const data = JSON.parse(response.getContentText() || "{}");
  if (code !== 200 || !data.users || !data.users.length || !data.users[0].localId) {
    throw new Error("Firebase authentication token is invalid or expired.");
  }
  return String(data.users[0].localId);
}

function verifyCreditPurchase_(uid, body) {
  const productId = String(body.productId || "");
  const purchaseToken = String(body.purchaseToken || "");
  const product = CONFIG.PRODUCTS[productId];
  if (!product || purchaseToken.length < 20) throw new Error("Invalid credit product or purchase token.");

  const purchase = getPlayProductPurchase_(productId, purchaseToken);
  if (Number(purchase.purchaseState) !== 0) throw new Error("Google Play purchase is not completed.");

  const tokenHash = sha256_(purchaseToken);
  return withScriptLock_(function() {
    const ss = getSpreadsheet_();
    const purchases = ss.getSheetByName("purchases");
    const existingRow = findRow_(purchases, 1, tokenHash);
    if (existingRow) {
      const existing = purchases.getRange(existingRow, 1, 1, HEADERS.purchases.length).getValues()[0];
      if (String(existing[1]) !== uid || String(existing[2]) !== productId) {
        throw new Error("Purchase token has already been used by another account.");
      }
      const wallet = getWalletRow_(uid, true);
      return { alreadyProcessed: true, creditSeconds: Number(wallet[1]) || 0 };
    }

    const wallet = getWalletRow_(uid, true);
    wallet[1] = (Number(wallet[1]) || 0) + product.seconds;
    wallet[2] = (Number(wallet[2]) || 0) + product.seconds;
    wallet[5] = new Date().toISOString();
    saveWalletRow_(uid, wallet);
    purchases.appendRow([
      tokenHash, uid, productId, product.credits, product.seconds,
      purchase.purchaseTimeMillis || "", new Date().toISOString()
    ]);
    ss.getSheetByName("transactions").appendRow([
      new Date().toISOString(), uid, "purchase", productId,
      product.credits, product.seconds, "", tokenHash
    ]);
    return {
      alreadyProcessed: false,
      creditsAdded: product.credits,
      secondsAdded: product.seconds,
      creditSeconds: wallet[1]
    };
  });
}

function verifyWatermarkPurchase_(uid, body) {
  const productId = String(body.productId || "");
  const purchaseToken = String(body.purchaseToken || "");
  if (CONFIG.WATERMARK_PRODUCT_IDS.indexOf(productId) < 0 || purchaseToken.length < 20) {
    throw new Error("Invalid watermark product or purchase token.");
  }
  const purchase = getPlayProductPurchase_(productId, purchaseToken);
  if (Number(purchase.purchaseState) !== 0) throw new Error("Google Play purchase is not completed.");

  const tokenHash = sha256_(purchaseToken);
  return withScriptLock_(function() {
    const ss = getSpreadsheet_();
    const purchases = ss.getSheetByName("purchases");
    const existingRow = findRow_(purchases, 1, tokenHash);
    if (existingRow) {
      const existing = purchases.getRange(existingRow, 1, 1, HEADERS.purchases.length).getValues()[0];
      if (String(existing[1]) !== uid || String(existing[2]) !== productId) {
        throw new Error("Watermark purchase token has already been claimed by another account.");
      }
    } else {
      purchases.appendRow([tokenHash, uid, productId, 0, 0, purchase.purchaseTimeMillis || "", new Date().toISOString()]);
      ss.getSheetByName("transactions").appendRow([
        new Date().toISOString(), uid, "watermark", productId, 0, 0, "", tokenHash
      ]);
    }
    const wallet = getWalletRow_(uid, true);
    wallet[4] = true;
    wallet[5] = new Date().toISOString();
    saveWalletRow_(uid, wallet);
    return { premiumOwned: true };
  });
}

function getPlayProductPurchase_(productId, purchaseToken) {
  const accessToken = getPlayAccessToken_();
  const url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
    encodeURIComponent(CONFIG.PACKAGE_NAME) + "/purchases/products/" +
    encodeURIComponent(productId) + "/tokens/" + encodeURIComponent(purchaseToken);
  const response = UrlFetchApp.fetch(url, {
    method: "get",
    headers: { Authorization: "Bearer " + accessToken },
    muteHttpExceptions: true
  });
  const code = response.getResponseCode();
  const data = JSON.parse(response.getContentText() || "{}");
  if (code !== 200) {
    console.error("Google Play API returned HTTP " + code + ": " + response.getContentText());
    throw new Error("Google Play could not verify this purchase. Check Play Console service-account permissions.");
  }
  return data;
}

function getPlayAccessToken_() {
  const cache = CacheService.getScriptCache();
  const cached = cache.get("PLAY_ACCESS_TOKEN");
  if (cached) return cached;

  const serviceAccount = JSON.parse(requiredProperty_("PLAY_SERVICE_ACCOUNT_JSON"));
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url_(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64Url_(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claim;
  const signature = Utilities.computeRsaSha256Signature(unsigned, serviceAccount.private_key);
  const assertion = unsigned + "." + Utilities.base64EncodeWebSafe(signature).replace(/=+$/, "");
  const response = UrlFetchApp.fetch("https://oauth2.googleapis.com/token", {
    method: "post",
    payload: {
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: assertion
    },
    muteHttpExceptions: true
  });
  const data = JSON.parse(response.getContentText() || "{}");
  if (response.getResponseCode() !== 200 || !data.access_token) {
    console.error("OAuth token exchange failed: " + response.getContentText());
    throw new Error("Could not authenticate the Google Play service account.");
  }
  cache.put("PLAY_ACCESS_TOKEN", data.access_token, Math.min(Number(data.expires_in || 3600) - 60, 3500));
  return data.access_token;
}

function startCreditRecording_(uid, body) {
  const sessionId = validSessionId_(body.sessionId);
  return withScriptLock_(function() {
    const now = Date.now();
    const ss = getSpreadsheet_();
    const sessions = ss.getSheetByName("sessions");
    const rows = readRows_(sessions);
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      if (String(row[1]) !== uid || String(row[2]) !== "active") continue;
      const last = Date.parse(row[4] || row[3] || "");
      if (!last || now - last > CONFIG.MAX_HEARTBEAT_GAP_SECONDS * 1000) {
        sessions.getRange(i + 2, 3).setValue("expired");
        sessions.getRange(i + 2, 6).setValue(new Date(now).toISOString());
      } else if (String(row[0]) !== sessionId) {
        throw new Error("Another recording session is already active.");
      }
    }

    const existing = findRow_(sessions, 1, sessionId);
    if (existing) throw new Error("Session ID has already been used.");
    const wallet = getWalletRow_(uid, true);
    const balance = Number(wallet[1]) || 0;
    if (balance <= 0) throw new Error("Recording credits are empty.");
    const iso = new Date(now).toISOString();
    sessions.appendRow([sessionId, uid, "active", iso, iso, "", balance]);
    return { sessionId: sessionId, remainingSeconds: balance };
  });
}

function heartbeatCreditRecording_(uid, body) {
  const sessionId = validSessionId_(body.sessionId);
  return withScriptLock_(function() {
    const ss = getSpreadsheet_();
    const sessions = ss.getSheetByName("sessions");
    const rowNumber = findRow_(sessions, 1, sessionId);
    if (!rowNumber) throw new Error("Recording session not found.");
    const session = sessions.getRange(rowNumber, 1, 1, HEADERS.sessions.length).getValues()[0];
    if (String(session[1]) !== uid || String(session[2]) !== "active") throw new Error("Recording session is not active.");

    const now = Date.now();
    const last = Date.parse(session[4]);
    if (!last) throw new Error("Session timestamp is invalid.");
    const elapsed = Math.max(0, Math.floor((now - last) / 1000));
    const expired = elapsed > CONFIG.MAX_HEARTBEAT_GAP_SECONDS;
    const wallet = getWalletRow_(uid, true);
    const balance = Math.max(0, Number(wallet[1]) || 0);
    const charge = Math.min(elapsed, CONFIG.MAX_CHARGE_PER_HEARTBEAT_SECONDS, balance);
    wallet[1] = balance - charge;
    wallet[3] = (Number(wallet[3]) || 0) + charge;
    wallet[5] = new Date(now).toISOString();
    saveWalletRow_(uid, wallet);

    const exhausted = wallet[1] <= 0;
    const status = expired ? "expired" : exhausted ? "exhausted" : "active";
    sessions.getRange(rowNumber, 3).setValue(status);
    sessions.getRange(rowNumber, 5).setValue(new Date(now).toISOString());
    sessions.getRange(rowNumber, 6).setValue(status === "active" ? "" : new Date(now).toISOString());
    sessions.getRange(rowNumber, 7).setValue(wallet[1]);
    if (charge > 0) {
      ss.getSheetByName("transactions").appendRow([
        new Date(now).toISOString(), uid, "usage", "", 0, -charge, sessionId, ""
      ]);
    }
    return {
      chargedSeconds: charge,
      remainingSeconds: wallet[1],
      expired: expired,
      exhausted: exhausted
    };
  });
}

function finishCreditRecording_(uid, body) {
  const sessionId = validSessionId_(body.sessionId);
  return withScriptLock_(function() {
    const ss = getSpreadsheet_();
    const sessions = ss.getSheetByName("sessions");
    const rowNumber = findRow_(sessions, 1, sessionId);
    if (!rowNumber) throw new Error("Recording session not found.");
    const session = sessions.getRange(rowNumber, 1, 1, HEADERS.sessions.length).getValues()[0];
    if (String(session[1]) !== uid) throw new Error("Session does not belong to this account.");
    if (String(session[2]) !== "active") {
      return { finished: true, remainingSeconds: Number(session[6]) || 0 };
    }

    const now = Date.now();
    const last = Date.parse(session[4]);
    const elapsed = last ? Math.max(0, Math.floor((now - last) / 1000)) : 0;
    const wallet = getWalletRow_(uid, true);
    const balance = Math.max(0, Number(wallet[1]) || 0);
    const charge = Math.min(elapsed, CONFIG.MAX_CHARGE_PER_HEARTBEAT_SECONDS, balance);
    wallet[1] = balance - charge;
    wallet[3] = (Number(wallet[3]) || 0) + charge;
    wallet[5] = new Date(now).toISOString();
    saveWalletRow_(uid, wallet);
    const iso = new Date(now).toISOString();
    sessions.getRange(rowNumber, 3).setValue("finished");
    sessions.getRange(rowNumber, 5).setValue(iso);
    sessions.getRange(rowNumber, 6).setValue(iso);
    sessions.getRange(rowNumber, 7).setValue(wallet[1]);
    if (charge > 0) {
      ss.getSheetByName("transactions").appendRow([
        iso, uid, "usage", "", 0, -charge, sessionId, ""
      ]);
    }
    return { finished: true, chargedSeconds: charge, remainingSeconds: wallet[1] };
  });
}

function getCreditBalance_(uid) {
  const wallet = getWalletRow_(uid, true);
  return {
    creditSeconds: Number(wallet[1]) || 0,
    totalPurchasedSeconds: Number(wallet[2]) || 0,
    totalUsedSeconds: Number(wallet[3]) || 0,
    premiumOwned: wallet[4] === true || String(wallet[4]).toLowerCase() === "true"
  };
}

function getWalletRow_(uid, createIfMissing) {
  const sheet = getSpreadsheet_().getSheetByName("wallets");
  const row = findRow_(sheet, 1, uid);
  if (row) return sheet.getRange(row, 1, 1, HEADERS.wallets.length).getValues()[0];
  if (!createIfMissing) return null;
  const fresh = [uid, 0, 0, 0, false, new Date().toISOString()];
  sheet.appendRow(fresh);
  return fresh;
}

function saveWalletRow_(uid, values) {
  const sheet = getSpreadsheet_().getSheetByName("wallets");
  const row = findRow_(sheet, 1, uid);
  if (row) sheet.getRange(row, 1, 1, HEADERS.wallets.length).setValues([values]);
  else sheet.appendRow(values);
}

function findRow_(sheet, column, value) {
  const last = sheet.getLastRow();
  if (last < 2) return 0;
  const found = sheet.getRange(2, column, last - 1, 1)
    .createTextFinder(String(value)).matchEntireCell(true).findNext();
  return found ? found.getRow() : 0;
}

function readRows_(sheet) {
  const last = sheet.getLastRow();
  return last < 2 ? [] : sheet.getRange(2, 1, last - 1, sheet.getLastColumn()).getValues();
}

function validSessionId_(value) {
  const id = String(value || "");
  if (!/^[A-Za-z0-9_-]{16,80}$/.test(id)) throw new Error("Invalid recording session ID.");
  return id;
}

function withScriptLock_(callback) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    return callback();
  } finally {
    lock.releaseLock();
  }
}

function getSpreadsheet_() {
  const id = requiredProperty_("SPREADSHEET_ID");
  return SpreadsheetApp.openById(id);
}

function requiredProperty_(name) {
  const value = PropertiesService.getScriptProperties().getProperty(name);
  if (!value) throw new Error("Missing Script Property: " + name);
  return value;
}

function sha256_(value) {
  const digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, value, Utilities.Charset.UTF_8);
  return Utilities.base64EncodeWebSafe(digest).replace(/=+$/, "");
}

function base64Url_(value) {
  return Utilities.base64EncodeWebSafe(value, Utilities.Charset.UTF_8).replace(/=+$/, "");
}

function json_(value) {
  return ContentService.createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}
