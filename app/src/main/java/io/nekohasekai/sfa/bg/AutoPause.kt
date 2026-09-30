package io.nekohasekai.sfa.bg

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.RuApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Aurora: turns the VPN off while VK, Ozon or a bank is on screen.
 *
 * Those apps look for a VPN at the system level: the tunnel interface and the
 * VPN network are visible to every app, including the ones excluded from the
 * tunnel, so excluding them is not enough. While one of them is in front the
 * tunnel is closed, and it comes back once the user leaves it.
 *
 * The app in front comes from usage events, which needs the user to grant
 * "Usage access" to Aurora.
 */
class AutoPause(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPause: suspend (packageName: String) -> Unit,
    private val onResume: suspend () -> Unit,
) {
    companion object {
        private const val POLL_MS = 500L
        private const val SCREEN_OFF_POLL_MS = 2_000L
        private const val IDLE_POLL_MS = 5_000L
        private const val SETTINGS_EVERY_MS = 5_000L

        /** Leaving a watched app for less than this doesn't bring the VPN back. */
        const val RESUME_AFTER_MS = 1_500L

        /** How far back to look for the app already in front when watching starts. */
        private const val LOOKBACK_MS = 10 * 60_000L

        // Both checks are deprecated on some API level; each is right on its own.
        @Suppress("DEPRECATION")
        fun hasPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
                } else {
                    appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
                }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** The Russian apps, plus whatever the user sends around the VPN. */
        fun watchedPackages(): Set<String> {
            val own =
                if (Settings.perAppProxyEnabled &&
                    Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_EXCLUDE
                ) {
                    Settings.getEffectivePerAppProxyList()
                } else {
                    emptySet()
                }
            return RuApps.PACKAGES.toSet() + own
        }
    }

    private var job: Job? = null

    /** Whether the tunnel is closed right now because of a watched app. */
    @Volatile
    var paused = false
        private set

    fun start() {
        if (job != null) return
        job = scope.launch { loop() }
    }

    /** Stops watching and waits for a pause or resume in progress. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        paused = false
    }

    /** Stops watching without waiting: safe from inside [onPause] / [onResume]. */
    fun cancel() {
        job?.cancel()
        job = null
        paused = false
    }

    private suspend fun loop() {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var decider: PauseDecider? = null
        var settingsCheckedAt = 0L
        var eventsUntil = 0L
        var foreground: String? = null

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now - settingsCheckedAt >= SETTINGS_EVERY_MS) {
                settingsCheckedAt = now
                val enabled = Settings.autoPauseEnabled && hasPermission(context) && !lockdown()
                decider =
                    if (enabled) {
                        (decider ?: PauseDecider(emptySet(), RESUME_AFTER_MS)).also {
                            it.watched = watchedPackages()
                        }
                    } else {
                        null
                    }
                if (decider == null) {
                    if (paused) setPaused(null)
                    eventsUntil = 0L
                    foreground = null
                }
            }
            val current = decider
            if (current == null) {
                delay(IDLE_POLL_MS)
                continue
            }

            if (!power.isInteractive) {
                // The screen is off: nothing is really in front, and apps in
                // the background (messengers) need the VPN.
                foreground = null
                apply(current.onForeground(null, now))
                eventsUntil = now
                delay(SCREEN_OFF_POLL_MS)
                continue
            }

            val from = if (eventsUntil == 0L) now - LOOKBACK_MS else eventsUntil
            foreground = latestForeground(usage, from, now, foreground)
            eventsUntil = now
            apply(current.onForeground(foreground, now))
            delay(POLL_MS)
        }
    }

    /**
     * "Block connections without VPN" is on: closing the tunnel would cut the
     * phone off entirely, VK and Ozon included, so don't pause.
     */
    private fun lockdown(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (context as? android.net.VpnService)?.isLockdownEnabled == true

    private suspend fun apply(action: PauseDecider.Action?) {
        when (action) {
            is PauseDecider.Action.Pause -> setPaused(action.packageName)
            PauseDecider.Action.Resume -> setPaused(null)
            null -> {}
        }
    }

    private suspend fun setPaused(packageName: String?) {
        if (packageName != null) {
            paused = true
            onPause(packageName)
        } else {
            paused = false
            onResume()
        }
    }

    /** Replays the usage events since [from] on top of what was in front before. */
    @Suppress("DEPRECATION")
    private fun latestForeground(usage: UsageStatsManager, from: Long, to: Long, before: String?): String? {
        val events = runCatching { usage.queryEvents(from, to) }.getOrNull() ?: return before
        var foreground = before
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> foreground = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    if (event.packageName == foreground) foreground = null
            }
        }
        return foreground
    }
}

/**
 * When to pause and resume, given the app in front. A watched app pauses at
 * once, since the app checks for a VPN as it opens; the VPN comes back only
 * after [resumeAfterMs] away from watched apps, so moving between screens of
 * the same app, or a system dialog over it, doesn't flap the tunnel.
 */
class PauseDecider(var watched: Set<String>, private val resumeAfterMs: Long) {
    sealed class Action {
        data class Pause(val packageName: String) : Action()
        data object Resume : Action()
    }

    var paused = false
        private set
    private var leftAt: Long? = null

    fun onForeground(packageName: String?, now: Long): Action? {
        if (packageName != null && packageName in watched) {
            leftAt = null
            if (paused) return null
            paused = true
            return Action.Pause(packageName)
        }
        if (!paused) return null
        val since = leftAt ?: now.also { leftAt = it }
        if (now - since < resumeAfterMs) return null
        paused = false
        leftAt = null
        return Action.Resume
    }
}
