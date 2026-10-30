package com.focus.launcher.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.ReviewActivity
import com.focus.launcher.data.Settings
import com.focus.launcher.data.WeekSummary
import com.focus.launcher.util.Perms
import com.focus.launcher.util.formatDuration
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The end-of-week nudge to reflect. On the last day of the week, at the hour chosen in settings,
 * the week becomes "pending": a line appears on the home screen and (if allowed) a notification
 * is posted. Both open [ReviewActivity].
 *
 * An inexact alarm does the waking. Because a launcher is resumed dozens of times a day,
 * [checkDue] also runs on every home-screen resume, so a missed alarm only delays the nudge.
 */
object WeeklyReview {
    private const val CHANNEL_ID = "weekly_review"
    private const val NOTIFICATION_ID = 7

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_weekly), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_weekly_desc) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** When the review for the week starting at [weekStart] becomes due. */
    private fun dueAt(weekStart: LocalDate, s: Settings, now: ZonedDateTime): ZonedDateTime =
        weekStart.plusDays(6).atTime(s.reviewHour, 0).atZone(now.zone)

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = alarmIntent(context)
        alarms.cancel(operation)
        val s = Graph.settings.value
        if (!s.weeklyEnabled) return

        val now = ZonedDateTime.now()
        val thisWeek = WeekSummary.weekStartOf(now.toLocalDate(), s.weekStartsMonday)
        val next = dueAt(thisWeek, s, now).takeIf { it.isAfter(now) } ?: dueAt(thisWeek.plusWeeks(1), s, now)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), operation)
    }

    /**
     * Marks a finished (or finishing) week as waiting for review, once per week.
     * Returns the week that just became pending, or null when there is nothing new.
     */
    fun checkDue(): LocalDate? {
        val s = Graph.settings.value
        if (!s.weeklyEnabled) return null
        val state = Graph.state
        val now = ZonedDateTime.now()
        val thisWeek = WeekSummary.weekStartOf(now.toLocalDate(), s.weekStartsMonday)
        val dueWeek = if (!now.isBefore(dueAt(thisWeek, s, now))) thisWeek else thisWeek.minusWeeks(1)

        // First run: start the clock from here instead of announcing a week we never watched.
        val last = state.lastAnnouncedWeek ?: thisWeek.minusWeeks(1).also { state.lastAnnouncedWeek = it }
        if (!dueWeek.isAfter(last)) return null

        state.lastAnnouncedWeek = dueWeek
        state.setPendingReview(dueWeek)
        return dueWeek
    }

    suspend fun notify(context: Context, weekStart: LocalDate) {
        if (!Perms.canPostNotifications(context)) return
        val days = Graph.usage.loadDays(weekStart, weekStart.plusDays(6))
        val total = days.values.sumOf { it.total }
        val text = if (total > 0) {
            context.getString(R.string.weekly_notification_text, formatDuration(context.resources, total))
        } else {
            context.getString(R.string.weekly_notification_text_empty)
        }
        val open = PendingIntent.getActivity(
            context, 0, ReviewActivity.intent(context, weekStart),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.weekly_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    fun dismissNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, WeeklyReviewReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class WeeklyReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Graph.scope.launch {
            try {
                WeeklyReview.checkDue()?.let { WeeklyReview.notify(app, it) }
                WeeklyReview.schedule(app)
            } finally {
                pending.finish()
            }
        }
    }
}
