package com.focus.launcher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.Settings
import com.focus.launcher.data.TAP_ALARMS
import com.focus.launcher.data.TAP_BATTERY
import com.focus.launcher.data.TAP_CALENDAR
import com.focus.launcher.data.TAP_NOTHING
import com.focus.launcher.data.TAP_SCREEN_TIME
import com.focus.launcher.ui.components.AppPickerDialog
import com.focus.launcher.ui.components.FocusDialog
import com.focus.launcher.ui.components.MenuRow

/**
 * Chooses what a tap on the clock does. Reached by long-pressing the clock on the home screen
 * (the same gesture that customises the corner shortcuts) and from Settings → Home screen.
 */
@Composable
fun ClockTapDialog(settings: Settings, apps: List<AppEntry>, onDismiss: () -> Unit) {
    var pickingApp by remember { mutableStateOf(false) }
    val set: (String) -> Unit = { spec -> Graph.settings.update { it.copy(clockTap = spec) } }

    if (pickingApp) {
        AppPickerDialog(
            title = stringResource(R.string.home_clock_tap_picker_title),
            apps = apps.filter { it.key !in settings.hidden },
            onDismiss = onDismiss,
            onPick = { set(it.key) },
        )
        return
    }

    val opensApp = !settings.clockTap.startsWith("tap:")
    FocusDialog(onDismiss, title = stringResource(R.string.home_clock_tap_title), subtitle = stringResource(R.string.home_clock_tap_subtitle)) {
        MenuRow(stringResource(R.string.home_clock_tap_open_app), detail = if (opensApp) clockTapLabel(settings.clockTap, apps) else null, selected = opensApp) {
            pickingApp = true
        }
        for (spec in listOf(TAP_ALARMS, TAP_CALENDAR, TAP_SCREEN_TIME, TAP_BATTERY, TAP_NOTHING)) {
            MenuRow(clockTapLabel(spec, apps), selected = settings.clockTap == spec) {
                set(spec)
                onDismiss()
            }
        }
    }
}
