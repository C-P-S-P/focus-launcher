package com.focus.launcher.ui.settings

import com.focus.launcher.ui.home.MusicAppPicker
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.Graph
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.CALENDAR_ALL
import com.focus.launcher.data.CalendarInfo
import com.focus.launcher.data.CalendarRepository
import com.focus.launcher.data.ClockStyle
import com.focus.launcher.data.FontChoice
import com.focus.launcher.data.HomeAlign
import com.focus.launcher.data.LaunchAnimation
import com.focus.launcher.data.MAX_FAVORITES
import com.focus.launcher.data.RingMode
import com.focus.launcher.data.SHORTCUT_CAMERA
import com.focus.launcher.data.SHORTCUT_PHONE
import com.focus.launcher.data.Settings
import com.focus.launcher.data.SplitSide
import com.focus.launcher.data.TimeFormat
import com.focus.launcher.ui.components.AppPickerDialog
import com.focus.launcher.ui.components.ChoiceDialog
import com.focus.launcher.ui.components.FocusDialog
import com.focus.launcher.ui.components.MenuRow
import com.focus.launcher.ui.components.SettingRow
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.ToggleRow
import com.focus.launcher.ui.home.ClockTapDialog
import com.focus.launcher.ui.home.clockTapLabel
import com.focus.launcher.ui.home.shortcutLabel
import com.focus.launcher.ui.launchApp
import com.focus.launcher.ui.theme.LocalFocusColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun update(transform: (Settings) -> Settings) = Graph.settings.update(transform)

// ---- Home screen -----------------------------------------------------------------------------

private enum class HomeDialog { NONE, CLOCK, SPLIT_SIDE, RING, TAP, TIME_FORMAT, ALIGN, LEFT, RIGHT, CALENDAR, MUSIC_APP, NOTE_APP }

