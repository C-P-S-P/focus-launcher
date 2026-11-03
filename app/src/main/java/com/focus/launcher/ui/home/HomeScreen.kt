package com.focus.launcher.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.focus.launcher.data.Tip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.ui.components.FocusDialog
import com.focus.launcher.ui.components.MenuRow
import com.focus.launcher.ui.components.Hairline
import com.focus.launcher.ui.components.TextInputDialog
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.focus.launcher.Graph
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.CalEvent
import com.focus.launcher.data.CalendarInfo
import com.focus.launcher.data.CalendarRepository
import com.focus.launcher.data.ClockStyle
import com.focus.launcher.data.DayUsage
import com.focus.launcher.data.FontChoice
import com.focus.launcher.data.SHORTCUT_CAMERA
import com.focus.launcher.data.SHORTCUT_PHONE
import com.focus.launcher.data.Settings
import com.focus.launcher.data.SplitSide
import com.focus.launcher.data.TAP_ALARMS
import com.focus.launcher.data.TAP_BATTERY
import com.focus.launcher.data.TAP_CALENDAR
import com.focus.launcher.data.TAP_NOTHING
import com.focus.launcher.data.TAP_SCREEN_TIME
import com.focus.launcher.data.TimeFormat
import com.focus.launcher.service.FocusAccessibilityService
import com.focus.launcher.ui.components.AppPickerDialog
import com.focus.launcher.ui.components.ChoiceDialog
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.components.WorkBadge
import com.focus.launcher.ui.components.hasColourGlyphs
import com.focus.launcher.ui.components.monochrome
import com.focus.launcher.ui.components.press
import com.focus.launcher.ui.launchOptions
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.Perms
import java.time.LocalDate

/**
 * Page one of the launcher. Top to bottom: the clock, today's screen time in plain words under it,
 * the optional calendar section, up to five fast apps, and the two corner shortcuts. Text only.
 *
 * Gestures on empty space: long-press opens settings, swipe down pulls the notification shade,
 * swipe up jumps to search, double-tap locks the phone. Swiping left (handled by the pager that
 * hosts this page) opens the app drawer.
 */
