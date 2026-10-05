package com.focus.launcher.ui.home

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.focus.launcher.Graph
import com.focus.launcher.data.AppEntry
import com.focus.launcher.service.MediaListener
import com.focus.launcher.ui.components.AppPickerDialog
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.components.hasColourGlyphs
import com.focus.launcher.ui.components.monochrome
import com.focus.launcher.ui.components.press
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** A data class on purpose: a player that reports the same thing again changes no state, so nothing redraws. */
internal data class NowPlaying(
    val controller: MediaController,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    /** Length of the song in ms; 0 when the player does not say (radio, a live stream). */
    val duration: Long = 0L,
    /** Carried along unchanged by [copy], so a new position report leaves this object equal to the last one. */
    val progress: Progress = Progress(),
) {
    fun positionNow(): Long =
        (if (playing) progress.position + ((SystemClock.elapsedRealtime() - progress.at) * progress.speed).toLong() else progress.position)
            .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
}

/**
 * Where the song was ([position], ms) at [at] (elapsedRealtime) and how fast it moves; the player
 * reports this, the clock does the rest. Plain fields and not state on purpose: players report
 * their position several times a second, and as state every report recomposed the section
 * (measured: 6 to 8 frames a second on an otherwise still home screen). The once-a-second reader
 * in [ProgressText] is the only thing that looks here.
 */
internal class Progress {
    var position = 0L
    var at = 0L
    var speed = 1f
}

/**
 * What the music section knows, held where the home screen can read it too: with "hide when
 * nothing is playing", the section, the line above it and its share of the height all depend on
 * whether there is music.
 */
@Stable
internal class MusicState {
    /** Notification access ([MediaListener]): there is a player to talk to. Without it the audio system's word has to do. */
    var hasAccess by mutableStateOf(false)
    var now by mutableStateOf<NowPlaying?>(null)
        private set
    private var audioPlaying by mutableStateOf(false)

    /** See [lingerUntil]; 0 = not lingering. */
    var lingerUntil by mutableLongStateOf(0L)

    /** Derived, so that a new song title does not recompose those who only ask whether there is music. */
    val playing by derivedStateOf { if (hasAccess) now?.playing == true else audioPlaying }

    /** Stopped a moment ago, in plain sight: still here, so that play is one tap away. */
    val lingering get() = lingerUntil != 0L

    /** [live] = seen happening, by callback; false = found this way when the home screen came into sight. */
    fun report(now: NowPlaying?, live: Boolean) = track(live) { this.now = now }

    fun reportAudio(playing: Boolean, live: Boolean) = track(live) { audioPlaying = playing }

    private inline fun track(live: Boolean, change: () -> Unit) {
        val before = playing
        change()
        lingerUntil = lingerUntil(before, playing, live, SystemClock.elapsedRealtime(), lingerUntil)
    }
}

/** Music, not a key click or a notification sound. Apps without special rights are only told about players that are sounding. */
private fun List<AudioPlaybackConfiguration>?.hasMedia(): Boolean =
    this?.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA || it.audioAttributes.usage == AudioAttributes.USAGE_UNKNOWN } == true

/**
 * Follows the music while the home screen is visible and only then; everything is pushed by the
 * system, nothing polls. With notification access that is the active player (the song, its state);
 * without it, the audio system saying whether media is sounding. [enabled] = the section is
 * switched on at all.
 */
@Composable
internal fun rememberMusicState(enabled: Boolean, resumeCount: Int): MusicState {
    val context = LocalContext.current
    val state = remember { MusicState() }
    val hasAccess = remember(resumeCount, enabled) { enabled && MediaListener.hasAccess(context) }
    LifecycleStartEffect(enabled, hasAccess) {
        state.hasAccess = hasAccess
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val component = MediaListener.component(context)
        var watched: MediaController? = null
        val callback = object : MediaController.Callback() {
            // Players report their position every second or so. Take what the callback hands over
            // instead of fetching everything again (the metadata carries the album art).
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                state.report(state.now?.copy(title = metadata.title(), artist = metadata.artist(), duration = metadata.duration()), live = true)
            }

            override fun onPlaybackStateChanged(playback: PlaybackState?) {
                state.report(state.now?.withState(playback), live = true)
            }

            override fun onSessionDestroyed() = state.report(null, live = true)
        }
        fun watch(controllers: List<MediaController>?, live: Boolean) {
            watched?.unregisterCallback(callback)
            // The system lists the session that would receive a media key first.
            watched = controllers?.firstOrNull()?.also { it.registerCallback(callback) }
            state.report(watched?.read(), live)
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it, live = true) }
        val audioCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = state.reportAudio(configs.hasMedia(), live = true)
        }
        var listening = false
        if (hasAccess && sessions != null) {
            try {
                sessions.addOnActiveSessionsChangedListener(listener, component)
                watch(sessions.getActiveSessions(component), live = false)
            } catch (_: SecurityException) {
                state.report(null, live = false)
            }
        } else {
            state.report(null, live = false)
            if (enabled && audio != null) {
                audio.registerAudioPlaybackCallback(audioCallback, Handler(Looper.getMainLooper()))
                listening = true
            }
        }
        state.reportAudio(listening && audio?.activePlaybackConfigurations.hasMedia(), live = false)
        onStopOrDispose {
            watched?.unregisterCallback(callback)
            sessions?.removeOnActiveSessionsChangedListener(listener)
            if (listening) audio?.unregisterAudioPlaybackCallback(audioCallback)
        }
    }
    // The minute after the music stopped runs out on its own, whether or not anybody is looking.
    val until = state.lingerUntil
    LaunchedEffect(until) {
        if (until != 0L) {
            kotlinx.coroutines.delay((until - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (state.lingerUntil == until) state.lingerUntil = 0L
        }
    }
    return state
}

private fun MediaMetadata?.title(): String? = this?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }

