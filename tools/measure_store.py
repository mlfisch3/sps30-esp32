#!/usr/bin/env python3
"""Measure `store.py` on real recorded readings, and print the flash arithmetic.

The point of this script is that no number in the report is estimated. It packs
actual CSVs through the actual encoder, round-trips every page back and asserts
the values survive to their quantum, and only then divides the partition size by
what it measured.

    python3 tools/measure_store.py ~/sps30-gui-data/*.csv
"""
from __future__ import annotations

import csv
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import store  # noqa: E402
import wire  # noqa: E402

#: The `storage` partition in `firmware/partitions.csv`, on the 4 MB part the
#: board actually has. Measured with `esptool.py flash_id`, not assumed.
PARTITION_BYTES = 0x270000


def read_csv(path: pathlib.Path) -> list[store.Reading]:
    out: list[store.Reading] = []
    with open(path, newline="") as fh:
        reader = csv.reader(fh)
        columns = next(reader, None)
        if not columns or columns[0] != "index":
            return out
        idx = {name: i for i, name in enumerate(columns)}
        if any(f not in idx for f in wire.FIELDS):
            return out
        for row in reader:
            if len(row) < len(wire.FIELDS) + 2:
                continue
            try:
                values = [float(row[idx[f]]) for f in wire.FIELDS]
                index = int(row[0])
            except ValueError:
                continue
            epoch_ms = _epoch_ms(row[1])
            if epoch_ms is None:
                continue
            event = row[idx["event"]] if "event" in idx and len(row) > idx["event"] else ""
            out.append(store.Reading(index, values, epoch_ms, event))
    return out


def _epoch_ms(timestamp: str) -> int | None:
    import datetime as dt
    try:
        return int(dt.datetime.fromisoformat(timestamp).timestamp() * 1000)
    except ValueError:
        return None


def main(argv: list[str]) -> int:
    paths = [pathlib.Path(p) for p in argv[1:]]
    if not paths:
        print(__doc__)
        return 2

    total_readings = 0
    total_pages = 0
    total_csv_bytes = 0
    per_file = []
    worst_error = 0.0
    # Full pages only: the steady state of a long run, where the one short page
    # at the end of a session is a rounding error rather than one page in
    # fifteen. That is the figure the capacity table has to be built on.
    full_pages = 0
    full_readings = 0

    for path in sorted(paths):
        readings = read_csv(path)
        if len(readings) < 2:
            continue
        pages = store.pack(readings)
        # Round-trip: the format is only worth measuring if it reads back.
        back = list(store.unpack(pages))
        assert len(back) == len(readings), f"{path}: {len(back)} != {len(readings)}"
        for original, restored in zip(readings, back):
            assert original.index == restored.index
            for a, b, q in zip(original.values, restored.values, store.QUANTA):
                error = abs(a - b)
                assert error <= q * 0.5 + 1e-9, f"{path}: {a} -> {b}"
                worst_error = max(worst_error, error)
        for raw in pages:
            decoded = store.decode_page(raw)
            if decoded is not None and decoded.full:
                full_pages += 1
                full_readings += decoded.count
        total_readings += len(readings)
        total_pages += len(pages)
        total_csv_bytes += path.stat().st_size
        per_file.append((path.name, len(readings), len(pages)))

    if not total_readings:
        print("no readable CSVs")
        return 1

    stored = total_pages * store.PAGE_BYTES
    per_reading = stored / total_readings
    steady = full_pages * store.PAGE_BYTES / full_readings
    csv_per_reading = total_csv_bytes / total_readings

    print(f"measured on {len(per_file)} sessions, "
          f"{total_readings:,} readings from real recorded data")
    print(f"  worst round-trip error : {worst_error:.6f} "
          f"(quantum/2 = {store.QUANTUM_DEFAULT / 2})")
    print()
    print(f"  CSV as recorded        : {csv_per_reading:8.1f} B/reading")
    print(f"  store, these sessions  : {per_reading:8.2f} B/reading   "
          f"(short sessions, one part-page each)")
    print(f"  store, steady state    : {steady:8.2f} B/reading   "
          f"({csv_per_reading / steady:.0f}x smaller)")
    print(f"  page size              : {store.PAGE_BYTES} B, "
          f"header {store.HEADER_BYTES} B")
    print(f"  readings per full page : {full_readings / full_pages:.1f} average")
    print(f"  worst power-fail loss  : {full_readings / full_pages:.0f} readings "
          f"(one unwritten page)")
    print()
    per_reading = steady

    cap = store.capacity(PARTITION_BYTES, per_reading)
    print(f"storage partition        : {PARTITION_BYTES:,} B "
          f"({PARTITION_BYTES / 2**20:.2f} MiB) on a 4 MB part")
    print(f"  pages                  : {cap.pages:,}")
    print(f"  readings it holds      : {cap.readings:,}")
    print()
    print("  interval    per day        flash lasts")
    print("  --------    -----------    -----------")
    for seconds in (1, 10, 30, 60, 900):
        per_day = 86400.0 / seconds * per_reading
        days = cap.days_at(seconds)
        label = f"{seconds} s" if seconds < 60 else f"{seconds // 60} min"
        if per_day >= 1e6:
            size = f"{per_day / 1e6:.2f} MB"
        else:
            size = f"{per_day / 1e3:.1f} kB"
        span = f"{days:.1f} days" if days < 90 else f"{days / 30.44:.1f} months"
        print(f"  {label:>8}    {size:>11}    {span:>11}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
