# CamRiderPro Google Apps Script Credit Backend

This backend replaces Firebase Cloud Functions and Firestore wallet writes with Google Apps Script + Google Sheets. Firebase Authentication remains enabled on the Spark plan and is used only to authenticate the Android user. No Firebase Blaze upgrade is required for this backend.

## What it provides

- Server-side Google Play product purchase verification for:
  - `1._1000_credit`: 1,000 credits / 7,200 seconds
  - `2._2000_credit`: 2,000 credits / 14,400 seconds
  - `5._5000_credit`: 5,000 credits / 39,600 seconds
- Idempotent purchase grants keyed by a SHA-256 hash of the Play purchase token.
- Server-owned credit wallet and transaction ledger in Google Sheets.
- Server-timestamp recording sessions with 15-second client heartbeat recommendation.
- Permanent watermark entitlement endpoint for the legacy product ID `remove_watermark`.

## Important limitations

Apps Script is suitable for a low-volume pilot, not a high-concurrency production wallet. Apps Script has execution and daily quotas; Google Sheets is not a transactional database. This implementation serializes mutations with ScriptLock, but a VPS/Cloud Run/managed database is the better long-term production design. Do not advertise guaranteed billing continuity during network loss. The Android app must stop a credit-funded recording when the backend cannot confirm its heartbeat.

## Setup

1. Keep Firebase Authentication enabled in the existing Firebase project. Anonymous Authentication is used to obtain a Firebase ID token. Do not disable the provider until a durable account-linking plan is ready.
2. Create a Google Sheet for CamRiderPro wallet data and copy its spreadsheet ID from the URL.
3. Open https://script.google.com and create a standalone Apps Script project.
4. Add `Code.gs` from this directory and set the project manifest to `appsscript.json`.
5. In **Project Settings → Script Properties**, add:
   - `SPREADSHEET_ID`: the spreadsheet ID from step 2.
   - `FIREBASE_WEB_API_KEY`: the API key from the existing app's `google-services.json` (this is a Firebase client API key, not a private credential).
   - `PLAY_SERVICE_ACCOUNT_JSON`: the complete contents of the Google Play service-account JSON private-key file. Never put this value in GitHub, Android resources, or a spreadsheet.
6. In the Apps Script editor, select and run `setupDatabase` once. Review and grant the requested spreadsheet/external-request permissions.
7. Enable **Google Play Android Developer API** in the Google Cloud project associated with the service account. In Play Console → Users and permissions, grant the service-account email permission to view financial/order data needed to verify purchases.
8. Deploy → New deployment → Web app:
   - Execute as: **Me**
   - Who has access: **Anyone**
   - Copy the `/exec` deployment URL. The endpoint is publicly reachable, but every action requires a valid Firebase Authentication ID token.
9. Do not share the private key or paste it into chat. Share only the deployment URL when ready.

## API contract

Every POST body is JSON and includes `idToken` (a current Firebase Authentication ID token) and `action`.

- `getCreditBalance`
- `verifyCreditPurchase` with `productId`, `purchaseToken`
- `verifyWatermarkPurchase` with `productId`, `purchaseToken`
- `startCreditRecording` with `sessionId`
- `heartbeatCreditRecording` with `sessionId`
- `finishCreditRecording` with `sessionId`

Successful responses have `{ "ok": true, "data": ... }`; failures have `{ "ok": false, "error": "..." }`.

## Android integration status

The Android app still points at the previous Firebase Functions/Firestore repository until the Apps Script deployment URL is known and the Android client migration is completed. Do not release this backend as active until the app's repository is migrated, the URL is configured, and the end-to-end purchase/session tests pass.
