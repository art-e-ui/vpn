package com.artesys.netcon

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale

private val DeepInk = Color(0xFFF5F6F8)
private val Shadow = Color(0xFFEBEEF1)
private val Steel = Color(0xFFE3E7EB)
private val Tray = Color(0xFFF9FAFB)
private val Bevel = Color(0xFFB8C0C8)
private val Copper = Color(0xFFB65F32)
private val CopperLight = Color(0xFFF0D7BC)
private val CopperDark = Color(0xFF7A3D1B)
private val Engrave = Color(0xFF252A30)
private val EngraveLight = Color(0xFF68717B)
private val Online = Color(0xFF4CAF50)
private val Offline = Color(0xFFE53935)

private enum class DashboardTab(val label: String) {
    Connection("CONNECTION"),
    Settings("CONFIG"),
    Info("INFO"),
}

@Composable
internal fun NetconDashboard(
    serverName: String,
    protocol: String,
    feedback: String,
    googleEmail: String,
    authBusy: Boolean,
    onFeedbackDismiss: () -> Unit,
    onImport: (String) -> Boolean,
    onFetchPublicNodes: suspend (String) -> NativePublicDirectory.Result,
    onRecommend: suspend (String, String, List<NativePublicDirectory.Node>) -> NativeGeminiRecommender.Recommendation,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onSelectPublicNode: (NativePublicDirectory.Node) -> Unit,
    onConnect: () -> Unit,
    onCopyLog: () -> Unit,
    onDisconnect: () -> Unit,
    onBlockedProtocol: () -> Unit,
    onStartConfigured: (String, String) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onExport: () -> Unit,
) {
    val vpnStatus by produceState(initialValue = NetconVpnService.getStatus()) {
        while (true) {
            value = NetconVpnService.getStatus()
            delay(1000)
        }
    }
    var tab by rememberSaveable { mutableStateOf(DashboardTab.Connection) }
    var serverDialog by rememberSaveable { mutableStateOf(false) }
    var purpose by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var recommendationMessage by remember { mutableStateOf("") }
    var recommendationBusy by remember { mutableStateOf(false) }
    var dns by rememberSaveable { mutableStateOf("1.1.1.1") }
    var route by rememberSaveable { mutableStateOf("Bypass LAN") }
    var enabledProtocols by rememberSaveable { mutableStateOf(setOf("vless", "vmess", "trojan", "shadowsocks")) }
    val coroutineScope = rememberCoroutineScope()
    val connected = vpnStatus.state == "connected"
    val connecting = vpnStatus.state == "connecting"
    val errorState = vpnStatus.state == "error"
    val duration = "%02d:%02d:%02d".format(
        vpnStatus.elapsedSeconds / 3600,
        vpnStatus.elapsedSeconds / 60 % 60,
        vpnStatus.elapsedSeconds % 60,
    )
    val downloadMbps = vpnStatus.downloadBytesPerSecond * 8 / 1_000_000
    val uploadMbps = vpnStatus.uploadBytesPerSecond * 8 / 1_000_000

    fun askGemini() {
        if (recommendationBusy) return
        if (googleEmail.isBlank()) {
            recommendationMessage = "Sign in with Google before asking Gemini for a recommendation."
            return
        }
        if (apiKey.isBlank()) {
            recommendationMessage = "Enter your Gemini API key. It stays in this screen session."
            return
        }
        if (purpose.trim().startsWith("{") || purpose.trim().startsWith("[") ||
            Regex("(?i)(vmess|vless|trojan|ss)://|[\"']?(uuid|password|privateKey|alterId)[\"']?\\s*:")
                .containsMatchIn(purpose.trim())
        ) {
            recommendationMessage = "Paste configuration links into the local import field; credentials are never sent to Gemini."
            return
        }
        recommendationBusy = true
        recommendationMessage = "Loading public server metadata…"
        coroutineScope.launch {
            try {
                val result = withTimeout(25_000) { onFetchPublicNodes("") }
                if (result.nodes.isEmpty()) throw IllegalStateException("No supported public servers were found in the top feed.")
                val availableNodes = NativeGeminiRecommender.filterEnabledNodes(result.nodes, enabledProtocols)
                if (availableNodes.isEmpty()) {
                    throw IllegalStateException("Enable at least one server protocol in CONFIG before asking Gemini.")
                }
                recommendationMessage = "Asking Gemini to choose from ${availableNodes.size} enabled, untrusted public servers…"
                val recommendation = withTimeout(25_000) {
                    onRecommend(purpose.trim(), apiKey, availableNodes)
                }
                val selected = availableNodes.firstOrNull { it.id == recommendation.nodeId }
                    ?: throw IllegalStateException("Gemini selected a server outside the downloaded list.")
                if (selected.protocol !in enabledProtocols) {
                    throw IllegalStateException(
                        "The recommended protocol was disabled during the request. Enable it in CONFIG and try again.",
                    )
                }
                onSelectPublicNode(selected)
                dns = recommendation.dnsAddress
                route = recommendation.routingMode
                recommendationMessage =
                    "${recommendation.reason} Imported locally. Suggested DNS ${recommendation.dnsAddress} and ${recommendation.routingMode} routing are selected in CONFIG; review before connecting. Public servers are untrusted and unmeasured."
            } catch (error: Exception) {
                recommendationMessage = error.message ?: "Unable to get a Gemini recommendation."
            } finally {
                recommendationBusy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Steel, Color(0xFFF0F2F4), DeepInk),
                    radius = 1250f,
                ),
            ),
    ) {
        Image(
            painter = painterResource(R.drawable.brushed_steel),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.025f),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(listOf(Shadow, Steel, Bevel, Steel, Shadow)),
                    )
                    .border(2.dp, Bevel, RoundedCornerShape(8.dp))
                    .padding(horizontal = 13.dp, vertical = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandPlate()
                Spacer(Modifier.height(9.dp))
                StatusBadge(vpnStatus.state)
                Spacer(Modifier.height(14.dp))
                DashboardNavigation(tab, onSelect = { tab = it })
                Spacer(Modifier.height(14.dp))
                when (tab) {
                    DashboardTab.Connection -> ConnectionScreen(
                        purpose = purpose,
                        onPurposeChange = { purpose = it },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it },
                        recommendationMessage = recommendationMessage,
                        recommendationBusy = recommendationBusy,
                        onAskGemini = ::askGemini,
                        googleEmail = googleEmail,
                        authBusy = authBusy,
                        onGoogleSignIn = onGoogleSignIn,
                        onGoogleSignOut = {
                            apiKey = ""
                            onGoogleSignOut()
                        },
                        serverName = serverName,
                        protocol = protocol,
                        state = vpnStatus.state,
                        error = if (errorState) vpnStatus.error else feedback,
                        duration = duration,
                        receivedBytes = vpnStatus.receivedBytes,
                        sentBytes = vpnStatus.sentBytes,
                        downloadMbps = downloadMbps,
                        uploadMbps = uploadMbps,
                        onOpenServers = { serverDialog = true },
                        onImport = { raw -> if (onImport(raw)) serverDialog = false },
                        onProtocolAllowed = { enabledProtocols.contains(protocol.lowercase(Locale.ROOT)) },
                        onConnect = onConnect,
                        onCopyLog = onCopyLog,
                        onDisconnect = onDisconnect,
                        onBlockedProtocol = onBlockedProtocol,
                        onStartConfigured = { onStartConfigured(dns, route) },
                        onDismissError = onFeedbackDismiss,
                    )
                    DashboardTab.Settings -> SettingsScreen(
                        dns = dns,
                        onDnsChange = { dns = it },
                        route = route,
                        onRouteChange = { route = it },
                        protocols = enabledProtocols,
                        onProtocolsChange = { enabledProtocols = it },
                        onOpenVpnSettings = onOpenVpnSettings,
                        onExport = onExport,
                    )
                    DashboardTab.Info -> InfoScreen(
                        serverName = serverName,
                        protocol = protocol,
                        state = vpnStatus.state,
                        onOpenVpnSettings = onOpenVpnSettings,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EngravedText("AIV2Ray / ENGINEERED FOR PRIVACY", 8)
                    EngravedText(if (connected) "XRAY CORE · ANDROID VPN" else "XRAY CORE · OFFLINE", 8)
                }
            }
        }
    }

    if (serverDialog) {
        PublicServerDialog(
            onDismiss = { serverDialog = false },
            fetchNodes = onFetchPublicNodes,
            onSelectNode = { node ->
                onSelectPublicNode(node)
                serverDialog = false
            },
            onImport = { raw ->
                if (onImport(raw)) serverDialog = false
            },
        )
    }

}

