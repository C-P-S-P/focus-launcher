# Tier 2 · The app

Kotlin 2.3.21, Compose BOM 2026.06.01 (foundation + ui + animation only, **no Material**), AGP
8.13.2, Gradle 8.14.3, compileSdk/targetSdk 36, minSdk 26. One process; activities and the
accessibility service share state through `Graph` (a hand-rolled service locator).

## Shape
```
FocusApp, Graph              Application + singletons (settings, apps, usage, limits, state)
MainActivity                 HorizontalPager: page 0 HomeScreen, page 1 DrawerScreen; swipe-right watcher
SettingsActivity             "Focus Settings", also the LAUNCHER entry; routes via --es route <name>
ReviewActivity               Today / Week review
BlockActivity                the "Time's up" wall AND the "open anyway?" consent screen
data/   Settings(+Store)     one immutable data class, JSON in SharedPreferences, StateFlow
        AppRepository        LauncherApps scan, label cache, categories, launch/uninstall
        UsageRepository      usage events → per-app per-hour time; DayAccumulator; day JSON cache;
                             sortStats() = system 7-day aggregates, only for ordering the drawer
        ForegroundTracker    pure state machine (unit tested)
        LimitManager         which apps are limited; continue/bypass passes; consent; weekly tally
        WeekSummary          aggregation for the review
        CalendarRepository   calendars, one-calendar agenda (cached), emoji stripping
        AppState             pending weekly review, reflection notes
service/ FocusAccessibilityService   mid-session enforcement, global actions (notifications, lock)
         WeeklyReview (+Receiver)    inexact weekly alarm, notification
ui/     theme/ components/ home/ drawer/ block/ review/ settings/   + Launching.kt (the launch gate)
```

## Features and where they live
| Feature | Where |
| --- | --- |
| Clock: split (default), ring (battery or day) or plain; tap/long-press action | `ui/home/HomeWidgets.kt` `HomeClock`, `ClockTapDialog.kt` |
| Screen time on home: title + total + "N% of today" (of 24 h) below the clock, outside the ring, no setting; tap → review (the 24-hour bar was removed from home; `DayBar` lives on in the review) | `HomeWidgets.kt` `ScreenTimeLine`; height counted in `HomeScreen` `heightOf` |
| Home layout that always fits | `ui/home/HomeScreen.kt` (`Fit` options, measured constants) |
| Drawer: search ranking, recent installs, A–Z scrubber | `ui/drawer/DrawerScreen.kt` |
| Drawer sort (A–Z / Most used / Recent), "Sort: …" under the search bar | `DrawerScreen.kt`, `Settings.drawerSort` (`DrawerSort`), `UsageRepository.sortStats()` |
| Drawer Personal / Work tabs (only with a work profile) | `DrawerScreen.kt`, `TabChip` in `ui/components/Basics.kt` |
| Work marker: drawn briefcase outline (`WorkBadge`) on drawer rows and pinned work apps | `ui/components/Basics.kt`, `DrawerScreen.kt`, `ui/home/HomeScreen.kt` |
| Keyboard opens with the drawer (`autoKeyboard`, toggled from Settings → App drawer *and* → Gestures); when the drawer counts as open | `MainActivity.kt` `drawerActive`, `DrawerScreen.kt` |
| Swipe right on home = the phone's web search | `MainActivity.kt` `Launcher` (pointerInput on the pager), `ui/Launching.kt` `openWebSearch`, `Settings.swipeRightSearch` |
| Double tap = lock (default on; needs the accessibility service) | `HomeScreen.kt`, `Settings.doubleTapLock`, `FocusAccessibilityService` |
| Long-press menu, timer dialog | `ui/drawer/AppMenu.kt` |
| Music and note sections on home (text, lines between sections, off by default). **Music is only there while something plays** (+1 min after it stops; `Settings.musicAutoHide`, owner 2026-09-20) | `ui/home/HomeMusic.kt` `MusicState`, `rememberMusicState`, `MusicSection`, `NoteSection`; `ui/home/MusicLinger.kt` (pure rule, tested); `service/MediaListener.kt`; toggles in `LauncherPages.kt` `HomePage` |
| Calendar widget on home (agenda, optional week strip) | `ui/home/HomeCalendar.kt` `CalendarWidget`, `WeekStrip` |
| Welcome screen (once) and learn-by-doing tips on the home screen | `ui/settings/WelcomePage.kt` (route `welcome`), `data/AppState.kt` `Tip` / `did()` / `tutorialSeen`; hooks in `HomeScreen.kt`, `MainActivity.kt`; drawer hint in `DrawerScreen.kt` |
| Fast apps (≤5), corner shortcuts, home gestures, notices | `ui/home/HomeScreen.kt` |
| Weekly review: alarm, home notice, notification, the week's summary | `service/WeeklyReview.kt`, `data/WeekSummary.kt`, `ui/review/ReviewScreen.kt` |
| Setup page (default home, usage access, app locking, notifications, calendar) | `ui/settings/SetupPage.kt` |
| Launch gate (wall / consent before an app opens) | `ui/Launching.kt` `launchApp`, `start`, `launchOptions` |
| Wall + consent UI | `ui/block/BlockScreen.kt`, `BlockActivity.kt` |
| Settings pages (12 routes) | `ui/settings/*` ; routes: main setup home fastapps drawer hidden timers timerapps weekly appearance gestures about |
| Theme, light-up press feedback, edge-to-edge, refresh rate | `ui/theme/Theme.kt` |

