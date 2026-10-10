# Backing everything up and clearing the sensor

A **separate** control and a separate code path, sitting beside the existing copy,
transfer, verification and erase features rather than inside any of them.
Those four behave exactly as they did; nothing here reaches into them.

What it adds is the interleaving.
The captain's own design, in his order:

1. Take a block of the oldest stored data.
2. Copy that block to the user's device.
3. Have the sensor confirm that block is present on the device.
4. Only then free that block's space on the controller.
5. Move to the next block, and repeat.

Because space is freed progressively, acquisition continues throughout into space
it would have used anyway.
There is no stop-the-world moment and no window in which readings are dropped.

## Why it could not be the erase gate with a smaller argument

`store_erase_confirmed` takes a manifest of whole sessions, checks all of them,
and then erases the sectors it may.
That is right for "clear what I already have" and wrong for "empty this board":
nothing is freed until the whole copy is finished, so a board holding more than a
phone can take in one held breath can never be emptied at all.
It also has nothing to say about the preservation area, and says so on purpose -
"an erase only ever removes sectors of the ring, so a device that takes a full
copy and then asks the controller to clear what it has proved it holds does not
remove a single preserved segment" (`STORAGE_FORMAT.md`).

The rule that gate is built on is not weakened anywhere here.
**Nothing is freed that the asking device has not proved it holds**, and the proof
is the same digest, recomputed from flash, per block, every time.

## The block

A block is one **flash erase unit** - a 4096-byte sector - because that is the
smallest thing that can actually be freed.
There are three kinds, and the order they are drained in is the order in which
losing one would hurt most:

| Order | Kind | What it holds | Where |
|---|---|---|---|
| 1 | `body` | preserved alert samples, as ordinary pages | preservation area |
| 2 | `segments` | preserved segment summaries, 32 per block | preservation area |
| 3 | `ring` | ordinary recorded readings, oldest first | the ring |

**The preservation area goes first** because it is by construction the *oldest*
data the board holds: it is what was rescued from ring sectors that have since
been overwritten, so it is the part that exists nowhere else.
Bodies before headers, so that an interrupted run leaves the summaries - which the
storage design requires to outlive their samples - rather than the other way up.

Draining the preservation area at all is the thing this feature could most easily
have got wrong.
Preserved data is addressed nothing like ring data: another region, its own two
rings, a 128-byte record format for the summaries, and an erase gate that never
touches any of it by design.
A "back up everything" that walked only the ring would leave every preserved
segment on the board and call it clear.
`test_store_drain.c` drives that case explicitly - both sample blocks and the
summary block are drained, in that order, before a single ring block is offered,
and the region is asserted **blank** rather than merely empty at the end.

## Oldest first, and the wrap boundary

Ring blocks are ordered by how far they sit behind the write head, not by
`page_seq`.
On a full ring those are different answers, and the difference is the whole of the
wrap boundary: the head's own sector holds both the very oldest pages and the very
newest, and it is the one sector that must go last.

So the rule is: among the sectors that hold pages, take the one just after the
head's and walk forward; the head's sector is left until nothing else is left.
By then acquisition has moved the head into space this operation freed, so the
sector it started in is an ordinary block like any other and goes with the rest.

## The end of the sweep

Eventually the only block still holding data is the one the write head is in.
It is copied and confirmed exactly like every other, and then **kept**: the captain
was explicit that a board still recording does not have that block deleted out from
under it.
It becomes the start of storage - and since the ring is cyclic and its tail is
derived from `page_seq` at scan time, nothing physically moves.

### What "properly initialised" means for this storage design

Worked out from the design rather than assumed, and all of it is done at the moment
the last block is confirmed:

- **The readings still in RAM are sealed first.** The page the writer is filling is
  real data and is the newest thing the board has; it goes onto flash before any
  account of the ring is thrown away.
- **The preservation area is re-scanned from flash**, so its two write heads, its
  sequence counters and its segment count describe what is actually there. The rate
  floor is reviewed against the new pressure, which is how a board that had been
  reduced to one sample a minute comes back to full rate.
- **The session index is emptied and rebuilt by a full ring scan.** A summary
  naming a session whose pages have gone is the one thing this storage design
  refuses, and the way to be sure of that is the way the board does it on the way
  up.
- **`slots_used`, the anchor table and the writer's session are all discarded** and
  re-derived.

Two things are deliberately **not** reset, and both would be bugs if they were:

- **`page_seq`.** It is monotonic for the life of the device by design, and
  `store_run_marker` depends on that - two runs of a clockless board carrying the
  same marker would be presented to a reader as one unbroken stretch of recording.
