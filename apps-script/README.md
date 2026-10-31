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

1. Keep Firebase Authentication enabled in the existing Firebase project and enable the Anonymous sign-in provider. Anonymous Authentication is used only to obtain a Firebase ID token and is available without upgrading to Blaze. Do not disable the provider until a durable account-linking plan is ready.
2. Create a Google Sheet for CamRiderPro wallet data and copy its spreadsheet ID from the URL.
3. Open https://script.google.com and create a standalone Apps Script project.
4. Add `Code.gs` from this directory and set the project manifest to `appsscript.json`.
5. In **Project Settings → Script Properties**, add:
   - `SPREADSHEET_ID`: the spreadsheet ID from step 2.
   - `FIREBASE_WEB_API_KEY`: an API key for the Firebase Identity Toolkit API. You may use the key from `google-services.json` only if it is not restricted to Android-app-only usage; otherwise create a separate API key in Google Cloud Console, restrict it to the Identity Toolkit API, and leave application restrictions suitable for server-side Apps Script calls. This API key is not a private service-account credential.
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

## Android integration

The Android client has been migrated to call this Apps Script API and continues to use Firebase Authentication only to obtain ID tokens. Firestore and Firebase Functions Android dependencies have been removed.

Before building an APK/AAB with working credit features, configure the Apps Script deployment URL as a Gradle property or environment variable named `CREDIT_API_URL`. The value must be the deployed Web App URL ending in `/exec`.

- Local build: add `CREDIT_API_URL=https://script.google.com/macros/s/DEPLOYMENT_ID/exec` to `~/.gradle/gradle.properties` (or pass `-PCREDIT_API_URL=...`).
- GitHub Actions: add a repository variable named `CREDIT_API_URL` with the same URL and expose it to the Gradle build environment.

Do not release until the endpoint is configured and the following tests pass: authenticated wallet read, each of the three credit purchases, duplicate purchase-token replay, watermark purchase, start/heartbeat/finish session, insufficient balance, and network-loss behavior.
