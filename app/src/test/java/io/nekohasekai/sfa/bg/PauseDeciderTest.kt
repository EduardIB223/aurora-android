package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseDeciderTest {
    private val ozon = "ru.ozon.app.android"
    private val vk = "com.vkontakte.android"
    private fun decider() = PauseDecider(setOf(ozon, vk), resumeAfterMs = 1_500)

    @Test
    fun opening_a_watched_app_pauses_at_once() {
        val d = decider()
        assertNull(d.onForeground("org.telegram.messenger", 0))
        assertEquals(PauseDecider.Action.Pause(ozon), d.onForeground(ozon, 500))
        assertNull(d.onForeground(ozon, 1_000))
    }

    @Test
    fun leaving_it_resumes_only_after_a_while_away() {
        val d = decider()
        d.onForeground(ozon, 0)
        assertNull(d.onForeground("com.android.launcher", 1_000))
        assertNull(d.onForeground("com.android.launcher", 2_000))
        assertEquals(PauseDecider.Action.Resume, d.onForeground("com.android.launcher", 2_500))
        assertNull(d.onForeground("com.android.launcher", 3_000))
    }

    @Test
    fun a_short_dialog_over_the_app_does_not_flap_the_vpn() {
        val d = decider()
        d.onForeground(ozon, 0)
        assertNull(d.onForeground("com.google.android.permissioncontroller", 500))
        assertNull(d.onForeground(null, 1_000))
        assertNull(d.onForeground(ozon, 1_500))
        // Away again: the wait starts over
        assertNull(d.onForeground(null, 2_000))
        assertNull(d.onForeground(null, 3_000))
        assertEquals(PauseDecider.Action.Resume, d.onForeground(null, 3_500))
    }

    @Test
    fun going_from_one_watched_app_to_another_stays_paused() {
        val d = decider()
        d.onForeground(ozon, 0)
        assertNull(d.onForeground(vk, 500))
        assertNull(d.onForeground(vk, 5_000))
        assertEquals(true, d.paused)
    }

    @Test
    fun a_new_watched_list_applies_right_away() {
        val d = decider()
        d.watched = setOf("ru.sberbankmobile")
        assertNull(d.onForeground(ozon, 0))
        assertEquals(PauseDecider.Action.Pause("ru.sberbankmobile"), d.onForeground("ru.sberbankmobile", 500))
    }
}
