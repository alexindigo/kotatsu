# Kotatsu house style: Paper index

**Paper index** is the style for Kotatsu-owned screens in the Android launcher
for the Mudita Kompakt e-ink phone. Navigation is a table of contents;
categories are chapters; groups are subheadings; secondary destinations are
pages in the same book. Typography, alignment and explicit words carry the UI.

Android settings, permission prompts, file pickers, provider apps and the system
keyboard remain outside Kotatsu's visual ownership. Reuse the shared kit for
new screens, including companion apps that already depend on it.

## Source map

This guide describes the current screens and shared kit. When the
guide disagrees with a rendered screen, inspect the screen first, then the
kit. Known mismatches are listed under Inconsistencies; they are not a second
style to copy.

Paths below are relative to the repository root. `kit/` in the tables means
`paper-ui/src/main/java/com/gezimos/katapult/ui/settings/`.

| # | Source | Owns |
|---|---|---|
| 1 | `paper-ui/src/main/java/com/gezimos/katapult/ui/PaperTokens.kt` | Bundled Lato family, resource-backed `PaperPalette`, `LocalPalette`, derived `LocalInk` and `LocalSurface` |
| 2 | `kit/PaperIndex.kt` | Headers, index tracks, entries, groups, Notice, pagers, page frames, `ChoiceSurface`, checkbox drawing, help |
| 3 | `kit/PaperActions.kt` | Independently actionable name/value tracks (`PaperActionEntry`) |
| 4 | `kit/PaperCard.kt` | Bracket cards, compact widget content, reorder presentation and gesture ownership |
| 5 | `kit/SettingsModel.kt` | Entry types, static/app-built choice classification, categories, groups, whole-group allocation, Action Service reminder state |
| 6 | `kit/SettingsNavigation.kt` | Saveable destination stack; only the top page is rendered |
| 7 | `app/src/main/java/com/gezimos/katapult/ui/settings/*Category.kt` | Launcher chapter vocabulary, groups, live values and injected callbacks |
| 8 | `app/src/main/java/com/gezimos/katapult/ui/SettingsScreen.kt` | Settings routing, parent page retention, permissions, editors and external handoffs |
| 9 | `app/src/main/java/com/gezimos/katapult/ui/settings/AppChecklistPage.kt` | Hidden/System apps matrix and notification-app selection |
| 10 | `app/src/main/java/com/gezimos/katapult/ui/settings/LockscreenWidgetsPage.kt` | Widget controls, Widgets section, Add widget and management destinations |
| 11 | `kit/PaperSheet.kt` | Shared bottom sheet drawing and heading; Activity and Action Service hosts own window lifetime and Back |

## Principles

Use the appearance owner's palette: black ink on white paper in Light, white
ink on black paper in Dark. Read `LocalInk` and `LocalSurface`, not hardcoded
per-screen colours. Muted is `#777777` in both palettes; Home menu leaders,
choice-sheet leaders and empty boxes, and Weather's stale/status marks use
it. Do not add other greys, opacity, gradients or shadows.
Ordinary antialiasing is rasterization, not an additional semantic colour.

Leave honest blank space. A sparse Screensaver page with one setting is valid.
Rules separate real sections, leaders connect names to values, and boxes show
checkbox states. Cards are for real content blocks such as configured widgets,
not a wrapper around every setting. No ornamental icons or chapter numbers.

Keep redraws deliberate. No animation, shimmer or automatic scrolling. Prefer
stable positions, explicit actions and few e-ink refreshes. Widget drag updates
are whole-card steps, not continuous movement effects.

Separate configuration from availability. A feature can be configured **On**
while a required service is **Off**. An app choice is not an Android grant,
and a grant is not proof that a consumer is active. Say which state is shown.

Build relationships structurally. A name, dotted leader and value share one
measured baseline; the value hugs the right content edge. Do not align them
with spaces, literal periods, fixed text widths or compensating offsets.

### Appearance ownership

`paper-ui/src/main/res/values/paper_palette.xml` defines ink, paper and muted
once. `PaperPalette` reads those resources for Compose and Android Views;
`inverse()` swaps ink and paper for held cards. `LocalPalette` has no fallback:
every Compose root must provide an owner's palette. `LocalInk` and
`LocalSurface` are derived accessors, not independent state. Open surfaces
observe their owner.

| # | Surface | Palette |
|---|---|---|
| 1 | Kotatsu Home, All Apps, Settings, Recent apps, choice surfaces and screenshot preview | Kotatsu Theme mode: Light, Dark or System; System is the default |
| 2 | Lock-screen widgets, music, weather, notifications, side screen and edge pages | Always Light, matching Mudita's white lock screen |
| 3 | Screensaver | Kotatsu Theme mode |
| 4 | Notes and Weather screens and widgets | Light, owned by each companion app |

Layouts and values XML reference palette resources. `AppearanceSourcePolicyTest`
checks all modules' main Kotlin/Java and layout/values XML during unit tests.
Drawable/mipmap artwork is exempt and UI glyphs are tinted at use.
Its exact-snippet pixel-processing allowlist covers contrast masks, canonical
icon silhouettes and glyph-coverage measurement, never whole UI files.

