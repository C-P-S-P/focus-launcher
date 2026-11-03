package com.focus.launcher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.CalendarContract
import com.focus.launcher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** [Settings.calendarKey] values that are not a real calendar; real ones are [CalendarInfo.key]. */
const val CALENDAR_AUTO = "auto"
const val CALENDAR_ALL = "all"

data class CalEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
) {
    /** All-day events are stored as UTC midnights; read them in UTC or they drift a day. */
    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
}

/** One calendar on the phone, e.g. "Family" under name@gmail.com, or one inside the Work profile. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val isPrimary: Boolean,
    /** Lives in the Android Work profile and is read through the cross-profile calendar API. */
    val work: Boolean = false,
) {
    /** Stable id for settings. Personal and work calendars have separate id spaces. */
    val key: String get() = (if (work) "w:" else "p:") + id

    /** For the picker: "Family  ·  name@gmail.com", or just the name when it already is the account. */
    val label: String get() = if (account.isEmpty() || account == name) name else "$name  ·  $account"

    /** For the home screen: a calendar named after its e-mail address is shown without the domain. */
    val shortName: String get() = if ('@' in name) name.substringBefore('@') else name
}

/** What the home screen's calendar section shows: one calendar (null = all) and its next events. */
class Agenda(val key: String, val calendar: CalendarInfo?, val events: List<CalEvent>, val loadedAt: Long)

object CalendarRepository {
    @Volatile
    private var cachedAgenda: Agenda? = null

    /** Forget the cached agenda; the calendar provider reported a change. */
    fun invalidate() {
        cachedAgenda = null
    }

    /**
     * The agenda for the calendar setting [key]. Working it out takes three provider queries
     * (list calendars, count events to find the lived-in one, read the next events), and a launcher
     * comes back to its home screen a hundred times a day, so the answer is kept until the
     * calendar actually changes ([invalidate]), an event in it ends, or it is ten minutes old.
     */
    suspend fun agenda(context: Context, key: String): Agenda {
        val now = System.currentTimeMillis()
        cachedAgenda?.let { cached ->
            val fresh = cached.key == key && now - cached.loadedAt < AGENDA_MAX_AGE_MS && cached.events.none { !it.allDay && it.end < now }
            if (fresh) return cached
        }
        val calendar = choose(context, key, calendars(context))
        return Agenda(key, calendar, upcoming(context, calendar), now).also { cachedAgenda = it }
    }

