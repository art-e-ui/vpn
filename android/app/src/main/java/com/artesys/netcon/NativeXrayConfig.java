package com.artesys.netcon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

final class NativeXrayConfig {
    private static final String[] LAN_IP_RANGES = {
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "fc00::/7",
        "fe80::/10"
    };

    private NativeXrayConfig() {}

    static String configuredXrayJson(String configText, String dnsAddress, String routingMode) throws Exception {
        JSONObject config = new JSONObject(configText);
        config.put("dns", new JSONObject().put("servers", new JSONArray().put(dnsAddress)));
        JSONObject routing = config.optJSONObject("routing");
        if (routing == null) routing = new JSONObject();
        JSONArray rules = new JSONArray();
        if ("Bypass LAN".equals(routingMode)) {
            JSONArray lanRanges = new JSONArray();
            for (String range : LAN_IP_RANGES) lanRanges.put(range);
            rules.put(new JSONObject()
                .put("type", "field")
                .put("ip", lanRanges)
                .put("outboundTag", "direct"));
        } else if ("Rule-based".equals(routingMode)) {
            JSONArray existing = routing.optJSONArray("rules");
            if (existing != null) {
                for (int index = 0; index < existing.length(); index++) rules.put(existing.get(index));
            }
        }
        routing.put("rules", rules);
        config.put("routing", routing);
        return config.toString();
    }

