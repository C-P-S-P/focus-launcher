package com.focus.launcher.ui

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import com.focus.launcher.BlockActivity
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.LaunchAnimation
import com.focus.launcher.util.Perms
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How a newly opened app arrives. "Fast" lets it cover the screen from its very first frame: it
 * starts at 94% size, already over the whole display, and settles into place while fading in.
 *
 * Why this and not a custom fade: since Android 13 the system ignores resource animations
 * (`makeCustomAnimation`) when the transition opens another *task*, which is what starting an app
 * from a launcher is. The built-in scale-up and clip-reveal types are still honoured. Clip-reveal
 * (growing out of the tapped row) was tried first; it hides most of the new app for the first half
 * of the animation, so launches felt late, worst of all from the clock ring at the top.
 */
fun launchOptions(context: Context): Bundle? {
    if (Graph.settings.value.launchAnimation == LaunchAnimation.SYSTEM) return null
    val view = (context as? Activity)?.window?.decorView ?: return null
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null
    val insetX = (width * 0.03f).toInt()
    val insetY = (height * 0.03f).toInt()
    return ActivityOptions.makeScaleUpAnimation(view, insetX, insetY, width - insetX * 2, height - insetY * 2).toBundle()
}

/**
 * Starts an app from the launcher, passing the daily timers on the way:
 *  - an app whose time for today is used never opens at all: the wall is shown in its place;
 *  - an app whose limit was ignored for today opens only after the user confirms, every time.
 * This gate needs only usage access, so timers keep working from the launcher even while the
 * accessibility service is switched off.
 */
fun launchApp(context: Context, scope: CoroutineScope, entry: AppEntry) {
    val limits = Graph.limits
    val pkg = entry.packageName
    val limit = limits.limitFor(pkg)
    if (limit == null || !Graph.usage.hasAccess()) {
        start(context, entry)
        return
    }
    if (limits.isBypassedToday(pkg)) {
        if (limits.needsConsent(pkg)) {
            val used = Graph.usage.today.value?.perApp?.get(pkg) ?: 0L
            context.startActivity(
                BlockActivity.intent(context, pkg, used, limit.minutes, midSession = false, appKey = entry.key, consent = true),
                launchOptions(context),
            )
        } else {
            start(context, entry)
        }
        return
    }
    if (limits.hasFreePass(pkg)) {
        start(context, entry)
        return
    }
    // Most taps on a limited app are nowhere near its limit. The numbers from the last refresh,
    // plus every second that has passed since (the most the app could have gained), prove that
    // without touching the usage log, so the app opens instantly.
    val known = Graph.usage.today.value
    if (known != null && known.date == LocalDate.now()) {
        val worstCase = (known.perApp[pkg] ?: 0L) + Graph.usage.todayAgeMs() + 5_000
        if (worstCase < limit.millis) {
            start(context, entry)
            return
        }
    }
    scope.launch {
        val used = withContext(Dispatchers.IO) { Graph.usage.usageTodayMs(pkg, maxAgeMs = 2_000) }
        if (used >= limit.millis) {
            limits.recordBlocked(pkg)
            context.startActivity(
                BlockActivity.intent(context, pkg, used, limit.minutes, midSession = false, appKey = entry.key),
                launchOptions(context),
            )
        } else {
            start(context, entry)
        }
    }
}

/**
 * The phone's web search box (the Google app where there is one). Used when swipe right is set to
 * the web search. If no search app opens, the Google page opens in whichever browser handles links.
 */
fun openWebSearch(context: Context) {
    val candidates = buildList {
        add(Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).setPackage("com.google.android.googlequicksearchbox"))
        add(Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH))
        add(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, ""))
        add(Intent(Intent.ACTION_VIEW, "https://www.google.com/".toUri()))
    }
    val ok = Perms.start(context, *candidates.toTypedArray(), options = launchOptions(context))
    if (!ok) Toast.makeText(context, context.getString(R.string.launching_no_search_app), Toast.LENGTH_SHORT).show()
}

/** Opens [entry] right now, no questions asked. Used once the gate (or the wall) has said yes. */
fun start(context: Context, entry: AppEntry) {
    val began = SystemClock.uptimeMillis()
    val ok = Graph.apps.launch(entry, options = launchOptions(context))
    // One line per launch: how long the launcher itself took to hand the request to Android.
    // `adb logcat -s FocusLaunch` shows it next to the system's own "Displayed ... +NNNms".
    Log.i("FocusLaunch", "${entry.packageName} requested in ${SystemClock.uptimeMillis() - began} ms")
    if (!ok) Toast.makeText(context, context.getString(R.string.launching_cannot_open, entry.label), Toast.LENGTH_SHORT).show()
}