@Composable
private fun StatusBadge(state: String) {
    val isConnected = state == "connected"
    val isConnecting = state == "connecting"
    val led = when {
        isConnected -> Online
        isConnecting -> Copper
        state == "error" -> Offline
        else -> Offline
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Tray, Shadow)))
            .border(1.dp, Bevel, CircleShape)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(led, CircleShape))
        Text(
            when {
                isConnected -> "CONNECTED"
                isConnecting -> "CONNECTING"
                state == "error" -> "VPN ERROR"
                else -> "OFFLINE"
            },
            color = if (isConnected || isConnecting) CopperLight else Offline,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BrandPlate() {
    val transition = rememberInfiniteTransition(label = "brand-plate")
    val glow by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(4800), RepeatMode.Reverse),
        label = "brand-glow",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5800), RepeatMode.Restart),
        label = "brand-wave",
    )
    val waveAlpha = when {
        wave < 0.55f || wave >= 0.82f -> 0f
        wave < 0.63f -> (wave - 0.55f) / 0.08f * 0.42f
        else -> (1f - (wave - 0.63f) / 0.19f) * 0.42f
    }.coerceIn(0f, 0.42f)
    val waveScale = 0.94f + (wave - 0.55f).coerceIn(0f, 0.27f) / 0.27f * 0.14f
    val brandGradient = Brush.linearGradient(
        listOf(
            Color(0xFFF0D7C5),
            Color(0xFFE5DCF7),
            Color(0xFFD2E9F3),
            Color(0xFFD5ECDF),
            Color(0xFFF2E7C9),
        ),
    )
    val plateShape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(waveScale)
                .alpha(waveAlpha)
                .border(1.dp, brandGradient, RoundedCornerShape(24.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .scale(1f + glow * 0.025f)
                .alpha(glow * 0.35f)
                .background(brandGradient, plateShape),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(plateShape)
                .background(brandGradient)
                .padding(1.dp)
                .clip(plateShape)
                .background(Tray)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFF8F1), Color(0xFFF2EFFF), Color(0xFFEDF8FA)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CopperDark, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "AIV2Ray",
                color = Engrave,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun DashboardNavigation(current: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.verticalGradient(listOf(Tray, Shadow)))
            .border(1.dp, Shadow, RoundedCornerShape(5.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DashboardTab.entries.forEach { item ->
            val active = item == current
            val icon = when (item) {
                DashboardTab.Connection -> Icons.Filled.Bolt
                DashboardTab.Settings -> Icons.Filled.Settings
                DashboardTab.Info -> Icons.Filled.Info
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (active) Brush.verticalGradient(listOf(Color(0xFFE0E3E5), Color(0xFF777F88), Color(0xFF41474E)))
                        else Brush.verticalGradient(listOf(Tray, Steel)),
                    )
                    .border(1.dp, if (active) Bevel else Shadow, RoundedCornerShape(4.dp))
                    .clickable { onSelect(item) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = null, tint = if (active) CopperDark else Engrave, modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(3.dp))
                Text(item.label, color = if (active) Engrave else EngraveLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ConnectionScreen(
    purpose: String,
    onPurposeChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    recommendationMessage: String,
    recommendationBusy: Boolean,
    onAskGemini: () -> Unit,
    googleEmail: String,
    authBusy: Boolean,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    serverName: String,
    protocol: String,
    state: String,
    error: String,
    duration: String,
    receivedBytes: Long,
    sentBytes: Long,
    downloadMbps: Double,
    uploadMbps: Double,
    onProtocolAllowed: () -> Boolean,
    onStartConfigured: () -> Unit,
    onOpenServers: () -> Unit,
    onImport: (String) -> Unit,
    onConnect: () -> Unit,
    onCopyLog: () -> Unit,
    onDisconnect: () -> Unit,
    onBlockedProtocol: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        MetalPanel {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Copper, modifier = Modifier.size(18.dp))
                    Text("AI · Ask Gemini", color = CopperDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    EngravedText("LOCAL CONFIG ENGINE", 7)
                }
                if (googleEmail.isBlank()) {
                    CopperButton(
                        if (authBusy) "SIGNING IN…" else "SIGN IN WITH GOOGLE",
                        Modifier.fillMaxWidth(),
                        onClick = onGoogleSignIn,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "GOOGLE · $googleEmail",
                            color = Engrave,
                            fontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onGoogleSignOut) {
                            Text("SIGN OUT", color = CopperDark, fontSize = 9.sp)
                        }
                    }
                }
                Text(
                    "Google sign-in verifies your identity only; it does not provide Gemini API access. Your separate Gemini key stays in this screen session.",
                    color = Engrave,
                    fontSize = 8.sp,
                )
                Text("WHAT DO YOU WANT TO USE THE VPN FOR?", color = Engrave, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                RecessedInput(
                    value = purpose,
                    onValueChange = onPurposeChange,
                    placeholder = "Describe your purpose, e.g. gaming or streaming",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("PUBG Mobile", "Facebook & IG", "YouTube 4K").forEach { label ->
                        Keycap(label, Modifier.weight(1f), selected = purpose == label) { onPurposeChange(label) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Low Latency Gaming", "Anti-Censorship").forEach { label ->
                        Keycap(label, Modifier.weight(1f), selected = purpose == label) { onPurposeChange(label) }
                    }
                }
                RecessedInput(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = "Gemini API key · session only",
                    secure = true,
                )
                CopperButton(
                    if (recommendationBusy) "ASKING GEMINI…" else "ASK GEMINI · FIND FREE SERVER",
                    Modifier.fillMaxWidth(),
                    onClick = onAskGemini,
                )
                Text(
                    "Never enter config links or credentials here. Gemini receives your purpose and public protocol/country metadata only; import links below for local parsing.",
                    color = Engrave,
                    fontSize = 8.sp,
                )
                if (recommendationMessage.isNotBlank()) {
                    Text(recommendationMessage, color = if (recommendationBusy) CopperDark else Engrave, fontSize = 9.sp)
                }
                CopperButton("IMPORT CONFIG LINK", Modifier.fillMaxWidth()) { onImport(purpose.trim()) }
                Text(
                    "Free public servers are untrusted and unmeasured. Select or import one before connecting.",
                    color = Engrave,
                    fontSize = 9.sp,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionTitle("ACTIVE SERVER")
            Text("MANAGE SERVERS  ›", color = CopperDark, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onOpenServers))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF9BA2AA), Color(0xFF707881), Color(0xFF444A51))))
                .border(1.dp, Bevel, RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenServers)
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(width = 39.dp, height = 37.dp)
                    .background(Brush.verticalGradient(listOf(Bevel, Steel)), RoundedCornerShape(4.dp))
                    .border(1.dp, Shadow, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = Engrave, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(serverName.ifBlank { "No Node Selected" }, color = Engrave, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                Text(if (serverName.isBlank()) "Import a VLESS, VMess, Trojan, or SS link" else "Imported configuration · local only", color = Engrave, fontSize = 9.sp)
            }
            if (protocol.isNotBlank()) {
                Text(protocol, color = CopperDark, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                    modifier = Modifier.border(1.dp, CopperDark, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 3.dp))
            }
        }

        MetalPanel {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("LIVE CONNECTION INSTRUMENTS")
                    EngravedText(if (state == "connected") "NATIVE VPN" else "ANDROID VPN", 7)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    AnalogGauge("LATENCY PING", "—", "ms", Modifier.weight(1f))
                    PowerControl(
                        state,
                        onClick = {
                            when {
                                state == "connected" || state == "connecting" || state == "error" -> onDisconnect()
                                protocol.isBlank() -> onConnect()
                                onProtocolAllowed() -> onStartConfigured()
                                else -> onBlockedProtocol()
                            }
                        },
                        modifier = Modifier.width(98.dp),
                    )
                    AnalogGauge("SPEED RATE", "%.1f".format(downloadMbps + uploadMbps), "Mbps", Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 5.dp).border(1.dp, Bevel, RoundedCornerShape(4.dp)).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Metric("DOWNLOAD", "%.1f Mbps".format(downloadMbps))
                    Metric("UPLOAD", "%.1f Mbps".format(uploadMbps))
                    Metric("RECEIVED", formatBytes(receivedBytes))
                    Metric("SENT", formatBytes(sentBytes))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(if (state == "connected") Online else Offline, CircleShape))
                        Text(connectionLabel(state), color = Engrave, fontSize = 9.sp)
                    }
                    Text(duration, color = Engrave, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
                if (error.isNotBlank()) {
                    Text(error, color = Offline, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onDismissError))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            EngravedText("LOCAL CONFIG PARSING", 7)
            TextButton(onClick = onCopyLog) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("COPY LOG", color = CopperDark, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    dns: String,
    onDnsChange: (String) -> Unit,
    route: String,
    onRouteChange: (String) -> Unit,
    protocols: Set<String>,
    onProtocolsChange: (Set<String>) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onExport: () -> Unit,
) {
    var dnsMenu by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        SectionTitle("DEVICE & NETWORK")
        MetalPanel {
            Column {
                SettingRow("Android VPN lockdown", "Configure Always-on VPN in Android settings") {
                    Text("OPEN", color = CopperDark, fontSize = 9.sp, modifier = Modifier.clickable(onClick = onOpenVpnSettings))
                }
                SettingRow("DNS resolver", dns) {
                    Text("CHANGE", color = CopperDark, fontSize = 9.sp, modifier = Modifier.clickable { dnsMenu = true })
                    androidx.compose.material3.DropdownMenu(expanded = dnsMenu, onDismissRequest = { dnsMenu = false }) {
                        listOf(
                            "Cloudflare · 1.1.1.1" to "1.1.1.1",
                            "Google · 8.8.8.8" to "8.8.8.8",
                            "AdGuard · 94.140.14.14" to "94.140.14.14",
                            "Quad9 · 9.9.9.9" to "9.9.9.9",
                        ).forEach { (label, address) ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { onDnsChange(address); dnsMenu = false })
                        }
                    }
                }
                Text("SUPPORTED PROTOCOLS", color = Engrave, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 13.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("VLESS" to "vless", "VMESS" to "vmess", "TROJAN" to "trojan", "SS" to "shadowsocks").forEach { (name, id) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(checked = id in protocols, onCheckedChange = { enabled ->
                                onProtocolsChange(if (enabled) protocols + id else protocols - id)
                            })
                            Text(name, color = if (id in protocols) CopperDark else Engrave, fontSize = 8.sp)
                        }
                    }
                }
                Text("ROUTING MODE", color = Engrave, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 13.dp, bottom = 7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Global", "Bypass LAN", "Rule-based").forEach { value ->
                        Keycap(value, Modifier.weight(1f), selected = route == value) { onRouteChange(value) }
                    }
                }
                Text("DNS and routing choices are applied when you connect or reconnect. Android controls the VPN lockdown setting.", color = Engrave, fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        MetalPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = CopperDark)
                Column {
                    Text("BRUSHED STEEL & BURNISHED COPPER", color = Engrave, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Machined instrument theme", color = Engrave, fontSize = 9.sp)
                }
            }
        }
        CopperButton("EXPORT XRAY / V2RAY JSON", Modifier.fillMaxWidth(), onClick = onExport)
        TextButton(onClick = onOpenVpnSettings) { Text("OPEN ANDROID VPN SETTINGS", color = CopperDark) }
    }
}

