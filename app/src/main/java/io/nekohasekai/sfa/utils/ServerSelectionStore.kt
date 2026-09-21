package io.nekohasekai.sfa.utils

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Remembers which server the user picked for each profile and applies it —
 * to the running core right away, and to the profile file so the next start
 * uses it even with the VPN off. See [ServerSelection] for the JSON side.
 */
object ServerSelectionStore {
    private const val TAG = "ServerSelection"
    private const val PREFS = "aurora_server_selection"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stored(context: Context, profileId: Long): String? =
        prefs(context).getString("p$profileId", null)

    /**
     * Makes [tag] the server of [profile]. With the VPN running the switch is
     * immediate and open connections are closed, so apps like Telegram
     * reconnect through the new server instead of sticking to the old one.
     */
    suspend fun select(
        context: Context,
        profile: Profile,
        selectorTag: String,
        tag: String,
        vpnRunning: Boolean,
    ) = withContext(Dispatchers.IO) {
        prefs(context).edit().putString("p${profile.id}", tag).apply()

        val file = File(profile.typed.path)
        val current = file.readText()
        val updated = ServerSelection.withDefault(current, selectorTag, tag)
        if (updated != current) file.writeText(updated)

        if (vpnRunning) {
            val client = Libbox.newStandaloneCommandClient()
            client.selectOutbound(selectorTag, tag)
            runCatching { client.closeConnections() }
        }
    }

    /**
     * Applies the stored choice once the VPN has started. Needed for profiles
     * imported by older versions, whose cache file restores an earlier choice
     * over the selector default. The command server may take a moment to come
     * up, hence the retries.
     */
    suspend fun applyAfterStart(context: Context, profile: Profile) = withContext(Dispatchers.IO) {
        val stored = stored(context, profile.id) ?: return@withContext
        val servers =
            runCatching { ServerSelection.read(File(profile.typed.path).readText()) }.getOrNull()
                ?: return@withContext
        if (servers.entries.none { it.tag == stored }) return@withContext

        repeat(5) { attempt ->
            val ok =
                runCatching {
                    Libbox.newStandaloneCommandClient().selectOutbound(servers.selectorTag, stored)
                }.onFailure { Log.d(TAG, "select after start, attempt ${attempt + 1}", it) }.isSuccess
            if (ok) return@withContext
            delay(400)
        }
    }
}
