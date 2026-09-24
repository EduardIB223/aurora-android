package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayMultiConfigTest {
    /** The shape of a Liberty subscription: regular servers plus whitelist-bypass bundles. */
    private val body =
        """
        [
          { "remarks": "🇩🇪⚡Германия", "outbounds": [
            { "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [{ "address": "203.0.113.10", "port": 8443,
                "users": [{ "id": "11111111-1111-1111-1111-111111111111", "encryption": "none", "flow": "xtls-rprx-vision" }] }] },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "serverName": "www.example.com", "publicKey": "PUB", "shortId": "ab12", "fingerprint": "firefox" } } },
            { "tag": "direct", "protocol": "freedom" } ] },
          { "remarks": "🇷🇺💳Россия", "outbounds": [
            { "tag": "proxy", "protocol": "hysteria",
              "settings": { "version": 2, "address": "203.0.113.20", "port": 8449 },
              "streamSettings": { "hysteriaSettings": { "auth": "secret" }, "tlsSettings": { "serverName": "ru.example" } } } ] },
          { "remarks": "DE (BS-5)", "outbounds": [
            { "tag": "proxy-decoy-1", "protocol": "vless",
              "settings": { "vnext": [{ "address": "203.0.113.30", "port": 8443,
                "users": [{ "id": "22222222-2222-2222-2222-222222222222", "encryption": "none" }] }] },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "serverName": "cdn.newyear.mail.ru", "publicKey": "K", "shortId": "cd" } } },
            { "tag": "proxy-wl-2", "protocol": "vless",
              "settings": { "vnext": [{ "address": "203.0.113.40", "port": 443,
                "users": [{ "id": "33333333-3333-3333-3333-333333333333", "encryption": "none" }] }] },
              "streamSettings": { "network": "xhttp", "security": "tls",
                "xhttpSettings": { "mode": "packet-up", "path": "/live/" },
                "tlsSettings": { "serverName": "cdn.tracker.yandex.net" } } } ] },
          { "remarks": "DE (BS-5) again", "outbounds": [
            { "tag": "proxy-wl-2", "protocol": "vless",
              "settings": { "vnext": [{ "address": "203.0.113.40", "port": 443,
                "users": [{ "id": "33333333-3333-3333-3333-333333333333", "encryption": "none" }] }] },
              "streamSettings": { "network": "xhttp", "security": "tls",
                "xhttpSettings": { "mode": "packet-up", "path": "/live/" },
                "tlsSettings": { "serverName": "cdn.tracker.yandex.net" } } } ] }
        ]
        """.trimIndent()

    @Test
    fun regularServersBecomeNativeOutbounds() {
        val servers = XrayMultiConfig.parse(body)!!
        val de = servers.first { it.name == "🇩🇪⚡Германия" }.outbound
        assertEquals("vless", de.getString("type"))
        assertEquals("203.0.113.10", de.getString("server"))
        assertEquals("xtls-rprx-vision", de.getString("flow"))
        assertEquals("ab12", de.getJSONObject("tls").getJSONObject("reality").getString("short_id"))
        assertEquals("firefox", de.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
        val ru = servers.first { it.name == "🇷🇺💳Россия" }.outbound
        assertEquals("hysteria2", ru.getString("type"))
        assertEquals("secret", ru.getString("password"))
    }

    @Test
    fun bypassBundlesBecomeAGroupWithXhttpInXray() {
        val servers = XrayMultiConfig.parse(body)!!
        val bypass = servers.filter { it.group == XrayMultiConfig.WHITELIST_GROUP }
        assertEquals("the repeated wl node is imported once", 2, bypass.size)
        val decoy = bypass.first { it.name == "DE (BS-5) · decoy" }.outbound
        assertEquals("vless", decoy.getString("type"))
        val wl = bypass.first { it.name == "DE (BS-5)" }.outbound
        assertEquals("xray", wl.getString("type"))
        assertEquals("xhttp", wl.getJSONObject("outbound").getJSONObject("streamSettings").getString("network"))
        assertTrue("the provider's tag is dropped", !wl.getJSONObject("outbound").has("tag"))
    }

    @Test
    fun theProfileHasAWhitelistSectionAndRuAppsBypassTheVpn() {
        val config = JSONObject(ProxyLinkParser.parse(body)!!.config)
        val sections = ServerSections.of(ServerSelection.read(config.toString())!!)
        assertEquals(listOf(null, XrayMultiConfig.WHITELIST_GROUP), sections.map { it.title })
        val excluded = config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("exclude_package")
        assertTrue((0 until excluded.length()).any { excluded.getString(it) == "ru.ozon.app.android" })
    }

    @Test
    fun aSingBoxProfileIsNotMistakenForXray() {
        assertNull(XrayMultiConfig.parse("""{"outbounds":[{"type":"direct","tag":"direct"}]}"""))
        assertNull(XrayMultiConfig.parse("vless://x@y:1"))
    }
}