@Composable
private fun InfoScreen(serverName: String, protocol: String, state: String, onOpenVpnSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        SectionTitle("DEVICE INFORMATION")
        MetalPanel {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("App version", "2.4.1")
                InfoRow("VPN service", "${state.uppercase()} · Xray")
                InfoRow("Protocol", protocol.ifBlank { "—" })
                InfoRow("Server", serverName.ifBlank { "—" })
                InfoRow("Configuration storage", "On this device")
            }
        }
        MetalPanel {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = CopperDark)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ZERO-LOG & DATA SAFETY", color = CopperDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Configuration links are parsed locally and saved in private app preferences. No telemetry or advertising SDK is included.", color = Engrave, fontSize = 11.sp)
                    Text("VPN behavior is provided by Android VpnService and the bundled Xray core. Verify traffic, DNS, and IP leak behavior on a physical device before relying on protection.", color = Engrave, fontSize = 10.sp)
                }
            }
        }
        MetalPanel {
            Text("ANDROID VPN", color = CopperDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Always-on VPN and Block connections without VPN are configured in Android system settings.", color = Engrave, fontSize = 10.sp)
            TextButton(onClick = onOpenVpnSettings) { Text("OPEN SYSTEM SETTINGS", color = CopperDark) }
        }
    }
}

