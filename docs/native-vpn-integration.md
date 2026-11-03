# Android VPN integration

The Android app has a native Jetpack Compose screen, a foreground `VpnService`, and the Xray Android core. It imports VLESS, VMess, Trojan, and Shadowsocks links or Xray JSON locally, requests Android VPN consent, establishes an IPv4/IPv6 TUN, and starts Xray with the TUN file descriptor. During a connection, the app process is bound to the Android-selected underlying network so Xray's upstream sockets bypass the captured VPN without requiring privileged interface-index lookups; the binding is removed when startup fails or the tunnel stops. The Android package has no WebView or bundled web assets; the website remains a separate browser app. Xray JSON imports need exactly one supported outbound tagged `proxy`, matching the native connection status and traffic-counter behavior. The native screen also supports session-only Google sign-in and Gemini recommendations over a same-origin API; it does not yet provide QR scanning, personal ratings, or the full website settings dashboard.

Native identity uses Supabase Google OAuth/PKCE through the system browser with the callback `com.artesys.netcon.client://auth/callback`; it requests no Gmail permissions or scopes. Access/refresh tokens are held in memory. Users still provide a separate Gemini API key, held only for the current screen session. The app sends the purpose, that key, and public node metadata to the same-origin `/api/recommend` endpoint. The endpoint verifies the Supabase access token and uses the key only to authenticate the provider request; the key is not included in the model prompt. Imported server configurations and their credentials remain local and are rejected from recommendation input. Gemini may suggest only a listed public server and allow-listed DNS/routing presets; candidate servers are limited to enabled protocols and at most 30 nodes. The app imports the server and selects the suggestions for review, but never connects automatically.

This implementation has **not yet passed physical-device acceptance testing**. A successful Gradle build or Xray startup check is not proof that internet traffic is routed correctly or that DNS/IP leaks are prevented. Do not describe it as verified until the acceptance tests below pass on supported Android versions and networks.

The launcher presets are demonstration endpoints and cannot connect. Import a valid VLESS, VMess, Trojan, or Shadowsocks configuration. The native service currently accepts a literal DNS server IP; it does not support an HTTPS resolver URL. The built-in LAN-bypass route uses explicit RFC1918, IPv4 link-local, IPv6 ULA, and IPv6 link-local ranges, so it does not depend on a `geoip.dat` file. Imported custom Xray rules that refer to GeoIP/GeoSite data still require those data files in the core's data directory. Latency is reported as unmeasured until a real device-side probe is implemented.

Configurations and credentials are saved only in the native app's private preferences, with Android backup disabled. Do not expose configs through public HTTP endpoints, logs, analytics, or AI requests. The UI polls service-confirmed state rather than treating a start request as a connected tunnel.

## Protection
- Capture DNS through TUN and route resolver queries through the selected policy; plain resolver IPs do not imply encrypted DNS. Validate custom resolvers and DNS/IP/domain routing interaction.
- On core startup failure after TUN establishment, keep the TUN and foreground service active so traffic is blocked rather than falling back to the physical network. A user-requested disconnect closes the TUN.
- Android Always-on VPN plus “Block connections without VPN” must be enabled by the user in system VPN settings to protect traffic if Android stops the service/process or after reboot. The app cannot enable this setting or reliably report that lockdown is active.
- Bypass routing through a captured TUN and direct Xray outbound differs from excluding apps or destinations from VpnService. Android lockdown can block excluded traffic. Explain and test this conflict before claiming both bypass and lockdown work.
- DNS/routing JSON exports alone do not configure Android's VPN interface or enforce a Kill Switch.

## Measurements
Emit a unique session ID for each connection. The UI polls the native service status and counters; never fall back to generated telemetry in native mode.

Define received/sent bytes from the user's perspective; use consistent Xray session stats or TUN stats without summing both (double counting). Poll local counters, validate monotonic deltas and timestamps, and convert bytes/s to Mbps with `bytes * 8 / 1_000_000`, bytes to displayed MB with `bytes / 1_000_000`. Session resets must reset odometer wheels explicitly. Keep the stats API local and inaccessible to arbitrary web content.

Latency probes must make a bounded end-to-end request through each node from the Android device, with concurrency limits and cancellation. Null means unmeasured/failed; do not substitute server-side or browser HTTP latency. Record measurement age and network identity; clear stale results when networks change.

## Public directory
The web client reads FastNodes directly and parses locally. Location comes only from the source's country-specific feed, is labeled source-reported, and is not proof of exit location. The top feed has unknown locations. Personal star ratings use local device storage; they are not community ratings or endorsements. Public servers are untrusted and are never selected/connected automatically. Source failures do not restore demo nodes. No real latency is currently measured.

Source: https://github.com/rtwo2/FastNodes . Usage terms include attribution, no standalone sale of raw data, lawful use, and no warranty. Terms are retained in `docs/fastnodes-license.txt`. Third-party configurations may have additional rights/risks; aggregation is not a safety guarantee.

## Android acceptance gates (not yet run)
1. Install on a physical Android device; grant VPN consent and confirm a real exit IP via independently operated HTTPS endpoints.
2. Verify DNS, IPv4 and IPv6 leaks; TCP/UDP, network handover and reconnect.
3. Verify global, LAN/domestic bypass and ad-block policies with known destinations and packaged data files.
4. Kill Xray, kill the app, revoke network, reboot with lockdown enabled: prove no unprotected traffic escapes.
5. Validate bytes against known downloads/uploads, session resets, stale events and device network changes.
6. Confirm arbitrary web origins cannot call the native adapter and credentials never enter logs/AI requests.