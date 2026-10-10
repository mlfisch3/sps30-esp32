# Two clockless sessions the board actually recorded

Real output from the ESP32 controller (sensor `E3164D2544338487`, firmware `2.1`)
on 2026-08-03, kept here so a host test can drive the app's conversion path
without a board, an emulator or a path outside the repository.

Nothing in this directory is synthetic and nothing is time-shifted.

## What each file is

| File | What it is |
|---|---|
| `SPS30-R000004B2-00000821.csv` | 1,000 readings, run `0x4B2` = 1202, **as the session was backed up** |
| `SPS30-R000004AC-00000817.csv` | 266 readings, run `0x4AC` = 1196, **as the session was backed up** |
| `SPS30-R000004B2-00000821.wire.csv` | the same 1,000 readings **as the board serves them** over `get_file` |
| `SPS30-R000004AC-00000817.wire.csv` | the same 266 readings, likewise |
| `time_map.json` | the three equivalences the board holds, exactly as it sends them |

The `.csv` pair was copied byte-for-byte from the backup taken at 10:33 on
2026-08-03.
The `.wire.csv` pair was fetched from the board itself at 12:38 the same day with
`get_file`, which is the message the app reads, and written out in the column
order the board declares.

The two forms were compared cell by cell: **every one of the 1,266 rows is
identical across the thirteen columns they share.** They are the same readings.
The wire form carries two further columns, `rel_boot` and `rel_ms` - the board's
own clock - and the backed-up form does not. That difference is the subject of
one of the tests.

The backed-up pair is what the **old** export wrote, and it is kept as that
rather than refreshed. `wire.CSV_COLUMNS_RELATIVE` now gives a clockless session
a header with room for its clock, so a backup taken today carries `rel_boot` and
`rel_ms` and reads back complete - but no change to a format reaches into a file
already on disk, and these two files are the record of what a clockless session
used to lose on the way out. `BoardSessionsTest` asserts both: the round trip
through the fixed format keeps all 1,266, and these two still keep none.

## Why these two sessions

They are the case the relative-time design exists for, and the board produced
them without being asked to:

- **Run 1202** (`...4B2`) was recording when a phone finally handed the board the
  time, so the board appended three equivalences for it. Its readings can be
  placed, and the test checks they are placed correctly and at the spacing the
  board recorded.
- **Run 1196** (`...4AC`) ended before anything ever connected. Nothing places
  it and nothing ever will. It is here to hold the rule that such a run is kept
  and shown as elapsed time, never dropped and never given an invented date.

The `timestamp` column is empty in every row of both files. The board left it
empty rather than filling it in, which is the board half of this design and is
already evidenced in `docs/evidence/relative-time-hardware-run.txt`.

## The equivalences

`time_map.json` is one `time_map` message verbatim. All three anchors belong to
run 1202; there is none for run 1196, which is what makes the second session
unplaceable. Their offsets from the board's clock differ by a few milliseconds
across the three, which is the crystal's real drift and is why the app uses the
nearest anchor rather than the first.