@Composable
private fun PublicServerDialog(
    onDismiss: () -> Unit,
    fetchNodes: suspend (String) -> NativePublicDirectory.Result,
    onSelectNode: (NativePublicDirectory.Node) -> Unit,
    onImport: (String) -> Unit,
) {
    var raw by rememberSaveable { mutableStateOf("") }
    var countryCode by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var countryMenu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf(emptyList<NativePublicDirectory.Node>()) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        if (loading) return
        loading = true
        error = ""
        scope.launch {
            try {
                val result = withTimeout(25_000) { fetchNodes(countryCode) }
                nodes = result.nodes
                error = "${result.nodes.size} supported · ${result.skipped} skipped${if (result.truncated) " · feed limited to 5,000 lines" else ""}"
            } catch (cause: Exception) {
                error = cause.message ?: "Public directory unavailable. Try another feed."
            } finally {
                loading = false
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(countryCode) { refresh() }
    val visibleNodes = nodes.filter {
        search.isBlank() || "${it.name} ${it.country} ${it.protocol}".contains(search, ignoreCase = true)
    }.take(40)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFADB3B9), Color(0xFF626A72), Color(0xFF383D43))))
                .border(1.dp, Bevel, RoundedCornerShape(7.dp)).padding(16.dp)
                .heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("FREE PUBLIC SERVERS", color = Engrave, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("FastNodes · public community feed. Servers are untrusted, may be unavailable, and have unmeasured latency.", color = Offline, fontSize = 9.sp)
            Box {
                Keycap(
                    if (countryCode.isEmpty()) "TOP FEED · UNKNOWN LOCATION" else "${Locale("", countryCode).displayCountry} · SOURCE-REPORTED",
                    Modifier.fillMaxWidth(),
                    onClick = { countryMenu = true },
                )
                DropdownMenu(expanded = countryMenu, onDismissRequest = { countryMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Top feed · unknown location") },
                        onClick = { countryCode = ""; countryMenu = false },
                    )
                    NativePublicDirectory.countryCodes.forEach { code ->
                        DropdownMenuItem(
                            text = { Text("${Locale("", code).displayCountry} · source-reported") },
                            onClick = { countryCode = code; countryMenu = false },
                        )
                    }
                }
            }
            RecessedInput(search, { search = it }, "Search protocol or source location")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "Loading public feed…" else error, color = if (loading) CopperDark else Engrave, fontSize = 8.sp)
                Keycap("REFRESH", onClick = ::refresh)
            }
            visibleNodes.forEach { node ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF90979E), Color(0xFF737B83))))
                        .border(1.dp, Bevel, RoundedCornerShape(4.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(node.name, color = Engrave, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${node.protocol.uppercase(Locale.ROOT)} · ${node.country} · UNTRUSTED · UNMEASURED", color = Engrave, fontSize = 7.sp)
                    }
                    Keycap("IMPORT", onClick = { onSelectNode(node) })
                }
            }
            if (nodes.isEmpty() && !loading && error.isNotBlank()) {
                Text("Try refreshing, selecting another country, or paste a server link below.", color = Engrave, fontSize = 9.sp)
            }
            Text("PASTE A SERVER LINK", color = Engrave, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            RecessedInput(raw, { raw = it }, "vless://, vmess://, trojan://, ss:// or Xray JSON", minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Keycap("CANCEL", Modifier.weight(1f), onClick = onDismiss)
                CopperButton("IMPORT LOCALLY", Modifier.weight(1f)) { onImport(raw.trim()) }
            }
        }
    }
}

