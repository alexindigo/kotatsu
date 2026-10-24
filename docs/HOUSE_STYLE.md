# Paper index

Kotatsu's house style for its own screens, chosen by the user during the
Settings revamp on 2026-10-19. Navigation reads like a table of contents;
categories are chapters; groups are subheadings; sheets are pages in the same
book. Typography and state carry the interface, rather than a collection of
Android control shapes.

The shared Android kit lives in `app/src/main/java/com/gezimos/katapult/ui/settings/`.
Settings root and all chapters use its data model, renderer, Notice and page sheets;
secondary management sheets are being migrated separately.
Apply this language to future Kotatsu-owned screens; Android's system settings
remain outside Kotatsu's visual ownership.

## Principles

Use black ink on white paper: `#000` and `#FFF`. Do not encode state with greys,
opacity, gradients or shadows. Ordinary glyph antialiasing is rasterization,
not an additional semantic colour. Use no animation, shimmer or automatic
scrolling. Prefer stable positions, explicit actions and few screen refreshes.
Separate current configuration from runtime availability: a widget can be
configured **On** while its required service is **Off**.

Leave honest blank space. Rules separate real sections, leaders connect real
names and values, and boxes express real checkbox states. Do not add cards,
chapter numbers or icons merely to fill or decorate a page.

## Tokens

Use the bundled [Lato Regular and Bold](fonts/), weights 400 and 700. Use `sp`
for type and `dp` for geometry in Android. The mockup uses an approximately
4:3 pixel conversion for a 480×800 reference frame; its pixels are not a
measured Android density or a portable sizing API.

| # | Token | Logical reference | Mockup reference |
|---|---|---|---|
| 1 | Chapter heading | 26 sp Bold | 34.667 px, 42 px line |
| 2 | Root index name / sheet title | 21 sp Bold | 28 px, 34 px line |
| 3 | Chapter entry name / value | 18 sp Bold | 24 px, 29 px line |
| 4 | Help / summary / group | 14 sp; Regular help/summary, Bold group | 18.667 px, 22–26 px line |
| 5 | Index row / minimum target | ≥48 dp | 64 px row; 64×64 px independent target |
| 6 | Chapter row | ≥67.5 dp; permissions ≥63 dp; grow for text | ≥90 px; permissions ≥84 px |
| 7 | Visibility row / control cells | 57 dp row; ≥48 dp cells | 76 px row; separate 64×64 px cells |
| 8 | Horizontal content inset | About 16 dp | 21 px |
| 9 | Leader | 1.5 dp dotted ink; flexible length | 2 px stroke, ≥12 px visible length; 10 px name gap |
| 10 | Group rule / group spacing | 2 dp rule; 9 dp gap | 2.667 px rule; 12 px between groups |
| 11 | Entry spacing | 9 dp column gap and vertical padding | 12 px; 2 px between text tracks |
| 12 | Sheet boundary | Heavy full-width ink rule | 3 px top rule |
| 13 | Checkbox | About 20 dp square; 2.5 dp enabled / 1 dp disabled stroke | 27 px; 3.333 / 1.333 px stroke |
| 14 | Pager | 48 dp targets around smaller round arrow drawings | 54 px circles in 64 px targets; 8 px dots |

Targets are never smaller than 48 dp in either dimension. A value word is the
drawing inside a full-row control, not a separate small target. Spacing may
grow for localized or enlarged text; do not shrink type or targets to preserve
a screenshot's row count. Align names, leaders and values structurally on a
shared baseline, not with hand-tuned offsets or strings of period characters.

## Components

```text
< Appearance                                      ?
Screen --------------------------------------------
Theme mode .................................. Light
Light, dark or follow system
Hide status bar ............................... Off
Hide the system status bar
                      o *                      (<)
```

The dots in these sketches represent a drawn leader, not literal text.

