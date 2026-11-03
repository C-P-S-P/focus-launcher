# Tier 1 · Basics (read all of it, every time)

**Focus** is a text-only, strictly black-and-white Android launcher (Kotlin + Jetpack Compose, no
Material, package `com.focus.launcher`, 36 Kotlin files, ~7,750 lines). Home = split clock (the
time | the next events or screen time, one line between them), up to 5 fast apps, 2 corner shortcuts.
Swipe left = searchable app list (sortable; Personal / Work tabs), swipe right = the phone's web
search, double tap = lock. Social apps and games get daily timers that lock the app; a weekly
review shows where the time went. No INTERNET permission. Optional: an accessibility service
(locks an app mid-session, locks the screen) and a notification listener (the song's name).

**Owner:** Chaitany (GitHub `patelchaitany`). His phone runs Android 16 with Focus as its default
launcher. He wants things done end to end and verified, and honest reports of what was not.

**This brain is public** (it is committed; the repo is public). Anything about his server, his
device or what is on his phone lives only in the git-ignored `private/` folder next to this file.

## Rules that prevent damage
1. **The phone is in use while you test.** Never inject blind taps or swipes. Open screens by
   intent; screenshot only when `topResumedActivity` is `com.focus.launcher/`.
2. **Never grant special access over adb** (usage access, accessibility, notification access,
   default home). The in-app Setup page sends the owner to each switch.
3. **The repo is public.** Before every push, run the audit in `2-overview/github-and-release.md`.
   Never commit `keystore.properties`, `local.properties`, `site/deploy.env`, `site/public/`,
   `.claude/launch.json`, or anything under a `private/` folder.
4. **Never publish anything observed on the phone**: screenshots, app lists, usage, calendar.
   Site mockups and examples in this brain use invented content.
5. **Never print or log the keystore password.** It lives in git-ignored `keystore.properties`.
6. **Server changes are surgical:** back up, `sudo nginx -t`, reload, roll back on failure. The
   server is shared with the owner's other projects.
7. **Never auto-limit communication tools** (mail, browsers, messengers). Never guess towards a limit.
8. **Everything stays monochrome and icon-free,** including emoji in third-party text. (One drawn
   exception, asked for by a contributor: the work-profile briefcase, `WorkBadge`.)
9. **Do not work around a managed work profile's restrictions** (calendar, usage). Explain the limit.
10. **Never promise search rankings.** Say what was done and what it depends on.
11. **The accessibility service and the notification listener stay** (owner, 2026-09-20). PR #11
    removed them to get past Play Protect; that took options away and he had it reverted. Never
    remove an option he uses, or add an SMS permission, without his yes first.

## Where things are
| What | Where |
| --- | --- |
| App source | `app/src/main/java/com/focus/launcher/` (`data/`, `service/`, `ui/`) |
| Unit tests | `app/src/test/…/` `data/` (tracker 13, emoji 6, settings 7, DayUsage 5, bucketing 5, limit logic 8) · `ui/home/` (music linger 7) — 51 total |
| Website source / output | `site/src/` → `site/public/` (generated, ignored) |
| nginx rules for the site | `site/nginx-focusapp.conf` (installed on the server as a snippet) |
| Signing key / its password | outside the repo / both named in `keystore.properties` (ignored) |
| Deploy target | `site/deploy.env` (ignored) |
| Server, device and phone specifics | `private/` (ignored) |
| CI · publish · release check | `.github/workflows/` `build.yml` · `publish.yml` · `verify-release.yml` |
| Live site · repo · releases | https://how2me.me/focusapp/ · https://github.com/patelchaitany/focus-launcher · `/releases` |

## Commands
```bash
# On the owner's Mac add -Dorg.gradle.java.home=<JDK 21 home> to every ./gradlew (default java is too new)
./gradlew :app:testDebugUnitTest :app:lintDebug      # must stay: all tests pass, lint 0 errors
./gradlew :app:assembleRelease                        # optimized, DEBUG-key signed: CI and emulators only
adb install --user 0 -r build/clean/focus-launcher-<version>.apk   # owner's phone: release key, --user 0
adb shell cmd package compile -m speed-profile -f com.focus.launcher
FOCUS_APK=$(site/clean-build.sh | tail -1) site/deploy.sh   # public APK from a CLEAN checkout + site
```
Three build types: `debug`, `release` (debug key), `dist` (release key: public *and* the owner's
phone). One cannot be installed over the other. No JDK path is pinned in the repo any more.

## State of the world (2026-09-20)
**Public:** the newest `vX.Y.Z` release on the site and the release page (older APKs still
served): split clock (owner's second sketch), plus a collaborator's merged PR #1 (drawer sort and
tabs, web search, work badge) and PR #10 (music and note sections); what in them touches his
earlier decisions waits for his word (open decisions 10–14). GPL-3.0-or-later. His phone runs the
release-key build since 2026-09-20: what goes onto it comes from `site/clean-build.sh` or the site.
Versions are `<base>.<commit count>`. **CI publishing is live**: a push to `main` that touches the
app or the site starts Publish, which waits for the owner's approval (first real run: 1.1.34).
**F-Droid:** repo ready, recipe verified; the merge request needs the owner's GitLab account and
token (`3-details/fdroid.md`). Agents never run the setup script, create accounts or enter tokens.
**Play Protect** blocks a downloaded APK that declares those two services (select markets; adb is
exempt). 1.1.37 had neither; he had that reverted the same day (`3-details/timers-wall-consent.md`).
Mid-session locking through the accessibility service is still unseen on his phone.
The brain is committed and public (`3-details/brain-upkeep.md`). Publishing happens on request.

## Tier 2 index: read the area(s) you will touch
- `2-overview/user-and-decisions.md` — what the owner asked for and decided; open decisions
- `2-overview/app.md` — architecture, features and where each lives
- `2-overview/device-testing.md` — building, installing, verifying on the phone safely
- `2-overview/website-and-server.md` — the site, the server, deployment, search
- `2-overview/github-and-release.md` — the public repo, CI, releases, signing, what stays private
