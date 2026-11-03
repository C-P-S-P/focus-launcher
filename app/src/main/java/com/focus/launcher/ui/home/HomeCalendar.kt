package com.focus.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.CalEvent
import com.focus.launcher.data.WeekSummary
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.theme.LocalFocusColors
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle

/**
 * A short agenda from one calendar: the next few events, as text. The Mon-Sun strip with today
 * marked is optional and off by default, since the clock above already carries the date.
 */
@Composable
fun CalendarWidget(
    today: LocalDate,
    mondayStart: Boolean,
    events: List<CalEvent>,
    hasAccess: Boolean,
    use24h: Boolean,
    onClick: () -> Unit,
    onRequestAccess: () -> Unit,
    modifier: Modifier = Modifier,
    maxEvents: Int = 3,
    calendarName: String? = null,
    showWeekStrip: Boolean = false,
) {
    val c = LocalFocusColors.current
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Label("Calendar", Modifier.weight(1f), color = c.fg)
            if (calendarName != null && hasAccess) T(calendarName, size = 12.sp, color = c.faint, maxLines = 1)
        }
        VSpace(10.dp)
        if (showWeekStrip) {
            WeekStrip(today, mondayStart)
            VSpace(10.dp)
        }
        when {
            !hasAccess -> T("Show upcoming events  →", Modifier.clickable(onClick = onRequestAccess).padding(vertical = 4.dp), size = 14.sp, color = c.dim)
            events.isEmpty() -> T("Nothing in the next 7 days", size = 14.sp, color = c.dim)
            else -> for (event in events.take(maxEvents)) {
                Row(Modifier.padding(vertical = 3.dp)) {
                    T(eventWhen(event, today, use24h), Modifier.width(118.dp), size = 14.sp, color = c.dim, maxLines = 1)
                    T(event.title, Modifier.weight(1f), size = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(today: LocalDate, mondayStart: Boolean) {
    val c = LocalFocusColors.current
    val locale = currentLocale()
    val weekStart = WeekSummary.weekStartOf(today, mondayStart)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0L..6L) {
            val day = weekStart.plusDays(i)
            val isToday = day == today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(day.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, locale), size = 11.sp, color = if (isToday) c.fg else c.faint, maxLines = 1)
                VSpace(5.dp)
                Box(
                    Modifier.size(30.dp).then(if (isToday) Modifier.background(c.fg) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    T(
                        day.dayOfMonth.toString(), size = 14.sp,
                        color = when {
                            isToday -> c.bg
                            day.isBefore(today) -> c.faint
                            else -> c.fg
                        },
                        weight = if (isToday) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
                    )
                }
            }
        }
    }
}
