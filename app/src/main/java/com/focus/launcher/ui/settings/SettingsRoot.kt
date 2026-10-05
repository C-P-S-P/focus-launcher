package com.focus.launcher.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focus.launcher.BuildConfig
import com.focus.launcher.Graph
import com.focus.launcher.data.CalendarRepository
import com.focus.launcher.data.Settings
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.SettingRow
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.Perms

/** Snapshot of the system switches Focus depends on; re-read whenever settings come back on screen. */
data class SetupStatus(
    val defaultLauncher: Boolean,
    val usageAccess: Boolean,
    val timerService: Boolean,
    /** Left out of battery optimisation: killed for memory last, like a launcher should be, instead of first. */
    val batteryExempt: Boolean,
    val notifications: Boolean,
    val calendar: Boolean,
) {
    /** The three that matter for the core experience. Battery exemption is recommended, not required: Focus works without it. */
    val done: Int get() = listOf(defaultLauncher, usageAccess, timerService).count { it }
    val complete: Boolean get() = done == 3

    companion object {
        fun read(context: Context) = SetupStatus(
            defaultLauncher = Perms.isDefaultLauncher(context),
            usageAccess = Perms.hasUsageAccess(),
            timerService = Perms.isTimerServiceEnabled(context),
            batteryExempt = Perms.isBatteryExempt(context),
            notifications = Perms.canPostNotifications(context),
            calendar = CalendarRepository.hasAccess(context),
        )
    }
}

object Routes {
    const val MAIN = "main"
    const val SETUP = "setup"
    const val HOME = "home"
    const val FAST_APPS = "fastapps"
    const val DRAWER = "drawer"
    const val HIDDEN = "hidden"
    const val TIMERS = "timers"
    const val TIMER_APPS = "timerapps"
    const val WEEKLY = "weekly"
    const val APPEARANCE = "appearance"
    const val GESTURES = "gestures"
    const val ABOUT = "about"
    const val WELCOME = "welcome"
}

@Composable
fun SettingsRoot(settings: Settings, startRoute: String?, onExit: () -> Unit) {
    val context = LocalContext.current
    val stack = remember {
        mutableStateListOf(Routes.MAIN).apply {
            if (startRoute != null && startRoute != Routes.MAIN) add(startRoute)
            // Someone new who found "Focus Settings" before making Focus their home screen.
            else if (startRoute == null && !Graph.state.tutorialSeen) {
                Graph.state.tutorialSeen = true // shown once, however it is left
                add(Routes.WELCOME)
            }
        }
    }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }
    val go: (String) -> Unit = { stack.add(it) }
    BackHandler(onBack = back)

    var status by remember { mutableStateOf(SetupStatus.read(context)) }
    LifecycleResumeEffect(Unit) {
        status = SetupStatus.read(context)
        onPauseOrDispose { }
    }
    val apps by Graph.apps.apps.collectAsStateWithLifecycle()

    // Going deeper slides in from the right, going back from the left; both with a short fade.
    AnimatedContent(
        targetState = stack.size to stack.last(),
        transitionSpec = {
            val forward = targetState.first >= initialState.first
            val spec = tween<IntOffset>(durationMillis = 260, easing = FastOutSlowInEasing)
            (slideInHorizontally(spec) { width -> if (forward) width / 7 else -width / 7 } + fadeIn(tween(200, delayMillis = 40)))
                .togetherWith(slideOutHorizontally(spec) { width -> if (forward) -width / 7 else width / 7 } + fadeOut(tween(120)))
        },
        contentKey = { it.second },
        label = "settings page",
    ) { (_, route) ->
        when (route) {
            Routes.SETUP -> SetupPage(status, back) { status = SetupStatus.read(context) }
            Routes.HOME -> HomePage(settings, apps, back, go)
            Routes.FAST_APPS -> FastAppsPage(settings, apps, back)
            Routes.DRAWER -> DrawerPage(settings, back, go)
            Routes.HIDDEN -> HiddenAppsPage(settings, apps, back)
            Routes.TIMERS -> TimersPage(settings, apps, status, back, go)
            Routes.TIMER_APPS -> TimerAppsPage(settings, apps, back, go)
            Routes.WEEKLY -> WeeklyPage(settings, status, back, go)
            Routes.APPEARANCE -> AppearancePage(settings, back)
            Routes.GESTURES -> GesturesPage(settings, status, back, go)
            Routes.ABOUT -> AboutPage(back, go)
            Routes.WELCOME -> WelcomePage { toSetup ->
                Graph.state.tutorialSeen = true
                Graph.state.restartTips() // also how "again" works, from About
                stack.remove(Routes.WELCOME)
                if (toSetup) go(Routes.SETUP) else onExit() // Start: to the home screen, where the tips are
            }
            else -> MainPage(settings, apps.size, status, back, go)
        }
    }
}

