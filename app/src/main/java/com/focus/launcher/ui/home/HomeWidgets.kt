package com.focus.launcher.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.annotation.StringRes
import android.os.SystemClock
import androidx.compose.foundation.layout.offset
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.AppEntry
import com.focus.launcher.ui.components.AppPickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.LifecycleStartEffect
import com.focus.launcher.service.MediaListener
import com.focus.launcher.ui.components.hasColourGlyphs
import com.focus.launcher.ui.components.monochrome
import com.focus.launcher.util.Perms
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
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
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.components.press
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.appLocale
import com.focus.launcher.util.formatDuration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marks what the current tip is about: a thin outline in the text colour, there for as long as the
 * tip is. Still, not pulsing: nothing on the home screen moves by itself.
 */
fun Modifier.tipTarget(on: Boolean, color: Color): Modifier = if (on) border(1.dp, color, RoundedCornerShape(12.dp)) else this

/**
 * The app's language (see [appLocale]), read so that a language change recomposes whatever
 * formats text with it.
 */
@Composable
fun currentLocale(): Locale = LocalResources.current.appLocale()

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

/** A date pattern from the resources (home_format_*), in the app's language. */
@Composable
private fun rememberDateFormat(@StringRes pattern: Int): DateTimeFormatter {
    val text = stringResource(pattern)
    val locale = currentLocale()
    return remember(text, locale) { DateTimeFormatter.ofPattern(text, locale) }
}

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
    val batteryText = batteryLine(LocalResources.current, battery, separator)
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
                    T(now.format(rememberDateFormat(R.string.home_format_date_short)), size = if (compact) 13.sp else 14.sp, color = c.dim, maxLines = 1)
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
                now.format(rememberDateFormat(R.string.home_format_date_long)).takeIf { settings.showDate },
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
                T(now.format(rememberDateFormat(R.string.home_format_date_short)), size = 14.sp, color = c.dim, maxLines = 1)
            }
            if (showBattery) {
                VSpace(4.dp)
                val urgent = battery.percent <= 15 && !battery.charging
                T(batteryLine(LocalResources.current, battery, "  ·  "), size = 12.sp, color = if (urgent) c.fg else c.faint, maxLines = 1)
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
        T(stringResource(R.string.home_screen_time_title), size = 13.sp, color = c.dim, maxLines = 1)
        VSpace(2.dp)
        if (hasAccess) {
            val total = today?.total ?: 0L
            T(formatDuration(LocalResources.current, total), size = 28.sp, weight = FontWeight.Light, maxLines = 1)
            T(stringResource(R.string.home_share_of_today, total * 100 / (24 * DayUsage.HOUR_MS)), size = 13.sp, color = c.dim, maxLines = 1)
        } else {
            T(stringResource(R.string.home_allow_usage_access), size = 14.sp, color = c.dim, maxLines = 2, lineHeight = 20.sp)
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
    val res = LocalResources.current
    Column(
        Modifier
            .fillMaxWidth()
            .press(onLongClick = onLongPress, onClick = if (hasAccess) onClick else onRequestAccess)
            .padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        when {
            !hasAccess -> T(stringResource(R.string.home_calendar_show_events), size = 14.sp, color = c.dim, maxLines = 2, lineHeight = 20.sp)
            events.isEmpty() -> T(stringResource(R.string.home_calendar_empty), size = 14.sp, color = c.dim, maxLines = 2, lineHeight = 20.sp)
            else -> events.take(maxEvents).forEachIndexed { i, event ->
                if (i > 0) VSpace(8.dp)
                T(eventWhen(res, event, today, use24h), size = 12.sp, color = c.dim, maxLines = 1)
                T(event.title, size = 15.sp, maxLines = 1)
            }
        }
    }
}

/** "85%", or with what it means after [separator]: "85%  ·  charging", "12%  ·  low". */
private fun batteryLine(res: Resources, battery: BatteryState, separator: String): String {
    val level = res.getString(R.string.home_battery_level, battery.percent)
    return when {
        battery.charging -> level + separator + res.getString(R.string.home_battery_charging)
        battery.percent <= 15 -> level + separator + res.getString(R.string.home_battery_low)
        else -> level
    }
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
        T(stringResource(R.string.home_screen_time_title), size = 15.sp, color = c.dim, maxLines = 1)
        VSpace(3.dp)
        if (hasAccess) {
            val total = today?.total ?: 0L
            T(formatDuration(LocalResources.current, total), size = 24.sp, maxLines = 1)
            // Of all 24 hours, sleep included: the same yardstick every day, at any time of day.
            T(stringResource(R.string.home_share_of_today, total * 100 / (24 * DayUsage.HOUR_MS)), size = 13.sp, color = c.dim, maxLines = 1)
        } else {
            T(stringResource(R.string.home_allow_usage_access), size = 14.sp, color = c.dim, maxLines = 1)
        }
    }
}

