#!/usr/bin/env python3
"""Emit the wire-protocol contract as JSON, for any platform to be held to.

The same argument as ``firmware/test/gen_wire_vectors.py``, aimed at the next
platform instead of at C. ``wire.py`` is the single source of truth for the
JSONL protocol; the C mirror is held to it by a generated header, and the
Kotlin mirror used to be held to it by hand-copied literals - parity kept by
memory. This script closes that gap: it runs the real ``wire.py`` over the same
deliberately awkward cases the C header uses and writes what it produced into
``vectors/wire_vectors.json``, a form an XCTest, a Kotlin test, or any harness
can consume without a C toolchain.

Three things keep the file trustworthy rather than decorative:

* **Every expected string is produced by calling the real ``wire.py``** - the
  module the Pi daemon, the ESP32 firmware and the Android app already agree
  through. Nothing here is typed in by hand.
* **The case tables are imported from ``gen_wire_vectors.py``**, not copied, so
  the JSON and the C header cannot drift apart on what they cover. This import
  is read-only; ``sys.dont_write_bytecode`` is set so it cannot even leave a
  ``__pycache__`` behind in ``firmware/``.
* **The committed file is held fresh by the standard Python pack**
  (``tests/test_wire_vectors_json.py`` regenerates it in memory and compares
  byte for byte), and consumed by machine on the Kotlin side
  (``apk/core/.../wire/WireVectorFileTest.kt``). A stale or hand-edited copy
  turns a pack red rather than quietly narrowing the contract.

Usage:  python3 tools/gen_wire_vectors_json.py [output.json]
        (default: vectors/wire_vectors.json; --check compares instead of writing)

Encoding conventions (also stated in the file's own ``meta.encoding``):

* ``line`` values are exact JSONL frames, trailing newline included.
* Measurement inputs carry both the float32-promoted double (a JSON number
  that round-trips exactly) and ``values_hex``, the big-endian IEEE-754
  binary32 bit pattern as 8 hex digits - belt and braces.
* IEEE specials, which JSON cannot carry as numbers, are the strings
  ``"NaN"``, ``"Infinity"`` and ``"-Infinity"`` - inputs only; every expected
  output ``wire.py`` produces is finite by construction.
* Per-page storage states are digit strings, one page per character.
"""
from __future__ import annotations

import datetime as dt
import json
import math
import pathlib
import struct
import sys

# Read-only imports from firmware/test must not write bytecode caches there.
sys.dont_write_bytecode = True

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
FIRMWARE_TEST = REPO_ROOT / "firmware" / "test"
DEFAULT_OUT = REPO_ROOT / "vectors" / "wire_vectors.json"

sys.path.insert(0, str(FIRMWARE_TEST))
sys.path.insert(0, str(REPO_ROOT))

# The shared case tables and the sps30 loader. Importing rather than copying is
# what keeps this file and the C header covering the same ground.
import gen_wire_vectors as gwv  # noqa: E402

import wire  # noqa: E402  (the repository's wire.py, the protocol source of truth)

sps30 = gwv.sps30
f32 = gwv.f32


def f32_hex(value: float) -> str:
    """Big-endian IEEE-754 binary32 bit pattern of the float32 nearest [value]."""
    return struct.pack(">f", f32(value)).hex()


def jnum(value: float):
    """[value] as JSON can carry it: a number, or the string an IEEE special needs."""
    if math.isnan(value):
        return "NaN"
    if math.isinf(value):
        return "Infinity" if value > 0 else "-Infinity"
    return value