    private const val AGENDA_MAX_AGE_MS = 10 * 60_000L

    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Every calendar synced to the phone, primary ones first. */
    suspend fun calendars(context: Context): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasAccess(context)) return@withContext emptyList()
        val out = ArrayList<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.IS_PRIMARY,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val account = c.getString(2).orEmpty()
                    out += CalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1)?.trim().orEmpty().ifEmpty { account.ifEmpty { "Calendar" } },
                        account = account,
                        isPrimary = c.getInt(3) != 0,
                    )
                }
            }
        } catch (_: Exception) {
        }
        out.sortedWith(compareByDescending<CalendarInfo> { it.isPrimary }.thenBy { it.name.lowercase() }) + workCalendars(context)
    }

    /** True when the phone has an Android Work profile (whose calendars live behind a separate door). */
    fun hasWorkProfile(context: Context): Boolean = try {
        (context.getSystemService(UserManager::class.java)?.userProfiles?.size ?: 1) > 1
    } catch (_: Exception) {
        false
    }

    /**
     * Calendars inside the Work profile, through Android's cross-profile calendar API. This only
     * returns anything when the organisation that manages the profile has allowed this app; when
     * it has not, the list is simply empty. The API never exposes calendar names.
     */
    private fun workCalendars(context: Context): List<CalendarInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val out = ArrayList<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.ENTERPRISE_CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val primary = c.getInt(1) != 0
                    val name = if (primary) context.getString(R.string.calendar_work)
                    else context.getString(R.string.calendar_work_numbered, out.size + 1)
                    out += CalendarInfo(c.getLong(0), name, context.getString(R.string.calendar_work_profile), primary, work = true)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Which single calendar the home screen shows: the one picked in settings, or, until the user
     * picks, the main calendar that actually has something coming up. (The first primary calendar
     * is often an empty "local account" or an old address.) Null means "all calendars together".
     */
    suspend fun choose(context: Context, key: String, available: List<CalendarInfo>): CalendarInfo? = when (key) {
        CALENDAR_ALL -> null
        CALENDAR_AUTO -> busiest(context, available)
        else -> available.firstOrNull { it.key == key } ?: busiest(context, available)
    }

    private suspend fun busiest(context: Context, available: List<CalendarInfo>): CalendarInfo? {
        val personal = available.filter { !it.work }
        if (personal.isEmpty()) return available.firstOrNull()
        val counts = upcomingCounts(context, available)
        val mains = personal.filter { it.isPrimary }
        return mains.filter { (counts[it.key] ?: 0) > 0 }.maxByOrNull { counts[it.key] ?: 0 }
            ?: personal.filter { (counts[it.key] ?: 0) > 0 }.maxByOrNull { counts[it.key] ?: 0 }
            ?: mains.firstOrNull()
            ?: personal.first()
    }

    /** [CalendarInfo.key] -> number of events in the next two weeks. Tells a lived-in calendar from an empty one. */
    suspend fun upcomingCounts(context: Context, available: List<CalendarInfo>): Map<String, Int> = withContext(Dispatchers.IO) {
        val out = HashMap<String, Int>()
        if (!hasAccess(context)) return@withContext out
        val now = System.currentTimeMillis()
        fun count(base: android.net.Uri, prefix: String) {
            try {
                val uri = base.buildUpon().also {
                    ContentUris.appendId(it, now)
                    ContentUris.appendId(it, now + 14 * 86_400_000L)
                }.build()
                context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.CALENDAR_ID), null, null, null)
                    ?.use { c -> while (c.moveToNext()) out.merge(prefix + c.getLong(0), 1, Int::plus) }
            } catch (_: Exception) {
            }
        }
        count(CalendarContract.Instances.CONTENT_URI, "p:")
        if (available.any { it.work } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            count(CalendarContract.Instances.ENTERPRISE_CONTENT_URI, "w:")
        }
        out
    }

    /** The next few events, soonest first, from [calendar] (null = every visible personal calendar). */
    suspend fun upcoming(context: Context, calendar: CalendarInfo?, max: Int = 3, daysAhead: Int = 7): List<CalEvent> =
        withContext(Dispatchers.IO) {
            if (!hasAccess(context)) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val work = calendar?.work == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val base = if (work) CalendarContract.Instances.ENTERPRISE_CONTENT_URI else CalendarContract.Instances.CONTENT_URI
            val uri = base.buildUpon().also {
                ContentUris.appendId(it, now)
                ContentUris.appendId(it, now + daysAhead * 86_400_000L)
            }.build()
            val calendarId = calendar?.id
            // The cross-profile door only admits a short list of columns, so keep its query minimal.
            val selection = buildString {
                // "Visible" is a switch inside the calendar app. It filters the everything-together
                // view, but a calendar the user picked here by name is shown regardless.
                if (calendar == null) append("${CalendarContract.Instances.VISIBLE} = 1")
                if (calendarId != null) append(if (isEmpty()) "" else " AND ").append("${CalendarContract.Instances.CALENDAR_ID} = ?")
            }.ifEmpty { null }
            val out = ArrayList<CalEvent>()
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.ALL_DAY,
                    ),
                    selection,
                    calendarId?.let { arrayOf(it.toString()) },
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { c ->
                    val today = LocalDate.now()
                    while (c.moveToNext() && out.size < max) {
                        val event = CalEvent(
                            title = stripEmoji(c.getString(0).orEmpty()).ifEmpty { context.getString(R.string.calendar_no_title) },
                            begin = c.getLong(1),
                            end = c.getLong(2),
                            allDay = c.getInt(3) != 0,
                        )
                        // Skip all-day entries that really belong to yesterday (UTC storage quirk).
                        if (event.allDay && event.date().isBefore(today)) continue
                        if (!event.allDay && event.end < now) continue
                        out += event
                    }
                }
            } catch (_: Exception) {
            }
            out
        }

    /**
     * Removes emoji and the invisible characters that glue them together. The launcher is text in
     * black and white; a green heart in an event title has no place in it.
     */
    fun stripEmoji(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val emoji = cp in 0x1F000..0x1FAFF ||   // pictographs, emoticons, transport, flags, skin tones
                cp in 0x2600..0x27BF ||             // misc symbols and dingbats
                cp in 0x2300..0x23FF ||             // watch, hourglass, media controls
                cp in 0x2B00..0x2BFF ||             // stars, large squares, arrows
                cp in 0xE0020..0xE007F ||           // tag characters used in some flags
                cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 // variation selector, joiner, keycap
            if (!emoji) out.appendCodePoint(cp)
        }
        return out.toString().replace(Regex("\\s{2,}"), " ").trim()
    }
}
