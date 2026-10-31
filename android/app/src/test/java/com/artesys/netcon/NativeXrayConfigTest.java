package com.artesys.netcon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

public final class NativeXrayConfigTest {
    @Test
    public void acceptsLocalXrayJson() throws Exception {
        NativeXrayConfig.ImportedServer imported = NativeXrayConfig.fromInput(
            "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\",\"settings\":{}}]}");
        assertEquals("Imported Xray configuration", imported.name);
        assertEquals(1, imported.config.getJSONArray("outbounds").length());
    }

    @Test
    public void bypassLanUsesExplicitRangesWithoutGeoIpData() throws Exception {
        NativeXrayConfig.ImportedServer imported = NativeXrayConfig.fromInput(
            "vless://user-id@example.com:443");
        org.json.JSONObject configured = new org.json.JSONObject(NativeXrayConfig.configuredXrayJson(
            imported.config.toString(), "1.1.1.1", "Bypass LAN"));
        org.json.JSONArray rules = configured.getJSONObject("routing").getJSONArray("rules");
        org.json.JSONArray ranges = rules.getJSONObject(0).getJSONArray("ip");

        assertEquals(6, ranges.length());
        assertEquals("10.0.0.0/8", ranges.getString(0));
        assertEquals("172.16.0.0/12", ranges.getString(1));
        assertEquals("192.168.0.0/16", ranges.getString(2));
        assertEquals("169.254.0.0/16", ranges.getString(3));
        assertEquals("fc00::/7", ranges.getString(4));
        assertEquals("fe80::/10", ranges.getString(5));
        assertEquals("direct", rules.getJSONObject(0).getString("outboundTag"));
        assertEquals("1.1.1.1", configured.getJSONObject("dns").getJSONArray("servers").getString(0));
        org.junit.Assert.assertFalse(configured.toString().contains("geoip:"));
    }

    @Test
    public void globalRoutingDoesNotRetainImportedRules() throws Exception {
        String config = "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\"}],"
            + "\"routing\":{\"rules\":[{\"type\":\"field\",\"ip\":[\"geoip:private\"]}]}}";
        org.json.JSONObject configured = new org.json.JSONObject(
            NativeXrayConfig.configuredXrayJson(config, "8.8.8.8", "Global"));

        assertEquals(0, configured.getJSONObject("routing").getJSONArray("rules").length());
    }

    @Test
    public void ruleBasedRoutingRetainsImportedRules() throws Exception {
        String config = "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\"}],"
            + "\"routing\":{\"rules\":[{\"type\":\"field\",\"domain\":[\"example.com\"]}]}}";
        org.json.JSONObject configured = new org.json.JSONObject(
            NativeXrayConfig.configuredXrayJson(config, "8.8.8.8", "Rule-based"));

        assertEquals("example.com", configured.getJSONObject("routing").getJSONArray("rules")
            .getJSONObject(0).getJSONArray("domain").getString(0));
    }

    @Test
    public void androidTunInboundDoesNotRequirePrivilegedInterfaceLookup() throws Exception {
        org.json.JSONObject inbound = NativeXrayConfig.androidTunInbound("1.1.1.1");
        org.json.JSONObject settings = inbound.getJSONObject("settings");

        assertEquals("tun", inbound.getString("protocol"));
        assertEquals("netcon0", settings.getString("name"));
        assertEquals(1500, settings.getInt("MTU"));
        assertEquals("1.1.1.1", settings.getJSONArray("DNS").getString(0));
        org.junit.Assert.assertFalse(settings.has("autoOutboundsInterface"));
    }