def sanitize(value):
    """Recursively rewrite IEEE specials so ``json.dumps(allow_nan=False)`` accepts."""
    if isinstance(value, float):
        return jnum(value)
    if isinstance(value, dict):
        return {k: sanitize(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [sanitize(v) for v in value]
    return value


def states_digits(states) -> str:
    """Per-page states as a digit string, one page per character."""
    return "".join(str(s) for s in states)


def line_of(message: dict) -> str:
    """The exact JSONL frame ``wire.py`` emits, trailing newline included."""
    return wire.encode(message).decode("utf-8")


def build() -> str:
    """The whole vector file as text. Deterministic: same inputs, same bytes."""
    doc: dict = {}

    doc["meta"] = {
        "source": "wire.py",
        "generator": "tools/gen_wire_vectors_json.py",
        "regenerate": "python3 tools/gen_wire_vectors_json.py",
        "freshness": "tests/test_wire_vectors_json.py regenerates and compares byte for byte",
        "encoding": {
            "line": "an exact JSONL frame as wire.py emits it, trailing newline included",
            "values_hex": "big-endian IEEE-754 binary32 bit pattern, 8 hex digits",
            "non_finite": 'inputs JSON cannot carry as numbers are the strings "NaN", "Infinity", "-Infinity"',
            "states": "per-page storage states as a digit string, one page per character",
        },
    }

    # --- the vocabulary -----------------------------------------------------
    doc["constants"] = {
        "default_port": wire.DEFAULT_PORT,
        "index_width": wire.INDEX_WIDTH,
        "max_clients": wire.MAX_CLIENTS,
        "max_clients_tcp": wire.MAX_CLIENTS_TCP,
        "max_clients_ble": wire.MAX_CLIENTS_BLE,
        "control_ttl_seconds": wire.CONTROL_TTL_SECONDS,
        "backup_chunk_bytes": wire.BACKUP_CHUNK_BYTES,
        "store_warn_fraction": wire.STORE_WARN_FRACTION,
        "clean_after_seconds": wire.CLEAN_AFTER_SECONDS,
        "min_firmware_for_sleep": list(wire.MIN_FIRMWARE_FOR_SLEEP),
        "interval_max_s": wire.INTERVAL_MAX,
        "cycle_max_s": wire.CYCLE_MAX,
        "warmup_max_s": wire.WARMUP_MAX,
        "average_max_s": wire.AVERAGE_MAX,
        "warmup_min_s": wire.WARMUP_MIN,
        "error_ref_none": wire.ERROR_REF_NONE,
        "alert_auto_default": wire.ALERT_AUTO_DEFAULT,
        "alert_default_thresholds": {
            k: f32(v) for k, v in wire.ALERT_DEFAULT_THRESHOLDS.items()
        },
    }

    doc["messages"] = {
        "hello": wire.MSG_HELLO,
        "reading": wire.MSG_READING,
        "state": wire.MSG_STATE,
        "raw_response": wire.MSG_RAW,
        "file_list": wire.MSG_FILES,
        "file_data": wire.MSG_FILE_DATA,
        "error": wire.MSG_ERROR,
        "profile": wire.MSG_PROFILE,
        "power": wire.MSG_POWER,
        "control": wire.MSG_CONTROL,
        "backup_meta": wire.MSG_BACKUP_META,
        "backup_chunk": wire.MSG_BACKUP_CHUNK,
        "clients": wire.MSG_CLIENTS,
        "time_map": wire.MSG_TIME_MAP,
        "storage": wire.MSG_STORAGE,
        "import_result": wire.MSG_IMPORT_RESULT,
        "erase_result": wire.MSG_ERASE_RESULT,
        "storage_map": wire.MSG_STORAGE_MAP,
        "alerts": wire.MSG_ALERTS,
        "alert_segment": wire.MSG_ALERT_SEGMENT,
        "alert_data": wire.MSG_ALERT_DATA,
        "drain_state": wire.MSG_DRAIN_STATE,
        "drain_data": wire.MSG_DRAIN_DATA,
        "power_estimate": wire.MSG_POWER_ESTIMATE,
    }

    doc["commands"] = {
        "pause": wire.CMD_PAUSE,
        "resume": wire.CMD_RESUME,
        "clean": wire.CMD_CLEAN,
        "restart": wire.CMD_RESTART,
        "raw": wire.CMD_RAW,
        "list_files": wire.CMD_LIST_FILES,
        "get_file": wire.CMD_GET_FILE,
        "set_profile": wire.CMD_SET_PROFILE,
        "shutdown": wire.CMD_SHUTDOWN,
        "identify": wire.CMD_IDENTIFY,
        "claim": wire.CMD_CLAIM,
        "release": wire.CMD_RELEASE,
        "backup": wire.CMD_BACKUP,
        "set_time": wire.CMD_SET_TIME,
        "put_file": wire.CMD_PUT_FILE,
        "erase": wire.CMD_ERASE,
        "storage": wire.CMD_STORAGE,
        "storage_map": wire.CMD_STORAGE_MAP,
        "get_alerts": wire.CMD_GET_ALERTS,
        "set_alerts": wire.CMD_SET_ALERTS,
        "get_power_estimate": wire.CMD_GET_POWER_ESTIMATE,
        "drain_begin": wire.CMD_DRAIN_BEGIN,
        "drain_next": wire.CMD_DRAIN_NEXT,
        "drain_confirm": wire.CMD_DRAIN_CONFIRM,
        "drain_cancel": wire.CMD_DRAIN_CANCEL,
    }
    doc["controller_only_commands"] = sorted(wire.CONTROLLER_ONLY_COMMANDS)
    doc["mutating_commands"] = sorted(wire.MUTATING_COMMANDS)

    doc["states"] = {
        "checking": wire.STATE_CHECKING,
        "measuring": wire.STATE_MEASURING,
        "paused": wire.STATE_PAUSED,
        "cleaning": wire.STATE_CLEANING,
        "sleeping": wire.STATE_SLEEPING,
        "warmup": wire.STATE_WARMUP,
        "shutting_down": wire.STATE_SHUTDOWN,
    }
    doc["modes"] = {
        "continuous": wire.MODE_CONTINUOUS,
        "duty_cycle": wire.MODE_DUTY_CYCLE,
    }
    doc["transports"] = {
        "tcp": wire.TRANSPORT_TCP,
        "ble": wire.TRANSPORT_BLE,
    }
    doc["hello_flags"] = {
        "supports_sleep": wire.HELLO_SUPPORTS_SLEEP,
        "can_power_off": wire.HELLO_CAN_POWER_OFF,
        "has_clock": wire.HELLO_HAS_CLOCK,
        "absent_means": True,
    }
    doc["time_sources"] = {
        "measured": wire.TIME_MEASURED,
        "derived": wire.TIME_DERIVED,
        "unanchored": wire.TIME_UNANCHORED,
        "manual": wire.TIME_MANUAL,
        "column": wire.TIME_SOURCE_COLUMN,
    }
    doc["power"] = {
        "clean_due_hours_key": wire.POWER_CLEAN_DUE_HOURS,
        "basis_modelled": wire.POWER_ESTIMATE_BASIS_MODELLED,
        "runtime_statuses": list(wire.POWER_RUNTIME_STATUSES),
        "subsystems": list(wire.POWER_SUBSYSTEMS),
        "compare_cycles_s": list(wire.POWER_COMPARE_CYCLES),
    }

    doc["mdns"] = {
        "service": wire.MDNS_SERVICE,
        "proto": wire.MDNS_PROTO,
        "service_type": wire.MDNS_SERVICE_TYPE,
        "hostname": wire.MDNS_HOSTNAME,
        "instance": wire.MDNS_INSTANCE,
        "txt_version_key": wire.MDNS_TXT_VERSION,
        "txt_protocol_key": wire.MDNS_TXT_PROTOCOL,
        "txt": [[k, v] for k, v in wire.MDNS_TXT],
    }

    doc["columns"] = {
        "fields": list(wire.FIELDS),
        "csv": list(wire.CSV_COLUMNS),
        "csv_relative": list(wire.CSV_COLUMNS_RELATIVE),
        "rel_boot": wire.COL_REL_BOOT,
        "rel_ms": wire.COL_REL_MS,
        "drain_reading": list(wire.DRAIN_READING_COLUMNS),
        "drain_segment": list(wire.DRAIN_SEGMENT_COLUMNS),
        "alert_segment_keys": list(wire.ALERT_SEGMENT_KEYS),
    }

    doc["file_list_keys"] = {
        "offset": wire.FILES_OFFSET,
        "total": wire.FILES_TOTAL,
        "next": wire.FILES_NEXT,
    }

    # --- reading lines ------------------------------------------------------
    readings = []
    for case_index, (name, values) in enumerate(gwv.MEASUREMENTS.items()):
        for instant_index, (_zone_name, naive) in enumerate(gwv.INSTANTS[:3]):
            when = naive.replace(tzinfo=wire.EASTERN)
            event = gwv.EVENTS[(case_index + instant_index) % len(gwv.EVENTS)]
            index = 1 + case_index * 7919 + instant_index
            csv = wire.csv_filename(when)
            measurement = sps30.Measurement(*[f32(v) for v in values])
            row = wire.measurement_row(index, when, measurement, event)
            readings.append({
                "name": f"{name} / {wire.iso_timestamp(when)}",
                "csv": csv,
                "index": index,
                "index_text": wire.format_index(index),
                "timestamp": wire.iso_timestamp(when),
                "event": event,
                "values": [f32(v) for v in values],
                "values_hex": [f32_hex(v) for v in values],
                "line": line_of({"type": wire.MSG_READING, "csv": csv, **row}),
            })
    # One with no session yet: the daemon sends a JSON null for csv before the
    # first file is open, and a client models it as a nullable.
    when = gwv.INSTANTS[1][1].replace(tzinfo=wire.EASTERN)
    plausible = gwv.MEASUREMENTS["plausible indoor"]
    measurement = sps30.Measurement(*[f32(v) for v in plausible])
    row = wire.measurement_row(4242, when, measurement, "")
    readings.append({
        "name": "no session yet (csv is null)",
        "csv": None,
        "index": 4242,
        "index_text": wire.format_index(4242),
        "timestamp": wire.iso_timestamp(when),
        "event": "",
        "values": [f32(v) for v in plausible],
        "values_hex": [f32_hex(v) for v in plausible],
        "line": line_of({"type": wire.MSG_READING, "csv": None, **row}),
    })
    doc["readings"] = readings

    # A reading from a clockless session: no timestamp, and the board's own
    # clock in its place. `timestamp` is empty on purpose - a fabricated one
    # would be read as an instant everywhere downstream.
    rel_row = wire.measurement_row(
        17, gwv.INSTANTS[1][1].replace(tzinfo=wire.EASTERN),
        sps30.Measurement(*[f32(v) for v in plausible]), "")
    rel_row["timestamp"] = ""
    doc["relative_reading"] = {
        "csv": wire.rel_session_name(854, 6437),
        "index": 17,
        "rel_boot": 854,
        "rel_ms": 21437,
        "values": [f32(v) for v in plausible],
        "values_hex": [f32_hex(v) for v in plausible],
        "line": line_of({
            "type": wire.MSG_READING,
            "csv": wire.rel_session_name(854, 6437),
            **rel_row,
            wire.COL_REL_BOOT: 854,
            wire.COL_REL_MS: 21437,
        }),
    }

    # --- timestamps and session names ---------------------------------------
    times = []
    for name, naive in gwv.INSTANTS:
        when = naive.replace(tzinfo=wire.EASTERN)
        offset = when.utcoffset()
        assert offset is not None
        offset_s = int(offset.total_seconds())
        assert offset_s % 60 == 0, (name, offset_s)
        times.append({
            "name": name,
            "epoch_s": int(when.timestamp()),
            "utc_offset_s": offset_s,
            "utc_offset_min": offset_s // 60,
            "iso": wire.iso_timestamp(when),
            "csv": wire.csv_filename(when),
        })
    doc["times"] = times

    doc["indices"] = [
        {"index": value, "expected": wire.format_index(value)}
        for value in [0, 1, 9, 10, 99, 1000, 9999999, 10000000, 4294967295]
    ]

    doc["rel_session_names"] = [
        {"run": run, "start_ms": start, "expected": wire.rel_session_name(run, start)}
        for run, start in [(0, 0), (1, 6437), (854, 3_612_400),
                           (0x7FFFFFFF, 0xFFFFFFFF)]
    ]

    # --- the storage occupancy map ------------------------------------------
    cells_cases = [
        [wire.PAGE_STATE_DATA, wire.PAGE_STATE_EMPTY,
         wire.PAGE_STATE_ANCHOR, wire.PAGE_STATE_BAD],
        [wire.PAGE_STATE_BAD],
        [wire.PAGE_STATE_DATA] * 5,
        [wire.PAGE_STATE_EMPTY] * 7,
    ]
    for states in cells_cases:
        assert wire.unpack_page_states(wire.pack_page_states(states),
                                       len(states)) == states
    doc["page_states"] = {
        "empty": wire.PAGE_STATE_EMPTY,
        "data": wire.PAGE_STATE_DATA,
        "anchor": wire.PAGE_STATE_ANCHOR,
        "bad": wire.PAGE_STATE_BAD,
    }
    doc["map_sections"] = {
        "ring": wire.MAP_SECTION_RING,
        "alert_headers": wire.MAP_SECTION_ALERT_HEADERS,
        "alert_bodies": wire.MAP_SECTION_ALERT_BODIES,
    }
    doc["cells"] = [
        {"states": states_digits(states), "cells": wire.pack_page_states(states)}
        for states in cells_cases
    ]

    # A board a few hours into its first pass, and the real geometry
    # mid-eighth-pass - the same two boards the C header carries.
    small_sections = [
        {"id": wire.MAP_SECTION_RING, "start": 0, "pages": 12, "head": 5,
         "wraps": 0, "states": [
             wire.PAGE_STATE_DATA, wire.PAGE_STATE_DATA, wire.PAGE_STATE_ANCHOR,
             wire.PAGE_STATE_DATA, wire.PAGE_STATE_BAD, wire.PAGE_STATE_EMPTY,
             wire.PAGE_STATE_EMPTY, wire.PAGE_STATE_EMPTY, wire.PAGE_STATE_EMPTY,
             wire.PAGE_STATE_EMPTY, wire.PAGE_STATE_EMPTY, wire.PAGE_STATE_EMPTY]},
        {"id": wire.MAP_SECTION_ALERT_HEADERS, "start": 12, "pages": 4,
         "head": 1, "wraps": 0,
         "states": [wire.PAGE_STATE_DATA] + [wire.PAGE_STATE_EMPTY] * 3},
        {"id": wire.MAP_SECTION_ALERT_BODIES, "start": 16, "pages": 4,
         "head": 0, "wraps": 0, "states": [wire.PAGE_STATE_EMPTY] * 4},
    ]

    def full_ring_state(i: int) -> int:
        if 1200 <= i < 1208:  # the erased sector ahead of the head
            return wire.PAGE_STATE_EMPTY
        if i == 4000:
            return wire.PAGE_STATE_BAD
        if i % 500 == 137:
            return wire.PAGE_STATE_ANCHOR
        return wire.PAGE_STATE_DATA

    full_sections = [
        {"id": wire.MAP_SECTION_RING, "start": 0, "pages": 4480, "head": 1200,
         "wraps": 7, "states": [full_ring_state(i) for i in range(4480)]},
        {"id": wire.MAP_SECTION_ALERT_HEADERS, "start": 4480, "pages": 128,
         "head": 9, "wraps": 0,
         "states": [wire.PAGE_STATE_DATA] * 9 + [wire.PAGE_STATE_EMPTY] * 119},
        {"id": wire.MAP_SECTION_ALERT_BODIES, "start": 4608, "pages": 384,
         "head": 37, "wraps": 0,
         "states": [wire.PAGE_STATE_DATA] * 37 + [wire.PAGE_STATE_EMPTY] * 347},
    ]
    full_line = wire.encode(wire.storage_map_message(512, 4992, full_sections))
    assert len(full_line) < 8192, f"full-size storage_map is {len(full_line)} B"

    def map_vector(page_bytes, pages, sections, line: bytes) -> dict:
        return {
            "page_bytes": page_bytes,
            "pages": pages,
            "sections": [
                {"id": s["id"], "start": s["start"], "pages": s["pages"],
                 "head": s["head"], "wraps": s["wraps"],
                 "states": states_digits(s["states"])}
                for s in sections
            ],
            "line": line.decode("utf-8"),
        }

    doc["storage_map"] = {
        "small": map_vector(512, 20, small_sections,
                            wire.encode(wire.storage_map_message(512, 20,
                                                                 small_sections))),
        "full": map_vector(512, 4992, full_sections, full_line),
    }

    # --- fan-clean accounting -----------------------------------------------
    clean = []
    for run_seconds in [0.0, 1.0, 359.9, 360.0, 3600.0, 302400.0, 604799.0,
                        604800.0, 1000000.0, 1234.5,
                        float("nan"), float("-inf")]:
        hours = wire.clean_due_in_hours(run_seconds)
        payload = {
            "source": "usb",
            "method": "none",
            wire.POWER_CLEAN_DUE_HOURS: hours,
        }
        clean.append({
            "run_seconds": jnum(run_seconds),
            "expected_hours": hours,
            "line": line_of({"type": wire.MSG_POWER, "power": payload}),
        })
    doc["clean"] = clean

    # --- the default profile, and normalizing one off the wire ---------------
    doc["default_profile"] = dict(wire.DEFAULT_PROFILE)
    doc["profile_line"] = line_of(
        {"type": wire.MSG_PROFILE, "profile": dict(wire.DEFAULT_PROFILE)})

    profile_cases = [
        {},
        {"mode": "duty_cycle"},
        {"mode": "sabotage"},
        {"interval": 10.0},
        {"interval": wire.INTERVAL_MAX},
        {"interval": wire.INTERVAL_MAX + 1},
        {"interval": 1e9},
        {"interval": 0.0},
        {"interval": -5.0},
        {"cycle_s": 86400.0},
        {"cycle_s": wire.CYCLE_MAX + 1.0},
        {"cycle_s": 86400000.0},
        {"warmup_s": 1.0},
        {"warmup_s": wire.WARMUP_MAX + 100.0},
        {"average_s": wire.AVERAGE_MAX * 10},
        {
            "mode": "duty_cycle",
            "interval": 1e12,
            "cycle_s": 1e12,
            "warmup_s": 1e12,
            "average_s": 1e12,
        },
        {"cycle_s": 10.0, "warmup_s": 30.0, "average_s": 30.0},
        {"interval": float("nan"), "cycle_s": float("nan")},
        {"interval": float("inf"), "cycle_s": float("inf")},
        {"interval": float("-inf")},
        {"interval": "not a number", "cycle_s": None},
    ]
    doc["profiles"] = [
        {"input": sanitize(case), "expected": wire.normalize_profile(case)}
        for case in profile_cases
    ]

    # --- a listing longer than one frame ------------------------------------
    listing = [
        {
            "name": "SPS30-20260802_175923.csv",
            "rows": 1834,
            "bytes": 399212,
            "start": "2026-08-02T17:59:23-04:00",
        },
        {
            "name": "SPS30-20260802_172243.csv",
            "rows": 41,
            "bytes": 8923,
            "start": "2026-08-02T17:22:43-04:00",
        },
        {
            "name": "SPS30-20260801_090000.csv",
            "rows": 7,
            "bytes": 1526,
            "start": "2026-08-01T09:00:00-04:00",
        },
    ]
    empty_page = wire.file_list_page([], 0)
    whole_page = wire.file_list_page(listing[:2], 0)
    middle_page = wire.file_list_page(listing, offset=1, limit=1)
    assert wire.file_list_next(middle_page) == 2, "the middle page stopped saying `next`"
    assert wire.file_list_next(whole_page) is None, "a complete listing claimed a `next`"
    doc["file_list"] = {
        "listing": listing,
        "empty": {"offset": 0, "total": 0, "next": None,
                  "line": line_of(empty_page)},
        "whole": {"offset": 0, "total": 2, "next": None,
                  "line": line_of(whole_page)},
        "page": {"offset": 1, "total": 3, "next": 2,
                 "line": line_of(middle_page)},
    }

    # --- preserved alert data -----------------------------------------------
    alerts = {
        "type": wire.MSG_ALERTS,
        "ready": True,
        "segments": 7,
        "pressure": f32(0.625),
        "rate_floor_s": f32(60.0),
        "wraps": 11,
        "thresholds": {"pm2_5": f32(20.0)},
    }
    segment_start = dt.datetime(2026, 8, 3, 11, 4, 5, tzinfo=wire.EASTERN)
    segment_stop = segment_start + dt.timedelta(seconds=99)
    segment = {
        "type": wire.MSG_ALERT_SEGMENT,
        "seq": 42,
        "session": wire.csv_filename(dt.datetime(2026, 8, 3, 10, 0, 0,
                                                 tzinfo=wire.EASTERN)),
        "field": "pm2_5",
        "threshold": f32(20.0),
        "start": wire.iso_timestamp(segment_start),
        "stop": wire.iso_timestamp(segment_stop),
        "first": f32(21.5),
        "last": f32(19.25),
        "midpoint": f32(44.125),
        "mean": f32(33.75),
        "variance": f32(12.5),
        "minimum": f32(18.0),
        "maximum": f32(91.5),
        "points": 100,
        "first_index": 3745,
        "last_index": 3844,
        "wrap": 11,
        "has_body": True,
    }
    assert list(segment)[1:] == wire.ALERT_SEGMENT_KEYS, (
        "the vector stopped matching wire.ALERT_SEGMENT_KEYS"
    )
    reduced = dict(segment, seq=43, has_body=False)

    alert_row_values = sps30.Measurement(
        *(f32(v) for v in (21.5, 44.25, 47.0, 48.5, 100.0, 110.5, 120.25,
                           130.0, 140.75, 0.75)))
    alert_rows = [
        [
            wire.format_index(3745),
            wire.iso_timestamp(segment_start),
            *[
                (f"{v:.3f}" if i == len(wire.FIELDS) - 1 else f"{v:.2f}")
                for i, v in enumerate(vars(alert_row_values).values())
            ],
            "",
        ]
    ]
    alert_data = {
        "type": wire.MSG_ALERT_DATA,
        "seq": 42,
        "name": segment["session"],
        "columns": ["index", "timestamp"] + list(wire.FIELDS) + ["event"],
        "rows": alert_rows,
        "done": True,
    }
    doc["alerts"] = {
        "session": segment["session"],
        "start": segment["start"],
        "stop": segment["stop"],
        "default_pm2_5": f32(wire.ALERT_DEFAULT_THRESHOLDS["pm2_5"]),
        "alerts_line": line_of(alerts),
        "segment_line": line_of(segment),
        "segment_reduced_line": line_of(reduced),
        "data_line": line_of(alert_data),
    }

    # --- backing everything up and clearing the board ------------------------
    drain_state = {
        "type": wire.MSG_DRAIN_STATE,
        "state": "offered",
        "message": "",
        "block": 17,
        "kind": wire.DRAIN_KIND_RING,
        "final": False,
        "rows": 328,
        "digest": "0a1b2c3d",
        "blocks_left": 461,
        "blocks_freed": 99,
        "blocks_kept": 0,
        "blocks_refused": 0,
        "readings_copied": 32472,
        "segments_copied": 6,
        "readings_freed": 32144,
    }
    assert drain_state["state"] in wire.DRAIN_STATES, "the vector left wire.DRAIN_STATES"

    drain_when = dt.datetime(2026, 8, 3, 11, 4, 5, tzinfo=wire.EASTERN)
    drain_session = wire.csv_filename(dt.datetime(2026, 8, 3, 10, 0, 0,
                                                  tzinfo=wire.EASTERN))
    drain_values = sps30.Measurement(
        *(f32(v) for v in (21.5, 44.25, 47.0, 48.5, 100.0, 110.5, 120.25,
                           130.0, 140.75, 0.75)))
    drain_cells = [
        (f"{v:.3f}" if i == len(wire.FIELDS) - 1 else f"{v:.2f}")
        for i, v in enumerate(vars(drain_values).values())
    ]
    drain_data = {
        "type": wire.MSG_DRAIN_DATA,
        "block": 17,
        "kind": wire.DRAIN_KIND_RING,
        "columns": list(wire.DRAIN_READING_COLUMNS),
        "rows": [
            [drain_session, wire.format_index(3745),
             wire.iso_timestamp(drain_when), *drain_cells, "", "", ""],
            [wire.rel_session_name(7, 1234000), wire.format_index(3746), "",
             *drain_cells, "settling", "7", "1235009"],
        ],
        "done": True,
    }
    drain_segments = {
        "type": wire.MSG_DRAIN_DATA,
        "block": 18,
        "kind": wire.DRAIN_KIND_SEGMENTS,
        "columns": list(wire.DRAIN_SEGMENT_COLUMNS),
        "rows": [
            ["42", drain_session, "pm2_5", repr(f32(20.0)),
             str(int(drain_when.timestamp())),
             str(int(drain_when.timestamp()) + 99),
             repr(f32(21.5)), repr(f32(19.25)), repr(f32(44.125)),
             repr(f32(33.75)), repr(f32(12.5)), repr(f32(18.0)),
             repr(f32(91.5)), "100", "3745", "3844", "11", "1"],
        ],
        "done": True,
    }
    doc["drain"] = {
        "kinds": {"body": wire.DRAIN_KIND_BODY,
                  "segments": wire.DRAIN_KIND_SEGMENTS,
                  "ring": wire.DRAIN_KIND_RING},
        "drain_states": list(wire.DRAIN_STATES),
        "session": drain_session,
        "timestamp": wire.iso_timestamp(drain_when),
        "start_s": int(drain_when.timestamp()),
        "state_line": line_of(drain_state),
        "data_line": line_of(drain_data),
        "segments_line": line_of(drain_segments),
    }

    # --- the raw console ----------------------------------------------------
    raw_request = sps30.build_frame(0x00, bytes([0x01, 0x03])).hex()
    ack = bytes([0x00, 0x00, 0x00, 0x00])
    raw_response = (
        bytes([sps30.START])
        + sps30.stuff(ack + bytes([sps30.checksum(ack)]))
        + bytes([sps30.STOP])
    ).hex()
    stuffed = sps30.build_frame(0xD0, bytes([0x7E, 0x7D, 0x11, 0x13])).hex()
    assert "7d5e" in stuffed, "the stuffed vector stopped exercising escaping"
    doc["raw"] = {
        "request_hex": raw_request,
        "response_hex": raw_response,
        "stuffed_hex": stuffed,
        "response_line": line_of({
            "type": wire.MSG_RAW,
            "request": raw_request,
            "response": raw_response,
        }),
    }

    # --- relative time ------------------------------------------------------
    anchors = [
        wire.anchor(854, 12_000, 1_754_170_975_000, -240),
        wire.anchor(854, 3_612_400, 1_754_174_575_400, -240),
        wire.anchor(855, 8_000, 1_754_180_000_000, -300),
    ]
    doc["time_map"] = {
        "boot": 855,
        "anchors": anchors,
        "line": line_of({"type": wire.MSG_TIME_MAP, "boot": 855,
                         "anchors": anchors}),
    }

    return json.dumps(doc, indent=2, allow_nan=False) + "\n"


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if a != "--check"]
    check = "--check" in argv[1:]
    out_path = pathlib.Path(args[0]) if args else DEFAULT_OUT

    text = build()
    if check:
        if not out_path.is_file():
            print(f"MISSING: {out_path} does not exist; run this script to create it")
            return 1
        if out_path.read_text() != text:
            print(f"STALE: {out_path} does not match wire.py; "
                  f"regenerate with: python3 {pathlib.Path(argv[0]).name}")
            return 1
        print(f"fresh: {out_path} matches wire.py")
        return 0

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(text)
    doc = json.loads(text)
    print(
        f"wrote {out_path} from {REPO_ROOT / 'wire.py'}: "
        f"{len(doc['readings'])} reading lines, {len(doc['times'])} instants, "
        f"{len(doc['indices'])} indices, {len(doc['profiles'])} profiles, "
        f"{len(text)} bytes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
