# Deferred features

Things raised but deliberately **not** scheduled yet, with the reason. Check this
file when planning a new phase - several of these become cheap (or unavoidable)
once other work lands.

Source: `sensirion_project_tasks_20260728.md`, triaged 2026-07-28.

---

## Backup archive browsing in the app
**Raised:** "a feature to add at a later time is some interface in the app where
the user can view the backed up files and load the data into the plot view."

A file browser over the archives exported to the phone, with the ability to load
a selected archive into the chart instead of the live session.

**Why deferred:** needs the export format to have settled first, and the chart
currently assumes one live session. Cheap once the storage work (quota +
export) is in and the "session" concept has been generalised.

**Prerequisite:** storage management landing in Phase 3.

---

## Restore an archive back onto the controller
**Raised:** two motivations, both real.

1. Before a factory reset or firmware upgrade: back up, reset, restore.
2. Multi-user recovery: if one person exports and then erases the controller,
   the others lose their chance to export. Restoring puts it back.

**Why deferred:** it is the inverse of an export path that does not exist yet,
and it needs the controller to accept a large inbound transfer - which on the
ESP32 is a different problem entirely (see below). Also raises a trust question
worth answering deliberately: any connected client being able to *write* history
onto the shared device is a bigger authority than anything the protocol grants
today, and should land after the Phase 3 control-claim/auth model.

**Prerequisite:** Phase 3 auth + Phase 4 storage decisions.

---

## ESP32-C6 storage strategy
**Raised:** "the question of what to do about all this on ESP32 with its limited
storage is a big issue, and needs to be addressed at some point, but not right
now."

The Pi has a 32 GB+ card and a 50 MB budget is generous. The C6 has ~4 MB of
flash total, most of it firmware. At 1 Hz continuous a day of CSV is roughly
15 MB - so continuous logging to on-board flash is simply not viable, and the
options are genuinely different in kind:

- treat the phone as the store of record and keep only a small ring buffer
  on-device (enough to survive a disconnection),
- add an SD card to the hardware design,
- or store decimated/binary records rather than CSV text (a fixed 32-byte binary
  record is ~8x smaller than the equivalent CSV line).

Flash wear is a second constraint the Pi does not have: NOR flash endurance is
~100k cycles, so a naive append-per-second pattern needs wear levelling.

**Why deferred:** it is a hardware-design decision as much as a software one,
and the answer depends on whether the product ships with an SD slot.

**Prerequisite:** decide the C6 board design. Do this before Phase 4 firmware.

---

## VPN hardening - moved out of Phase 3, may never be needed
The Wi-Fi socket binding stays in the code (it fixes the no-internet-AP case,
which matters for ESP32 provisioning), but **nothing further is scheduled** and
this is explicitly not a prerequisite for anything.

Reasons, in order of weight:
1. An app subject to a VPN **cannot** route around it - Android forbids it, and
   binding to the Wi-Fi network fails with EPERM regardless of permissions. This
   was measured, not assumed. So the "hardening" was never going to work anyway.
2. In practice the phone has connected to the Pi every time with the VPN up. The
   only VPN trouble ever seen was in the developer adb workflow, which is not a
   situation any user is in.
3. First contact is heading towards Bluetooth provisioning, which has no IP stack
   involved and so cannot be captured by a VPN at all.

If a user ever reports a VPN that blocks LAN traffic, the honest fix is the
message already shipped - name it as the likely cause and point at Bluetooth.

---

