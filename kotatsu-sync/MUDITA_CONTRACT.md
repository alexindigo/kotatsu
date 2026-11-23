# Mudita Calendar 1.2.1 provider

Version-specific, undocumented interface. Source: inspected Calendar APK SHA256
`7c79dceb330fe3e6f854e2e337c7304cbf18d3eafbe896aa3fa9d77b8d01e59f`.
Decompiled evidence is outside the repository in
`~/TemporaryFiles/kotatsu/mudita-sync-research/rea/sources/Calendar-*`.

## Rows and routes

Authority `com.mudita.calendar`, no permission gate. `/events` query always
returns the following fixed columns, ignoring projection. A numeric-row query
throws `IllegalArgumentException`. Insert uses `/events`; update requires
`/events/#`; delete accepts both routes. Unknown routes throw
`IllegalArgumentException`. A delete on `/events` ignores the selection; the
collection route with null selectionArgs deletes all rows. Always use the
numeric route for a single row. The emulator fixture models the collection wipe.

| # | Column | Type |
|---|---|---|
| 1 | id | Long |
| 2 | title | String |
| 3 | desc | String |
| 4 | startDateTime | Long, epoch milliseconds |
| 5 | endDateTime | Long, epoch milliseconds |
| 6 | isAllDayEvent | Integer, 0/1 |
| 7 | reminder | JSON string, serialized H3.f |
| 8 | isPreReminderSet | Integer, 0/1 |

`v3.a.a` consumes a complete ContentValues model, including id on insert and
update. Update uses that model's id, not a compare-and-set selection. It can
return zero on conversion/write failure; insert can return null. Read back
after writes. The provider never calls `notifyChange`; the Calendar app also
writes Room directly. Periodic polling is required.

## Times

`v3.a.a` converts each epoch to LocalDateTime through `ZoneId.systemDefault`,
then passes its date and time components into H3.b. The cursor serializes the
model's LocalDateTimes through `E2.f.V`. There is no UTC or all-day-specific
normalization in this converter: isAllDayEvent is a separate Boolean. A bridge
must translate CalendarContract's UTC all-day dates to local dates rather than
letting the system-zone conversion shift the displayed date. Timed events keep
their instants. The exposed interface carries no per-event zone or recurrence.

## Reminders are deliberately not bridged

Insert and update both use `v3.a.a`, which ignores an incoming `reminder`
entirely. It constructs H3.f(false, emptyList), reads isPreReminderSet, then
rebuilds the reminder object through `A0.d.B(event)`. H3.f contains a Boolean
and a list of integer alarm identifiers, not a portable list of offsets.
The exact reflected JSON shape is `{"d":true,"e":[12345]}` (or
`{"d":false,"e":[]}`). DEX field inspection confirms names `d` and `e`;
JADX's `f1888d`/`f1889e` are decompiler aliases, not JSON keys.
`A0.d.B` creates no identifiers for an all-day/past event, one for a future
timed event, and possibly a second when isPreReminderSet requests a five-minute
pre-reminder. These are generated alarm ids, not server reminder values.

Per the approved no-reminders addendum, neither field participates in bridge
fingerprints. New Mudita rows set isPreReminderSet=false; updates retain the
currently read flag. No reminder JSON is written. Per addendum 3, a newly
created recurrence exception inherits the base event's CalendarContract
reminders unchanged (platform cloning for RRULE, an atomic provider batch for
RDATE). The bridge never changes reminders on existing rows, and phone uploads
get none. A full-model Mudita
update still rebuilds its internal alarm identifiers; it cannot preserve them
byte-for-byte. The flag is the only reminder choice the converter accepts.

## Standard-calendar all-day transitions

Per approved addendum 2, phone edits preserve a timed event's zone unless its
all-day flag changes. Timed -> all-day writes UTC midnight dates and zone UTC;
all-day -> timed uses the current device zone. All-day -> all-day stays UTC.
This also applies to individual recurrence exceptions. Android requires this
normalization; an all-day event has no zone of its own.

## Standard recurrence identity

