package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    companion object {
        const val TIMEOUT_MILLIS = 20_000

        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
        // Without a deadline a request to an unreachable address (e.g. a QR
        // pointing at the wrong network) hangs for minutes on Android and the
        // UI just spins.
        client.setTimeout(TIMEOUT_MILLIS)
    }

    fun getString(url: String): String = getString(url, userAgent)

    fun getString(url: String, userAgent: String): String {
        val request = client.newRequest()
        request.setUserAgent(userAgent)
        request.setURL(url)
        val response = request.execute()
        return response.content.unwrap
    }

    override fun close() {
        client.close()
    }
}
