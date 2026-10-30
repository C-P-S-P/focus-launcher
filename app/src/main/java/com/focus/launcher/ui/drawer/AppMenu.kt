package com.focus.launcher.ui.drawer

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.DayUsage
import com.focus.launcher.data.LimitSource
import com.focus.launcher.data.MAX_FAVORITES
import com.focus.launcher.data.Settings
import com.focus.launcher.service.FocusAccessibilityService
import com.focus.launcher.ui.components.ChoiceDialog
import com.focus.launcher.ui.components.FocusDialog
import com.focus.launcher.ui.components.Hairline
import com.focus.launcher.ui.components.MenuRow
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.TextInputDialog
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.Perms
import com.focus.launcher.util.formatDuration
import com.focus.launcher.util.formatMinutes

private enum class Sub { NONE, TIMER, RENAME, REPLACE }

/** The long-press menu of an app, on the home screen and in the drawer alike. */
@Composable
fun AppMenu(
    app: AppEntry,
    settings: Settings,
    today: DayUsage?,
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    var sub by remember { mutableStateOf(Sub.NONE) }

    val installed by Graph.apps.apps.collectAsStateWithLifecycle()
    val favorites = settings.favorites.filter { key -> installed.any { it.key == key } }
    val isFavorite = app.key in favorites
    val isHidden = app.key in settings.hidden
    val limit = Graph.limits.limitFor(app.packageName, settings)
    val used = today?.perApp?.get(app.packageName)

    when (sub) {
        Sub.NONE -> {
            val status = buildList {
                if (app.isRenamed) add(app.systemLabel)
                if (used != null && used > 0) add(stringResource(R.string.menu_status_used_today, formatDuration(res, used)))
                if (limit != null) add(stringResource(R.string.menu_status_limit, formatMinutes(res, limit.minutes)))
            }.joinToString("  ·  ").ifEmpty { null }

            FocusDialog(onDismiss, title = app.label, subtitle = status) {
                if (!app.isSystem) {
                    MenuRow(stringResource(R.string.menu_uninstall)) {
                        Graph.apps.uninstall(app)
                        onDismiss()
                    }
                }
                MenuRow(stringResource(R.string.menu_app_info)) {
                    Graph.apps.openAppInfo(app)
                    onDismiss()
                }
                MenuRow(
                    stringResource(if (isFavorite) R.string.menu_fast_apps_remove else R.string.menu_fast_apps_add),
                    detail = "${favorites.size} / $MAX_FAVORITES",
                ) {
                    when {
                        isFavorite -> {
                            Graph.settings.update { it.copy(favorites = favorites - app.key) }
                            onDismiss()
                        }
                        favorites.size >= MAX_FAVORITES -> sub = Sub.REPLACE
                        else -> {
                            Graph.settings.update { it.copy(favorites = favorites + app.key) }
                            Toast.makeText(context, res.getString(R.string.menu_toast_on_home_screen, app.label), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    }
                }
                if (Graph.apps.canLimit(app.packageName)) {
                    MenuRow(
                        stringResource(R.string.menu_app_timer),
                        detail = limit?.let { stringResource(R.string.menu_timer_per_day, formatMinutes(res, it.minutes)) } ?: stringResource(R.string.menu_timer_off),
                    ) { sub = Sub.TIMER }
                }
                MenuRow(stringResource(R.string.menu_rename)) { sub = Sub.RENAME }
                MenuRow(stringResource(if (isHidden) R.string.menu_unhide else R.string.menu_hide)) {
                    Graph.settings.update { s -> s.copy(hidden = if (isHidden) s.hidden - app.key else s.hidden + app.key) }
                    if (!isHidden) {
                        Toast.makeText(context, res.getString(R.string.menu_toast_hidden), Toast.LENGTH_LONG).show()
                    }
                    onDismiss()
                }
            }
        }

        Sub.TIMER -> TimerDialog(app.packageName, app.label, settings, used, onDismiss, onOpenSetup)

        Sub.RENAME -> TextInputDialog(
            title = stringResource(R.string.menu_rename),
            subtitle = stringResource(R.string.menu_rename_subtitle, app.systemLabel),
            initial = app.label,
            placeholder = app.systemLabel,
            onDismiss = onDismiss,
        ) { name ->
            Graph.settings.update { s ->
                s.copy(renames = if (name.isEmpty() || name == app.systemLabel) s.renames - app.key else s.renames + (app.key to name))
            }
        }

        Sub.REPLACE -> ChoiceDialog(
            title = stringResource(R.string.menu_fast_apps_full),
            subtitle = stringResource(R.string.menu_fast_apps_replace, app.label),
            options = favorites.map { key -> key to (installed.firstOrNull { it.key == key }?.label ?: key) },
            selected = null,
            onDismiss = onDismiss,
        ) { replaced ->
            Graph.settings.update { it.copy(favorites = favorites.map { key -> if (key == replaced) app.key else key }) }
        }
    }
}

private val PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180)

/** Daily time limit for one app. Reused by the long-press menu and by Settings → App timers. */
@Composable
fun TimerDialog(
    packageName: String,
    label: String,
    settings: Settings,
    usedToday: Long?,
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val res = LocalResources.current
    var custom by remember { mutableStateOf(false) }

    val own = settings.appLimits[packageName]
    val fallback = Graph.limits.categoryDefaultFor(packageName, settings)
    val ready = Perms.hasUsageAccess() && Perms.isTimerServiceEnabled(context)

    fun apply(minutes: Int?) {
        Graph.settings.update { s ->
            s.copy(appLimits = if (minutes == null) s.appLimits - packageName else s.appLimits + (packageName to minutes))
        }
        // A new limit should bite immediately, not after an old "continue" window runs out.
        Graph.limits.clearPasses(packageName)
        FocusAccessibilityService.recheck()
        onDismiss()
    }

    if (custom) {
        TextInputDialog(
            title = stringResource(R.string.menu_timer_custom_title),
            subtitle = stringResource(R.string.menu_timer_custom_subtitle, label),
            initial = own?.takeIf { it > 0 }?.toString().orEmpty(),
            placeholder = stringResource(R.string.menu_timer_custom_hint),
            numeric = true,
            confirmLabel = stringResource(R.string.menu_timer_custom_set),
            onDismiss = onDismiss,
        ) { text -> text.toIntOrNull()?.takeIf { it in 1..1440 }?.let { apply(it) } }
        return
    }

    val category = stringResource(Graph.apps.describeCategoryRes(packageName))
    val subtitle = if (usedToday != null) {
        stringResource(R.string.menu_timer_subtitle_used_today, formatDuration(res, usedToday), category)
    } else {
        category
    }
    FocusDialog(onDismiss, title = stringResource(R.string.menu_timer_title, label), subtitle = subtitle) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (fallback != null) {
                val kind = when (fallback.source) {
                    LimitSource.GAME_DEFAULT -> R.string.menu_timer_default_games
                    LimitSource.VIDEO_DEFAULT -> R.string.menu_timer_default_video
                    else -> R.string.menu_timer_default_social
                }
                MenuRow(stringResource(kind), detail = formatMinutes(res, fallback.minutes), selected = own == null) { apply(null) }
            }
            MenuRow(stringResource(R.string.menu_timer_no_limit), selected = own == 0 || (own == null && fallback == null)) {
                apply(if (fallback != null) 0 else null)
            }
            for (minutes in PRESETS) {
                MenuRow(formatMinutes(res, minutes), selected = own == minutes) { apply(minutes) }
            }
            val isCustom = own != null && own > 0 && own !in PRESETS
            MenuRow(stringResource(R.string.menu_timer_custom), detail = if (isCustom) formatMinutes(res, own) else null, selected = isCustom) { custom = true }
        }
        if (!ready) {
            Hairline()
            T(
                stringResource(R.string.menu_timer_setup_needed),
                Modifier.clickable {
                    onDismiss()
                    onOpenSetup()
                }.padding(horizontal = 24.dp, vertical = 14.dp),
                size = 13.sp, color = c.dim, lineHeight = 19.sp,
            )
        }
    }
}
