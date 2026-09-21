package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox

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
        val native = HTTPClient().use { it.getString(url) }
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
}
