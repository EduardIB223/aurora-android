package io.nekohasekai.sfa.utils

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts share links (vless://, vmess://, trojan://, ss://) into a complete
 * sing-box configuration.
 *
 * sing-box itself only understands its own profile format, so links copied from
 * a panel or scanned from a QR code cannot be imported as-is. This builds a
 * ready-to-run config around the parsed outbound: TUN inbound, DNS through the
 * proxy, and routing that keeps Russian domains and address ranges off the
 * tunnel so banking and government apps keep working on a local IP.
 */
object ProxyLinkParser {
    private const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"

    data class ParsedLink(
        val name: String,
        val config: String,
    )

    private val schemes = listOf("vless://", "vmess://", "trojan://", "ss://")

    fun isProxyLink(data: String): Boolean {
        val trimmed = data.trim()
        return schemes.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    /** Parses one or more links (newline separated); the first valid one wins. */
    fun parse(data: String): ParsedLink? {
        for (line in data.trim().lines()) {
            val link = line.trim()
            if (link.isEmpty()) continue
            val outbound =
                runCatching {
                    when {
                        link.startsWith("vless://", true) -> parseVless(link)
                        link.startsWith("trojan://", true) -> parseTrojan(link)
                        link.startsWith("vmess://", true) -> parseVmess(link)
                        link.startsWith("ss://", true) -> parseShadowsocks(link)
                        else -> null
                    }
                }.getOrNull() ?: continue

            val name = outbound.remove("__name__") as? String
            return ParsedLink(
                name = name?.takeIf { it.isNotBlank() } ?: outbound.optString("server"),
                config = buildConfig(outbound),
            )
        }
        return null
    }

    private fun fragmentName(uri: Uri): String? = uri.fragment?.let { Uri.decode(it) }

    private fun parseVless(link: String): JSONObject {
        val uri = Uri.parse(link)
        val uuid = uri.userInfo ?: error("missing uuid")
        val host = uri.host ?: error("missing host")
        val port = uri.port.takeIf { it > 0 } ?: error("missing port")

        val outbound =
            JSONObject().apply {
                put("type", "vless")
                put("tag", PROXY_TAG)
                put("server", host)
                put("server_port", port)
                put("uuid", Uri.decode(uuid))
                uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            }

        applyTls(outbound, uri, defaultSni = host)
        applyTransport(outbound, uri)
        outbound.put("__name__", fragmentName(uri))
        return outbound
    }

    private fun parseTrojan(link: String): JSONObject {
        val uri = Uri.parse(link)
        val password = uri.userInfo ?: error("missing password")
        val host = uri.host ?: error("missing host")
        val port = uri.port.takeIf { it > 0 } ?: error("missing port")

        val outbound =
            JSONObject().apply {
                put("type", "trojan")
                put("tag", PROXY_TAG)
                put("server", host)
                put("server_port", port)
                put("password", Uri.decode(password))
            }

        applyTls(outbound, uri, defaultSni = host, tlsByDefault = true)
        applyTransport(outbound, uri)
        outbound.put("__name__", fragmentName(uri))
        return outbound
    }

    private fun parseVmess(link: String): JSONObject {
        val payload = link.removePrefix("vmess://").removePrefix("VMESS://").trim()
        val json = JSONObject(String(Base64.decode(payload, Base64.DEFAULT or Base64.URL_SAFE)))

        val host = json.optString("add").ifBlank { error("missing host") }
        val port = json.optInt("port").takeIf { it > 0 } ?: error("missing port")

        val outbound =
            JSONObject().apply {
                put("type", "vmess")
                put("tag", PROXY_TAG)
                put("server", host)
                put("server_port", port)
                put("uuid", json.optString("id"))
                put("alter_id", json.optInt("aid", 0))
                json.optString("scy").takeIf { it.isNotBlank() }?.let { put("security", it) }
            }

        if (json.optString("tls").equals("tls", true)) {
            outbound.put(
                "tls",
                JSONObject().apply {
                    put("enabled", true)
                    val sni = json.optString("sni").ifBlank { json.optString("host").ifBlank { host } }
                    put("server_name", sni)
                    json.optString("fp").takeIf { it.isNotBlank() }?.let { fp ->
                        put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                    }
                },
            )
        }

        val network = json.optString("net")
        if (network == "ws" || network == "grpc" || network == "httpupgrade") {
            outbound.put(
                "transport",
                JSONObject().apply {
                    when (network) {
                        "grpc" -> {
                            put("type", "grpc")
                            put("service_name", json.optString("path"))
                        }
                        else -> {
                            put("type", if (network == "ws") "ws" else "httpupgrade")
                            json.optString("path").takeIf { it.isNotBlank() }?.let { put("path", it) }
                            json.optString("host").takeIf { it.isNotBlank() }?.let {
                                put("headers", JSONObject().put("Host", it))
                            }
                        }
                    }
                },
            )
        }

        outbound.put("__name__", json.optString("ps"))
        return outbound
    }

    private fun parseShadowsocks(link: String): JSONObject {
        val withoutScheme = link.removePrefix("ss://").removePrefix("SS://")
        val fragment = withoutScheme.substringAfter('#', "").takeIf { it.isNotBlank() }
        val body = withoutScheme.substringBefore('#').substringBefore('?')

        val method: String
        val password: String
        val host: String
        val port: Int

        if (body.contains('@')) {
            // ss://base64(method:password)@host:port
            val userInfo = body.substringBefore('@')
            val server = body.substringAfter('@')
            val decoded =
                runCatching {
                    String(Base64.decode(userInfo, Base64.DEFAULT or Base64.URL_SAFE))
                }.getOrElse { Uri.decode(userInfo) }
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':')
            host = server.substringBeforeLast(':')
            port = server.substringAfterLast(':').toIntOrNull() ?: error("missing port")
        } else {
            // ss://base64(method:password@host:port)
            val decoded = String(Base64.decode(body, Base64.DEFAULT or Base64.URL_SAFE))
            method = decoded.substringBefore(':')
            val rest = decoded.substringAfter(':')
            password = rest.substringBeforeLast('@')
            val server = rest.substringAfterLast('@')
            host = server.substringBeforeLast(':')
            port = server.substringAfterLast(':').toIntOrNull() ?: error("missing port")
        }

        return JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", PROXY_TAG)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
            put("__name__", fragment?.let { Uri.decode(it) })
        }
    }

    private fun applyTls(
        outbound: JSONObject,
        uri: Uri,
        defaultSni: String,
        tlsByDefault: Boolean = false,
    ) {
        val security = uri.getQueryParameter("security")?.lowercase()
        val enabled = tlsByDefault || security == "tls" || security == "reality" || security == "xtls"
        if (!enabled) return

        val tls =
            JSONObject().apply {
                put("enabled", true)
                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: defaultSni
                put("server_name", sni)
                uri.getQueryParameter("alpn")?.takeIf { it.isNotBlank() }?.let { alpn ->
                    put("alpn", JSONArray(alpn.split(",").map { it.trim() }))
                }
                uri.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let { fp ->
                    put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
                }
                if (security == "reality") {
                    put(
                        "reality",
                        JSONObject().apply {
                            put("enabled", true)
                            put("public_key", uri.getQueryParameter("pbk").orEmpty())
                            uri.getQueryParameter("sid")?.takeIf { it.isNotBlank() }?.let {
                                put("short_id", it)
                            }
                        },
                    )
                }
            }
        outbound.put("tls", tls)
    }

    private fun applyTransport(
        outbound: JSONObject,
        uri: Uri,
    ) {
        when (uri.getQueryParameter("type")?.lowercase()) {
            "ws" ->
                outbound.put(
                    "transport",
                    JSONObject().apply {
                        put("type", "ws")
                        uri.getQueryParameter("path")?.takeIf { it.isNotBlank() }?.let {
                            put("path", Uri.decode(it))
                        }
                        uri.getQueryParameter("host")?.takeIf { it.isNotBlank() }?.let {
                            put("headers", JSONObject().put("Host", it))
                        }
                    },
                )

            "grpc" ->
                outbound.put(
                    "transport",
                    JSONObject().apply {
                        put("type", "grpc")
                        uri.getQueryParameter("serviceName")?.takeIf { it.isNotBlank() }?.let {
                            put("service_name", Uri.decode(it))
                        }
                    },
                )

            "httpupgrade" ->
                outbound.put(
                    "transport",
                    JSONObject().apply {
                        put("type", "httpupgrade")
                        uri.getQueryParameter("path")?.takeIf { it.isNotBlank() }?.let {
                            put("path", Uri.decode(it))
                        }
                        uri.getQueryParameter("host")?.takeIf { it.isNotBlank() }?.let {
                            put("host", it)
                        }
                    },
                )
        }
    }

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

    private fun buildConfig(outbound: JSONObject): String {
        outbound.remove("__name__")

        val config =
            JSONObject().apply {
                put("log", JSONObject().put("level", "warn").put("timestamp", true))

                put(
                    "dns",
                    JSONObject().apply {
                        put("final", "dns-remote")
                        put("independent_cache", true)
                        put("reverse_mapping", true)
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
                            put("address", JSONArray().put("198.18.0.1/16"))
                            put("auto_route", true)
                            put("strict_route", false)
                            put("mtu", 1500)
                            put("stack", "system")
                        },
                    ),
                )

                put(
                    "outbounds",
                    JSONArray()
                        .put(outbound)
                        .put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG)),
                )

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
            }

        return config.toString(2)
    }
}