    @Test
    public void rejectsXrayJsonWithoutSupportedProxyOutbound() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(
            "{\"outbounds\":[{\"protocol\":\"freedom\"}]}"));
    }

    @Test
    public void rejectsXrayJsonWithDuplicateProxyOutbounds() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(
            "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\"},{\"tag\":\"proxy\",\"protocol\":\"trojan\"}]}"));
    }

    @Test
    public void rejectsEmptyConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(" "));
    }

    @Test
    public void rejectsJsonWithEmptyOutbounds() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput("{\"outbounds\":[]}"));
    }

    @Test
    public void rejectsOversizedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput("x".repeat(2_000_001)));
    }

    @Test
    public void parsesVlessRealityAndEncodedParameters() throws Exception {
        NativeXrayConfig.ImportedServer imported = NativeXrayConfig.fromInput(
            "vless://user-id@[2001:db8::1]:443?type=ws&security=reality&sni=edge.example&pbk=public-key&sid=42&path=%2Fvpn&host=front.example#My%20Node");
        assertEquals("My Node", imported.name);
        org.json.JSONObject proxy = proxy(imported);
        assertEquals("vless", proxy.getString("protocol"));
        org.json.JSONObject vnext = proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0);
        assertEquals("2001:db8::1", vnext.getString("address"));
        org.json.JSONObject reality = proxy.getJSONObject("streamSettings").getJSONObject("realitySettings");
        assertEquals("public-key", reality.getString("publicKey"));
        assertEquals("/vpn", proxy.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"));
    }

    @Test
    public void parsesTrojanTlsParameters() throws Exception {
        NativeXrayConfig.ImportedServer imported = NativeXrayConfig.fromInput(
            "trojan://secret@example.com:8443?security=tls&sni=edge.example#Secure%20Node");
        assertEquals("Secure Node", imported.name);
        org.json.JSONObject proxy = proxy(imported);
        assertEquals("trojan", proxy.getString("protocol"));
        assertEquals("secret", proxy.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password"));
        assertEquals("edge.example", proxy.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"));
    }

    @Test
    public void parsesVmessBase64Configuration() throws Exception {
        String json = "{\"add\":\"vmess.example\",\"port\":\"443\",\"id\":\"user-id\",\"ps\":\"VMess test\",\"net\":\"tcp\",\"tls\":\"tls\"}";
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        NativeXrayConfig.ImportedServer imported = NativeXrayConfig.fromInput("vmess://" + encoded);
        assertEquals("VMess test", imported.name);
        assertEquals("vmess", proxy(imported).getString("protocol"));
    }

    @Test
    public void parsesShadowsocksSip002Link() throws Exception {
        NativeXrayConfig.ImportedServer imported = NativeXrayConfig.fromInput(
            "ss://aes-128-gcm:pass%40word@example.com:8388#SS%20Node");
        assertEquals("SS Node", imported.name);
        org.json.JSONObject server = proxy(imported).getJSONObject("settings").getJSONArray("servers").getJSONObject(0);
        assertEquals("shadowsocks", proxy(imported).getString("protocol"));
        assertEquals("pass@word", server.getString("password"));
        assertEquals(8388, server.getInt("port"));
    }

    @Test
    public void rejectsShadowsocksPluginAndMultipleLinks() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(
            "ss://aes-128-gcm:password@example.com:8388?plugin=obfs"));
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(
            "vless://user@example.com:443\nvmess://encoded"));
    }

    @Test
    public void rejectsRealityWithoutPublicKey() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(
            "vless://user@example.com:443?security=reality"));
    }

    @Test
    public void rejectsUnsupportedTransport() {
        assertThrows(IllegalArgumentException.class, () -> NativeXrayConfig.fromInput(
            "vless://user@example.com:443?type=unknown"));
    }

    private static org.json.JSONObject proxy(NativeXrayConfig.ImportedServer imported) {
        org.json.JSONArray outbounds = imported.config.optJSONArray("outbounds");
        for (int index = 0; index < outbounds.length(); index++) {
            org.json.JSONObject outbound = outbounds.optJSONObject(index);
            if ("proxy".equals(outbound.optString("tag"))) return outbound;
        }
        fail("Expected proxy outbound");
        return new org.json.JSONObject();
    }
}
