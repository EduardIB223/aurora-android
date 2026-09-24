package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R

/**
 * Downloads a remote profile and makes sure the result can actually run.
 *
 * A remote URL may be a real sing-box profile, or a panel subscription that
 * answers with a base64 list of share links. Worse, panels that do serve
 * sing-box JSON often use options sing-box 1.13 refuses to start with (legacy
 * DNS fake-ip and friends), so the profile imports fine and then "just doesn't
 * work". Only a config that passes [Libbox.checkConfig] is accepted as-is;
 * anything else is re-fetched as a link list and rebuilt with
 * [ProxyLinkParser], which always emits a config the bundled core accepts.
 */
object RemoteProfileLoader {
    /** Panels shape their answer on the client; this one gets plain share links. */
    private const val LINK_LIST_USER_AGENT = "v2rayN/6.42 (Aurora)"

    class Result(
        val content: String,
        /** True when the profile was rebuilt from share links. */
        val convertedFromLinks: Boolean,
        val serverCount: Int,
    )

    fun fetch(url: String): Result {
        // Prefer building the profile from the provider's full server list
        // (Xray configs or share links): it keeps servers a sing-box profile
        // can't express (XHTTP whitelist bypass), and adds Aurora's RU
        // routing and sections. Liberty serves 29 servers as sing-box JSON
        // but ~42 (with the bypass group) this way.
        if (!isLanUrl(url)) {
            val full = runCatching { HTTPClient().use { it.getString(url, LINK_LIST_USER_AGENT) } }.getOrNull()
            full?.let(ProxyLinkParser::parse)?.let { parsed ->
                if (runCatching { Libbox.checkConfig(parsed.config) }.isSuccess) {
                    return Result(parsed.config, convertedFromLinks = true, serverCount = parsed.serverCount)
                }
            }
        }
        val native =
            try {
                HTTPClient().use { it.getString(url) }
            } catch (e: Exception) {
                val raw = e.message ?: e.toString()
                val message =
                    unreachableLanHost(url, raw)?.let { host ->
                        Application.application.getString(R.string.error_lan_unreachable, host) + "\n\n" + raw
                    } ?: raw
                throw IllegalStateException(message, e)
            }
        val nativeError = runCatching { Libbox.checkConfig(native) }.exceptionOrNull()
        if (nativeError == null) {
            return Result(native, convertedFromLinks = false, serverCount = 0)
        }

        // The native answer may itself be a link list (base64 or plain).
        ProxyLinkParser.parse(native)?.let { parsed ->
            return Result(parsed.config, convertedFromLinks = true, serverCount = parsed.serverCount)
        }

        // Ask again the way link-based clients do.
        val links = runCatching { HTTPClient().use { it.getString(url, LINK_LIST_USER_AGENT) } }.getOrNull()
        links?.let(ProxyLinkParser::parse)?.let { parsed ->
            Libbox.checkConfig(parsed.config)
            return Result(parsed.config, convertedFromLinks = true, serverCount = parsed.serverCount)
        }

        // Nothing usable: surface the original reason, which is the most specific.
        throw IllegalStateException(nativeError.message ?: "Unsupported profile format", nativeError)
    }

    /**
     * The host, when [message] says a private/LAN address could not be
     * reached — almost always a QR from a PC on another network, or pointing
     * at a VPN address the phone cannot reach. Null for anything else.
     */
    internal fun unreachableLanHost(url: String, message: String): String? {
        val host = hostOf(url)
        val lanHost = isLanUrl(url)
        val stalled =
            listOf("timeout", "deadline", "eof", "refused", "unreachable", "no route")
                .any { message.contains(it, ignoreCase = true) }
        return host.takeIf { lanHost && stalled }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')

    /**
     * A URL on the local network — e.g. a profile served by Aurora on a PC.
     * Such links are temporary, so auto-update must default to off for them.
     */
    fun isLanUrl(url: String): Boolean {
        val host = hostOf(url)
        return host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host) ||
            host.startsWith("198.18.") || host.startsWith("198.19.")
    }
}
