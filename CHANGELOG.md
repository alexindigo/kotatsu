# Changelog

Kotatsu changelog. The sections after 0.1 are inherited Katapult history through v1.6.

## [0.5.2] — 2026-10-31

### Added
- **Kotatsu Weather update time**: when the forecast is over an hour old, “Last updated at …” appears above the rows, using the phone's 12- or 24-hour clock. The Weather editor explains why an update failed and when it will try again.
- **Kotatsu Weather screen-reader description** includes each place's selected hours, weather and temperature range, plus the last-update time when shown.

### Changed
- **Kotatsu Weather rows** fit every place on a single line, with the font size based on the widget size and number of places.
- **Kotatsu Weather row icons** show the worst weather over the whole selected interval, including its end hour.
- **Kotatsu Weather after waking** shows a picture older than four hours quietly in grey, without a needle, until the fresh picture arrives.

### Fixed
- **Top weather line** shows Mudita's “few clouds” as partly cloudy instead of clear on Home, the screensaver and the lock screen.

## [0.5.1] — 2026-10-28

### Added
- **Built-in Kotatsu wallpaper**: choose Kotatsu or your own photo in Settings › Appearance › Wallpaper or from the Home long-press menu. None is the default.

### Changed
- **Notification badges** show the number of unread messages reported by apps such as Delta Chat, instead of counting one per chat.
- **Kotatsu Weather** has its version aligned with the launcher; no other changes.

### Fixed
- **Home-slot picker** leaves out hidden apps. Apps already assigned to Home stay in place.
- **Action Service recovery message** says access is off and explains how to turn it back on, without blaming an app update.

## [0.5] — 2026-10-27

### Changed
- **Home content** stays readable over any wallpaper: the status line, clock, date, app labels and All Apps dots take black or white ink from the picture directly under each part. Icon tiles, badges and the music widget are unchanged. Without a wallpaper, nothing changes.
- **Screensaver clock** uses the same automatic black or white ink over its wallpaper.
- **Text backgrounds** and **Screensaver text backgrounds** settings and their background plates are removed, replaced by automatic ink.
- **Kotatsu Weather** has its version aligned with the launcher; no other changes.

## [0.4] — 2026-10-26

### Fixed
- **Kotatsu Weather** downloads three days of weather, fixing morning "No forecast" after an evening update.
- **Kotatsu Weather** keeps forecasts at the right hours across daylight-saving and time-zone changes.
- **Kotatsu Weather** keeps a good saved forecast when a download is broken or a partial server reply has fewer useful hours.
- **Kotatsu Weather** keeps the temperature when wind or condition values are missing.
- **Kotatsu Weather updates** retry after a failure, starting at one minute and backing off to at most 30 minutes, without waking the phone.
- **Kotatsu Weather widgets** redraw when the needle moves, at midnight, when rows turn grey after 12 hours, after a clock or time-zone change, and when the lock screen appears.
- **Lock-screen overlays** wait until the bare lock screen is confirmed before appearing, so they do not cover the PIN pad.
- **Lock-screen weather** no longer lets a slow or failing Mudita Weather app freeze or crash the lock-screen service.

## [0.3] — 2026-10-25

### Changed
- **Kotatsu Weather settings**: each change is saved immediately; Done closes the screen.

### Fixed
- **Kotatsu Weather places** could be lost when an older or restored settings screen saved its stale list over newer changes. Changes are now saved as you make them.
- **Lock-screen widgets** that no longer exist in Android, for example after reinstalling the widget's app, no longer stay listed or block adding new ones.

## [0.2] — 2026-10-25

### Added
- **App Store**: a Settings entry opens F-Droid, or explains how to get it when it is not installed.

### Changed
- **Settings**: rebuilt in the Paper index style, with a table of contents and live summaries, grouped chapters, choice pages and "?" help. Settings pages replace slide-in sheets, Back returns one level, and switches use On/Off words.
- **E-ink controls**: moved into Appearance alongside the other screen settings.
- **App icons**: bundled and catalogue glyphs and newly imported artwork are capped at 54% of the icon tile. Mudita artwork and existing saved imports stay unchanged. The Kotatsu Weather glyph is smaller, with its frame unchanged.
- **Action Service reminder**: one dismissible notice replaces repeated warnings in Settings.
- **README and website**: refreshed with Kotatsu's own screenshots, features and installation guide.

### Fixed
- **Settings wording**: consistent "Lock screen" labels, a single % in "Show battery %", clearer descriptions for Android's Notification log and Notification history, and a positive "Music widget" switch.

## [0.1] — 2026-10-18

First Kotatsu release, based on Katapult 1.6.