/** A data class on purpose: a player that reports the same thing again changes no state, so nothing redraws. */
internal data class NowPlaying(
    val controller: MediaController,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    /** Length of the song in ms; 0 when the player does not say (radio, a live stream). */
    val duration: Long = 0L,
    /** Carried along unchanged by [copy], so a new position report leaves this object equal to the last one. */
    val progress: Progress = Progress(),
) {
    fun positionNow(): Long =
        (if (playing) progress.position + ((SystemClock.elapsedRealtime() - progress.at) * progress.speed).toLong() else progress.position)
            .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
}

/**
 * Where the song was ([position], ms) at [at] (elapsedRealtime) and how fast it moves; the player
 * reports this, the clock does the rest. Plain fields and not state on purpose: players report
 * their position several times a second, and as state every report recomposed the section
 * (measured: 6 to 8 frames a second on an otherwise still home screen). The once-a-second reader
 * in [ProgressText] is the only thing that looks here.
 */
internal class Progress {
    var position = 0L
    var at = 0L
    var speed = 1f
}

/**
 * What the music section knows, held where the home screen can read it too: with "hide when
 * nothing is playing", the section, the line above it and its share of the height all depend on
 * whether there is music.
 */
@Stable
internal class MusicState {
    /** Notification access ([MediaListener]): there is a player to talk to. Without it the audio system's word has to do. */
    var hasAccess by mutableStateOf(false)
    var now by mutableStateOf<NowPlaying?>(null)
        private set
    private var audioPlaying by mutableStateOf(false)

    /** See [lingerUntil]; 0 = not lingering. */
    var lingerUntil by mutableLongStateOf(0L)

    /** Derived, so that a new song title does not recompose those who only ask whether there is music. */
    val playing by derivedStateOf { if (hasAccess) now?.playing == true else audioPlaying }

    /** Stopped a moment ago, in plain sight: still here, so that play is one tap away. */
    val lingering get() = lingerUntil != 0L

    /** [live] = seen happening, by callback; false = found this way when the home screen came into sight. */
    fun report(now: NowPlaying?, live: Boolean) = track(live) { this.now = now }

    fun reportAudio(playing: Boolean, live: Boolean) = track(live) { audioPlaying = playing }

    private inline fun track(live: Boolean, change: () -> Unit) {
        val before = playing
        change()
        lingerUntil = lingerUntil(before, playing, live, SystemClock.elapsedRealtime(), lingerUntil)
    }
}

/** Music, not a key click or a notification sound. Apps without special rights are only told about players that are sounding. */
private fun List<AudioPlaybackConfiguration>?.hasMedia(): Boolean =
    this?.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA || it.audioAttributes.usage == AudioAttributes.USAGE_UNKNOWN } == true

/**
 * Follows the music while the home screen is visible and only then; everything is pushed by the
 * system, nothing polls. With notification access that is the active player (the song, its state);
 * without it, the audio system saying whether media is sounding. [enabled] = the section is
 * switched on at all.
 */
