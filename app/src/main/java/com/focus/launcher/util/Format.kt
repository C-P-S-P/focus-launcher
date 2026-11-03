package com.focus.launcher.util

import android.content.res.Resources
import com.focus.launcher.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every unit, word and date pattern below comes from the string resources, so a translation can
 * change them all (including word order) without touching code.
 *
 * Dates use the language the strings actually resolved to (`resources_locale`), not the phone's:
 * on a phone set to a language Focus has no translation for, the text falls back to English, and
 * month names must then be English too. The phone's region is kept when the language matches.
 */
fun Resources.appLocale(): Locale {
    val device = configuration.locales.get(0) ?: Locale.getDefault()
    val strings = Locale.forLanguageTag(getString(R.string.resources_locale))
    if (strings.language.isEmpty()) return device
    return if (device.language == strings.language) device else strings
}

/** "2h 41m", "48m", "<1m", "0m" in English. */
fun formatDuration(res: Resources, ms: Long): String {
    if (ms <= 0) return res.getString(R.string.duration_minutes, 0L)
    val totalMinutes = ms / 60_000
    if (totalMinutes == 0L) return res.getString(R.string.duration_under_minute)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0L -> res.getString(R.string.duration_minutes, m)
        m == 0L -> res.getString(R.string.duration_hours, h)
        else -> res.getString(R.string.duration_hours_minutes, h, m)
    }
}

/** Limits and presets: 30 -> "30m", 90 -> "1h 30m". */
fun formatMinutes(res: Resources, minutes: Int): String = formatDuration(res, minutes * 60_000L)

/** 14 -> "14:00" or "2 PM". The 12-hour pattern is a resource, so a language can reorder it. */
fun formatHour(res: Resources, hour: Int, use24h: Boolean): String {
    val h = ((hour % 24) + 24) % 24
    val pattern = if (use24h) "HH:mm" else res.getString(R.string.format_hour_12h)
    return LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern(pattern, res.appLocale()))
}

/** -0.12 -> "12% less than last week", 0.3 -> "30% more than last week". */
fun formatChangeVsLastWeek(res: Resources, fraction: Float): String {
    val pct = (abs(fraction) * 100).roundToInt()
    return when {
        pct == 0 -> res.getString(R.string.change_same_as_last_week)
        fraction < 0 -> res.getString(R.string.change_less_than_last_week, pct)
        else -> res.getString(R.string.change_more_than_last_week, pct)
    }
}

/** "14 – 20 Sep" or "28 Sep – 4 Oct" in English. */
fun formatWeekRange(res: Resources, start: LocalDate, end: LocalDate): String {
    val locale = res.appLocale()
    return if (start.month == end.month) {
        val month = end.format(DateTimeFormatter.ofPattern(res.getString(R.string.format_month_short), locale))
        res.getString(R.string.format_week_range_same_month, start.dayOfMonth, end.dayOfMonth, month)
    } else {
        val dayMonth = DateTimeFormatter.ofPattern(res.getString(R.string.format_day_month), locale)
        res.getString(R.string.format_week_range, start.format(dayMonth), end.format(dayMonth))
    }
}