@Composable
private fun MainPage(settings: Settings, appCount: Int, status: SetupStatus, onBack: () -> Unit, go: (String) -> Unit) {
    Page("Focus", onBack) {
        VSpace(6.dp)
        SettingRow(
            "Setup",
            subtitle = if (status.complete) "Everything Focus needs is switched on." else "Finish these so timers and screen time work.",
            value = if (status.complete) "All set" else "${status.done} of 3",
            onClick = { go(Routes.SETUP) },
        )
        Section("Launcher")
        SettingRow("Home screen", subtitle = "Clock, sections, fast apps, corner shortcuts", onClick = { go(Routes.HOME) })
        SettingRow("App drawer", subtitle = "Keyboard, search, recently installed, hidden apps", value = "$appCount apps", onClick = { go(Routes.DRAWER) })
        SettingRow("Gestures", subtitle = "Swipes, double tap, keyboard in the drawer", onClick = { go(Routes.GESTURES) })
        SettingRow("Appearance", subtitle = "Black or white, typeface, text size", onClick = { go(Routes.APPEARANCE) })
        Section("Focus")
        SettingRow(
            "App timers",
            subtitle = "Daily limits that lock social apps and games",
            value = if (settings.timersEnabled) "On" else "Off",
            onClick = { go(Routes.TIMERS) },
        )
        SettingRow(
            "Weekly review",
            subtitle = "A look back at the end of every week",
            value = if (settings.weeklyEnabled) "On" else "Off",
            onClick = { go(Routes.WEEKLY) },
        )
        Section("")
        SettingRow("About", onClick = { go(Routes.ABOUT) })
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit, go: (String) -> Unit) {
    val c = LocalFocusColors.current
    Page("About", onBack) {
        SettingRow("Welcome screen and tips", subtitle = "Shows the tips on the home screen again, one at a time.", onClick = { go(Routes.WELCOME) })
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            T("Focus ${BuildConfig.VERSION_NAME}", size = 20.sp, weight = FontWeight.Medium)
            VSpace(12.dp)
            T(
                "A launcher with nothing to look at. No icons, no colour, no feed: the time, the few apps you " +
                    "chose, and an honest picture of where your day is going.",
                size = 16.sp, lineHeight = 24.sp,
            )
            VSpace(20.dp)
            Label("Privacy")
            VSpace(8.dp)
            T(
                "Focus has no internet permission, so nothing it knows can leave this phone. Screen time is read " +
                    "from Android's own usage log and stored only in the app's private storage. The timer service " +
                    "sees the name of the app in front and nothing else; it cannot read what is on your screen.",
                size = 15.sp, color = c.dim, lineHeight = 22.sp,
            )
            VSpace(20.dp)
            Label("Tips")
            VSpace(8.dp)
            for (tip in listOf(
                "Swipe left on the home screen for all apps.",
                "Long-press any app for its menu: timer, rename, hide, fast apps.",
                "Long-press empty space on the home screen to open these settings.",
                "Long-press a corner shortcut to change it.",
                "Tap the clock for alarms, the date for your calendar, the day bar for details.",
            )) {
                T("–  $tip", Modifier.padding(vertical = 4.dp), size = 15.sp, color = c.dim, lineHeight = 22.sp)
            }
        }
    }
}

// ---- shared page scaffolding -----------------------------------------------------------------

@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("←", Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 10.dp), size = 22.sp)
            T(title, Modifier.weight(1f).padding(start = 4.dp), size = 22.sp, weight = FontWeight.Medium, maxLines = 1)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
            VSpace(48.dp)
        }
    }
}

@Composable
internal fun Section(title: String) {
    Label(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 4.dp))
}

@Composable
internal fun Note(text: String, onClick: (() -> Unit)? = null) {
    T(
        text,
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        size = 13.sp, color = LocalFocusColors.current.dim, lineHeight = 19.sp,
    )
}
