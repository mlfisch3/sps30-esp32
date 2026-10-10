#!/usr/bin/env python3
"""Drive a clockless controller and record what it actually does.

The evidence behind the relative-time work. Run against a board flashed with
`NET_TIME_USE_SNTP 0` (see `firmware/main/main.c`), which has a network but no
clock - so the whole path can be watched over TCP rather than only over
Bluetooth, without faking a single reading.

It performs, in order, and prints what it saw at each step:

1. **Collect.** Connect and read the live stream. Every reading must arrive with
   an empty `timestamp` and a `rel_boot`/`rel_ms` pair, and the indices must be
   consecutive - nothing dropped.
2. **List.** `list_files` must name the session for its run
   (`SPS30-Rxxxxxxxx-xxxxxxxx.csv`), flagged `relative`, with no start instant
   invented for it.
3. **Fetch.** `get_file` must return rows carrying the board's own clock, and
   those rows are kept for step 5.
4. **Set the time.** `set_time`, then `time_map` must come back holding an
   equivalence for this run.
5. **Fetch again.** The rows must be **byte-identical** to step 3. That is the
   hard rule of the whole design: the board appends, it never rewrites.
6. **Convert.** Apply the equivalence the way the app does and print the
   absolute times the same readings now have.

Usage:  relative_time_demo.py --host 192.168.1.226 [--seconds 150] [--out log.jsonl]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import socket
import sys
import time
from datetime import datetime, timedelta, timezone

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT))

import wire  # noqa: E402  (the protocol's source of truth)


class Link:
    """One connection, framed with the repository's own JSONL rules."""

    def __init__(self, host: str, port: int, log) -> None:
        self.sock = socket.create_connection((host, port), timeout=30)
        self.sock.settimeout(30)
        self.buffer = bytearray()
        self.log = log

    def send(self, message: dict) -> None:
        self.sock.sendall(wire.encode(message))
        self.record("->", message)

    def record(self, direction: str, message: dict) -> None:
        if self.log is not None:
            self.log.write(json.dumps({"t": time.time(), "dir": direction,
                                       "msg": message}) + "\n")
            self.log.flush()

    def messages(self, seconds: float):
        """Yield decoded messages for up to [seconds]."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            for message in wire.iter_messages(self.buffer):
                self.record("<-", message)
                yield message
            try:
                chunk = self.sock.recv(4096)
            except socket.timeout:
                return
            if not chunk:
                return
            self.buffer += chunk
        for message in wire.iter_messages(self.buffer):
            self.record("<-", message)
            yield message

    def wait_for(self, type_name: str, seconds: float = 20.0):
        for message in self.messages(seconds):
            if message.get("type") == type_name:
                return message
        return None

    def close(self) -> None:
        self.sock.close()


def collect(link: Link, seconds: float) -> tuple[list[dict], dict | None]:
    readings: list[dict] = []
    time_map = None
    for message in link.messages(seconds):
        kind = message.get("type")
        if kind == wire.MSG_READING:
            readings.append(message)
        elif kind == wire.MSG_TIME_MAP:
            time_map = message
    return readings, time_map


def fetch_session(link: Link, name: str, seconds: float = 120.0) -> tuple[list, list]:
    link.send({"cmd": wire.CMD_GET_FILE, "name": name})
    columns: list[str] = []
    rows: list[list[str]] = []
    for message in link.messages(seconds):
        if message.get("type") != wire.MSG_FILE_DATA:
            continue
        columns = message.get("columns") or columns
        rows.extend(message.get("rows") or [])
        if message.get("done"):
            break
    return columns, rows


def local_iso(epoch_ms: int, offset_min: int) -> str:
    tz = timezone(timedelta(minutes=offset_min))
    return datetime.fromtimestamp(epoch_ms / 1000.0, tz).isoformat(timespec="seconds")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=wire.DEFAULT_PORT)
    parser.add_argument("--seconds", type=float, default=150.0,
                        help="how long to watch the live stream in step 1")
    parser.add_argument("--out", help="write every framed message to this JSONL file")
    args = parser.parse_args(argv[1:])

    log = open(args.out, "w") if args.out else None
    failures = 0

    def check(ok: bool, claim: str, detail: str = "") -> None:
        nonlocal failures
        if not ok:
            failures += 1
        print(f"  [{'ok ' if ok else 'FAIL'}] {claim}{(' - ' + detail) if detail else ''}")

    link = Link(args.host, args.port, log)
    hello = link.wait_for(wire.MSG_HELLO)
    print(f"connected to {args.host}:{args.port}")
    print(f"  sensor {hello.get('serial')} firmware {hello.get('firmware')}, "
          f"state {hello.get('state')}")

    print(f"\n1. watching the live stream for {args.seconds:.0f} s")
    readings, time_map = collect(link, args.seconds)
    check(bool(readings), f"readings arrived at all ({len(readings)})")
    relative = [r for r in readings if r.get(wire.COL_REL_MS) is not None]
    check(len(relative) == len(readings),
          "every reading carries the board's own clock",
          f"{len(relative)} of {len(readings)}")
    check(all(r.get("timestamp") == "" for r in relative),
          "and no invented timestamp beside it")
    indices = [int(r["index"]) for r in readings]
    gaps = [b - a for a, b in zip(indices, indices[1:]) if b - a != 1]
    check(not gaps, "indices are consecutive, so nothing was dropped",
          f"gaps: {gaps}" if gaps else "")
    boots = {r[wire.COL_REL_BOOT] for r in relative}
    check(len(boots) == 1, "one run", f"runs seen: {sorted(boots)}")
    run = next(iter(boots)) if boots else None
    if relative:
        span = (relative[-1][wire.COL_REL_MS] - relative[0][wire.COL_REL_MS]) / 1000.0
        print(f"  run {run}: clock {relative[0][wire.COL_REL_MS]} ms -> "
              f"{relative[-1][wire.COL_REL_MS]} ms ({span:.1f} s of recording)")
    started_clockless = time_map is not None and not time_map.get("anchors")
    if started_clockless:
        check(True, "no equivalence yet, which is the situation being tested")
    elif time_map is not None:
        # A second run against the same board: the first one gave it a clock, and
        # nothing takes that back. Not a failure - it just means the interesting
        # half of step 4 has already happened once.
        print(f"  note: this board already holds "
              f"{len(time_map.get('anchors', []))} equivalence(s) from an earlier "
              f"run of this script; power-cycle it for the virgin case")

    print("\n2. what the controller says it is holding")
    link.send({"cmd": wire.CMD_LIST_FILES})
    listing = link.wait_for(wire.MSG_FILES)
    files = (listing or {}).get("files", [])
    relative_sessions = [f for f in files if f.get("relative")]
    check(bool(relative_sessions), "a session is flagged relative",
          f"{len(relative_sessions)} of {len(files)}")
    for entry in relative_sessions:
        print(f"  {entry['name']}  {entry['rows']} rows  start={entry.get('start')!r}")
        check(wire.parse_rel_session_name(entry["name"]) is not None,
              f"{entry['name']} is named for its run, not for a date")
        check(not entry.get("start"),
              "and no start instant was invented for it")
    # The session this run is recording, and - if the board has been power cut
    # while clockless - one from a previous run. They are treated differently on
    # purpose: the first is about to get an equivalence, and the second never
    # can, because the run that recorded it ended before anything connected.
    def run_of(entry: dict) -> int | None:
        parsed = wire.parse_rel_session_name(entry["name"])
        return parsed[0] if parsed else None

    target = None
    orphan = None
    for entry in relative_sessions:
        if run_of(entry) == run:
            target = entry["name"]
        elif orphan is None:
            orphan = entry["name"]
    if target is None:
        print("\nno relative session for the running run; stopping")
        link.close()
        return 1
    print(f"  this run's session is {target}")
    if orphan is not None:
        print(f"  and {orphan} is from a run that ended before anything connected")

    print(f"\n3. fetching {target}")
    columns, rows = fetch_session(link, target)
    print(f"  {len(rows)} rows, columns: {columns}")
    check(wire.COL_REL_BOOT in columns and wire.COL_REL_MS in columns,
          "the stored rows carry the board's own clock")
    ts_col = columns.index("timestamp")
    check(all(not row[ts_col] for row in rows),
          "and no stored row claims an instant")
    before = [list(row) for row in rows]

    print("\n4. handing the controller a clock")
    now_ms = int(time.time() * 1000)
    # tm_isdst, not time.daylight: the latter says the zone *has* a summer time,
    # not whether one is in force, and getting it wrong puts the equivalence an
    # hour out - which would then be believed.
    offset_min = -int((time.altzone if time.localtime().tm_isdst else time.timezone) / 60)
    link.send({"cmd": wire.CMD_SET_TIME, "epoch_ms": now_ms,
               "utc_offset_min": offset_min})
    print(f"  sent set_time epoch_ms={now_ms} ({local_iso(now_ms, offset_min)})")
    updated = link.wait_for(wire.MSG_TIME_MAP)
    anchors = (updated or {}).get("anchors", [])
    check(bool(anchors), "the controller answered with an equivalence",
          f"{len(anchors)} held")
    mine = [a for a in anchors if a.get("boot") == run]
    check(bool(mine), "one of them is for the run that recorded this data")
    for a in anchors:
        print(f"  run {a['boot']}: clock {a['rel_ms']} ms = "
              f"{local_iso(a['epoch_ms'], a.get('utc_offset_min', 0))}")

    print("\n5. fetching the same session again")
    columns_after, rows_after = fetch_session(link, target)
    check(columns_after == columns, "the columns are unchanged")
    # The session is still recording, so the second fetch is *longer*. What must
    # not have happened is any change to what was already there: the claim is
    # that the board appends and never rewrites, and a growing tail is the
    # append. Comparing the whole list would test that the sensor had stopped.
    prefix = rows_after[:len(before)]
    differing = [i for i, (a, b) in enumerate(zip(before, prefix)) if a != b]
    check(len(rows_after) >= len(before) and not differing,
          "every row already written is byte-identical: NOTHING was rewritten",
          f"{len(differing)} of {len(before)} rows changed" if differing
          else f"{len(rows_after) - len(before)} new rows appended since")

    print("\n6. converting, the way the app does")
    if mine:
        boot_col = columns.index(wire.COL_REL_BOOT)
        ms_col = columns.index(wire.COL_REL_MS)
        shown = [0, len(rows_after) // 2, len(rows_after) - 1] if rows_after else []
        for i in shown:
            row = rows_after[i]
            resolved = wire.resolve_relative(anchors, int(row[boot_col]), int(row[ms_col]))
            check(resolved is not None, f"row {row[0]} resolves")
            if resolved:
                epoch_ms, offset = resolved
                print(f"  row {row[0]}  clock {row[ms_col]:>10} ms  ->  "
                      f"{local_iso(epoch_ms, offset)}")
        unknown = wire.resolve_relative(anchors, 999999, 1000)
        check(unknown is None,
              "a run with no equivalence stays unknowable rather than being guessed at")

    # --- the power-loss case ------------------------------------------------
    if orphan is not None:
        print(f"\n7. the run that was cut off: {orphan}")
        orphan_columns, orphan_rows = fetch_session(link, orphan)
        check(bool(orphan_rows), f"its readings are still here ({len(orphan_rows)})")
        check(wire.COL_REL_MS in orphan_columns,
              "and still carry the board's clock, which is all that was ever known")
        boot_col = orphan_columns.index(wire.COL_REL_BOOT)
        ms_col = orphan_columns.index(wire.COL_REL_MS)
        orphan_run = int(orphan_rows[0][boot_col])
        check(orphan_run != run, "it belongs to a different run",
              f"{orphan_run} vs {run}")
        resolved = [wire.resolve_relative(anchors, int(r[boot_col]), int(r[ms_col]))
                    for r in orphan_rows]
        check(all(r is None for r in resolved),
              "and NOTHING places it - the equivalence from the later run does not "
              "apply, and no time is invented")
        first_ms = int(orphan_rows[0][ms_col])
        last_ms = int(orphan_rows[-1][ms_col])
        print(f"  {len(orphan_rows)} readings spanning {(last_ms - first_ms) / 1000:.1f} s "
              f"of known internal timing, at an unknown position")
        # Which is exactly what the app has to say out loud, and offer to shift.

    link.close()
    if log is not None:
        log.close()
        print(f"\nevery framed message written to {args.out}")
    print(f"\n{'ALL CHECKS PASSED' if failures == 0 else str(failures) + ' CHECK(S) FAILED'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