## Naming: what to call the product, and the two kinds of "export"
The button labels were made explicit ("Save as CSV" / "Copy all sessions to this
device") because *export* had come to mean two different things: writing out
what is on the plot, and fetching the sensor's whole archive over the network.
Confusing them risks someone believing they hold a backup when they hold a
screenshot's worth of numbers.

Still unsettled: what to call the product itself. "Sensor" is used throughout the
UI because it is what a customer would say, but strictly the SPS30 is the sensor
and the thing holding the data is the controller beside it. Worth deciding once,
properly, and then applying everywhere - user-facing copy, docs and code.

---

## Options-menu ordering
**Raised:** "we need to revisit the order in which options are presented in the
options menu, so that more frequently changed stuff is accessible in front of
stuff that is less likely to be changed."

The sheet has grown section by section in the order features landed, not in the
order a user reaches for them. Worth one pass ranking sections by expected
frequency of use. Explicitly deferred - no need to do it with any particular
feature.

---

## The info row with only the battery and gear icons
After moving the window/points text into the plot, that row carries just the
power pill and the options gear. Either find other things worth putting beside
them, or rehome both and delete the row to give the height back to the chart.
Left as-is deliberately: it works, and it was not worth blocking on.

---

## The watch update-rate wording, again
**Raised:** "I in principle know how this works better than a customer would, and
I'm still not entirely sure exactly what happens based on this description."

The options text has now been rewritten twice and still does not land. The
underlying behaviour is genuinely three rules at once - a minimum gap the user
picks, a change threshold, and a heartbeat - and prose describing all three
reads as hedging. Worth solving as a *design* problem rather than a copy one:
either simplify the behaviour until one sentence describes it, or show it (a
worked example, or a line reading "last sent 2 min ago") instead of explaining
it. Note the earlier "refresh every 5 minutes" phrasing was removed because it
implied a floor the user's own setting could not override, which was itself
misleading.

---

## In-app information page about particulate matter
**Raised:** a question-mark entry point, perhaps on the row with the battery and
gear icons, opening an information page **inside the app** - not a browser.

Content should treat the reader as capable and not dumb anything down: what
these particles are, what produces them, what harm they do and by what
mechanism, and why this sensor can be trusted. Properly cited, with references
to journals and links where appropriate. Aimed at someone without a science
background who is willing to engage, and equally useful to a technical reader
who simply does not know this field.

Presentation matters here: scrollable, longer than a screen, with graphics, and
a premium look - closer to a well-made article than a help dialog. A modal sheet
in the style of the options menu is the obvious mechanism.

**Why deferred:** it is a content project as much as a code one, and the
citations need doing properly rather than quickly.

---

## Notes for whoever picks these up

The storage quota, auto-export at 85%, manual full export and protected erase
are **not** in this file - they are scheduled as part of Phase 3, because the
multi-user coordination the user described ("if one person decides to export the
data and then completely erase the controller's data") only makes sense
alongside the control-claim model.

## Taking a clockless session back onto the controller

`put_file` refuses a session whose name is `SPS30-Rxxxxxxxx-xxxxxxxx.csv` - one
recorded before the board had a clock - and says so rather than failing quietly.

Supporting it is possible: the name carries the run and the position in it, which
is exactly the identity a relative page needs, so the import would rebuild
relative pages instead of absolute ones.

**The format half of this is now done.** `wire.CSV_COLUMNS_RELATIVE` is the
variant that was missing - `wire.CSV_COLUMNS` with `rel_boot`/`rel_ms` appended -
and `wire.csv_columns(name)` picks it from the session name, so a client sending
such a session back now has somewhere to put the board's clock.
What is left is the store side: `wire_parse_row` still reads the classic thirteen
by position and `put_file` still refuses a `SPS30-Rxxxxxxxx-xxxxxxxx.csv` name
outright, because rebuilding relative pages from rows is real work in
`store_merge.c` and none of it was needed to stop the data being lost on the way
to a file and back.

Refusing is the safe direction. Filing such a session under a guessed absolute
identity would put readings into a session they do not belong to, and the whole
point of relative time is that a guess dressed as a measurement is the one
outcome to avoid.

## Folding a clockless session into the stored-sessions overview

`StoredSessions` reads a folder of session CSVs off the tablet and folds them
into the long-term overview plot.
It goes through `SessionCsv.read`, which averages readings into fixed windows and
keys each point on an **absolute bucket ordinal** - `floor(epoch / bucketSeconds)`
- so that re-reading the same folder adds nothing rather than a second copy.

A clockless session has no epoch to take that ordinal from.
Every row's `timestamp` is empty, `parseEpoch` refuses it, and the file is
counted as empty.
That is now the last place a clockless session is dropped: the archive round trip
and the shared CSV both carry `rel_boot`/`rel_ms` and read back complete.

It is left alone deliberately, because doing it needs a decision this change did
not have to make.
Bucketing such a session at epoch zero would put it in 1970, and - worse - two
unplaceable runs would land in the *same* buckets and de-duplicate each other
away, which is the loss this whole area exists to prevent.
Doing it properly means the bucket key growing a run marker, so relative points
share a key space with each other and with nothing else, and it means this path
consulting a `TimeBasis` it does not currently have - the folder can be read with
no controller connected and therefore no equivalences at hand.

Nothing is lost by waiting: the readings are on the board, and the file the phone
wrote now holds what places them, so whenever this is built the sessions already
on disk will fold in correctly.
