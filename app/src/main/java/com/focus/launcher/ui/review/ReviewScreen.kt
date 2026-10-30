package com.focus.launcher.ui.review

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.DayPeriod
import com.focus.launcher.data.DayUsage
import com.focus.launcher.data.Settings
import com.focus.launcher.data.TimeFormat
import com.focus.launcher.data.WeekSummary
import com.focus.launcher.ui.components.FocusButton
import com.focus.launcher.ui.components.Hairline
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.TabChip
import com.focus.launcher.ui.components.TextInputDialog
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.home.DayBar
import com.focus.launcher.ui.home.HourScale
import com.focus.launcher.ui.home.currentLocale
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.Perms
import com.focus.launcher.util.appLocale
import com.focus.launcher.util.formatChangeVsLastWeek
import com.focus.launcher.util.formatDuration
import com.focus.launcher.util.formatHour
import com.focus.launcher.util.formatMinutes
import com.focus.launcher.util.formatWeekRange
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(settings: Settings, initialWeek: LocalDate?, onBack: () -> Unit) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(if (initialWeek != null) 1 else 0) }
    var hasAccess by remember { mutableStateOf(Perms.hasUsageAccess()) }
    LifecycleResumeEffect(Unit) {
        hasAccess = Perms.hasUsageAccess()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            T("←", Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 10.dp), size = 22.sp)
            T(stringResource(R.string.review_title), Modifier.weight(1f).padding(start = 4.dp), size = 22.sp, weight = FontWeight.Medium, maxLines = 1)
        }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabChip(stringResource(R.string.review_tab_today), tab == 0) { tab = 0 }
            TabChip(stringResource(R.string.review_tab_week), tab == 1) { tab = 1 }
        }
        Hairline()

        if (!hasAccess) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
                T(stringResource(R.string.review_usage_access_needed), size = 17.sp, lineHeight = 25.sp)
                VSpace(8.dp)
                T(stringResource(R.string.review_usage_access_private), size = 14.sp, color = c.dim, lineHeight = 20.sp)
                VSpace(22.dp)
                FocusButton(stringResource(R.string.review_allow_usage_access), primary = true) { Perms.openUsageAccess(context) }
            }
            return@Column
        }

        Crossfade(targetState = tab, animationSpec = tween(220), label = "review tab") { shown ->
            if (shown == 0) TodayTab(settings) else WeekTab(settings, initialWeek)
        }
    }
}

// ---- Today -----------------------------------------------------------------------------------

@Composable
private fun TodayTab(settings: Settings) {
    val c = LocalFocusColors.current
    val res = LocalResources.current
    val today by Graph.usage.today.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Graph.usage.refreshToday(maxAgeMs = 0) }

    val usage = today ?: DayUsage.empty(LocalDate.now())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        VSpace(26.dp)
        T(formatDuration(res, usage.total), size = 54.sp, weight = FontWeight.Light, maxLines = 1)
        val share = (usage.total * 100f / 86_400_000f)
        T(
            pluralStringResource(
                R.plurals.review_today_summary, usage.unlocks,
                if (share < 1f && usage.total > 0) "<1" else share.roundToInt().toString(), usage.unlocks,
            ),
            size = 14.sp, color = c.dim,
        )
        VSpace(22.dp)
        DayBar(usage.perHour, height = 18.dp, currentHour = java.time.LocalTime.now().hour)
        VSpace(2.dp)
        HourScale()

        VSpace(34.dp)
        Label(stringResource(R.string.review_apps_today))
        VSpace(8.dp)
        val top = usage.topApps(12)
        if (top.isEmpty()) {
            T(stringResource(R.string.review_today_empty), Modifier.padding(vertical = 10.dp), size = 16.sp, color = c.dim)
        }
        val max = top.firstOrNull()?.second ?: 1L
        for ((pkg, ms) in top) {
            val limit = Graph.limits.limitFor(pkg, settings)
            UsageRow(
                label = Graph.apps.labelForPackage(pkg),
                value = limit?.let { stringResource(R.string.review_used_of_limit, formatDuration(res, ms), formatMinutes(res, it.minutes)) }
                    ?: formatDuration(res, ms),
                fraction = ms.toFloat() / max,
                note = null,
            )
        }
        VSpace(40.dp)
    }
}