@Composable
internal fun rememberMusicState(enabled: Boolean, resumeCount: Int): MusicState {
    val context = LocalContext.current
    val state = remember { MusicState() }
    val hasAccess = remember(resumeCount, enabled) { enabled && MediaListener.hasAccess(context) }
    LifecycleStartEffect(enabled, hasAccess) {
        state.hasAccess = hasAccess
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val component = MediaListener.component(context)
        var watched: MediaController? = null
        val callback = object : MediaController.Callback() {
            // Players report their position every second or so. Take what the callback hands over
            // instead of fetching everything again (the metadata carries the album art).
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                state.report(state.now?.copy(title = metadata.title(), artist = metadata.artist(), duration = metadata.duration()), live = true)
            }

            override fun onPlaybackStateChanged(playback: PlaybackState?) {
                state.report(state.now?.withState(playback), live = true)
            }

            override fun onSessionDestroyed() = state.report(null, live = true)
        }
        fun watch(controllers: List<MediaController>?, live: Boolean) {
            watched?.unregisterCallback(callback)
            // The system lists the session that would receive a media key first.
            watched = controllers?.firstOrNull()?.also { it.registerCallback(callback) }
            state.report(watched?.read(), live)
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it, live = true) }
        val audioCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = state.reportAudio(configs.hasMedia(), live = true)
        }
        var listening = false
        if (hasAccess && sessions != null) {
            try {
                sessions.addOnActiveSessionsChangedListener(listener, component)
                watch(sessions.getActiveSessions(component), live = false)
            } catch (_: SecurityException) {
                state.report(null, live = false)
            }
        } else {
            state.report(null, live = false)
            if (enabled && audio != null) {
                audio.registerAudioPlaybackCallback(audioCallback, Handler(Looper.getMainLooper()))
                listening = true
            }
        }
        state.reportAudio(listening && audio?.activePlaybackConfigurations.hasMedia(), live = false)
        onStopOrDispose {
            watched?.unregisterCallback(callback)
            sessions?.removeOnActiveSessionsChangedListener(listener)
            if (listening) audio?.unregisterAudioPlaybackCallback(audioCallback)
        }
    }
    // The minute after the music stopped runs out on its own, whether or not anybody is looking.
    val until = state.lingerUntil
    LaunchedEffect(until) {
        if (until != 0L) {
            delay((until - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (state.lingerUntil == until) state.lingerUntil = 0L
        }
    }
    return state
}

private fun MediaMetadata?.title(): String? = this?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }

private fun MediaMetadata?.artist(): String? =
    (this?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: this?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }

private fun MediaMetadata?.duration(): Long = this?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L

private fun NowPlaying.withState(state: PlaybackState?): NowPlaying {
    progress.position = state?.position?.coerceAtLeast(0L) ?: 0L
    progress.at = state?.lastPositionUpdateTime ?: 0L
    progress.speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
    return copy(playing = state?.state == PlaybackState.STATE_PLAYING)
}

private fun MediaController.read(): NowPlaying {
    val data = metadata
    return NowPlaying(this, data.title(), data.artist(), playing = false, duration = data.duration()).withState(playbackState)
}

/** "1:05", "1:02:40". */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.US, s / 60, s % 60)
}

/**
 * "1:02 / 2:00": played, then the song's length. Ticks once a second, and only while something plays and the
 * home screen is showing; paused, it is worked out once and stands still.
 */
@Composable
private fun ProgressText(now: NowPlaying?, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val text by produceState<String?>(null, now, owner) {
        fun read() = now?.let { n ->
            val at = n.positionNow()
            if (n.duration > 0) clock(at) + " / " + clock(n.duration) else if (at > 0) clock(at) else null
        }
        value = read()
        if (now?.playing == true) owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = read()
                delay(1000)
            }
        }
    }
    text?.let { T(it, modifier, size = 12.sp, color = LocalFocusColors.current.faint, maxLines = 1) }
}

/**
 * Music as a section of the home screen: what is playing, and previous · play or pause · next as
 * the three buttons every player has. With notification access the section talks to the active
 * player and knows the song; without it the buttons still work, as media keys that Android hands
 * to the last-used player, and one line offers the access that would bring the song's name.
 *
 * A tap on the section opens whatever is playing, or else [onOpenDefault] (the music app the user
 * chose); a long-press is [onChoose], like every other thing on the home screen that can be set.
 */