private fun MediaMetadata?.artist(): String? =
    (this?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: this?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }

private fun MediaMetadata?.duration(): Long = this?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L

private fun NowPlaying.withState(state: PlaybackState?): NowPlaying {
    progress.position = state?.position?.coerceAtLeast(0L) ?: 0L
    progress.at = state?.lastPositionUpdateTime ?: 0L
    progress.speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
    return copy(playing = state?.state == PlaybackState.STATE_PLAYING)
}

private fun MediaController.read(): NowPlaying {
    val data = metadata
    return NowPlaying(this, data.title(), data.artist(), playing = false, duration = data.duration()).withState(playbackState)
}

/** "1:05", "1:02:40". */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.US, s / 60, s % 60)
}

/**
 * "1:02 / 2:00": played, then the song's length. Ticks once a second, and only while something plays and the
 * home screen is showing; paused, it is worked out once and stands still.
 */
@Composable
private fun ProgressText(now: NowPlaying?, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val text by produceState<String?>(null, now, owner) {
        fun read() = now?.let { n ->
            val at = n.positionNow()
            if (n.duration > 0) clock(at) + " / " + clock(n.duration) else if (at > 0) clock(at) else null
        }
        value = read()
        if (now?.playing == true) owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = read()
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    text?.let { T(it, modifier, size = 12.sp, color = LocalFocusColors.current.faint, maxLines = 1) }
}

/**
 * Music as a section of the home screen: what is playing, and previous · play or pause · next as
 * the three buttons every player has. With notification access the section talks to the active
 * player and knows the song; without it the buttons still work, as media keys that Android hands
 * to the last-used player, and one line offers the access that would bring the song's name.
 *
 * A tap on the section opens whatever is playing, or else [onOpenDefault] (the music app the user
 * chose); a long-press is [onChoose], like every other thing on the home screen that can be set.
 */
@Composable
internal fun MusicSection(state: MusicState, onOpenDefault: () -> Unit, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    val context = LocalContext.current
    val audio = context.getSystemService(AudioManager::class.java)
    val hasAccess = state.hasAccess
    val now = state.now
    val playing = state.playing

    // What the key did is heard from the system: the player's session, or the audio callback.
    fun key(code: Int) {
        audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    val controls = now?.controller?.transportControls

    val open = { now?.let { openPlayer(context, it.controller) } ?: onOpenDefault() }
    Column(modifier.fillMaxWidth().press(onLongClick = onChoose, onClick = open).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Music", Modifier.weight(1f), color = c.fg)
            // Each button is a 48dp square with its sign in the middle: Android widens a smaller
            // touch target to 48dp without showing it, so the glow sat off to one side of where
            // the finger was. The row is pulled right by the last square's margin, so the "next"
            // sign itself ends where the lines end.
            Row(Modifier.offset(x = 17.dp)) {
                MediaButton(Glyph.PREVIOUS, 14.dp, c.dim) { controls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                MediaButton(if (playing) Glyph.PAUSE else Glyph.PLAY, 17.dp, c.fg) {
                    if (controls == null) key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else if (playing) controls.pause() else controls.play()
                }
                MediaButton(Glyph.NEXT, 14.dp, c.dim) { controls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT) }
            }
        }
        when {
            !hasAccess -> T(
                "Show the song's name  →",
                Modifier.press { Perms.start(context, Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.padding(vertical = 3.dp),
                size = 14.sp, color = c.dim, maxLines = 1,
            )
            now?.title == null -> T("Nothing playing  ·  tap to open your music app", Modifier.padding(vertical = 3.dp), size = 14.sp, color = c.dim, maxLines = 1)
            else -> Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
                val line = listOfNotNull(now.title, now.artist).joinToString("  ·  ")
                // A title too long for the row ends in "…", a little short of the time. (A sliding
                // title was tried and dropped: it draws every frame for as long as it moves.)
                T(line, Modifier.weight(1f).then(if (hasColourGlyphs(line)) Modifier.monochrome() else Modifier), size = 14.sp, color = c.dim, maxLines = 1)
                // Played / length, in the corner where players put it. A composable of its own: it
                // changes once a second, and nothing else in the section should recompose with it.
                ProgressText(now, Modifier.padding(start = 17.dp))
            }
        }
    }
}

/**
 * A few lines of your own, kept by Focus and always in sight: a tap edits them then and there.
 * The notes app, if one was chosen, is the word at the right of the title ([appLabel], [onOpenApp]):
 * what it holds lives on its own servers, so Focus cannot show it, only open it. A long-press
 * anywhere offers the settings of the section.
 */
@Composable
fun NoteSection(note: String, appLabel: String?, maxLines: Int, onEdit: () -> Unit, onOpenApp: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    Column(modifier.fillMaxWidth().press(onLongClick = onLongClick, onClick = onEdit).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Note", Modifier.weight(1f).padding(vertical = 10.dp), color = c.fg)
            if (appLabel != null) {
                T("$appLabel  →", Modifier.press(onLongClick = onLongClick, onClick = onOpenApp).padding(start = 14.dp, top = 10.dp, bottom = 10.dp), size = 13.sp, color = c.dim, maxLines = 1)
            }
        }
        if (note.isBlank()) T("Tap to write a note", size = 14.sp, color = c.dim, maxLines = 1)
        else T(note, if (hasColourGlyphs(note)) Modifier.monochrome() else Modifier, size = 14.sp, color = c.dim, maxLines = maxLines, lineHeight = 20.sp)
        VSpace(8.dp)
    }
}

@Composable
private fun MediaButton(glyph: Glyph, side: Dp, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).press(onClick = onClick), contentAlignment = Alignment.Center) { MediaGlyph(glyph, side, color) }
}