    static JSONObject androidTunInbound(String dnsAddress) throws Exception {
        return new JSONObject()
            .put("tag", "tun")
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", new JSONObject()
                .put("name", "netcon0")
                .put("MTU", 1500)
                .put("gateway", new JSONArray().put("10.64.0.1/30").put("fd64:64::1/126"))
                .put("DNS", new JSONArray().put(dnsAddress)));
    }

    static ImportedServer fromInput(String input) throws Exception {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Paste a server link or Xray JSON configuration.");
        }
        String value = input.trim();
        if (value.getBytes(StandardCharsets.UTF_8).length > 2_000_000) {
            throw new IllegalArgumentException("The configuration exceeds the 2 MB size limit.");
        }
        if (value.startsWith("{")) {
            JSONObject config = new JSONObject(value);
            JSONArray outbounds = config.optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                throw new IllegalArgumentException("Xray JSON must contain at least one outbound.");
            }
            boolean hasProxy = false;
            for (int index = 0; index < outbounds.length(); index++) {
                JSONObject outbound = outbounds.optJSONObject(index);
                if (outbound == null || !"proxy".equals(outbound.optString("tag"))) continue;
                String protocol = outbound.optString("protocol").toLowerCase(Locale.ROOT);
                if (!Arrays.asList("vless", "vmess", "trojan", "shadowsocks").contains(protocol)) {
                    throw new IllegalArgumentException("Xray JSON must use a supported proxy outbound tagged \"proxy\".");
                }
                if (hasProxy) {
                    throw new IllegalArgumentException("Xray JSON must contain only one proxy outbound tagged \"proxy\".");
                }
                hasProxy = true;
            }
            if (!hasProxy) {
                throw new IllegalArgumentException("Xray JSON must contain a supported outbound tagged \"proxy\".");
            }
            return new ImportedServer(config, "Imported Xray configuration");
        }
        String firstLink = "";
        int linkCount = 0;
        for (String line : value.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                if (firstLink.isEmpty()) firstLink = line.trim();
                linkCount++;
            }
        }
        if (linkCount > 1) {
            throw new IllegalArgumentException("Import one server link at a time.");
        }
        if (firstLink.startsWith("vmess://")) {
            return fromVmess(firstLink.substring(8));
        }
        if (firstLink.startsWith("ss://")) {
            return fromShadowsocks(firstLink.substring(5));
        }
        if (firstLink.startsWith("vless://") || firstLink.startsWith("trojan://")) {
            return fromUrl(firstLink);
        }
        throw new IllegalArgumentException("Use a vmess://, vless://, trojan://, ss:// link, or Xray JSON.");
    }

    private static ImportedServer fromVmess(String encoded) throws Exception {
        JSONObject data = new JSONObject(decodeBase64(encoded));
        String address = data.optString("add");
        int port = integer(data.optString("port"), 0);
        String id = data.optString("id");
        requireEndpoint(address, id, port);
        String name = data.optString("ps", "VMess server");
        String network = data.optString("net", "tcp");
        String security = data.optString("tls", "none");

        JSONObject params = new JSONObject();
        for (String key : new String[] {"path", "host", "sni", "alpn", "fp", "aid", "scy", "type"}) {
            if (data.has(key) && !data.isNull(key)) {
                params.put(key, String.valueOf(data.get(key)));
            }
        }
        JSONObject stream = streamSettings(network, security, address, params);
        JSONObject user = new JSONObject()
            .put("id", id)
            .put("alterId", integer(params.optString("aid", "0"), 0))
            .put("security", params.optString("scy", "auto"));
        JSONObject outbound = new JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", new JSONObject().put("vnext", new JSONArray().put(
                new JSONObject()
                    .put("address", address)
                    .put("port", port)
                    .put("users", new JSONArray().put(user)))))
            .put("streamSettings", stream);
        return new ImportedServer(baseConfig(outbound), name);
    }

    private static ImportedServer fromUrl(String link) throws Exception {
        URI uri = parseUri(link);
        String protocol = "vless".equalsIgnoreCase(uri.getScheme()) ? "vless" : "trojan";
        String address = unbracketHost(uri.getHost());
        int port = uri.getPort() < 1 ? 443 : uri.getPort();
        String credential = decode(uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo());
        String network = query(uri, "type", "tcp");
        String security = query(uri, "security", "trojan".equals(protocol) ? "tls" : "none");
        String fragment = uri.getRawFragment();
        String name = fragment == null || fragment.trim().isEmpty()
            ? protocol.toUpperCase(Locale.ROOT) + " server"
            : decode(fragment);

        requireEndpoint(address, credential, port);
        JSONObject params = new JSONObject();
        for (String key : new String[] {"sni", "fp", "alpn", "flow", "pbk", "sid", "spx", "path", "host", "serviceName", "mode", "type"}) {
            String value = query(uri, key, null);
            if (value != null) params.put(key, value);
        }
        JSONObject stream = streamSettings(network, security, address, params);
        JSONObject user;
        JSONObject server;
        if ("vless".equals(protocol)) {
            user = new JSONObject().put("id", credential).put("encryption", "none");
            if (params.has("flow")) user.put("flow", params.getString("flow"));
            server = new JSONObject()
                .put("vnext", new JSONArray().put(new JSONObject()
                    .put("address", address)
                    .put("port", port)
                    .put("users", new JSONArray().put(user))));
        } else {
            server = new JSONObject().put("servers", new JSONArray().put(new JSONObject()
                .put("address", address)
                .put("port", port)
                .put("password", credential)));
        }
        JSONObject outbound = new JSONObject()
            .put("tag", "proxy")
            .put("protocol", protocol)
            .put("settings", server)
            .put("streamSettings", stream);
        return new ImportedServer(baseConfig(outbound), name);
    }

    private static ImportedServer fromShadowsocks(String link) throws Exception {
        String[] fragmentSplit = link.split("#", 2);
        String body = fragmentSplit[0];
        String name = fragmentSplit.length == 2 ? decode(fragmentSplit[1]) : "Shadowsocks server";
        String authority = body.split("\\?", 2)[0];
        String expanded = authority;
        if (!authority.contains("@")) expanded = decodeBase64(authority);
        int at = expanded.lastIndexOf('@');
        if (at < 0) throw new IllegalArgumentException("The Shadowsocks link is missing its server address.");

        String auth = expanded.substring(0, at);
        String endpoint = expanded.substring(at + 1);
        if (!auth.contains(":")) auth = decodeBase64(auth);
        int separator = auth.indexOf(':');
        if (separator < 1) throw new IllegalArgumentException("The Shadowsocks link is missing its cipher or password.");
        String method = decode(auth.substring(0, separator));
        String password = decode(auth.substring(separator + 1));
        if (!Arrays.asList(
            "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305"
        ).contains(method)) {
            throw new IllegalArgumentException("Unsupported Shadowsocks cipher.");
        }
        if (hasQueryParameter(parseUri("ss://" + body), "plugin")) {
            throw new IllegalArgumentException("Shadowsocks plugins are not supported.");
        }
        URI hostUri = parseUri("https://" + endpoint);
        String address = unbracketHost(hostUri.getHost());
        int port = hostUri.getPort() < 1 ? 443 : hostUri.getPort();
        requireEndpoint(address, password, port);
        JSONObject server = new JSONObject().put("servers", new JSONArray().put(new JSONObject()
            .put("address", address)
            .put("port", port)
            .put("password", password)
            .put("method", method)));
        JSONObject outbound = new JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", server)
            .put("streamSettings", new JSONObject().put("network", "tcp").put("security", "none"));
        return new ImportedServer(baseConfig(outbound), name);
    }

    private static JSONObject streamSettings(String network, String security, String address, JSONObject params) throws Exception {
        if (!Arrays.asList("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp", "kcp").contains(network)) {
            throw new IllegalArgumentException("Unsupported transport: " + network);
        }
        if (!Arrays.asList("none", "tls", "reality").contains(security)) {
            throw new IllegalArgumentException("Unsupported security: " + security);
        }
        JSONObject stream = new JSONObject().put("network", network).put("security", security);
        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject()
                .put("serverName", params.optString("sni", address))
                .put("fingerprint", params.optString("fp", "chrome"));
            if (params.has("alpn")) tls.put("alpn", new JSONArray(params.getString("alpn").split(",")));
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            String publicKey = params.optString("pbk");
            if (publicKey.trim().isEmpty()) throw new IllegalArgumentException("Reality requires a public key (pbk).");
            stream.put("realitySettings", new JSONObject()
                .put("serverName", params.optString("sni", address))
                .put("fingerprint", params.optString("fp", "chrome"))
                .put("publicKey", publicKey)
                .put("shortId", params.optString("sid", ""))
                .put("spiderX", params.optString("spx", "/")));
        }
        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject().put("path", params.optString("path", "/"));
            if (params.has("host")) ws.put("headers", new JSONObject().put("Host", params.getString("host")));
            stream.put("wsSettings", ws);
        } else if ("grpc".equals(network)) {
            stream.put("grpcSettings", new JSONObject()
                .put("serviceName", params.optString("serviceName", params.optString("path", "")))
                .put("multiMode", "multi".equals(params.optString("mode"))));
        } else if ("http".equals(network)) {
            String host = params.optString("host", address);
            stream.put("httpSettings", new JSONObject()
                .put("path", params.optString("path", "/"))
                .put("host", new JSONArray(host.split(","))));
        } else if ("httpupgrade".equals(network) || "xhttp".equals(network)) {
            stream.put(network + "Settings", new JSONObject()
                .put("path", params.optString("path", "/"))
                .put("host", params.optString("host", address)));
        } else if ("kcp".equals(network)) {
            stream.put("kcpSettings", new JSONObject().put("header", new JSONObject().put("type", params.optString("type", "none"))));
        }
        return stream;
    }

    private static JSONObject baseConfig(JSONObject proxy) throws Exception {
        return new JSONObject()
            .put("log", new JSONObject().put("loglevel", "none"))
            .put("dns", new JSONObject().put("servers", new JSONArray().put("1.1.1.1")))
            .put("inbounds", new JSONArray()
                .put(new JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", 10808)
                    .put("protocol", "socks").put("settings", new JSONObject().put("udp", true)))
                .put(new JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", 10809)
                    .put("protocol", "http").put("settings", new JSONObject())))
            .put("outbounds", new JSONArray()
                .put(proxy)
                .put(new JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", new JSONObject()))
                .put(new JSONObject().put("tag", "block").put("protocol", "blackhole").put("settings", new JSONObject())))
            .put("routing", new JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", new JSONArray()));
    }

    private static URI parseUri(String link) {
        try {
            return new URI(link);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("The server link is malformed.", error);
        }
    }

    private static String query(URI uri, String key, String fallback) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) return fallback;
        for (String parameter : rawQuery.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (decode(pair[0]).equals(key)) {
                String value = pair.length == 2 ? decode(pair[1]) : "";
                return value.trim().isEmpty() ? fallback : value;
            }
        }
        return fallback;
    }

    private static boolean hasQueryParameter(URI uri, String key) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) return false;
        for (String parameter : rawQuery.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (decode(pair[0]).equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException("UTF-8 decoding is unavailable.", error);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("The server link contains invalid URL encoding.", error);
        }
    }

    private static String unbracketHost(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static void requireEndpoint(String address, String credential, int port) {
        if (address == null || address.trim().isEmpty() || credential == null || credential.trim().isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("The server link needs a host, credential and valid port.");
        }
    }

    private static int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String decodeBase64(String value) {
        String normalized = value.replace('-', '+').replace('_', '/').replaceAll("\\s", "");
        try {
            int firstPadding = normalized.indexOf('=');
            int padding;
            if (firstPadding >= 0) {
                padding = normalized.length() - firstPadding;
                if (padding > 2 || normalized.length() % 4 != 0) {
                    throw new IllegalArgumentException("Invalid Base64 padding.");
                }
                for (int index = firstPadding; index < normalized.length(); index++) {
                    if (normalized.charAt(index) != '=') throw new IllegalArgumentException("Invalid Base64 padding.");
                }
            } else {
                int remainder = normalized.length() % 4;
                if (remainder == 1) throw new IllegalArgumentException("Invalid Base64 length.");
                padding = (4 - remainder) % 4;
                normalized += "=".repeat(padding);
            }

            ByteArrayOutputStream decoded = new ByteArrayOutputStream(normalized.length() / 4 * 3 - padding);
            for (int index = 0; index < normalized.length(); index += 4) {
                boolean finalBlock = index + 4 == normalized.length();
                int a = base64Value(normalized.charAt(index));
                int b = base64Value(normalized.charAt(index + 1));
                char third = normalized.charAt(index + 2);
                char fourth = normalized.charAt(index + 3);
                if (a < 0 || b < 0 || (third == '=' && fourth != '=') || (!finalBlock && (third == '=' || fourth == '='))) {
                    throw new IllegalArgumentException("Invalid Base64 data.");
                }
                int c = third == '=' ? 0 : base64Value(third);
                int d = fourth == '=' ? 0 : base64Value(fourth);
                if (c < 0 || d < 0) throw new IllegalArgumentException("Invalid Base64 data.");
                int block = (a << 18) | (b << 12) | (c << 6) | d;
                decoded.write((block >>> 16) & 0xff);
                if (third != '=') decoded.write((block >>> 8) & 0xff);
                if (fourth != '=') decoded.write(block & 0xff);
            }
            return new String(decoded.toByteArray(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("The configuration link contains invalid Base64 data.", error);
        }
    }

    private static int base64Value(char character) {
        if (character >= 'A' && character <= 'Z') return character - 'A';
        if (character >= 'a' && character <= 'z') return character - 'a' + 26;
        if (character >= '0' && character <= '9') return character - '0' + 52;
        if (character == '+') return 62;
        if (character == '/') return 63;
        return -1;
    }

    static final class ImportedServer {
        final JSONObject config;
        final String name;

        ImportedServer(JSONObject config, String name) {
            this.config = config;
            this.name = name;
        }
    }
}