## Exact kit tokens

Use `sp` for type and `dp` for geometry. Lato Regular is weight 400; Lato Bold
is weight 700. The typeface is bundled; do not substitute a platform default.
Screenshot pixels are not Android sizing tokens. The values below come from
the implementation, not from measuring an image.

### Type, rows and alignment

| # | Element | Exact current values | Owner |
|---|---|---|---|
| 1 | Font and colours | Lato Regular/Bold; Light ink `#000000`, paper `#FFFFFF`; Dark swaps them; muted `#777777` in both | `PaperTokens.kt`: `LatoFamily`, `PaperPalette`, `LocalInk`, `LocalSurface` |
| 2 | Page title | 26 sp Bold, 31.5 sp line height; one line, ellipsis | `PageHeader` |
| 3 | Header structure | Minimum 57 dp high; top 9 dp, end 12 dp; start 6 dp with a leading action, otherwise 16 dp | `PageHeader` |
| 4 | Header action | Centred drawing inside a 48 x 48 dp button; help `?` is 22.5 sp Bold | `HeaderAction`, `ChapterHeader` |
| 5 | Back/next chevron | 18 x 21 dp drawing, 2.25 dp stroke; x positions 0.33/0.71, y positions 0.22/0.50/0.78 of its bounds | Private `Chevron` |
| 6 | Group heading | 14 sp Bold, 17.25 sp line height; 3 dp top padding, 5.25 dp before a 2 dp full-width rule | `GroupHeading` |
| 7 | Root name | 21 sp Bold, 25.5 sp line height; one line, ellipsis | `IndexName(root = true)` |
| 8 | Root summary | 14 sp Regular, 17.25 sp line height | `IndexLine(root = true)` |
| 9 | Entry name/value | 18 sp Bold, 21.75 sp line height; name one line with ellipsis | `IndexName`, `IndexLine` |
| 10 | External glyph | 18.75 sp Bold after a value, separated by 7.5 dp | `IndexLine(external = true)` |
| 11 | Name/value tracks | 7.5 dp gap on each side of leader; 9 dp minimum leader allocation; value measured first, at most half the row width; shared first baseline | Internal `IndexTracks` |
| 12 | Dotted leader | 1.5 dp high/stroke; round caps; dash pattern 0.1 raw pixel on, 3 dp off | Internal `IndexLeader`, allocated by `IndexTracks` |
| 13 | Chapter entry | Minimum 48 dp without help, 67.5 dp with help; 9 dp vertical padding inside the target; grows with content | `Entry` |
| 14 | Entry help/extra summary | 14 sp, 16.5 sp line height; 1.5 dp above each line/block; help Regular, Open summary Bold | `Entry` |
| 15 | Chapter content | 16 dp horizontal inset; 9 dp between groups | `IndexGroup`, `PaperIndexRenderer` |
| 16 | Permission entry | Explicit 63 dp minimum; otherwise normal Entry type and help | App `PermissionRow` -> `Entry` |
| 17 | Page choice/checkbox option | Minimum 53.25 dp row; 18 sp, 21.75 sp line height; one-line label; checkbox labels always Bold; trailing 48 x 48 dp centred control slot | Private `OptionRow`, `CheckboxOption` |
| 18 | Page choice weight | Selected label Bold; other labels Regular; no dotted leader | `ChoicePage`, `ChoiceOption` -> `OptionRow` |
| 19 | Check mark | 21 x 21 dp vector, 1.5 dp stroke; path through (4,13), (9,18), (20,4) on a 24-unit grid | `CheckMark` |
| 20 | Checkbox drawing | 20 dp square, 3.75 dp corner radius; 2.5 dp enabled or 1 dp disabled border; checked ink fill, paper check | `IndexCheckbox` |
| 21 | Checkbox check wrapper | 14 dp wrapper around the shared check drawing; wrapper is not a touch target | `IndexCheckbox` |
| 22 | Focus | 2 dp ink rectangular outline, only while focused | Internal `Modifier.inkFocus()` |
| 23 | Help paragraphs | 14 sp Regular, 19.5 sp line height, 13.5 dp bottom spacing per paragraph | `HelpPage` |
| 24 | Introductory prose | 14 sp Regular, 19.5 sp line height, 6 dp bottom padding | `SheetText` (historical name; used on full pages) |

### Frames, footers, actions and cards

