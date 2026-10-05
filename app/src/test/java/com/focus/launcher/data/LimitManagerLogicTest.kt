package com.focus.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure limit decision ([resolveLimit]) and the weekly-tally sum ([LimitStats.plus]). */
class LimitManagerLogicTest {
    private val base = Settings()   // timers on, social 30, game 30, video 0 by default

    private fun resolve(
        pkg: String = "x",
        s: Settings = base,
        canLimit: Boolean = true,
        category: AppCategory = AppCategory.SOCIAL,
    ) = resolveLimit(pkg, s, canLimit) { category }

    @Test
    fun `timers off means no limit at all`() {
        assertNull(resolve(s = base.copy(timersEnabled = false)))
    }

    @Test
    fun `a protected app is never limited`() {
        assertNull(resolve(canLimit = false))
    }

    @Test
    fun `an app-specific limit wins and skips classification`() {
        val limit = resolveLimit("x", base.copy(appLimits = mapOf("x" to 45)), canLimit = true) {
            throw AssertionError("category must not be read when the app has its own limit")
        }
        assertEquals(AppLimit(45, LimitSource.APP), limit)
    }

    @Test
    fun `an app-specific zero means explicitly unlimited, overriding the category default`() {
        assertNull(resolve(s = base.copy(appLimits = mapOf("x" to 0)), category = AppCategory.SOCIAL))
    }

    @Test
    fun `category defaults apply when there is no app-specific limit`() {
        assertEquals(AppLimit(30, LimitSource.SOCIAL_DEFAULT), resolve(category = AppCategory.SOCIAL))
        assertEquals(AppLimit(30, LimitSource.GAME_DEFAULT), resolve(category = AppCategory.GAME))
    }

    @Test
    fun `a category default of zero leaves the app free`() {
        // video default is 0 out of the box
        assertNull(resolve(category = AppCategory.VIDEO))
        assertNull(resolve(s = base.copy(socialDefaultMin = 0), category = AppCategory.SOCIAL))
    }

    @Test
    fun `communication and unclassified apps are never limited by default`() {
        for (c in listOf(AppCategory.COMMUNICATION, AppCategory.UNSURE, AppCategory.OTHER)) {
            assertNull(resolve(category = c))
        }
    }

    @Test
    fun `LimitStats adds field by field`() {
        val sum = LimitStats(1, 2, 3, 4) + LimitStats(10, 20, 30, 40)
        assertEquals(LimitStats(11, 22, 33, 44), sum)
    }
}
