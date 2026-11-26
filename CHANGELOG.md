# Changelog

Kotatsu changelog. The sections after 0.1 are inherited Katapult history through v1.6.

## [0.7.1] - 2026-11-25

This release includes Kotatsu, Kotatsu Weather and Kotatsu Notes. Kotatsu Sync
changes are recorded below for source history only. Kotatsu Sync is not packaged
in 0.7.1; its release tooling is a separate change.

### 2026-11-25

#### Fix

The launcher hides its screenshot preview before taking the next capture, so the previous thumbnail does not appear in the new image. Overlapping captures wait for their own preview suppression, and the preview can recover from failed surface transactions or window removal and show the next result.

- Exclude the previous preview from new screenshot captures (`d0068a7`).
- Recover from failed preview transactions without leaving stale preview pixels (`89b28da`).
- Tie capture readiness to the active preview suppression leases (`4260662`).
- Use a compositor-compatible hidden buffer and restore visibility when reusing the preview (`93b1c53`).

#### Tests

The Sync settings test now waits for the full confirmation before pressing Move and for the serialized operation to finish before asserting the persisted Last sync status. This changes only the test, not the move itself.

- Wait for the confirmed contact move and its status update in settings tests (`a8cb99e`).

#### Integration

The screenshot-preview branch incorporated the concurrent Sync test fix. The merge adds no separately described behavior.

- Integrate main into the screenshot-preview branch (`8f3d284`).

### 2026-11-24

#### Fix

Sync's calendar recovery checks that an approval still matches the evidence it was granted for, and then checks the same write permissions for new and recovering operations. Its contact moves also keep the waiting count current and distinguish address-book identity from its display label. These changes are source-only in 0.7.1.

- Revalidate calendar review evidence, hold bulk losses across calendars and preserve accurate contact-move status (`2efbfbc`).
- Use one calendar write policy for new work and recovery, with model tests for interruptions (`2946657`).

#### Feature

Secondary settings now use the same Paper controls as the main settings book. Long choices and checklists retain help and can page; short Notes quote choices open in sheets, while the note picker stays a page. Launcher import and reset use the same confirmation page style. In the Sync source, Look ahead is a short sheet with four choices, and legacy horizons map to a supported value on read.

- Share option rows, selection marks and help across sheets, paged choices and checklists (`5b8a68d`).
- Use a shared Paper confirmation page for launcher import and reset (`fbf1c2e`).
- Open Notes quote frequency and order as short Paper sheets (`de6b8a5`).
- Offer 2, 4, 8 and 12 weeks in the Sync Look ahead sheet (`19f653d`).

#### Release tooling

Release staging and verification distinguish the three publishable apps from Sync and the fake calendar fixture. Gradle settings and the tools now use a shared module inventory, reject unknown, missing or duplicate entries, and require Kotlin build scripts for the three release apps. This changes what the tooling checks, not the set of published APKs.

- Classify release apps separately from Sync and the calendar fixture (`da08d18`).
- Detect undeclared Android application builds in nested directories (`d830a1b`).
- Distinguish active application plugin declarations from comments and inactive declarations (`ff5626b`).
- Declare the module inventory once for Gradle settings and release tooling (`147dd10`).
- Require Kotlin build scripts for release apps (`549689f`).

#### Integration

The release-tooling and Sync branches incorporated concurrent changes from main. The Paper merge also uses the shared confirmation page for Sync contact moves and server deletions, reconciles the shared action row and updates the settings assertions for the new controls. Changes already described under their own commits are not repeated here.

- Integrate main into the release-module classification branch (`a8f530a`).
- Integrate the Paper pages into Sync and reconcile action rows, confirmations and tests (`6caee7e`).
- Integrate the release-module classification into Sync (`46ec914`).
- Integrate the Gradle module inventory changes into Sync (`aeac51e`).

### 2026-11-22

#### Fix

Sync source changes protect pending calendar writes and hold unexplained calendar loss instead of propagating it. Settings check dependent choices on the same serial lane as sync work and handle unavailable setup. The launcher checklist also tests its controls at different scales.

- Keep competing calendar passes from aborting on an occupied reservation (`abda716`).
- Report unavailable Sync setup and check launcher control layout at scale (`36f6845`).
- Validate dependent settings on the serialized sync lane (`28d23c0`).
- Hold unexplained calendar loss before either provider propagates it (`34fe3df`).
- Keep account and calendar choices explicit and retire insert receipts (`90075bd`).