@Composable
fun HomeScreen(
    settings: Settings,
    apps: List<AppEntry>,
    today: DayUsage?,
    usageAccess: Boolean,
    setupIncomplete: Boolean,
    pendingReview: LocalDate?,
    resumeCount: Int,
    onLaunch: (AppEntry) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
    onOpenDrawer: (focusSearch: Boolean) -> Unit,
    onOpenSettings: (route: String?) -> Unit,
    onOpenReview: (week: LocalDate?) -> Unit,
) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val now by rememberNow()
    // The gesture detectors below outlive recompositions, so they must call the latest callbacks.
    val openDrawer by rememberUpdatedState(onOpenDrawer)
    val openSettings by rememberUpdatedState(onOpenSettings)

    // Calendar section data
    var calendarAccess by remember { mutableStateOf(CalendarRepository.hasAccess(context)) }
    var events by remember { mutableStateOf(emptyList<CalEvent>()) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAccess = it }
    var shownCalendar by remember { mutableStateOf<CalendarInfo?>(null) }
    LaunchedEffect(settings.showCalendar, settings.calendarKey, resumeCount, calendarAccess) {
        if (settings.showCalendar) {
            calendarAccess = CalendarRepository.hasAccess(context)
            // Exactly one calendar is shown: the chosen one, else the main one with events coming up.
            val agenda = CalendarRepository.agenda(context, settings.calendarKey)
            shownCalendar = agenda.calendar
            events = agenda.events
        }
    }
    // The agenda is cached; the calendar provider tells us when that cache is no longer true.
    // The observer does no work itself, so a sync in the background costs nothing here.
    DisposableEffect(settings.showCalendar, calendarAccess) {
        if (!settings.showCalendar || !calendarAccess) return@DisposableEffect onDispose { }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = CalendarRepository.invalidate()
        }
        val registered = try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (_: Exception) {
            false
        }
        onDispose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }

    // The music section is there while there is music (and for a minute after it stopped), unless
    // it was asked to stay. Its line and its share of the height come and go with it.
    val music = rememberMusicState(enabled = settings.showMusic, resumeCount = resumeCount)
    val musicVisible = settings.showMusic && (!settings.musicAutoHide || music.playing || music.lingering)

    var editingShortcut by remember { mutableStateOf<Boolean?>(null) } // true = left, false = right
    // Arranging the fast apps in place: which one is lifted, where its top was, how far it has been dragged.
    val rowTops = remember { mutableStateMapOf<String, Float>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragStartTop by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var choosingClockTap by remember { mutableStateOf(false) }
    val tip by Graph.state.tip.collectAsStateWithLifecycle()
    // A tip about something that is not on the screen teaches nothing: pass over it.
    LaunchedEffect(tip, settings.showMusic, settings.showNote, settings.showShortcuts, settings.favorites.size) {
        val absent = (tip == Tip.SECTION_APPS && !settings.showMusic && !settings.showNote) || (tip == Tip.CORNERS && !settings.showShortcuts) ||
            (tip == Tip.FAST_APPS && settings.favorites.size < 2)
        if (absent) Graph.state.nextTip()
    }
    var editingNote by remember { mutableStateOf(false) }
    var choosingMusicApp by remember { mutableStateOf(false) }
    var choosingNoteApp by remember { mutableStateOf(false) }
    var noteMenu by remember { mutableStateOf(false) }
    var editingNoteLink by remember { mutableStateOf(false) }
    val noteApp = remember(settings.noteApp, apps) { apps.firstOrNull { it.key == settings.noteApp } }
    var choosingSplitSide by remember { mutableStateOf(false) }

    // Split clock: the section in its right half is not shown a second time further down. The
    // calendar can only sit there while the calendar section is on; otherwise it is screen time.
    val split = settings.clockStyle == ClockStyle.SPLIT
    val sideCalendar = split && settings.splitSide == SplitSide.CALENDAR && settings.showCalendar
    val sideScreenTime = split && !sideCalendar

    val favorites = remember(settings.favorites, apps) { settings.favorites.mapNotNull { key -> apps.firstOrNull { it.key == key } } }
    // Fast apps whose allowance for today is gone are shown dimmed.
    val spent = remember(favorites, today, settings.appLimits, settings.timersEnabled, settings.socialDefaultMin, settings.gameDefaultMin) {
        favorites.filter { app ->
            val limit = Graph.limits.limitFor(app.packageName, settings) ?: return@filter false
            (today?.perApp?.get(app.packageName) ?: 0L) >= limit.millis && !Graph.limits.hasFreePass(app.packageName)
        }.mapTo(HashSet()) { it.key }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(settings.swipeDownNotifications, settings.swipeUpSearch) {
                val threshold = 64.dp.toPx()
                var dragged = 0f
                var fired = false
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f; fired = false },
                    onVerticalDrag = { _, dy ->
                        dragged += dy
                        if (!fired && dragged > threshold && settings.swipeDownNotifications) {
                            fired = true
                            expandNotifications(context)
                            Graph.state.did(Tip.SWIPE_DOWN)
                        } else if (!fired && dragged < -threshold && settings.swipeUpSearch) {
                            fired = true
                            openDrawer(true)
                            Graph.state.did(Tip.SWIPE_UP)
                        }
                    },
                )
            }
            .pointerInput(settings.doubleTapLock) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        Graph.state.did(Tip.SETTINGS)
                        openSettings(null)
                    },
                    onDoubleTap = if (settings.doubleTapLock) {
                        {
                            Graph.state.did(Tip.DOUBLE_TAP)
                            if (!FocusAccessibilityService.lockScreen()) {
                                Toast.makeText(context, "Turn on the Focus timer service to lock with a double tap", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else null,
                )
            },
    ) {

        // A home screen must never scroll or push its corner shortcuts off the edge. Estimate the
        // height each arrangement needs (constants measured on a real screen) and take the
        // roomiest one that fits, whatever the phone, text size, fast apps and enabled sections.
        // Detail is given up before the clock is: first fewer events, and only then a smaller ring.
        val bars = WindowInsets.systemBars.asPaddingValues()
        val available = (maxHeight - bars.calculateTopPadding() - bars.calculateBottomPadding()).value
        val textScale = LocalDensity.current.fontScale * settings.textScale
        val notices = (if (setupIncomplete) 1 else 0) + (if (pendingReview != null) 1 else 0) + (if (tip != null) 3 else 0) // a framed tip is about three notice lines tall
        val rowCount = favorites.size.coerceAtLeast(2) // the empty-state hint is two lines tall
        val eventCount = events.size
        val preferredRing = (maxHeight * if (settings.showCalendar) 0.25f else 0.29f).coerceIn(140.dp, 236.dp)

        class Fit(val ring: Dp, val textSp: Float, val padDp: Float, val maxEvents: Int)

        // Split clock: the time is as large as its half allows. Digits are about 0.56 em wide in the
        // sans and serif faces and 0.6 em in the monospace one; "AM" / "PM" takes its share first.
        val use24h = when (settings.timeFormat) {
            TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
            TimeFormat.H24 -> true
            TimeFormat.H12 -> false
        }
        val halfWidth = (maxWidth.value - 36f) / 2f - 30f // column padding, then the half's own padding
        val amPmWidth = if (use24h) 0f else 26f * textScale
        val emsWide = if (settings.font == FontChoice.MONO) 3.1f else 2.75f
        val splitTimeSp = ((halfWidth - amPmWidth) / emsWide / textScale).coerceIn(30f, 56f)

        fun heightOf(fit: Fit): Float {
            val splitClock = (splitTimeSp * 1.2f + 42f) * textScale + 16f
            val splitSide = if (sideCalendar) eventCount.coerceIn(1, 2) * 44f * textScale + 16f else 86f * textScale + 16f
            val clock = when (settings.clockStyle) {
                ClockStyle.RING -> fit.ring.value
                ClockStyle.SPLIT -> maxOf(splitClock, splitSide)
                ClockStyle.PLAIN -> 100f * textScale
            }
            val noticeLines = if (notices > 0) 14f + notices * (19f * textScale + 12f) else 0f
            val shownEvents = fit.maxEvents.coerceAtMost(eventCount).coerceAtLeast(1)
            val strip = if (settings.showWeekStrip) 46f + 14f * textScale else 0f
            val calendar = if (settings.showCalendar && !sideCalendar) 22f + 14f * textScale + strip + shownEvents * (18f * textScale + 6f) else 0f
            // Title row as tall as its 48dp buttons, then the song.
            val musicHeight = if (musicVisible) 12f + 48f + 24f * textScale else 0f
            val noteLines = if (settings.note.isBlank()) 1 else fit.maxEvents
            val note = if (settings.showNote) 20f + 14f * textScale + 8f + noteLines * 20f * textScale else 0f
            val sectionCount = listOf(settings.showCalendar && !sideCalendar, musicVisible, settings.showNote).count { it }
            val lines = if (sectionCount > 0) (sectionCount + 1) * 1f else 0f
            val shortcuts = if (settings.showShortcuts) 28f + 20f * textScale else 20f
            val rows = rowCount * (fit.textSp * 1.2f * textScale + fit.padDp * 2)
            val screenTime = if (sideScreenTime) 0f else 25f + 64f * textScale // gap, title, total, share of the day
            return clock + screenTime + noticeLines + calendar + musicHeight + note + lines + rows + shortcuts + 6f + 36f // 36 = air
        }

        val options = listOf(
            Fit(preferredRing, 26f, 10f, maxEvents = 3),
            Fit(preferredRing, 24f, 8f, maxEvents = 3),
            Fit(preferredRing, 24f, 7f, maxEvents = 2),
            Fit(preferredRing * 0.88f, 22f, 6f, maxEvents = 2),
            Fit(132.dp, 22f, 4f, maxEvents = 1),
        )
        val fit = options.firstOrNull { heightOf(it) <= available } ?: options.last()
        val ring = fit.ring.coerceAtLeast(132.dp)
        val favoriteSize = fit.textSp.sp
        val favoritePadding = fit.padDp.dp

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp),
            horizontalAlignment = settings.homeAlign.horizontal(),
        ) {
            // The split clock is a header: it sits near the top. The ring floats lower, as a centrepiece.
            Spacer(Modifier.weight(if (split) 0.35f else 0.9f))

            val clockTap: () -> Unit = { performClockTap(context, settings.clockTap, apps, onLaunch) { onOpenReview(null) } }
            val openScreenTime: () -> Unit = {
                Graph.state.did(Tip.SCREEN_TIME)
                if (usageAccess) onOpenReview(null) else Perms.openUsageAccess(context)
            }
            if (split) {
                SplitClockRow(
                    settings = settings,
                    now = now,
                    timeSize = splitTimeSp.sp,
                    onTap = clockTap,
                    onLongPress = { Graph.state.did(Tip.CLOCK); choosingClockTap = true },
                    highlight = tip == Tip.CLOCK,
                ) {
                    if (sideCalendar) {
                        SplitCalendar(
                            today = now.toLocalDate(),
                            events = events,
                            hasAccess = calendarAccess,
                            use24h = use24h,
                            onClick = { openCalendarApp(context) },
                            onLongPress = { choosingSplitSide = true },
                            onRequestAccess = { askCalendar.launch(Manifest.permission.READ_CALENDAR) },
                        )
                    } else {
                        SplitScreenTime(today, usageAccess, onClick = openScreenTime, onLongPress = { choosingSplitSide = true }, highlight = tip == Tip.SCREEN_TIME)
                    }
                }
            } else {
                HomeClock(
                    settings = settings,
                    now = now,
                    ringSize = ring,
                    onTap = clockTap,
                    onLongPress = { Graph.state.did(Tip.CLOCK); choosingClockTap = true },
                    modifier = (if (settings.clockStyle == ClockStyle.RING) Modifier.align(Alignment.CenterHorizontally) else Modifier).tipTarget(tip == Tip.CLOCK, c.fg),
                )
            }

            if (!sideScreenTime) {
                VSpace(10.dp)
                val ringed = settings.clockStyle == ClockStyle.RING
                ScreenTimeLine(
                    today = today,
                    hasAccess = usageAccess,
                    align = if (ringed) Alignment.CenterHorizontally else settings.homeAlign.horizontal(),
                    onClick = openScreenTime,
                    modifier = (if (ringed) Modifier.align(Alignment.CenterHorizontally) else Modifier).tipTarget(tip == Tip.SCREEN_TIME, c.fg),
                )
            }

            // One-line notices. They disappear as soon as they have been dealt with.
            if (setupIncomplete || pendingReview != null || tip != null) {
                VSpace(14.dp)
                if (pendingReview != null) {
                    Notice("Your weekly review is ready  →", strong = true) { onOpenReview(pendingReview) }
                }
                if (setupIncomplete) {
                    Notice("Finish setting up Focus  →", strong = false) { onOpenSettings("setup") }
                }
                // One thing a new user could not guess, until they have done it once. A tap moves on.
                tip?.let { current ->
                    TipLine(current) {
                        Graph.state.nextTip()
                        if (current == Tip.SECTIONS) onOpenSettings("home")
                    }
                }
            }

            Spacer(Modifier.weight(0.8f))

            // The sections, one under the other as in the owner's sketch: calendar, music, note. No
            // boxes: a line above each and one below the last, the same line the split clock uses.
            val calendarSection = settings.showCalendar && !sideCalendar
            val anySection = calendarSection || musicVisible || settings.showNote
            if (calendarSection) {
                Hairline(Modifier.padding(horizontal = 12.dp), c.faint)
                CalendarWidget(
                    today = now.toLocalDate(),
                    mondayStart = settings.weekStartsMonday,
                    events = events,
                    hasAccess = calendarAccess,
                    use24h = use24h,
                    onClick = { openCalendarApp(context) },
                    onRequestAccess = { askCalendar.launch(Manifest.permission.READ_CALENDAR) },
                    maxEvents = fit.maxEvents,
                    calendarName = shownCalendar?.shortName,
                    showWeekStrip = settings.showWeekStrip,
                )
            }
            if (musicVisible) {
                Hairline(Modifier.padding(horizontal = 12.dp), c.faint)
                MusicSection(
                    music,
                    // Nothing playing: the music app of the user's choice; the first tap asks which.
                    onOpenDefault = { apps.firstOrNull { it.key == settings.musicApp }?.let(onLaunch) ?: run { choosingMusicApp = true } },
                    onChoose = { Graph.state.did(Tip.SECTION_APPS); choosingMusicApp = true },
                    modifier = Modifier.tipTarget(tip == Tip.SECTION_APPS, c.fg),
                )
            }
            if (settings.showNote) {
                Hairline(Modifier.padding(horizontal = 12.dp), c.faint)
                NoteSection(
                    note = settings.note,
                    appLabel = noteApp?.label,
                    maxLines = fit.maxEvents,
                    onEdit = { editingNote = true },
                    // One page of the app, if a link to it was given; the app itself otherwise.
                    onOpenApp = { noteApp?.let { app -> if (settings.noteLink.isBlank() || !openLink(context, settings.noteLink, app.packageName)) onLaunch(app) } },
                    onLongClick = { Graph.state.did(Tip.SECTION_APPS); noteMenu = true },
                    modifier = Modifier.tipTarget(tip == Tip.SECTION_APPS, c.fg),
                )
            }
            if (anySection) Hairline(Modifier.padding(horizontal = 12.dp), c.faint)

            Spacer(Modifier.weight(if (anySection) 0.9f else 0.6f))

            // Fast apps
            if (favorites.isEmpty()) {
                T(
                    "Swipe left for your apps.\nLong-press one to pin it here.",
                    Modifier.fillMaxWidth().clickable { onOpenDrawer(false) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    size = 15.sp, color = c.dim, align = settings.homeAlign.text(), lineHeight = 23.sp,
                )
            } else {
                for (app in favorites) key(app.key) {
                    val lifted = draggingKey == app.key
                    Row(
                        Modifier
                            .zIndex(if (lifted) 1f else 0f)
                            .onPlaced { rowTops[app.key] = it.positionInParent().y }
                            // The lifted row follows the finger however the list reflows under it.
                            // Read while drawing: a drag recomposes nothing.
                            .graphicsLayer {
                                if (lifted) {
                                    translationY = dragStartTop + dragOffset - (rowTops[app.key] ?: dragStartTop)
                                    alpha = 0.85f
                                }
                            }
                            // Hold an app: lift it and drag it into place, like on any home screen.
                            // Let go without moving and the app's menu opens instead.
                            .pointerInput(app.key) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragStartTop = rowTops[app.key] ?: 0f
                                    dragOffset = 0f
                                    draggingKey = app.key
                                    homeDragging.value = true
                                    var moved = 0f
                                    try {
                                        while (true) {
                                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == held.id } ?: break
                                            val released = !change.pressed
                                            dragOffset += change.positionChange().y
                                            moved = maxOf(moved, kotlin.math.abs(dragOffset))
                                            change.consume()
                                            if (released) break
                                            // Changes places with the neighbour whose middle the lifted row's middle has passed.
                                            val order = Graph.settings.value.favorites
                                            val at = order.indexOf(app.key)
                                            val rowH = size.height.toFloat()
                                            val middle = dragStartTop + dragOffset + rowH / 2
                                            val above = order.getOrNull(at - 1)?.let { rowTops[it] }
                                            val below = order.getOrNull(at + 1)?.let { rowTops[it] }
                                            val to = when {
                                                above != null && middle < above + rowH / 2 -> at - 1
                                                below != null && middle > below + rowH / 2 -> at + 1
                                                else -> at
                                            }
                                            if (to != at) {
                                                Graph.settings.update { it.copy(favorites = it.favorites.toMutableList().apply { add(to, removeAt(at)) }) }
                                                Graph.state.did(Tip.FAST_APPS)
                                            }
                                        }
                                    } finally {
                                        draggingKey = null
                                        homeDragging.value = false
                                    }
                                    if (moved < viewConfiguration.touchSlop) onAppMenu(app)
                                }
                            }
                            .press { onLaunch(app) }
                            .padding(vertical = favoritePadding, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(
                            app.label,
                            Modifier.weight(1f, fill = false).then(if (hasColourGlyphs(app.label)) Modifier.monochrome() else Modifier),
                            size = favoriteSize,
                            color = if (app.key in spent) c.faint else c.fg,
                            weight = FontWeight.Normal,
                            maxLines = 1,
                        )
                        // Same marker as in the drawer, sized with the name next to it.
                        if (app.isWorkProfile) WorkBadge(Modifier.padding(start = 10.dp), side = (favoriteSize.value * 0.62f).dp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Corner shortcuts
            if (settings.showShortcuts) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (left in listOf(true, false)) {
                        val spec = if (left) settings.leftShortcut else settings.rightShortcut
                        T(
                            shortcutLabel(spec, apps),
                            Modifier
                                .tipTarget(tip == Tip.CORNERS, c.fg)
                                .press(onLongClick = { Graph.state.did(Tip.CORNERS); editingShortcut = left }) { launchShortcut(context, spec, apps, onLaunch) }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            size = 15.sp, color = c.dim, maxLines = 1,
                        )
                    }
                }
            } else {
                VSpace(20.dp)
            }
            VSpace(6.dp)
        }
    }

    if (choosingClockTap) ClockTapDialog(settings, apps) { choosingClockTap = false }

    val visibleApps = remember(apps, settings.hidden) { apps.filter { it.key !in settings.hidden } }
    if (choosingMusicApp) {
        MusicAppPicker(
            apps = visibleApps,
            subtitle = "Opens when you tap the music section and nothing is playing. Long-press the section to change it.",
            onDismiss = { choosingMusicApp = false },
            onPick = { app ->
                Graph.settings.update { it.copy(musicApp = app.key) }
                onLaunch(app)
            },
        )
    }
    if (choosingNoteApp) {
        AppPickerDialog(
            title = "Notes app",
            subtitle = "Shown as a word next to the note's title; a tap on it opens the app. Your own lines stay where they are.",
            apps = visibleApps,
            onDismiss = { choosingNoteApp = false },
            leading = listOf("None" to { Graph.settings.update { it.copy(noteApp = "", noteLink = "") } }),
            onPick = { app -> Graph.settings.update { it.copy(noteApp = app.key, noteLink = "") } },
        )
    }
    if (noteMenu) {
        FocusDialog({ noteMenu = false }, title = "Note") {
            MenuRow("Notes app", detail = noteApp?.label ?: "none") {
                noteMenu = false
                choosingNoteApp = true
            }
            if (noteApp != null) {
                MenuRow("Open one page instead of the app", detail = if (settings.noteLink.isBlank()) null else "set") {
                    noteMenu = false
                    editingNoteLink = true
                }
            }
        }
    }

    if (editingNoteLink) {
        TextInputDialog(
            title = "Link to the page",
            initial = settings.noteLink,
            placeholder = "https://…",
            subtitle = "In ${noteApp?.label ?: "your notes app"}, open the page, choose Share or Copy link, and paste it here. Leave it empty to open the app itself.",
            onDismiss = { editingNoteLink = false },
        ) { link -> Graph.settings.update { it.copy(noteLink = link) } }
    }

    if (editingNote) {
        TextInputDialog(
            title = "Note",
            initial = settings.note,
            placeholder = "Write something",
            onDismiss = { editingNote = false },
            multiline = true,
        ) { text -> Graph.settings.update { it.copy(note = text) } }
    }
    if (choosingSplitSide) {
        ChoiceDialog(
            "Next to the clock",
            SplitSide.entries.map { it to it.label },
            if (sideCalendar) SplitSide.CALENDAR else SplitSide.SCREEN_TIME,
            { choosingSplitSide = false },
        ) { side ->
            // Choosing the calendar is also the moment to switch its section on and to ask for access.
            Graph.settings.update { it.copy(splitSide = side, showCalendar = it.showCalendar || side == SplitSide.CALENDAR) }
            if (side == SplitSide.CALENDAR && !CalendarRepository.hasAccess(context)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    editingShortcut?.let { left ->
        val visible = remember(apps, settings.hidden) { apps.filter { it.key !in settings.hidden } }
        AppPickerDialog(
            title = if (left) "Left shortcut" else "Right shortcut",
            subtitle = "Opens from the bottom corner of the home screen.",
            apps = visible,
            onDismiss = { editingShortcut = null },
            leading = listOf(
                "Phone" to { setShortcut(left, SHORTCUT_PHONE) },
                "Camera" to { setShortcut(left, SHORTCUT_CAMERA) },
            ),
            onPick = { setShortcut(left, it.key) },
        )
    }
}

/**
 * A tip, framed so that it cannot be mistaken for part of the home screen: the gesture in full brightness, what it does next to it, quieter. Short enough for one
 * line; if a large text size makes it longer it wraps, it is never cut off. A tap skips it.
 */
@Composable
private fun TipLine(tip: Tip, onClick: () -> Unit) {
    val c = LocalFocusColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .border(1.dp, c.fg, RoundedCornerShape(12.dp))
            .press(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        T(
            "TIP ${tip.ordinal + 1} / ${Tip.entries.size}",
            Modifier.background(c.fg).padding(horizontal = 6.dp, vertical = 1.dp),
            size = 10.sp, color = c.bg, weight = FontWeight.Medium, letterSpacing = 1.2.sp, maxLines = 1,
        )
        VSpace(5.dp)
        Row(verticalAlignment = Alignment.Top) {
            T(tip.gesture, size = 16.sp, weight = FontWeight.Medium, maxLines = 1)
            T("  →  ", size = 16.sp, color = c.dim, maxLines = 1)
            T(tip.result, Modifier.weight(1f, fill = false), size = 16.sp, color = c.dim)
        }
    }
}

@Composable
private fun Notice(text: String, strong: Boolean, lines: Int = 1, onClick: () -> Unit) {
    val c = LocalFocusColors.current
    T(
        text,
        Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        size = 14.sp,
        color = if (strong) c.fg else c.dim,
        weight = if (strong) FontWeight.Medium else FontWeight.Normal,
        maxLines = lines,
        lineHeight = 20.sp,
    )
}

private fun setShortcut(left: Boolean, spec: String) {
    Graph.settings.update { if (left) it.copy(leftShortcut = spec) else it.copy(rightShortcut = spec) }
}

fun shortcutLabel(spec: String, apps: List<AppEntry>): String = when (spec) {
    SHORTCUT_PHONE -> "Phone"
    SHORTCUT_CAMERA -> "Camera"
    else -> apps.firstOrNull { it.key == spec }?.label ?: "Not set"
}

private fun launchShortcut(context: Context, spec: String, apps: List<AppEntry>, onLaunch: (AppEntry) -> Unit) {
    when (spec) {
        SHORTCUT_PHONE -> Perms.start(context, Intent(Intent.ACTION_DIAL), options = launchOptions(context))
        SHORTCUT_CAMERA -> Perms.start(
            context,
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            options = launchOptions(context),
        )
        else -> apps.firstOrNull { it.key == spec }?.let(onLaunch)
    }
}

/** Opens [link] in [packageName] if that app takes it, else in whatever does. False when nothing could. */
private fun openLink(context: Context, link: String, packageName: String): Boolean {
    val uri = link.trim().toUri()
    if (uri.scheme.isNullOrEmpty()) return false
    return Perms.start(
        context,
        Intent(Intent.ACTION_VIEW, uri).setPackage(packageName),
        Intent(Intent.ACTION_VIEW, uri),
        options = launchOptions(context),
    )
}

/** True while a fast app is lifted. The pager and the web-search swipe stand still meanwhile: a drag is up or down only. */
internal val homeDragging = mutableStateOf(false)

private fun openCalendarApp(context: Context) {
    Perms.start(
        context,
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
        // Calendars that do not register the category still open on "show me this moment".
        Intent(Intent.ACTION_VIEW, "content://com.android.calendar/time/${System.currentTimeMillis()}".toUri()),
        options = launchOptions(context),
    )
}

/** Name of a clock tap action, for settings. */
fun clockTapLabel(spec: String, apps: List<AppEntry>): String = when (spec) {
    TAP_ALARMS -> "Alarms"
    TAP_CALENDAR -> "Calendar"
    TAP_SCREEN_TIME -> "Screen time"
    TAP_BATTERY -> "Battery"
    TAP_NOTHING -> "Nothing"
    else -> apps.firstOrNull { it.key == spec }?.label ?: "Not set"
}

private fun performClockTap(
    context: Context,
    spec: String,
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onOpenScreenTime: () -> Unit,
) {
    when (spec) {
        TAP_ALARMS -> Perms.start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), options = launchOptions(context))
        TAP_CALENDAR -> openCalendarApp(context)
        TAP_SCREEN_TIME -> onOpenScreenTime()
        TAP_BATTERY -> Perms.start(
            context,
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
            options = launchOptions(context),
        )
        TAP_NOTHING -> Unit
        else -> apps.firstOrNull { it.key == spec }?.let(onLaunch)
    }
}

/** Pulls the notification shade down: via the timer service when it is on, else the status-bar service. */
@SuppressLint("WrongConstant", "PrivateApi")
private fun expandNotifications(context: Context) {
    if (FocusAccessibilityService.openNotifications()) return
    try {
        val statusBar = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(statusBar)
    } catch (_: Exception) {
    }
}
