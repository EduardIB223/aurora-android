package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyLinkParserTest {
    private val uuid = "11111111-1111-1111-1111-111111111111"

    private fun single(link: String): JSONObject {
        val servers = ProxyLinkParser.parseAll(link)
        assertEquals("expected exactly one server from: $link", 1, servers.size)
        return servers[0].outbound
    }

    private fun b64(text: String): String = java.util.Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun vlessRealityOverTcpKeepsFlowAndReality() {
        val ob = single(
            "vless://$uuid@nl.example.com:443?encryption=none&flow=xtls-rprx-vision&type=tcp" +
                "&security=reality&sni=www.example.com&fp=firefox&pbk=PUBKEY&sid=428ef87f#NL-1",
        )
        assertEquals("vless", ob.getString("type"))
        assertEquals("nl.example.com", ob.getString("server"))
        assertEquals(443, ob.getInt("server_port"))
        assertEquals("xtls-rprx-vision", ob.getString("flow"))
        val tls = ob.getJSONObject("tls")
        assertEquals("www.example.com", tls.getString("server_name"))
        assertEquals("PUBKEY", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("428ef87f", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
        assertFalse("raw TCP must not get a transport block", ob.has("transport"))
    }

    @Test
    fun vlessWebSocketDropsFlowSingBoxWouldReject() {
        val ob = single(
            "vless://$uuid@cdn.example.com:8443?type=ws&path=%2Fapi%2Fstream&host=cdn.example.com" +
                "&security=tls&flow=xtls-rprx-vision&alpn=h2%2Chttp%2F1.1#WS",
        )
        val transport = ob.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/api/stream", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getJSONObject("headers").getString("Host"))
        assertFalse("flow over ws makes sing-box reject the profile", ob.has("flow"))
        assertEquals(2, ob.getJSONObject("tls").getJSONArray("alpn").length())
    }

    @Test
    fun vlessGrpcServiceName() {
        val ob = single("vless://$uuid@g.example.com:443?type=grpc&serviceName=my-svc&mode=gun&security=reality&pbk=K&sid=ab#G")
        val transport = ob.getJSONObject("transport")
        assertEquals("grpc", transport.getString("type"))
        assertEquals("my-svc", transport.getString("service_name"))
    }

    @Test
    fun realityWithoutFingerprintGetsUtlsDefault() {
        val ob = single("vless://$uuid@r.example.com:443?security=reality&pbk=K&sid=ab#R")
        assertTrue(ob.getJSONObject("tls").has("utls"))
    }

    @Test
    fun hysteria2AndHy2Schemes() {
        for (scheme in listOf("hysteria2", "hy2")) {
            val ob = single("$scheme://secret@h.example.com:8443?sni=h.example.com&insecure=1&obfs=salamander&obfs-password=x#HY")
            assertEquals("hysteria2", ob.getString("type"))
            assertEquals("secret", ob.getString("password"))
            assertTrue(ob.getJSONObject("tls").getBoolean("insecure"))
            assertEquals("salamander", ob.getJSONObject("obfs").getString("type"))
        }
    }

    @Test
    fun socksWithCredentials() {
        val ob = single("socks5://user:pass@127.0.0.1:1080#S")
        assertEquals("socks", ob.getString("type"))
        assertEquals("user", ob.getString("username"))
        assertEquals("pass", ob.getString("password"))
    }

    @Test
    fun shadowsocksBothFormats() {
        val sip002 = single("ss://${b64("aes-256-gcm:pw")}@ss.example.com:8388#SS")
        assertEquals("aes-256-gcm", sip002.getString("method"))
        assertEquals("pw", sip002.getString("password"))

        val legacy = single("ss://${b64("chacha20-ietf-poly1305:pw2@ss2.example.com:8389")}#Old")
        assertEquals("ss2.example.com", legacy.getString("server"))
        assertEquals(8389, legacy.getInt("server_port"))
        assertEquals("pw2", legacy.getString("password"))
    }

    @Test
    fun vmessWebSocket() {
        val json = """{"v":"2","ps":"VM","add":"v.example.com","port":"443","id":"$uuid","aid":"0","net":"ws","path":"/ray","host":"cdn.example.com","tls":"tls"}"""
        val ob = single("vmess://${b64(json)}")
        assertEquals("vmess", ob.getString("type"))
        assertEquals("/ray", ob.getJSONObject("transport").getString("path"))
        assertTrue(ob.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun namesAreDecodedIncludingEmojiAndCyrillic() {
        val servers = ProxyLinkParser.parseAll("vless://$uuid@a.example.com:443?security=tls#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B")
        assertEquals("🇳🇱 Нидерланды", servers[0].name)
    }

    @Test
    fun percentDecodeKeepsBase64Padding() {
        assertEquals("abc/def==", ProxyLinkParser.percentDecode("abc%2Fdef%3D%3D"))
        assertEquals("a b", ProxyLinkParser.percentDecode("a+b"))
    }

    @Test
    fun base64SubscriptionBodyYieldsEveryServer() {
        val links = (1..5).joinToString("\n") { "vless://$uuid@s$it.example.com:443?security=tls#Server $it" }
        // Wrapped across lines, as some panels send it.
        val body = b64(links).chunked(40).joinToString("\n")
        val servers = ProxyLinkParser.parseAll(body)
        assertEquals(5, servers.size)
        assertEquals("Server 3", servers[2].name)
    }

    @Test
    fun malformedLinesAreSkipped() {
        val servers = ProxyLinkParser.parseAll(
            "vless://$uuid@ok.example.com:443#OK\nvless://broken\nnot a link\ntrojan://pw@t.example.com:443#T",
        )
        assertEquals(listOf("OK", "T"), servers.map { it.name })
    }

    @Test
    fun configGroupsEveryServerForSwitchingAndPing() {
        val parsed = ProxyLinkParser.parse(
            "vless://$uuid@a.example.com:443?security=tls#Same\nvless://$uuid@b.example.com:443?security=tls#Same",
        )
        assertNotNull(parsed)
        assertEquals(2, parsed!!.serverCount)

        val outbounds = JSONObject(parsed.config).getJSONArray("outbounds")
        val byTag = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.associateBy { it.getString("tag") }

        // Duplicate names get unique tags.
        assertTrue(byTag.containsKey("Same") && byTag.containsKey("Same (2)"))

        val auto = byTag.getValue(ProxyLinkParser.AUTO_TAG)
        assertEquals("urltest", auto.getString("type"))
        assertEquals(2, auto.getJSONArray("outbounds").length())

        val selector = byTag.getValue(ProxyLinkParser.PROXY_TAG)
        assertEquals("selector", selector.getString("type"))
        assertEquals(ProxyLinkParser.AUTO_TAG, selector.getString("default"))
    }

    @Test
    fun singleServerStillGetsGroupsSoPingAllWorks() {
        val parsed = ProxyLinkParser.parse("vless://$uuid@a.example.com:443?security=tls#Only")!!
        val outbounds = JSONObject(parsed.config).getJSONArray("outbounds")
        val types = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("type") }
        assertTrue(types.contains("urltest"))
        assertTrue(types.contains("selector"))
        assertEquals("Only", parsed.name)
    }

    @Test
    fun localDnsHasNoDirectDetour() {
        // sing-box aborts at startup on a DNS server detoured to a bare `direct`.
        val parsed = ProxyLinkParser.parse("vless://$uuid@a.example.com:443?security=tls#A")!!
        val servers = JSONObject(parsed.config).getJSONObject("dns").getJSONArray("servers")
        val local = (0 until servers.length()).map { servers.getJSONObject(it) }.first { it.getString("tag") == "dns-local" }
        assertFalse(local.has("detour"))
    }

    @Test
    fun notALinkReturnsNull() {
        assertNull(ProxyLinkParser.parse("hello world"))
        assertFalse(ProxyLinkParser.isProxyLink("https://example.com/sub"))
        assertTrue(ProxyLinkParser.isProxyLink("  VLESS://x@y:1"))
    }

    @Test
    fun subscriptionWrappersUnwrapToTheUrl() {
        val url = "https://example.com/sub/abc"
        val enc = "https%3A%2F%2Fexample.com%2Fsub%2Fabc"
        assertEquals(url, ProxyLinkParser.subscriptionUrl(url))
        assertEquals(url, ProxyLinkParser.subscriptionUrl("clash://install-config?url=$enc"))
        assertEquals(url, ProxyLinkParser.subscriptionUrl("hiddify://install-config?url=$enc"))
        assertEquals(url, ProxyLinkParser.subscriptionUrl("happ://add/$url"))
        assertEquals(url, ProxyLinkParser.subscriptionUrl("sub://${b64(url)}"))
        assertNull(ProxyLinkParser.subscriptionUrl("vless://$uuid@a.example.com:443"))
        assertNull(ProxyLinkParser.subscriptionUrl("https://"))
    }

    @Test
    fun base64RejectsBinaryGarbage() {
        assertNull(ProxyLinkParser.base64Decode("not base64!"))
        assertEquals("hello", ProxyLinkParser.base64Decode("aGVsbG8"))
    }
}

/**
 * Opt-in: converts a real subscription body into a config file so it can be
 * run in sing-box. Reads/writes paths from env vars; never embeds real data.
 *   AURORA_SUB_FILE=<body> AURORA_OUT_FILE=<config.json> ./gradlew testOtherDebugUnitTest
 */
class ProxyLinkParserLiveExport {
    @Test
    fun exportRealSubscription() {
        val input = System.getenv("AURORA_SUB_FILE") ?: return
        val output = System.getenv("AURORA_OUT_FILE") ?: return
        val body = java.io.File(input).readText()
        val parsed = ProxyLinkParser.parse(body)
        assertNotNull("subscription should parse", parsed)
        java.io.File(output).writeText(parsed!!.config)
        println("exported ${parsed.serverCount} servers to $output")
    }
}
