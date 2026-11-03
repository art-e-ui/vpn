package com.artesys.netcon

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getPreferences(MODE_PRIVATE) }
    private var savedConfig by mutableStateOf("")
    private var savedName by mutableStateOf("")
    private var feedback by mutableStateOf("")
    private var googleEmail by mutableStateOf("")
    private var authBusy by mutableStateOf(false)
    private var pendingConfig = ""
    private var supabaseSession: NativeSupabaseAuth.Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetconVpnService.logDiagnostic("Application started")
        window.statusBarColor = android.graphics.Color.rgb(245, 246, 248)
        window.navigationBarColor = android.graphics.Color.rgb(245, 246, 248)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        savedConfig = preferences.getString("xray_config", "") ?: ""
        savedName = preferences.getString("server_name", "") ?: ""
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
                feedback = feedback,
                googleEmail = googleEmail,
                authBusy = authBusy,
                onFeedbackDismiss = { feedback = "" },
                onImport = ::importConfiguration,
                onFetchPublicNodes = { countryCode ->
                    withContext(Dispatchers.IO) { NativePublicDirectory.fetch(countryCode) }
                },
                onRecommend = { purpose, apiKey, nodes ->
                    try {
                        NativeGeminiRecommender.recommend(
                            purpose,
                            apiKey,
                            getSupabaseAccessToken(),
                            nodes,
                        )
                    } catch (error: NativeSupabaseAuth.SessionExpiredException) {
                        supabaseSession = null
                        googleEmail = ""
                        throw error
                    }
                },
                onGoogleSignIn = ::signInWithGoogle,
                onGoogleSignOut = ::signOutGoogle,
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
                    val config = NativeXrayConfig.configuredXrayJson(savedConfig, dnsAddress, routingMode)
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
        val callback = intent.data
        if (callback?.scheme == "com.artesys.netcon.client" &&
            callback.host == "auth" &&
            callback.path == "/callback"
        ) {
            completeGoogleSignIn(callback)
            return
        }
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!shared.isNullOrBlank()) importConfiguration(shared.trim())
    }

    private fun signInWithGoogle() {
        if (authBusy) return
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            feedback = "Google sign-in is not configured in this build."
            return
        }
        authBusy = true
        feedback = "Opening Google sign-in…"
        lifecycleScope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    NativeSupabaseAuth.createAuthorizationRequest(
                        BuildConfig.SUPABASE_URL,
                        BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    )
                }
                preferences.edit().putString(PENDING_AUTH_VERIFIER, request.codeVerifier).apply()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)))
                feedback = "Complete sign-in in your browser."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                preferences.edit().remove(PENDING_AUTH_VERIFIER).apply()
                feedback = error.message ?: "Unable to start Google sign-in."
            } finally {
                authBusy = false
            }
        }
    }

    private fun completeGoogleSignIn(callback: Uri) {
        val verifier = preferences.getString(PENDING_AUTH_VERIFIER, "").orEmpty()
        preferences.edit().remove(PENDING_AUTH_VERIFIER).apply()
        val code = callback.getQueryParameter("code").orEmpty()
        if (verifier.isBlank() || code.isBlank() || callback.getQueryParameter("error") != null) {
            feedback = "Google sign-in was cancelled or could not be verified. Please try again."
            return
        }
        authBusy = true
        lifecycleScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    NativeSupabaseAuth.exchangeAuthorizationCode(
                        BuildConfig.SUPABASE_URL,
                        BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                        code,
                        verifier,
                    )
                }
                supabaseSession = session
                googleEmail = session.email.ifBlank { "Google account" }
                feedback = "Signed in with Google."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                supabaseSession = null
                googleEmail = ""
                feedback = error.message ?: "Unable to complete Google sign-in."
            } finally {
                authBusy = false
            }
        }
    }

    private fun signOutGoogle() {
        val session = supabaseSession
        supabaseSession = null
        googleEmail = ""
        preferences.edit().remove(PENDING_AUTH_VERIFIER).apply()
        feedback = "Signed out on this device."
        if (session != null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        NativeSupabaseAuth.revokeSession(
                            BuildConfig.SUPABASE_URL,
                            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                            session,
                        )
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    feedback = "Signed out on this device, but the remote session could not be revoked."
                }
            }
        }
    }

    private suspend fun getSupabaseAccessToken(): String {
        val current = supabaseSession
            ?: throw IllegalStateException("Sign in with Google before requesting a recommendation.")
        return try {
            val refreshed = withContext(Dispatchers.IO) {
                NativeSupabaseAuth.refreshIfNeeded(
                    BuildConfig.SUPABASE_URL,
                    BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    current,
                )
            }
            supabaseSession = refreshed
            refreshed.accessToken
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            supabaseSession = null
            googleEmail = ""
            throw IllegalStateException("Your Google session expired. Sign in again.", error)
        }
    }

    private fun importConfiguration(raw: String): Boolean {
        return try {
            val imported = NativeXrayConfig.fromInput(raw)
            saveServer(imported.config.toString(), imported.name)
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
        saveServer(node.configJson, node.name)
        feedback = "Free public server imported. It is untrusted and has not been tested."
    }

    private fun saveServer(config: String, name: String) {
        savedConfig = config
        savedName = name
        preferences.edit()
            .putString("xray_config", savedConfig)
            .putString("server_name", savedName)
            .remove("server_location")
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

    private companion object {
        const val PENDING_AUTH_VERIFIER = "supabase_pending_auth_verifier"
    }
}