package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSectionsTest {
    /** The selector of a profile shared from the desktop: Auto, then a "⚡" Auto + servers per subscription. */
    private val shared =
        """
        {"outbounds":[
          {"type":"vless","tag":"Home"},
          {"type":"vless","tag":"🇳🇱 Amsterdam"},
          {"type":"vless","tag":"🇩🇪 Berlin"},
          {"type":"xray","tag":"🇷🇺 BS-5"},
          {"type":"urltest","tag":"auto","outbounds":["🇳🇱 Amsterdam","🇩🇪 Berlin","🇷🇺 BS-5"]},
          {"type":"urltest","tag":"⚡ Liberty","outbounds":["🇳🇱 Amsterdam","🇩🇪 Berlin"]},
          {"type":"urltest","tag":"⚡ Обход белых списков","outbounds":["🇷🇺 BS-5"]},
          {"type":"selector","tag":"proxy","outbounds":[
            "auto","Home","⚡ Liberty","🇳🇱 Amsterdam","🇩🇪 Berlin","⚡ Обход белых списков","🇷🇺 BS-5"
          ]}
        ]}
        """.trimIndent()

    @Test
    fun aSectionPerSubscriptionWithItsOwnAuto() {
        val sections = ServerSections.of(ServerSelection.read(shared)!!)
        assertEquals(listOf(null, "Liberty", "Обход белых списков"), sections.map { it.title })
        assertEquals("auto", sections[0].auto?.tag)
        assertEquals(listOf("Home"), sections[0].servers.map { it.tag })
        assertEquals("⚡ Liberty", sections[1].auto?.tag)
        assertEquals(listOf("🇳🇱 Amsterdam", "🇩🇪 Berlin"), sections[1].servers.map { it.tag })
        assertEquals(listOf("xray"), sections[2].servers.map { it.type })
    }

    @Test
    fun aPlainProfileIsOneSection() {
        val config = ProxyLinkParser.buildConfig(
            ProxyLinkParser.parseAll(
                "vless://11111111-1111-1111-1111-111111111111@a.example.com:443?security=tls#A\n" +
                    "vless://11111111-1111-1111-1111-111111111111@b.example.com:443?security=tls#B",
            ),
        )
        val sections = ServerSections.of(ServerSelection.read(config)!!)
        assertEquals(1, sections.size)
        assertNull(sections[0].title)
        assertEquals("auto", sections[0].auto?.tag)
        assertEquals(listOf("A", "B"), sections[0].servers.map { it.tag })
    }

    @Test
    fun flagIsSplitOffTheName() {
        assertEquals("🇩🇪" to "Berlin", ServerSections.splitFlag("🇩🇪 Berlin"))
        assertEquals(null to "Berlin", ServerSections.splitFlag("Berlin"))
        assertEquals(null to "", ServerSections.splitFlag(""))
    }

    private fun group(tag: String, type: String, selected: String, vararg delays: Pair<String, Int>) =
        ServerSections.GroupState(tag, type, selected, delays.toMap())

    @Test
    fun liveRouteFollowsTheSelectorThroughAuto() {
        val groups = listOf(
            group("auto", "urltest", "🇩🇪 Berlin", "🇳🇱 Amsterdam" to 90, "🇩🇪 Berlin" to 40),
            group("⚡ Liberty", "urltest", "🇳🇱 Amsterdam", "🇳🇱 Amsterdam" to 90),
            group("proxy", "selector", "⚡ Liberty", "⚡ Liberty" to 90),
        )
        assertEquals(ServerSections.LiveRoute("🇳🇱 Amsterdam", 90, "⚡ Liberty"), ServerSections.liveRoute(groups))

        val manual = groups.dropLast(1) + group("proxy", "selector", "🇩🇪 Berlin", "🇩🇪 Berlin" to 40)
        assertEquals(ServerSections.LiveRoute("🇩🇪 Berlin", 40, null), ServerSections.liveRoute(manual))

        assertNull(ServerSections.liveRoute(emptyList()))
    }

    @Test
    fun serverDelaysSkipGroupsAndKeepTheBestKnown() {
        val groups = listOf(
            group("auto", "urltest", "A", "A" to 120, "B" to 0),
            group("⚡ Liberty", "urltest", "A", "A" to 130),
            group("proxy", "selector", "auto", "auto" to 120, "⚡ Liberty" to 130, "A" to 125, "B" to 0),
        )
        val delays = ServerSections.serverDelays(groups)
        assertEquals(130, delays["A"])
        assertEquals(0, delays["B"])
        assertNull("groups are not servers", delays["auto"])
        assertNull(delays["⚡ Liberty"])
    }
}