| # | Element | Exact current values | Owner |
|---|---|---|---|
| 1 | Root frame | Title minimum 36 dp high, 16 dp horizontal inset; title 26/31.5 sp Bold; rows minimum 48 dp, horizontal 16 dp and vertical 9 dp padding | `TableOfContents` |
| 2 | Root bottom lane | Notice when present; otherwise a fixed 48 dp spacer; contents may scroll | `TableOfContents` |
| 3 | Page frame | Full available height and width; shared header then weighted body; surface background; system insets applied by owner | `PageFrame`; launcher `SettingsScreen` owns status/navigation bar padding |
| 4 | Secondary content | 16 dp horizontal inset; measured whole child blocks; header measured separately; six-block page cap | Private `PagedContent`, via `SettingsPage` / `PagedSettingsPage` |
| 5 | Pager lane | Ordinary 63 dp high with 7.5 dp bottom padding; compact 48 dp high without bottom padding; 12 dp side padding | `Pager` |
| 6 | Pager arrows | 48 x 48 dp targets; centred 40.5 dp circle, 3 dp border, shared chevron | Private `PageArrow` |
| 7 | Pager dots | 6 dp circles, 1.5 dp border, 4.5 dp gap; active dot filled ink | `Pager` |
| 8 | Notice | 16 dp side inset, 2 dp top rule; row minimum 48 dp; 6 dp between tracks; message 14/18 sp Regular with 6 dp vertical padding | `Notice` |
| 9 | Notice action/dismiss | Action minimum 64.5 x 48 dp, 14 sp Bold underlined; dismiss 48 x 48 dp, `×` 22.5 sp | `Notice` |
| 10 | Close vector | 18 dp square, 2.25 dp strokes from 20% to 80% of bounds; separate from Notice's text `×` | `CloseMark` |
| 11 | Plain full-row action | Minimum 48 dp high, 18/21.75 sp Regular, left aligned; no invented value/leader | `SheetAction` |
| 12 | Two-target action entry | Name/help and trailing action each minimum 48 dp; help 14/18 sp; action 18/21.75 sp Bold; shared index tracks | `PaperActionEntry` |
| 13 | Bracket card | Full measured content height; 2 dp bracket strokes, 7.5 dp caps; horizontal 14 dp and vertical 6 dp padding | `PaperCard` |
| 14 | Card text | Name 18/21.75 sp Bold; help 14/18 sp Regular; size 14/18 sp Bold; tracks stacked without extra gaps; minimum content height 48 dp | `PaperCardEntry` |
| 15 | Card external action | Independent minimum 48 x 48 dp target, top-end aligned; `Open ↗` 18/21.75 sp Bold; 7.5 dp leader gaps, 9 dp minimum leader allocation | `PaperCardEntry` |
| 16 | Card list | 12 dp between cards; whole held card becomes black with white ink, including bracket lanes | `PaperCardList`, `PaperCard(moving = true)` |
| 17 | Hide/Notify matrix | 64 dp wide outer control columns and minimum 64 dp row/cell height; 6 dp between columns; labels 14/17.25 sp Regular in 64 dp columns | App `VisibilityRow`, `ColumnLabel` in `AppChecklistPage.kt` |
| 18 | Message editor field | Single line, 18 sp Lato Regular; 2.5 dp ink border, 12 dp padding; ink cursor; IME padding on page | App `LockscreenMessageSheet` -> `SettingsPage(imePadding = true)` |
| 19 | Bottom sheet | Bottom anchored, paper fill; 2 dp ink outline on top and sides, no bottom edge; 18 dp top corners; 16 dp side and 10 dp bottom content padding, navigation-bar padding | `PaperSheet` |
| 20 | Sheet heading | Normal page header with zero start inset inside the sheet's content inset, followed by a 2 dp ink rule | `PaperSheetHeading` |
| 21 | Choice-sheet help | Entry help 14/16.5 sp Regular; 1.5 dp top and 4.5 dp bottom padding | `ChoiceSurface` |
| 22 | Choice-sheet option | 18/21.75 sp, selected Bold otherwise Regular; minimum 48 dp without help or 67.5 dp with help; 9 dp vertical padding; optional full-width 14/16.5 sp help with 1.5 dp top gap | Private `ChoiceSheetOption` |
| 23 | Choice-sheet tracks | Shared baseline leader geometry in muted; value cell centred on the name track; 20 dp checkbox, chosen ink fill/paper check, unchosen 2.5 dp muted outline; external mark before box with 7.5 dp gap | `IndexTracks`, `IndexCheckbox` |

Targets must be at least 48 dp in both dimensions. A value word or a check
drawing is not its own tiny target: the surrounding row or independent control
cell owns interaction. Let rows grow for larger type and translated help.
Never reduce type or targets to preserve a screenshot's page count.

Help-less chapter entries use the index-row minimum instead of reserving a
missing help line. Their 9 dp vertical padding stays inside the row target;
groups retain their normal spacing. Entries with help keep their normal
sizing. Pagination measures actual rows rather than assuming every row has help.

## Page anatomy

These sketches show relationships, not pixel dimensions. Periods stand for the
kit's drawn leader; `[ ]` and `[x]` stand for shared checkbox drawings. Blank
space is intentional. The phone's system status bar is outside these sketches.

### Root: a table of contents

```text
Settings

Appearance ............. Rounded / Light / E-ink off
Home Screen ...................... Alarm on / Dock +3
All Apps .............................. Alphabetical
Gestures .................................. Vertical
Notifications ........................ Access granted
Lock screen ................... 2 widgets configured
Screensaver .................................... Off
System ........................ Access and defaults
About ..................................... Kotatsu
App Store ............................ Open F-Droid ↗

                 reserved bottom lane
```

`TableOfContents` has a compact, noninteractive title and no header Back icon.
Device Back exits Settings. Root names are larger than chapter entries;
their summaries are smaller and Regular. Every row opens its destination in
one tap. Only Open and Link entry types are accepted by this renderer.

