# Pivot Launcher — Technician Setup

A minimal home-screen replacement for Android POS terminals. It shows only the
apps you select (Pivot POS by default) as centered icons on a pure black screen. This is **not** kiosk lockdown —
staff can still reach Recents and the notification shade; that is by design.

- Default POS package: `com.blurredlimes.pivotpos` (changeable in settings)
- Minimum Android version: **7.0 (API 24)**

## 1. Get the APK

**From CI (no Android Studio needed):** every push builds a debug APK. GitHub →
*Actions* tab → latest *Build APK* run → download the `pivot-launcher-debug`
artifact and unzip it to get `app-debug.apk`.

**Locally:** with JDK 17 installed, `./gradlew assembleDebug` produces
`app/build/outputs/apk/debug/app-debug.apk` (the Android SDK is downloaded
automatically if `ANDROID_HOME` points at an SDK; otherwise install
Android Studio or the command-line tools first).

**Signed release:** add the keystore secrets described at the top of
`.github/workflows/build.yml`, then push a tag like `v1.0`. The
`pivot-launcher-release` artifact contains the signed `app-release.apk`.
Use a release build for fleet deployments — debug builds can't be updated
in place by a release-signed successor.

## 2. Install on the terminal

**With adb (preferred):**

```
adb install app-debug.apk
```

If the terminal is network-connected and has ADB-over-TCP enabled (common on
Sunmi/Elo service menus): `adb connect <terminal-ip>:5555` first.

**Without adb:** copy the APK to a USB stick, insert it into the terminal, open
the built-in file manager, and tap the APK. Android will prompt to allow
installs from this source (Settings → Apps → Special app access → *Install
unknown apps* on Android 8+; a single global *Unknown sources* toggle under
Settings → Security on Android 7).

## 3. Set as the default home app

**Just open the app.** On first launch, if Pivot Launcher is not yet the
default home app, it prompts automatically: on Android 10+ you get the system
"set as default" dialog directly; on Android 7.0–9 it opens the Home-app
settings page. The prompt screen stays (with retry buttons) until the default
is set, and disappears permanently once it is. Android does not allow an app
to seize the home role silently, so one confirmation tap is required.

Manual route, if you prefer:
Settings → **Apps → Default apps → Home app** → choose **Pivot Launcher**.

On some Android versions and OEM skins that menu is missing or buried. In that
case just press the **Home** button: Android shows a chooser listing all
installed home apps. Pick **Pivot Launcher** and — important — tap
**"Always"**, not "Just once". If you tapped "Just once", press Home again and
choose Always.

Verify: press Home. You should see a black screen with a single centered icon.
Tap it — the POS app opens. Press Home again — you're back on the black screen.

## 4. Configure

**Long-press anywhere on the empty black background and hold for ~2 seconds.**
There is no visible settings button. The configuration screen lets you:

- Check the apps to show on the home screen from a list of installed apps
  (pre-set to `com.blurredlimes.pivotpos`). Icons appear in the order you
  check them.
- Adjust the icon size (96–320 dp, default 125)

Tap **Done** (or the Back button) to return. Settings persist across reboots.

If the configured app isn't installed, the home screen shows a diagnostic
message with the configured package name instead of an empty void. It re-checks
every time the screen resumes, so installing the POS app fixes it immediately —
no reboot needed.

## 5. Revert to the stock launcher

Settings → Apps → Default apps → Home app → select the original launcher.
Or simply uninstall Pivot Launcher (`adb uninstall
com.blurredlimes.pivotlauncher` or Settings → Apps); Android falls back to the
stock launcher automatically.

## 6. OEM skins and vendor launchers

POS AIO vendors often ship their own launcher or MDM layer that competes for
the HOME intent. What to expect:

- **Sunmi**: devices ship with the Sunmi desktop/launcher and sometimes a
  "kiosk"/whitelist mode managed by Sunmi's own settings or cloud MDM. If the
  Home chooser never appears, look for *Settings → System → Default launcher*
  in Sunmi's settings, or disable Sunmi's kiosk mode first. On managed units,
  the home app may be pinned by the Sunmi MDM policy and must be changed there.
- **Elo**: EloView-managed devices lock the home experience entirely; the
  device must be taken out of EloView (or this launcher deployed *through*
  EloView) before the HOME chooser will appear.
- **Generic/Chinese AIOs**: some ROMs hide *Default apps* but still show the
  chooser when Home is pressed after installing a second launcher. Others
  auto-restore their own launcher on reboot; look for a "default launcher" or
  "boot app" option in the vendor's settings/service menu (service menus are
  often reached via a dial code or a multi-tap on the build number).

Diagnosis from adb:

```
adb shell cmd package resolve-activity -c android.intent.category.HOME android.intent.action.MAIN
```

shows which launcher currently wins the HOME intent. To list all candidates:

```
adb shell cmd package query-activities -c android.intent.category.HOME android.intent.action.MAIN
```

Setting the role directly (works on many Android 10+ builds even when the UI
hides the option):

```
adb shell cmd package set-home-activity com.blurredlimes.pivotlauncher/.MainActivity
```

## Notes

- **No network access.** The app declares no INTERNET permission, makes no
  network calls, and contains no analytics or telemetry.
- **minSdk 24 (Android 7.0).** Chosen because deployed POS AIO hardware
  (Sunmi T2/V2 era, older Elo I-Series, generic units) commonly ships Android
  7.1–11. Cost: no adaptive app icon for the launcher itself (a plain vector
  icon is used everywhere instead), and the app uses the pre-33
  `PackageManager` query APIs (suppressed deprecation) rather than the newer
  typed variants. Nothing functional is lost.