| # | Component | Contract |
|---|---|---|
| 1 | Index line | Name left, flexible dotted leader, concise value/summary right. Keep the value readable and the leader clear of both texts. Root entries open their destination in one tap |
| 2 | Chapter entry | Same line, with quiet full-width help underneath. Pure choice values open a choice sheet; boolean rows toggle and display literal **On** / **Off**. Management actions say **Open**; existing external handoffs use **↗**. Static metadata stays noninteractive |
| 3 | Chapter heading / group | Back and accessible **?** header action where help exists; help replaces a Read Me list row. Named groups use a bold label and rule. At most six settings per chapter page; split at whole-group boundaries and avoid a one-row final page. A genuine single-setting category is valid |
| 4 | Pager | Bottom dots with the current page filled and round previous/next arrows. Omit unavailable directions and the pager on single-page categories. Swipe remains available, but is not the only route; category Back returns to Settings |
| 5 | Notice | One reusable bottom component for temporary service/dependency reminders on every current and future screen; see the Notice contract below |
| 6 | Sheet as page | Full-width white page over unchanged context, separated by the heavy rule. Same typography and entry grammar; no dimming scrim or floating card. Back dismisses. Covering underlying rows is expected occlusion, not permission to clip the sheet's own text |
| 7 | Choice list | Short full-row options; one check mark beside the selected option, with its name Bold. No radio rings or leaders pointing to empty cells. Selection applies the choice; Back leaves it unchanged |
| 8 | Hide / Notify matrix | **Hide** left, app name/leader centre, **Notify** right. Checked = black square with white check; enabled unchecked = heavy empty box; disabled = thin empty box; not applicable = reserved blank cell. Both columns have independent targets; blank/disabled Notify must not activate Hide |

```text
Hide     App                                  Notify
[x]      Alarm ............................... [ ]  thin: disabled
[x]      Calculator .......................... [x]
[ ]      Camera ..............................      not applicable
```

Draw the check mark as a shared vector symbol where the bundled font lacks
the glyph; do not depend on an arbitrary system fallback font. Likewise,
render an external-link symbol once: a hidden zero-font copy plus a generated
duplicate changes line metrics and breaks the help spacing.

The accepted root uses device Back, a compact noninteractive title and ten
48 dp index rows. Its 64 px reference Notice lane fits the 480×800 example
without moving the index. This exact capacity is a reference composition,
not a requirement to squeeze ten entries onto every device or font scale.

### Notice

Use **one reusable Notice component**, not separate global and contextual
designs. Its anatomy is a top ink rule, a plain message, an optional text
action and a **×** dismiss control. Use Lato Regular 14 sp for the message,
Bold for the action, black on white, with no box, warning icon or animation.
The reference has a 2.667 px rule, 21 px side insets, 8 px gaps and a minimum
64 px lane; the action is 86×64 px and dismiss is 64×64 px.

The future app component takes message identity/text, optional action
label/callback and a dismissal callback as inputs; it does not know which
screen consumes it or own service navigation. A shared message-state owner
supplies visibility and dismissal to every screen. Do not create screen-local
copies of that state or per-screen Notice implementations.

```text
Chapter rows / root index
                   blank space
---------------------------------------------------
Kotatsu Action Service is off             Turn on  x
                       o *                      (<)
```

**Placement:** always at the bottom of the owning screen, immediately above
the pager when one exists; otherwise in the bottom lane as on Settings root.
Use remaining blank space before adding a page. Never overlay settings,
shrink type/targets or split a group just to make a notice fit. Let localized
or enlarged text wrap and the lane grow when necessary. With a Notice, the
reference pager uses its targets' intrinsic 64 px lane without the ordinary
84 px lane's spare padding; its 64 px targets and 54 px drawings are unchanged.
This keeps Appearance's five intact rows on one page. The root title stays
only **Settings**; removing its Notice leaves the title and ten rows in place.
A modal sheet covers the underlying screen's Notice along with its content;
do not hoist that Notice above the sheet or repeat it under sheet rows.