#### Feature

Sync's source gains settings for calendar and contacts choices and a Paper index chapter for navigating them. Pass status uses typed outcomes and readable wording. The shared control columns also let the launcher's Hidden and Notify labels fit the text scale.

- Add the Sync settings screen and shared size-aware control columns (`437b26e`).
- Render typed sync status in plain language (`70fb3b4`).
- Rebuild the Sync settings screen as a Paper index chapter (`87f063b`).

#### Tests

Sync tests isolate provider and contact fixtures so existing device data is not part of their setup or assertions. They now check rollback on the production move path and audit the isolation guards; there is no shipped APK change from these tests.

- Isolate fixtures and verify production contact-move rollback (`6a6b5d7`).
- Give Sync provider cases owned device state and settings fixtures (`fe69fa0`).
- Strengthen contact isolation checks and the fixture-isolation audit (`0c9d1df`).

#### Integration

The settings branch incorporated the concurrent main history. The merge adds no separately described behavior.

- Integrate main into the Sync settings branch (`f5fda1b`).

### 2026-11-21

#### Fix

Sync source changes keep retry state when old calendar retirement decisions no longer apply. Contact moves also preserve each photo and the selected defaults instead of letting Android choose replacements.

- Preserve pending sync retries and discard obsolete retirement baselines (`276485d`).
- Preserve each full-size contact photo and selected primary defaults (`b2fa747`).

#### Feature

Sync source gains component-start job arming, moves into selected Android sync address books, and visibility of external adapters. Durable pass status and retry ownership let an interrupted one-time move resume for the original destination rather than reroute contacts.

- Arm Sync work on component start and package replacement (`1e928d4`).
- Move phone contacts into selected sync address books (`edeea6d`).
- Persist pass status and the destination of pending one-time contact moves (`92e6fee`).
- Make external calendar and contact sync adapters visible to the bridge (`fe3b59c`).

#### Tests

Emulator fixtures now wait for platform ownership changes and shell command completion instead of guessing from delays. Read-only shell observations respect the enclosing wait deadline. These changes affect tests, not the released runtime.

- Wait for platform ownership and acknowledged shell completion (`2765d7f`).
- Bound shell observations by the outer wait deadline (`ef13e04`).

#### Integration

The calendar review follow-up incorporated concurrent main history. Its merged changes are described under their own commits.

- Integrate main into the Sync review follow-up (`b9279e8`).

### 2026-11-20

#### Fix

Sync source learns to recover calendar state at individual writes after interruptions, preserve event descriptions when the generated location line is edited, and reserve only rows with pending write ownership. The changes preserve unrelated work and avoid treating historical receipts as pending writes.

- Preserve calendar state and recurrence behavior across interrupted passes (`9303b7f`).
- Keep the real description separate from edits to the generated location line (`63d1ace`).
- Recover pending writes per calendar item from durable evidence (`1fd0a23`).
- Scope receipt reservations to writes that are still pending (`9e1e39a`).

#### Feature

The repository adds an in-development bridge between Mudita Calendar and Android calendars. Provider ports and field mapping support the two-way engine, and Android jobs schedule serialized passes. There is no Sync APK in this release.

- Add the calendar provider ports and event mapping (`7fdcc0b`).
- Add the two-way calendar bridge and persistent mapping store (`49a9681`).
- Schedule serialized calendar bridge passes (`cfafaa4`).

#### Tests

The bridge is exercised against real Android calendar APIs and an emulator-only Mudita stand-in. Connected CI runs those cases without turning the stand-in into a release asset.

- Add the emulator provider fixture and run the Sync suite in CI (`86d67f1`).

### 2026-11-19

#### Fix

Recent apps now treats a relaunch immediately after a stop request as reuse only when there is supporting evidence. Closing without usage consent keeps the necessary clocks but not usage-derived history.

- Tighten close anchors and verify reuse and no-consent behavior (`d8cd417`).

#### Tests and CI

Pull requests gain the full connected matrix and shared emulator preparation. The runner resets onboarding and rejects missing required suites, and failed UI waits retain the window hierarchy and logcat. Fixture audits now inspect actual rendered entry ordering and structural cleanup boundaries, and the Weather replay test observes the frame at hide time; none is a Weather runtime change.

