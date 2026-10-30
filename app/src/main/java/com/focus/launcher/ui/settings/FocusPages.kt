package com.focus.launcher.ui.settings

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.BlockActivity
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.ReviewActivity
import com.focus.launcher.data.AppCategory
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.LimitSource
import com.focus.launcher.data.Settings
import com.focus.launcher.data.TimeFormat
import com.focus.launcher.data.WeekSummary
import com.focus.launcher.service.FocusAccessibilityService
import com.focus.launcher.service.WeeklyReview
import com.focus.launcher.ui.components.AppPickerDialog
import com.focus.launcher.ui.components.ChoiceDialog
import com.focus.launcher.ui.components.MultiChoiceDialog
import com.focus.launcher.ui.components.SettingRow
import com.focus.launcher.ui.components.ToggleRow
import com.focus.launcher.ui.drawer.TimerDialog
import com.focus.launcher.util.formatHour
import com.focus.launcher.util.formatMinutes
import java.time.LocalDate

/** Timer settings change what the service should be doing right now, so poke it after each edit. */
private fun updateTimers(transform: (Settings) -> Settings) {
    Graph.settings.update(transform)
    FocusAccessibilityService.recheck()
}

private fun dailyLabel(res: Resources, minutes: Int) =
    if (minutes > 0) res.getString(R.string.settings_per_day, formatMinutes(res, minutes)) else res.getString(R.string.settings_off)

// ---- App timers ------------------------------------------------------------------------------

private enum class TimerDialogKind { NONE, SOCIAL, GAMES, VIDEO, CONTINUE, PAUSE, WARN }

private val DEFAULT_CHOICES = listOf(0, 10, 15, 20, 30, 45, 60, 90, 120)