The standard adapter preserves the series event id and ORIGINAL_INSTANCE_TIME
as each occurrence's identity, including moved exceptions. ORIGINAL_SYNC_ID
must refer to the original server id, never to the exception id. The bridge
never invents or writes a server `_sync_id`. Connected fixtures seed server
events as a sync adapter, like DAVx5 does, rather than as unsynced local series.
RRULE exceptions use CalendarProvider's CONTENT_EXCEPTION_URI so the platform
clones unsupported fields, attendees and reminders without bridge-side reminder
writes. That API requires duration, not dtend, for an exception request.

## Bridge recovery and description ownership

Every provider mutation has an item-owned durable before/after intent. Identity
reservations, mapping replacement/removal and intent acknowledgement are atomic.
Observe and assess the cohort before replaying a pending write or applying a new
diff. Both paths need the same journal authorization. Persist provider-confirmed success separately from
readback equality. A later edit must remain different from the intended baseline.
Unconfirmed writes with drift stay unresolved instead of enabling an overwrite.
Not-yet-applied retirement rechecks current selection, recurrence identity and
horizon membership through the same scope predicate used by the diff.

Standard uploads carry a private CUSTOM_APP_URI marker (no CUSTOM_APP_PACKAGE
or server sync id); recover it even on tombstoned rows. New Mudita imports carry
a UUID receipt encoded in invisible format characters in the description
transport. The adapter extracts this as metadata, preserves it on updates and
never sends it to CalendarContract or includes it in content fingerprints.
Only a provider-returned ID or a unique exact provider-stored receipt establishes
ownership. A matching independent event without that receipt is never adopted.
Ambiguous or missing evidence reserves the affected item without blindly
repeating insertion; rows carrying a pending import's receipt are reserved even
when that receipt is duplicated or conflicts with another receipt. Historical
receipts with no pending owner do not quarantine events: the agreed first-run
matching and new-phone-event upload rules still apply, including after reinstall.

`BridgeEngine.resolvePending` and the serialized `SyncRuntime.resolve` accept an
explicit external result: `Applied(providerId)` for an insertion, `Applied()`
for a mutation, or an operator assertion `NotApplied`. This is not automatic
inference from absence or content equality. Settings UI remains part 2. Removing
or copying private metadata externally destroys evidence; this is not an
exactly-once guarantee after such destruction. Schema 4 preserves schema 3
pairs, pending writes and reservations. No provider baseline is reconstructed
from development history or raw IDs alone; the first trusted non-empty read
establishes it. Unsupported upgrades preserve the database and stop.

Pass results distinguish retryable provider failures from unresolved evidence.
Runtime preserves status and propagates failures to job completion for retry;
intentional missing-permission/incompatible-schema stops do not request retry.

Mapping provenance records whether a generated location first line was added.
Ordinary description lines, including ones beginning with Location:, are not
stripped from unprefixed rows or phone uploads. An invisible format-character
suffix identifies generated lines, and a separate invisible body-boundary marker
survives replacement of the whole generated line. Deleting a generated line
cannot consume the ordinary body line it exposes. Location line separators are
normalized to spaces for display only; the server location stays unchanged.
The body marker is removed, not used as a slicing offset: ordinary text inserted
before or after it participates in fingerprints and reaches the standard side.

## Calendar continuity and review

The bridge measures loss against its mapped copies, not every provider row.
Three unexplained missing copies, a read-empty store with at least two mapped
copies, or observed identity loss can hold propagation. A removed standard
series counts once when its base row is gone and the calendar is still present
and syncing. Explicit unselection and ordinary window retirement are not
inferred deletions. A held snapshot retains its mapping and before-images and
does not age into deletion permission. Approval is scoped to that snapshot.

Mudita exposes no real provider generation or deletion tombstone. Row count,
maximum ID and the first-pass marker are observations, not proof of a reset.
An undetectable wipe of a one-item store still looks like one ordinary delete;
a replacement that presents many of the same IDs and no other distinguishing
evidence can also be undetectable. A fresh read reduces but does not eliminate
the race with an independent writer: providers do not share a transaction.
Standard continuity also records the calendar's account and available server
`_sync_id` values. Kotatsu never writes those server IDs.

