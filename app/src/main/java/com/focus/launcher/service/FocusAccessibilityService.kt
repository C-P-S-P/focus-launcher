package com.focus.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.focus.launcher.BlockActivity
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.AppLimit
import com.focus.launcher.util.formatMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Enforces app timers while an app is in use.
 *
 * The service only listens for window changes to learn which app is in front (it cannot read
 * window content). When a limited app comes up, it asks the usage log how much of today's
 * allowance is left and sets a timer for exactly that long. When the timer fires the numbers are
 * re-read, and only if the allowance is truly gone does the wall ([BlockActivity]) go up.
 *
 * Being a system-bound accessibility service is also what permits starting that activity from
 * the background, and what keeps the process alive while another app is on screen.
 */
class FocusAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val activityCache = LruCache<String, Boolean>(256)

    private var currentPkg: String? = null
    private var sessionStart = 0L

    /** Bumped on every foreground change so results of slow lookups for an old app are dropped. */
    private var generation = 0

    private val check = Runnable { evaluate() }
    private var warn: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    cancelTimers()
                    Graph.limits.sessionConsent = null // the next unlock is a new visit
                }
                // After unlocking, the same app is back in front without a fresh window event.
                Intent.ACTION_USER_PRESENT -> evaluate()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Keyboards, dialogs, the notification shade and toasts also raise this event.
        // Only a real activity means the foreground app changed.
        if (!isActivity(pkg, cls)) return
        // Back on a home screen: the visit the user agreed to is over. (Focus's own wall is in
        // this package too, and is of course not a home screen.)
        if (Graph.limits.sessionConsent != null && cls != BlockActivity::class.java.name && Graph.apps.isHomeApp(pkg)) {
            Graph.limits.sessionConsent = null
        }
        if (pkg == currentPkg) return
        currentPkg = pkg
        sessionStart = System.currentTimeMillis()
        generation++
        evaluate()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        cancelTimers()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
    }

    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache.get(key)?.let { return it }
        val result = try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        activityCache.put(key, result)
        return result
    }

    private fun cancelTimers() {
        main.removeCallbacks(check)
        warn?.let(main::removeCallbacks)
        warn = null
    }

    /** Works out what, if anything, has to happen for the app that is in front right now. */
    private fun evaluate() {
        cancelTimers()
        val pkg = currentPkg ?: return
        if (pkg == packageName) return
        val limits = Graph.limits
        val limit = limits.limitFor(pkg) ?: return
        if (limits.isBypassedToday(pkg)) {
            // Ignored for today, but each visit still needs a yes. Opened from Focus, the launcher
            // has already asked; opened from a notification or recents, this is where it happens.
            if (limits.needsConsent(pkg) && limits.sessionConsent != pkg) askConsent(pkg, limit)
            return
        }

        val now = System.currentTimeMillis()
        val passUntil = limits.extensionUntil(pkg)
        if (now < passUntil) {
            // Inside a "continue for N minutes" window: look again the moment it closes.
            main.postDelayed(check, passUntil - now + 250)
            return
        }

        val gen = generation
        val since = sessionStart - 2_000
        // Shared pool: the service keeps no thread of its own alive between the rare look-ups.
        Graph.scope.launch(Dispatchers.IO) {
            val used = Graph.usage.usageTodayMs(pkg)
            val remaining = limit.millis - used
            // About to block: confirm with the usage log that this app really is still in front.
            val actuallyInFront = if (remaining <= 0) Graph.usage.lastResumedPackage(since) else null
            main.post {
                if (gen != generation || currentPkg != pkg) return@post
                when {
                    remaining > 0 -> scheduleFor(pkg, remaining)
                    actuallyInFront != null && actuallyInFront != pkg && actuallyInFront != packageName -> {
                        // We missed a switch; follow the usage log instead of blocking the wrong app.
                        currentPkg = actuallyInFront
                        sessionStart = System.currentTimeMillis()
                        generation++
                        evaluate()
                    }
                    else -> block(pkg, used, limit)
                }
            }
        }
    }

    /** Arms the timer for the moment today's allowance runs out, plus an optional heads-up before it. */
    private fun scheduleFor(pkg: String, remaining: Long) {
        main.postDelayed(check, remaining.coerceAtLeast(1_500) + 300)
        val warnMs = Graph.settings.value.warnMinutes * 60_000L
        if (warnMs > 0 && remaining > warnMs + 5_000) {
            val label = Graph.apps.labelForPackage(pkg)
            val r = Runnable {
                if (currentPkg == pkg) {
                    val left = formatMinutes(resources, Graph.settings.value.warnMinutes)
                    Toast.makeText(this, getString(R.string.toast_time_left_today, left, label), Toast.LENGTH_LONG).show()
                }
            }
            warn = r
            main.postDelayed(r, remaining - warnMs)
        }
    }

    private fun askConsent(pkg: String, limit: AppLimit) {
        val gen = generation
        Graph.scope.launch(Dispatchers.IO) {
            val used = Graph.usage.usageTodayMs(pkg, maxAgeMs = 10_000)
            main.post {
                if (gen != generation || currentPkg != pkg || Graph.limits.sessionConsent == pkg) return@post
                val intent = BlockActivity.intent(this@FocusAccessibilityService, pkg, used, limit.minutes, midSession = true, consent = true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun block(pkg: String, usedMs: Long, limit: AppLimit) {
        Graph.limits.recordBlocked(pkg)
        val intent = BlockActivity.intent(this, pkg, usedMs, limit.minutes, midSession = true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        private var instance: FocusAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** Re-checks the app in front; call after limits or passes change. */
        fun recheck() {
            val service = instance ?: return
            service.main.post { service.evaluate() }
        }

        fun openNotifications(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false

        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
        }
    }
}