@Composable
internal fun HomePage(settings: Settings, apps: List<AppEntry>, onBack: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(HomeDialog.NONE) }
    val close = { dialog = HomeDialog.NONE }
    val favoriteCount = settings.favorites.count { key -> apps.any { it.key == key } }
    // Switching the calendar section on is also the moment to ask for the permission it needs.
    var calendarGrants by remember { mutableIntStateOf(0) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarGrants++ }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var shownCalendar by remember { mutableStateOf<CalendarInfo?>(null) }
    var calendarCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(settings.showCalendar, settings.calendarKey, calendarGrants) {
        calendars = CalendarRepository.calendars(context)
        calendarCounts = CalendarRepository.upcomingCounts(context, calendars)
        shownCalendar = CalendarRepository.choose(context, settings.calendarKey, calendars)
    }
    // A Work profile exists, yet none of its calendars came through: the organisation says no.
    val workBlocked = remember(calendars) { CalendarRepository.hasWorkProfile(context) && calendars.none { it.work } }

    Page("Home screen", onBack) {
        Section("Clock")
        SettingRow(
            "Style",
            subtitle = when (settings.clockStyle) {
                ClockStyle.SPLIT -> "The clock on the left, one section on the right, a single line between them."
                ClockStyle.RING -> "The clock inside a circle."
                ClockStyle.PLAIN -> "The clock as plain text."
            },
            value = settings.clockStyle.label,
            onClick = { dialog = HomeDialog.CLOCK },
        )
        when (settings.clockStyle) {
            ClockStyle.SPLIT -> SettingRow(
                "Next to the clock",
                subtitle = "What the right half shows. It is not repeated further down. Long-pressing it on the home screen gets you here too.",
                value = (if (settings.splitSide == SplitSide.CALENDAR && settings.showCalendar) SplitSide.CALENDAR else SplitSide.SCREEN_TIME).label,
                onClick = { dialog = HomeDialog.SPLIT_SIDE },
            )
            ClockStyle.RING -> SettingRow(
                "Ring shows",
                subtitle = if (settings.ringMode == RingMode.BATTERY) "A full circle is a full battery." else "The circle fills up as the day passes.",
                value = settings.ringMode.label,
                onClick = { dialog = HomeDialog.RING },
            )
            ClockStyle.PLAIN -> Unit
        }
        // Without a ring the same stored choice decides one thing only: whether the battery is written out.
        if (settings.clockStyle != ClockStyle.RING) {
            ToggleRow("Show the battery level", settings.ringMode == RingMode.BATTERY) { v ->
                update { it.copy(ringMode = if (v) RingMode.BATTERY else RingMode.DAY) }
            }
        }
        SettingRow("Tap on the clock", subtitle = "Opens an app of your choice, or alarms, calendar, screen time. Long-pressing the clock gets you here too.", value = clockTapLabel(settings.clockTap, apps), onClick = { dialog = HomeDialog.TAP })
        SettingRow("Time format", value = settings.timeFormat.label, onClick = { dialog = HomeDialog.TIME_FORMAT })
        ToggleRow("Show the date", settings.showDate) { v -> update { it.copy(showDate = v) } }

        Section("Sections")
        ToggleRow(
            "Calendar", settings.showCalendar,
            subtitle = "Your next events, from one calendar. Emoji in titles are left out.",
        ) { v ->
            update { it.copy(showCalendar = v) }
            if (v && !CalendarRepository.hasAccess(context)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        SettingRow(
            "Calendar to show",
            subtitle = "Only one is shown at a time. Search the list to find the one you want.",
            value = if (settings.calendarKey == CALENDAR_ALL) "All" else shownCalendar?.shortName ?: "None found",
            enabled = settings.showCalendar && calendars.isNotEmpty(),
            onClick = { dialog = HomeDialog.CALENDAR },
        )
        if (settings.showCalendar && workBlocked) {
            Note(
                "Calendars inside your Work profile are not listed: the organisation that manages it does not let other " +
                    "apps read them, and Focus respects that. If you share your work calendar with a personal Google " +
                    "account, it shows up here like any other calendar.",
            )
        }
        ToggleRow(
            "Week strip", settings.showWeekStrip, enabled = settings.showCalendar,
            subtitle = "Monday to Sunday with today marked, above the events.",
        ) { v -> update { it.copy(showWeekStrip = v) } }
        ToggleRow(
            "Music", settings.showMusic,
            subtitle = "Previous, play or pause, next, in words. It can name the song if you allow it notification access.",
        ) { v -> update { it.copy(showMusic = v) } }
        ToggleRow(
            "Hide it while nothing is playing", settings.musicAutoHide, enabled = settings.showMusic,
            subtitle = "The section comes with the music and leaves a minute after it stops. Off: it is always there.",
        ) { v -> update { it.copy(musicAutoHide = v) } }
        SettingRow(
            "Music app",
            subtitle = "Opens when you tap the section and nothing is playing. Long-pressing the section gets you here too.",
            value = apps.firstOrNull { it.key == settings.musicApp }?.label ?: "Asks first",
            enabled = settings.showMusic,
            onClick = { dialog = HomeDialog.MUSIC_APP },
        )
        ToggleRow(
            "Note", settings.showNote,
            subtitle = "A few lines of your own, always in sight. Tap them on the home screen to write.",
        ) { v -> update { it.copy(showNote = v) } }
        SettingRow(
            "Notes app",
            subtitle = "A word next to the note's title that opens it. Your own lines stay on the home screen either way.",
            value = apps.firstOrNull { it.key == settings.noteApp }?.label ?: "None",
            enabled = settings.showNote,
            onClick = { dialog = HomeDialog.NOTE_APP },
        )

        Section("Fast apps")
        SettingRow("Fast apps", subtitle = "Up to $MAX_FAVORITES apps, one tap from the home screen.", value = "$favoriteCount / $MAX_FAVORITES", onClick = { go(Routes.FAST_APPS) })
        SettingRow("Alignment", value = settings.homeAlign.label, onClick = { dialog = HomeDialog.ALIGN })

        Section("Corner shortcuts")
        ToggleRow("Show shortcuts", settings.showShortcuts) { v -> update { it.copy(showShortcuts = v) } }
        SettingRow("Bottom left", value = shortcutLabel(settings.leftShortcut, apps), enabled = settings.showShortcuts, onClick = { dialog = HomeDialog.LEFT })
        SettingRow("Bottom right", value = shortcutLabel(settings.rightShortcut, apps), enabled = settings.showShortcuts, onClick = { dialog = HomeDialog.RIGHT })
    }

    when (dialog) {
        HomeDialog.NONE -> Unit
        HomeDialog.CLOCK -> ChoiceDialog("Clock style", ClockStyle.entries.map { it to it.label }, settings.clockStyle, close) { v -> update { it.copy(clockStyle = v) } }
        HomeDialog.SPLIT_SIDE -> ChoiceDialog(
            "Next to the clock",
            SplitSide.entries.map { it to it.label },
            if (settings.splitSide == SplitSide.CALENDAR && settings.showCalendar) SplitSide.CALENDAR else SplitSide.SCREEN_TIME,
            close,
        ) { side ->
            update { it.copy(splitSide = side, showCalendar = it.showCalendar || side == SplitSide.CALENDAR) }
            if (side == SplitSide.CALENDAR && !CalendarRepository.hasAccess(context)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        HomeDialog.RING -> ChoiceDialog("Ring shows", RingMode.entries.map { it to it.label }, settings.ringMode, close) { v -> update { it.copy(ringMode = v) } }
        HomeDialog.TAP -> ClockTapDialog(settings, apps, close)
        HomeDialog.CALENDAR -> CalendarPickerDialog(
            calendars = calendars,
            upcomingCounts = calendarCounts,
            selectedKey = if (settings.calendarKey == CALENDAR_ALL) CALENDAR_ALL else shownCalendar?.key,
            workProfileBlocked = workBlocked,
            onDismiss = close,
            onPick = { key -> update { it.copy(calendarKey = key) } },
        )
        HomeDialog.TIME_FORMAT -> ChoiceDialog("Time format", TimeFormat.entries.map { it to it.label }, settings.timeFormat, close) { v -> update { it.copy(timeFormat = v) } }
        HomeDialog.ALIGN -> ChoiceDialog("Alignment", HomeAlign.entries.map { it to it.label }, settings.homeAlign, close) { v -> update { it.copy(homeAlign = v) } }
        HomeDialog.MUSIC_APP -> MusicAppPicker(
            apps = apps.filter { it.key !in settings.hidden },
            subtitle = null,
            onDismiss = close,
            onPick = { app -> update { it.copy(musicApp = app.key) } },
        )
        HomeDialog.NOTE_APP -> AppPickerDialog(
            title = "Notes app",
            apps = apps.filter { it.key !in settings.hidden },
            onDismiss = close,
            leading = listOf("None" to { update { it.copy(noteApp = "", noteLink = "") } }),
            onPick = { app -> update { it.copy(noteApp = app.key, noteLink = "") } },
        )
        HomeDialog.LEFT, HomeDialog.RIGHT -> {
            val left = dialog == HomeDialog.LEFT
            val set: (String) -> Unit = { spec -> update { if (left) it.copy(leftShortcut = spec) else it.copy(rightShortcut = spec) } }
            AppPickerDialog(
                title = if (left) "Bottom left shortcut" else "Bottom right shortcut",
                apps = apps.filter { it.key !in settings.hidden },
                onDismiss = close,
                leading = listOf("Phone" to { set(SHORTCUT_PHONE) }, "Camera" to { set(SHORTCUT_CAMERA) }),
                onPick = { set(it.key) },
            )
        }
    }
}

@Composable
internal fun FastAppsPage(settings: Settings, apps: List<AppEntry>, onBack: () -> Unit) {
    val c = LocalFocusColors.current
    var adding by remember { mutableStateOf(false) }
    val favorites = settings.favorites.mapNotNull { key -> apps.firstOrNull { it.key == key } }
    val keys = favorites.map { it.key }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in keys.indices) return
        val next = keys.toMutableList()
        next.add(target, next.removeAt(index))
        update { it.copy(favorites = next) }
    }

    Page("Fast apps", onBack) {
        Note("The apps listed on your home screen, in this order. You can also add one from the app drawer: long-press it, then “Move to fast apps”.")
        favorites.forEachIndexed { i, app ->
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T(app.label, Modifier.weight(1f).padding(vertical = 15.dp), size = 18.sp, maxLines = 1)
                T("↑", Modifier.clickable(enabled = i > 0) { move(i, -1) }.padding(12.dp), size = 18.sp, color = if (i > 0) c.fg else c.line)
                T("↓", Modifier.clickable(enabled = i < keys.lastIndex) { move(i, 1) }.padding(12.dp), size = 18.sp, color = if (i < keys.lastIndex) c.fg else c.line)
                T("Remove", Modifier.clickable { update { it.copy(favorites = keys - app.key) } }.padding(12.dp), size = 14.sp, color = c.dim)
            }
        }
        if (favorites.size < MAX_FAVORITES) {
            SettingRow("Add an app", value = "${favorites.size} / $MAX_FAVORITES", onClick = { adding = true })
        } else {
            Note("That's the maximum. Five is plenty.")
        }
    }

    if (adding) {
        AppPickerDialog(
            title = "Add a fast app",
            apps = apps.filter { it.key !in keys && it.key !in settings.hidden },
            onDismiss = { adding = false },
            onPick = { app -> update { it.copy(favorites = (keys + app.key).take(MAX_FAVORITES)) } },
        )
    }
}

