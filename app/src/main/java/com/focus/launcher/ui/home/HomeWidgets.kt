package com.focus.launcher.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.focus.launcher.data.CalEvent
import com.focus.launcher.data.ClockStyle
import com.focus.launcher.data.DayUsage
import com.focus.launcher.data.HomeAlign
import com.focus.launcher.data.RingMode
import com.focus.launcher.data.Settings
import com.focus.launcher.data.TimeFormat
import com.focus.launcher.ui.components.HSpace
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.components.press
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.formatDuration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marks what the current tip is about: a thin outline in the text colour, there for as long as the
 * tip is. Still, not pulsing: nothing on the home screen moves by itself.
 */
fun Modifier.tipTarget(on: Boolean, color: Color): Modifier = if (on) border(1.dp, color, RoundedCornerShape(12.dp)) else this

/** The user's locale, read so that a language change recomposes whatever formats text with it. */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** The current time, refreshed on each minute boundary and only while the screen is visible. */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val owner = LocalLifecycleOwner.current
    return produceState(LocalDateTime.now(), owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalDateTime.now()
                delay(60_000 - System.currentTimeMillis() % 60_000 + 40)
            }
        }
    }
}

fun HomeAlign.horizontal(): Alignment.Horizontal = when (this) {
    HomeAlign.LEFT -> Alignment.Start
    HomeAlign.CENTER -> Alignment.CenterHorizontally
    HomeAlign.RIGHT -> Alignment.End
}

fun HomeAlign.text(): TextAlign = when (this) {
    HomeAlign.LEFT -> TextAlign.Start
    HomeAlign.CENTER -> TextAlign.Center
    HomeAlign.RIGHT -> TextAlign.End
}

private val DATE_LONG = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val DATE_SHORT = DateTimeFormatter.ofPattern("EEE, d MMM")

data class BatteryState(val percent: Int, val charging: Boolean)

private fun readBattery(context: Context, update: Intent?): BatteryState {
    val intent = update ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return BatteryState(
        percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** Battery level and charging state, followed only while the screen it is shown on is visible. */
@Composable
fun rememberBattery(): State<BatteryState> {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    return produceState(readBattery(context, null), context, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    value = readBattery(c, intent)
                }
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)?.let {
                value = readBattery(context, it)
            }
            try {
                awaitCancellation()
            } finally {
                context.unregisterReceiver(receiver)
            }
        }
    }
}

/**
 * Date and time. In ring style the bright arc is either the battery (a full circle is 100%) or
 * the part of today that has already passed, starting at the top. The whole clock is one touch
 * target that lights up like everything else: a tap runs the action chosen by the user (usually
 * "open this app"), a long-press lets them choose it.
 */