### Added
- **Kotatsu identity**: its own emblem, wordmark and app package, so it can be installed alongside Katapult.
- **Sensitive access**: separate choices for collecting badges, media information and usage statistics, and for notification dismissal, call acknowledgment, media controls and accessibility features. These choices are separate from Android's access grants.
- **Offline icon collections**: 2,174 curated Simple Icons, Tabler and Material choices, with search and paging. Reviewed app matches and hand-picked stock line icons apply automatically; manual choices take priority.
- **Lock-screen widgets** (Kompakt only): host installed Android app widgets in a configurable box over the padlock and unlock hint, with size and order controls. Needs the Kotatsu Action Service.
- **Kotatsu Weather**: a separate app with an Open-Meteo weather widget for up to four places, each with its own label and hours. Add places by search or a one-off current-location request, choose Fahrenheit or Celsius, and open settings from the app icon. Weather uses the network; the launcher has no Internet permission.

### Changed
- **Launcher network access**: removed the Internet permission and built-in updater. External apps and providers opened from Kotatsu can still use the network independently.
- **Optional setup**: finish onboarding without granting sensitive access or making Kotatsu the default launcher. Back exits the preview once internal navigation and dialogs are resolved.
- **App appearance and navigation**: rounded-square shaping applies only to app icons; buttons and page arrows stay round. New installs start with rounded icons and visible page arrows. Arrows stop at the first and last page, even when swipe wrapping is on; existing appearance settings are kept.
- **Hidden Apps and System Apps**: manage both under Settings > All Apps. Kompakt stock apps omitted by Mudita's launcher, app stores, FUTO Keyboard and Mudita's Update start hidden and can be shown individually.
- **Hidden-app notifications**: hidden apps stay off the lock screen unless their separate Notify box is ticked. Lock-screen notification exclusions still take precedence.
- **Action Service setup**: one guide explains how to enable Kotatsu Action Service and leave its floating shortcut off. If access is lost, recovery names the affected features and offers to turn them off.
- **App reordering**: leaving an unsaved order asks Save, Discard or Cancel; Reset changes the draft until it is saved.

### Fixed
- **Lock-screen overlays** step aside for the PIN screen, alarms, calls, the power menu and other screens covering the lock screen. Hosted widgets keep their full width after updates.
- **Drafts and file pickers** keep rename text, lock-screen message text and pending import choices across Home navigation. Late keyboard updates no longer change another editor's draft.
- **Configuration import and export** report failed exports and reject invalid imports before replacing settings. Imports show a confirmation before changing sensitive-access choices.
- **Custom icons and wallpapers** keep the previous saved choice when a replacement fails; icon changes refresh consistently across the launcher.
- **Home music information** refreshes after playback changes, and stored feature switches remain visible when their required access is unavailable.

## [1.6]

### Added
- **Lockscreen Message**: your own line of text at the bottom of the lock screen, replacing "Touch the power button to unlock". Needs the Action Service.
- **Screensaver shortcut** for other launchers, plus an optional Screensaver icon in the app drawer.

### Changed
- **Dark Mode** is now **Theme Mode**: System, Light or Dark.
- Faster Home and All Apps: the app list and icons stay in memory.
- **Most Used** sorting looks at the last 30 days.
- Performance improvements.

### Fixed
- Weather keeps the last reading when the connection drops.
- The music widget controls apps on the newer media API, like Transistor.
- Long app names shorten to one line on Home, and text islands no longer leave a gap after them.
- Renaming, removing or uninstalling an app updates its home slot straight away.
- Wallpapers are stored at screen size, so they load faster.

## [1.5]

### Added
- **Lockscreen Music Controls** (Kompakt only): the stock MuditaOS lockscreen media widget only works with Mudita's own player, so anything else playing left the lock screen with no controls. Katapult now draws its own music widget in the same spot for those players, with the same outline as the notification widget. Off by default, needs the Katapult Action Service, and stays hidden for the Mudita player so the two never appear together. The home screen widget still works with every player, Mudita's included.
- **Search Settings** in the System section: opens Android's settings search so you can find a system setting by name without digging through menus.
- **Icon Size** in Appearance: switch app icons between 72 x 72 and 80 x 80 on both the home screen and All Apps. Row and column spacing stays the same, so only the icons grow.
- **Show Weather** (Kompakt only): Shows temperature above the clock, beside the alarm and battery, on the home screen and the screensaver. Syncs with the weather app on the device.
- **Permissions** in the System section: lists what Katapult can access and links to the right settings screen for each.
- **Order Apps** in the All Apps section: sort the app list alphabetically, by most recent, or by most used. Recency and usage come from Android's usage access, asked for when you pick one of those. Alphabetical keeps your manual reorder.

