package com.focus.launcher.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.focus.launcher.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * What a new user cannot guess, taught where it happens: one line on the home screen at a time,
 * gone the moment the thing has been done once (or the line is tapped away), then the next.
 * A new gesture or hidden feature gets its line here.
 */
enum class Tip(@StringRes val gestureRes: Int, @StringRes val resultRes: Int) {
    // Two or three words each side, so that a tip is one glance and fits one line on any phone.
    SWIPE_LEFT(R.string.tip_swipe_left, R.string.tip_swipe_left_result),
    APP_MENU(R.string.tip_app_menu, R.string.tip_app_menu_result),
    SWIPE_UP(R.string.tip_swipe_up, R.string.tip_swipe_up_result),
    SWIPE_RIGHT(R.string.tip_swipe_right, R.string.tip_swipe_right_result),
    SWIPE_DOWN(R.string.tip_swipe_down, R.string.tip_swipe_down_result),
    CLOCK(R.string.tip_clock, R.string.tip_clock_result),
    SCREEN_TIME(R.string.tip_screen_time, R.string.tip_screen_time_result),
    CORNERS(R.string.tip_corners, R.string.tip_corners_result),
    SECTIONS(R.string.tip_sections, R.string.tip_sections_result),
    /** Only shown while one of the two sections is on; see `HomeScreen`. */
    SECTION_APPS(R.string.tip_section_apps, R.string.tip_section_apps_result),
    SETTINGS(R.string.tip_settings, R.string.tip_settings_result),
    // Last on purpose: it turns the screen off, which would cut any tip after it short.
    DOUBLE_TAP(R.string.tip_double_tap, R.string.tip_double_tap_result),
}

/** Small bits of launcher state that are not preferences: review reminders and reflection notes. */
class AppState(context: Context) {
    private val prefs = context.getSharedPreferences("focus_state", Context.MODE_PRIVATE)
    private val settingsExisted = context.getSharedPreferences("focus_settings", Context.MODE_PRIVATE).contains("settings_json")

    private val _pendingReview = MutableStateFlow(readDate(KEY_PENDING))
    /** Start date of a week whose review is waiting to be opened; shown as a line on the home screen. */
    val pendingReview: StateFlow<LocalDate?> = _pendingReview

    /** Start date of the most recent week the user has already been told about. */
    var lastAnnouncedWeek: LocalDate?
        get() = readDate(KEY_ANNOUNCED)
        set(value) = prefs.edit { putString(KEY_ANNOUNCED, value?.toString()) }

    /**
     * The introduction is for someone new. An install that already has saved settings when this
     * first ships is not new: it is never shown there unasked.
     */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL, settingsExisted)
        set(value) = prefs.edit { putBoolean(KEY_TUTORIAL, value) }

    private val _tip = MutableStateFlow(Tip.entries.getOrNull(prefs.getInt(KEY_TIP, if (settingsExisted) Tip.entries.size else 0)))
    /** The one tip showing on the home screen right now; null once all have been done or skipped. */
    val tip: StateFlow<Tip?> = _tip

    /** The user just did [what]. If that is what the current tip teaches, the next one takes its place. */
    fun did(what: Tip) {
        if (_tip.value == what) nextTip()
    }

    fun nextTip() {
        setTip((_tip.value ?: return).ordinal + 1)
    }

    fun restartTips() = setTip(0)

    private fun setTip(index: Int) {
        prefs.edit { putInt(KEY_TIP, index) }
        _tip.value = Tip.entries.getOrNull(index)
    }

    fun setPendingReview(weekStart: LocalDate?) {
        prefs.edit { putString(KEY_PENDING, weekStart?.toString()) }
        _pendingReview.value = weekStart
    }

    /** The one-line intention the user wrote at the end of a weekly review. */
    fun intention(weekStart: LocalDate): String = prefs.getString("intention_$weekStart", "") ?: ""

    fun setIntention(weekStart: LocalDate, text: String) {
        prefs.edit { putString("intention_$weekStart", text.trim()) }
    }

    private fun readDate(key: String): LocalDate? = try {
        prefs.getString(key, null)?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEY_TUTORIAL = "tutorial_seen"
        const val KEY_TIP = "tip_index"
        const val KEY_PENDING = "pending_review_week"
        const val KEY_ANNOUNCED = "last_announced_week"
    }
}
