package io.nekohasekai.sfa.utils

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSelectionTest {
    private val uuid = "11111111-1111-1111-1111-111111111111"

    /** Same shape as the profile "Send to phone" produces: 4 servers + auto. */
    private val config =
        ProxyLinkParser.parse(
            listOf("user8", "fgd", "NL-1 | 1 Gb/s", "FLAB").mapIndexed { i, name ->
                "vless://$uuid@s$i.example.com:443?security=tls#${name.replace(" ", "%20").replace("|", "%7C")}"
            }.joinToString("\n"),
        )!!.config

    private fun defaultOf(cfg: String): String {
        val outbounds = JSONObject(cfg).getJSONArray("outbounds")
        return (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .first { it.getString("type") == "selector" }.getString("default")
    }

    @Test
    fun listsAutoPlusEveryServer() {
        val servers = ServerSelection.read(config)
        assertNotNull(servers)
        servers!!
        assertEquals(ProxyLinkParser.PROXY_TAG, servers.selectorTag)
        assertEquals(listOf("auto", "user8", "fgd", "NL-1 | 1 Gb/s", "FLAB"), servers.entries.map { it.tag })
        assertTrue(servers.entries.first().isAuto)
        assertEquals(4, servers.serverCount)
        assertEquals("vless", servers.entries[1].type)
    }

    @Test
    fun choosingAServerMakesItTheDefaultForTheNextStart() {
        val servers = ServerSelection.read(config)!!
        val updated = ServerSelection.withDefault(config, servers.selectorTag, "user8")
        assertEquals("user8", defaultOf(updated))
        // Nothing else about the profile changes.
        assertEquals(servers.entries, ServerSelection.read(updated)!!.entries)
    }

    @Test
    fun unknownChoiceLeavesTheProfileUntouched() {
        assertSame(config, ServerSelection.withDefault(config, "proxy", "no-such-server"))
        assertSame(config, ServerSelection.withDefault(config, "no-such-group", "user8"))
    }

    @Test
    fun currentPrefersStoredChoiceThenDefault() {
        val servers = ServerSelection.read(config)!!
        assertEquals("fgd", ServerSelection.current(servers, "fgd"))
        // A server removed from the subscription falls back to the default.
        assertEquals("auto", ServerSelection.current(servers, "gone"))
        assertEquals("auto", ServerSelection.current(servers, null))
    }

    /**
     * A subscription update must keep the chosen server — and re-applying the
     * choice to identical content must produce identical text, otherwise every
     * update would look like a change and reload the VPN.
     */
    @Test
    fun subscriptionUpdateKeepsTheChoiceAndIsStable() {
        val onDisk = ServerSelection.applyStored(config, "user8")
        assertEquals("user8", defaultOf(onDisk))
        val nextUpdate = ServerSelection.applyStored(config, "user8")
        assertEquals(onDisk, nextUpdate)
    }

    @Test
    fun profilesWithoutASelectorHaveNoList() {
        assertNull(ServerSelection.read("{}"))
        assertNull(ServerSelection.read("""{"outbounds":[{"type":"vless","tag":"proxy"}]}"""))
        assertNull(ServerSelection.read("not json"))
        assertSame("{}", ServerSelection.applyStored("{}", "user8"))
    }
}
