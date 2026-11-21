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
`IllegalArgumentException`. Use numeric-row deletion, not a selection assumed
to implement Android's normal SQL semantics.

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
Recover each pending write before a fresh diff; reserve only its identities,
never stop unrelated sync. Persist provider-confirmed success separately from
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
exactly-once guarantee after such destruction. Schema 3 is fresh-install-only,
as were the earlier unpublished bridge schemas; unsupported upgrades preserve
the old database and stop rather than reset it.

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
