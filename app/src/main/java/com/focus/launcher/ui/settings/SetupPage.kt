package com.focus.launcher.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.ui.components.SettingRow
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.Perms

/**
 * The checklist of system switches. Android does not let an app flip any of these itself, so each
 * row opens the matching system screen and the state is re-read when the user comes back.
 */
@Composable
internal fun SetupPage(status: SetupStatus, onBack: () -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val c = LocalFocusColors.current
    val calendarAdded = stringResource(R.string.settings_setup_calendar_added)

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (!granted) Perms.openNotificationSettings(context)
    }
    val calendarRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        // Allowing access is pointless while the section itself is hidden, so switch it on too.
        if (granted) {
            Graph.settings.update { it.copy(showCalendar = true) }
            Toast.makeText(context, calendarAdded, Toast.LENGTH_SHORT).show()
        }
    }

    Page(stringResource(R.string.settings_setup), onBack) {
        T(
            stringResource(R.string.settings_setup_intro),
            Modifier.padding(horizontal = 24.dp, vertical = 10.dp), size = 15.sp, color = c.dim, lineHeight = 22.sp,
        )

        Section(stringResource(R.string.settings_setup_required))
        SettingRow(
            stringResource(R.string.settings_setup_launcher),
            subtitle = stringResource(R.string.settings_setup_launcher_summary),
            value = stringResource(if (status.defaultLauncher) R.string.settings_setup_done else R.string.settings_setup_set),
            onClick = {
                val roles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(RoleManager::class.java) else null
                if (roles != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)
                ) {
                    try {
                        roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    } catch (_: Exception) {
                        Perms.openHomeSettings(context)
                    }
                } else {
                    Perms.openHomeSettings(context)
                }
            },
        )
        SettingRow(
            stringResource(R.string.settings_setup_usage),
            subtitle = stringResource(R.string.settings_setup_usage_summary),
            value = stringResource(if (status.usageAccess) R.string.settings_setup_allowed else R.string.settings_setup_allow),
            onClick = { Perms.openUsageAccess(context) },
        )
        SettingRow(
            stringResource(R.string.settings_setup_timer_service),
            subtitle = stringResource(R.string.settings_setup_timer_service_summary, stringResource(R.string.accessibility_label)),
            value = stringResource(if (status.timerService) R.string.settings_on else R.string.settings_setup_turn_on),
            onClick = { Perms.openAccessibility(context) },
        )
        if (!status.timerService) {
            Note(stringResource(R.string.settings_setup_restricted)) { Perms.openAppDetails(context) }
        }

        Section(stringResource(R.string.settings_setup_optional))
        SettingRow(
            stringResource(R.string.settings_setup_notifications),
            subtitle = stringResource(R.string.settings_setup_notifications_summary),
            value = stringResource(if (status.notifications) R.string.settings_setup_allowed else R.string.settings_setup_allow),
            onClick = {
                if (!status.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Perms.openNotificationSettings(context)
                }
            },
        )
        val calendarShown = Graph.settings.flow.collectAsStateWithLifecycle().value.showCalendar
        SettingRow(
            stringResource(R.string.settings_setup_calendar),
            subtitle = stringResource(R.string.settings_setup_calendar_summary),
            value = when {
                !status.calendar -> stringResource(R.string.settings_setup_allow)
                calendarShown -> stringResource(R.string.settings_on)
                else -> stringResource(R.string.settings_setup_turn_on)
            },
            onClick = {
                when {
                    !status.calendar -> calendarRequest.launch(Manifest.permission.READ_CALENDAR)
                    !calendarShown -> {
                        Graph.settings.update { it.copy(showCalendar = true) }
                        Toast.makeText(context, calendarAdded, Toast.LENGTH_SHORT).show()
                    }
                    else -> Perms.openAppDetails(context)
                }
            },
        )
    }
}