- **The wrap count.** Because the newest block is kept, the sequence lineage is
  intact and the boot scan re-derives the true count from it. This is a store that
  has been emptied and re-initialised, on a board that still knows how long it has
  been running.

The **alert policy** is also left alone. It is not stored data, it does not survive
a reboot, and it is the connected user's current preference; silently narrowing
what the board preserves after a clear would be a surprise rather than a reset.

### One deliberate boundary: anchor pages

A page carrying clock equivalences holds no readings, so it contributes no rows and
no digest, and it is erased with its block.
Those equivalences reach the device through `time_map`, which is sent on connection
and sits **above** the drain in the client's turn order - so a device that is
draining has already been told every equivalence the board holds.
The count of such pages is reported rather than hidden.
This is the correct end state for a factory-reset-equivalent clear, and it is
written down here because it is the one thing the operation removes that is not a
reading.

## Crash safety

There is **no persistent drain state**, and that is the design rather than an
omission.
Progress *is* the contents of flash: a freed block is gone, so the next "oldest
block" is the next one, and a run interrupted anywhere resumes by asking the same
question again.

| Interrupted | What exists afterwards |
|---|---|
| between copy and confirm | the block is still on the sensor; the device throws away its partial copy |
| between confirm and free | the block is durable on the device **and** still on the sensor; the next run copies it again |
| after free | the block is durable on the device and gone from the sensor |
| board reboot | the sensor answers "what is oldest" from its own flash and carries on |
| app killed | the folder on the device is the record; a new run continues from what the sensor still holds |
| user cancels | the sensor is told to give the claim back and frees nothing on the way |

Nothing can be half-freed and unrecorded, because a block is only ever freed after
its copy has been proved.
A sector erase that a power cut interrupts leaves pages that fail their CRC and are
simply not pages, which is the bargain the page format already makes.
Re-copying a block that was confirmed but not freed costs a second and loses
nothing.

### On the device

`DrainSink.seal()` is the load-bearing call: the sensor is told it may free a block
the moment it returns, so what it has done has to have survived the process being
killed on the next line.
`DrainStore` therefore writes each block as **one whole file**, forces it to storage
with `fsync`, reads it back, and computes the digest from what it read.
A short write, a full disk or a truncated line is caught there - where it can be
reported as a device that could not write - rather than by the sensor refusing a
confirmation nobody can explain.

The folder is the copy:

```
<app files>/backups/sps30-clear-<run>/
    MANIFEST.csv          block,kind,rows,digest,file,freed
    block-000001-body.csv
    block-000002-segments.csv
    block-000003-ring.csv
    ...
```

Each block file is an ordinary CSV with the sensor's own column list at the top, so
every one of them opens in a spreadsheet with no tooling at all.
Zipping the folder into `Downloads/SPS30` happens **after** the last block is
confirmed and is allowed to fail: a run whose final tidying went wrong has still
saved every reading, and says where they are.

## The three things that have to agree before a byte is erased

`store_drain_confirm` checks them in this order:

1. The id is the block in flight, and it was actually streamed.
2. The device's claim equals what was streamed - it holds what it was sent.
3. A **fresh** read of the block from flash still equals what was streamed, so what
   is about to be erased is what was proved.

Any disagreement refuses, frees nothing, and ends the run.
Carrying on past a block the sensor could not confirm would mean skipping it and
calling the sensor clear.

The digests are `store_drain_digest_reading` and `store_drain_digest_segment`.
The first is `store_digest_reading`'s canonical bytes with a CRC of the session's
name in front of them - the name is there because a block is a slice of *flash*,
not of one session, and may hold pages of several.
The second digests a summary over the fields that cross the wire, with the
statistics as their raw IEEE-754 bits: the encoder writes the shortest text that
reads back as the same number, so both ends reconstruct the identical float and
there is nothing to disagree about.

**The expected values are carried on both sides**, on real board output:
`test_the_digests_the_app_must_agree_with` in `firmware/test/test_store_drain.c` and
`DeviceDrainTest` in the app assert the same hex for the same rows.
That is not decoration.
If the two implementations drift the operation either stops working or - far worse -
starts agreeing about data that is not the same data, and neither side's tests can
see that alone.
This pair caught a real defect while it was being written: the Kotlin was reading
each row's values one cell to the left, which is a digest that is wrong in a way
nothing else notices, because the numbers are still numbers.

## The protocol

