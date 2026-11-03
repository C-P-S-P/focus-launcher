package com.focus.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Pure checks on [DayUsage]: the per-hour cap, totals, the top-apps filter and the cache JSON. */
class DayUsageTest {
    private val date = LocalDate.of(2026, 1, 15)
    private val H = DayUsage.HOUR_MS

    private fun hours(vararg pairs: Pair<Int, Long>) = LongArray(24).also { for ((i, v) in pairs) it[i] = v }

    @Test
    fun `an hour never exceeds 60 minutes even with two apps in front`() {
        // Split screen: two apps each report a full hour 0. The hour still holds only one hour.
        val usage = DayUsage(date, mapOf("a" to hours(0 to H), "b" to hours(0 to H)), unlocks = 0)
        assertEquals(H, usage.perHour[0])
        assertEquals(H, usage.total)
    }

    @Test
    fun `perApp and total add the raw hours without the cap`() {
        val usage = DayUsage(date, mapOf("a" to hours(0 to 1000, 1 to 2000)), unlocks = 0)
        assertEquals(3000L, usage.perApp["a"])
        assertEquals(3000L, usage.total)
    }

    @Test
    fun `topApps drops sub-second apps and orders by time`() {
        val usage = DayUsage(
            date,
            mapOf(
                "big" to hours(0 to 5000),
                "small" to hours(0 to 999),   // below the 1s floor -> excluded
                "mid" to hours(0 to 2000),
            ),
            unlocks = 0,
        )
        assertEquals(listOf("big" to 5000L, "mid" to 2000L), usage.topApps(5))
        assertEquals(listOf("big" to 5000L), usage.topApps(1))
    }

    @Test
    fun `toJson then fromJson round-trips`() {
        val usage = DayUsage(date, mapOf("a" to hours(3 to 1234, 20 to 5678)), unlocks = 7)
        val back = DayUsage.fromJson(usage.toJson())!!
        assertEquals(date, back.date)
        assertEquals(7, back.unlocks)
        assertEquals(usage.perApp, back.perApp)
        assertEquals(usage.perHour.toList(), back.perHour.toList())
    }

    @Test
    fun `fromJson rejects a cache written by an older algorithm`() {
        val stale = usage().toJson().put("v", DayUsage.CACHE_VERSION - 1)
        assertNull(DayUsage.fromJson(stale))
    }

    private fun usage() = DayUsage(date, mapOf("a" to hours(0 to 1000)), unlocks = 1)
}
