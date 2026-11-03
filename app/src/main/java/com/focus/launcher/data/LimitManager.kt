package com.focus.launcher.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.time.LocalDate

/** Where an app's daily limit comes from. */
enum class LimitSource { APP, SOCIAL_DEFAULT, GAME_DEFAULT, VIDEO_DEFAULT }

data class AppLimit(val minutes: Int, val source: LimitSource) {
    val millis: Long get() = minutes * 60_000L
}

/** How often the wall came up over some days, and what the user did about it. */
data class LimitStats(
    val blocked: Int = 0,
    val continued: Int = 0,
    val continuedMinutes: Int = 0,
    val bypassed: Int = 0,
) {
    operator fun plus(o: LimitStats) = LimitStats(
        blocked + o.blocked, continued + o.continued, continuedMinutes + o.continuedMinutes, bypassed + o.bypassed,
    )
}

/**
 * Pure limit decision, split out of [LimitManager.limitFor] so it can be unit-tested without a
 * Context. [category] is evaluated only when the app has no limit of its own, matching the original
 * short-circuit (classifying an app can be real work on the resume path).
 */
internal fun resolveLimit(pkg: String, s: Settings, canLimit: Boolean, category: () -> AppCategory): AppLimit? {
    if (!s.timersEnabled || !canLimit) return null
    s.appLimits[pkg]?.let { own -> return if (own > 0) AppLimit(own, LimitSource.APP) else null }
    return when (category()) {
        AppCategory.SOCIAL -> s.socialDefaultMin.takeIf { it > 0 }?.let { AppLimit(it, LimitSource.SOCIAL_DEFAULT) }
        AppCategory.GAME -> s.gameDefaultMin.takeIf { it > 0 }?.let { AppLimit(it, LimitSource.GAME_DEFAULT) }
        AppCategory.VIDEO -> s.videoDefaultMin.takeIf { it > 0 }?.let { AppLimit(it, LimitSource.VIDEO_DEFAULT) }
        // Communication tools and apps Focus is unsure about are never limited on its own initiative.
        AppCategory.COMMUNICATION, AppCategory.UNSURE, AppCategory.OTHER -> null
    }
}

/**
 * Decides which apps are limited and remembers the short-lived exceptions the user grants from the
 * wall: "continue for N minutes" and "ignore the limit today". Also keeps a small per-day tally of
 * those decisions for the weekly review.
 */
class LimitManager(
    context: Context,
    private val settings: SettingsStore,
    private val apps: AppRepository,
) {
    private val state = context.getSharedPreferences("focus_limit_state", Context.MODE_PRIVATE)
    private val log = context.getSharedPreferences("focus_limit_log", Context.MODE_PRIVATE)

    /** The limit that applies to [pkg] right now, or null when the app is free to use. */
    fun limitFor(pkg: String, s: Settings = settings.value): AppLimit? =
        resolveLimit(pkg, s, apps.canLimit(pkg)) { apps.categoryOf(pkg) }

    /** The limit [pkg] would fall back to if its own setting were cleared. */
    fun categoryDefaultFor(pkg: String, s: Settings = settings.value): AppLimit? =
        limitFor(pkg, s.copy(appLimits = s.appLimits - pkg))

    // ---- exceptions granted from the wall ---------------------------------------------------

    fun extensionUntil(pkg: String): Long = state.getLong("ext_$pkg", 0L)

    fun isBypassedToday(pkg: String): Boolean = state.getString("bypass_$pkg", null) == LocalDate.now().toString()

    /** True when [pkg] may run regardless of its usage: inside a "continue" window or bypassed today. */
    fun hasFreePass(pkg: String, now: Long = System.currentTimeMillis()): Boolean =
        isBypassedToday(pkg) || now < extensionUntil(pkg)

    fun grantExtension(pkg: String, minutes: Int) {
        state.edit { putLong("ext_$pkg", System.currentTimeMillis() + minutes * 60_000L) }
        record(pkg) { it.copy(continued = it.continued + 1, continuedMinutes = it.continuedMinutes + minutes) }
    }

    fun bypassToday(pkg: String) {
        state.edit { putString("bypass_$pkg", LocalDate.now().toString()) }
        record(pkg) { it.copy(bypassed = it.bypassed + 1) }
    }

    fun recordBlocked(pkg: String) = record(pkg) { it.copy(blocked = it.blocked + 1) }

    /**
     * The app the user has just agreed to open although its limit is ignored for today. It spares
     * them a second question from the timer service for the same visit; the service forgets it
     * again when they go back to a home screen or the screen turns off. Not persisted on purpose.
     */
    @Volatile
    var sessionConsent: String? = null

    /** True when opening [pkg] should be confirmed first: its limit was ignored for today. */
    fun needsConsent(pkg: String, s: Settings = settings.value): Boolean = s.askAfterBypass && isBypassedToday(pkg)

    /** Drops "continue" windows and bypasses when the user edits a limit, so the change bites at once. */
    fun clearPasses(pkg: String) {
        state.edit { remove("ext_$pkg"); remove("bypass_$pkg") }
        if (sessionConsent == pkg) sessionConsent = null
    }

    // ---- tally for the weekly review --------------------------------------------------------

    @Synchronized
    private fun record(pkg: String, change: (LimitStats) -> LimitStats) {
        val day = LocalDate.now().toString()
        val json = try {
            JSONObject(log.getString(day, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val next = change(json.optJSONObject(pkg).toStats())
        json.put(pkg, JSONObject().apply {
            put("b", next.blocked)
            put("c", next.continued)
            put("m", next.continuedMinutes)
            put("x", next.bypassed)
        })
        log.edit { putString(day, json.toString()) }
    }

    /** Per-package tallies summed over [from]..[to] inclusive. */
    fun stats(from: LocalDate, to: LocalDate): Map<String, LimitStats> {
        val out = HashMap<String, LimitStats>()
        var d = from
        while (!d.isAfter(to)) {
            val raw = log.getString(d.toString(), null)
            if (raw != null) {
                try {
                    val json = JSONObject(raw)
                    for (pkg in json.keys()) {
                        out[pkg] = (out[pkg] ?: LimitStats()) + json.optJSONObject(pkg).toStats()
                    }
                } catch (_: Exception) {
                }
            }
            d = d.plusDays(1)
        }
        return out
    }

    fun prune(olderThan: LocalDate) {
        log.edit {
            for (key in log.all.keys) {
                val date = try {
                    LocalDate.parse(key)
                } catch (_: Exception) {
                    null
                }
                if (date != null && date.isBefore(olderThan)) remove(key)
            }
        }
    }

    private fun JSONObject?.toStats(): LimitStats =
        if (this == null) LimitStats() else LimitStats(optInt("b"), optInt("c"), optInt("m"), optInt("x"))
}
