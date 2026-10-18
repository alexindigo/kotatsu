<div align="center">
	<img src="docs/img/katapult_w.svg" alt="Kotatsu wordmark" height="48">
	&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
	<a href="https://buymeacoffee.com/gezimos"><img src="docs/img/bmc-button.png" alt="Buy me a coffee" height="48"></a>
	<h2>Kotatsu — a comfortable launcher.</h2>
</div>

Kotatsu is a separately installable fork of [Katapult by gezimos](https://github.com/gezimos/Katapult). Its source is [alexindigo/kotatsu](https://github.com/alexindigo/kotatsu). Its Kotatsu emblem and Lato wordmark identify the fork; wider release and device acceptance remain separate verification gates.

> [!NOTE]
> Build Kotatsu from this source tree. [Upstream Katapult releases](https://github.com/gezimos/Katapult/releases) are Katapult distributions, not Kotatsu updates. Kotatsu has no built-in updater or Internet permission. External browser/document-provider/Mudita weather handoffs and Android-managed backup/device transfer remain; their providers can network independently. Release and wider feature acceptance remain separate verification gates.

The following images are **upstream Katapult reference screenshots**, not screenshots of final Kotatsu artwork.

<div align="center">
    <table>
        <tr>
            <td><img src='docs/img/screenshots/home_music_weather.png' height='300' alt=""></td>
            <td><img src='docs/img/screenshots/home_clean.png' height='300' alt=""></td>
            <td><img src='docs/img/screenshots/all_apps_80.png' height='300' alt=""></td>
            <td><img src='docs/img/screenshots/lockscreen_widgets.png' height='300' alt=""></td>
        </tr>
        <tr>
            <td><img src='docs/img/screenshots/with_wallpaper.png' height='300' alt=""></td>
            <td><img src='docs/img/screenshots/dark_home.png' height='300' alt=""></td>
            <td><img src='docs/img/screenshots/settings.png' height='300' alt=""></td>
            <td><img src='docs/img/screenshots/order_apps.png' height='300' alt=""></td>
        </tr>
    </table>
</div>

## Setup

Finish onboarding without granting sensitive access or changing your default HOME app. Notification access, supported-device SMS/call-read access and default-HOME selection are optional setup actions. Back exits a nondefault Kotatsu preview after internal navigation/dialogs are resolved.

- **Settings**: Long-press on any empty area of the home screen
- **Replace home apps**: Long-press on the Phone, SMS, or extra row shortcuts
- **Clock/Date shortcut**: Long-press on the clock or date to assign an app

## Features

- **3x4 app grid** tap on chevrons or use horizontal swipes
- **Music widget** with playback controls and song info, works with other third-party apps like Auxio, Fossify Music, Spotify, Foobar, CalmCast, Antenna Pod etc.
- **Icon tinting and shaping** for a uniform black & white look (works with ~95% of apps)
- **Icon shape**: Circle or Rounded (Mudita's rounded square) for app icons; buttons and page arrows stay round, as on Mudita's launcher
- **Offline icon collections**: 2,174 curated Simple Icons, Tabler and Material choices. Long-press an app → Change Icon to search or page through the collections, import PNG/SVG artwork, or reset. Verified brand matches are automatic, and a few stock apps with photographic icons (Settings, Clock, Files, FM Radio and others) get a matching line icon; other apps keep their original icons. Existing manual choices take priority. [Catalogue provenance and maintenance](docs/ICON_CATALOGUE.md).
- **Extra dock row**: 3 additional shortcuts above the Phone/SMS bar
- **Notification indicators** on app icons (requires notification listener permission)
- **Show battery %** with level-based icon above the clock.
- **Show AM/PM** for 12-hour clocks
- **Hide status bar** for a cleaner look
- **E-Ink refresh on home**: flashes the screen when pressing the home button to clear ghosting
- **Double-tap brightness**: toggle display brightness by double-tapping the home background (requires system settings permission)
- **Infinite scroll**: wrap around from last page to first
- **Simple wallpaper**: set a background image (not managed by Android)
- **Hide apps**: from the app context menu or bulk-hide in Settings > All Apps
- **Rename apps**: long-press any app in the grid
- **App context menu**: Reorder, Rename, Hide, App Info, Uninstall
- **Reorder apps**: swap-based reordering from the context menu. Dirty exits ask Save/Discard/Cancel; Reset changes the draft until you save.
- **Lockscreen Notifications (experimental)**: per-app notification counts on the lock screen, with an app allowlist and sort order. Long-press the widget to move/resize it, tap to save.
- **Screensaver (experimental)**: a Kotatsu screen saver showing the home clock, date, alarm, battery, and up to 4 notification rows. Start it with a two-finger long-press on the home screen (or the home long-press menu); the power button exits. Select Kotatsu once in system Screen saver settings (Settings → Experimental → Screensaver opens it).

## Permissions

| Permission | Why |
|------------|-----|
| `QUERY_ALL_PACKAGES` | List all installed apps |
| `WRITE_SETTINGS` | Double-tap brightness toggle |
| `REQUEST_DELETE_PACKAGES` | Uninstall apps from the context menu |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read notifications for badge counts |
| `READ_CALL_LOG` | Missed call badge on Mudita Phone (Mudita Kompakt only) |
| `READ_SMS` | Unread SMS badge on Mudita Messages (Mudita Kompakt only) |
| `BIND_ACCESSIBILITY_SERVICE` | Optional inherited lockscreen/widget features; enable the Kotatsu Action Service deliberately in Android settings. |

## FAQ

| Question | Answer |
|----------|--------|
| Will it have customizable fonts? | No. |
| Will it have a customizable grid? | No. |
| Will it have third-party icon packs? | No. |
| Will it get as many features as inkOS? | No. |
| Can I add more apps to the home screen? | No. |
| Can I adjust the opacity of the wallpaper? | No. |
| Can I change date/clock formats? | Yes, or leave them on System to follow Android settings. |
| Can I add AM/PM to a 12-hour clock? | Yes. It's in the settings. |
| Can I reorder apps? | Yes. Long-press an app, tap Reorder, then tap another app to swap. Tap Save when done. |
| Will this launcher have a notification tray or letters screen? | No. |

## Troubleshooting

### Notification indicator not showing
Go to Settings > Notification Log and check if the notification exists in Android. Many apps require Google Play Services for push notifications. If it's not in the log, it doesn't exist for Android and Kotatsu can't read it.

### Notification not clearing
Check the Notification Log. If Android doesn't clear it, Kotatsu can't either.

### How to disable notification dots for a specific app
There are no allowlists. Go to Settings > App Notifications, tap the app, and disable "Allow notification dot".

## Upstream attribution and support

<table><tr>
<td><a href="https://buymeacoffee.com/gezimos"><img src="docs/img/bmc-button.png" alt="Buy me a coffee" height="40" style="max-width:200px;"></a></td>
<td valign="middle">This donation link supports gezimos, the upstream Katapult author. It is not a Kotatsu donation destination.</td>
</tr></table>

## Acknowledgments

- The experimental lockscreen widget's approach — drawing over the keyguard through an accessibility overlay — was inspired by [Lockscreen Widgets](https://github.com/zacharee/LockscreenWidgets) by Zachary Wander. Katapult's implementation is its own; no code was copied.