@Composable
fun HomeClock(
    settings: Settings,
    now: LocalDateTime,
    ringSize: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val locale = currentLocale()
    val use24h = when (settings.timeFormat) {
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
    }
    val time = now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm"))
    val amPm = if (use24h) null else now.format(DateTimeFormatter.ofPattern("a", locale)).uppercase()

    val showBattery = settings.ringMode == RingMode.BATTERY
    val battery by rememberBattery()
    // The lowest line sits where the circle is already narrowing, so a small ring gets short text.
    val compact = settings.clockStyle == ClockStyle.RING && ringSize < 172.dp
    val separator = if (compact) " " else "  ·  "
    val batteryText = batteryLine(battery, separator)
    val tap = Modifier.combinedClickable(onLongClick = onLongPress, onClick = onTap)

    if (settings.clockStyle == ClockStyle.RING) {
        val target = if (showBattery) battery.percent / 100f else (now.hour * 60 + now.minute) / 1440f
        // Sweeps in from empty the first time, then glides to every new value. The animated value
        // is only read while drawing, so a moving arc never recomposes the clock.
        val fill = remember { Animatable(0f) }
        LaunchedEffect(target) { fill.animateTo(target, tween(durationMillis = 900, easing = FastOutSlowInEasing)) }

        Box(modifier.size(ringSize).clip(CircleShape).then(tap), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2 + 4.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(c.line, 0f, 360f, false, topLeft, arcSize, style = Stroke(1.5.dp.toPx()))
                val sweep = 360f * fill.value.coerceIn(0f, 1f)
                drawArc(c.fg, -90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                // A bead at the end of the arc, so even a nearly empty ring has a clear reading.
                val radius = arcSize.width / 2
                val angle = Math.toRadians((sweep - 90f).toDouble())
                val bead = Offset(size.width / 2 + radius * cos(angle).toFloat(), size.height / 2 + radius * sin(angle).toFloat())
                drawCircle(c.bg, 6.dp.toPx(), bead)
                drawCircle(c.fg, 3.5.dp.toPx(), bead)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T(time, size = (ringSize.value * (if (compact) 0.215f else 0.235f)).sp, weight = FontWeight.Light, maxLines = 1)
                    if (amPm != null) {
                        HSpace(4.dp)
                        T(amPm, Modifier.padding(bottom = (ringSize.value * 0.04f).dp), size = 12.sp, color = c.dim, maxLines = 1)
                    }
                }
                if (settings.showDate) {
                    VSpace(if (compact) 0.dp else 2.dp)
                    T(now.format(DATE_SHORT), size = if (compact) 13.sp else 14.sp, color = c.dim, maxLines = 1)
                }
                if (showBattery) {
                    VSpace(if (compact) 2.dp else 5.dp)
                    val urgent = battery.percent <= 15 && !battery.charging
                    T(batteryText, size = if (compact) 11.sp else 12.sp, color = if (urgent) c.fg else c.faint, maxLines = 1)
                }
            }
        }
    } else {
        Column(modifier.then(tap), horizontalAlignment = settings.homeAlign.horizontal()) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(time, size = 68.sp, weight = FontWeight.Light, maxLines = 1)
                if (amPm != null) {
                    HSpace(6.dp)
                    T(amPm, Modifier.padding(bottom = 14.dp), size = 15.sp, color = c.dim, maxLines = 1)
                }
            }
            val line = listOfNotNull(
                now.format(DATE_LONG).takeIf { settings.showDate },
                batteryText.takeIf { showBattery },
            ).joinToString("   ·   ")
            if (line.isNotEmpty()) T(line, Modifier.padding(vertical = 4.dp), size = 17.sp, color = c.dim, maxLines = 1)
        }
    }
}

/**
 * The split clock: the time on the left, one section on the right, and a single vertical line
 * between them. There is no frame around it; the line is the whole design. Both halves hug it
 * (the clock's text ends at the line, the section's text starts there), so it reads as a spine.
 *
 * Each half is its own touch target and lights up like everything else. Left: tap runs the
 * clock's action, long-press chooses it. Right: whatever [side] does with its taps.
 */
@Composable
fun SplitClockRow(
    settings: Settings,
    now: LocalDateTime,
    timeSize: TextUnit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** Outline the clock half: a tip is pointing at it. */
    highlight: Boolean = false,
    side: @Composable () -> Unit,
) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val locale = currentLocale()
    val use24h = when (settings.timeFormat) {
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
    }
    val time = now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm"))
    val amPm = if (use24h) null else now.format(DateTimeFormatter.ofPattern("a", locale)).uppercase()
    val showBattery = settings.ringMode == RingMode.BATTERY
    val battery by rememberBattery()

    Row(
        modifier
            .fillMaxWidth()
            // The one boundary that is kept: drawn over the row's own height, whichever half is taller.
            .drawBehind {
                val x = size.width / 2
                drawLine(c.faint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .tipTarget(highlight, c.fg)
                .press(onLongClick = onLongPress, onClick = onTap)
                .padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(time, size = timeSize, weight = FontWeight.Light, maxLines = 1)
                if (amPm != null) {
                    HSpace(5.dp)
                    T(amPm, Modifier.padding(bottom = (timeSize.value * 0.17f).dp), size = 12.sp, color = c.dim, maxLines = 1)
                }
            }
            if (settings.showDate) {
                VSpace(2.dp)
                T(now.format(DATE_SHORT), size = 14.sp, color = c.dim, maxLines = 1)
            }
            if (showBattery) {
                VSpace(4.dp)
                val urgent = battery.percent <= 15 && !battery.charging
                T(batteryLine(battery, "  ·  "), size = 12.sp, color = if (urgent) c.fg else c.faint, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f)) { side() }
    }
}

