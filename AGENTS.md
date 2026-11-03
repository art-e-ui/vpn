# Netcon VPN Developer Instructions

## Android Core & Testing
- Before building or testing the Android application, fetch the Xray core AAR via:
  ```sh
  bash scripts/fetch-android-core.sh
  ```
- Run unit tests with:
  ```sh
  cd android && ./gradlew testDebugUnitTest
  ```
- Build debug artifacts with:
  ```sh
  cd android && ./gradlew assembleDebug bundleDebug
  ```

## Architecture
- Native Jetpack Compose application targeting modern Android.
- Utilizes Android `VpnService` with local SOCKS/HTTP inbounds and TUN device routing.
- Bundles Xray core (`libv2ray.aar`) for proxy protocol support (VLESS, VMess, Trojan, Shadowsocks).