## Mudita Contacts 1.3.1

APK SHA256 `4e96ce48f6404aa353c884e0b9be5dce55516c8674224c0d2792762263f57aa9`.
REA/JADX evidence remains outside the repo under
`~/TemporaryFiles/kotatsu/mudita-sync-research/rea/Contacts-jadx-class-*`
and `~/TemporaryFiles/kotatsu/kotatsu-sync-part2a/contacts-*.json`.

| # | Path | Contract |
|---|---|---|
| 1 | `ContactSaveService.createRawContact` | Inserts RawContacts with the chosen account name/type/data set, then Data rows with a raw-contact backreference in one ContactsContract batch. |
| 2 | `AccountWithDataSet.getLocalAccount` | Uses `RawContacts.getLocalAccountName(context)` and `getLocalAccountType(context)`. These are device framework values, not Mudita-specific string literals. Null-account contacts use null name/type/data set. The bridge uses the same framework helpers, never treats an unknown account as local. |
| 3 | `DeviceLocalAccountTypeFactory.Default.classifyAccount` | Device-local means null type or the framework's local type. SIM-backed accounts come from `SimContacts.getSimAccounts`; they are separate, read-only sources, not phone contacts to delete. |
| 4 | `SimContactDaoImpl.loadSimContacts` | Reads `SimPhonebookContract.SimRecords.getContentUri(subscriptionId, EF_ADN)` (not a ContactsContract raw-contact write). |
| 5 | `SimContact.appendCreateContactOperations` | Import inserts a new raw contact into the user-chosen account and name/phone/email data rows. It writes no SIM record id or imported-SIM flag into ContactsContract. An import into the phone account is therefore an ordinary local candidate; an import directly into a sync account is already there. The bridge never reads or writes ICC/SimPhonebook data. |

Ongoing and explicit one-time moves use Contacts go to. Automatic selection
prefers Personal. Automatic batches of three or more wait for confirmation;
no local source is deleted outside its per-contact atomic move. Both paths operate on current non-deleted phone raw contacts,
including previously imported SIM contacts; neither moves a SIM-backed mirror or
an already-synced raw contact. No phone/SIM permission is requested.

Destination choices come from ContactsContract Settings with `should_sync=1`
and a registered uploading contacts sync adapter, including empty address books.
DAVx5 creates one account per address book and installs its provider Settings;
its parent login account is not a destination. The debug authenticator and no-op
sync adapter stand in for those address-book accounts only in emulator tests.

On Android 11+, SyncManager filters `getSyncAdapterTypes()` by the caller's
package visibility (Android 12 `SyncManager.getSyncAdapterTypes`,
`PackageManagerInternal.filterAppAccess`). The manifest therefore queries the
`android.content.SyncAdapter` intent, exposing matching adapter packages for both
contacts and calendar authorities. It retains the Mudita Calendar provider query;
no package-name allowlist or QUERY_ALL_PACKAGES permission is required.
The external `fake-mudita-calendar` androidTest APK verifies this from a separate UID,
with shell-owned setup that does not grant the target implicit visibility.

Production calendar discovery reads `Calendars.ACCOUNT_TYPE` and `SYNC_EVENTS`
from CalendarContract; it never enumerates AccountManager accounts, resolves an
authenticator, or loads adapter metadata. Provider account fields remain available
under calendar permissions even before the adapter visibility fix. Contacts reads
its account fields from ContactsContract Settings and additionally needs the
adapter registry. No separate AccountAuthenticator intent query or account-access
permission is needed by these production paths. The connected external-package
case checks both adapter registrations and authenticator/service metadata, a
CalendarContract read/edit/readback, and a real phone-to-external-account move.