/** Right half of the split clock: today's screen time. */
@Composable
fun SplitScreenTime(today: DayUsage?, hasAccess: Boolean, onClick: () -> Unit, onLongPress: () -> Unit, highlight: Boolean = false) {
    val c = LocalFocusColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .tipTarget(highlight, c.fg)
            .press(onLongClick = onLongPress, onClick = onClick)
            .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        T("Screen Time", size = 13.sp, color = c.dim, maxLines = 1)
        VSpace(2.dp)
        if (hasAccess) {
            val total = today?.total ?: 0L
            T(formatDuration(total), size = 28.sp, weight = FontWeight.Light, maxLines = 1)
            T("${total * 100 / (24 * DayUsage.HOUR_MS)}% of today", size = 13.sp, color = c.dim, maxLines = 1)
        } else {
            T("Allow usage access  →", size = 14.sp, color = c.dim, maxLines = 2, lineHeight = 20.sp)
        }
    }
}

/** Right half of the split clock: the next events of the one calendar, two lines each. */
@Composable
fun SplitCalendar(
    today: LocalDate,
    events: List<CalEvent>,
    hasAccess: Boolean,
    use24h: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRequestAccess: () -> Unit,
    maxEvents: Int = 2,
) {
    val c = LocalFocusColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .press(onLongClick = onLongPress, onClick = if (hasAccess) onClick else onRequestAccess)
            .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        when {
            !hasAccess -> T("Show upcoming events  →", size = 14.sp, color = c.dim, maxLines = 2, lineHeight = 20.sp)
            events.isEmpty() -> T("Nothing in the next 7 days", size = 14.sp, color = c.dim, maxLines = 2, lineHeight = 20.sp)
            else -> events.take(maxEvents).forEachIndexed { i, event ->
                if (i > 0) VSpace(8.dp)
                T(eventWhen(event, today, use24h), size = 12.sp, color = c.dim, maxLines = 1)
                T(event.title, size = 15.sp, maxLines = 1)
            }
        }
    }
}

private fun batteryLine(battery: BatteryState, separator: String): String = when {
    battery.charging -> "${battery.percent}%${separator}charging"
    battery.percent <= 15 -> "${battery.percent}%${separator}low"
    else -> "${battery.percent}%"
}

/**
 * One bar for the whole day: 24 cells, one per hour. The bright part of each cell is the share of
 * that hour spent on the phone, so the total amount of white is today's screen time out of 24h.
 */
@Composable
fun DayBar(perHour: LongArray, modifier: Modifier = Modifier, height: Dp = 12.dp, currentHour: Int? = null) {
    val c = LocalFocusColors.current
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height + 5.dp)) {
        val gap = 2.dp.toPx()
        val cell = (size.width - gap * 23) / 24f
        val barHeight = height.toPx()
        for (h in 0 until 24) {
            val x = h * (cell + gap)
            drawRect(c.line, Offset(x, 0f), Size(cell, barHeight))
            val share = (perHour[h].toFloat() / DayUsage.HOUR_MS).coerceIn(0f, 1f) * grow.value
            if (share > 0f) drawRect(c.fg, Offset(x, 0f), Size((cell * share).coerceAtLeast(1f), barHeight))
            if (h == currentHour) drawRect(c.dim, Offset(x, barHeight + 3.dp.toPx()), Size(cell, 1.5.dp.toPx()))
        }
    }
}

@Composable
fun HourScale(modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (mark in listOf("0", "6", "12", "18", "24")) T(mark, size = 10.sp, color = c.faint, maxLines = 1)
    }
}

/**
 * Today's screen time, said plainly under the clock: a small title, the total in type large
 * enough to read at a glance, and what share of the day's 24 hours that is. A tap opens the review, where the hour-by-hour picture lives.
 */
@Composable
fun ScreenTimeLine(today: DayUsage?, hasAccess: Boolean, align: Alignment.Horizontal, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    Column(modifier.press(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = align) {
        T("Screen Time", size = 15.sp, color = c.dim, maxLines = 1)
        VSpace(3.dp)
        if (hasAccess) {
            val total = today?.total ?: 0L
            T(formatDuration(total), size = 24.sp, maxLines = 1)
            // Of all 24 hours, sleep included: the same yardstick every day, at any time of day.
            T("${total * 100 / (24 * DayUsage.HOUR_MS)}% of today", size = 13.sp, color = c.dim, maxLines = 1)
        } else {
            T("Allow usage access  →", size = 14.sp, color = c.dim, maxLines = 1)
        }
    }
}

internal fun eventWhen(event: CalEvent, today: LocalDate, use24h: Boolean): String {
    val date = event.date()
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d"))
    }
    if (event.allDay) return "$day · all day"
    val time = Instant.ofEpochMilli(event.begin).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))
    return "$day $time"
}
