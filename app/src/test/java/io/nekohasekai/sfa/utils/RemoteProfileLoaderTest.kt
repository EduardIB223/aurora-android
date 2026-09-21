package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProfileLoaderTest {
    @Test
    fun linkFromAPcOnTheLanIsRecognised() {
        assertTrue(RemoteProfileLoader.isLanUrl("http://192.168.0.103:50535/abc.json"))
        assertTrue(RemoteProfileLoader.isLanUrl("http://10.0.0.5:1234/abc.json"))
        assertTrue(RemoteProfileLoader.isLanUrl("http://172.20.1.2:1234/abc.json"))
        // The VPN address the desktop used to hand out by mistake.
        assertTrue(RemoteProfileLoader.isLanUrl("http://198.18.0.1:52073/abc.json"))
    }

    @Test
    fun publicSubscriptionsAreNotLan() {
        assertFalse(RemoteProfileLoader.isLanUrl("https://panel.example.com/sub/abc123"))
        assertFalse(RemoteProfileLoader.isLanUrl("https://172.217.0.1/sub")) // outside 172.16/12
        assertFalse(RemoteProfileLoader.isLanUrl("https://example.com/192.168.0.1"))
    }

    /** The exact error the phone showed: EOF against the PC's VPN address. */
    @Test
    fun eofAgainstALanAddressGetsAnActionableExplanation() {
        val url = "http://198.18.0.1:52073/b96b2106d53c4c5a900bff2888bee957.json"
        assertEquals(
            "198.18.0.1",
            RemoteProfileLoader.unreachableLanHost(url, "Get \"$url\": EOF"),
        )
        assertEquals(
            "192.168.0.103",
            RemoteProfileLoader.unreachableLanHost(
                "http://192.168.0.103:5000/x.json",
                "context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
            ),
        )
    }

    @Test
    fun otherFailuresKeepTheirOwnMessage() {
        // Reachable computer, wrong path: not a network problem.
        assertNull(RemoteProfileLoader.unreachableLanHost("http://192.168.0.103:5000/x.json", "HTTP 404 Not Found"))
        // Internet subscription timing out is not about the LAN.
        assertNull(RemoteProfileLoader.unreachableLanHost("https://panel.example.com/sub/abc", "i/o timeout"))
    }
}