### Changed
- New installs from 1.5 onward start with the extra dock row on, vertical All Apps gestures, page arrows hidden, infinite scroll off, and 80 x 80 icons. Updating from an earlier version changes nothing: your settings are kept exactly as they are, icons included.
- The home dock rows now sit on the same grid All Apps uses, so the rows line up when switching between the two screens. Holds whether app names and the status bar are shown or hidden, and the All Apps button lines up with the app icons beside it.
- The album art placeholder in the music widget is now a black note on white instead of white on black, matching the rest of the widget.
- Hide App Names moved from the Home Screen section to Appearance, since it affects labels on both the home screen and All Apps.
- **Disable Home Editing** is now **Long-press app in home**, with a choice of App Info, Context Menu, Clear Notifications, Edit Shortcut or Disabled. Context Menu opens the same menu as All Apps. Your existing setting carries over.
- Settings that used to cycle through their values on each tap now open a list you pick from: Long-press app in home, Clock Format, Date Format, Double tap in home, E-Ink Mode and Screensaver Update. Date Format in particular has six values, so finding one no longer means tapping past the rest. The two-value settings still toggle in place.
- The screensaver's Show Clock, Show Notifications and Text Backgrounds toggles are now one **Customization** entry listing what is on, and the update interval moved inside the Screensaver Update list, so choosing Interval and setting the minutes happens in one place instead of two.

### Fixed
- Turning on a setting that needs the Katapult Action Service now completes by itself once you enable the service, instead of needing a second tap.
- Importing a custom icon could silently do nothing and drop you back on the home screen, if Android reclaimed the launcher's memory while the file picker was open. Imports now survive that, and a failed one says so.
- Czech, Dutch, French, German, Italian, Polish, Portuguese, and Spanish were missing 19 strings, including the whole Check for Updates flow, which rendered in English. All nine languages are complete again.

## [1.4]

### Added
- **App Shortcuts**: long-press an app in All Apps and pick App Shortcuts to enable the shortcuts it publishes (New timer, a specific conversation, and so on). Enabled shortcuts appear in All Apps as their own tiles with reorder, rename, custom icons, and remove, and can be assigned to any home slot including clock and date. Requires Katapult to be the default launcher.
- Pinned shortcut support: "Add to Home screen" from the browser (websites and PWAs) and pin requests from other apps now land in All Apps instead of going nowhere.
- **Config Export/Import**: save all settings to a timestamped JSON file and restore them later, plus a Clear All Data option. Imports are validated, so a foreign or corrupted file is rejected instead of wiping settings.
- **Check for Updates**: in-app updater that checks GitHub releases, shows the release notes, and downloads and installs the new APK.
- **Double Tap in home** now cycles between Disabled, Brightness, and Lock Phone (locking uses the accessibility service). Replaces the Double-Tap Brightness toggle; an enabled toggle carries over as Brightness.
- **Hide Status Bar Clock** setting (Kompakt only): covers the duplicate status bar clock while the launcher is in the foreground, since the home screen shows its own clock. Needs the accessibility service; off by default.
- **Material Icons** in Change Icon: the dialog now has two tabs, Katapult Icons (bundled set plus PNG/SVG import, as before) and Material Icons with 133 outlined Material icons to assign to any app.
- **Rejected calls and voicemail in the Phone badge** (Kompakt only): the badge now counts declined calls and waiting voicemail alongside missed calls, mirroring how the Mudita phone app and launcher behave on MuditaOS 1.6.0, so both launchers show the same number.
- **Clear Notifications for the Phone app** (Kompakt only): long-press Phone in All Apps to reset its badge. The Phone and Messages badges are read from the call log and SMS databases rather than notifications, so this option was previously hidden. It now works for Phone, and also clears counts the stock launcher cannot.

### Changed
- The accessibility service is now listed as **Katapult Action Service** in system accessibility settings, since it powers the lockscreen widget, the status bar clock cover, phone locking, and the screensaver. Only the label changed; an already enabled service stays enabled after updating.
- Tapping the Katapult icon in All Apps (Show Katapult Icon) now opens Katapult Settings while Katapult is the default launcher, instead of just returning to the home screen. When another launcher is default, the icon opens the app normally.

### Fixed
- AOSP Calendar, WebView DevTools, AOSP Search (Quick Search Box), and Chrome now get bundled default icons instead of their stock ones.
- Turning on Lockscreen Notifications or Screensaver on power no longer saves the setting when the Katapult Action Service was never enabled. The toggle used to stick on while the feature did nothing, and enabling the service later for an unrelated reason would switch it on unannounced.

## [1.3]

