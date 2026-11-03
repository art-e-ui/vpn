package com.artesys.netcon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

public final class NetconVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.artesys.netcon.CONNECT";
    public static final String ACTION_DISCONNECT = "com.artesys.netcon.DISCONNECT";
    public static final String EXTRA_CONFIG = "xrayConfig";

    private static final String CHANNEL_ID = "netcon-vpn";
    private static final int NOTIFICATION_ID = 4182;
    private static final Object LOCK = new Object();
    private static final Object DIAGNOSTIC_LOCK = new Object();
    private static final int MAX_DIAGNOSTIC_LINES = 200;
    private static final int MAX_DIAGNOSTIC_CHARS = 24_000;
    private static final ArrayDeque<String> diagnosticLines = new ArrayDeque<>();
    private static int diagnosticCharacterCount;
    private static volatile String state = "disconnected";
    private static volatile String errorMessage = "";
    private static volatile String sessionId = "";
    private static volatile long startedAt;
    private static volatile long receivedBytes;
    private static volatile long sentBytes;
    private static volatile double downloadBytesPerSecond;
    private static volatile double uploadBytesPerSecond;
    private static volatile long previousSampleAt;
    private static volatile long previousCoreReceivedBytes;
    private static volatile long previousCoreSentBytes;
    private static CoreController coreController;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor vpnInterface;
    private ConnectivityManager boundConnectivityManager;
    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            boolean failed = false;
            synchronized (LOCK) {
                if ("connected".equals(state)) {
                    try {
                        if (coreController == null || !coreController.getIsRunning()) {
                            setState("error", "Xray stopped unexpectedly. Traffic remains blocked until you disconnect.");
                            failed = true;
                        }
                    } catch (RuntimeException error) {
                        android.util.Log.e("NetconVpnService", "Unable to verify Xray status.", error);
                        setState("error", "Unable to verify Xray status. Traffic remains blocked until you disconnect.");
                        failed = true;
                    }
                }
            }
            if (failed) {
                updateNotification("VPN blocked: Xray stopped");
            }
            if (!isFinishing()) {
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    public static void logDiagnostic(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String safeMessage = message.replace('\n', ' ').replace('\r', ' ')
            .replaceAll("(?i)(vless|vmess|trojan|ss)://\\S+", "$1://[redacted]")
            .replaceAll("(?i)(\\\"?(?:password|passwd|uuid|userid|user|id|token|secret|authorization|privatekey)\\\"?\\s*[:=]\\s*\\\"?)[^\\s,\\\"}]+", "$1[redacted]");
        if (safeMessage.length() > 2_000) safeMessage = safeMessage.substring(0, 2_000);
        String entry = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + safeMessage;
        synchronized (DIAGNOSTIC_LOCK) {
            diagnosticLines.addLast(entry);
            diagnosticCharacterCount += entry.length() + 1;
            while (diagnosticLines.size() > MAX_DIAGNOSTIC_LINES || diagnosticCharacterCount > MAX_DIAGNOSTIC_CHARS) {
                String removed = diagnosticLines.removeFirst();
                diagnosticCharacterCount -= removed.length() + 1;
            }
        }
    }

    public static String getDiagnosticLog() {
        synchronized (DIAGNOSTIC_LOCK) {
            if (diagnosticLines.isEmpty()) return "No diagnostic events captured.";
            StringBuilder log = new StringBuilder(diagnosticCharacterCount);
            for (String entry : diagnosticLines) {
                if (log.length() > 0) log.append('\n');
                log.append(entry);
            }
            return log.toString();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            logDiagnostic("VPN service received disconnect request");
            mainHandler.removeCallbacks(healthCheck);
            worker.execute(() -> {
                stopTunnel();
                setState("disconnected", "");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            });
            return START_NOT_STICKY;
        }

        String config = intent == null ? null : intent.getStringExtra(EXTRA_CONFIG);
        if (config == null || config.trim().isEmpty()) {
            logDiagnostic("VPN service received an empty configuration");
            setState("error", "No saved VPN configuration is available.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        logDiagnostic("VPN service received connection request");
        setState("connecting", "");
        try {
            startForegroundCompat();
        } catch (RuntimeException error) {
            logDiagnostic("Unable to start foreground service: " + diagnosticError(error));
            setState("error", safeMessage(error));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        worker.execute(() -> {
            stopTunnel();
            setState("connecting", "");
            startTunnel(config);
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        mainHandler.removeCallbacks(healthCheck);
        worker.execute(() -> {
            stopTunnel();
            setState("disconnected", "");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(healthCheck);
        worker.shutdown();
        stopTunnel();
        super.onDestroy();
    }

    private synchronized void startTunnel(String configText) {
        try {
            logDiagnostic("Starting Android VPN tunnel");
            Network underlyingNetwork = getUnderlyingNetwork();
            ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
            if (connectivity == null) {
                throw new IllegalStateException("Android network services are unavailable.");
            }

            JSONObject config = new JSONObject(configText);
            String dnsAddress = firstDnsAddress(config);
            JSONArray inbounds = new JSONArray().put(NativeXrayConfig.androidTunInbound(dnsAddress));
            config.put("inbounds", inbounds);
            config.put("stats", new JSONObject());
            config.put("policy", new JSONObject()
                .put("levels", new JSONObject().put("0", new JSONObject()
                    .put("statsUserUplink", true)
                    .put("statsUserDownlink", true)))
                .put("system", new JSONObject()
                    .put("statsOutboundUplink", true)
                    .put("statsOutboundDownlink", true)));

            Builder builder = new Builder()
                .setSession("Netcon")
                .setMtu(1500)
                .addAddress("10.64.0.1", 30)
                .addAddress("fd64:64::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(dnsAddress);
            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                throw new IllegalStateException("Android could not establish the VPN interface.");
            }
            logDiagnostic("Android VPN interface established");
            setUnderlyingNetworks(new Network[] { underlyingNetwork });
            if (!connectivity.bindProcessToNetwork(underlyingNetwork)) {
                throw new IllegalStateException("Android could not bind Xray traffic to the underlying network.");
            }
            boundConnectivityManager = connectivity;
            logDiagnostic("Xray traffic bound to the underlying Android network");

            Seq.setContext(getApplicationContext());
            Libv2ray.initCoreEnv(getFilesDir().getAbsolutePath(), "");
            logDiagnostic("Xray core initialized");
            CoreController controller = Libv2ray.newCoreController(new CoreCallbackHandler() {
                @Override public long startup() { return 0; }
                @Override public long shutdown() { return 0; }
                @Override public long onEmitStatus(long statusCode, String message) {
                    logDiagnostic("Xray status " + statusCode + ": " + message);
                    return 0;
                }
            });
            synchronized (LOCK) {
                coreController = controller;
            }
            logDiagnostic("Starting Xray core with Android VPN interface");
            controller.startLoop(config.toString(), vpnInterface.getFd());
            if (!controller.getIsRunning()) {
                throw new IllegalStateException("Xray did not start.");
            }
            synchronized (LOCK) {
                sessionId = UUID.randomUUID().toString();
                startedAt = SystemClock.elapsedRealtime();
                receivedBytes = 0;
                sentBytes = 0;
                downloadBytesPerSecond = 0;
                uploadBytesPerSecond = 0;
                previousSampleAt = startedAt;
                previousCoreReceivedBytes = 0;
                previousCoreSentBytes = 0;
                state = "connected";
                errorMessage = "";
            }
            logDiagnostic("VPN connected; Xray is running");
            updateNotification("VPN active");
            mainHandler.removeCallbacks(healthCheck);
            mainHandler.postDelayed(healthCheck, 1000);
        } catch (Exception error) {
            if (coreController != null) {
                try {
                    coreController.stopLoop();
                } catch (Exception stopError) {
                    android.util.Log.e("NetconVpnService", "Unable to stop Xray after startup failure.", stopError);
                }
                coreController = null;
            }
            unbindProcessNetwork();
            String message = safeMessage(error);
            logDiagnostic("VPN startup failed: " + diagnosticError(error));
            setState("error", message);
            if (vpnInterface != null) {
                updateNotification("VPN blocked: " + message);
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private Network getUnderlyingNetwork() {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        if (network == null || capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw new IllegalStateException("Connect to Wi-Fi or mobile data before starting the VPN.");
        }
        return network;
    }

    private static String firstDnsAddress(JSONObject config) throws Exception {
        JSONArray servers = config.optJSONObject("dns") == null ? null : config.getJSONObject("dns").optJSONArray("servers");
        String address = servers == null || servers.length() == 0 ? "1.1.1.1" : servers.optString(0, "1.1.1.1");
        if (!address.matches("[0-9.]+|[0-9a-fA-F:]+")) {
            throw new IllegalArgumentException("Android VPN DNS must be a literal IP address.");
        }
        InetAddress parsed = InetAddress.getByName(address);
        if (!(parsed instanceof Inet4Address) && !(parsed instanceof Inet6Address)) {
            throw new IllegalArgumentException("The configured DNS address is invalid.");
        }
        return parsed.getHostAddress();
    }

    private synchronized void stopTunnel() {
        synchronized (LOCK) {
            if (coreController != null) {
                try {
                    coreController.stopLoop();
                } catch (Exception error) {
                    android.util.Log.w("NetconVpnService", "Unable to stop Xray cleanly.", error);
                }
                coreController = null;
            }
            if (vpnInterface != null) {
                try {
                    vpnInterface.close();
                } catch (Exception error) {
                    android.util.Log.w("NetconVpnService", "Unable to close the VPN interface cleanly.", error);
                }
                vpnInterface = null;
            }
            unbindProcessNetwork();
            if (!"error".equals(state)) {
                state = "disconnected";
                errorMessage = "";
            }
            sessionId = "";
            startedAt = 0;
            receivedBytes = 0;
            sentBytes = 0;
            downloadBytesPerSecond = 0;
            uploadBytesPerSecond = 0;
            previousSampleAt = 0;
            previousCoreReceivedBytes = 0;
            previousCoreSentBytes = 0;
        }
    }

    private void unbindProcessNetwork() {
        ConnectivityManager connectivity = boundConnectivityManager;
        boundConnectivityManager = null;
        if (connectivity != null && !connectivity.bindProcessToNetwork(null)) {
            android.util.Log.e("NetconVpnService", "Unable to unbind the app process from the underlying network.");
            logDiagnostic("Unable to unbind the app process from the underlying network");
        }
    }

    private static void setState(String nextState, String message) {
        synchronized (LOCK) {
            state = nextState;
            errorMessage = message;
            sessionId = "";
            startedAt = 0;
            receivedBytes = 0;
            sentBytes = 0;
            downloadBytesPerSecond = 0;
            uploadBytesPerSecond = 0;
            previousSampleAt = 0;
            previousCoreReceivedBytes = 0;
            previousCoreSentBytes = 0;
        }
    }

    public static Status getStatus() {
        synchronized (LOCK) {
            sampleTraffic();
            return new Status(
                state,
                errorMessage,
                startedAt == 0 ? 0 : (SystemClock.elapsedRealtime() - startedAt) / 1000,
                receivedBytes,
                sentBytes,
                downloadBytesPerSecond,
                uploadBytesPerSecond
            );
        }
    }

    public static final class Status {
        public final String state;
        public final String error;
        public final long elapsedSeconds;
        public final long receivedBytes;
        public final long sentBytes;
        public final double downloadBytesPerSecond;
        public final double uploadBytesPerSecond;

        private Status(String state, String error, long elapsedSeconds, long receivedBytes, long sentBytes,
                       double downloadBytesPerSecond, double uploadBytesPerSecond) {
            this.state = state;
            this.error = error;
            this.elapsedSeconds = elapsedSeconds;
            this.receivedBytes = receivedBytes;
            this.sentBytes = sentBytes;
            this.downloadBytesPerSecond = downloadBytesPerSecond;
            this.uploadBytesPerSecond = uploadBytesPerSecond;
        }
    }

    private static void sampleTraffic() {
        if (coreController == null || !"connected".equals(state)) {
            return;
        }
        long downloaded = 0;
        long uploaded = 0;
        String sample;
        try {
            sample = coreController.queryAllOutboundTrafficStats();
        } catch (RuntimeException error) {
            downloadBytesPerSecond = 0;
            uploadBytesPerSecond = 0;
            android.util.Log.w("NetconVpnService", "Unable to sample Xray traffic counters.", error);
            return;
        }
        if (sample == null) {
            downloadBytesPerSecond = 0;
            uploadBytesPerSecond = 0;
            return;
        }
        for (String item : sample.split(";")) {
            String[] fields = item.split(",");
            if (fields.length != 3 || !"proxy".equals(fields[0])) {
                continue;
            }
            try {
                long value = Math.max(0, Long.parseLong(fields[2]));
                if ("downlink".equals(fields[1])) downloaded += value;
                if ("uplink".equals(fields[1])) uploaded += value;
            } catch (NumberFormatException ignored) {
            }
        }
        long now = SystemClock.elapsedRealtime();
        long interval = Math.max(1, now - previousSampleAt);
        long receivedDelta = downloaded >= previousCoreReceivedBytes
            ? downloaded - previousCoreReceivedBytes : downloaded;
        long sentDelta = uploaded >= previousCoreSentBytes
            ? uploaded - previousCoreSentBytes : uploaded;
        downloadBytesPerSecond = receivedDelta * 1000d / interval;
        uploadBytesPerSecond = sentDelta * 1000d / interval;
        receivedBytes += receivedDelta;
        sentBytes += sentDelta;
        previousCoreReceivedBytes = downloaded;
        previousCoreSentBytes = uploaded;
        previousSampleAt = now;
    }

    private void startForegroundCompat() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Netcon VPN", NotificationManager.IMPORTANCE_LOW));
        }
        updateNotification("Starting VPN");
        Notification notification = buildNotification("Starting VPN");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private boolean isFinishing() {
        synchronized (LOCK) {
            return !"connected".equals(state) && !"error".equals(state);
        }
    }

    private Notification buildNotification(String text) {
        Intent disconnect = new Intent(this, NetconVpnService.class).setAction(ACTION_DISCONNECT);
        PendingIntent pendingDisconnect = PendingIntent.getService(this, 1, disconnect, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Netcon VPN")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", pendingDisconnect)
            .build();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "The Android VPN tunnel failed to start.";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static String diagnosticError(Throwable error) {
        StringBuilder details = new StringBuilder();
        Throwable cause = error;
        int depth = 0;
        while (cause != null && depth < 5) {
            if (depth > 0) details.append(" <- ");
            details.append(cause.getClass().getSimpleName());
            String message = cause.getMessage();
            if (message != null && !message.trim().isEmpty()) details.append(": ").append(message);
            cause = cause.getCause();
            depth++;
        }
        return details.toString();
    }
}