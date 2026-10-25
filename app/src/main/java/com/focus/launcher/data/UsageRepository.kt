package com.focus.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Screen time of one calendar day.
 * [appHours] maps package -> milliseconds in the foreground during each of the 24 hours.
 */
class DayUsage(
    val date: LocalDate,
    val appHours: Map<String, LongArray>,
    val unlocks: Int,
) {
    val perApp: Map<String, Long> = appHours.mapValues { (_, hours) -> hours.sum() }

    val perHour: LongArray = LongArray(24).also { out ->
        for (hours in appHours.values) for (i in 0 until 24) out[i] += hours[i]
        // Split-screen can put two apps in front at once; an hour still only has 60 minutes.
        for (i in 0 until 24) out[i] = out[i].coerceAtMost(HOUR_MS)
    }

    val total: Long = perHour.sum()

    fun topApps(n: Int): List<Pair<String, Long>> =
        perApp.entries.asSequence().filter { it.value >= 1000 }
            .sortedByDescending { it.value }.take(n).map { it.key to it.value }.toList()

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", CACHE_VERSION)
        put("date", date.toString())
        put("unlocks", unlocks)
        put("apps", JSONObject().apply {
            for ((pkg, hours) in appHours) put(pkg, JSONArray().apply { hours.forEach { put(it) } })
        })
    }

    companion object {
        const val HOUR_MS = 3_600_000L

        /** Bump whenever the way usage is computed changes, so days cached the old way are redone. */
        // 3: time in the Settings app is no longer mistaken for time on a home screen.
        const val CACHE_VERSION = 3

        fun empty(date: LocalDate) = DayUsage(date, emptyMap(), 0)

        /** Null when the file was written by an older version of the algorithm. */
        fun fromJson(o: JSONObject): DayUsage? {
            if (o.optInt("v") != CACHE_VERSION) return null
            val apps = o.getJSONObject("apps")
            val map = HashMap<String, LongArray>()
            for (pkg in apps.keys()) {
                val arr = apps.getJSONArray(pkg)
                map[pkg] = LongArray(24) { i -> if (i < arr.length()) arr.optLong(i) else 0L }
            }
            return DayUsage(LocalDate.parse(o.getString("date")), map, o.optInt("unlocks"))
        }
    }
}

/**
 * Adds one foreground interval `[start, end)` to [target] as per-hour milliseconds within the day
 * `[dayStart, dayEnd)`. Pure (no Android, no instance state) so the hour-splitting math can be
 * unit-tested. [plainDay] skips the calendar on non-DST days; [zone] is read only when it is false.
 * Returns false when the interval falls entirely outside the day.
 */
internal fun addForegroundMillis(
    target: HashMap<String, LongArray>,
    pkg: String,
    start: Long,
    end: Long,
    dayStart: Long,
    dayEnd: Long,
    plainDay: Boolean,
    zone: ZoneId,
): Boolean {
    var t = maxOf(start, dayStart)
    val stop = minOf(end, dayEnd)
    if (t >= stop) return false
    val perHour = target.getOrPut(pkg) { LongArray(24) }
    while (t < stop) {
        val hour = if (plainDay) ((t - dayStart) / DayUsage.HOUR_MS).toInt().coerceIn(0, 23)
            else Instant.ofEpochMilli(t).atZone(zone).hour
        val hourEnd = if (plainDay) dayStart + (hour + 1) * DayUsage.HOUR_MS
            else Instant.ofEpochMilli(t).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
        val segmentEnd = minOf(stop, hourEnd)
        perHour[hour] += segmentEnd - t
        t = segmentEnd
    }
    return true
}

/**
 * Turns the system's raw usage events (activity resumed / paused, screen off, unlock) into
 * per-app, per-hour foreground time.
 *
 * Today is kept as a running total ([DayAccumulator]): every system event is read exactly once,
 * and a refresh only asks the OS for what happened since the previous one. That matters because
 * a launcher refreshes on every return to the home screen, a hundred or more times a day, and the
 * day's event log grows to tens of thousands of entries by evening.
 *
 * The system only keeps raw events for roughly a week, so every finished day is written to a small
 * JSON file. That cache is what lets the weekly review look further back than the OS can.
 */