// ---- Week ------------------------------------------------------------------------------------

@Composable
private fun WeekTab(settings: Settings, initialWeek: LocalDate?) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val res = LocalResources.current
    val locale = currentLocale()
    val today = LocalDate.now()
    val currentWeek = WeekSummary.weekStartOf(today, settings.weekStartsMonday)
    var weekStart by rememberSaveable(stateSaver = LocalDateSaver) {
        mutableStateOf(initialWeek?.let { WeekSummary.weekStartOf(it, settings.weekStartsMonday) } ?: currentWeek)
    }
    var summary by remember { mutableStateOf<WeekSummary?>(null) }

    LaunchedEffect(weekStart) {
        summary = null
        val days = Graph.usage.loadDays(weekStart.minusDays(7), weekStart.plusDays(6))
        val stats = Graph.limits.stats(weekStart, weekStart.plusDays(6))
        summary = WeekSummary.build(weekStart, today, days, stats) { Graph.apps.labelForPackage(it) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        // Week switcher
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            T("‹", Modifier.clickable { weekStart = weekStart.minusWeeks(1) }.padding(horizontal = 14.dp, vertical = 6.dp), size = 26.sp, color = c.dim)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                T(formatWeekRange(res, weekStart, weekStart.plusDays(6)), size = 17.sp, weight = FontWeight.Medium, maxLines = 1)
                T(
                    when (weekStart) {
                        currentWeek -> stringResource(R.string.review_this_week)
                        currentWeek.minusWeeks(1) -> stringResource(R.string.review_last_week)
                        else -> weekStart.year.toString()
                    },
                    size = 12.sp, color = c.dim, maxLines = 1,
                )
            }
            val canGoForward = weekStart.isBefore(currentWeek)
            T(
                "›",
                Modifier.clickable(enabled = canGoForward) { weekStart = weekStart.plusWeeks(1) }.padding(horizontal = 14.dp, vertical = 6.dp),
                size = 26.sp, color = if (canGoForward) c.dim else c.line,
            )
        }

        val s = summary
        if (s == null) {
            T(stringResource(R.string.review_week_loading), Modifier.padding(vertical = 40.dp), size = 16.sp, color = c.dim)
            return@Column
        }
        if (!s.hasData) {
            VSpace(36.dp)
            T(stringResource(R.string.review_week_no_data), size = 17.sp)
            VSpace(8.dp)
            T(
                stringResource(R.string.review_week_no_data_detail),
                size = 14.sp, color = c.dim, lineHeight = 20.sp,
            )
            return@Column
        }

        // Headline
        VSpace(22.dp)
        T(formatDuration(res, s.total), size = 54.sp, weight = FontWeight.Light, maxLines = 1)
        val perDay = stringResource(R.string.review_daily_average, formatDuration(res, s.dailyAverage))
        T(
            s.changeVsPrevious?.let { "$perDay  ·  ${formatChangeVsLastWeek(res, it)}" } ?: perDay,
            size = 14.sp, color = c.dim,
        )
        val waking = s.dailyAverage * 100f / (16 * 3_600_000f)
        if (waking >= 1f) {
            VSpace(4.dp)
            T(stringResource(R.string.review_waking_share, waking.roundToInt()), size = 14.sp, color = c.dim)
        }

        // Day by day
        VSpace(30.dp)
        Label(stringResource(R.string.review_day_by_day))
        VSpace(12.dp)
        val dayMax = s.days.maxOf { it.total }.coerceAtLeast(1L)
        Bars(
            fractions = s.days.map { it.total.toFloat() / dayMax },
            height = 130.dp,
            topLabels = s.days.map { if (it.total > 0) shortHours(res, it.total) else "" },
            bottomLabels = s.days.map { it.date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, locale) },
            emphasized = s.days.map { it.date == s.busiestDay?.date },
            muted = s.days.map { it.date.isAfter(today) },
        )
        s.busiestDay?.let { busiest ->
            VSpace(10.dp)
            val name = busiest.date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, locale)
            val light = s.lightestDay?.takeIf { it.date != busiest.date }
            T(
                light?.let {
                    stringResource(
                        R.string.review_busiest_and_lightest_day, name, formatDuration(res, busiest.total),
                        it.date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, locale), formatDuration(res, it.total),
                    )
                } ?: stringResource(R.string.review_busiest_day, name, formatDuration(res, busiest.total)),
                size = 14.sp, color = c.dim, lineHeight = 20.sp,
            )
        }

        // Time of day
        VSpace(32.dp)
        Label(stringResource(R.string.review_time_of_day))
        VSpace(12.dp)
        val hourMax = s.perHour.max().coerceAtLeast(1L)
        Bars(
            fractions = s.perHour.map { it.toFloat() / hourMax },
            height = 84.dp,
            gap = 2.dp,
            emphasized = s.perHour.indices.map { it == s.peakHour },
        )
        VSpace(2.dp)
        HourScale()
        VSpace(14.dp)
        val use24h = when (settings.timeFormat) {
            TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
            TimeFormat.H24 -> true
            TimeFormat.H12 -> false
        }
        for (period in DayPeriod.entries) {
            val ms = s.perPeriod[period] ?: 0L
            UsageRow(
                label = stringResource(period.labelRes),
                value = stringResource(R.string.review_time_and_share, formatDuration(res, ms), (ms * 100f / s.total).roundToInt()),
                fraction = ms.toFloat() / (s.perPeriod.values.max().coerceAtLeast(1L)),
                note = period.hours,
                strong = period == s.peakPeriod,
            )
        }
        s.peakHour?.let { hour ->
            VSpace(6.dp)
            T(stringResource(R.string.review_busiest_hour, formatHour(res, hour, use24h), formatHour(res, hour + 1, use24h)), size = 14.sp, color = c.dim)
        }

        // Apps
        VSpace(32.dp)
        Label(stringResource(R.string.review_where_it_went))
        VSpace(8.dp)
        val appMax = s.topApps.firstOrNull()?.total ?: 1L
        for (app in s.topApps) {
            val notes = buildList {
                add(stringResource(app.peakPeriod.mostlyRes))
                add(pluralStringResource(R.plurals.review_days_used, s.daysElapsed, app.daysUsed, s.daysElapsed))
                val past = app.limitStats.continued + app.limitStats.bypassed
                if (past > 0) add(stringResource(R.string.review_past_limit, past))
            }
            UsageRow(
                label = app.label,
                value = stringResource(R.string.review_time_and_share, formatDuration(res, app.total), (app.total * 100f / s.total).roundToInt()),
                fraction = app.total.toFloat() / appMax,
                note = notes.joinToString("  ·  "),
            )
        }

        // Limits and unlocks
        VSpace(32.dp)
        Label(stringResource(R.string.review_limits_title))
        VSpace(10.dp)
        val l = s.limitStats
        if (l.blocked + l.continued + l.bypassed == 0) {
            T(stringResource(R.string.review_no_limit_hit), size = 15.sp, lineHeight = 22.sp)
        } else {
            T(
                if (l.continuedMinutes > 0) {
                    stringResource(
                        R.string.review_limits_summary_extra,
                        times(res, l.blocked), times(res, l.continued), formatMinutes(res, l.continuedMinutes), times(res, l.bypassed),
                    )
                } else {
                    stringResource(R.string.review_limits_summary, times(res, l.blocked), times(res, l.continued), times(res, l.bypassed))
                },
                size = 15.sp, lineHeight = 22.sp,
            )
        }
        if (s.unlocks > 0) {
            VSpace(6.dp)
            T(
                pluralStringResource(R.plurals.review_unlocks, s.unlocks, s.unlocks, (s.unlocks.toFloat() / s.daysElapsed).roundToInt()),
                size = 15.sp, color = c.dim, lineHeight = 22.sp,
            )
        }

        // Reflection
        VSpace(32.dp)
        Hairline()
        VSpace(24.dp)
        Reflection(weekStart)
        VSpace(48.dp)
    }
}

