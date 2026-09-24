package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Subscriptions like Liberty's answer with a JSON array of whole Xray client
 * configs, one per server (the desktop parses the same format). A regular
 * server is an entry with exactly one outbound tagged "proxy". Whitelist-
 * bypass entries ("БС") carry a `proxy-wl-*` server (a Russian IP behind a
 * whitelisted TLS name, usually XHTTP) and a `proxy-decoy-*` one; each
 * becomes a server in [WHITELIST_GROUP], the same node imported once.
 *
 * Protocols sing-box has exactly (VLESS over plain TCP, Hysteria2,
 * Shadowsocks) become native outbounds; anything else (XHTTP, gRPC/WS
 * variants) runs as-is in the xray-core built into our libbox.
 */
object XrayMultiConfig {
    const val WHITELIST_GROUP = "Обход белых списков"

    /** Servers in [body], or null when it isn't an Xray multi-config. */
    fun parse(body: String): List<ProxyLinkParser.Server>? {
        val text = body.trim()
        val elements: List<JSONObject> = when {
            text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()?.let { listOf(it) }
            else -> null
        } ?: return null
        // A sing-box profile is JSON too: Xray entries have outbounds with "protocol".
        val looksLikeXray = elements.any { el ->
            val obs = el.optJSONArray("outbounds") ?: return@any false
            (0 until obs.length()).any { obs.optJSONObject(it)?.has("protocol") == true }
        }
        if (!looksLikeXray) return null

        val servers = mutableListOf<ProxyLinkParser.Server>()
        val seenBypass = HashSet<String>()
        for (el in elements) {
            val regular = regularServer(el)
            if (regular != null) servers += regular else servers += bypassServers(el, seenBypass)
        }
        return servers
    }

    private fun outboundsOf(el: JSONObject): List<JSONObject> {
        val obs = el.optJSONArray("outbounds") ?: return emptyList()
        return (0 until obs.length()).mapNotNull { obs.optJSONObject(it) }
    }

    private fun regularServer(el: JSONObject): ProxyLinkParser.Server? {
        val proxies = outboundsOf(el).filter { it.optString("tag") == "proxy" }
        val proxy = proxies.singleOrNull() ?: return null
        val outbound = toOutbound(proxy) ?: return null
        val name = el.optString("remarks").ifBlank { "server" }
        return ProxyLinkParser.Server(name, outbound)
    }

    private fun bypassServers(el: JSONObject, seen: MutableSet<String>): List<ProxyLinkParser.Server> {
        val base = el.optString("remarks")
            .replace("⬇️", "").replace("⬇", "").trim()
            .ifEmpty { WHITELIST_GROUP }
        val out = mutableListOf<ProxyLinkParser.Server>()
        for (ob in outboundsOf(el)) {
            val tag = ob.optString("tag")
            val decoy = tag.startsWith("proxy-decoy-")
            if (!decoy && !tag.startsWith("proxy-wl-")) continue
            val key = JSONObject(ob.toString()).apply { remove("tag") }.toString()
            if (!seen.add(key)) continue
            val outbound = toOutbound(ob) ?: continue
            out += ProxyLinkParser.Server(if (decoy) "$base · decoy" else base, outbound, WHITELIST_GROUP)
        }
        return out
    }

    private fun isPlainTcp(ob: JSONObject): Boolean =
        ob.optJSONObject("streamSettings")?.optString("network").let { it == null || it == "" || it == "tcp" || it == "raw" }

    /** A sing-box outbound (no tag) for one Xray outbound. */
    internal fun toOutbound(ob: JSONObject): JSONObject? {
        val native = when (ob.optString("protocol")) {
            "vless" -> if (isPlainTcp(ob)) vless(ob) else null
            "hysteria" -> hysteria2(ob)
            "shadowsocks" -> shadowsocks(ob)
            else -> null
        }
        return native ?: passthrough(ob)
    }

    private fun passthrough(ob: JSONObject): JSONObject? {
        val settings = ob.optJSONObject("settings") ?: return null
        val endpoint = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: settings
        if (endpoint.optString("address").isEmpty()) return null
        val inner = JSONObject(ob.toString()).apply { remove("tag") }
        return JSONObject().put("type", "xray").put("outbound", inner)
    }

    private fun vless(ob: JSONObject): JSONObject? {
        val vnext = ob.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: return null
        val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
        val stream = ob.optJSONObject("streamSettings")
        val security = stream?.optString("security")?.ifEmpty { null } ?: "none"
        val reality = stream?.optJSONObject("realitySettings")
        val tlsSettings = stream?.optJSONObject("tlsSettings")
        return JSONObject().apply {
            put("type", "vless")
            put("server", vnext.optString("address").ifEmpty { return null })
            put("server_port", vnext.optInt("port").takeIf { it > 0 } ?: return null)
            put("uuid", user.optString("id").ifEmpty { return null })
            user.optString("flow").takeIf { it.isNotEmpty() }?.let { put("flow", it) }
            if (security == "reality" || security == "tls") {
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        val sni = (reality ?: tlsSettings)?.optString("serverName").orEmpty()
                        if (sni.isNotEmpty()) put("server_name", sni)
                        val fp = (reality ?: tlsSettings)?.optString("fingerprint")?.ifEmpty { null }
                            ?: if (security == "reality") "chrome" else null
                        fp?.let { put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
                        if (security == "reality" && reality != null) {
                            put(
                                "reality",
                                JSONObject().apply {
                                    put("enabled", true)
                                    put("public_key", reality.optString("publicKey"))
                                    reality.optString("shortId").takeIf { it.isNotEmpty() }?.let { put("short_id", it) }
                                },
                            )
                        }
                        tlsSettings?.optJSONArray("alpn")?.let { put("alpn", it) }
                    },
                )
            }
        }
    }

    /** Xray labels Hysteria2 as "hysteria" with settings.version 2; v1 is skipped. */
    private fun hysteria2(ob: JSONObject): JSONObject? {
        val settings = ob.optJSONObject("settings") ?: return null
        if (settings.optInt("version") != 2) return null
        val stream = ob.optJSONObject("streamSettings") ?: return null
        val password = stream.optJSONObject("hysteriaSettings")?.optString("auth")?.ifEmpty { null } ?: return null
        val tls = stream.optJSONObject("tlsSettings")
        return JSONObject().apply {
            put("type", "hysteria2")
            put("server", settings.optString("address").ifEmpty { return null })
            put("server_port", settings.optInt("port").takeIf { it > 0 } ?: return null)
            put("password", password)
            put(
                "tls",
                JSONObject().apply {
                    put("enabled", true)
                    tls?.optString("serverName")?.takeIf { it.isNotEmpty() }?.let { put("server_name", it) }
                    if (tls?.optBoolean("allowInsecure") == true) put("insecure", true)
                },
            )
        }
    }

    private fun shadowsocks(ob: JSONObject): JSONObject? {
        val entry = ob.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0) ?: return null
        return JSONObject().apply {
            put("type", "shadowsocks")
            put("server", entry.optString("address").ifEmpty { return null })
            put("server_port", entry.optInt("port").takeIf { it > 0 } ?: return null)
            put("method", entry.optString("method").ifEmpty { return null })
            put("password", entry.optString("password").ifEmpty { return null })
        }
    }
}