Settings root has ten index rows with a 48 dp minimum height and a bottom
lane of at least 48 dp. That capacity is not a mandate to squeeze ten rows
onto every device or font scale. Contents scroll when necessary. Removing
the Notice retains the title, rows and bottom lane.

### Chapter: grouped settings and a fixed pager

```text
< Appearance                                      ?

Icons
---------------------------------------------------
Icon shape ................................. Rounded
Shape of app icons

Icon size .................................. 80 x 80
Size of app icons

Hide app names ................................. Off
Hide labels under app icons

                  honest blank space

                       * o o                    (>)
```

The Back action and optional help action share `PageHeader`. Help opens a
`HelpPage`; do not add a redundant Read Me entry to the settings list. Group
labels are Bold with a solid rule immediately below. Entries have a Bold
name and value on the first line and Regular full-width help underneath.
The pager is outside the scrolling body, attached to the bottom.

### Chapter with a Notice

```text
< Lock screen                                     ?
Notifications
---------------------------------------------------
Lock screen notifications ...................... On
Show notification counts on the lock screen

                  honest blank space

---------------------------------------------------
Kotatsu Action Service is off           Turn on   ×
                       * o                      (>)
```

The Notice is a bottom lane, immediately above the pager. It never floats
over entries. Use compact pager height with a Notice; the targets and circle
drawings do not get smaller. A single-page chapter has no pager. When the
service is On, its Notice is absent; when Off, visibility also depends on
the shared dismissal state described below.

### Management: one book, different content

```text
< Hidden apps (3)
Tick Notify for hidden apps on the lock screen
   Hide                                     Notify
   [ ]    Alarm ...........................
   [x]    Calculator ......................  [x]
   [x]    Camera ..........................  [ ]

                       * o o o o                (>)
```

Hidden apps is a matrix, not two overlapping row actions. Hide is left;
name/leader centre; Notify right. Notify applies only to eligible hidden
apps. Blank/not-applicable and disabled Notify cells retain their own space
and must not activate Hide. Checked is a black square with a white check;
enabled unchecked is a heavy empty box; disabled is a thin empty box.
The current implementation explicitly supports that disabled matrix state.

Lock screen notification apps instead uses a single trailing checkbox per
row and a select/deselect-all header action. Both kinds of multi-select apply
in place and stay open until Back. Use the shared `AppChecklistPage` in the
launcher rather than implementing another app-list renderer.

```text
< Lock screen widgets
Show on lock screen ............................ On
Covers the padlock and unlock hint
Rotation ............................. On each wake
Show one widget at a time, in list order
Swipe to switch ................................ On
Swipe left or right on the widget area
Widgets
---------------------------------------------------
[ Kotatsu Weather ....................... Open ↗  ]
[ Resize, move or remove this widget               ]
[ Medium                                          ]

[ Quote of the day ...................... Open ↗  ]
[ Resize, move or remove this widget               ]
[ Medium                                          ]
Add widget ................................... Open
```

Brackets span one measured block, not three text lines drawn independently.
The name is at the normal entry weight; help is smaller Regular; the size is
smaller Bold. No Size or Remove action rows inside the card. Tap the card
outside Open for Kotatsu's size/move/remove page. Open is an independent
external handoff to provider configuration, or the provider app when
reconfiguration is unavailable. If neither exists, omit Open and its leader;
do not leave an inert action.

The widget list has a Widgets section with one card per configured provider,
followed immediately by Add widget. The three top controls retain their entry
presentation. Add appears on page 1 when measured content fits. Long-press and
drag moves whole cards without animation.

Cards use "Resize, move or remove this widget", the small Bold size,
6 dp vertical / 14 dp inner horizontal padding and 12 dp between cards.
The configured-count summary is omitted from this management page, not from
the parent chapter's entry. On pickup the whole card becomes solid black with
white text/leader ink, including the bracket lanes, in one state redraw.
Brackets and fill have one drawing owner and the same measured bounds. Drag
previews whole-card steps without animation or per-pixel redraw. Drop commits
once and restores normal ink; cancellation restores normal ink and saved order.
Accessibility move actions provide the same reorder result; omit unavailable
directions. Side-screen widget rows use their existing separate presentation.

### Help: prose, not another index

```text
< Gestures

By default, All Apps pages use horizontal swipes or
the arrow buttons. You can change the gesture
direction to vertical and hide the arrows if you
prefer.

Swipe for All Apps lets you swipe up or left to open
the All Apps page, depending on the gesture direction.

                  honest blank space
```

Use `HelpPage(title, paragraphs, onDismiss)`. Paragraphs are Regular, left
aligned, allowed to wrap, and separated by their bottom spacing. No group
rule, leader, value or decorative card is needed. Long help uses the same
measured page machinery, not an overlay. Back returns to its chapter.

### Editor: a field and explicit actions

```text
< Lock screen message
+-------------------------------------------------+
| editable text                                   |
+-------------------------------------------------+
Save

Cancel

              available space above the IME
===================================================
                 system keyboard
```