@Composable
internal fun TimersPage(settings: Settings, apps: List<AppEntry>, status: SetupStatus, onBack: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    var dialog by remember { mutableStateOf(TimerDialogKind.NONE) }
    val close = { dialog = TimerDialogKind.NONE }
    val on = settings.timersEnabled
    val limited = remember(apps, settings) { apps.map { it.packageName }.distinct().count { Graph.limits.limitFor(it, settings) != null } }

    Page(stringResource(R.string.settings_app_timers), onBack) {
        ToggleRow(
            stringResource(R.string.settings_app_timers), on,
            subtitle = stringResource(R.string.settings_app_timers_toggle_summary),
        ) { v -> updateTimers { it.copy(timersEnabled = v) } }

        if (on && !(status.usageAccess && status.timerService)) {
            Note(
                when {
                    !status.usageAccess -> stringResource(R.string.settings_timers_no_usage_access)
                    else -> stringResource(R.string.settings_timers_service_off)
                },
            ) { go(Routes.SETUP) }
        }

        Section(stringResource(R.string.settings_automatic_limits))
        SettingRow(
            stringResource(R.string.settings_social_media),
            subtitle = stringResource(R.string.settings_social_media_summary),
            value = dailyLabel(res, settings.socialDefaultMin),
            enabled = on, onClick = { dialog = TimerDialogKind.SOCIAL },
        )
        SettingRow(
            stringResource(R.string.settings_games),
            subtitle = stringResource(R.string.settings_games_summary),
            value = dailyLabel(res, settings.gameDefaultMin),
            enabled = on, onClick = { dialog = TimerDialogKind.GAMES },
        )
        SettingRow(
            stringResource(R.string.settings_video),
            subtitle = stringResource(R.string.settings_video_summary),
            value = dailyLabel(res, settings.videoDefaultMin),
            enabled = on, onClick = { dialog = TimerDialogKind.VIDEO },
        )
        SettingRow(
            stringResource(R.string.settings_limited_apps),
            subtitle = stringResource(R.string.settings_limited_apps_summary),
            value = limited.toString(),
            enabled = on, onClick = { go(Routes.TIMER_APPS) },
        )
        Note(stringResource(R.string.settings_automatic_limits_note))

        Section(stringResource(R.string.settings_when_time_is_up))
        ToggleRow(stringResource(R.string.settings_allow_continue), settings.allowContinue, enabled = on) { v -> updateTimers { it.copy(allowContinue = v) } }
        SettingRow(
            stringResource(R.string.settings_continue_options),
            value = stringResource(R.string.settings_continue_options_value, settings.continueOptions.sorted().joinToString(", ")),
            enabled = on && settings.allowContinue,
            onClick = { dialog = TimerDialogKind.CONTINUE },
        )
        ToggleRow(
            stringResource(R.string.settings_allow_bypass), settings.allowBypass, enabled = on,
            subtitle = if (!settings.allowContinue && !settings.allowBypass) stringResource(R.string.settings_strict_mode) else null,
        ) { v -> updateTimers { it.copy(allowBypass = v) } }
        ToggleRow(
            stringResource(R.string.settings_ask_after_bypass), settings.askAfterBypass, enabled = on && settings.allowBypass,
            subtitle = stringResource(R.string.settings_ask_after_bypass_summary),
        ) { v -> updateTimers { it.copy(askAfterBypass = v) } }
        SettingRow(
            stringResource(R.string.settings_pause),
            subtitle = stringResource(R.string.settings_pause_summary),
            value = if (settings.frictionSeconds > 0) stringResource(R.string.settings_pause_value, settings.frictionSeconds) else stringResource(R.string.settings_none),
            enabled = on, onClick = { dialog = TimerDialogKind.PAUSE },
        )
        SettingRow(
            stringResource(R.string.settings_heads_up),
            value = if (settings.warnMinutes > 0) formatMinutes(res, settings.warnMinutes) else stringResource(R.string.settings_off),
            enabled = on, onClick = { dialog = TimerDialogKind.WARN },
        )

        Section("")
        SettingRow(stringResource(R.string.settings_preview_lock), onClick = {
            context.startActivity(
                BlockActivity.intent(context, context.packageName, usedMs = 31 * 60_000L, limitMinutes = 30, midSession = false, preview = true),
            )
        })
    }

    when (dialog) {
        TimerDialogKind.NONE -> Unit
        TimerDialogKind.SOCIAL -> ChoiceDialog(
            stringResource(R.string.settings_social_media), DEFAULT_CHOICES.map { it to dailyLabel(res, it) }, settings.socialDefaultMin, close,
            subtitle = stringResource(R.string.settings_social_media_dialog),
        ) { v -> updateTimers { it.copy(socialDefaultMin = v) } }
        TimerDialogKind.VIDEO -> ChoiceDialog(
            stringResource(R.string.settings_video), DEFAULT_CHOICES.map { it to dailyLabel(res, it) }, settings.videoDefaultMin, close,
            subtitle = stringResource(R.string.settings_video_dialog),
        ) { v -> updateTimers { it.copy(videoDefaultMin = v) } }
        TimerDialogKind.GAMES -> ChoiceDialog(
            stringResource(R.string.settings_games), DEFAULT_CHOICES.map { it to dailyLabel(res, it) }, settings.gameDefaultMin, close,
            subtitle = stringResource(R.string.settings_games_dialog),
        ) { v -> updateTimers { it.copy(gameDefaultMin = v) } }
        TimerDialogKind.CONTINUE -> MultiChoiceDialog(
            stringResource(R.string.settings_continue_options),
            listOf(1, 2, 5, 10, 15, 20, 30).map { it to stringResource(R.string.settings_continue_option, it) },
            settings.continueOptions.toSet(), close,
            subtitle = stringResource(R.string.settings_continue_options_subtitle),
        ) { picked -> updateTimers { it.copy(continueOptions = picked.sorted().take(4).ifEmpty { listOf(5) }) } }
        TimerDialogKind.PAUSE -> ChoiceDialog(
            stringResource(R.string.settings_pause),
            listOf(0, 3, 5, 10, 20, 30, 60).map {
                it to if (it == 0) stringResource(R.string.settings_none) else pluralStringResource(R.plurals.settings_pause_seconds, it, it)
            },
            settings.frictionSeconds, close,
        ) { v -> updateTimers { it.copy(frictionSeconds = v) } }
        TimerDialogKind.WARN -> ChoiceDialog(
            stringResource(R.string.settings_heads_up),
            listOf(0, 1, 2, 5, 10).map {
                it to if (it == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_heads_up_before, formatMinutes(res, it))
            },
            settings.warnMinutes, close,
        ) { v -> updateTimers { it.copy(warnMinutes = v) } }
    }
}

