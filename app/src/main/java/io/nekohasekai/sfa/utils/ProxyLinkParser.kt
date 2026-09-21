package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts share links (vless://, vmess://, trojan://, ss://, hysteria2://,
 * socks://) and subscription bodies into a complete sing-box configuration.
 *
 * sing-box itself only understands its own profile format, so links copied from
 * a panel or scanned from a QR code cannot be imported as-is. This builds a
 * ready-to-run config around the parsed servers: TUN inbound, DNS through the
 * proxy, and routing that keeps Russian domains and address ranges off the
 * tunnel so banking and government apps keep working on a local IP.
 *
 * Every server lands in a `selector` group ("proxy") plus a `urltest` group
 * ("auto"), so the app can switch servers and ping all of them at once — even
 * when the profile holds a single server.
 *
 * Parsing is done by hand rather than with android.net.Uri: panels emit links
 * that strict URI parsers reject (raw emoji in names, unescaped characters),
 * and it keeps this class testable on a plain JVM.
 */
object ProxyLinkParser {
    const val PROXY_TAG = "proxy"
    const val AUTO_TAG = "auto"
    const val DIRECT_TAG = "direct"

    /** URL the auto group and the "ping all" button measure through each server. */
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    data class ParsedLink(
        val name: String,
        val config: String,
        val serverCount: Int = 1,
    )

    /** One server: its display name and a sing-box outbound (without a tag). */
    data class Server(
        val name: String,
        val outbound: JSONObject,
    )

    private val schemes =
        listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "socks5://", "socks://")

    fun isProxyLink(data: String): Boolean {
        val trimmed = data.trim()
        return schemes.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    /**
     * Prefixes other clients use to wrap a subscription URL. `true` means the
     * URL sits in a `url=` parameter; `false` that the rest of the link is the
     * payload (plain, percent-encoded or base64).
     */
    private val wrapperPrefixes =
        listOf(
            "clash://install-config?" to true,
            "clashmeta://install-config?" to true,
            "sing-box://import-remote-profile?" to true,
            "sn://subscription?" to true,
            "hiddify://install-config?" to true,
            "hiddify://install-sub?" to true,
            "v2rayn://install-sub?" to true,
            "v2raytun://import/" to false,
            "happ://add/" to false,
            "sub://" to false,
        )

    /**
     * Recognises a subscription link and normalises it to a plain http(s) URL:
     * direct `https://…` as well as the wrapper links other apps hand out.
     * Returns null for single-server links and anything unrecognised.
     */
    fun subscriptionUrl(data: String): String? {
        val link = data.trim()
        if (link.isEmpty() || link.contains('\n')) return null
        if (link.startsWith("http://", true) || link.startsWith("https://", true)) {
            return link.takeIf { it.substringAfter("://").isNotBlank() }
        }
        val lower = link.lowercase()
        for ((prefix, inQuery) in wrapperPrefixes) {
            if (!lower.startsWith(prefix)) continue
            val payload = link.substring(prefix.length)
            val candidate =
                if (inQuery) parseQuery(payload)["url"] ?: return null else payload.trimStart('/')
            val decoded = percentDecode(candidate)
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) return decoded
            val fromB64 = base64Decode(candidate)?.trim()
            if (fromB64 != null && (fromB64.startsWith("http://") || fromB64.startsWith("https://"))) return fromB64
            return null
        }
        return null
    }

    /**
     * Parses one or more links (newline separated), or a base64 subscription
     * body, into a config holding every valid server.
     */
    fun parse(data: String, name: String? = null): ParsedLink? {
        val servers = parseAll(data)
        if (servers.isEmpty()) return null
        val displayName =
            name?.takeIf { it.isNotBlank() }
                ?: if (servers.size == 1) servers[0].name else "Aurora (${servers.size})"
        return ParsedLink(
            name = displayName,
            config = buildConfig(servers),
            serverCount = servers.size,
        )
    }

    /** Every server found in [data]; malformed lines are skipped. */
    fun parseAll(data: String): List<Server> {
        val text = data.trim()
        if (text.isEmpty()) return emptyList()

        val direct = text.lines().mapNotNull { parseLine(it) }
        if (direct.isNotEmpty()) return direct

        // Subscription bodies are usually base64 of the link list, sometimes
        // wrapped across lines.
        val compact = text.filterNot { it.isWhitespace() }
        val decoded = base64Decode(compact) ?: return emptyList()
        return decoded.lines().mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): Server? {
        val link = line.trim()
        if (link.isEmpty()) return null
        return runCatching {
            when {
                link.startsWith("vless://", true) -> parseVless(link)
                link.startsWith("trojan://", true) -> parseTrojan(link)
                link.startsWith("vmess://", true) -> parseVmess(link)
                link.startsWith("ss://", true) -> parseShadowsocks(link)
                link.startsWith("hysteria2://", true) || link.startsWith("hy2://", true) -> parseHysteria2(link)
                link.startsWith("socks5://", true) || link.startsWith("socks://", true) -> parseSocks(link)
                else -> null
            }
        }.getOrNull()
    }

    // ── Link splitting ──

    /** The parts of `scheme://userinfo@host:port?query#name`. */
    private class Link(
        val userInfo: String?,
        val host: String,
        val port: Int,
        val params: Map<String, String>,
        val name: String,
    ) {
        fun param(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
    }

    private fun splitLink(link: String): Link {
        val afterScheme = link.substringAfter("://")
        val name = if (afterScheme.contains('#')) percentDecode(afterScheme.substringAfterLast('#')) else ""
        val body = afterScheme.substringBeforeLast('#')

        val authority = body.substringBefore('?').trimEnd('/')
        val query = if (body.contains('?')) body.substringAfter('?') else ""

        val userInfo = if (authority.contains('@')) authority.substringBeforeLast('@') else null
        val hostPort = authority.substringAfterLast('@')
        val (host, port) = splitHostPort(hostPort)

        return Link(userInfo, host, port, parseQuery(query), name)
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        if (hostPort.startsWith('[')) {
            val end = hostPort.indexOf("]:")
            require(end > 0) { "missing port" }
            return hostPort.substring(1, end) to hostPort.substring(end + 2).toInt()
        }
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: error("missing port")
        require(host.isNotBlank() && port in 1..65535) { "invalid address" }
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=')
            val value = if (pair.contains('=')) pair.substringAfter('=') else ""
            map[key] = percentDecode(value)
        }
        return map
    }

    /**
     * Percent-decode one value (`+` means space). Decoding values one at a time
     * matters: `=` inside base64 keys and `&` inside paths must survive.
     */
    internal fun percentDecode(value: String): String {
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                    } else {
                        out.write(c.code)
                        i++
                    }
                }
                c == '+' -> {
                    out.write(' '.code)
                    i++
                }
                else -> {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    out.write(bytes, 0, bytes.size)
                    i++
                }
            }
        }
        return out.toString("UTF-8")
    }

    // ── Protocols ──

    private fun parseVless(link: String): Server {
        val l = splitLink(link)
        val outbound =
            JSONObject().apply {
                put("type", "vless")
                put("server", l.host)
                put("server_port", l.port)
                put("uuid", percentDecode(l.userInfo ?: error("missing uuid")))
            }

        val hasTransport = applyTransport(outbound, l)
        // xtls-rprx-vision only exists over raw TCP; keeping it next to a ws or
        // grpc transport makes sing-box reject the whole profile.
        l.param("flow")?.let { if (!hasTransport) outbound.put("flow", it) }
        applyTls(outbound, l, defaultSni = l.host)
        return Server(l.name.ifBlank { l.host }, outbound)
    }

    private fun parseTrojan(link: String): Server {
        val l = splitLink(link)
        val outbound =
            JSONObject().apply {
                put("type", "trojan")
                put("server", l.host)
                put("server_port", l.port)
                put("password", percentDecode(l.userInfo ?: error("missing password")))
            }
        applyTransport(outbound, l)
        applyTls(outbound, l, defaultSni = l.host, tlsByDefault = true)
        return Server(l.name.ifBlank { l.host }, outbound)
    }

    private fun parseHysteria2(link: String): Server {
        val l = splitLink(link)
        val outbound =
            JSONObject().apply {
                put("type", "hysteria2")
                put("server", l.host)
                put("server_port", l.port)
                put("password", percentDecode(l.userInfo ?: error("missing password")))
                if (l.param("obfs") == "salamander") {
                    put(
                        "obfs",
                        JSONObject()
                            .put("type", "salamander")
                            .put("password", l.param("obfs-password").orEmpty()),
                    )
                }
                put(
                    "tls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("server_name", l.param("sni") ?: l.host)
                        val insecure = l.param("insecure")
                        if (insecure == "1" || insecure == "true") put("insecure", true)
                        l.param("alpn")?.let { put("alpn", JSONArray(it.split(",").map(String::trim))) }
                    },
                )
            }
        return Server(l.name.ifBlank { l.host }, outbound)
    }

    private fun parseSocks(link: String): Server {
        val l = splitLink(link)
        val outbound =
            JSONObject().apply {
                put("type", "socks")
                put("server", l.host)
                put("server_port", l.port)
                put("version", "5")
                l.userInfo?.let { info ->
                    // Some clients base64 the credentials.
                    val creds = if (info.contains(':')) info else base64Decode(info) ?: info
                    val user = percentDecode(creds.substringBefore(':'))
                    if (user.isNotBlank()) put("username", user)
                    if (creds.contains(':')) put("password", percentDecode(creds.substringAfter(':')))
                }
            }
        return Server(l.name.ifBlank { l.host }, outbound)
    }

    private fun parseVmess(link: String): Server {
        val payload = link.substringAfter("://").trim()
        val json = JSONObject(base64Decode(payload) ?: error("bad vmess payload"))

        val host = json.optString("add").ifBlank { error("missing host") }
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port").takeIf { it > 0 } ?: error("missing port")

        val outbound =
            JSONObject().apply {
                put("type", "vmess")
                put("server", host)
                put("server_port", port)
                put("uuid", json.optString("id"))
                put("alter_id", json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0))
                put("security", json.optString("scy").ifBlank { "auto" })
            }

        if (json.optString("tls").equals("tls", true)) {
            outbound.put(
                "tls",
                JSONObject().apply {
                    put("enabled", true)
                    put("server_name", json.optString("sni").ifBlank { json.optString("host").ifBlank { host } })
                    json.optString("fp").takeIf { it.isNotBlank() }?.let { fp ->
                        put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                    }
                    json.optString("alpn").takeIf { it.isNotBlank() }?.let {
                        put("alpn", JSONArray(it.split(",").map(String::trim)))
                    }
                },
            )
        }

        val params =
            buildMap {
                put("type", json.optString("net"))
                put("path", json.optString("path"))
                put("host", json.optString("host"))
                // VMess JSON conventionally carries the gRPC service name in `path`.
                put("serviceName", json.optString("serviceName").ifBlank { json.optString("path") })
            }
        applyTransport(outbound, Link(null, host, port, params, ""))

        return Server(json.optString("ps").ifBlank { host }, outbound)
    }

    private fun parseShadowsocks(link: String): Server {
        val afterScheme = link.substringAfter("://")
        val name = if (afterScheme.contains('#')) percentDecode(afterScheme.substringAfterLast('#')) else ""
        val body = afterScheme.substringBeforeLast('#').substringBefore('?').trimEnd('/')

        val method: String
        val password: String
        val host: String
        val port: Int

        if (body.contains('@')) {
            // ss://base64(method:password)@host:port  or  ss://method:password@host:port
            val userInfo = body.substringBeforeLast('@')
            val decoded = base64Decode(userInfo)?.takeIf { it.contains(':') } ?: percentDecode(userInfo)
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':')
            val hp = splitHostPort(body.substringAfterLast('@'))
            host = hp.first
            port = hp.second
        } else {
            // ss://base64(method:password@host:port)
            val decoded = base64Decode(body) ?: error("bad ss payload")
            method = decoded.substringBefore(':')
            val rest = decoded.substringAfter(':')
            password = rest.substringBeforeLast('@')
            val hp = splitHostPort(rest.substringAfterLast('@'))
            host = hp.first
            port = hp.second
        }

        val outbound =
            JSONObject().apply {
                put("type", "shadowsocks")
                put("server", host)
                put("server_port", port)
                put("method", method)
                put("password", password)
            }
        return Server(name.ifBlank { host }, outbound)
    }

    private fun applyTls(
        outbound: JSONObject,
        l: Link,
        defaultSni: String,
        tlsByDefault: Boolean = false,
    ) {
        val security = l.param("security")?.lowercase()
        val enabled = tlsByDefault || security == "tls" || security == "reality" || security == "xtls"
        if (!enabled || security == "none") return

        val tls =
            JSONObject().apply {
                put("enabled", true)
                put("server_name", l.param("sni") ?: l.param("peer") ?: defaultSni)
                l.param("alpn")?.let { put("alpn", JSONArray(it.split(",").map(String::trim))) }
                l.param("fp")?.let { put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
                val insecure = l.param("allowInsecure") ?: l.param("insecure")
                if (insecure == "1" || insecure == "true") put("insecure", true)
                if (security == "reality") {
                    put(
                        "reality",
                        JSONObject().apply {
                            put("enabled", true)
                            put("public_key", l.param("pbk").orEmpty())
                            l.param("sid")?.let { put("short_id", it) }
                        },
                    )
                    // REALITY requires uTLS; default it like other clients do.
                    if (!has("utls")) put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                }
            }
        outbound.put("tls", tls)
    }

    /** Adds a transport block; returns true when one was added (i.e. not raw TCP). */
    private fun applyTransport(
        outbound: JSONObject,
        l: Link,
    ): Boolean {
        val transport =
            when (l.param("type")?.lowercase()) {
                "ws" ->
                    JSONObject().apply {
                        put("type", "ws")
                        put("path", l.param("path") ?: "/")
                        l.param("host")?.let { put("headers", JSONObject().put("Host", it)) }
                    }
                "grpc" ->
                    JSONObject().apply {
                        put("type", "grpc")
                        put("service_name", l.param("serviceName") ?: l.param("servicename").orEmpty())
                    }
                "httpupgrade" ->
                    JSONObject().apply {
                        put("type", "httpupgrade")
                        put("path", l.param("path") ?: "/")
                        l.param("host")?.let { put("host", it) }
                    }
                "http", "h2" ->
                    JSONObject().apply {
                        put("type", "http")
                        put("path", l.param("path") ?: "/")
                        l.param("host")?.let { put("host", JSONArray().put(it)) }
                    }
                else -> null
            } ?: return false
        outbound.put("transport", transport)
        return true
    }

    // ── Config ──

    /** Domains that must stay on the local connection to keep working. */
    private val directDomainSuffixes =
        listOf(
            ".ru", ".рф", "yandex.com", "vk.com", "mail.ru", "ok.ru",
            "sberbank.ru", "gosuslugi.ru", "wildberries.ru", "ozon.ru",
            "avito.ru", "tinkoff.ru", "alfabank.ru", "vtb.ru",
        )

    private val directIpRanges =
        listOf(
            "5.45.192.0/18", "77.88.0.0/18", "87.240.128.0/18",
            "93.186.225.0/24", "95.142.192.0/20",
        )

    private val reservedTags = setOf(PROXY_TAG, AUTO_TAG, DIRECT_TAG, "block", "dns-out")

    /** Unique, readable tags — the app lists servers by these. */
    internal fun uniqueTags(servers: List<Server>): List<String> {
        val used = HashSet<String>()
        return servers.mapIndexed { i, server ->
            val base = server.name.trim().takeIf { it.isNotEmpty() && it !in reservedTags } ?: "server-${i + 1}"
            var tag = base
            var n = 2
            while (!used.add(tag)) {
                tag = "$base ($n)"
                n++
            }
            tag
        }
    }

    fun buildConfig(servers: List<Server>): String {
        require(servers.isNotEmpty()) { "no servers" }
        val tags = uniqueTags(servers)

        val outbounds = JSONArray()
        servers.forEachIndexed { i, server ->
            val ob = JSONObject(server.outbound.toString())
            ob.put("tag", tags[i])
            outbounds.put(ob)
        }
        outbounds.put(
            JSONObject().apply {
                put("type", "urltest")
                put("tag", AUTO_TAG)
                put("outbounds", JSONArray(tags))
                put("url", TEST_URL)
                // Relaxed interval: probing dozens of servers costs battery.
                put("interval", "10m")
                put("tolerance", 50)
            },
        )
        outbounds.put(
            JSONObject().apply {
                put("type", "selector")
                put("tag", PROXY_TAG)
                put("outbounds", JSONArray(listOf(AUTO_TAG) + tags))
                put("default", AUTO_TAG)
                put("interrupt_exist_connections", false)
            },
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))

        val config =
            JSONObject().apply {
                put("log", JSONObject().put("level", "warn").put("timestamp", true))

                put(
                    "dns",
                    JSONObject().apply {
                        put("final", "dns-remote")
                        put("independent_cache", true)
                        put(
                            "servers",
                            JSONArray().apply {
                                put(
                                    JSONObject()
                                        .put("tag", "dns-remote")
                                        .put("type", "https")
                                        .put("server", "1.1.1.1")
                                        .put("detour", PROXY_TAG)
                                        .put("domain_resolver", "dns-local"),
                                )
                                // No detour: sing-box refuses to start when a DNS
                                // server is pointed at a bare `direct` outbound.
                                put(
                                    JSONObject()
                                        .put("tag", "dns-local")
                                        .put("type", "udp")
                                        .put("server", "8.8.8.8"),
                                )
                            },
                        )
                    },
                )

                put(
                    "inbounds",
                    JSONArray().put(
                        JSONObject().apply {
                            put("type", "tun")
                            put("tag", "tun-in")
                            put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                            put("auto_route", true)
                            put("strict_route", false)
                            put("mtu", 9000)
                            put("stack", "system")
                        },
                    ),
                )

                put("outbounds", outbounds)

                put(
                    "route",
                    JSONObject().apply {
                        put("auto_detect_interface", true)
                        put("default_domain_resolver", "dns-local")
                        put("final", PROXY_TAG)
                        put(
                            "rules",
                            JSONArray().apply {
                                put(JSONObject().put("action", "sniff"))
                                put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
                                // Keep the local network local: Android's VPN captures
                                // every route, so without this the PC, router and
                                // printers were only reachable via the remote server.
                                put(JSONObject().put("ip_is_private", true).put("outbound", DIRECT_TAG))
                                put(
                                    JSONObject()
                                        .put("domain_suffix", JSONArray(directDomainSuffixes))
                                        .put("outbound", DIRECT_TAG),
                                )
                                put(
                                    JSONObject()
                                        .put("ip_cidr", JSONArray(directIpRanges))
                                        .put("outbound", DIRECT_TAG),
                                )
                            },
                        )
                    },
                )

                // No cache_file: it would restore the last runtime choice on start
                // and override the selector default that the server picker writes.
            }

        return config.toString(2)
    }

    // ── Base64 ──

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Lenient base64 decode (standard or URL-safe, padding optional) to UTF-8.
     * Returns null for anything that is not valid base64 text. Hand-rolled
     * because java.util.Base64 is missing below API 26 and android.util.Base64
     * does not run in JVM unit tests.
     */
    internal fun base64Decode(input: String): String? {
        val s = input.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.isEmpty() || s.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = B64.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.UTF_8)
        // Reject binary garbage that merely happened to be valid base64.
        return if (text.contains('�')) null else text
    }
}