The current message editor is a full page with one bordered single-line
field, then plain Save and Cancel rows. It requests field focus on entry and
uses IME padding. Save passes trimmed text to the owner; Cancel and Back
leave without applying the draft. The keyboard is Android-owned, not a
Kotatsu surface to restyle. See the inconsistencies section before treating
the current action rows as a model for visual emphasis elsewhere.

### Home menu: transient actions

The Home menu uses the shared `PaperSheet`, not a secondary destination page.
It is bottom anchored with paper fill and an ink outline on the top and both
sides, open at the bottom. There is no scrim, shadow or animation. Back or a
tap above closes it without passing that tap to the app below. Activity dialogs
and Action Service overlays host the same drawing; each host owns Back and
the window lifetime.

`HomeMenu` shares its content between Home and the held-Home menu over the
current app. Its heading uses the normal page-header grammar and a rule.
Rows use chapter entries: Bold names, muted dotted leaders and Bold values,
without icons. Settings says **Open**; Wallpaper shows the current wallpaper.
When the All Apps button is hidden, both menus also offer All Apps.
Destination rows over another app
carry the external-handoff mark because they open Kotatsu; rows on Home do
not. Menu leaders use the palette's muted token; labels and values stay ink.

### Short choices: a sheet over the chapter

Static lists of one to four choices open the shared `PaperSheet` on tap.
Longer lists and app-built lists keep `ChoicePage`, even when an app-built
list currently has few items. `SettingsEntry.Value.usesSheet` owns this
decision; every value consumer uses `ChoiceSurface`. There is no rolling
selector or long-press interaction.

The sheet uses `PaperSheetHeading`: the normal 26 sp Bold page title and
rule, not a separate smaller title. Entry help is 14 sp above the options.
Each option has an 18 sp ink name, Regular unless chosen (Bold), a flexible
muted dotted leader and a checkbox at the value edge. Chosen is ink-filled
with the paper check; unchosen has an empty muted outline. The box is
centred structurally on the name track; the leader follows the name's
baseline. Optional option help sits below the full line.

An option that opens another surface carries the external-link mark in
the value cell immediately before the box, and a localized external-action
accessibility label. Wallpaper's **From photos** is such a handoff.
The whole row exposes radio-button selection semantics; the box is a
drawing, not an independent checkbox action.

Selection closes before applying the callback, so a choice can open another
destination. Back or a tap above leaves the value unchanged. The chapter
stays visible without a scrim or dimming, but is excluded from accessibility
while the sheet owns interaction. One Settings back stack owns this
transient destination and preserves the chapter's page number.

## Values and interaction vocabulary

| # | Content | Use |
|---|---|---|
| 1 | **On** / **Off** | A boolean setting's literal value. Tapping the full `SettingsEntry.Switch` row toggles it. No pill switch or sliding dot |
| 2 | **Rounded**, **Light**, **80 x 80**, **Latest on top** | The actual choice value, Bold at the right. A `SettingsEntry.Value` opens `ChoiceSurface`: a sheet for one to four static choices, otherwise `ChoicePage` |
| 3 | **Open** | An internal management destination, not a fake current value. `SettingsEntry.Open` supplies this by default |
| 4 | **Save file** / **Choose file** | Purpose-specific `Open.actionLabel` values on Config export/import. Say what the next action does |
| 5 | **Granted** / **Not granted** | Current Android access status. The whole permission row manages access; it is not a grant/revoke switch |
| 6 | **On** / **Off** on Kotatsu Action Service | Live service availability in Permissions, not a feature configuration choice |
| 7 | **↗** | External handoff. `SettingsEntry.Link` renders the glyph as the value; `Open(external = true)` appends it after an action such as **Open** |
| 8 | Regular root summary | Short live chapter summary, not a second action. Root Link may append **↗** after that summary |
| 9 | Regular help | Full-width explanation below the entry's first line; explain effect/options, not a second copy of its current value |
| 10 | Small Bold summary | Extra configuration detail, such as **Configured: On, 2 widgets**, after the help of an Open entry |
| 11 | Metadata | A name and accurate value with no interaction. Do not invent **Open** or a chevron for static version/runtime information |

Choice pages show one label per full-row option. The selected label is Bold
with one shared vector check in the trailing slot; unselected labels are
Regular. They have no radio rings and no leaders pointing to empty cells.
Selection pops the choice page before applying the callback, because applying
may itself open another destination. Back applies nothing. Multi-select uses
`CheckboxOption` and stays on the page instead.

Render shared marks once. Use `CheckMark` where the bundled font lacks a
check glyph, not an arbitrary fallback font. Do not combine a hidden zero-font
external-link character with a generated duplicate: that changes line metrics
and breaks help spacing. Prefer the kit's existing external rendering path.

## Pagination and navigation

`PageFrame` is the full-height shell, not an allocator. System insets belong
to its owner; do not add another status-bar inset in a child. The launcher
renders only the top destination inside a saveable state provider. Device
Back and header Back pop exactly one level and retain the parent's page.
`SettingsNavigation.push` rejects a destination already in the stack.
The kit also exposes `SettingsBackOwner` for a book with one stable device
Back callback; standalone frames install a callback when appropriate.
For a short-choice sheet, the owner retains the previous destination beneath
the sheet and removes its accessibility semantics until the sheet closes.

