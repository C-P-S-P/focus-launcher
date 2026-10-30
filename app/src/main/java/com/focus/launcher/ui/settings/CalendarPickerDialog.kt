package com.focus.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.R
import com.focus.launcher.data.CALENDAR_ALL
import com.focus.launcher.data.CalendarInfo
import com.focus.launcher.ui.components.FocusDialog
import com.focus.launcher.ui.components.Hairline
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.UnderlinedField
import com.focus.launcher.ui.theme.LocalFocusColors

/**
 * Picks the one calendar shown on the home screen. Phones with a few Google accounts easily carry
 * a dozen calendars, half of them called "Holidays", so the list can be searched by calendar or
 * account name, and every entry says how many events it has coming up: the quickest way to
 * recognise the one you live in.
 */
@Composable
internal fun CalendarPickerDialog(
    calendars: List<CalendarInfo>,
    upcomingCounts: Map<String, Int>,
    selectedKey: String?,
    workProfileBlocked: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = LocalFocusColors.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val shown = remember(calendars, query.text) {
        val q = query.text.trim()
        if (q.isEmpty()) calendars
        else calendars.filter { it.name.contains(q, ignoreCase = true) || it.account.contains(q, ignoreCase = true) }
    }

    FocusDialog(
        onDismiss,
        title = stringResource(R.string.settings_calendar_to_show),
        subtitle = stringResource(R.string.settings_calendar_picker_subtitle),
        tall = true,
    ) {
        UnderlinedField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.settings_calendar_search),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            imeAction = ImeAction.Search,
        )
        LazyColumn(Modifier.weight(1f)) {
            if (workProfileBlocked) {
                item(key = "work-note") {
                    Column {
                        T(
                            stringResource(R.string.settings_calendar_picker_work_note),
                            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            size = 13.sp, color = c.dim, lineHeight = 19.sp,
                        )
                        Hairline()
                    }
                }
            }
            items(shown, key = { it.key }) { calendar ->
                val count = upcomingCounts[calendar.key] ?: 0
                CalendarRow(
                    name = calendar.name,
                    account = calendar.account.takeIf { it.isNotEmpty() && it != calendar.name },
                    detail = if (count > 0) pluralStringResource(R.plurals.settings_calendar_upcoming, count, count) else stringResource(R.string.settings_calendar_empty),
                    selected = calendar.key == selectedKey,
                ) {
                    onPick(calendar.key)
                    onDismiss()
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    T(stringResource(R.string.settings_calendar_no_match, query.text.trim()), Modifier.padding(horizontal = 24.dp, vertical = 16.dp), size = 15.sp, color = c.dim)
                }
            }
            if (query.text.isBlank()) {
                item(key = "all") {
                    CalendarRow(stringResource(R.string.settings_calendar_all_together), account = null, detail = null, selected = selectedKey == CALENDAR_ALL) {
                        onPick(CALENDAR_ALL)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarRow(name: String, account: String?, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val c = LocalFocusColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(name, size = 17.sp, maxLines = 1)
            if (account != null) T(account, Modifier.padding(top = 2.dp), size = 12.sp, color = c.faint, maxLines = 1)
        }
        if (detail != null) T(detail, size = 12.sp, color = c.dim, maxLines = 1)
        if (selected) Box(Modifier.size(8.dp).background(c.fg, CircleShape))
    }
}