### Added
- **Dark Mode** setting — inverts the whole launcher to white-on-black, including app icons (the same monochrome silhouettes, filled white instead of black). Manual toggle; off by default.
- **Text Backgrounds** setting — draws white rounded "islands" (matching the icon corner radius and border) behind the clock, date, status row, and app names so text stays legible over a wallpaper. Off by default.
- **Swipe for All Apps** setting — open All Apps with a swipe on the home screen. Direction follows the gesture setting: swipe up (vertical) or swipe left (horizontal). Off by default.
- **App Gesture Direction** setting — switch All Apps page navigation between Horizontal and Vertical swipes.
- Adaptive All Apps grid — the number of rows now scales to the screen height, so taller devices show more apps per page and shorter devices fewer (instead of a fixed 3×4). With app names hidden, an extra row fits.
- Swipe back to Home from the first All Apps page (when infinite scroll is off) — swipe right (horizontal) or down (vertical).
- Reorder hint tooltip — entering reorder mode shows a floating hint ("Tap an app to swap it with the highlighted one") that dismisses on the first tap or after a few seconds.
- **Experimental: Lockscreen Notifications** — shows per-app notification counts on the lock screen as an island-style widget, via an accessibility overlay (must be enabled in system Accessibility settings). Includes an app allowlist and latest/oldest sort; long-press the widget to move and resize it, tap to save. Off by default. Note: the accessibility permission sounds scary, but Katapult uses it only to draw the widget and to read the lock screen itself — never your apps — so the widget hides when the PIN screen appears; it records and shares nothing, as its open source code proves.
- **Experimental: Screensaver** — a Katapult screen saver (dream) showing the home clock, date, alarm, battery, and up to 4 notification rows pinned to the bottom. Start it with a two-finger long-press on the home screen (or the home long-press menu); the power button exits it. Select Katapult once as the system screen saver — Settings → Experimental → Screensaver opens the system picker. Android also auto-starts it while charging.
- Czech translation, and completed Dutch; all new strings translated across all 9 languages.

### Changed
- Settings reorganized into titled sections: Appearance, Home Screen, All Apps, Gestures, Notifications, E-Ink (Mudita only), and System.
- Renamed the Extra Dock Row description to "Show 3 extra apps in home".

### Fixed
- With the Rounded icon shape, the small icons in the shortcut app picker and Hidden Apps list rendered as circles — the corner radius now scales with icon size.
- With app names hidden, home shortcuts and All Apps icons now render icon-only (no reserved label space), so icon spacing matches between the two screens.

## [1.2]

### Added
- **Clock Format** setting — cycle the home clock between System, `20:24` (24-hour), `08:24 PM`, `8:24 PM`, and `8:24`. Replaces the old Show AM/PM toggle; the AM/PM suffix now appears automatically for the 12-hour formats that include it.
- **Date Format** setting — cycle the home date between System (locale long date), `Mon, Jun 8`, `Monday, June 8`, `June 8, 2026`, `6/8/2026`, and `2026-06-08`.
- **Disable Music Widget** setting — hides the home-screen music widget.
- Bundled icons for Discord and Libro.fm.

### Fixed
- The Home button now dismisses any open bottom sheet (menu, app picker, hidden apps, app context menu, rename, change icon, reset confirm) — previously only Back closed them.

## [1.1]

### Added
- Custom icon import — long-press an app → **Change Icon** to pick a bundled Katapult icon or import your own PNG/SVG. SVGs are rasterized and centered at 60% of the icon shape with black fill.
- **Clear Notifications** option in the app context menu — dismisses tray entries for that package so the badge resets. Hidden for Mudita direct-badge packages (call log / SMS). Respects re-posting apps.
- **Hide Arrow Buttons** setting — hides the page arrows and dots in All Apps so the grid fills the full height; swipe to change pages. Reorder controls and the indicator row still appear when relevant.
- **Disable Home Editing** setting — locks home shortcuts so long-press no longer opens the app picker. Instead it opens Android's App Info screen for the assigned app. The empty-area long-press menu (Settings / Hidden Apps / Wallpaper) stays accessible.
- **Hide All Apps Button** setting — replaces the dashed-dot All Apps tile in the dock with a regular shortcut (defaults to the system contacts app). Tap launches it; long-press opens the picker (or App Info when home editing is locked). Drawer access moves to a new **All Apps** entry in the empty-area long-press menu.
- Tap the battery percentage on the home screen to open Android's battery settings.
- App version is now shown at the bottom of the Settings screen.

### Changed
- Monochrome app icons are now always used when the app provides one (API 33+ natively, API 31–32 via drawable XML parsing).
- Notification counting rewritten from a counter to a set of active notification keys — fixes apps that update one notification N times being counted as N separate notifications (e.g. podcast apps, multi-message conversations).
- Translations updated for new strings across de / pt / it / fr / pl / es / nl.

### Fixed
- Re-importing an icon for the same package now correctly invalidates the cache.
- Notification counts stay consistent when a conversation with multiple messages is opened — the badge now clears completely instead of decrementing by one.

## [1.0]

Initial public release.
