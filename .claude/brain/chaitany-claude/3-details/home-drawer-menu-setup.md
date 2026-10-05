# Tier 3 · Home, drawer, the long-press menu, and Setup

Code: `ui/home/HomeScreen.kt`, `ui/home/HomeWidgets.kt`, `ui/drawer/DrawerScreen.kt`,
`ui/drawer/AppMenu.kt`, `ui/settings/SetupPage.kt`, `ui/settings/LauncherPages.kt`,
`data/Settings.kt`. Layout fitting, touch feedback and motion are in `ui-system.md`.

## Home (page 0), top to bottom, after the owner's sketch
1. **Clock**, three styles (`ClockStyle`). **SPLIT** is the default since 2026-09-20, from the
   owner's second sketch: the time, date and battery on the left, one section on the right, and a
   single vertical line between them; no frame (`SplitClockRow`). Both halves hug the line (clock
   text right-aligned, section text left-aligned), and the row sits near the top as a header.
   The right half (`splitSide`) is the calendar's next two events, two lines each
   (`SplitCalendar`), or today's screen time (`SplitScreenTime`); long-press it to choose, which
   also switches the calendar section on and asks for access. **Whatever is in the right half is
   not repeated further down**, and the calendar can only be there while `showCalendar` is on,
   otherwise it is screen time. RING (arc = battery % or day passed) and PLAIN remain options.
   Tap the clock = `clockTap` (alarms by default, or calendar / screen time / battery / nothing /
   any app); long-press opens the chooser (`ClockTapDialog`).
