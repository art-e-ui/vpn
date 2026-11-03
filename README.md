# Netcon VPN (Android)

Netcon VPN is a native Android VPN application built with Jetpack Compose, Material 3, Android `VpnService`, and an embedded Xray core.

## Project Structure

- `android/`: Android native project (Kotlin, Jetpack Compose, Gradle).
- `scripts/`: Build and dependency utility scripts (e.g. `fetch-android-core.sh` to download `libv2ray.aar`).
- `docs/`: Technical documentation including release and architecture notes.
- `assets/`: Application branding assets.

## Build Android debug APK and AAB

Requirements: Java 21 and Android SDK platform/build tools 36.

```sh
bash scripts/fetch-android-core.sh
cd android
./gradlew testDebugUnitTest assembleDebug bundleDebug
```

Outputs:

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- AAB: `android/app/build/outputs/bundle/debug/app-debug.aab`

These debug artifacts use the debug signing key and are for testing, not Play Store publishing. See [docs/android-release.md](./docs/android-release.md) for signed release builds.
