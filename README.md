# CamRider Pro

[![Android Build](https://github.com/diycraftchannel83-create/Alangcam/actions/workflows/build-android.yml/badge.svg)](https://github.com/diycraftchannel83-create/Alangcam/actions/workflows/build-android.yml)

CamRider Pro is an Android motorcycle and vehicle dash camera with GPS speed tracking and telemetry overlays. It combines camera recording, ride data capture, and an on-screen cockpit display so important trip information can be reviewed in the app and included in exported recordings.

> **Project status:** Active development. Features and screens may change between versions.

## Features

- **Camera recording** using Android CameraX.
- **GPS speed tracking** with live speed and ride telemetry.
- **Telemetry HUD** designed for portrait and landscape layouts.
- **Speedometer display** with digital speed information.
- **Ride history** backed by a local Room database.
- **Recorded video export** with a telemetry overlay.
- **Settings and localization** managed in the app UI.
- **Billing integration** for in-app purchase or monetization flows.
- **Automated Android builds and releases** through GitHub Actions.

Feature availability may depend on device hardware, Android permissions, GPS reception, and the current app build.

## Screenshots and assets

The repository contains design and branding assets, including:

- [CamRider artwork](./CamRider.png)
- [Recording watermark](./app/src/main/res/drawable-nodpi/watermark_camrider.png)

## Tech stack

- Kotlin
- Jetpack Compose and Material 3
- Android CameraX
- AndroidX Media3
- Google Play Services Location
- Room database
- Kotlin Coroutines
- Gradle Kotlin DSL
- GitHub Actions for CI and release automation

## Requirements

- Android Studio with a compatible Android SDK.
- JDK 11 or a compatible JDK supported by the configured Gradle / Android Gradle Plugin toolchain.
- Android device or emulator running **Android 7.0 (API 24) or later**.
- Google Play Services on the device for location features.

The app module currently declares `minSdk 24`, `targetSdk 36`, and `compileSdk 36.1`. Use the versions configured in the project rather than arbitrarily downgrading the Android toolchain.

## Build from source

1. Clone the repository:

   ```bash
   git clone https://github.com/diycraftchannel83-create/Alangcam.git
   cd Alangcam
   ```

2. Open the project in Android Studio and allow Gradle sync to finish.

3. Build a debug APK:

   ```bash
   ./gradlew assembleDebug
   ```

   On Windows PowerShell:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

4. Find the APK at:

   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

To build a release APK:

```bash
./gradlew assembleRelease
```

A release build requires the signing credentials described below.

## Release signing

The release signing configuration reads credentials from environment variables:

| Variable | Purpose |
| --- | --- |
| `KEYSTORE_PATH` | Path to the upload/release keystore. Defaults to `my-upload-key.jks` in the project root. |
| `STORE_PASSWORD` | Keystore password. |
| `KEY_ALIAS` | Key alias. Defaults to `upload`. |
| `KEY_PASSWORD` | Key password. |

Keep keystore files and passwords private. Never commit signing keys, passwords, API keys, or populated `.env` files to the repository. For CI releases, configure the required values as GitHub Actions secrets. A debug build uses the project debug signing configuration and does not require the release keystore.

The repository includes `.env.example` as a template for local environment configuration.

## GitHub Actions

Workflow definitions are in [`.github/workflows/`](./.github/workflows/):

- [Build Android](./.github/workflows/build-android.yml)
- [Build APK](./.github/workflows/build-apk.yml)
- [Release Android](./.github/workflows/release-android.yml)

Check the **Actions** tab for workflow execution details and generated artifacts. Release workflows may require repository secrets to be configured before they can sign and publish release packages.

## Permissions and device considerations

Camera, location, and media/export operations require the relevant Android permissions. Grant permissions when prompted for the corresponding feature. GPS accuracy and update availability depend on device sensors, location settings, network conditions, and the surrounding environment.

Always mount the device securely and obey local traffic laws. Do not operate the app or adjust the device while riding; start and configure recordings before moving.

## Contributing

Contributions, bug reports, and feature suggestions are welcome. Before submitting a pull request:

1. Describe the problem and the intended change.
2. Keep changes focused and compatible with the existing Kotlin/Gradle setup.
3. Run the relevant unit tests and build tasks.
4. Include screenshots or logs when reporting UI or recording issues. Remove private information from logs.

## License

No license file is currently provided in this repository. Unless a license is added, the source is not granted an open-source license; contact the repository owner for permission to reuse or redistribute it.

## Repository

https://github.com/diycraftchannel83-create/Alangcam
