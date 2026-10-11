# Offline icon catalogue

The catalogue bundles the original SVGs from saved selection revision **3730**.
It contains **2,174 named choices**, stored in **2,165 unique SVG payloads**;
names sharing identical artwork remain separate searchable choices.

| # | Collection | Included entries | Upstream |
|---|---|---:|---|
| 1 | Simple Icons | 843 | Simple Icons 16.34.0 |
| 2 | Tabler | 1,034 | Tabler 3.49.0, outline |
| 3 | Material Icons | 297 | Classic Material Design Icons, Outlined |

## Selection and rendering

Long-press an app and choose **Change Icon**. Each collection uses the same
bordered tiles, full-corpus search and 20-item pages. Searching matches names,
labels, aliases, tags, categories and stable IDs before pagination. **Choose
PNG / SVG** retains the existing image-import flow. **Reset to Default** removes
only the selected app/shortcut's manual override.

Resolution is manual choice first, then an exact included Simple Icons brand
match, then the installed app's original icon. Automatic choices are not saved
as overrides. Published shortcuts keep their own artwork unless manually
overridden; package fallback applies only when no shortcut drawable exists.

New overrides use `catalogue:<stable-id>`. Existing `file:` imports and `res:`
bundled choices remain valid. App and shortcut keys retain their separate
identities. The shared AndroidSVG 1.4 renderer fits the source viewBox into the
same centred 60% transparent viewport used by bundled tiles, preserving strokes,
fills, primitives, transformations and unpainted geometry. PNG import retains
its existing bitmap path.

## Ownership

| # | Owner | Responsibility |
|---|---|---|
| 1 | `IconCatalogue` | Immutable IDs, metadata, normalized search and exact mappings |
| 2 | `CatalogueIconRenderer` / `SvgRasterizer` | Verified payloads, bounded size-aware rendering cache and shared SVG import |
| 3 | `IconUtility` | Manual-first app/shortcut resolution and consumer cache invalidation |
| 4 | `PrefsManager` / `MainViewModel` | Validate rendering, confirm storage and refresh consumers |
| 5 | `IconCollectionPicker` | Shared tabs/search/pages/tiles; injected rendering and selection/import callbacks |
| 6 | `AppContextMenu` | Parent wiring, reset and existing import ownership |

## Mapping provenance

The current catalogue has **905 package mappings and 2,528 component mappings**.
They derive from Arcticons `newicons/appfilter.xml` at commit
`90a4343510f35e33421f0a41df5debc29c3604d1`. The compiler requires an unambiguous
product-comment identity and drawing identity to agree with an included Simple
Icons name/title/alias. It uses no display-name substring guessing.

When a package has conflicting included brands, all its components are also
quarantined. In this input that includes Apple Music and the Google app; neither
receives a contradictory alternate-activity match. The original app icon is
the fallback. `provenance/mappings.json` records source identities and conflicts.
These mappings describe offline reference coverage, not a claim that every
installed app has a curated brand.

## Reproduction and checks

`tools/compile_icon_catalogue.py` consumes the frozen `selection.json`,
`catalogue.json`, `draft.json`, `snapshot.json`, source notices, pinned reference
files and selected SVG cache. It validates the complete included/excluded
partition, exact input hashes, original Git blobs, sizes and final counts.
It refuses differing existing generated output. A future selection is an
explicit catalogue-version change; existing saved IDs must remain resolvable.

All builds, tests and asset processing run inside the project-owned Kotatsu VM,
`arch-niri-kotatsu-android12-phase-a` on `ark.home`, using the `kotatsu-vm`
access workflow. The current guest checkout is
`/srv/kotatsu/source/kotatsu-icons-20261010-01`. Inside that guest:

```bash
python -B tools/verify_icon_catalogue.py --root "$PWD" --apk app/build/outputs/apk/release/kotatsu-v1.0-release.apk
```

The verifier checks every named entry and actual packaged asset byte, included
mapping targets, ambiguous-package exclusion, payload hashes and notices.
`IconSelectionTest` checks resolution precedence. Native instrumentation
checks the complete corpus at 96/192 pixels, synthetic stroke/primitive/hole
geometry, legacy/file overrides and shortcut isolation. Picker instrumentation
uses separate choose/verify invocations around a real process stop, with a
restoration journal for temporary choices. Public release UI proof is separate
from debug instrumentation.

## Attribution

Original source metadata, pins and hashes live in
`app/src/main/assets/icons/provenance/`. Licence texts, disclaimers and attribution
are packaged in `app/src/main/assets/icons/notices/`: Simple Icons and its per-logo
disclaimer; Tabler's MIT licence; Material's Apache-2.0 licence; AndroidSVG's
Apache-2.0 licence including Cave Rock Software's copyright; and Arcticons'
GPL-3.0 licence for derived identity metadata. The compiler does not redraw or
substitute the selected artwork.