- Audit rendered Settings order and structural fixture-cleanup boundaries (`aca3d4e`).
- Capture UI hierarchy and logcat when a wait times out (`c7b973e`).
- Add shared emulator preparation and connected-suite/JUnit tooling (`4e1ae06`).
- Run the connected suites on pull requests (`961ab8a`).
- Keep cleanup scanning aligned with Kotlin and Java declaration syntax (`5668aa2`).
- Reset onboarding and reject missing required CI suites (`9365038`).
- Capture the Weather replay oracle at the hide boundary (`6c073f2`).

#### Docs

Contributors now have a description of the local checks and the PR CI matrix. The documentation also points to evidence available after a UI failure.

- Document local checks and CI for pull requests (`01d2720`).

#### Integration

The close-anchor and cleanup-guard branches incorporated changes already recorded in main. These merges do not introduce separate product changes.

- Integrate main into the recent-close anchor follow-up (`c454357`).
- Integrate main's connected checks into the cleanup-guard follow-up (`b985b00`).
- Integrate main's close-anchor changes into the cleanup-guard follow-up (`3ef3077`).

### 2026-11-18

#### Fix

Recent apps captures the selected row before dismissal and uses Android activity-resume evidence to tell a genuine reopen from stale history. The close trail is matched within the right boot and accounts for Android's actual timestamp shift, keeping unproven reopens hidden. Without usage consent, the action does not collect usage history.

- Capture the close boundary before dismissal changes the offered list (`fe71a3a`).
- Anchor closes in Android's usage-event stream (`73f412f`).
- Match a close's event trail only within its boot (`8859a4f`).
- Place closes using the exact shift applied by Android (`6b2f119`).
- Keep unproven reopens hidden and cover actions without usage consent (`6c59d95`).

#### Tests

Test cleanup now waits for accessibility state to persist, and guard checks no longer mistake expression-bodied code or localized layout for failures. These changes are in the test fixtures and audits.

- Reconcile the accessibility fixture's live and persisted settings (`e808d04`).
- Keep cleanup guards within expression-bodied functions (`ad8bff4`).
- Make fixture guards independent of locale and line wrapping (`72ae114`).

#### Integration

Three follow-up branches incorporated concurrent launcher and test history. The merges are recorded here; their individual changes are counted once.

- Integrate main into the editor-input follow-up (`5e17aed`).
- Integrate main into the recent-close records follow-up (`a5b1207`).
- Integrate main into the Settings page-fill follow-up (`d61e1f3`).

### 2026-11-17

#### Fix

Settings fits complete groups into the available page space before advancing, while overflowing content remains reachable. Recent apps keeps a closed entry off the list until Android reports a new resume rather than counting an old pause as reuse.

- Fill Settings pages before advancing, accounting for the visible notice (`33f95e2`).
- Keep closed apps hidden until Android reports a fresh resume (`e46821b`).

#### Tests

Fixture cleanup now attempts independent widget-host and Android task restorations even when one fails. The suite reports queued Notes cleanup failures and checks shell exit status, so neither a missing acknowledgement nor a cleanup error masks the original failure.

- Return queued cleanup failures to the test thread (`e52338a`).
- Attempt Weather and Notes widget-host restorations independently (`ee04f43`).
- Isolate independent test fixture restorations (`966e445`).
- Confirm exit status rather than assuming bounded shell commands succeeded (`64adb79`).

#### Docs

The Recent apps guide distinguishes acting on the newest row from acting on other rows. The accessibility assertion checks the wording the guide describes.

- Clarify Recent apps return-to-Home behavior and verify native action names (`d2d91b2`).

### 2026-11-16

#### Fix

Launcher and Weather editors keep input tied to the window that owns it, so a stale keyboard or focus event cannot act on the next editor. Edge swipe settings hide their dependent rows when disabled and restore the saved choices when enabled.

- Fix keyboard and focus ownership across launcher and Weather editor windows (`a74e5d7`).
- Hide dependent edge swipe controls while the master switch is off (`7df169e`).

#### Feature

Recent apps labels its action Hide on Android 14 and later, where it cannot stop other apps. Hidden Apps names its checkbox for the state it represents, including in translated accessibility labels.

- Use Hide on Android 14+ and keep Close on earlier Android versions (`243478a`).
- Label the Hidden checkbox by its state in each locale (`0ac7583`).

#### Tests

Fixtures own the activity, service, provider and preference state they need, report unexpected HOME ownership, and keep original failures visible during cleanup. Owned inventories and cold wallpaper readbacks make the assertions independent of previous tests. This block changes test coverage, not release behavior.