## Things that are easy to get wrong
- **Timers work in two layers.** The launcher's gate needs only usage access. Mid-session locking
  needs the accessibility service. Keep both working independently. The service and the
  notification listener stay by the owner's decision, although Play Protect blocks such a download
  in some markets; the variant without them (PR #11) was reverted (`3-details/timers-wall-consent.md`).
- **Settings is one JSON blob**; `fromJson` must tolerate missing keys (older installs).
- **Changing a default does not reach existing installs**: the saved JSON already holds the key.
  When an old stored value has to be reinterpreted, bump `Settings.SCHEMA` (stored as `"v"`) and
  migrate in `fromJson`: schema 2 turned a stored `RING` from before into `SPLIT` once (it was the
  old default, not a choice; a stored `PLAIN` was a choice and is kept). Tested in
  `SettingsMigrationTest`.
  `doubleTapLock` became `true` on 2026-09-19; an older install keeps `false` until it is switched
  on in Settings → Gestures. Keys absent from old JSON (`swipeRightSearch`, `drawerSort`) do get
  the new default.
- **Drawer order uses the system's usage aggregates, not the `ForegroundTracker`**: an ordering
  does not need exact minutes. Never show those numbers as screen time.
- **No hosted AppWidgets** (the search "widget" is an intent): a widget would bring colour and
  icons onto the home screen.
- **`DayUsage.CACHE_VERSION`** must be bumped whenever the usage computation changes.
- **Per-resume work is multiplied ~100×/day.** Anything added to the resume path or a timer must
  be incremental or event-driven.
- **`TRIM_MEMORY_UI_HIDDEN` fires on every app launch**; never clear caches there.
- Compose lint is strict here: no `Locale.getDefault()` or `StateFlow.value` in composables
  (use `currentLocale()` and `collectAsStateWithLifecycle`).

## Quality bar
51 unit tests pass (tracker 13, emoji 6, settings 7, music linger 7, DayUsage 5, usage bucketing 5,
limit logic 8; `org.json` is a test-only dependency because the JVM has none); `lintDebug` = 0
errors (remaining warnings are "newer version available", deliberate: newer AndroidX needs
compileSdk 37 + AGP 9.1). About 7,800 lines.
The data layer is tested through pure functions split out of the Android-touching classes (no
Robolectric on the path): `addForegroundMillis` (the per-hour bucketing, out of
`UsageRepository.DayAccumulator.add`) and `resolveLimit` (the limit decision, out of
`LimitManager.limitFor`; takes `category` as a lambda so classification stays lazy). Add new data
tests the same way — extract the pure core, keep the Android call a thin wrapper.
Release APK = 1,366,083 bytes (≈ 1.37 MB). Version **1.1** (`versionCode` 2) since 2026-09-19;
1.0 was 1,366,063 bytes. The same checks run on GitHub for every push and pull request.

## Tier 3 pointers
`usage-tracking.md` · `app-classification.md` · `timers-wall-consent.md` · `weekly-review.md` ·
`home-drawer-menu-setup.md` · `ui-system.md` · `calendar.md` · `performance.md` ·
`toolchain-and-build.md` · `mistakes-and-lessons.md`