/** A sentence to carry into next week, plus a reminder of what was written the week before. */
@Composable
private fun Reflection(weekStart: LocalDate) {
    val c = LocalFocusColors.current
    var intention by remember(weekStart) { mutableStateOf(Graph.state.intention(weekStart)) }
    val previous = remember(weekStart) { Graph.state.intention(weekStart.minusWeeks(1)) }
    var editing by remember { mutableStateOf(false) }

    Label(stringResource(R.string.review_reflect))
    VSpace(10.dp)
    T(stringResource(R.string.review_reflect_question), size = 20.sp, weight = FontWeight.Light, lineHeight = 28.sp)
    if (previous.isNotEmpty()) {
        VSpace(14.dp)
        T(stringResource(R.string.review_previous_intention), size = 13.sp, color = c.dim)
        T(stringResource(R.string.review_quoted, previous), Modifier.padding(top = 2.dp), size = 16.sp, lineHeight = 23.sp)
    }
    VSpace(16.dp)
    if (intention.isEmpty()) {
        FocusButton(stringResource(R.string.review_write_intention)) { editing = true }
    } else {
        T(stringResource(R.string.review_next_week), size = 13.sp, color = c.dim)
        T(stringResource(R.string.review_quoted, intention), Modifier.clickable { editing = true }.padding(top = 2.dp, bottom = 6.dp), size = 16.sp, lineHeight = 23.sp)
    }
    if (editing) {
        TextInputDialog(
            title = stringResource(R.string.review_intention_title),
            subtitle = stringResource(R.string.review_intention_subtitle),
            initial = intention,
            placeholder = stringResource(R.string.review_intention_placeholder),
            onDismiss = { editing = false },
        ) { text ->
            Graph.state.setIntention(weekStart, text)
            intention = text
        }
    }
}

