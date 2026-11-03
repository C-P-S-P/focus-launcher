package com.focus.launcher.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.focus.launcher.Graph
import com.focus.launcher.service.FocusAccessibilityService

/** Checks for, and deep links to, the handful of system switches the launcher depends on. */
object Perms {
    fun isDefaultLauncher(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    fun hasUsageAccess(): Boolean = Graph.usage.hasAccess()

    /** True when the user switched the timer service on in Accessibility settings. */
    fun isTimerServiceEnabled(context: Context): Boolean {
        if (FocusAccessibilityService.isRunning) return true
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val mine = ComponentName(context, FocusAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    fun canPostNotifications(context: Context): Boolean {
        val enabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return enabled
        return enabled && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** True once the user has told Android not to optimise Focus's battery use (it is then killed last, not first). */
    fun isBatteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Phones whose own background manager kills apps over Android's head, each with a screen where
     * the user allows an app. This is where the big launchers send people too. Empty = stock Android.
     */
    private fun vendorBackgroundScreens(): List<Intent> {
        val maker = Build.MANUFACTURER.lowercase()
        val components = when {
            "vivo" in maker || "iqoo" in maker -> listOf(
                "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
                "com.vivo.abe/com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
            )
            "xiaomi" in maker || "redmi" in maker || "poco" in maker -> listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
            "oppo" in maker || "realme" in maker || "oneplus" in maker -> listOf(
                "com.coloros.safecenter/.startupapp.StartupAppListActivity",
                "com.oppo.safe/.permission.startup.StartupAppListActivity",
            )
            "huawei" in maker || "honor" in maker -> listOf("com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity")
            "samsung" in maker -> listOf("com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity")
            else -> emptyList()
        }
        return components.mapNotNull { ComponentName.unflattenFromString(it) }.map { Intent().setComponent(it) }
    }

    /** True on phones that have such a screen at all. */
    fun hasVendorBackgroundScreen(): Boolean = vendorBackgroundScreens().isNotEmpty()

    /** Opens the vendor's allow-list screen, trying each known one; false when none could be opened. */
    fun openVendorBackgroundScreen(context: Context): Boolean = start(context, *vendorBackgroundScreens().toTypedArray())

    // Play's policy reserves this dialog for apps that break without it. A home screen that the
    // phone keeps clearing from memory is that case; the lint rule cannot tell. Focus is not on
    // Play anyway (website and F-Droid).
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) = start(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** The system's list of exempt apps: where to look once Focus is already on it. */
    fun openBatteryOptimizationList(context: Context) = start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    // ---- deep links --------------------------------------------------------------------------

    fun openUsageAccess(context: Context) = start(
        context,
        // Some phones jump straight to this app's switch when given the package, others reject it.
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    fun openAccessibility(context: Context) = start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openHomeSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openNotificationSettings(context: Context) = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context),
    )

    fun openAppDetails(context: Context) = start(context, appDetails(context))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /** Tries each intent in order until one can be started. [options] carries the launch animation. */
    fun start(context: Context, vararg candidates: Intent, options: Bundle? = null): Boolean {
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }
}
