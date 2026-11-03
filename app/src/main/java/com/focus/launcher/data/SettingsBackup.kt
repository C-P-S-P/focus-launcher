package com.focus.launcher.data

import org.json.JSONObject

/**
 * One file holds every preference: layout, sections, gestures, fast apps, hidden apps, renames,
 * timers, theme. It is the very JSON the launcher already keeps, under a small envelope that names
 * the app and the schema, so a file can be told from any other JSON and an old file still reads
 * through [Settings.fromJson]'s tolerance for missing keys.
 *
 * Screen-time history and the limit tallies are not settings and are left out on purpose.
 */
object SettingsBackup {
    private const val APP = "focus-launcher"
    private const val FORMAT = 1

    fun write(settings: Settings): String = JSONObject()
        .put("app", APP)
        .put("format", FORMAT)
        .put("settings", settings.toJson())
        .toString(2)

    /** The settings held in [text], or null when it is not a Focus backup. */
    fun read(text: String): Settings? = try {
        val root = JSONObject(text)
        if (root.optString("app") != APP) null else Settings.fromJson(root.getJSONObject("settings"))
    } catch (_: Exception) {
        null
    }
}
