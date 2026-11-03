package com.focus.launcher.data

import androidx.annotation.StringRes
import com.focus.launcher.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class DayPeriod(@StringRes val labelRes: Int, val hours: String) {
    MORNING(R.string.review_period_morning, "5 – 12"),
    AFTERNOON(R.string.review_period_afternoon, "12 – 17"),
    EVENING(R.string.review_period_evening, "17 – 21"),
    NIGHT(R.string.review_period_night, "21 – 5");

    companion object {
        fun ofHour(hour: Int): DayPeriod = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

data class AppWeekUsage(
    val packageName: String,
    val label: String,
    val total: Long,
    /** The part of the day this app is used the most. */
    val peakPeriod: DayPeriod,
    val daysUsed: Int,
    val limitStats: LimitStats,
)

/** Everything the weekly review shows, precomputed off the main thread. */
class WeekSummary(
    val weekStart: LocalDate,
    /** Seven days, in order, starting at [weekStart]. Future days are empty. */
    val days: List<DayUsage>,
    /** How many days of this week have begun (1..7); the divisor for the daily average. */
    val daysElapsed: Int,
    val previousDailyAverage: Long?,
    val topApps: List<AppWeekUsage>,
    val limitStats: LimitStats,
) {
    val weekEnd: LocalDate = weekStart.plusDays(6)
    val total: Long = days.sumOf { it.total }
    val dailyAverage: Long = if (daysElapsed > 0) total / daysElapsed else 0L
    val unlocks: Int = days.sumOf { it.unlocks }
    val hasData: Boolean = total > 0

    /** Foreground time per hour of day, summed over the week. */
    val perHour: LongArray = LongArray(24).also { out -> days.forEach { d -> for (i in 0 until 24) out[i] += d.perHour[i] } }

    val perPeriod: Map<DayPeriod, Long> = DayPeriod.entries.associateWith { p ->
        (0 until 24).filter { DayPeriod.ofHour(it) == p }.sumOf { perHour[it] }
    }

    val peakHour: Int? = if (hasData) perHour.indices.maxByOrNull { perHour[it] } else null
    val peakPeriod: DayPeriod? = if (hasData) perPeriod.maxByOrNull { it.value }?.key else null
    val busiestDay: DayUsage? = days.filter { it.total > 0 }.maxByOrNull { it.total }
    val lightestDay: DayUsage? = days.take(daysElapsed).filter { it.total > 0 }.minByOrNull { it.total }

    /** Change of the daily average versus last week, e.g. -0.12 for 12% less. Null if unknown. */
    val changeVsPrevious: Float? = previousDailyAverage?.takeIf { it > 0 && hasData }
        ?.let { prev -> (dailyAverage - prev).toFloat() / prev.toFloat() }

    companion object {
        fun weekStartOf(date: LocalDate, mondayStart: Boolean): LocalDate =
            date.with(TemporalAdjusters.previousOrSame(if (mondayStart) DayOfWeek.MONDAY else DayOfWeek.SUNDAY))

        fun build(
            weekStart: LocalDate,
            today: LocalDate,
            usage: Map<LocalDate, DayUsage>,
            limitStats: Map<String, LimitStats>,
            labelOf: (String) -> String,
        ): WeekSummary {
            val days = (0L..6L).map { offset -> weekStart.plusDays(offset).let { usage[it] ?: DayUsage.empty(it) } }
            val elapsed = days.count { !it.date.isAfter(today) }.coerceIn(1, 7)

            val previous = (1L..7L).mapNotNull { usage[weekStart.minusDays(it)] }
            // A comparison only means something if most of last week was actually recorded.
            val previousAverage = previous.filter { it.total > 0 }.takeIf { it.size >= 4 }
                ?.let { recorded -> recorded.sumOf { it.total } / recorded.size }

            val perApp = HashMap<String, LongArray>()
            val daysUsed = HashMap<String, Int>()
            for (day in days) {
                for ((pkg, hours) in day.appHours) {
                    val acc = perApp.getOrPut(pkg) { LongArray(24) }
                    for (i in 0 until 24) acc[i] += hours[i]
                    if (hours.sum() >= 60_000) daysUsed[pkg] = (daysUsed[pkg] ?: 0) + 1
                }
            }
            val top = perApp.entries
                .map { (pkg, hours) -> Triple(pkg, hours, hours.sum()) }
                .filter { it.third >= 60_000 }
                .sortedByDescending { it.third }
                .take(10)
                .map { (pkg, hours, total) ->
                    val peak = DayPeriod.entries.maxByOrNull { p ->
                        (0 until 24).filter { DayPeriod.ofHour(it) == p }.sumOf { hours[it] }
                    } ?: DayPeriod.EVENING
                    AppWeekUsage(pkg, labelOf(pkg), total, peak, daysUsed[pkg] ?: 0, limitStats[pkg] ?: LimitStats())
                }

            return WeekSummary(
                weekStart = weekStart,
                days = days,
                daysElapsed = elapsed,
                previousDailyAverage = previousAverage,
                topApps = top,
                limitStats = limitStats.values.fold(LimitStats()) { a, b -> a + b },
            )
        }
    }
}