Management, help, editors and long/app-built choices use full pages:
root -> chapter -> page -> sub-page. The child replaces the parent's content
and Notice, without a scrim, slide-in transition, heavy modal boundary or
previous page left visible. Short static choices are the shared-sheet
exception described above, in the same Settings back stack. Some other
function names still contain `Sheet` or `Dialog` while rendering full pages.
Android-owned screens and prompts retain their existing behaviour.

| # | Path | Allocation contract |
|---|---|---|
| 1 | `PaperIndexRenderer` | Filter invisible entries and empty groups; measure complete groups and the candidate footer. Maximum six visible entries per page. Each nonempty group must contain one to six visible entries |
| 2 | `paginateGroups` | Keep groups whole; choose fewest pages, then fewest avoidable one-entry pages, then balanced entry counts. A genuine one-setting category is valid |
| 3 | Chapter Notice | Reserve measured Notice height only for candidate pages where it applies. `noticeGroup` restricts it to pages containing that group; a Notice without a group applies on every chapter page |
| 4 | `SettingsPage` -> `PagedContent` | Measure each direct layout child as an indivisible block; maximum six blocks per page plus measured height. Keep a heading and its entries in one `Column` when they must travel together |
| 5 | `PagedSettingsPage` -> `PagedContent` | A separately measured persistent header plus whole item rows, up to six item blocks per page. The pager stays outside the scrolling body |
| 6 | Oversized block | Preserve it whole and allow its viewport to scroll. Do not shrink type, drop content or cut a group to fake a fit |
| 7 | Single page | No dots or arrow buttons. Root uses scrollable contents, not the six-entry chapter allocator |

The six-entry limit is a chapter contract. `PagedContent` counts direct
blocks, not nested settings: a `Column` containing six permission entries is
one block, and the widget cards plus Add widget are one block. It allocates
greedily and does not use the chapter allocator's balancing/orphan pass.
Do not describe it as automatically enforcing a recursive six-setting cap.

Current gestures differ: chapters page on vertical drags; secondary pages
page on horizontal drags unless `swipePaging = false`. Both implementations
accumulate a drag and change page after release beyond 80 raw pixels. These
are implementation details, not dp design tokens. Never make swiping the
only route: round previous/next controls remain available. Unavailable
directions are omitted, not greyed out. Changing page resets body scroll to
the top. Header Back returns to the parent, not the previous pagination page.

## Notice contract

Use **one reusable Notice component** for temporary service/dependency
reminders on every current and future screen, not distinct global/contextual
designs. Its anatomy is a top ink rule, plain message, optional text action
and **×** dismiss control. Lato Regular 14 sp message, Bold underlined action,
palette ink on paper; no box, warning icon or animation.

`NoticeContent` takes message text, localized region/dismiss labels,
optional action label/callback, dismissal callback and announcement state /
acknowledgement callback. Semantic dependency identity belongs to the shared
state owner, not the renderer. The component knows no screen, preference,
service or navigation destination. Owners inject actions; do not duplicate
state or implement a screen-specific Notice.

**Placement:** at the bottom of the owning screen, immediately above the
pager when present; otherwise in the bottom lane as on Settings root. Use
remaining blank space before adding a page. Never overlay settings, shrink
type/targets or split a group just to fit a reminder. Localized/enlarged
message text wraps and grows the lane. With a Notice, the pager uses its
intrinsic 48 dp lane rather than the ordinary 63 dp lane; 48 dp targets and
40.5 dp arrow circles stay unchanged. Page capacity follows measured content,
not a fixed row count. Root title stays only **Settings**. A secondary page
replaces its parent's Notice, rather than repeating it beneath unrelated
child content.

**Dismissal:** retain one dismissal state per semantic message/dependency,
shared across screens and reopening. Root, both Lock screen pages,
Appearance's clock prerequisite and Screensaver's Power-button prerequisite
share the Action Service notice identity, even with contextual wording.
Dismissing anywhere hides it
everywhere during that service-OFF period. Observing OFF again or navigating
does not reset it. Service ON removes it and clears the old dismissal; a
later ON -> OFF transition shows it again. **Turn on** opens setup; opening
or cancelling setup neither enables the service nor dismisses the reminder.
Other messages have independent state.

**State ownership:** `ActionServiceNoticeState` implements the transition,
dismissal and announcement behaviour. `MainViewModel` owns it and persists
dismissal in
`PrefsManager.noticeActionServiceDismissed`; observing service On clears that
saved dismissal. The shared reminder is supplied to root, relevant Appearance,
Gestures, Lock screen, Screensaver and Edge swipes pages. Appearance limits it
to group `screen`; enabled Screensaver limits it to `interaction`. Other
secondary pages do not inherit it. The component receives visibility from
this owner; translated text is never the state key. Verify dismissal,
persistence and the Off -> On -> Off flow at runtime, not from a static screen.

**Wording:** state the condition briefly and truthfully, with an explicit
action when available. Current shared copy is **Kotatsu Action Service is
off** / **Turn on**. Contextual wording such as **Lock-screen features need
Kotatsu Action Service** uses the same anatomy, placement and semantic
identity, not smaller type or a separate design. Configured **On**
values remain distinct from runtime availability. Localize complete messages
and purpose-specific labels; do not key dismissal by translated text or screen.

