package com.focus.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsBackupTest {
    private val changed = Settings(
        dark = false, font = FontChoice.MONO, favorites = listOf("a/b", "c/d#10"),
        hidden = setOf("x/y"), renames = mapOf("a/b" to "Mail"), appLimits = mapOf("com.x" to 15, "com.y" to 0),
        socialDefaultMin = 45, doubleTapLock = false, note = "milk", showMusic = true,
    )

    @Test fun `what goes out comes back`() {
        assertEquals(changed, SettingsBackup.read(SettingsBackup.write(changed)))
    }

    @Test fun `defaults survive too`() {
        assertEquals(Settings(), SettingsBackup.read(SettingsBackup.write(Settings())))
    }

    @Test fun `anything that is not a Focus backup is refused`() {
        assertNull(SettingsBackup.read("not json"))
        assertNull(SettingsBackup.read("{}"))
        assertNull(SettingsBackup.read("""{"app":"other","settings":{}}"""))
    }

    @Test fun `a backup with keys from an older version still reads`() {
        val old = """{"app":"focus-launcher","format":1,"settings":{"dark":false,"someKeyFromTheFuture":3}}"""
        assertEquals(false, SettingsBackup.read(old)!!.dark)
    }
}
