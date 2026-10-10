# On-device storage format

How readings should be stored on a controller with little flash (the ESP32),
and why. Every figure here was measured on 120,000 consecutive real readings
from `SPS30-20260724_213851.csv`, not estimated.

## How much precision is real

From the SPS30 datasheet (v2.0, section 1.1).
**These are device-to-device precision figures**: they bound how far two units
of this sensor may disagree with each other on the same air.
They are *not* accuracy figures, and they say nothing about how close either
unit is to the true concentration.
The datasheet publishes no absolute accuracy specification at all, so none is
quoted here.

| Quantity | Device-to-device precision, 0-100 range |
|---|---|
| PM1.0, PM2.5 mass | **±(5 µg/m³ + 5% m.v.)** |
| PM4.0, PM10 mass | **±25 µg/m³** |
| NC0.5, NC1.0, NC2.5 | ±100 #/cm³ |
| NC4.0, NC10 | ±250 #/cm³ |

Typical indoor readings here are 1-3 µg/m³.
In absolute terms the whole value sits inside the spread those figures allow -
so storing 16 significant digits, as the CSV does today, is storing about 15
digits of noise.

Unit-to-unit spread is not the whole story though: a plot cares about how finely
*one* unit resolves *change*, which is far finer than that spread and is a
separate question from agreement with truth.
Measured, consecutive readings move by:

| | median |Δ| | p95 |Δ| |
|---|---|---|
| PM mass fields | 0.019 µg/m³ | 0.057 |
| NC fields | 0.14 #/cm³ | 0.43 |
| Typical size | 0.0011 µm | 0.0034 |

There is no hardware quantum to preserve - the output is an algorithmically
filtered float, and steps as small as 1e-6 appear.

**Chosen quantum: 0.01** for mass and number concentrations, **0.001** for
typical particle size. That is roughly 5x finer than the sensor's own
sample-to-sample movement, so the curve stays smooth, while being 500x coarser
than the doubles stored today. Worst-case quantisation error is 0.005 µg/m³,
against a spec device-to-device precision of ±5.

## Measured encodings