**Dismissal proposal for user confirmation (2026-10-19):** retain one dismissal
state per semantic message/dependency, shared across all screens and reopening.
Root, both Lock screen pages, Appearance's clock prerequisite and Screensaver's
Power-button prerequisite all refer to the same Action Service notice identity,
even if contextual wording differs. Dismissing anywhere hides it everywhere
during the same service-OFF period. Observing OFF again or navigating does not
reset it. Service ON removes it and clears the old dismissal; a later ON → OFF
transition shows it again. **Turn on** opens the existing setup flow; merely
opening or cancelling setup neither enables the service nor dismisses the notice.
Other messages have independent dismissal state. This is a design proposal,
not implemented state management or a claim of final user confirmation.

**Wording:** state the condition briefly and truthfully, with an explicit
action when one is available. The current shared copy is **Kotatsu Action
Service is off** / **Turn on**. The optional contextual alternative
**Lock-screen features need Kotatsu Action Service** uses the same component,
placement and identity, with wrapping rather than smaller type if needed.
Keep configured **On** values separate from runtime availability. Localize
complete messages and purpose-specific labels; do not key dismissal by the
translated text or screen name.

**Accessibility:** action and dismiss are separate controls, each ≥48×48 dp,
with visible black focus and logical message → action → dismiss reading order.
Name the region **Action Service reminder** and × **Dismiss Action Service
reminder** (localized). Keep the rule decorative. Announce a newly changed
condition politely once, not on every page visit; avoid stealing focus. After
dismissal, retain a sensible focus location. These are Android implementation
requirements; static mockup buttons do not implement setup or shared dismissal.

**Use for** temporary, dismissible availability/dependency messages and setup
reminders, once per relevant screen rather than once per affected setting.
Every future notice of this kind uses this component and shared state contract.
**Do not use for** ordinary row help, persistent configuration summaries,
permission-status/management rows, missing-app values, mandatory confirmations,
blocking errors or transient success feedback. Those have different jobs; a
dismissible reminder must not replace a required decision or hide live state.

## Writing and accessibility

Use sentence-style setting names, active verbs and established destination
names. Write **Lock screen** consistently; keep deliberate names such as
**All Apps** and **Home Screen**. Help explains an effect or available options,
never a second copy of the selected value. Use positive labels where possible:
**Music widget On**, not a double-negative disable flag. Localize complete
phrases, numbers and plurals; never assemble grammar by joining English words.

Expose the row's actual role, label, value/state and action to accessibility.
Decorative leaders/rules are not spoken. Announce choice selection and checkbox
checked/disabled states programmatically; thin strokes and Bold alone are
insufficient. Name **?**, **×**, pager arrows and external links by purpose.
Provide visible black focus, logical focus order and non-gesture alternatives.
Do not make a permission-status link a pretend grant/revoke switch. Preserve
legible text and separate targets at larger font scales and in all locales;
move whole groups to another page rather than truncating explanations.

| # | Do | Don't |
|---|---|---|
| 1 | Show accurate live values and explicit On/Off words | Imply a configured feature is active when its prerequisite is unavailable |
| 2 | Use leaders for name/value relationships and rules for groups | Add dot leaders, borders or icons as filler |
| 3 | Keep targets ≥48 dp and let help wrap | Treat a small word/check drawing as the entire touch target |
| 4 | Reuse the same page language for sheets and management screens | Reintroduce generic pill/switch chrome on secondary pages |
| 5 | Verify fonts, bounds, baselines and all affected states in a VM | Treat a source-only mockup, one screenshot or a build as proof of app behaviour |

## Reference status

The design reference lives outside the repository at
`~/Documents/kotatsu/notes/settings-revamp-20261018/mockups/` (`index.html`,
`current.css`, semantic strings/history in `notes.md`). The user selected
Paper index and adopted it as the house style. Its HTML frames have been
rendered and reviewed; actual Settings interactions, accessibility behaviour,
locale fit and physical e-ink refresh remain implementation/device checks.