**Accessibility:** action and dismiss are separate controls, each at least
48 x 48 dp, with visible black focus and message -> action -> dismiss reading
order. Region: **Action Service reminder**; dismiss: **Dismiss Action Service
reminder**, localized. The rule is decorative. Announce a newly changed
condition politely once, not on every visit; do not steal focus. Keep a
sensible focus location after dismissal. `Notice` exposes a pane title and
conditional polite live region, then calls `onAnnounced`; focus and actual
assistive-technology outcomes still need runtime checks.

**Use for:** temporary, dismissible availability/dependency and setup
reminders, once per relevant screen rather than once per affected setting.
**Not for:** ordinary row help, permanent configuration summaries, permission
status/management rows, missing-app values, mandatory confirmations, blocking
errors or transient success feedback. A dismissible reminder must not hide
live state or replace a required decision.

## Writing

Write plainly. Use sentence-style setting names and active verbs. Describe
effects rather than implementation details; do not market a feature or expose
an internal class/flag name. Keep names consistent throughout a flow.

Use **Lock screen** consistently. Retain deliberate destination names such
as **All Apps** and **Home Screen**. Prefer positive labels: **Music widget
On**, not a double-negative disable flag. Do not present a toggle as
"selected". Legacy choice **Disabled** is a choice label, not the vocabulary
for a boolean row's Off state.

Names and action labels stay one line. The kit truncates long names/titles
with an ellipsis; do not manually insert line breaks. Keep labels short
enough to preserve a useful leader and readable value. Help and prose may
wrap across the full width, and rows must grow. Do not shorten a truthful
explanation just to retain a particular page count.

Help explains effect, scope, options or a prerequisite. Prefer "Show one
widget at a time, in list order" over repeating the current rotation value.
An error or unavailable state needs a clear condition and available next
action, not an apology or vague "something went wrong". Avoid redundant
labels and filler.

Localize complete phrases, numbers, plurals and formats. Do not assemble
grammar by joining English fragments. Use stable untranslated IDs for
choices, destinations and dependencies; visible labels may change with the
locale. Docs and contributor prose use ordinary typeable punctuation. The UI
glyphs `↗`, `×` and `✓` may be named when describing their actual drawings.

## Accessibility and e-ink checks

Expose actual roles and state, not only visual weight or stroke thickness.
`Entry` switches use `Role.Switch` and localized On/Off state descriptions;
choices use selectable radio-button semantics; checkboxes expose checked
state. A permission status link remains a button/management action, not a
pretend grant switch. Metadata remains noninteractive.

Leaders, rules and bracket strokes are decorative. Name **?**, **×**, pager
arrows and external actions by purpose. Use heading semantics on page
titles, a logical reading order and visible ink focus on actionable controls.
Independent actions need independent targets and semantics, not a parent
click handler that swallows a nested control. In the Hide/Notify matrix, name
the action and app; announce disabled/not-applicable Notify honestly.

Provide non-gesture routes. Pager buttons supplement swipes; widget move-up
and move-down accessibility actions supplement drag. Omit unavailable move
directions and unavailable provider Open actions. The disabled Notify cell
is a specific matrix state, not a pattern for leaving dead actions visible.

For a new screen, check enlarged text, translation fit, focus order, selected
and unselected states, empty lists, first/last pages and prerequisite-off
states. Verify the consumer outcome, not just that the screen loads. A build,
static mock or single screenshot does not prove interaction or accessibility.
Rendered fit, actual TalkBack announcements, IME behaviour and physical e-ink
refresh need their own checks.

## Adding a setting or page

Keep one owner per responsibility. The app owns live state, persistence,
Android access and navigation actions. Category builders own vocabulary and
grouping. The shared kit owns presentation, measurements and common control
semantics. A reusable component knows its children, never its consumers.

| # | Step | How |
|---|---|---|
| 1 | Pick the semantic entry type | `Switch` for a boolean; `Value` for a pure choice; `Open` for management/action with text value; `Link` for external handoff; `Metadata` for static information |
| 2 | Add localized copy | Use app string resources for title, help, values and accessibility purpose labels. Keep stable IDs separate from text |
| 3 | Define or extend a category | Use `SettingsCategory`, named `SettingsGroup`s and entries in `app/.../ui/settings/*Category.kt`. Inject state/callbacks; do not read storage inside the kit |
| 4 | Check group boundaries | Each nonempty chapter group has one to six visible entries. Hide unavailable entries using `visible`; empty groups disappear. Do not split a meaningful group to match a screenshot |
| 5 | Render a chapter | Pass it to `PaperIndexRenderer`. Reuse its help, choices, measured groups, footer and pager instead of hand-building them |
| 6 | Route a destination | Add the category to the Settings owner's chapter map or push a stable secondary destination. Render only the current top page and use the same pop callback for header/device Back |
| 7 | Build a secondary destination | `ChoiceSurface` for a pure choice, preserving its static/app-built classification and retained background; `HelpPage` for prose; `PagedSettingsPage` for item lists; `SettingsPage` for mixed blocks/editors. Group inseparable children in a `Column` |
| 8 | Add a reminder only when needed | Supply the shared `NoticeContent` from its state owner and, for chapters, the relevant `noticeGroup`. Do not copy Notice state into the page |
| 9 | Check the result | Compare actual rendered anatomy, baselines, live values, targets, page boundaries and Back behaviour with this guide. Record any remaining mismatch rather than silently changing the style |