// ---- App drawer ------------------------------------------------------------------------------

@Composable
internal fun DrawerPage(settings: Settings, onBack: () -> Unit, go: (String) -> Unit) {
    Page("App drawer", onBack) {
        Section("Search")
        ToggleRow("Open the keyboard right away", settings.autoKeyboard, subtitle = "Start typing the moment you swipe to the drawer.") { v -> update { it.copy(autoKeyboard = v) } }
        ToggleRow("Open when one app is left", settings.autoLaunch, subtitle = "Launches the app as soon as your search matches only one.") { v -> update { it.copy(autoLaunch = v) } }

        Section("List")
        ToggleRow("Recently installed", settings.showRecentInstalls, subtitle = "Apps installed in the last 24 hours, at the top.") { v -> update { it.copy(showRecentInstalls = v) } }
        ToggleRow("Show timer progress", settings.showUsageInDrawer, subtitle = "“12m / 30m” next to every app that has a daily limit.") { v -> update { it.copy(showUsageInDrawer = v) } }
        SettingRow("Hidden apps", value = settings.hidden.size.toString(), onClick = { go(Routes.HIDDEN) })
    }
}

@Composable
internal fun HiddenAppsPage(settings: Settings, apps: List<AppEntry>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    val hidden = apps.filter { it.key in settings.hidden }

    Page("Hidden apps", onBack) {
        if (hidden.isEmpty()) {
            Note("Nothing is hidden. To hide an app, long-press it in the drawer and choose “Hide app”. Hidden apps stay installed and can be opened from here.")
        }
        for (app in hidden) SettingRow(app.label, onClick = { selected = app })
    }

    selected?.let { app ->
        FocusDialog({ selected = null }, title = app.label) {
            MenuRow("Open") {
                launchApp(context, scope, app)
                selected = null
            }
            MenuRow("Unhide") {
                update { it.copy(hidden = it.hidden - app.key) }
                selected = null
            }
        }
    }
}