Each move is one non-yielding ContactsProvider batch: assert the source version,
account and non-deleted state and target Settings, insert the target raw contact,
copy all MIME rows (`data1`-`data15`, resource package and primary flags), explicitly
aggregate it with the source, then delete the source and restore aggregate-wide
super-primary selections. These defaults are deferred for every MIME type until
source deletion: duplicating them during aggregation makes Android clear both
copies. Per-raw primary flags are copied directly; defaults owned by other raw
contacts are not replaced. Target rows use ordinary
application writes: ContactsProvider sets dirty state for DAVx5, and no server
source id/sync metadata is invented. A failed assertion or later batch operation
rolls back. A lost result after commit is safe to retry: the local source no longer
exists. Starred, ringtone and send-to-voicemail choices are retained.

Two account/provider-owned references must be translated, not blindly copied:
photo `data14` is a file id, so each row's full image is read from
`DisplayPhoto.CONTENT_URI/<PHOTO_FILE_ID>`, not the raw contact's primary-photo
URI. The bytes are supplied to the new photo row and Android regenerates its
file/thumbnail. Connected tests compare two distinct full-size images and their
primary/super-primary flags, phone/email defaults, and a joined raw contact's
independent default. Group membership
`data1` is an account-owned group id, so the group title/notes are matched or
created in the destination within the same batch (source group version checked).
New groups retain the source's visibility flag; an existing target group's own
visibility choice is kept. This prevents a newly copied group from hiding its
contacts through Android's default invisible-group flag.
Neither the SIM nor another account's groups are deleted.

Android's explicit aggregation exception aggregates immediately before deleting
the source. Connected cases verify a stable numeric aggregate id, one contact,
and the same number/name returned through PhoneLookup, including nameless and
already-joined contacts. Account-dependent lookup keys are regenerated: an old
lookup URI can stop resolving. The bridge does not preserve a hidden local raw
contact or invent a DAVx5 source id to fake that key. Inspected Mudita Phone
1.3.1 (`d4.d`) and Messages 1.3.1 (`io.sentry.util.l`) resolve numbers through
`PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI`; they are not keyed by the deleted
phone raw-contact id. Evidence: the sibling `dial-cp2` and
`messages-contactutil` JSON/text files. This is a platform account-transfer detail,
not a claim of a live Phone/Messages UI or server round-trip test.

The one-time action persists its destination and the snapshot of local
raw-contact ids before the first batch. Failed/interrupted items retain that
intent across database reopen; an automatic pass resumes that same destination
and reserves those ids from the configured automatic move. Contacts created
after that snapshot use the current target. Completed batches remove their own local candidates, so a crash
before clearing the one-time intent cannot duplicate a contact. A missing target
or permission retains the pending action rather than rerouting it.

## Typed pass status

`BridgeStore.syncStatus` stores `SyncStatus(lastPassAt, changes, stopReasons, availabilityStop)` as a
single state row; no calendar-bridge schema/version bump is needed. The timestamp
is the completion time of the latest bridge attempt or one-time action, not a
claim that DAVx5 reached the server. `changes` counts provider-confirmed logical
event mutations plus moved raw contacts in that attempt, not mapping saves,
copied Data rows, or recovery acknowledgements. A no-op writes a fresh timestamp
and zero changes. Stops, unresolved evidence and exceptions store stable enum codes;
transient provider failures additionally request job retry after saving status.
Scheduling and settings-operation failures retain the entire latest real pass
result and store a separate typed availability stop. Only the UI maps reasons
to complete localized phrases. Diagnostic text
stays in the engine's diagnostic status, never in the display contract.
Unconfigured automatic contacts movement is inactive; a configured but unavailable
destination is a stop. Calendar and contacts permission-only attempts preserve
the last real pass instead of overwriting it. While either access is missing,
settings shows that real result, or hides Last sync if none exists; the access
rows alone explain the missing grants. Independent provider stops are combined
as a deduplicated typed set, not joined diagnostic text. Legacy free-text status
is decoded once at the read boundary into known codes (unknown diagnostics mean
retry); permission-only legacy timestamps are not treated as real passes.
Last sync alone reflows a value that does not fit its normal index track below
its single-line label, with wrapping and no ellipsis at normal or enlarged text.