@Composable
internal fun MusicSection(state: MusicState, onOpenDefault: () -> Unit, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val audio = context.getSystemService(AudioManager::class.java)
    val hasAccess = state.hasAccess
    val now = state.now
    val playing = state.playing

    // What the key did is heard from the system: the player's session, or the audio callback.
    fun key(code: Int) {
        audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    val controls = now?.controller?.transportControls

    val open = { now?.let { openPlayer(context, it.controller) } ?: onOpenDefault() }
    Column(modifier.fillMaxWidth().press(onLongClick = onChoose, onClick = open).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(stringResource(R.string.home_music_title), Modifier.weight(1f), color = c.fg)
            // Each button is a 48dp square with its sign in the middle: Android widens a smaller
            // touch target to 48dp without showing it, so the glow sat off to one side of where
            // the finger was. The row is pulled right by the last square's margin, so the "next"
            // sign itself ends where the lines end.
            Row(Modifier.offset(x = 17.dp)) {
                MediaButton(Glyph.PREVIOUS, 14.dp, c.dim) { controls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                MediaButton(if (playing) Glyph.PAUSE else Glyph.PLAY, 17.dp, c.fg) {
                    if (controls == null) key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else if (playing) controls.pause() else controls.play()
                }
                MediaButton(Glyph.NEXT, 14.dp, c.dim) { controls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT) }
            }
        }
        when {
            !hasAccess -> T(
                stringResource(R.string.home_music_show_song),
                Modifier.press { Perms.start(context, Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.padding(vertical = 3.dp),
                size = 14.sp, color = c.dim, maxLines = 1,
            )
            now?.title == null -> T(stringResource(R.string.home_music_nothing_playing), Modifier.padding(vertical = 3.dp), size = 14.sp, color = c.dim, maxLines = 1)
            else -> Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
                val line = listOfNotNull(now.title, now.artist).joinToString("  ·  ")
                // A title too long for the row ends in "…", a little short of the time. (A sliding
                // title was tried and dropped: it draws every frame for as long as it moves.)
                T(line, Modifier.weight(1f).then(if (hasColourGlyphs(line)) Modifier.monochrome() else Modifier), size = 14.sp, color = c.dim, maxLines = 1)
                // Played / length, in the corner where players put it. A composable of its own: it
                // changes once a second, and nothing else in the section should recompose with it.
                ProgressText(now, Modifier.padding(start = 17.dp))
            }
        }
    }
}

/**
 * A few lines of your own, kept by Focus and always in sight: a tap edits them then and there.
 * The notes app, if one was chosen, is the word at the right of the title ([appLabel], [onOpenApp]):
 * what it holds lives on its own servers, so Focus cannot show it, only open it. A long-press
 * anywhere offers the settings of the section.
 */
@Composable
fun NoteSection(note: String, appLabel: String?, maxLines: Int, onEdit: () -> Unit, onOpenApp: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    Column(modifier.fillMaxWidth().press(onLongClick = onLongClick, onClick = onEdit).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(stringResource(R.string.home_note_title), Modifier.weight(1f).padding(vertical = 10.dp), color = c.fg)
            if (appLabel != null) {
                T("$appLabel  →", Modifier.press(onLongClick = onLongClick, onClick = onOpenApp).padding(start = 14.dp, top = 10.dp, bottom = 10.dp), size = 13.sp, color = c.dim, maxLines = 1)
            }
        }
        if (note.isBlank()) T(stringResource(R.string.home_note_empty), size = 14.sp, color = c.dim, maxLines = 1)
        else T(note, if (hasColourGlyphs(note)) Modifier.monochrome() else Modifier, size = 14.sp, color = c.dim, maxLines = maxLines, lineHeight = 20.sp)
        VSpace(8.dp)
    }
}

@Composable
private fun MediaButton(glyph: Glyph, side: Dp, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).press(onClick = onClick), contentAlignment = Alignment.Center) { MediaGlyph(glyph, side, color) }
}

/**
 * The music app a tap on the section opens. Only players are listed (see
 * [com.focus.launcher.data.AppRepository.musicPackages]); "All apps…" is there for the one that
 * declares nothing.
 */
@Composable
fun MusicAppPicker(apps: List<AppEntry>, subtitle: String?, onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    var players by remember { mutableStateOf<Set<String>?>(null) }
    var all by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { players = withContext(Dispatchers.IO) { Graph.apps.musicPackages() } }
    val found = players
    AppPickerDialog(
        title = stringResource(R.string.home_music_app),
        subtitle = subtitle,
        apps = if (all || found == null) (if (all) apps else emptyList()) else apps.filter { it.packageName in found },
        onDismiss = onDismiss,
        more = if (all || found == null) null else stringResource(R.string.home_all_apps_more) to { all = true },
        onPick = onPick,
    )
}

/** The only drawn signs besides the work badge, because words make poor buttons here. */
private enum class Glyph(@StringRes val says: Int) {
    PREVIOUS(R.string.home_media_previous), PLAY(R.string.home_media_play), PAUSE(R.string.home_media_pause), NEXT(R.string.home_media_next),
}

/**
 * Play, pause, previous, next as the plain shapes they are everywhere: a triangle, two bars, a
 * triangle against a bar. Drawn, like the work badge, so they stay the text's colour; the Unicode
 * characters for them turn into colour emoji on many phones.
 */