// ---- Appearance ------------------------------------------------------------------------------

private enum class LookDialog { NONE, THEME, FONT, SIZE, LAUNCH }

private val TEXT_SIZES = listOf(0.9f to "Small", 1f to "Default", 1.1f to "Large", 1.2f to "Larger")

@Composable
internal fun AppearancePage(settings: Settings, onBack: () -> Unit) {
    var dialog by remember { mutableStateOf(LookDialog.NONE) }
    val close = { dialog = LookDialog.NONE }

    Page("Appearance", onBack) {
        Section("Black and white")
        SettingRow("Theme", subtitle = "Pure black saves battery on OLED screens.", value = if (settings.dark) "Black" else "White", onClick = { dialog = LookDialog.THEME })
        SettingRow("Typeface", value = settings.font.label, onClick = { dialog = LookDialog.FONT })
        SettingRow("Text size", value = TEXT_SIZES.firstOrNull { it.first == settings.textScale }?.second ?: "Default", onClick = { dialog = LookDialog.SIZE })

        Section("Screen")
        SettingRow(
            "Opening apps",
            subtitle = "Fast puts the app over the whole screen from its first frame. System default uses your phone's own animation.",
            value = settings.launchAnimation.label,
            onClick = { dialog = LookDialog.LAUNCH },
        )
        ToggleRow("Hide the status bar", settings.hideStatusBar, subtitle = "No notification icons on the home screen. Swipe down from the top edge to peek.") { v -> update { it.copy(hideStatusBar = v) } }
    }

    when (dialog) {
        LookDialog.NONE -> Unit
        LookDialog.THEME -> ChoiceDialog("Theme", listOf(true to "Black", false to "White"), settings.dark, close) { v -> update { it.copy(dark = v) } }
        LookDialog.FONT -> ChoiceDialog("Typeface", FontChoice.entries.map { it to it.label }, settings.font, close) { v -> update { it.copy(font = v) } }
        LookDialog.LAUNCH -> ChoiceDialog("Opening apps", LaunchAnimation.entries.map { it to it.label }, settings.launchAnimation, close) { v -> update { it.copy(launchAnimation = v) } }
        LookDialog.SIZE -> ChoiceDialog("Text size", TEXT_SIZES, settings.textScale, close) { v -> update { it.copy(textScale = v) } }
    }
}

