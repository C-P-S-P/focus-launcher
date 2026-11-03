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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.Graph
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
            Toast.makeText(context, "Calendar added to your home screen", Toast.LENGTH_SHORT).show()
        }
    }

    Page("Setup", onBack) {
        T(
            "Three switches make Focus work. Tap one to open the right system screen, flip it, and come back.",
            Modifier.padding(horizontal = 24.dp, vertical = 10.dp), size = 15.sp, color = c.dim, lineHeight = 22.sp,
        )

        Section("Required")
        SettingRow(
            "1 · Default launcher",
            subtitle = "Makes Focus the screen you land on when you press Home.",
            value = if (status.defaultLauncher) "Done" else "Set",
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
            "2 · Usage access",
            subtitle = "Lets Focus measure screen time: the day bar, app timers and the weekly review all depend on it.",
            value = if (status.usageAccess) "Allowed" else "Allow",
            onClick = { Perms.openUsageAccess(context) },
        )
        SettingRow(
            "3 · Focus timer service",
            subtitle = "An accessibility service that notices which app is in front, so an app can be locked the moment " +
                "its time runs out. It cannot read your screen. Find “Focus app timers” under installed or downloaded apps.",
            value = if (status.timerService) "On" else "Turn on",
            onClick = { Perms.openAccessibility(context) },
        )
        if (!status.timerService) {
            Note(
                "Switch greyed out, or “restricted setting”? Open App info → ⋮ (top right) → Allow restricted " +
                    "settings, then try again.  Open App info  →",
            ) { Perms.openAppDetails(context) }
        }

        Section("Recommended")
        SettingRow(
            "Keep Focus running",
            subtitle = "Some phones clear the home screen from memory several times a day, and show their own " +
                "launcher for the second Focus takes to come back. This tells the phone to leave Focus be: on the " +
                "page that opens, choose “Unrestricted”. Nothing runs in the background either way.",
            value = if (status.batteryExempt) "Done" else "Allow",
            onClick = { if (status.batteryExempt) Perms.openBatteryOptimizationList(context) else Perms.requestBatteryExemption(context) },
        )
        if (Perms.hasVendorBackgroundScreen()) {
            Note(
                (if (status.batteryExempt) "Still being replaced by the phone's own launcher? " else "This phone also has its own list. ") +
                    "Open it and allow Focus to run in the background or to auto-start.  Open  →",
            ) { if (!Perms.openVendorBackgroundScreen(context)) Perms.openAppDetails(context) }
        }
        Section("Optional")
        SettingRow(
            "Notifications",
            subtitle = "Only used for one thing: telling you the weekly review is ready.",
            value = if (status.notifications) "Allowed" else "Allow",
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
            "Calendar section",
            subtitle = "This week at a glance and your next events, on the home screen.",
            value = when {
                !status.calendar -> "Allow"
                calendarShown -> "On"
                else -> "Turn on"
            },
            onClick = {
                when {
                    !status.calendar -> calendarRequest.launch(Manifest.permission.READ_CALENDAR)
                    !calendarShown -> {
                        Graph.settings.update { it.copy(showCalendar = true) }
                        Toast.makeText(context, "Calendar added to your home screen", Toast.LENGTH_SHORT).show()
                    }
                    else -> Perms.openAppDetails(context)
                }
            },
        )
    }
}