class UsageRepository(private val context: Context, private val apps: () -> AppRepository) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val cacheDir = File(context.filesDir, "usage").apply { mkdirs() }

    private val _today = MutableStateFlow<DayUsage?>(null)
    /** Today's usage as of the last [refreshToday]. Null until computed or while access is missing. */
    val today: StateFlow<DayUsage?> = _today

    @Volatile private var todayComputedAt = 0L
    @Volatile private var ignoredCache: Set<String>? = null
    private var accumulator: DayAccumulator? = null

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        // The platform has deprecated and un-deprecated both variants of this call over the years.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** How long ago [today] was computed. */
    fun todayAgeMs(): Long = System.currentTimeMillis() - todayComputedAt

    /** Brings today's numbers up to date unless they are younger than [maxAgeMs]. */
    suspend fun refreshToday(maxAgeMs: Long = 5_000): DayUsage? = withContext(Dispatchers.IO) {
        computeTodayBlocking(maxAgeMs)
    }

    /** Foreground time of [pkg] today, including the session in progress. Blocking. */
    fun usageTodayMs(pkg: String, maxAgeMs: Long = 0): Long =
        computeTodayBlocking(maxAgeMs)?.perApp?.get(pkg) ?: 0L

    /** Lets go of everything that can be rebuilt; called when the system is short of memory. */
    @Synchronized
    fun trimMemory() {
        ignoredCache = null
    }

    @Synchronized
    private fun computeTodayBlocking(maxAgeMs: Long): DayUsage? {
        if (!hasAccess()) {
            accumulator = null
            _today.value = null
            return null
        }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val cached = _today.value?.takeIf { it.date == date }
        if (cached != null && now - todayComputedAt <= maxAgeMs) return cached

        // A new day, a new time zone, or a clock that jumped backwards: start the day over.
        var acc = accumulator
        if (acc == null || acc.date != date || acc.zone != zone || now < acc.consumedUntil) {
            acc = DayAccumulator(date, zone)
            accumulator = acc
        }
        val versionBefore = acc.version
        acc.consume(now)
        todayComputedAt = now

        // Nothing new happened and no counted app is in front (the usual case while the home
        // screen itself is showing): the previous value is still exact. Handing back the very same
        // object also means nothing downstream recomposes.
        if (cached != null && acc.version == versionBefore && !acc.hasCountedAppInFront(now)) return cached

        val fresh = acc.snapshot(now)
        _today.value = fresh
        return fresh
    }

    /**
     * Usage for every day in [from]..[to] (inclusive). Today comes from the running total, finished
     * days from the cache when possible, and everything else from system events in a single pass.
     */
    suspend fun loadDays(from: LocalDate, to: LocalDate): Map<LocalDate, DayUsage> = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val out = HashMap<LocalDate, DayUsage>()
        val missing = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) {
            when {
                d.isAfter(today) -> out[d] = DayUsage.empty(d)
                d == today -> out[d] = computeTodayBlocking(maxAgeMs = 30_000) ?: DayUsage.empty(d)
                else -> readCache(d)?.let { out[d] = it } ?: run { missing += d }
            }
            d = d.plusDays(1)
        }
        if (missing.isNotEmpty() && hasAccess()) {
            val computed = compute(missing.first(), missing.last())
            for (day in missing) {
                val usage = computed[day] ?: DayUsage.empty(day)
                out[day] = usage
                // Never cache an empty day: it may only be empty because the OS already dropped
                // the events, or because access was granted late.
                if (usage.total > 0) writeCache(usage)
            }
        }
        for (day in missing) out.putIfAbsent(day, DayUsage.empty(day))
        out
    }

    /** Persists the last week of finished days while the OS still remembers them. */
    suspend fun backfill() {
        val today = LocalDate.now()
        loadDays(today.minusDays(8), today.minusDays(1))
        pruneCache(today.minusDays(400))
    }

    /**
     * The package whose activity was most recently resumed at or after [since]; null when nothing
     * was resumed in that window. Lets the timer service double-check what is really in front.
     */
    fun lastResumedPackage(since: Long): String? {
        if (!hasAccess()) return null
        val events = try {
            usm.queryEvents(since, System.currentTimeMillis() + 1000)
        } catch (_: Exception) {
            return null
        } ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    /**
     * Per package over the last [days] days: foreground time and the moment of last use, taken
     * from the system's own aggregates. Only orders the drawer ("most used", "recent").
     */
    // ponytail: system aggregates, not the ForegroundTracker; exact minutes do not matter for an order.
    fun sortStats(days: Int = 7): Map<String, Pair<Long, Long>> {
        if (!hasAccess()) return emptyMap()
        val now = System.currentTimeMillis()
        return try {
            usm.queryAndAggregateUsageStats(now - days * 24 * DayUsage.HOUR_MS, now)
                .mapValues { (_, s) -> s.totalTimeInForeground to s.lastTimeUsed }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---- event crunching ---------------------------------------------------------------------

    /** Home screens and the system UI: they take part in the event stream but are not "screen time". */
    private fun ignoredPackages(): Set<String> {
        ignoredCache?.let { return it }
        val set = HashSet<String>(apps().homePackages())
        set += context.packageName
        set += "com.android.systemui"
        ignoredCache = set
        return set
    }

    /** Feeds one system event to [tracker]; returns true when it was an unlock. */
    private fun dispatch(e: UsageEvents.Event, tracker: ForegroundTracker): Boolean {
        val pkg = e.packageName ?: return false
        val ts = e.timeStamp
        when (e.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> tracker.resumed(pkg, ts)
            UsageEvents.Event.ACTIVITY_PAUSED -> tracker.paused(pkg, ts)
            UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> tracker.screenOff(ts)
            UsageEvents.Event.KEYGUARD_HIDDEN -> {
                tracker.tick(ts)
                return true
            }
            else -> tracker.tick(ts)
        }
        return false
    }

    /**
     * Running totals for one calendar day. Launchers and the system UI still go through the
     * tracker, so that coming home "covers" the app that was open; they are left out when adding up.
     */
    private inner class DayAccumulator(val date: LocalDate, val zone: ZoneId) {
        private val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        private val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        /** True on all but daylight-saving days; lets an hour be found by division instead of a calendar. */
        private val plainDay = dayEnd - dayStart == 24 * DayUsage.HOUR_MS
        private val ignored = ignoredPackages()
        private val hours = HashMap<String, LongArray>()
        private var unlocks = 0
        private val event = UsageEvents.Event()
        private val tracker = ForegroundTracker(COVER_GRACE_MS) { pkg, start, end -> if (add(hours, pkg, start, end)) version++ }

        /** Everything before this instant has been read. Starts early so a session that straddles midnight is seen whole. */
        var consumedUntil = dayStart - LOOKBACK_MS
            private set

        /** Goes up whenever the totals change. */
        var version = 0
            private set

        /** Reads the events that arrived since last time. The newest moment is left to settle for the next round. */
        fun consume(now: Long) {
            val until = minOf(now - SETTLE_MS, dayEnd + LOOKBACK_MS)
            if (until <= consumedUntil) return
            val events = try {
                usm.queryEvents(consumedUntil, until)
            } catch (_: Exception) {
                null
            } ?: return
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (dispatch(event, tracker) && event.timeStamp in dayStart until dayEnd) {
                    unlocks++
                    version++
                }
            }
            consumedUntil = until
        }

        fun hasCountedAppInFront(now: Long): Boolean =
            tracker.hasOpenIntervals && tracker.openIntervals(now).any { it.first !in ignored }

        /** Closed totals plus whatever is in front right now, counted up to [now]. */
        fun snapshot(now: Long): DayUsage {
            val copy = HashMap<String, LongArray>(hours.size + 2)
            for ((pkg, perHour) in hours) copy[pkg] = perHour.copyOf()
            for ((pkg, start, end) in tracker.openIntervals(minOf(now, dayEnd))) add(copy, pkg, start, end)
            return DayUsage(date, copy, unlocks)
        }

        /** The hour-splitting itself is [addForegroundMillis], so that the code the app runs is what the tests run. */
        private fun add(target: HashMap<String, LongArray>, pkg: String, start: Long, end: Long): Boolean =
            pkg !in ignored && addForegroundMillis(target, pkg, start, end, dayStart, dayEnd, plainDay, zone)
    }

    /** Finished days, straight from the event log in one pass. Used for history, never for today. */
    private fun compute(from: LocalDate, to: LocalDate): Map<LocalDate, DayUsage> {
        val zone = ZoneId.systemDefault()
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val rangeEnd = minOf(to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        if (rangeEnd <= rangeStart) return emptyMap()

        val events = try {
            // Look back a little so a session that straddles midnight is seen from its start.
            usm.queryEvents(rangeStart - LOOKBACK_MS, rangeEnd)
        } catch (_: Exception) {
            null
        } ?: return emptyMap()

        val ignored = ignoredPackages()
        val buckets = HashMap<LocalDate, HashMap<String, LongArray>>()
        val unlocks = HashMap<LocalDate, Int>()

        fun add(pkg: String, start: Long, end: Long) {
            if (pkg in ignored) return
            var t = maxOf(start, rangeStart)
            val stop = minOf(end, rangeEnd)
            while (t < stop) {
                val zdt = Instant.ofEpochMilli(t).atZone(zone)
                val nextHour = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val segmentEnd = minOf(stop, nextHour)
                val hours = buckets.getOrPut(zdt.toLocalDate()) { HashMap() }.getOrPut(pkg) { LongArray(24) }
                hours[zdt.hour] += segmentEnd - t
                t = segmentEnd
            }
        }

        val tracker = ForegroundTracker(COVER_GRACE_MS, ::add)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (dispatch(e, tracker) && e.timeStamp >= rangeStart) {
                val day = Instant.ofEpochMilli(e.timeStamp).atZone(zone).toLocalDate()
                unlocks[day] = (unlocks[day] ?: 0) + 1
            }
        }
        tracker.finish(rangeEnd)

        val out = HashMap<LocalDate, DayUsage>()
        var d = from
        while (!d.isAfter(to)) {
            out[d] = DayUsage(d, buckets[d] ?: emptyMap(), unlocks[d] ?: 0)
            d = d.plusDays(1)
        }
        return out
    }

    // ---- day cache ---------------------------------------------------------------------------

    private fun cacheFile(date: LocalDate) = File(cacheDir, "$date.json")

    private fun readCache(date: LocalDate): DayUsage? = try {
        cacheFile(date).takeIf { it.exists() }?.let { DayUsage.fromJson(JSONObject(it.readText())) }
    } catch (_: Exception) {
        null
    }

    private fun writeCache(usage: DayUsage) {
        try {
            cacheFile(usage.date).writeText(usage.toJson().toString())
        } catch (_: Exception) {
        }
    }

    private fun pruneCache(olderThan: LocalDate) {
        cacheDir.listFiles()?.forEach { f ->
            val date = try {
                LocalDate.parse(f.name.removeSuffix(".json"))
            } catch (_: Exception) {
                null
            }
            if (date != null && date.isBefore(olderThan)) f.delete()
        }
    }

    private companion object {
        const val LOOKBACK_MS = 3 * 3_600_000L
        const val COVER_GRACE_MS = 3_000L

        /**
         * The last moments before "now" are not read yet. The OS writes its log from another
         * thread, so the newest entries can still be landing; leaving them for the next round is
         * what makes reading every event exactly once safe.
         */
        const val SETTLE_MS = 1_500L
    }
}