/**
 * The music app a tap on the section opens. Only players are listed (see
 * [com.focus.launcher.data.AppRepository.musicPackages]); "All apps…" is there for the one that
 * declares nothing.
 */
@Composable
fun MusicAppPicker(apps: List<AppEntry>, subtitle: String?, onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    var players by remember { mutableStateOf<Set<String>?>(null) }
    var all by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { players = withContext(Dispatchers.IO) { Graph.apps.musicPackages() } }
    val found = players
    AppPickerDialog(
        title = "Music app",
        subtitle = subtitle,
        apps = if (all || found == null) (if (all) apps else emptyList()) else apps.filter { it.packageName in found },
        onDismiss = onDismiss,
        more = if (all || found == null) null else "All apps…" to { all = true },
        onPick = onPick,
    )
}

/** The only drawn signs besides the work badge, because words make poor buttons here. */
private enum class Glyph(val says: String) { PREVIOUS("Previous"), PLAY("Play"), PAUSE("Pause"), NEXT("Next") }

/**
 * Play, pause, previous, next as the plain shapes they are everywhere: a triangle, two bars, a
 * triangle against a bar. Drawn, like the work badge, so they stay the text's colour; the Unicode
 * characters for them turn into colour emoji on many phones.
 */
@Composable
private fun MediaGlyph(glyph: Glyph, side: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(side).semantics { contentDescription = glyph.says }) {
        val w = size.width
        val h = size.height
        fun triangle(from: Float, to: Float) = drawPath(
            Path().apply {
                moveTo(from, 0f)
                lineTo(to, h / 2)
                lineTo(from, h)
                close()
            },
            color,
        )
        val bar = w * 0.16f
        when (glyph) {
            Glyph.PLAY -> triangle(w * 0.12f, w)
            Glyph.PAUSE -> {
                drawRect(color, Offset(w * 0.14f, 0f), Size(w * 0.26f, h))
                drawRect(color, Offset(w * 0.60f, 0f), Size(w * 0.26f, h))
            }
            Glyph.NEXT -> {
                triangle(0f, w - bar - w * 0.06f)
                drawRect(color, Offset(w - bar, 0f), Size(bar, h))
            }
            Glyph.PREVIOUS -> {
                drawRect(color, Offset(0f, 0f), Size(bar, h))
                triangle(w, bar + w * 0.06f)
            }
        }
    }
}

/** The player's own "now playing" screen if it offers one, else just the player. */
private fun openPlayer(context: Context, controller: MediaController) {
    try {
        controller.sessionActivity?.send() ?: context.packageManager.getLaunchIntentForPackage(controller.packageName)?.let { Perms.start(context, it) }
    } catch (_: Exception) {
    }
}