// ---- pieces ----------------------------------------------------------------------------------

/** Name and value over a thin proportional bar. The list rows of both tabs. */
@Composable
private fun UsageRow(label: String, value: String, fraction: Float, note: String?, strong: Boolean = false) {
    val c = LocalFocusColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            T(label, Modifier.weight(1f), size = 17.sp, weight = if (strong) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            T(value, Modifier.padding(start = 12.dp), size = 14.sp, color = c.dim, maxLines = 1)
        }
        if (note != null) T(note, Modifier.padding(top = 2.dp), size = 12.sp, color = c.faint, maxLines = 1)
        VSpace(7.dp)
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.line)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.004f, 1f)).fillMaxHeight().background(c.fg))
        }
    }
}

/** Vertical bar chart drawn with plain boxes, so the labels line up with the bars for free. */
@Composable
private fun Bars(
    fractions: List<Float>,
    height: Dp,
    gap: Dp = 8.dp,
    topLabels: List<String>? = null,
    bottomLabels: List<String>? = null,
    emphasized: List<Boolean>? = null,
    muted: List<Boolean>? = null,
) {
    val c = LocalFocusColors.current
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
        fractions.forEachIndexed { i, fraction ->
            val strong = emphasized?.get(i) == true
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (topLabels != null) {
                    T(topLabels[i], size = 10.sp, color = if (strong) c.fg else c.dim, maxLines = 1)
                    VSpace(4.dp)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val barColor: Color = if (strong || emphasized == null) c.fg else c.dim
                    if (fraction > 0f) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceIn(0.015f, 1f)).background(barColor))
                    } else {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    }
                }
                if (bottomLabels != null) {
                    VSpace(6.dp)
                    T(
                        bottomLabels[i], size = 11.sp,
                        color = if (muted?.get(i) == true) c.line else if (strong) c.fg else c.dim,
                        weight = if (strong) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun shortHours(res: Resources, ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) res.getString(R.string.review_bar_minutes, minutes)
    else res.getString(R.string.review_bar_hours, String.format(res.appLocale(), "%.1f", minutes / 60f))
}

/** "once", "twice", "3 times". Two has its own string, because English plural rules cannot single it out. */
private fun times(res: Resources, n: Int): String = when (n) {
    2 -> res.getString(R.string.review_times_two)
    else -> res.getQuantityString(R.plurals.review_times, n, n)
}

/** "mostly evening": its own string per part of the day, since the word changes form in many languages. */
@get:StringRes
private val DayPeriod.mostlyRes: Int
    get() = when (this) {
        DayPeriod.MORNING -> R.string.review_mostly_morning
        DayPeriod.AFTERNOON -> R.string.review_mostly_afternoon
        DayPeriod.EVENING -> R.string.review_mostly_evening
        DayPeriod.NIGHT -> R.string.review_mostly_night
    }

private val LocalDateSaver = androidx.compose.runtime.saveable.Saver<LocalDate, String>(
    save = { it.toString() },
    restore = { LocalDate.parse(it) },
)
