package io.nekohasekai.sfa.utils

import org.json.JSONObject

/**
 * The server list of a profile, as the user sees it: the members of its main
 * `selector` group — "Auto" plus every server — and which one is chosen.
 *
 * Choosing works with the VPN off too: the choice is written into the
 * selector's `default`, so the next start uses it. JSON-only on purpose, so it
 * runs in plain JVM tests; the Android side (preferences, the running core)
 * lives in [ServerSelectionStore].
 */
object ServerSelection {
    data class Entry(
        val tag: String,
        /** The latency-tested group ("Auto — fastest") rather than a server. */
        val isAuto: Boolean,
        /** Outbound type, e.g. "vless"; shown under the name. */
        val type: String,
    )

    data class Servers(
        val selectorTag: String,
        val entries: List<Entry>,
        /** The selector's configured default, if any. */
        val default: String?,
    ) {
        /** Only real servers (what "Ping all" measures). */
        val serverCount: Int get() = entries.count { !it.isAuto }
    }

    /**
     * The selectable servers of [config], or null when the profile has no
     * selector (a hand-written config, or one server without groups).
     */
    fun read(config: String): Servers? {
        val root = runCatching { JSONObject(config) }.getOrNull() ?: return null
        val outbounds = root.optJSONArray("outbounds") ?: return null
        val all = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
        val selector =
            all.firstOrNull { it.optString("type") == "selector" && it.optString("tag") == ProxyLinkParser.PROXY_TAG }
                ?: all.firstOrNull { it.optString("type") == "selector" }
                ?: return null

        val typeOf = all.associate { it.optString("tag") to it.optString("type") }
        val members = selector.optJSONArray("outbounds") ?: return null
        val entries =
            (0 until members.length()).mapNotNull { i ->
                val tag = members.optString(i).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val type = typeOf[tag].orEmpty()
                Entry(tag = tag, isAuto = type == "urltest", type = type)
            }
        if (entries.isEmpty()) return null

        return Servers(
            selectorTag = selector.optString("tag"),
            entries = entries,
            default = selector.optString("default").takeIf { it.isNotEmpty() },
        )
    }

    /** The choice to show: the stored one if it still exists, else the config's. */
    fun current(servers: Servers, stored: String?): String? {
        val tags = servers.entries.map { it.tag }
        return stored?.takeIf { it in tags }
            ?: servers.default?.takeIf { it in tags }
            ?: tags.firstOrNull()
    }

    /**
     * [config] with [tag] as the selector's default. Returns [config] unchanged
     * when the selector or the tag is missing, so a stale choice never breaks
     * a profile.
     */
    fun withDefault(config: String, selectorTag: String, tag: String): String {
        val root = runCatching { JSONObject(config) }.getOrNull() ?: return config
        val outbounds = root.optJSONArray("outbounds") ?: return config
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.optString("type") != "selector" || ob.optString("tag") != selectorTag) continue
            val members = ob.optJSONArray("outbounds") ?: return config
            val isMember = (0 until members.length()).any { members.optString(it) == tag }
            if (!isMember) return config
            if (ob.optString("default") == tag) return config
            ob.put("default", tag)
            return root.toString(2)
        }
        return config
    }

    /**
     * Re-applies a stored choice to freshly downloaded content, so updating a
     * subscription neither loses the chosen server nor looks like a change
     * (which would reload the VPN on every update).
     */
    fun applyStored(content: String, stored: String?): String {
        if (stored.isNullOrEmpty()) return content
        val servers = read(content) ?: return content
        return withDefault(content, servers.selectorTag, stored)
    }
}