This is an excerpt from the real `notificationsCategory` builder in
`app/src/main/java/com/gezimos/katapult/ui/settings/NotificationsCategory.kt`:

```kotlin
SettingsGroup("indicators", stringResource(R.string.paper_group_indicators), listOf(
    SettingsEntry.Switch(
        "indicators",
        stringResource(R.string.notification_indicators),
        stringResource(R.string.notification_indicators_desc),
        indicators,
        onIndicators,
    ),
)),
SettingsGroup("android", stringResource(R.string.paper_group_android_notifications), listOf(
    SettingsEntry.Link(
        "apps",
        stringResource(R.string.app_notifications),
        stringResource(R.string.app_notifications_desc),
        onApps,
    ),
    // The real group also contains notification log and history links.
)),
```

Those groups go inside `SettingsCategory.groups`; `indicators` is the current
boolean, and `onIndicators` / `onApps` are owner-supplied callbacks. The switch
automatically gets the normal On/Off value and full-row target; the Link gets
the external glyph. The category also supplies a live access summary and
`SettingsHelp`. No new padding, toggle drawing or second renderer is needed.
For a real custom-block example, see `LockscreenWidgetsPage`: it uses three
normal entries and one complete Widgets `Column` containing heading, cards
and Add widget, all inside `SettingsPage`.

## Do / don't

| # | Do | Don't |
|---|---|---|
| 1 | Show accurate live values and literal On/Off | Imply a configured feature is active because its preference is On |
| 2 | Use real baseline tracks, leaders for name/value and rules for groups | Align with spaces, period strings, magic offsets or decorative rules |
| 3 | Keep targets at least 48 dp and let help grow | Make a word/check drawing the entire touch target or shrink text to fit |
| 4 | Reuse `PaperSheet` for short static choices and the full-page kit for long/app-built choices, help, editors and management | Add another sheet implementation or generic pill/switch chrome |
| 5 | Use one selected check and Bold label on a choice page | Add radio rings, leaders to empty cells or colour-only selection |
| 6 | Keep groups and measured blocks whole | Force a screenshot's row count or split a heading from its entries |
| 7 | Use bracket cards for configured widget blocks | Put every ordinary setting in a card or add Size/Remove rows inside widget cards |
| 8 | Pass data and callbacks into shared components | Import a consumer, inspect its internals or duplicate its state |
| 9 | Reuse one dependency Notice state and component | Add one reminder per row or carry a parent's Notice onto an unrelated child |
| 10 | Verify fonts, bounds, baselines and affected states in the project VM | Treat a mock, build or one screenshot as proof of app behaviour |

## Inconsistencies seen on current screens

These are current screen differences, not fixes or new design specifications.
Some are explicitly supported by current kit code. Keep them visible as
differences rather than quietly turning them into a second house style.

| # | Screen | Observation and relationship to the style |
|---|---|---|
| 1 | Edge swipes | A full-width rule has no group label, with blank space where the label would be. `EdgeSwipesCategory.kt` passes an empty group title to `GroupHeading`, which still reserves heading space and draws the rule. Unlike the named chapter groups, the rule has no visible section name |
| 2 | Long or app-built choice pages | These keep plain left labels and a light check inset from the chapter value edge, not the short sheet's name/leader/box line. `ChoiceOption` intentionally makes only the selected label Bold and centres its 21 dp / 1.5 dp-stroke check in a 48 dp trailing slot. This remains the implemented page-specific exception, not a reason to add leaders or heavier marks locally |
| 3 | Lock screen message editor | Save and Cancel are separate plain Regular rows below the field, rather than the Bold action/value emphasis used by entries. They currently use `SheetAction`; no action-bar redesign is implied |
| 4 | Lock screen notification apps | Deselect all is a plain Regular header action above Bold app labels and trailing checkboxes. It uses `SheetAction`, so its typography differs from the main entry/action vocabulary |
| 5 | Gestures and Order apps | Values include `Edit Shortcut`, `Most Recent` and `Most Used`, mixing title case with sentence-style labels. `Lock Phone` is also an existing gesture choice. These are legacy copy differences, not the writing model for new labels |
| 6 | Lock screen notification apps | The long header ends in an ellipsis. This follows `PageHeader`'s one-line overflow behaviour but loses the full visible destination name. Purpose labels and semantics still need to identify the page; its accessibility result needs a runtime check |

Notifications, Screensaver and About can have single-page compositions. Page
count follows measured content and visible state, not chapter identity.

## Mood

Aim for a notepad-like feel: personal, spacious and plain, with the owner's
ink on paper. Clear type, simple marks and honest blank space keep the screen
calm on e-ink. Make each page easy to read and leave. Do not make it look busy
to prove that it does something.