@Composable
private fun MetalPanel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFB3B8BD), Color(0xFF858D95), Color(0xFF646C74), Color(0xFF9DA4AA))))
            .border(1.dp, Bevel, RoundedCornerShape(6.dp))
            .padding(13.dp),
    ) {
        content()
    }
}

@Composable
private fun RecessedInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    secure: Boolean = false,
) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF565D64), Color(0xFF767E86))))
            .border(1.dp, Shadow, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Color(0xFF3D4248), fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = Engrave, fontSize = 12.sp),
            minLines = minLines,
            cursorBrush = Brush.verticalGradient(listOf(Copper, CopperDark)),
            visualTransformation = if (secure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
    }
}

@Composable
private fun Keycap(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Brush.verticalGradient(if (selected) listOf(Color(0xFFC4C8CB), Color(0xFF8A9299)) else listOf(Color(0xFFA4ABB1), Color(0xFF777F87))))
            .border(1.dp, if (selected) CopperDark else Bevel, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 5.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) CopperDark else Engrave, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun CopperButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Brush.verticalGradient(listOf(CopperLight, Copper, CopperDark)))
            .border(1.dp, CopperDark, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Color(0xFF27160C), fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AnalogGauge(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                val radius = size.minDimension / 2f - 3.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(Color(0xFFE2E4E5), Color(0xFF858D95), Color(0xFF565E65))), radius)
                drawCircle(Shadow, radius * .88f)
                drawArc(CopperDark, 135f, 270f, false, style = Stroke(width = 2.dp.toPx()), topLeft = Offset(center.x - radius * .74f, center.y - radius * .74f), size = androidx.compose.ui.geometry.Size(radius * 1.48f, radius * 1.48f))
                for (tick in 0..24) {
                    val angle = Math.toRadians((135 + tick * 11.25).toDouble())
                    val outer = radius * .82f
                    val inner = radius * if (tick % 4 == 0) .69f else .75f
                    drawLine(
                        if (tick % 4 == 0) CopperLight else Bevel,
                        Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                        Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                        strokeWidth = if (tick % 4 == 0) 1.4.dp.toPx() else 0.7.dp.toPx(),
                    )
                }
                val needleAngle = Math.toRadians(222.0)
                val tip = Offset(center.x + cos(needleAngle).toFloat() * radius * .62f, center.y + sin(needleAngle).toFloat() * radius * .62f)
                val left = Offset(center.x + cos(needleAngle + 1.55).toFloat() * 2.dp.toPx(), center.y + sin(needleAngle + 1.55).toFloat() * 2.dp.toPx())
                val right = Offset(center.x + cos(needleAngle - 1.55).toFloat() * 2.dp.toPx(), center.y + sin(needleAngle - 1.55).toFloat() * 2.dp.toPx())
                val needle = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }
                drawPath(needle, Brush.linearGradient(listOf(CopperLight, Copper, CopperDark), tip, center))
                drawCircle(CopperDark, radius = 4.dp.toPx())
                drawCircle(CopperLight, radius = 1.7.dp.toPx())
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = CopperLight, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(unit, color = EngraveLight, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text(label, color = Engrave, fontSize = 7.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun PowerControl(state: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = state == "connected"
    val connecting = state == "connecting"
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(92.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFD5D9DC), Color(0xFF828A92), Color(0xFF343A40))))
                .border(3.dp, if (active || connecting) Copper else Bevel, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF8A9299), Color(0xFF3B4147), Shadow)))
                .border(1.dp, Color(0xFFB8BEC3), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = if (active) "Disconnect VPN" else "Connect VPN", tint = if (active || connecting) Copper else CopperDark, modifier = Modifier.size(39.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                active -> "TAP TO DISCONNECT"
                connecting -> "CONNECTING"
                state == "error" -> "TAP TO STOP"
                else -> "TAP TO CONNECT"
            },
            color = CopperDark,
            fontSize = 6.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = EngraveLight, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun EngravedText(text: String, size: Int) {
    Text(text, color = Engrave, fontFamily = FontFamily.Monospace, fontSize = size.sp, maxLines = 1)
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Engrave, fontSize = 6.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = Engrave, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingRow(label: String, detail: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Engrave, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Engrave, fontSize = 9.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box { control() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Engrave, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Engrave, fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

private fun connectionLabel(state: String): String = when (state) {
    "connected" -> "Native VPN tunnel connected"
    "connecting" -> "Starting Android VPN service…"
    "error" -> "Android VPN service error"
    else -> "Your VPN tunnel is disconnected"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1_000_000 -> "%.1f KB".format(bytes / 1000.0)
    bytes < 1_000_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%.1f GB".format(bytes / 1_000_000_000.0)
}