`wire.py` is the authority; `wire.h`/`wire.c` and `Wire.kt` mirror it, and
`gen_wire_vectors.py` pins the C to the exact bytes `wire.py`'s own encoder
produces.

The device drives every step and the controller only ever answers.
That is what makes an interruption safe at any point: a controller that is never
mid-decision has nothing to roll back.

```
app                                        sensor
 |  drain_begin                              |
 |----------------------------------------->|  flush, close the open excursion,
 |<-----------------------------------------|  drain_state {idle, blocks_left}
 |  drain_next                               |
 |----------------------------------------->|  choose the oldest block
 |<-----------------------------------------|  drain_data {block, kind, rows, done:false}
 |<-----------------------------------------|  drain_data {... done:true}
 |<-----------------------------------------|  drain_state {offered, rows, digest}
 |  write it down, fsync, read it back       |
 |  drain_confirm {block, rows, digest}      |
 |----------------------------------------->|  re-read from flash, compare, erase
 |<-----------------------------------------|  drain_state {freed}
 |  ... and around again, until ...          |
 |<-----------------------------------------|  drain_state {kept}   the last block
```

All four commands are behind the control claim, including the two that free
nothing: a run another device could interleave with is a run neither of them can
reason about.
A drain and an import refuse each other, and so do a drain and an erase.

## Encryption - the honest note

**This transfer is not encrypted, and neither is anything else this product sends.**

The captain asked for it to be encrypted like everything else.
Nothing is encrypted today: the security design exists
(`data/sensirion-security-design-v2/report.md`, 2026-08-06) and is not implemented.
No part of this work attempts encryption and no part of it should be read as
providing any.
This copy is exactly as exposed as the live readings, a backfill, a session copy or
an erase - which, per that report, means readable by anyone who has ever had the
Wi-Fi password, or by a fifty-dollar dongle on the Bluetooth path.

What has been done is to make sure it will be covered for free when the sealed
channel lands.
That report puts the sealed framing in `net_server.c`'s dispatch path, above the
`net_link` seam, so that one implementation covers both transports.
Everything here is ordinary JSONL commands and messages through that same dispatch:
no second socket, no side channel, no bespoke framing, nothing that would have to be
sealed a second time or, worse, would quietly bypass the seal.
The digest is an integrity check against loss and corruption, not a signature - it
says the data is the same data, and nothing about who is asking.

## Where the code is

| | |
|---|---|
| the block rule, pure and host-tested | `firmware/components/store/store_drain.{h,c}` |
| the flash, the lock, the end state | `store.c`, the section at the end |
| the wire | `wire.py`, `wire.{h,c}`, `Wire.kt`, `Commands.kt`, `Messages.kt` |
| the handlers | `net_server.c`, the drain section |
| the device's state machine | `apk/.../data/DeviceDrain.kt` |
| the durable folder | `apk/.../data/DrainStore.kt` |
| the control | `apk/.../ui/BackupAndClearRow.kt` |

## Tests

The happy path is one test.
Everything else is a way of being interrupted, because the board this will run
against is recording a record its owner will not lose.

- `firmware/test/test_store_drain.c` - a full ring, the wrap boundary, a failed
  confirmation, a short row count, a block id nobody offered, an interruption
  before confirming, an interruption before freeing, a board reboot resumed from
  flash alone, flash changing under a block between being sent and being confirmed,
  a cancel part way through, preserved segments drained first and completely, a
  half-taken copy, several sessions in one block, a page that fails its CRC, and the
  end state asserted blank.
- `apk/.../DeviceDrainTest.kt` - the same failure paths from the device's side,
  with the sink recording its calls in order so "sealed before confirmed" is
  checked as a sequence rather than argued for.
- `apk/.../DrainStoreTest.kt` - what is on disk when `seal` returns, that the digest
  comes off the disk rather than out of memory, and what an unconfirmed block leaves
  behind.
- `apk/.../DrainMessagesTest.kt`, `firmware/test/test_wire.c`,
  `tests/test_protocol.py` - the wire, from all three ends.
- `apk/.../BackupAndClearTest.kt` - the rule that a heading never claims more than
  the body, which matters more here than anywhere else in the app.

## What has not been done

- **No device has been touched.** The captain's sensor is recording an air-quality
  event and nothing here has been run against it. Everything above is asserted on a
  simulated partition, a host build, and the ESP-IDF build.
- **No encryption**, as above.
- **The reported figures during a run come from the sensor's own count of its
  flash**, so the progress bar can genuinely go up when the sensor records faster
  than a block is copied. It is clamped so it never goes backwards, which is honest
  rather than flattering.
