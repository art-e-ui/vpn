package com.artesys.netcon

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getPreferences(MODE_PRIVATE) }
    private var savedConfig by mutableStateOf("")
    private var savedName by mutableStateOf("")
    private var serverLocation by mutableStateOf("")
    private var serverLatitude by mutableStateOf<Double?>(null)
    private var serverLongitude by mutableStateOf<Double?>(null)
    private var feedback by mutableStateOf("")
    private var pendingConfig = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetconVpnService.logDiagnostic("Application started")
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true
        savedConfig = preferences.getString("xray_config", "") ?: ""
        savedName = preferences.getString("server_name", "") ?: ""
        serverLocation = preferences.getString("server_location", "") ?: ""
        serverLatitude = preferences.getString("server_latitude", null)?.toDoubleOrNull()
        serverLongitude = preferences.getString("server_longitude", null)?.toDoubleOrNull()
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && pendingConfig.isNotBlank()) {
                    NetconVpnService.logDiagnostic("Android VPN permission granted")
                    startVpn(pendingConfig)
                } else {
                    NetconVpnService.logDiagnostic("Android VPN permission denied")
                    feedback = "Android VPN permission was not granted."
                }
                pendingConfig = ""
            }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val config = pendingConfig
                pendingConfig = ""
                if (config.isNotBlank()) {
                    if (!granted) feedback = "Notifications are disabled; VPN status may be less visible."
                    NetconVpnService.logDiagnostic(if (granted) "Notification permission granted" else "Notification permission denied")
                    requestVpnPermissionAndStart(config, permissionLauncher)
                }
            }
            fun startWithNotificationPermission(config: String) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingConfig = config
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requestVpnPermissionAndStart(config, permissionLauncher)
                }
            }
            NetconDashboard(
                serverName = savedName,
                protocol = protocolName(savedConfig),
                serverLocation = serverLocation,
                serverLatitude = serverLatitude,
                serverLongitude = serverLongitude,
                onSaveLocation = { name, latitude, longitude ->
                    serverLocation = name
                    serverLatitude = latitude
                    serverLongitude = longitude
                    preferences.edit()
                        .putString("server_location", name)
                        .putString("server_latitude", latitude.toString())
                        .putString("server_longitude", longitude.toString())
                        .apply()
                },
                feedback = feedback,
                onFeedbackDismiss = { feedback = "" },
                onImport = ::importConfiguration,
                onFetchPublicNodes = { countryCode ->
                    withContext(Dispatchers.IO) { NativePublicDirectory.fetch(countryCode) }
                },
                onRecommend = { purpose, apiKey, nodes ->
                    NativeGeminiRecommender.recommend(purpose, apiKey, nodes)
                },
                onSelectPublicNode = ::selectPublicNode,
                onConnect = {
                    if (savedConfig.isBlank()) {
                        feedback = "Import a server configuration before connecting."
                    } else {
                        startWithNotificationPermission(savedConfig)
                    }
                },
                onCopyLog = {
                    NetconVpnService.logDiagnostic("Diagnostic log copied to clipboard")
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "Netcon diagnostic log",
                            NetconVpnService.getDiagnosticLog(),
                        ),
                    )
                    feedback = "Diagnostic log copied. Review it before sharing."
                },
                onDisconnect = { disconnectVpn() },
                onBlockedProtocol = { feedback = "Enable this server protocol in Settings before connecting." },
                onStartConfigured = { dnsAddress, routingMode ->
                    val config = configuredXrayJson(savedConfig, dnsAddress, routingMode)
                    startWithNotificationPermission(config)
                },
                onOpenVpnSettings = {
                    try {
                        startActivity(Intent("android.net.vpn.SETTINGS"))
                    } catch (_: Exception) {
                        feedback = "Unable to open Android VPN settings."
                    }
                },
                onExport = {
                    if (savedConfig.isBlank()) {
                        feedback = "Import a configuration before exporting."
                    } else {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Xray JSON", savedConfig))
                        feedback = "Xray configuration copied to clipboard."
                    }
                },
            )
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank()) importConfiguration(shared.trim())
    }

    private fun importConfiguration(raw: String): Boolean {
        return try {
            val imported = NativeXrayConfig.fromInput(raw)
            saveServer(imported.config.toString(), imported.name, "")
            NetconVpnService.logDiagnostic("Configuration imported successfully")
            feedback = "Configuration saved on this device."
            true
        } catch (error: Exception) {
            NetconVpnService.logDiagnostic("Configuration import failed (${error.javaClass.simpleName})")
            feedback = error.message ?: "Invalid server configuration."
            false
        }
    }

    private fun selectPublicNode(node: NativePublicDirectory.Node) {
        val country = node.countryCode?.let { "${node.country} · source-reported" }.orEmpty()
        saveServer(node.configJson, node.name, country)
        feedback = "Free public server imported. It is untrusted and has not been tested."
    }

    private fun saveServer(config: String, name: String, location: String) {
        savedConfig = config
        savedName = name
        serverLocation = location
        serverLatitude = null
        serverLongitude = null
        preferences.edit()
            .putString("xray_config", savedConfig)
            .putString("server_name", savedName)
            .putString("server_location", serverLocation)
            .remove("server_latitude")
            .remove("server_longitude")
            .apply()
    }

    private fun startVpn(config: String) {
        try {
            NetconVpnService.logDiagnostic("VPN service start requested")
            val intent = Intent(this, NetconVpnService::class.java)
                .setAction(NetconVpnService.ACTION_CONNECT)
                .putExtra(NetconVpnService.EXTRA_CONFIG, config)
            ContextCompat.startForegroundService(this, intent)
            feedback = ""
        } catch (error: Exception) {
            NetconVpnService.logDiagnostic("VPN service start failed (${error.javaClass.simpleName})")
            feedback = error.message ?: "Unable to start the Android VPN service."
        }
    }

    private fun requestVpnPermissionAndStart(
        config: String,
        permissionLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>,
    ) {
        NetconVpnService.logDiagnostic("Android VPN permission check started")
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startVpn(config)
        } else {
            pendingConfig = config
            permissionLauncher.launch(permissionIntent)
        }
    }

    private fun disconnectVpn() {
        NetconVpnService.logDiagnostic("VPN disconnect requested")
        startService(Intent(this, NetconVpnService::class.java).setAction(NetconVpnService.ACTION_DISCONNECT))
    }

    private fun protocolName(config: String): String {
        if (config.isBlank()) return ""
        return try {
            val outbounds = JSONObject(config).optJSONArray("outbounds")
            (0 until (outbounds?.length() ?: 0))
                .mapNotNull { outbounds?.optJSONObject(it) }
                .firstOrNull { it.optString("tag") == "proxy" }
                ?.optString("protocol")
                ?.uppercase(Locale.ROOT)
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun configuredXrayJson(config: String, dnsAddress: String, routingMode: String): String {
        val root = JSONObject(config)
        root.put("dns", JSONObject().put("servers", JSONArray().put(dnsAddress)))
        val routing = root.optJSONObject("routing") ?: JSONObject()
        val rules = JSONArray()
        if (routingMode == "Bypass LAN") {
            rules.put(JSONObject()
                .put("type", "field")
                .put("ip", JSONArray().put("geoip:private"))
                .put("outboundTag", "direct"))
        } else if (routingMode == "Rule-based") {
            val existing = routing.optJSONArray("rules")
            if (existing != null) {
                for (index in 0 until existing.length()) rules.put(existing.get(index))
            }
        }
        routing.put("rules", rules)
        root.put("routing", routing)
        return root.toString()
    }
}