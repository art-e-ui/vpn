# Android app and GitHub releases

The Android app uses a native Jetpack Compose screen and Android `VpnService` with the bundled Xray core. It does not load a WebView or package the separate browser interface. The native app accepts one VLESS, VMess, Trojan, or Shadowsocks link at a time, as well as Xray JSON with one supported outbound tagged `proxy`; parsing happens on-device. Imported configuration is stored in private app preferences with Android backup disabled. The app also accepts shared text and direct configuration links. Native Gemini recommendations require optional Google sign-in and the user's separate, session-only Gemini API key; QR scanning and the full website settings remain web-only.

Google sign-in uses Supabase OAuth with PKCE and the system browser; it requests no Gmail scopes. The user access and refresh tokens remain in memory, and the Gemini key remains in the current screen session. Recommendations are sent to the same-origin `/api/recommend` endpoint, which verifies the Supabase session and uses the key only for provider authentication. The model receives the user's purpose and public node metadata, never configuration links, credentials, or the Gemini key. It can suggest only an available public server using a protocol enabled in CONFIG, with at most 30 candidates, and allow-listed DNS/routing presets. Suggestions are imported locally for review and never start the VPN automatically. The deployed site must serve `/api/recommend` and configure `SUPABASE_URL` plus `SUPABASE_PUBLISHABLE_KEY` as server-side variables.

## Local Android project

Requirements: Java 21, Android Studio, and Android SDK platform/build tools.

```sh
bash scripts/fetch-android-core.sh
cd android
./gradlew assembleDebug
```

Run `./gradlew testDebugUnitTest assembleDebug bundleDebug` to run the native parser tests and build debug APK/AAB artifacts. The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`; the AAB is written to `android/app/build/outputs/bundle/debug/app-debug.aab`. Both are debug-signed and are for testing, not Play Store publishing. No web build or Capacitor sync is part of Android packaging. To regenerate Android launcher assets after changing the shared icon, run `npm run android:assets`.

To test Google sign-in locally, provide `ANDROID_SUPABASE_URL` and `ANDROID_SUPABASE_PUBLISHABLE_KEY` in the Gradle process environment and add the callback URI below to the Supabase redirect allowlist. Without these build values, sign-in is disabled in the APK.

## Configure GitHub releases

Add the following Actions secrets under **Settings > Secrets and variables > Actions**:

- Secret `ANDROID_KEYSTORE_BASE64`: base64-encoded Android release keystore.
- Secret `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- Secret `ANDROID_KEY_ALIAS`: signing key alias.
- Secret `ANDROID_KEY_PASSWORD`: signing key password.

Add these public Actions variables under **Settings > Secrets and variables > Actions > Variables** so release builds can enable Google sign-in:

- Variable `ANDROID_SUPABASE_URL`: the Supabase project URL (HTTPS).
- Variable `ANDROID_SUPABASE_PUBLISHABLE_KEY`: the Supabase publishable key. This public client key is not a service-role secret.

In Supabase Authentication URL configuration, allow `com.artesys.netcon.client://auth/callback` as a redirect URL. The Supabase Google provider must be enabled. Google identity does not grant Gemini API access; users still provide their own Gemini key in the app.

Generate a release keystore once and keep it backed up securely; losing it prevents updates from being signed as the same Android app:

```sh
keytool -genkeypair -v -keystore netcon-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias netcon
base64 -w 0 netcon-release.jks
```

Store the encoded output as `ANDROID_KEYSTORE_BASE64` and never commit the keystore or passwords. Push a new version tag such as `v1.0.4` to build, sign, verify, and attach `Netcon.apk` to a GitHub Release:

```sh
git tag v1.0.4
git push origin v1.0.4
```

If a tag push does not start Actions, run **Android release** from the Actions tab with the existing tag as `release_tag`. The manual run checks out that tag and publishes the same signed artifacts.

The release APK is signed for direct installation. Its version name comes from the tag, and its version code increments with each workflow run. Google Play distribution may require a separately configured Android App Bundle and Play signing.