@Composable
private fun MediaGlyph(glyph: Glyph, side: Dp, color: Color, modifier: Modifier = Modifier) {
    val says = stringResource(glyph.says)
    Canvas(modifier.size(side).semantics { contentDescription = says }) {
        val w = size.width
        val h = size.height
        fun triangle(from: Float, to: Float) = drawPath(
            Path().apply {
                moveTo(from, 0f)
                lineTo(to, h / 2)
                lineTo(from, h)
                close()
            },
            color,
        )
        val bar = w * 0.16f
        when (glyph) {
            Glyph.PLAY -> triangle(w * 0.12f, w)
            Glyph.PAUSE -> {
                drawRect(color, Offset(w * 0.14f, 0f), Size(w * 0.26f, h))
                drawRect(color, Offset(w * 0.60f, 0f), Size(w * 0.26f, h))
            }
            Glyph.NEXT -> {
                triangle(0f, w - bar - w * 0.06f)
                drawRect(color, Offset(w - bar, 0f), Size(bar, h))
            }
            Glyph.PREVIOUS -> {
                drawRect(color, Offset(0f, 0f), Size(bar, h))
                triangle(w, bar + w * 0.06f)
            }
        }
    }
}

/** The player's own "now playing" screen if it offers one, else just the player. */
private fun openPlayer(context: Context, controller: MediaController) {
    try {
        controller.sessionActivity?.send() ?: context.packageManager.getLaunchIntentForPackage(controller.packageName)?.let { Perms.start(context, it) }
    } catch (_: Exception) {
    }
}

/**
 * A short agenda from one calendar: the next few events, as text. The Mon-Sun strip with today
 * marked is optional and off by default, since the clock above already carries the date.
 */
@Composable
fun CalendarWidget(
    today: LocalDate,
    mondayStart: Boolean,
    events: List<CalEvent>,
    hasAccess: Boolean,
    use24h: Boolean,
    onClick: () -> Unit,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
    maxEvents: Int = 3,
    calendarName: String? = null,
    showWeekStrip: Boolean = false,
) {
    val c = LocalFocusColors.current
    val res = LocalResources.current
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Label(stringResource(R.string.home_calendar_title), Modifier.weight(1f), color = c.fg)
            if (calendarName != null && hasAccess) T(calendarName, size = 12.sp, color = c.faint, maxLines = 1)
        }
        VSpace(10.dp)
        if (showWeekStrip) {
            WeekStrip(today, mondayStart)
            VSpace(10.dp)
        }
        when {
            !hasAccess -> T(stringResource(R.string.home_calendar_show_events), Modifier.clickable(onClick = onRequestAccess).padding(vertical = 4.dp), size = 14.sp, color = c.dim)
            events.isEmpty() -> T(stringResource(R.string.home_calendar_empty), size = 14.sp, color = c.dim)
            else -> for (event in events.take(maxEvents)) {
                Row(Modifier.padding(vertical = 3.dp)) {
                    T(eventWhen(res, event, today, use24h), Modifier.width(118.dp), size = 14.sp, color = c.dim, maxLines = 1)
                    T(event.title, Modifier.weight(1f), size = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(today: LocalDate, mondayStart: Boolean) {
    val c = LocalFocusColors.current
    val locale = currentLocale()
    val weekStart = com.focus.launcher.data.WeekSummary.weekStartOf(today, mondayStart)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0L..6L) {
            val day = weekStart.plusDays(i)
            val isToday = day == today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(day.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, locale), size = 11.sp, color = if (isToday) c.fg else c.faint, maxLines = 1)
                VSpace(5.dp)
                Box(
                    Modifier.size(30.dp).then(if (isToday) Modifier.background(c.fg) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    T(
                        day.dayOfMonth.toString(), size = 14.sp,
                        color = when {
                            isToday -> c.bg
                            day.isBefore(today) -> c.faint
                            else -> c.fg
                        },
                        weight = if (isToday) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun eventWhen(res: Resources, event: CalEvent, today: LocalDate, use24h: Boolean): String {
    val locale = res.appLocale()
    val date = event.date()
    val day = when (date) {
        today -> res.getString(R.string.home_event_today)
        today.plusDays(1) -> res.getString(R.string.home_event_tomorrow)
        else -> date.format(DateTimeFormatter.ofPattern(res.getString(R.string.home_format_event_day), locale))
    }
    if (event.allDay) return res.getString(R.string.home_event_all_day, day)
    val time = Instant.ofEpochMilli(event.begin).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else res.getString(R.string.home_format_time_12h), locale))
    return res.getString(R.string.home_event_day_time, day, time)
}
