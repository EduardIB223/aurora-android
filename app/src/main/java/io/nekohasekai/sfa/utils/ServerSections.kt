package io.nekohasekai.sfa.utils

/**
 * The server list, cut into the sections the desktop shows. A profile shared
 * from Aurora lists, in its selector: "auto" (every server), servers of
 * groups that have no Auto, then per subscription a "⚡ <name>" urltest
 * followed by that subscription's servers. Each urltest opens a section.
 * Plain JSON logic, so it runs in JVM tests.
 */
object ServerSections {
    data class Section(
        /** Null for the leading "all servers" section. */
        val title: String?,
        /** The section's Auto entry (its urltest), if any. */
        val auto: ServerSelection.Entry?,
        val servers: List<ServerSelection.Entry>,
    )

    const val GROUP_AUTO_PREFIX = "⚡ "

    fun of(servers: ServerSelection.Servers): List<Section> {
        val sections = mutableListOf<Section>()
        var title: String? = null
        var auto: ServerSelection.Entry? = null
        var members = mutableListOf<ServerSelection.Entry>()

        fun flush() {
            if (auto != null || members.isNotEmpty()) sections += Section(title, auto, members)
        }

        for (entry in servers.entries) {
            if (entry.isAuto && entry.tag.startsWith(GROUP_AUTO_PREFIX)) {
                flush()
                title = entry.tag.removePrefix(GROUP_AUTO_PREFIX)
                auto = entry
                members = mutableListOf()
            } else if (entry.isAuto && auto == null && title == null) {
                auto = entry
            } else if (!entry.isAuto) {
                members += entry
            }
        }
        flush()
        return sections
    }

    /** Where traffic goes right now: the server, its last ping, and the Auto that picked it. */
    data class LiveRoute(val server: String, val delay: Int, val via: String?)

    /**
     * Follow the running selector down to a server: the selector points at a
     * server, or at an Auto (urltest) whose pick is the server. [groups] is
     * tag → (type, selected, member delays).
     */
    fun liveRoute(groups: List<GroupState>): LiveRoute? {
        val byTag = groups.associateBy { it.tag }
        var group = groups.firstOrNull { it.type == "selector" } ?: return null
        var via: String? = null
        repeat(4) {
            val next = byTag[group.selected]
            if (next == null) {
                val tag = group.selected.ifEmpty { return null }
                return LiveRoute(tag, group.delays[tag] ?: 0, via)
            }
            if (next.type == "urltest") via = next.tag
            group = next
        }
        return null
    }

    data class GroupState(
        val tag: String,
        val type: String,
        val selected: String,
        val delays: Map<String, Int>,
    )

    /**
     * A leading flag emoji (two regional-indicator symbols) split off the
     * name — shown large on the tile, like the desktop. Null flag when the
     * name has none.
     */
    fun splitFlag(name: String): Pair<String?, String> {
        if (name.isEmpty()) return null to name
        val first = name.codePointAt(0)
        if (first !in 0x1F1E6..0x1F1FF) return null to name
        val secondIndex = name.offsetByCodePoints(0, 1)
        if (secondIndex >= name.length) return null to name
        val second = name.codePointAt(secondIndex)
        if (second !in 0x1F1E6..0x1F1FF) return null to name
        val end = name.offsetByCodePoints(0, 2)
        return name.substring(0, end) to name.substring(end).trimStart()
    }
}
