package com.focus.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The hour-splitting math pulled out of DayAccumulator.add (see [addForegroundMillis]). */
class UsageBucketingTest {
    private val H = DayUsage.HOUR_MS
    private val dayStart = 0L
    private val dayEnd = 24 * H
    private val utc = ZoneOffset.UTC

    private fun add(start: Long, end: Long, plainDay: Boolean = true): Pair<Boolean, LongArray> {
        val target = HashMap<String, LongArray>()
        val counted = addForegroundMillis(target, "a", start, end, dayStart, dayEnd, plainDay, utc)
        return counted to (target["a"] ?: LongArray(24))
    }

    @Test
    fun `an interval inside one hour lands in that hour`() {
        val (counted, h) = add(2 * H + 1000, 2 * H + 4000)
        assertTrue(counted)
        assertEquals(3000L, h[2])
        assertEquals(3000L, h.sum())
    }

    @Test
    fun `an interval crossing an hour boundary is split`() {
        val (_, h) = add(H - 1000, H + 2000)
        assertEquals(1000L, h[0])
        assertEquals(2000L, h[1])
    }

    @Test
    fun `an interval starting before midnight is clamped to the day`() {
        val (counted, h) = add(-5000, 1000)
        assertTrue(counted)
        assertEquals(1000L, h[0])
    }

    @Test
    fun `an interval fully outside the day counts nothing`() {
        assertFalse(add(-5000, -1000).first)
        assertFalse(add(dayEnd + 1000, dayEnd + 2000).first)
        assertFalse(add(5000, 5000).first)   // empty interval
    }

    @Test
    fun `the calendar path matches the plainDay fast path on a UTC day`() {
        val fast = add(90 * 60_000, 150 * 60_000, plainDay = true).second
        val calendar = add(90 * 60_000, 150 * 60_000, plainDay = false).second
        assertEquals(fast.toList(), calendar.toList())
        assertEquals(30 * 60_000L, fast[1])
        assertEquals(30 * 60_000L, fast[2])
    }
}
