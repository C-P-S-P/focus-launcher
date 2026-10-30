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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.CALENDAR_ALL
import com.focus.launcher.data.CalendarInfo
import com.focus.launcher.data.CalendarRepository
import com.focus.launcher.data.ClockStyle
import com.focus.launcher.data.FavoriteSize
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

private enum class HomeDialog { NONE, CLOCK, SPLIT_SIDE, RING, TAP, TIME_FORMAT, FAVORITE_SIZE, ALIGN, LEFT, RIGHT, CALENDAR, MUSIC_APP, NOTE_APP }

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

    Page(stringResource(R.string.settings_home_screen), onBack) {
        Section(stringResource(R.string.settings_clock))
        SettingRow(
            stringResource(R.string.settings_style),
            subtitle = when (settings.clockStyle) {
                ClockStyle.SPLIT -> stringResource(R.string.settings_clock_split_summary)
                ClockStyle.RING -> stringResource(R.string.settings_clock_ring_summary)
                ClockStyle.PLAIN -> stringResource(R.string.settings_clock_plain_summary)
            },
            value = stringResource(settings.clockStyle.labelRes),
            onClick = { dialog = HomeDialog.CLOCK },
        )
        when (settings.clockStyle) {
            ClockStyle.SPLIT -> SettingRow(
                stringResource(R.string.settings_next_to_clock),
                subtitle = stringResource(R.string.settings_next_to_clock_summary),
                value = stringResource((if (settings.splitSide == SplitSide.CALENDAR && settings.showCalendar) SplitSide.CALENDAR else SplitSide.SCREEN_TIME).labelRes),
                onClick = { dialog = HomeDialog.SPLIT_SIDE },
            )
            ClockStyle.RING -> SettingRow(
                stringResource(R.string.settings_ring_shows),
                subtitle = stringResource(if (settings.ringMode == RingMode.BATTERY) R.string.settings_ring_battery_summary else R.string.settings_ring_day_summary),
                value = stringResource(settings.ringMode.labelRes),
                onClick = { dialog = HomeDialog.RING },
            )
            ClockStyle.PLAIN -> Unit
        }
        // Without a ring the same stored choice decides one thing only: whether the battery is written out.
        if (settings.clockStyle != ClockStyle.RING) {
            ToggleRow(stringResource(R.string.settings_show_battery), settings.ringMode == RingMode.BATTERY) { v ->
                update { it.copy(ringMode = if (v) RingMode.BATTERY else RingMode.DAY) }
            }
        }
        SettingRow(
            stringResource(R.string.settings_clock_tap),
            subtitle = stringResource(R.string.settings_clock_tap_summary),
            value = clockTapLabel(settings.clockTap, apps),
            onClick = { dialog = HomeDialog.TAP },
        )
        SettingRow(stringResource(R.string.settings_time_format), value = stringResource(settings.timeFormat.labelRes), onClick = { dialog = HomeDialog.TIME_FORMAT })
        ToggleRow(
            stringResource(R.string.settings_show_seconds), settings.showSeconds,
            subtitle = stringResource(R.string.settings_show_seconds_summary),
        ) { v -> update { it.copy(showSeconds = v) } }
        ToggleRow(stringResource(R.string.settings_show_date), settings.showDate) { v -> update { it.copy(showDate = v) } }

        Section(stringResource(R.string.settings_sections))
        ToggleRow(
            stringResource(R.string.settings_calendar), settings.showCalendar,
            subtitle = stringResource(R.string.settings_calendar_summary),
        ) { v ->
            update { it.copy(showCalendar = v) }
            if (v && !CalendarRepository.hasAccess(context)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        SettingRow(
            stringResource(R.string.settings_calendar_to_show),
            subtitle = stringResource(R.string.settings_calendar_to_show_summary),
            value = if (settings.calendarKey == CALENDAR_ALL) {
                stringResource(R.string.settings_calendar_all)
            } else {
                shownCalendar?.shortName ?: stringResource(R.string.settings_calendar_none_found)
            },
            enabled = settings.showCalendar && calendars.isNotEmpty(),
            onClick = { dialog = HomeDialog.CALENDAR },
        )
        if (settings.showCalendar && workBlocked) {
            Note(stringResource(R.string.settings_calendar_work_blocked))
        }
        ToggleRow(
            stringResource(R.string.settings_week_strip), settings.showWeekStrip, enabled = settings.showCalendar,
            subtitle = stringResource(R.string.settings_week_strip_summary),
        ) { v -> update { it.copy(showWeekStrip = v) } }
        ToggleRow(
            stringResource(R.string.settings_music), settings.showMusic,
            subtitle = stringResource(R.string.settings_music_summary),
        ) { v -> update { it.copy(showMusic = v) } }
        ToggleRow(
            stringResource(R.string.settings_music_auto_hide), settings.musicAutoHide, enabled = settings.showMusic,
            subtitle = stringResource(R.string.settings_music_auto_hide_summary),
        ) { v -> update { it.copy(musicAutoHide = v) } }
        SettingRow(
            stringResource(R.string.settings_music_app),
            subtitle = stringResource(R.string.settings_music_app_summary),
            value = apps.firstOrNull { it.key == settings.musicApp }?.label ?: stringResource(R.string.settings_music_app_ask),
            enabled = settings.showMusic,
            onClick = { dialog = HomeDialog.MUSIC_APP },
        )
        ToggleRow(
            stringResource(R.string.settings_note), settings.showNote,
            subtitle = stringResource(R.string.settings_note_summary),
        ) { v -> update { it.copy(showNote = v) } }
        SettingRow(
            stringResource(R.string.settings_notes_app),
            subtitle = stringResource(R.string.settings_notes_app_summary),
            value = apps.firstOrNull { it.key == settings.noteApp }?.label ?: stringResource(R.string.settings_none),
            enabled = settings.showNote,
            onClick = { dialog = HomeDialog.NOTE_APP },
        )

        Section(stringResource(R.string.settings_fast_apps))
        SettingRow(
            stringResource(R.string.settings_fast_apps),
            subtitle = pluralStringResource(R.plurals.settings_fast_apps_summary, MAX_FAVORITES, MAX_FAVORITES),
            value = "$favoriteCount / $MAX_FAVORITES",
            onClick = { go(Routes.FAST_APPS) },
        )
        SettingRow(
            stringResource(R.string.settings_favorite_size),
            value = stringResource(settings.favoriteSize.labelRes),
            onClick = { dialog = HomeDialog.FAVORITE_SIZE },
        )
        SettingRow(stringResource(R.string.settings_alignment), value = stringResource(settings.homeAlign.labelRes), onClick = { dialog = HomeDialog.ALIGN })

        Section(stringResource(R.string.settings_corner_shortcuts))
        ToggleRow(stringResource(R.string.settings_show_shortcuts), settings.showShortcuts) { v -> update { it.copy(showShortcuts = v) } }
        SettingRow(stringResource(R.string.settings_bottom_left), value = shortcutLabel(settings.leftShortcut, apps), enabled = settings.showShortcuts, onClick = { dialog = HomeDialog.LEFT })
        SettingRow(stringResource(R.string.settings_bottom_right), value = shortcutLabel(settings.rightShortcut, apps), enabled = settings.showShortcuts, onClick = { dialog = HomeDialog.RIGHT })
    }

    when (dialog) {
        HomeDialog.NONE -> Unit
        HomeDialog.CLOCK -> ChoiceDialog(
            stringResource(R.string.settings_clock_style),
            ClockStyle.entries.map { it to stringResource(it.labelRes) },
            settings.clockStyle, close,
        ) { v -> update { it.copy(clockStyle = v) } }
        HomeDialog.SPLIT_SIDE -> ChoiceDialog(
            stringResource(R.string.settings_next_to_clock),
            SplitSide.entries.map { it to stringResource(it.labelRes) },
            if (settings.splitSide == SplitSide.CALENDAR && settings.showCalendar) SplitSide.CALENDAR else SplitSide.SCREEN_TIME,
            close,
        ) { side ->
            update { it.copy(splitSide = side, showCalendar = it.showCalendar || side == SplitSide.CALENDAR) }
            if (side == SplitSide.CALENDAR && !CalendarRepository.hasAccess(context)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
        }
        HomeDialog.RING -> ChoiceDialog(
            stringResource(R.string.settings_ring_shows),
            RingMode.entries.map { it to stringResource(it.labelRes) },
            settings.ringMode, close,
        ) { v -> update { it.copy(ringMode = v) } }
        HomeDialog.TAP -> ClockTapDialog(settings, apps, close)
        HomeDialog.CALENDAR -> CalendarPickerDialog(
            calendars = calendars,
            upcomingCounts = calendarCounts,
            selectedKey = if (settings.calendarKey == CALENDAR_ALL) CALENDAR_ALL else shownCalendar?.key,
            workProfileBlocked = workBlocked,
            onDismiss = close,
            onPick = { key -> update { it.copy(calendarKey = key) } },
        )
        HomeDialog.TIME_FORMAT -> ChoiceDialog(
            stringResource(R.string.settings_time_format),
            TimeFormat.entries.map { it to stringResource(it.labelRes) },
            settings.timeFormat, close,
        ) { v -> update { it.copy(timeFormat = v) } }
        HomeDialog.FAVORITE_SIZE -> ChoiceDialog(
            stringResource(R.string.settings_favorite_size),
            FavoriteSize.entries.map { it to stringResource(it.labelRes) },
            settings.favoriteSize, close,
        ) { v -> update { it.copy(favoriteSize = v) } }
        HomeDialog.ALIGN -> ChoiceDialog(
            stringResource(R.string.settings_alignment),
            HomeAlign.entries.map { it to stringResource(it.labelRes) },
            settings.homeAlign, close,
        ) { v -> update { it.copy(homeAlign = v) } }
        HomeDialog.MUSIC_APP -> MusicAppPicker(
            apps = apps.filter { it.key !in settings.hidden },
            subtitle = null,
            onDismiss = close,
            onPick = { app -> update { it.copy(musicApp = app.key) } },
        )
        HomeDialog.NOTE_APP -> AppPickerDialog(
            title = stringResource(R.string.settings_notes_app),
            apps = apps.filter { it.key !in settings.hidden },
            onDismiss = close,
            leading = listOf(stringResource(R.string.settings_none) to { update { it.copy(noteApp = "", noteLink = "") } }),
            onPick = { app -> update { it.copy(noteApp = app.key, noteLink = "") } },
        )
        HomeDialog.LEFT, HomeDialog.RIGHT -> {
            val left = dialog == HomeDialog.LEFT
            val set: (String) -> Unit = { spec -> update { if (left) it.copy(leftShortcut = spec) else it.copy(rightShortcut = spec) } }
            AppPickerDialog(
                title = stringResource(if (left) R.string.settings_bottom_left_shortcut else R.string.settings_bottom_right_shortcut),
                apps = apps.filter { it.key !in settings.hidden },
                onDismiss = close,
                leading = listOf(
                    stringResource(R.string.settings_shortcut_phone) to { set(SHORTCUT_PHONE) },
                    stringResource(R.string.settings_shortcut_camera) to { set(SHORTCUT_CAMERA) },
                ),
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

    Page(stringResource(R.string.settings_fast_apps), onBack) {
        Note(stringResource(R.string.settings_fast_apps_note))
        favorites.forEachIndexed { i, app ->
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T(app.label, Modifier.weight(1f).padding(vertical = 15.dp), size = 18.sp, maxLines = 1)
                T("↑", Modifier.clickable(enabled = i > 0) { move(i, -1) }.padding(12.dp), size = 18.sp, color = if (i > 0) c.fg else c.line)
                T("↓", Modifier.clickable(enabled = i < keys.lastIndex) { move(i, 1) }.padding(12.dp), size = 18.sp, color = if (i < keys.lastIndex) c.fg else c.line)
                T(stringResource(R.string.settings_remove), Modifier.clickable { update { it.copy(favorites = keys - app.key) } }.padding(12.dp), size = 14.sp, color = c.dim)
            }
        }
        if (favorites.size < MAX_FAVORITES) {
            SettingRow(stringResource(R.string.settings_add_app), value = "${favorites.size} / $MAX_FAVORITES", onClick = { adding = true })
        } else {
            Note(stringResource(R.string.settings_fast_apps_full, MAX_FAVORITES))
        }
    }

    if (adding) {
        AppPickerDialog(
            title = stringResource(R.string.settings_add_fast_app),
            apps = apps.filter { it.key !in keys && it.key !in settings.hidden },
            onDismiss = { adding = false },
            onPick = { app -> update { it.copy(favorites = (keys + app.key).take(MAX_FAVORITES)) } },
        )
    }
}

// ---- App drawer ------------------------------------------------------------------------------

@Composable
internal fun DrawerPage(settings: Settings, onBack: () -> Unit, go: (String) -> Unit) {
    Page(stringResource(R.string.settings_app_drawer), onBack) {
        Section(stringResource(R.string.settings_search))
        ToggleRow(
            stringResource(R.string.settings_auto_keyboard), settings.autoKeyboard,
            subtitle = stringResource(R.string.settings_auto_keyboard_summary),
        ) { v -> update { it.copy(autoKeyboard = v) } }
        ToggleRow(
            stringResource(R.string.settings_auto_launch), settings.autoLaunch,
            subtitle = stringResource(R.string.settings_auto_launch_summary),
        ) { v -> update { it.copy(autoLaunch = v) } }

        Section(stringResource(R.string.settings_list))
        ToggleRow(
            stringResource(R.string.settings_recent_installs), settings.showRecentInstalls,
            subtitle = stringResource(R.string.settings_recent_installs_summary),
        ) { v -> update { it.copy(showRecentInstalls = v) } }
        ToggleRow(
            stringResource(R.string.settings_drawer_usage), settings.showUsageInDrawer,
            subtitle = stringResource(R.string.settings_drawer_usage_summary),
        ) { v -> update { it.copy(showUsageInDrawer = v) } }
        SettingRow(stringResource(R.string.settings_hidden_apps), value = settings.hidden.size.toString(), onClick = { go(Routes.HIDDEN) })
    }
}

@Composable
internal fun HiddenAppsPage(settings: Settings, apps: List<AppEntry>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    val hidden = apps.filter { it.key in settings.hidden }

    Page(stringResource(R.string.settings_hidden_apps), onBack) {
        if (hidden.isEmpty()) {
            Note(stringResource(R.string.settings_hidden_apps_empty))
        }
        for (app in hidden) SettingRow(app.label, onClick = { selected = app })
    }

    selected?.let { app ->
        FocusDialog({ selected = null }, title = app.label) {
            MenuRow(stringResource(R.string.settings_open)) {
                launchApp(context, scope, app)
                selected = null
            }
            MenuRow(stringResource(R.string.settings_unhide)) {
                update { it.copy(hidden = it.hidden - app.key) }
                selected = null
            }
        }
    }
}

// ---- Appearance ------------------------------------------------------------------------------

private enum class LookDialog { NONE, THEME, FONT, SIZE, LAUNCH }

private val TEXT_SIZES = listOf(
    0.9f to R.string.settings_text_size_small,
    1f to R.string.settings_text_size_default,
    1.1f to R.string.settings_text_size_large,
    1.2f to R.string.settings_text_size_larger,
)

@Composable
internal fun AppearancePage(settings: Settings, onBack: () -> Unit) {
    var dialog by remember { mutableStateOf(LookDialog.NONE) }
    val close = { dialog = LookDialog.NONE }

    Page(stringResource(R.string.settings_appearance), onBack) {
        Section(stringResource(R.string.settings_black_and_white))
        SettingRow(
            stringResource(R.string.settings_theme),
            subtitle = stringResource(R.string.settings_theme_summary),
            value = stringResource(if (settings.dark) R.string.settings_theme_black else R.string.settings_theme_white),
            onClick = { dialog = LookDialog.THEME },
        )
        SettingRow(stringResource(R.string.settings_typeface), value = stringResource(settings.font.labelRes), onClick = { dialog = LookDialog.FONT })
        SettingRow(
            stringResource(R.string.settings_text_size),
            value = stringResource(TEXT_SIZES.firstOrNull { it.first == settings.textScale }?.second ?: R.string.settings_text_size_default),
            onClick = { dialog = LookDialog.SIZE },
        )

        Section(stringResource(R.string.settings_screen))
        SettingRow(
            stringResource(R.string.settings_opening_apps),
            subtitle = stringResource(R.string.settings_opening_apps_summary),
            value = stringResource(settings.launchAnimation.labelRes),
            onClick = { dialog = LookDialog.LAUNCH },
        )
        ToggleRow(
            stringResource(R.string.settings_hide_status_bar), settings.hideStatusBar,
            subtitle = stringResource(R.string.settings_hide_status_bar_summary),
        ) { v -> update { it.copy(hideStatusBar = v) } }
    }

    when (dialog) {
        LookDialog.NONE -> Unit
        LookDialog.THEME -> ChoiceDialog(
            stringResource(R.string.settings_theme),
            listOf(true to stringResource(R.string.settings_theme_black), false to stringResource(R.string.settings_theme_white)),
            settings.dark, close,
        ) { v -> update { it.copy(dark = v) } }
        LookDialog.FONT -> ChoiceDialog(
            stringResource(R.string.settings_typeface),
            FontChoice.entries.map { it to stringResource(it.labelRes) },
            settings.font, close,
        ) { v -> update { it.copy(font = v) } }
        LookDialog.LAUNCH -> ChoiceDialog(
            stringResource(R.string.settings_opening_apps),
            LaunchAnimation.entries.map { it to stringResource(it.labelRes) },
            settings.launchAnimation, close,
        ) { v -> update { it.copy(launchAnimation = v) } }
        LookDialog.SIZE -> ChoiceDialog(
            stringResource(R.string.settings_text_size),
            TEXT_SIZES.map { (scale, label) -> scale to stringResource(label) },
            settings.textScale, close,
        ) { v -> update { it.copy(textScale = v) } }
    }
}

// ---- Gestures --------------------------------------------------------------------------------

@Composable
internal fun GesturesPage(settings: Settings, status: SetupStatus, onBack: () -> Unit, go: (String) -> Unit) {
    Page(stringResource(R.string.settings_gestures), onBack) {
        Note(stringResource(R.string.settings_gestures_note))
        ToggleRow(stringResource(R.string.settings_swipe_down_notifications), settings.swipeDownNotifications) { v -> update { it.copy(swipeDownNotifications = v) } }
        ToggleRow(
            stringResource(R.string.settings_swipe_up_search), settings.swipeUpSearch,
            subtitle = stringResource(R.string.settings_swipe_up_search_summary),
        ) { v -> update { it.copy(swipeUpSearch = v) } }
        // Also under App drawer. It is looked for here too: it is what swiping to the drawer does.
        ToggleRow(
            stringResource(R.string.settings_keyboard_with_drawer), settings.autoKeyboard,
            subtitle = stringResource(R.string.settings_keyboard_with_drawer_summary),
        ) { v -> update { it.copy(autoKeyboard = v) } }
        ToggleRow(
            stringResource(R.string.settings_swipe_right), settings.swipeRightSearch,
            subtitle = stringResource(R.string.settings_swipe_right_summary),
        ) { v -> update { it.copy(swipeRightSearch = v) } }
        if (settings.swipeRightSearch) {
            SwipeRightRow(settings)
        }
        ToggleRow(
            stringResource(R.string.settings_double_tap_lock), settings.doubleTapLock,
            subtitle = stringResource(R.string.settings_double_tap_lock_summary),
        ) { v -> update { it.copy(doubleTapLock = v) } }
        if (settings.doubleTapLock && !status.timerService) {
            Note(stringResource(R.string.settings_double_tap_service_off)) { go(Routes.SETUP) }
        }
    }
}

/** Which app swipe right opens: any app, or the web search. Shared by Settings → Gestures and the welcome screen. */
@Composable
internal fun SwipeRightRow(settings: Settings) {
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val webSearch = stringResource(R.string.settings_web_search)
    val current = apps.firstOrNull { it.key == settings.swipeRightApp }?.label ?: webSearch
    SettingRow(
        stringResource(R.string.settings_swipe_right_opens),
        subtitle = stringResource(R.string.settings_swipe_right_opens_summary),
        value = current,
        onClick = { picking = true },
    )
    if (picking) {
        AppPickerDialog(
            title = stringResource(R.string.settings_swipe_right_opens),
            apps = apps.filter { it.key !in settings.hidden },
            onDismiss = { picking = false },
            leading = listOf(webSearch to { update { it.copy(swipeRightApp = "") } }),
            onPick = { app -> update { it.copy(swipeRightApp = app.key) } },
        )
    }
}