2. **Screen time** (`ScreenTimeLine`, always there, no setting): "Screen Time", today's total at
   24sp, "N% of today" (of 24 h). Tapping it opens the review, or the usage-access switch if that
   is still missing. The 24-hour bar that used to be a home section now lives only in the review
   (a contributor's change; open decision 14 in `2-overview/user-and-decisions.md`).
   **Section**, a toggle: the calendar agenda (`calendar.md`; off until calendar access is given).
   **More sections**, each a toggle in Settings → Home screen and off by default, drawn under the
   calendar in this order and **separated by 1dp lines in the split clock's line colour
   (`faint`), never by boxes** (a contributor's request, after the owner's first sketch; an
   earlier round of rounded cards, hosted widgets and drag-to-arrange was dropped entirely):
   - **Music** (`MusicSection`): "MUSIC" with three drawn buttons (previous, play or pause, next:
     `MediaGlyph`, Canvas shapes in the text colour; words were tried first and he asked for
     buttons), and under it "title · artist" (dim, cut with "…") with **"played / length"**
     ("1:02 / 2:00", faint) in the right corner. (Tried and taken back the same evening: the time
     between the word and the buttons; a marquee for long titles, which draws every frame while
     it moves.)
     (`ProgressText`, a composable of its own so that nothing else recomposes with it: from `PlaybackState.position / lastPositionUpdateTime / playbackSpeed`
     and `METADATA_KEY_DURATION`; one text update a second, only while something plays *and* the
     home screen is STARTED; paused it stands still; no duration, e.g. radio, = played only).
     **The position is deliberately not Compose state** (`Progress`, plain fields): players report
     it several times a second, and as state each report recomposed the section: 6–8 frames a
     second on a still home screen, found by measuring. Measured afterwards, quiet 5 s windows,
     home in front: paused = 0 frames, 0–20 ms CPU; playing = 5 frames (one a second),
     240–340 ms CPU, probes included, before `ProgressText` was split off; not re-measured since.
     Each button is a **48dp square with the sign centred** (`MediaButton`): Compose widens a
     smaller clickable's touch target to 48dp invisibly, so with padded 14dp signs the glow lit
     up beside the finger ("the button and the clicks are not aligned"). The button row is
     offset 17dp so the last sign, not its square, ends where the lines end. **Tap** = whatever is playing, else the music app
     of his choice (`Settings.musicApp`; the first tap asks with `MusicAppPicker`, which lists only players:
     `AppRepository.musicPackages()` = a `MediaBrowserService`, `CATEGORY_APP_MUSIC` or
     `ApplicationInfo.CATEGORY_AUDIO`, read on IO when the picker opens; "All apps…" as the last
     row, through `AppPickerDialog(more = …)`);
     **long-press** = choose that app. With **notification access**
     (`service/MediaListener.kt`) it follows the active `MediaController` by callback, registered
     in a `LifecycleStartEffect` so nothing listens while home is hidden; without it the three
     words are sent as media keys (`AudioManager.dispatchMediaKeyEvent`) and one line offers the
     access. The listener calls `requestUnbind()` in `onListenerConnected`: the *grant* is what
     `MediaSessionManager.getActiveSessions` checks, while a bound listener, even an empty one, is
     handed every notification on the phone. `NowPlaying` is a data class fed from the callback's
     own arguments, so a player ticking its position every second redraws nothing.
     **The section is only there while there is music** (owner, 2026-09-20: "when nothing is
     playing the music control should hide automatically"; `Settings.musicAutoHide`, on by
     default, also for existing installs; off = always there, as the contributor built it).
     `rememberMusicState` (called by `HomeScreen`, because the line above the section, its share
     of `heightOf` and `anySection` all hang on it) holds a `MusicState`: `playing` = the active
     session's `STATE_PLAYING` with notification access, else the audio system's word
     (`AudioPlaybackCallback` + `activePlaybackConfigurations`, usage MEDIA or UNKNOWN only, so a
     key click or a notification sound is not music; this replaced the 400 ms re-read of
     `isMusicActive` after a key press). Listeners live in one `LifecycleStartEffect`: nothing
     listens while home is hidden. **It lingers for `MUSIC_LINGER_MS` = 1 min after a stop that
     was seen happening** (a callback): room to press play again, and no flicker between two
     songs (BUFFERING is not PLAYING). What is found on returning to the home screen is *not*
     such a moment (`live = false`): music that stopped while he was elsewhere shows nothing,
     but a minute already running survives the return, and one that ran out during sleep is
     cleared by the same read (the expiry `delay` counts uptime, the rule elapsedRealtime). The
     rule is the pure `lingerUntil(...)` in `ui/home/MusicLinger.kt`, 7 unit tests. Show and hide
     are instant on purpose: an enter animation would unfold the section on every return home
     with music on, and an exit animation can be held back while the launcher is frozen in the
     background and play on return.
   - **Note** (`NoteSection`): `Settings.note`, dim, up to `Fit.maxEvents` lines, **always
     shown; a tap edits it** (`TextInputDialog(multiline)`): "can't I see notes and write them
     quickly" won over "a tap opens my notes app", which hid the lines behind "Open <app> →". The
     notes app (`Settings.noteApp`, "" = none) is a word at the right of the title, "<app> →";
     a tap on it opens the app, or one page of it if `Settings.noteLink` holds a link
     (`ACTION_VIEW` aimed at the app's package, then at anything; cleared when the app changes).
     **Long-press** = notes app / page link. A notes app's *content* cannot be shown: it lives on
     the app's server and Focus has no INTERNET permission.
   Section titles (CALENDAR, MUSIC, NOTE) are full brightness like the fast apps, what is under
   them is dim: his call, after finding the dim titles "faded".
   Both choices are also rows in Settings → Home screen, and apps open through `onLaunch`, so the
   daily timers apply to them as to any other launch.
   Both are counted in `heightOf`.
3. **Notices**, only when needed: "Finish setting up Focus →" and "Your weekly review is ready →".
4. **Fast apps**: at most `MAX_FAVORITES = 5`, text only, aligned by `homeAlign`
   (left / center / right). Hold = lift and drag into a new place (`Settings.favorites`), let go
   without moving = the app menu. Settings → Fast apps keeps the ↑ / ↓ arrows and "Remove".
5. **Corner shortcuts**: `leftShortcut` / `rightShortcut`. Defaults `auto:phone` → `ACTION_DIAL`
   and `auto:camera` → `INTENT_ACTION_STILL_IMAGE_CAMERA`, so they work whatever dialer or camera
   is installed; either can be any app. Long-press a corner to change it in place;
   `showShortcuts` hides both.
Background gestures (each a setting): swipe down = notifications, swipe up = drawer with search
focused, double-tap = lock (on by default, needs the service), long-press = settings, swipe right
(finger moves right; nothing is to the left of home) = the phone's web search (`ui-system.md`).

## Drawer (page 1, swipe left)
- Search field on top. `autoKeyboard` opens the keyboard on arrival (off by default; when the
  drawer counts as "arrived at" is in `ui-system.md`; dragging the list hides it); `autoLaunch`
  opens the app when ≥ 2 letters leave exactly one result.
- **"Installed in the last 24 hours"**: `firstInstallTime` within 24 h, newest first, Focus itself
  excluded; hidden while searching; `showRecentInstalls` turns it off.
- Under the search field, hidden while searching: Personal / Work tabs (only when a work profile
  has launchable apps; the list and "installed in the last 24 hours" follow the tab, search
  covers both) and "Sort: A–Z / Most used / Recent" (`Settings.drawerSort`; the two usage orders
  come from `UsageRepository.sortStats()`, 7 days).
- Then every visible app in that order; the scrubber only for A–Z. `showUsageInDrawer` adds
  today's time next to apps that have a limit.
- Hidden apps are filtered out of the list and the search; they are managed in
  Settings → Drawer → Hidden apps. Search matches the name shown in Focus (so a renamed app is
  found by its new name, not its system name).
- Work-profile apps sit under the Work tab, carry the drawn briefcase (`WorkBadge`, also on pinned
  apps on the home screen) and are launched through `LauncherApps.startMainActivity` with their user.

## Welcome screen and tips (`WelcomePage`, `data/AppState.kt` `Tip`)
Asked for as "a splash screen with a proper tutorial, all features". An eight-page text tour was
built first and rejected outright: "too much text, too many next pages … it should come as a
tutorial when using". So:
- **Welcome**: one screen, once, scrollable so its buttons survive a small screen or large type
  (name, the owner's headline, two short lines, "Start" / "Set up
  Focus first"). Not a splash on every start: a launcher is opened dozens of times a day.
  `AppState.tutorialSeen`; opened by `MainActivity.onCreate` on the first start ever, or by
  `SettingsRoot` if "Focus Settings" is opened first; an install that already had saved settings
  counts as having seen it. Again any time: Settings → About → "Welcome screen and tips".
- **Tips**: `enum Tip(gesture, result)`, one at a time in the home screen's notices (`TipLine`):
  a faint "TIP 3 / 9", then the gesture in full brightness and "→ result" quieter, **two or three
  words each side** ("Swipe right → web search"). The first wording was a dim sentence per tip
  and was sent back as too much text and hard to see; keep new tips this short. It wraps rather
  than being cut if a large text size makes it longer. The tip is **framed** (1dp outline in the
  text colour, 12dp corners; the counter inverted like a selected tab) so it cannot be taken for
  part of the home screen, and **what it is about is outlined the same way while it shows**
  (`Modifier.tipTarget`: the clock, screen time, both bottom corners, the music and note
  sections). A still outline, not a pulse: nothing on the home screen moves by itself. Tips about
  a gesture on empty space have nothing to outline. **Double tap is the last tip**: it turns the
  screen off, which cut short whatever came after it.
  **A tip goes away when the thing it teaches has been done once** (`AppState.did(Tip.X)`, called
  from the gesture itself: drawer reached, app menu opened, swipe up / right / down, clock
  long-press, double tap, settings long-press) or when it is tapped (`nextTip()`; the "add a
  calendar, music or a note" tip opens Settings → Home screen on that tap). The app-menu tip also
  shows under the search bar in the drawer, where it applies (full brightness, same wording). Thirteen tips; a tip about something that is not on the screen (music/note long-press with
  both sections off, corners with shortcuts hidden) is passed over by itself. Deliberately *not*
  tips, because they are visible controls or settings, not hidden gestures: drawer sort and tabs,
  the A–Z scrubber, auto-open of a single match, the weekly review (it announces itself). The
  index is persisted
  (`tip_index`); "Start" on the welcome screen resets it to 0. The tip counts as two notice lines
  in `heightOf`. **A new gesture or hidden feature gets its line in `Tip`.**

## The long-press menu (`AppMenu`): the order is the owner's, do not reorder
Title = the app's name; the subtitle shows the system name if renamed, today's time and the limit.
1. **Uninstall**: `ACTION_DELETE package:…` (+ `EXTRA_USER` for work-profile apps); not offered
   for system apps. The system asks for confirmation; Focus never removes anything itself.
2. **App info**: `LauncherApps.startAppDetailsActivity`.
3. **Move to fast apps** / Remove from fast apps, with "n / 5". When full: "Fast apps are full",
   choose the one to replace.
4. **App timer**: only if `canLimit`. Opens `TimerDialog`: the category default, No limit,
   presets, Custom…; the subtitle says how Focus classified the app (`describeCategory`).
5. **Rename**: inside Focus only; empty restores the system label.
6. **Hide app** / Unhide app.

## Setup page (Settings → Setup; also the "finish setup" notice)
"Three switches make Focus work", each row opens the right system screen and shows its state on
return:
1. **Default home**: `RoleManager.ROLE_HOME` request on Android 10+, else the home settings.
2. **Usage access**: screen time, timers and the review depend on it.
3. **App locking**: the accessibility service. Sideloaded apps hit Android's "restricted setting"
   block, so the row explains the way through (App info → ⋮ → Allow restricted settings).
Optional: **Notifications** (used only for the weekly review) and **Calendar section** (requests
`READ_CALENDAR` and turns the section on in one step).
Focus never flips these switches itself, and neither does an agent over adb
(`2-overview/device-testing.md`).

## Settings, in short
One immutable `Settings` data class stored as JSON; `fromJson` tolerates missing keys. Pages:
main · setup · home · fastapps · drawer · hidden · timers · timerapps · weekly · appearance ·
gestures · about. Open one directly: `am start -n com.focus.launcher/.SettingsActivity --es route
<name>`. Appearance: dark or light, font (sans / serif / mono), text scale, hidden status bar,
launch animation (fast / system).