| Scheme | B/reading | Per day @1 Hz | Gain |
|---|---|---|---|
| CSV float text (ships today) | 220 | 19.0 MB | 1x |
| CSV rounded to 2 dp | 89 | 7.7 MB | 2x |
| CSV + gzip (today's backup path) | 116 | 10.0 MB | 2x |
| uint16 x10, implicit timestamp | 20 | 1.7 MB | 11x |
| **delta + zigzag varint** | **10** | **0.9 MB** | **22x** |
| delta + varint, 4 PM fields only | 4 | 0.3 MB | 55x |
| delta + varint then gzip | 3.8 | 0.3 MB | 57x |

## The format

**Delta + zigzag varint, 10 bytes per reading.** That works out at exactly one
byte per field, because every observed delta fits in a single varint - which the
p95 of 0.057 µg/m³ (about 6 quanta) predicts.

Per reading, for each of the ten fields in `wire.FIELDS` order:

    value_q     = round(value / quantum)
    delta       = value_q - previous_value_q
    encoded     = varint(zigzag(delta))

Timestamps cost nothing at a fixed cadence: derive them from the session header's
start time plus the reading index. Write an explicit marker record only when the
cadence actually breaks - a dropout, a profile change, a clock correction.

A session file is then:

    header:  magic, format version, start epoch, cadence, quanta, field list
    body:    one varint group per reading
    markers: interspersed, tagged, for gaps and events

**Deliberately no gzip on-device.** It buys 2.6x more but costs RAM, CPU and a
decompressor in every reader, and the capacity numbers below show it is not
needed. Compression still belongs on the *transfer* path, where radio time
dominates the power budget - that is a different trade and already implemented.

## What it buys on the ESP32

With roughly 2 MB usable after firmware:

| Acquisition mode | Per day | Flash lasts |
|---|---|---|
| Continuous 1 Hz | 0.9 MB | ~2 days |
| One reading / minute | 14 kB | ~5 months |
| Low power, 15-min cycle | 960 B | years |

Continuous 1 Hz is the only regime that outruns the flash, and it is precisely
the regime where someone is connected and streaming anyway. The deployable
case - left in a warehouse, in low power, collected weeks later - stops being a
storage problem at all; battery bounds it long before flash does.

Flash wear is not a concern either: at 1 Hz with wear levelling over 2 MB the
array cycles about every 2.4 days, so 100k-cycle NOR endurance is centuries.

## Further compression, if it is ever needed

PM4.0 and PM10 track PM2.5 almost exactly in clean air - the sensor sizes only
particles below ~1.3 µm optically and derives the coarser cuts from that
distribution. Delta-coding them against PM2.5 rather than against their own
previous value would drive them to near zero bits. The same holds for the NC
fields against each other. Not worth the complexity unless the budget tightens.

## As built

Implemented 2026-08-02, in `store.py` (the specification), `firmware/components/store`
(the firmware) and `firmware/partitions.csv` (the room to put it).
Three things were settled by measurement rather than by this document, and all
three are worth reading before changing anything here.

**The part is 4 MB, not 2.**
`esptool.py flash_id` says ESP32-D0WD-V3, 4 MB.
The build was configured for 2 MB and used the default single-app partition
table, which reaches `0x110000` and left nearly three quarters of the flash
unaddressed - which is the literal reason the firmware had nowhere to put
readings.
`store` is now 2,555,904 bytes and `factory` is 1.5 MB against an 854 kB image.

**A page, not a file, and twelve and a half bytes rather than ten.**
There is no filesystem, so the unit is a 512-byte page written in one shot under
a CRC, carrying enough header to be decoded entirely on its own.
That is what makes a ring buffer possible - the oldest pages are overwritten and
whatever survives still reads back - and what makes power loss safe by
construction rather than by a shutdown ritual.
Repeating that header is what separates the measured 12.5 from the 10 above.
512 bytes was chosen against 256, 1024, 2048 and 4096 on real sessions: 512 and
1024 store equally well and larger pages get *worse*, because a page holds at
most 255 readings and a dropout that ends one early wastes more of a big one.
512 therefore wins on the tie-break of losing least when the plug is pulled.

**Timestamps are derived per page, not per session.**
Deriving them from the session start was measured and does not work: the real
cadence is 1009 ms, not 1000, so the derived time drifts about nine milliseconds
a reading and every reading in a long session needs a correction marker - which
cost a third of the capacity before it was found.
Rebasing each page on its own first reading costs eight bytes a page and holds
the accumulated error under half a second, which is below the tolerance at which
a marker is written at all, so in the ordinary case it costs nothing.

Measured on 14,128 readings of real recorded data with `tools/measure_store.py`,
which round-trips every page and asserts the values survive to their quantum:

| Interval | Per day | Flash lasts |
|---|---|---|
| **1 s** | **1.08 MB** | **2.4 days** |
| 10 s | 108 kB | 24 days |
| 30 s | 36 kB | 71 days |
| 60 s | 18 kB | 4.7 months |
| 15 min | 1.2 kB | years |

So continuous 1 Hz clears the two-day requirement without slowing acquisition at
all, and there is no reason to sample less often than the sensor publishes.

## What happens when it wraps

Added 2026-08-03, from the captain's decision.
Everything above says how much fits.
This says what happens when it does not, and the rule that decides every question in it is his:

> **Recording incoming data always beats protecting older data.**
> If the choice is ever between accepting a new reading and preserving an old one, the new reading wins.

Wrapping is therefore allowed and expected, and nothing stops, throttles or delays acquisition to keep history.
The ring is 4480 slots - the partition less the preservation area below - which is **2.12 days of continuous 1 Hz**.
When it is full it wraps, and ordinary history goes.

**The rate is never reduced because the ring filled.**
Dropping to low-frequency sampling as soon as storage fills was considered and rejected: avoiding rate reduction until it is genuinely necessary is the behaviour wanted.
The one thing that does reduce it is below, and it is about the preservation area, not the ring.

### Alert-level readings leave the ring's way

A reading at or above the alert threshold is stored normally in the ring **and** copied into a dedicated area at the end of the partition that the ring never writes to.
The copy happens inline, as the reading is recorded.
If the ring is about to overwrite alert-level readings that never got there - because a phone lowered the line since, or because the readings were imported rather than measured - a sweep in front of the erase rescues them first.
That sweep is bounded to the one sector about to go, and it cannot stall acquisition: every failure in it is counted and the reading is recorded regardless.

**What counts as alert-level** is not a new idea.
It is the app's own `AlertRule`: one field, one threshold, firing at or above.
The controller is told **one number per field** - the minimum of the enabled rules' thresholds - and nothing else: no rule id, no label, no reaction, nothing per user.
That is a retention policy, not a notification setting, which is why it does not contradict the standing instruction that thresholds live only on the phone.
It has to exist on the controller because the case preservation is for is the one where **no phone is present** to hold an opinion, so it ships with `pm2_5 >= 20.0` - `AlertEngine.defaults()`'s only enabled rule.

### Two rings, ageing apart

```
 <------------------- store partition, 2.44 MB ----------------------->
 [ ring: 4480 pages, oldest reclaimed first ][ headers 64 kB ][ bodies 192 kB ]
                                              \_____ preservation area _____/
```

The preservation area is a region of the `store` partition rather than a partition of its own, deliberately: a partition-table change has to be flashed, and flashing one at a different offset is how a board's existing readings get orphaned.
It costs the ring about a tenth of its slots - six hours of 1 Hz - and buys alert data that survives however many times the ring turns over.

Inside it, **headers and samples age independently**, and that is the whole design.
A header is 128 bytes and a segment's samples are typically thousands, so 512 summaries fit in the space a handful of segments' samples take.
When the sample ring has to reclaim - and it must, or a new alert could not be recorded, which is the one thing this refuses - the summaries stay.

**Every preserved segment carries a header**, and it survives even if everything else about the segment is eventually reduced away:
start time, stop time, first value, last value, a midpoint averaged over a local neighbourhood, the segment average, the segment variance, the minimum, the maximum, and the number of points.
The statistics are of the field that tripped it, which the header names.
A header is never rewritten to say its samples have gone - flash cannot rewrite a record in place and the CRC covers the flag - so "reduced away" is worked out at read time by comparing against the oldest sample page still held.
That also keeps the rule the relative-time work states plainly: the board appends, it does not go back and relabel what is stored.

### The wrap counter

The ring counts its completed passes, so relative-time indexing continues across a wrap rather than resetting.
`page_seq` was already monotonic and that has not changed; what is new is that the count is kept and reported, and that it **survives an erase of the ring** - every segment header records the wrap it was written at, and the preservation area is outside the ring, so an erase does not touch it.

### Rate reduction, and only for the preservation area

When the sample region fills past 60 % the controller raises a floor under the recording interval to one a minute, and past 85 % to one every fifteen minutes - about 1.2 kB a day, which buys a great deal.
It is repeatable as pressure continues, and it is the **only** rate reduction in the firmware.

Three properties it has to keep:

- It never makes the interval shorter than the user asked for; the floor is a floor.
- It does not touch the fan. Only the recording interval moves, inside continuous mode, so a reduction cannot put a spike or a settling window into the record the way a mode change would.
- It is visible. The `alerts` message carries `rate_floor_s` and the app says so, because a board quietly recording once a minute otherwise looks exactly like a board recording once a second.

It also **comes back down** once the pressure clears, which the decision does not say in so many words but follows from the rule that governs all of this: a board left at one sample per fifteen minutes for ever because of one bad afternoon is protecting old data at the cost of new.

### Coarse-graining: recorded, not implemented

Repeatedly halving the resolution of preserved segments - keeping every second point, then every fourth - would also free space in the preservation area, and it is a reasonable idea.
Sampling-rate reduction was chosen instead.
This is written down so the option is not lost, **not** as a thing to go and build.

### How this sits with the auto-export at 85 %

`HANDOFF.md` item 13 and this are complementary and must not provoke each other.
That one sends everything to a device that is present, before there is any pressure to delete; this one protects the case where no device is present at all.

The one place they could fight is the fullness figure, and it is answered: `store_fraction_used` is the **ring's** fullness and stops at the preservation area, so preserved alert data accumulating in its own region can never look like a ring filling up.
The other direction is safe by construction: an erase only ever removes sectors of the ring, so a device that takes a full copy and then asks the controller to clear what it has proved it holds does not remove a single preserved segment.
## Recording with no clock

Added 2026-08-03.
Everything above assumes the controller knows what time it is.
A board that has never joined Wi-Fi does not, and it used to lose everything to that: a reading with no timestamp was not stored, not streamed, and discarded by the app if it ever arrived.

The board now counts on its own clock and says so.
Two additions, both small, and neither of them changes a byte of what an absolute session writes:

**A page can declare its times relative** (`FLAG_RELATIVE`).
`session_start_ms` and `page_start_ms` then hold a packed `(run, ms)` reading of the board's own clock rather than an epoch: the run in the top 32 bits, milliseconds in the bottom.
The run is the page sequence at boot, which is already monotonic for the life of the device, so it needs no new counter and two runs cannot be confused - which matters, because a reboot restarts the millisecond count by an amount nobody measured and the two stretches must never be presented as one.

The reason it goes in the existing field rather than a new one is the per-page rebase above.
`page_start_ms` is still this page's own first reading, the deltas within the page are still `i * cadence`, and the 1009 ms cadence is still absorbed by `MARK_TIME`.
Relative time changes what the origin *means* and nothing about how a page keeps time inside itself.
Milliseconds fit in 32 bits for 49 days of uninterrupted running, far past the point at which the ring has overwritten itself.

**An equivalence page** (`FLAG_ANCHOR`), appended when a time source turns up.
Eighteen bytes say "my clock read X when the world clock read Y", and the page holds up to sixteen of them.
The board does **not** go back and relabel anything: rewriting stored readings would put data that is already safe back under the write head, and a power cut part way through would corrupt an archive that was fine.
It only appends.

One is recorded per time source that arrives, not only the first, because each is an independent measurement of the same offset - so a reader uses the one nearest a given page and the error is the crystal's drift across that gap rather than across the whole run.
How far apart they may safely be is decided by the crystal's free-run drift, measured on this board at **-0.029 +/- 0.005 s per hour** (-8.0 ppm) over 3.6 hours with `firmware/tools/drift_probe.py`.
Two equivalences an hour apart therefore bound the conversion error at about thirty milliseconds, which is below the 500 ms tolerance at which this format writes a time marker at all - so in the ordinary case the drift between them costs nothing.

Each anchor page repeats every equivalence known, so the newest is a complete record on its own and equivalences do not age out one at a time with the pages that first carried them.
A page costs one ring slot, about forty seconds of 1 Hz recording.

`count == 0` is corrupt on a reading page and correct on an anchor page; the flag is the whole of that difference to a reader.

**The conversion is the client's.**
The controller sends the raw pairs and does no arithmetic on the client's behalf, which is what keeps it reversible: raw readings plus equivalences means a better time source later can simply be applied again, and that is impossible once a timestamp is baked into flash.
A block with no equivalence at all - power lost before anything ever connected - is genuinely unplaceable, and the client says so and offers to let the user shift it rather than inventing a plausible time.

## Consequences for what is already built

The session-copy feature assumes the controller can hold a full archive and
serve it as a zip of CSVs. An ESP32 cannot hold that. Two things follow:

1. The wire format for a copy should carry these compact records, with the
   *client* expanding them to CSV if the user wants CSV. The phone has the space
   and the CPU; the controller has neither.
   **Not done, deliberately.** `get_file` streams the daemon's existing
   `file_data` rows, which the app already parses, so storage landed without a
   protocol change on either side. Compact records remain the right next step and
   would cut the transfer by roughly fifteen times.
2. The controller becomes a ring buffer rather than an archive. It keeps what it
   can and the phone becomes the store of record, which makes the auto-export at
   85% (already scheduled) load-bearing rather than a convenience.
   **Half right, and the wrong half matters.** The controller is a ring buffer,
   but the phone is *not* the store of record - the board is, and it spills to
   the phone when it fills. That is not a wording preference: it decides what
   happens when nobody is watching, and a phone-as-record design loses everything
   that occurs while the phone is asleep, out of range or switched off, which is
   exactly the failure this was written to prevent. `backup` is still
   unsupported, because it asks for an archive this part cannot build;
   `list_files` plus a `get_file` each is the route, and the app takes it
   automatically on every reconnect.