// ---- Gestures --------------------------------------------------------------------------------

@Composable
internal fun GesturesPage(settings: Settings, status: SetupStatus, onBack: () -> Unit, go: (String) -> Unit) {
    Page("Gestures", onBack) {
        Note("Swiping left always opens the app drawer, and a long-press on empty space opens these settings.")
        ToggleRow("Swipe down for notifications", settings.swipeDownNotifications) { v -> update { it.copy(swipeDownNotifications = v) } }
        ToggleRow("Swipe up to search", settings.swipeUpSearch, subtitle = "Jumps to the drawer with the keyboard open.") { v -> update { it.copy(swipeUpSearch = v) } }
        // Also under App drawer. It is looked for here too: it is what swiping to the drawer does.
        ToggleRow("Keyboard opens with the drawer", settings.autoKeyboard, subtitle = "Start typing the moment you swipe to your apps.") { v -> update { it.copy(autoKeyboard = v) } }
        ToggleRow("Swipe right", settings.swipeRightSearch, subtitle = "Opens the app below, or the web search, like the page left of a stock home screen.") { v -> update { it.copy(swipeRightSearch = v) } }
        if (settings.swipeRightSearch) {
            SwipeRightRow(settings)
        }
        ToggleRow("Double tap to lock", settings.doubleTapLock, subtitle = "Turns the screen off. Uses the Focus timer service.") { v -> update { it.copy(doubleTapLock = v) } }
        if (settings.doubleTapLock && !status.timerService) {
            Note("The Focus timer service is off, so double tap cannot lock yet.  Open setup  →") { go(Routes.SETUP) }
        }
    }
}

/** Which app swipe right opens: any app, or the web search. Shared by Settings → Gestures and the welcome screen. */
@Composable
internal fun SwipeRightRow(settings: Settings) {
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val current = apps.firstOrNull { it.key == settings.swipeRightApp }?.label ?: "Web search"
    SettingRow("Swipe right opens", subtitle = "Any app, or the web search.", value = current, onClick = { picking = true })
    if (picking) {
        AppPickerDialog(
            title = "Swipe right opens",
            apps = apps.filter { it.key !in settings.hidden },
            onDismiss = { picking = false },
            leading = listOf("Web search" to { update { it.copy(swipeRightApp = "") } }),
            onPick = { app -> update { it.copy(swipeRightApp = app.key) } },
        )
    }
}
