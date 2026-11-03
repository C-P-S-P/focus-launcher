package com.focus.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings as SystemSettings
import android.telecom.TelecomManager
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.focus.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

/**
 * How Focus sorts apps for the automatic timers.
 *
 * Android's own label is too coarse to use directly: its "social" category officially covers
 * "messaging, communication, email, or social network apps" (the Play Store files Social,
 * Communication and Dating under it), so Gmail, Chrome and WhatsApp carry the same label as
 * Instagram. [AppRepository.categorize] splits that label apart.
 */
enum class AppCategory(@StringRes val labelRes: Int) {
    /** Feeds, social networks, dating. Gets the automatic "social apps" limit. */
    SOCIAL(R.string.category_social),
    /** Whatever Android lists as a game. Gets the automatic "games" limit. */
    GAME(R.string.category_game),
    /** Video and streaming. Has its own automatic limit, which is off unless switched on. */
    VIDEO(R.string.category_video),
    /** Browsers, mail, messaging, calls, contacts. Tools; never limited automatically. */
    COMMUNICATION(R.string.category_communication),
    /** Android says "social" but Focus cannot tell feed from tool. Not limited; offered in settings. */
    UNSURE(R.string.category_unsure),
    OTHER(R.string.category_other),
}

/** One launchable activity. Text only: the launcher never loads an icon. */
data class AppEntry(
    /** Stable id used in settings: flattened component, plus "#serial" for work-profile apps. */
    val key: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val systemLabel: String,
    /** What is shown: the user's custom name if they renamed the app, else [systemLabel]. */
    val label: String,
    val firstInstallTime: Long,
    val isSystem: Boolean,
    val category: AppCategory,
    val isWorkProfile: Boolean,
    /** When the package was last updated; lets the next start skip re-reading an unchanged label. */
    val updatedAt: Long = 0L,
) {
    val isRenamed: Boolean get() = label != systemLabel
}

class AppRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val pm: PackageManager = context.packageManager
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val myUser: UserHandle = Process.myUserHandle()

    private val raw = MutableStateFlow<List<AppEntry>>(emptyList())
    private val categoryCache = ConcurrentHashMap<String, AppCategory>()

    // Declared before init {}: the first reload() starts on another thread while this object is
    // still being constructed.
    private val scanLock = Any()
    private val cacheFile = File(context.filesDir, "apps.json")

    @Volatile
    private var protectedCache: Set<String>? = null

    @Volatile
    private var homeCache: Set<String>? = null

    /** True for launchers (this one included). Cached: the timer service asks on every app switch. */
    fun isHomeApp(pkg: String): Boolean = (homeCache ?: homePackages().also { homeCache = it }).contains(pkg)

    /** Refreshed by every scan; see [findCommunicationApps]. */
    @Volatile
    private var communication: Set<String> = emptySet()

    private val _loaded = MutableStateFlow(false)
    /** False until the first scan finishes, so the UI can tell "loading" from "no apps". */
    val loaded: StateFlow<Boolean> = _loaded

    /** Every launchable app (hidden ones included), renamed and sorted for display. */
    val apps: StateFlow<List<AppEntry>> =
        combine(raw, settings.flow.map { it.renames }.distinctUntilChanged()) { list, renames ->
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            list.map { e -> renames[e.key]?.let { e.copy(label = it) } ?: e }
                .sortedWith { a, b ->
                    val c = collator.compare(a.label, b.label)
                    if (c != 0) c else a.key.compareTo(b.key)
                }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val callback = object : LauncherApps.Callback() {
        // One app changed: look at that app only. A night of Play Store updates fires dozens of
        // these, and rescanning every label for each of them cost seconds of CPU apiece.
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refreshPackage(packageName, user)
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = refreshPackage(packageName, user)
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = refreshPackage(packageName, user)
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    /** Full scan. Runs at process start and for the rare bulk events (SD card, profile switched on). */
    fun reload() {
        scope.launch(Dispatchers.IO) {
            synchronized(scanLock) {
                // Show the list remembered from last time at once, then correct it.
                val remembered = readCache()
                if (raw.value.isEmpty() && remembered.isNotEmpty()) {
                    raw.value = remembered
                    _loaded.value = true
                }
                val fresh = scan(reuse = remembered.ifEmpty { raw.value })
                categoryCache.clear()
                // Recompute here, on the IO thread, so the UI never pays for these package queries.
                protectedCache = null
                protectedPackages()
                if (fresh != raw.value) {
                    raw.value = fresh
                    writeCache(fresh)
                }
                _loaded.value = true
            }
        }
    }

    private fun refreshPackage(packageName: String?, user: UserHandle?) {
        if (packageName == null || user == null) return reload()
        scope.launch(Dispatchers.IO) {
            synchronized(scanLock) {
                val untouched = raw.value.filterNot { it.packageName == packageName && it.user == user }
                communication = findCommunicationApps() // a newly installed browser or mail app counts at once
                val fresh = untouched + entriesFor(packageName, user, reuse = emptyMap())
                categoryCache.remove(packageName)
                protectedCache = null
                protectedPackages()
                if (fresh != raw.value) {
                    raw.value = fresh
                    writeCache(fresh)
                }
            }
        }
    }

    /** Lets go of everything that can be rebuilt; called when the system is short of memory. */
    fun trimMemory() {
        categoryCache.clear()
    }

    // ---- remembered app list -----------------------------------------------------------------

    private fun currentLocaleTag(): String = context.resources.configuration.locales[0].toLanguageTag()

    private fun writeCache(entries: List<AppEntry>) {
        try {
            val array = JSONArray()
            for (e in entries) {
                array.put(JSONObject().apply {
                    put("c", e.component.flattenToString())
                    put("u", if (e.isWorkProfile) userManager.getSerialNumberForUser(e.user) else 0L)
                    put("l", e.systemLabel)
                    put("t", e.firstInstallTime)
                    put("m", e.updatedAt)
                    put("s", e.isSystem)
                    put("g", e.category.name)
                })
            }
            // Labels are translated, so they are only good for the language they were read in.
            cacheFile.writeText(JSONObject().put("v", 2).put("locale", currentLocaleTag()).put("apps", array).toString())
        } catch (_: Exception) {
        }
    }

    private fun readCache(): List<AppEntry> = try {
        val root = JSONObject(cacheFile.readText())
        val array = if (root.optString("locale") == currentLocaleTag()) root.getJSONArray("apps") else JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val component = ComponentName.unflattenFromString(o.getString("c")) ?: return@mapNotNull null
            val serial = o.optLong("u", 0L)
            val user = (if (serial == 0L) myUser else userManager.getUserForSerialNumber(serial)) ?: return@mapNotNull null
            val label = o.getString("l")
            AppEntry(
                key = if (serial == 0L) component.flattenToString() else component.flattenToString() + "#" + serial,
                packageName = component.packageName,
                component = component,
                user = user,
                systemLabel = label,
                label = label,
                firstInstallTime = o.optLong("t"),
                isSystem = o.optBoolean("s"),
                category = AppCategory.entries.firstOrNull { it.name == o.optString("g") } ?: AppCategory.OTHER,
                isWorkProfile = serial != 0L,
                updatedAt = o.optLong("m"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Reads the launchable apps of every profile. Loading a label means opening that app's
     * resources, by far the slowest part (seconds for 200 apps), so a label is only loaded when
     * the app is new or has been updated since [reuse] was written.
     */
    private fun scan(reuse: List<AppEntry>): List<AppEntry> {
        communication = findCommunicationApps()
        homeCache = null
        val known = reuse.associateBy { it.key }
        val out = ArrayList<AppEntry>()
        val profiles = try {
            launcherApps.profiles.ifEmpty { listOf(myUser) }
        } catch (_: Exception) {
            listOf(myUser)
        }
        for (user in profiles) out += entriesFor(null, user, known)
        return out
    }

    private fun entriesFor(packageName: String?, user: UserHandle, reuse: Map<String, AppEntry>): List<AppEntry> {
        val activities = try {
            launcherApps.getActivityList(packageName, user)
        } catch (_: Exception) {
            emptyList()
        }
        val isMain = user == myUser
        val serial = if (isMain) 0L else userManager.getSerialNumberForUser(user)
        val updateTimes = HashMap<String, Long>()
        return activities.map { info ->
            val component = info.componentName
            val key = if (isMain) component.flattenToString() else component.flattenToString() + "#" + serial
            // Update times are only visible for this user's packages; work-profile labels are re-read.
            val updatedAt = if (!isMain) 0L else updateTimes.getOrPut(component.packageName) {
                try {
                    pm.getPackageInfo(component.packageName, 0).lastUpdateTime
                } catch (_: Exception) {
                    0L
                }
            }
            val remembered = reuse[key]?.takeIf { updatedAt != 0L && it.updatedAt == updatedAt }
            val label = remembered?.systemLabel
                ?: info.label?.toString()?.trim().orEmpty().ifEmpty { component.packageName }
            val appInfo = info.applicationInfo
            // Our own entry ("Focus Settings") stays in the list so settings are one search away.
            AppEntry(
                key = key,
                packageName = component.packageName,
                component = component,
                user = user,
                systemLabel = label,
                label = label,
                firstInstallTime = info.firstInstallTime,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                category = categorize(appInfo),
                isWorkProfile = !isMain,
                updatedAt = updatedAt,
            )
        }
    }

    // ---- lookups ----------------------------------------------------------------------------

    fun byKey(key: String): AppEntry? = apps.value.firstOrNull { it.key == key }

    fun byPackage(pkg: String): AppEntry? =
        apps.value.firstOrNull { it.packageName == pkg && !it.isWorkProfile }
            ?: apps.value.firstOrNull { it.packageName == pkg }

    /** Display name for any package, including ones without a launcher entry or already uninstalled. */
    fun labelForPackage(pkg: String): String {
        byPackage(pkg)?.let { return it.label }
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    fun categoryOf(pkg: String): AppCategory {
        categoryCache[pkg]?.let { return it }
        val category = byPackage(pkg)?.category ?: try {
            categorize(pm.getApplicationInfo(pkg, 0))
        } catch (_: Exception) {
            AppCategory.OTHER
        }
        categoryCache[pkg] = category
        return category
    }

    /** One sentence on how [pkg] was sorted and why, shown to the user next to its timer. */
    @StringRes
    fun describeCategoryRes(pkg: String): Int = when (categoryOf(pkg)) {
        AppCategory.SOCIAL -> R.string.category_explain_social
        AppCategory.GAME -> R.string.category_explain_game
        AppCategory.VIDEO -> R.string.category_explain_video
        AppCategory.COMMUNICATION -> R.string.category_explain_communication
        AppCategory.UNSURE -> R.string.category_explain_unsure
        AppCategory.OTHER -> R.string.category_explain_other
    }

    /**
     * Sorts one app. Order matters:
     *  1. a curated list of social networks wins over everything (many declare no category);
     *  2. games are taken from Android's label, which the Play Store sets reliably;
     *  3. video is a curated list plus Android's label;
     *  4. anything else Android calls "social" is a tool if it is a known communication app, or if
     *     the system says it can open web pages, send mail or SMS, or dial. Otherwise Focus does
     *     not guess: it is [AppCategory.UNSURE], left alone, and listed in settings for the user.
     */
    private fun categorize(info: ApplicationInfo): AppCategory {
        val pkg = info.packageName
        @Suppress("DEPRECATION")
        val flaggedGame = (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        return when {
            pkg in KNOWN_SOCIAL -> AppCategory.SOCIAL
            info.category == ApplicationInfo.CATEGORY_GAME || flaggedGame -> AppCategory.GAME
            pkg in KNOWN_VIDEO || info.category == ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
            info.category == ApplicationInfo.CATEGORY_SOCIAL ->
                if (pkg in KNOWN_COMMUNICATION || pkg in communication || COMMUNICATION_HINTS.any { it in pkg }) {
                    AppCategory.COMMUNICATION
                } else {
                    AppCategory.UNSURE
                }
            else -> AppCategory.OTHER
        }
    }

    /** Packages the system itself reports as browsers, mail clients, SMS apps or dialers. */
    private fun findCommunicationApps(): Set<String> {
        val out = HashSet<String>()
        val probes = listOf(
            // A generic web address only matches real browsers; apps that claim their own links
            // (instagram.com and the like) filter by host and do not match.
            Intent(Intent.ACTION_VIEW, "http://www.example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            Intent(Intent.ACTION_SENDTO, "mailto:".toUri()),
            Intent(Intent.ACTION_SENDTO, "smsto:".toUri()),
            Intent(Intent.ACTION_DIAL, "tel:".toUri()),
        )
        for (probe in probes) {
            try {
                pm.queryIntentActivities(probe, PackageManager.MATCH_ALL).forEach { out += it.activityInfo.packageName }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * Packages that play music, by what they declare rather than by name: a media browser service
     * (what Android Auto and Bluetooth use to find players), the "music app" launcher category, or
     * the audio app category the Play Store sets. Asked only when the music picker opens; blocking.
     */
    fun musicPackages(): Set<String> {
        val out = HashSet<String>()
        try {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).forEach { out += it.serviceInfo.packageName }
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0).forEach { out += it.activityInfo.packageName }
        } catch (_: Exception) {
        }
        for (app in apps.value) {
            try {
                if (pm.getApplicationInfo(app.packageName, 0).category == ApplicationInfo.CATEGORY_AUDIO) out += app.packageName
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * Apps that must never be walled off: this launcher, the dialer (emergency calls), system
     * settings (the way out of any misconfiguration) and other home screens.
     */
    fun canLimit(pkg: String): Boolean = pkg !in protectedPackages()

    fun protectedPackages(): Set<String> {
        protectedCache?.let { return it }
        val set = HashSet<String>()
        set += context.packageName
        set += "com.android.systemui"
        set += "com.android.settings"
        set += homePackages()
        try {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { set += it }
        } catch (_: Exception) {
        }
        pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }?.let { set += it }
        pm.resolveActivity(Intent(SystemSettings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }?.let { set += it }
        protectedCache = set
        return set
    }

    /**
     * Every installed home screen, this one included. Time on a home screen is not "screen time".
     *
     * The Settings app also declares a HOME activity (FallbackHome, shown for a moment while the
     * phone boots) at priority -1000. Taking it for a launcher made every minute spent in Settings
     * vanish from the day's total, so only real launchers, at normal priority, are returned.
     */
    fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.priority >= 0 }
            .mapTo(HashSet()) { it.activityInfo.packageName }
            .apply { add(context.packageName) }
    }

    // ---- actions ----------------------------------------------------------------------------

    fun launch(entry: AppEntry, sourceBounds: Rect? = null, options: Bundle? = null): Boolean = try {
        launcherApps.startMainActivity(entry.component, entry.user, sourceBounds, options)
        true
    } catch (_: Exception) {
        false
    }

    fun openAppInfo(entry: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
        } catch (_: Exception) {
        }
    }

    fun uninstall(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, "package:${entry.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (entry.isWorkProfile) intent.putExtra(Intent.EXTRA_USER, entry.user)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** Feeds, social networks and dating: where the scrolling happens. */
        val KNOWN_SOCIAL = setOf(
            "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
            "com.facebook.katana", "com.facebook.lite",
            "com.twitter.android", "com.twitter.android.lite",
            "com.snapchat.android",
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill",
            "com.reddit.frontpage",
            "com.pinterest",
            "com.linkedin.android",
            "com.tumblr",
            "com.discord",
            "com.bereal.ft",
            "org.joinmastodon.android",
            "xyz.blueskyweb.app",
            "com.quora.android",
            "in.mohalla.sharechat", "in.mohalla.video", "com.eterno.shortvideos",
            "com.vkontakte.android", "com.sina.weibo",
            "co.hinge.app", "com.tinder", "com.bumble.app", "com.okcupid.okcupid",
        )

        /** Video and streaming. */
        val KNOWN_VIDEO = setOf(
            "com.google.android.youtube", "app.revanced.android.youtube", "com.google.android.apps.youtube.kids",
            "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
            "in.startv.hotstar", "com.jio.media.ondemand", "com.sonyliv", "com.graymatrix.did",
            "tv.twitch.android.app",
        )

        /** Tools that Android files under "social": never limited automatically. */
        val KNOWN_COMMUNICATION = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.google.android.apps.dynamite", "com.facebook.orca",
            "com.viber.voip", "jp.naver.line.android", "com.tencent.mm",
            "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android",
            "com.google.android.contacts", "com.google.android.dialer", "com.truecaller",
            "com.google.android.apps.tachyon", "us.zoom.videomeetings", "com.microsoft.teams", "com.Slack", "com.skype.raider",
            "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx",
            "com.opera.browser", "com.duckduckgo.mobile.android", "com.sec.android.app.sbrowser",
        )

        /** Package-name fragments that mark a communication tool nobody has listed yet. */
        val COMMUNICATION_HINTS = listOf("browser", "mail", "messag", ".sms", "contacts", "dialer")
    }
}