- Give fixtures ownership of service, widget provider and Settings task state (`5253521`).
- Preserve original failures through fixture cleanup (`ed0cf36`).
- Supply an owned duplicate-label launcher inventory (`ac19076`).
- Verify cold wallpaper restoration without changing saved activation values (`4bb45b2`).
- Report the resolved HOME on ownership failures (`51bed90`).
- Seed preferences within the owning fixture scope (`e859021`).
- Keep Notes assertions visible through cleanup (`b2e65d4`).
- Keep launcher assertions visible through cleanup (`a10077b`).

### 2026-11-15

#### Fix

Returning from a child Settings page restores the parent's page or scroll position. Screenshot previews follow capture order even if writes finish out of order, so a late save cannot replace a newer result or bring back an expired preview.

- Preserve Settings positions across child pages (`f27145b`).
- Keep screenshot previews ordered by capture (`11913cd`).

## [0.7.0] - 2026-11-14

### Added
- **Recent apps** opens over the current app on a double press of Home, with tap to switch and Close or a left swipe to close an app.
- **Screenshot** captures the screen when you hold Home and press Volume down, saves it to Pictures/Screenshots and shows a brief preview.
- **Home edge swipes** open the side screen or camera from Home as well as the lock screen, with an Enable on home screen switch.
- **Home menu** opens in a bottom sheet, and holding Home opens the same menu over any app without leaving it when dismissed.
- **Home button settings** provide separate Recent apps, Screenshot and Home menu switches, all on by default.

### Changed
- **Dark mode** uses one palette across launcher screens and overlays, with Theme mode defaulting to System while lock-screen surfaces, Weather and Notes stay light.
- **Short settings choices** open in a bottom sheet with the selected option marked, while longer lists keep their full page.
- **App menu** puts quick actions first, followed by Kotatsu, Android and Move tiles, with Change instead of Move on Home and an App icon checkbox to add or remove quick-action icons.
- **Holding an app on Home** always opens its app menu, replacing the Long-press app on Home setting.
- **Swipe for All Apps** offers None, Up, Left or Up and left, with Up and left for new installs and existing directions preserved.
- **All Apps paging** follows its arrows: left/right with arrows shown, up/down with arrows hidden, replacing the App gesture direction setting.

### Fixed
- **Kotatsu Weather refreshes** no longer silently drop a new refresh when an older job stops.
- **Swipe to Home** no longer mistakes a small sideways or vertical drift for an intentional return from All Apps.
- **Slow Home swipes** no longer open the Home menu.
- **Home button navigation** closes Home side screens when returning Home.
- **Recent apps Close** retries briefly after returning Home so Android has time to make the app eligible to stop.
- **Notes task lists** keep the bottom of the last task's letters fully visible.
- **Settings paging** keeps scrolling within overflowing content until a paging swipe can take over.
- **Imported settings** preserve the previous Home swipe direction when restoring an older backup.

## [0.6.1] — 2026-11-08

### Fixed
- **Empty quotes notes** are skipped in the lock-screen widget slot instead of leaving an empty widget on screen.

### Added
- **Kotatsu Notes task-list widget** also reads Mudita Notes text notes. Bullet lines become checkboxes and other lines become headings; checking an item writes a check mark into the note.

## [0.6] — 2026-11-08

### Added
- **Lock-screen edge swipes** open the side screen from the left and the secure camera from the right by default. Choose Side screen, Camera or Nothing for each edge. Setup explains how to allow Open Camera while locked.
- **Side screen** hosts one Full widget or a Split top/bottom pair, with a screen name and a scroll rail for lists. Widgets that open apps ask for the phone's PIN first.
- **Kotatsu Notes** is a separate, optional app with a Mudita Notes task-list widget. Choose a list, then check and uncheck its items while locked.
- **Quote of the day** shows quotes from a Mudita Notes text note in the lock-screen widget slot. Choose daily, hourly or on-wake changes, with sequential, shuffled or random order.
- **Kotatsu Notes settings** bring task-list and quote choices together, with separate switches for the two widget kinds.

### Changed
- **Lock-screen widgets** share a single slot. Rotate on each wake or every 30 seconds, 1, 5 or 15 minutes while awake, or swipe to switch. Settings shows each widget as a card with its controls.
- **Kotatsu Weather margins** are left to the host, so the widget fits the lock-screen slot and side screen without extra outer padding.

### Fixed
- **Kotatsu Weather editor** keeps its rows in place during forecast refreshes, so taps on its controls are no longer lost.

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