@Composable
internal fun TimerAppsPage(settings: Settings, apps: List<AppEntry>, onBack: () -> Unit, go: (String) -> Unit) {
    val res = LocalResources.current
    val today by Graph.usage.today.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    // One row per package, even when an app has several launcher entries or a work-profile twin.
    val packages = remember(apps) { apps.distinctBy { it.packageName } }
    val limited = remember(packages, settings) {
        packages.mapNotNull { app -> Graph.limits.limitFor(app.packageName, settings)?.let { app to it } }
    }
    val exempt = remember(packages, settings) {
        packages.filter { settings.appLimits[it.packageName] == 0 && Graph.limits.categoryDefaultFor(it.packageName, settings) != null }
    }
    // Android calls these "social", but that label also covers tools. Focus will not guess.
    val unsure = remember(packages, settings) {
        packages.filter { it.category == AppCategory.UNSURE && it.packageName !in settings.appLimits && Graph.apps.canLimit(it.packageName) }
    }

    Page(stringResource(R.string.settings_limited_apps), onBack) {
        SettingRow(stringResource(R.string.settings_add_timer), subtitle = stringResource(R.string.settings_add_timer_summary), onClick = { adding = true })

        if (limited.isNotEmpty()) Section(stringResource(R.string.settings_with_daily_limit))
        for ((app, limit) in limited) {
            SettingRow(
                app.label,
                subtitle = when (limit.source) {
                    LimitSource.APP -> stringResource(R.string.settings_limit_own)
                    LimitSource.SOCIAL_DEFAULT -> stringResource(R.string.settings_limit_social)
                    LimitSource.GAME_DEFAULT -> stringResource(R.string.settings_limit_game)
                    LimitSource.VIDEO_DEFAULT -> stringResource(R.string.settings_limit_video)
                },
                value = formatMinutes(res, limit.minutes),
                onClick = { editing = app },
            )
        }
        if (limited.isEmpty()) {
            Note(stringResource(R.string.settings_no_limited_apps))
        }

        if (exempt.isNotEmpty()) {
            Section(stringResource(R.string.settings_excluded))
            for (app in exempt) SettingRow(app.label, value = stringResource(R.string.settings_no_limit), onClick = { editing = app })
        }

        if (unsure.isNotEmpty()) {
            Section(stringResource(R.string.settings_unsure))
            Note(stringResource(R.string.settings_unsure_note))
            for (app in unsure) SettingRow(app.label, value = stringResource(R.string.settings_add), onClick = { editing = app })
        }
    }

    editing?.let { app ->
        TimerDialog(
            packageName = app.packageName,
            label = app.label,
            settings = settings,
            usedToday = today?.perApp?.get(app.packageName),
            onDismiss = { editing = null },
            onOpenSetup = { go(Routes.SETUP) },
        )
    }
    if (adding) {
        AppPickerDialog(
            title = stringResource(R.string.settings_add_timer),
            apps = packages.filter { Graph.apps.canLimit(it.packageName) },
            onDismiss = { adding = false },
            onPick = { editing = it },
        )
    }
}

// ---- Weekly review ---------------------------------------------------------------------------

private enum class WeeklyDialog { NONE, WEEK_START, HOUR }

@Composable
internal fun WeeklyPage(settings: Settings, status: SetupStatus, onBack: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(WeeklyDialog.NONE) }
    val close = { dialog = WeeklyDialog.NONE }
    val on = settings.weeklyEnabled
    val use24h = when (settings.timeFormat) {
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
    }
    val res = LocalResources.current
    // Only ever one of two days, so both are resources: a language can pick the form its sentences need.
    val lastDay = stringResource(if (settings.weekStartsMonday) R.string.settings_last_day_sunday else R.string.settings_last_day_saturday)

    fun change(transform: (Settings) -> Settings) {
        Graph.settings.update(transform)
        WeeklyReview.schedule(context)
    }

    Page(stringResource(R.string.settings_weekly_review), onBack) {
        ToggleRow(
            stringResource(R.string.settings_weekly_review), on,
            subtitle = stringResource(R.string.settings_weekly_review_toggle_summary),
        ) { v -> change { it.copy(weeklyEnabled = v) } }

        Section(stringResource(R.string.settings_schedule))
        SettingRow(
            stringResource(R.string.settings_week_starts_on),
            value = stringResource(if (settings.weekStartsMonday) R.string.settings_week_starts_monday else R.string.settings_week_starts_sunday),
            onClick = { dialog = WeeklyDialog.WEEK_START },
        )
        SettingRow(
            stringResource(R.string.settings_review_ready),
            subtitle = stringResource(R.string.settings_review_ready_summary),
            value = stringResource(R.string.settings_review_ready_value, lastDay, formatHour(res, settings.reviewHour, use24h)),
            enabled = on, onClick = { dialog = WeeklyDialog.HOUR },
        )
        if (on && !status.notifications) {
            Note(stringResource(R.string.settings_review_notifications_off)) { go(Routes.SETUP) }
        }

        Section("")
        SettingRow(stringResource(R.string.settings_open_this_week), onClick = {
            context.startActivity(ReviewActivity.intent(context, WeekSummary.weekStartOf(LocalDate.now(), settings.weekStartsMonday)))
        })
    }

    when (dialog) {
        WeeklyDialog.NONE -> Unit
        WeeklyDialog.WEEK_START -> ChoiceDialog(
            stringResource(R.string.settings_week_starts_on),
            listOf(true to stringResource(R.string.settings_week_starts_monday), false to stringResource(R.string.settings_week_starts_sunday)),
            settings.weekStartsMonday, close,
        ) { v ->
            change { it.copy(weekStartsMonday = v) }
        }
        WeeklyDialog.HOUR -> ChoiceDialog(
            stringResource(R.string.settings_review_ready_at), (6..23).map { it to formatHour(res, it, use24h) }, settings.reviewHour, close,
            subtitle = stringResource(R.string.settings_review_ready_at_subtitle, lastDay),
        ) { v -> change { it.copy(reviewHour = v) } }
    }
}
