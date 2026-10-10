#!/usr/bin/env python3
"""Generate C test vectors by running the real protocol module, ``wire.py``.

The same argument as ``gen_vectors.py``, one layer up. ``components/wire`` is a
port of the encoding half of ``wire.py``, and the strongest available check is
not a test somebody invented but a byte-for-byte comparison against the module
the Raspberry Pi daemon and the Android app have been agreeing through for
months.

So: this imports the real ``wire.py`` from the repository root, calls its real
``encode``/``measurement_row``/``iso_timestamp``/``csv_filename``/
``format_index``, and writes what they produce into ``wire_vectors_generated.h``.
``test_wire.c`` then asserts the C emits exactly those bytes.

Two details make that comparison meaningful rather than decorative:

* **The floats are float32 values**, round-tripped through ``struct`` before
  Python ever sees them, so the double the JSON encoder formats is exactly the
  one the C gets when it promotes ``float`` to ``double``. Without that the two
  would be comparing different numbers and the test would be measuring
  ``struct.pack``.
* **The timestamps carry their UTC offset explicitly.** ``wire.py`` resolves
  ``America/New_York`` with ``zoneinfo``; the C is pure and takes the offset as
  an argument. Exporting the offset Python resolved is what lets the two be
  compared without shipping a tzdata to the firmware, and the cases straddle
  both changeovers so the daylight-saving arithmetic is actually exercised.

Usage:  python3 gen_wire_vectors.py [output.h]   (default: wire_vectors_generated.h)
"""
from __future__ import annotations

import datetime as dt
import json
import math
import pathlib
import struct
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

sys.path.insert(0, str(HERE))
sys.path.insert(0, str(REPO_ROOT))

# Reuses the pyserial stub and the real sps30.py loader, so there is one place
# that knows how to import the reference implementation.
import gen_vectors  # noqa: E402

import wire  # noqa: E402  (the repository's wire.py, the protocol source of truth)

sps30 = gen_vectors.sps30


def f32(x: float) -> float:
    """The double a C ``float`` promotes to, so both sides format one number."""
    return struct.unpack(">f", struct.pack(">f", x))[0]


def c_double(value: float) -> str:
    """A C ``double`` literal for [value], including the ones ``repr`` cannot spell.

    ``repr(float("nan"))`` is ``nan``, which no C compiler accepts. Needed because
    the fan-clean vectors deliberately carry a non-finite case: that is precisely
    where the two mirrors had drifted apart, and a vector table that cannot
    express the input cannot catch it.
    """
    if math.isnan(value):
        return "NAN"
    if math.isinf(value):
        return "INFINITY" if value > 0 else "-INFINITY"
    return repr(value)


def c_string(s: str) -> str:
    """A C string literal for [s]. JSON is mostly quotes, so this matters."""
    out = ['"']
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\r":
            out.append("\\r")
        elif 0x20 <= ord(ch) < 0x7F:
            out.append(ch)
        else:
            out.append(f"\\x{ord(ch):02x}")
    out.append('"')
    return "".join(out)


def bits(value: float) -> int:
    """Native-endian bit pattern of the float32 nearest [value]."""
    return struct.unpack("<I", struct.pack("<f", f32(value)))[0]


# --- the cases --------------------------------------------------------------

# Ten-value sets chosen for what they break, not for variety:
#   clean air        every value zero - the encoder must emit "0.0", not "0"
#   plausible indoor what the sensor actually reads in this room
#   full scale       the datasheet's ceiling
#   awkward binaries thirds and repeating fractions, where a shortest-repr
#                    encoder and a fixed-precision one visibly disagree
#   integral         values that are exactly integers in binary, the case a
#                    naive "%g" gets wrong by dropping the ".0" Python emits
MEASUREMENTS = {
    "clean air": [0.0] * 10,
    "plausible indoor": [1.62, 1.91, 2.03, 2.11, 10.94, 12.85, 13.11, 13.16, 13.17, 0.63],
    "datasheet full scale": [1000.0, 1000.0, 1000.0, 1000.0, 3000.0, 3000.0, 3000.0,
                             3000.0, 3000.0, 10.0],
    "awkward binaries": [1 / 3, 2 / 3, 1 / 7, 1e-5, 1.0000001, 0.1, 0.2, 0.3, 1e6, 1e-38],
    "integral": [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0],
}

# Instants that matter for the zone, not arbitrary ones. wire.py records in
# America/New_York, so the two changeovers and the values either side of them
# are where an offset bug lives.
INSTANTS = [
    ("winter, EST", dt.datetime(2026, 1, 15, 9, 30, 0)),
    ("summer, EDT", dt.datetime(2026, 8, 1, 15, 45, 12)),
    ("an hour before spring forward", dt.datetime(2026, 3, 8, 1, 0, 0)),
    ("an hour after spring forward", dt.datetime(2026, 3, 8, 3, 30, 0)),
    ("the morning of fall back", dt.datetime(2026, 11, 1, 3, 0, 0)),
    ("new year's eve, one second to midnight", dt.datetime(2026, 12, 31, 23, 59, 59)),
    ("a leap day", dt.datetime(2024, 2, 29, 12, 0, 0)),
    ("the epoch, seen from here", dt.datetime(1969, 12, 31, 19, 0, 0)),
]

EVENTS = ["", "settling", "startup"]


def main(argv: list[str]) -> int:
    out_path = pathlib.Path(argv[1]) if len(argv) > 1 else HERE / "wire_vectors_generated.h"

    parts: list[str] = [
        "/* Generated by gen_wire_vectors.py from the repository's wire.py.\n"
        " * Do not edit. Regenerate with: make -C firmware/test wire-vectors\n"
        " *\n"
        " * Every expected string in this file was produced by calling the real\n"
        " * wire.py - the module the Pi daemon and the Android app agree through.\n"
        " */\n"
        "#pragma once\n\n"
        "#include <stddef.h>\n#include <stdint.h>\n\n",
    ]

    # --- reading lines ------------------------------------------------------
    reading_rows: list[tuple[str, str | None, int, str, str, list[float], str]] = []
    for case_index, (name, values) in enumerate(MEASUREMENTS.items()):
        for instant_index, (_zone_name, naive) in enumerate(INSTANTS[:3]):
            when = naive.replace(tzinfo=wire.EASTERN)
            event = EVENTS[(case_index + instant_index) % len(EVENTS)]
            index = 1 + case_index * 7919 + instant_index
            csv = wire.csv_filename(when)
            measurement = sps30.Measurement(*[f32(v) for v in values])
            row = wire.measurement_row(index, when, measurement, event)
            line = wire.encode({"type": wire.MSG_READING, "csv": csv, **row})
            reading_rows.append(
                (f"{name} / {wire.iso_timestamp(when)}", csv, index,
                 wire.iso_timestamp(when), event, values, line.decode("utf-8"))
            )

    # One with no session yet: the daemon sends a JSON null for csv before the
    # first file is open, and a client models it as a nullable.
    when = INSTANTS[1][1].replace(tzinfo=wire.EASTERN)
    measurement = sps30.Measurement(*[f32(v) for v in MEASUREMENTS["plausible indoor"]])
    row = wire.measurement_row(4242, when, measurement, "")
    reading_rows.append(
        ("no session yet (csv is null)", None, 4242, wire.iso_timestamp(when), "",
         MEASUREMENTS["plausible indoor"],
         wire.encode({"type": wire.MSG_READING, "csv": None, **row}).decode("utf-8"))
    )

    for i, (_n, _csv, _idx, _ts, _ev, values, _line) in enumerate(reading_rows):
        packed = ", ".join(f"0x{bits(v):08x}u" for v in values)
        parts.append(f"static const uint32_t reading_{i}_values[10] = {{ {packed} }};\n")
    parts.append("\n")

    parts.append(
        "typedef struct {\n"
        "    const char *name;\n"
        "    const char *csv;      /* NULL where wire.py sent a JSON null */\n"
        "    uint32_t index;\n"
        "    const char *timestamp;\n"
        "    const char *event;\n"
        "    const uint32_t *values; /* ten native-endian float bit patterns */\n"
        "    const char *expected;   /* the exact line wire.py emitted */\n"
        "} wire_reading_vector_t;\n\n"
        "static const wire_reading_vector_t WIRE_READING_VECTORS[] = {\n"
    )
    for i, (name, csv, index, ts, event, _values, line) in enumerate(reading_rows):
        csv_literal = c_string(csv) if csv is not None else "NULL"
        parts.append(
            f"    {{ {c_string(name)}, {csv_literal}, {index}u, {c_string(ts)},"
            f" {c_string(event)}, reading_{i}_values, {c_string(line)} }},\n"
        )
    parts.append("};\n\n")

    # --- timestamps and session names --------------------------------------
    parts.append(
        "typedef struct {\n"
        "    const char *name;\n"
        "    int64_t epoch_s;\n"
        "    int utc_offset_s;   /* what zoneinfo resolved for this instant */\n"
        "    int utc_offset_min; /* the same offset in the unit that gets stored */\n"
        "    const char *iso;    /* wire.iso_timestamp */\n"
        "    const char *csv;    /* wire.csv_filename */\n"
        "} wire_time_vector_t;\n\n"
        "static const wire_time_vector_t WIRE_TIME_VECTORS[] = {\n"
    )
    for name, naive in INSTANTS:
        when = naive.replace(tzinfo=wire.EASTERN)
        offset = when.utcoffset()
        assert offset is not None
        offset_s = int(offset.total_seconds())
        # Both units, because both are used and crossing them is the mistake
        # these vectors exist to catch: `wire.iso_timestamp` and
        # `wire.csv_filename` are given seconds, while every field that records
        # an offset - the page header, the `time_map` anchor, the app's
        # `utcOffsetMinutes` - carries minutes. Asserted whole here so a zone
        # with seconds in its offset would fail the generator rather than round
        # silently on the C side.
        assert offset_s % 60 == 0, (name, offset_s)
        parts.append(
            f"    {{ {c_string(name)}, {int(when.timestamp())}LL,"
            f" {offset_s}, {offset_s // 60}, {c_string(wire.iso_timestamp(when))},"
            f" {c_string(wire.csv_filename(when))} }},\n"
        )
    parts.append("};\n\n")

    # --- zero-padded indices ------------------------------------------------
    index_cases = [0, 1, 9, 10, 99, 1000, 9999999, 10000000, 4294967295]
    parts.append(
        "typedef struct {\n    uint32_t index;\n    const char *expected;\n"
        "} wire_index_vector_t;\n\n"
        "static const wire_index_vector_t WIRE_INDEX_VECTORS[] = {\n"
    )
    for value in index_cases:
        parts.append(f"    {{ {value}u, {c_string(wire.format_index(value))} }},\n")
    parts.append("};\n\n")

    # --- the vocabulary -----------------------------------------------------
    # Asserting the constants rather than trusting them: a message type that
    # differs by one character is a client that silently ignores the message.
    constants = {
        "PY_DEFAULT_PORT": wire.DEFAULT_PORT,
        "PY_MAX_CLIENTS": wire.MAX_CLIENTS,
        "PY_INDEX_WIDTH": wire.INDEX_WIDTH,
    }
    for key, value in constants.items():
        parts.append(f"#define {key} {value}\n")
    parts.append(f"#define PY_CONTROL_TTL_SECONDS {wire.CONTROL_TTL_SECONDS}\n\n")

    strings = {
        "PY_MSG_HELLO": wire.MSG_HELLO,
        "PY_MSG_READING": wire.MSG_READING,
        "PY_MSG_STATE": wire.MSG_STATE,
        "PY_MSG_ERROR": wire.MSG_ERROR,
        "PY_MSG_PROFILE": wire.MSG_PROFILE,
        "PY_MSG_CONTROL": wire.MSG_CONTROL,
        "PY_MSG_CLIENTS": wire.MSG_CLIENTS,
        "PY_MSG_POWER": wire.MSG_POWER,
        "PY_MSG_FILES": wire.MSG_FILES,
        "PY_MSG_RAW": wire.MSG_RAW,
        "PY_CMD_PAUSE": wire.CMD_PAUSE,
        "PY_CMD_RESUME": wire.CMD_RESUME,
        "PY_CMD_CLEAN": wire.CMD_CLEAN,
        "PY_CMD_RESTART": wire.CMD_RESTART,
        "PY_CMD_RAW": wire.CMD_RAW,
        "PY_CMD_LIST_FILES": wire.CMD_LIST_FILES,
        "PY_CMD_GET_FILE": wire.CMD_GET_FILE,
        "PY_CMD_SET_PROFILE": wire.CMD_SET_PROFILE,
        "PY_CMD_SHUTDOWN": wire.CMD_SHUTDOWN,
        "PY_CMD_IDENTIFY": wire.CMD_IDENTIFY,
        "PY_CMD_CLAIM": wire.CMD_CLAIM,
        "PY_CMD_RELEASE": wire.CMD_RELEASE,
        "PY_CMD_BACKUP": wire.CMD_BACKUP,
        "PY_CMD_SET_TIME": wire.CMD_SET_TIME,
        "PY_MSG_TIME_MAP": wire.MSG_TIME_MAP,
        "PY_COL_REL_BOOT": wire.COL_REL_BOOT,
        "PY_COL_REL_MS": wire.COL_REL_MS,
        "PY_MODE_CONTINUOUS": wire.MODE_CONTINUOUS,
        "PY_MODE_DUTY_CYCLE": wire.MODE_DUTY_CYCLE,
        "PY_HELLO_SUPPORTS_SLEEP": wire.HELLO_SUPPORTS_SLEEP,
        "PY_HELLO_CAN_POWER_OFF": wire.HELLO_CAN_POWER_OFF,
        "PY_HELLO_HAS_CLOCK": wire.HELLO_HAS_CLOCK,
        "PY_POWER_CLEAN_DUE_HOURS": wire.POWER_CLEAN_DUE_HOURS,
        # The advertisement. These are the names an app that has never seen this
        # controller has to guess right in order to find it at all, so they are
        # held to wire.py like every other word of the vocabulary.
        "PY_MDNS_SERVICE": wire.MDNS_SERVICE,
        "PY_MDNS_PROTO": wire.MDNS_PROTO,
        "PY_MDNS_HOSTNAME": wire.MDNS_HOSTNAME,
        "PY_MDNS_INSTANCE": wire.MDNS_INSTANCE,
    }
    for key, value in strings.items():
        parts.append(f"#define {key} {c_string(value)}\n")
    parts.append("\n")

    # The TXT records, in order and in full, so that a key added on one side and
    # not the other fails here rather than on a LAN. The count is exported as
    # well as the table: "and nothing more" is half the specification, and a
    # third key would otherwise pass every assertion about the first two.
    parts.append(
        "/* wire.MDNS_TXT: exactly these keys, in this order, and no others. */\n"
        f"#define PY_MDNS_TXT_COUNT {len(wire.MDNS_TXT)}\n"
        "static const struct { const char *key; const char *value; } PY_MDNS_TXT[] = {\n"
    )
    for key, value in wire.MDNS_TXT:
        parts.append(f"    {{ {c_string(key)}, {c_string(value)} }},\n")
    parts.append("};\n\n")

    # The CSV/wire column order, from wire.py, so a reorder fails the build.
    parts.append(
        "/* wire.FIELDS, the ten values in contract order. */\n"
        "static const char *const PY_WIRE_FIELDS[10] = {\n    "
        + ", ".join(c_string(f) for f in wire.FIELDS)
        + ",\n};\n\n"
    )

    # --- the raw console ----------------------------------------------------
    # A genuine exchange rather than an invented pair of strings: the frame
    # sps30.py sends for Start-Measurement, and the acknowledgement the sensor
    # answers with, both built by sps30.py's own framing.
    #
    # A third frame carries a payload byte that has to be escaped, because the
    # console's whole promise is that byte-stuffing survives the round trip
    # untouched - and a vector made only of frames that need no escaping is
    # exactly the vector that would not notice if it did not.
    raw_request = sps30.build_frame(0x00, bytes([0x01, 0x03])).hex()
    ack = bytes([0x00, 0x00, 0x00, 0x00])  # addr, cmd, state=0, len=0
    raw_response = (
        bytes([sps30.START])
        + sps30.stuff(ack + bytes([sps30.checksum(ack)]))
        + bytes([sps30.STOP])
    ).hex()
    stuffed = sps30.build_frame(0xD0, bytes([0x7E, 0x7D, 0x11, 0x13])).hex()
    assert "7d5e" in stuffed, "the stuffed vector stopped exercising escaping"
    parts.append(
        "/* wire.MSG_RAW, encoded exactly as the daemon's _cmd_raw sends it. */\n"
        f"#define PY_RAW_REQUEST_HEX {c_string(raw_request)}\n"
        f"#define PY_RAW_RESPONSE_HEX {c_string(raw_response)}\n"
        "/* A frame whose payload needs every SHDLC escape. */\n"
        f"#define PY_RAW_STUFFED_HEX {c_string(stuffed)}\n"
        "#define PY_RAW_RESPONSE_LINE "
        + c_string(
            wire.encode(
                {
                    "type": wire.MSG_RAW,
                    "request": raw_request,
                    "response": raw_response,
                }
            ).decode("utf-8")
        )
        + "\n\n"
    )

    # --- a listing longer than one frame ------------------------------------
    # `file_list` carries where its page sits and where the rest begin, because a
    # board holds more sessions than a frame can name and a session nobody can
    # name is one nobody can fetch, plot or erase. wire.file_list_page is the
    # definition; these pin the C encoder to the exact bytes it produces, key
    # order included, for all three shapes that matter: nothing stored, the whole
    # listing in one frame, and a page with more to come.
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
    parts.append(
        "/* wire.file_list_page: an empty listing, a whole one, and one page of\n"
        "   three with the rest still to come. */\n"
        f"#define PY_FILES_OFFSET {c_string(wire.FILES_OFFSET)}\n"
        f"#define PY_FILES_TOTAL {c_string(wire.FILES_TOTAL)}\n"
        f"#define PY_FILES_NEXT {c_string(wire.FILES_NEXT)}\n"
        "#define PY_FILE_LIST_EMPTY_LINE "
        + c_string(wire.encode(empty_page).decode("utf-8"))
        + "\n#define PY_FILE_LIST_WHOLE_LINE "
        + c_string(wire.encode(whole_page).decode("utf-8"))
        + "\n#define PY_FILE_LIST_PAGE_LINE "
        + c_string(wire.encode(middle_page).decode("utf-8"))
        + "\n\n"
    )

    # --- preserved alert data -----------------------------------------------
    # The three messages the controller uses to hand over what it kept when the
    # ring wrapped. wire.py names the types and fixes the key order
    # (ALERT_SEGMENT_KEYS); this pins the C to exactly the bytes wire.py's own
    # encoder produces for them, key order included, so the app cannot be built
    # against one shape and the firmware emit another.
    alerts = {
        "type": wire.MSG_ALERTS,
        "ready": True,
        "segments": 7,
        "pressure": f32(0.625),
        "rate_floor_s": f32(60.0),
        "wraps": 11,
        "thresholds": {"pm2_5": f32(20.0)},
    }
    # A segment mid-excursion, and one whose samples the area has reduced away -
    # which is the case the header exists for, so it is a vector and not a
    # comment.
    segment_start = dt.datetime(2026, 8, 3, 11, 4, 5, tzinfo=wire.EASTERN)
    segment_stop = segment_start + dt.timedelta(seconds=99)
    segment = {
        "type": wire.MSG_ALERT_SEGMENT,
        "seq": 42,
        "session": wire.csv_filename(dt.datetime(2026, 8, 3, 10, 0, 0, tzinfo=wire.EASTERN)),
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
    assert list(segment)[1:] == ["type"][1:] + wire.ALERT_SEGMENT_KEYS, (
        "the vector stopped matching wire.ALERT_SEGMENT_KEYS"
    )
    reduced = dict(segment, seq=43, has_body=False)

    # The rows, in the shape file_data uses, because they are readings of a real
    # session and the client folds them in through the same path.
    alert_row_values = sps30.Measurement(
        *(f32(v) for v in (21.5, 44.25, 47.0, 48.5, 100.0, 110.5, 120.25, 130.0, 140.75, 0.75))
    )
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
    parts.append(
        "/* Preserved alert data: wire.MSG_ALERTS / MSG_ALERT_SEGMENT / MSG_ALERT_DATA. */\n"
        f"#define PY_MSG_ALERTS {c_string(wire.MSG_ALERTS)}\n"
        f"#define PY_MSG_ALERT_SEGMENT {c_string(wire.MSG_ALERT_SEGMENT)}\n"
        f"#define PY_MSG_ALERT_DATA {c_string(wire.MSG_ALERT_DATA)}\n"
        f"#define PY_CMD_GET_ALERTS {c_string(wire.CMD_GET_ALERTS)}\n"
        f"#define PY_CMD_SET_ALERTS {c_string(wire.CMD_SET_ALERTS)}\n"
        "/* The line a board with no phone preserves against. */\n"
        f"#define PY_ALERT_DEFAULT_PM2_5 {c_double(f32(wire.ALERT_DEFAULT_THRESHOLDS['pm2_5']))}\n"
        "#define PY_ALERTS_LINE "
        + c_string(wire.encode(alerts).decode("utf-8"))
        + "\n#define PY_ALERT_SEGMENT_LINE "
        + c_string(wire.encode(segment).decode("utf-8"))
        + "\n/* The same segment once its samples have been reduced away. */\n"
        "#define PY_ALERT_SEGMENT_REDUCED_LINE "
        + c_string(wire.encode(reduced).decode("utf-8"))
        + "\n#define PY_ALERT_DATA_LINE "
        + c_string(wire.encode(alert_data).decode("utf-8"))
        + "\n#define PY_ALERT_SESSION "
        + c_string(str(segment["session"]))
        + "\n#define PY_ALERT_START "
        + c_string(str(segment["start"]))
        + "\n#define PY_ALERT_STOP "
        + c_string(str(segment["stop"]))
        + "\n\n"
    )

    # --- backing everything up and clearing the board ------------------------
    # The two messages of the drain, pinned to the bytes wire.py's own encoder
    # produces. This one matters more than most: its rows are what a device
    # computes a digest over, and the controller frees flash on the strength of
    # that digest matching. A cell that lost a digit between the two
    # implementations would fail a copy that is perfectly complete - or, in the
    # direction that costs data, agree about the wrong thing.
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
    drain_session = wire.csv_filename(dt.datetime(2026, 8, 3, 10, 0, 0, tzinfo=wire.EASTERN))
    drain_values = sps30.Measurement(
        *(f32(v) for v in (21.5, 44.25, 47.0, 48.5, 100.0, 110.5, 120.25, 130.0, 140.75, 0.75))
    )
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
            # A dated reading: no board clock, so the last two cells are empty.
            [drain_session, wire.format_index(3745), wire.iso_timestamp(drain_when),
             *drain_cells, "", "", ""],
            # And one recorded with no clock at all, which carries the board's
            # own clock instead and no instant. One block can hold pages of both
            # kinds, which is why there is one column list rather than two.
            [wire.rel_session_name(7, 1234000), wire.format_index(3746), "",
             *drain_cells, "settling", "7", "1235009"],
        ],
        "done": True,
    }

    # A block of preserved segment summaries, which is the part of "everything"
    # that lives in another region entirely and is where this feature could most
    # easily have skipped data in silence.
    drain_segments = {
        "type": wire.MSG_DRAIN_DATA,
        "block": 18,
        "kind": wire.DRAIN_KIND_SEGMENTS,
        "columns": list(wire.DRAIN_SEGMENT_COLUMNS),
        "rows": [
            ["42", drain_session, "pm2_5", repr(f32(20.0)),
             str(int(drain_when.timestamp())), str(int(drain_when.timestamp()) + 99),
             repr(f32(21.5)), repr(f32(19.25)), repr(f32(44.125)), repr(f32(33.75)),
             repr(f32(12.5)), repr(f32(18.0)), repr(f32(91.5)),
             "100", "3745", "3844", "11", "1"],
        ],
        "done": True,
    }

    parts.append(
        "/* The drain: wire.MSG_DRAIN_STATE / MSG_DRAIN_DATA. */\n"
        f"#define PY_MSG_DRAIN_STATE {c_string(wire.MSG_DRAIN_STATE)}\n"
        f"#define PY_MSG_DRAIN_DATA {c_string(wire.MSG_DRAIN_DATA)}\n"
        f"#define PY_CMD_DRAIN_BEGIN {c_string(wire.CMD_DRAIN_BEGIN)}\n"
        f"#define PY_CMD_DRAIN_NEXT {c_string(wire.CMD_DRAIN_NEXT)}\n"
        f"#define PY_CMD_DRAIN_CONFIRM {c_string(wire.CMD_DRAIN_CONFIRM)}\n"
        f"#define PY_CMD_DRAIN_CANCEL {c_string(wire.CMD_DRAIN_CANCEL)}\n"
        f"#define PY_DRAIN_KIND_BODY {c_string(wire.DRAIN_KIND_BODY)}\n"
        f"#define PY_DRAIN_KIND_SEGMENTS {c_string(wire.DRAIN_KIND_SEGMENTS)}\n"
        f"#define PY_DRAIN_KIND_RING {c_string(wire.DRAIN_KIND_RING)}\n"
        "#define PY_DRAIN_STATE_LINE "
        + c_string(wire.encode(drain_state).decode("utf-8"))
        + "\n#define PY_DRAIN_DATA_LINE "
        + c_string(wire.encode(drain_data).decode("utf-8"))
        + "\n#define PY_DRAIN_SEGMENTS_LINE "
        + c_string(wire.encode(drain_segments).decode("utf-8"))
        + f"\n#define PY_DRAIN_SESSION {c_string(drain_session)}\n"
        + f"#define PY_DRAIN_TIMESTAMP {c_string(wire.iso_timestamp(drain_when))}\n"
        + f"#define PY_DRAIN_START_S {int(drain_when.timestamp())}LL\n"
    )
    parts.append(
        "/* wire.DRAIN_READING_COLUMNS and wire.DRAIN_SEGMENT_COLUMNS, in order. */\n"
        "static const char *const PY_DRAIN_READING_COLUMNS[] = {\n    "
        + ", ".join(c_string(k) for k in wire.DRAIN_READING_COLUMNS)
        + ",\n};\n"
        "static const char *const PY_DRAIN_SEGMENT_COLUMNS[] = {\n    "
        + ", ".join(c_string(k) for k in wire.DRAIN_SEGMENT_COLUMNS)
        + ",\n};\n"
        "static const char *const PY_DRAIN_STATES[] = {\n    "
        + ", ".join(c_string(k) for k in wire.DRAIN_STATES)
        + ",\n};\n\n"
    )

    # --- the storage occupancy map ------------------------------------------
    # `storage_map` is one cell per flash page, so its cells string is the one
    # payload on this wire whose size is fixed by the chip's geometry rather
    # than by what happened to be recorded. Three layers are pinned here:
    # the 2-bit packing itself (PY_CELLS_VECTORS), a small frame a reader can
    # eyeball (PY_STORAGE_MAP_LINE), and a frame at the real 4,992-page
    # geometry (PY_STORAGE_MAP_FULL_LINE) - the last because "it fits the
    # controller's big buffer" is a claim about the full-size frame and only a
    # full-size vector can hold the C to it.
    cells_cases = [
        # The worked example from wire.py's own docstring: 0b11_10_00_01.
        [wire.PAGE_STATE_DATA, wire.PAGE_STATE_EMPTY,
         wire.PAGE_STATE_ANCHOR, wire.PAGE_STATE_BAD],
        # One page, so three quarters of the byte is padding.
        [wire.PAGE_STATE_BAD],
        # Five pages: a full byte then a partial one, and base64 padding.
        [wire.PAGE_STATE_DATA] * 5,
        # Empty flash of an awkward length.
        [wire.PAGE_STATE_EMPTY] * 7,
    ]
    for states in cells_cases:
        assert wire.unpack_page_states(wire.pack_page_states(states),
                                       len(states)) == states
    parts.append(
        "/* wire.pack_page_states: 2-bit states, four pages a byte, base64. */\n"
        "typedef struct {\n"
        "    size_t pages;\n"
        "    const uint8_t *states;\n"
        "    const char *expected;\n"
        "} wire_cells_vector_t;\n\n"
    )
    for i, states in enumerate(cells_cases):
        packed = ", ".join(str(s) for s in states)
        parts.append(f"static const uint8_t cells_{i}_states[] = {{ {packed} }};\n")
    parts.append("static const wire_cells_vector_t WIRE_CELLS_VECTORS[] = {\n")
    for i, states in enumerate(cells_cases):
        parts.append(
            f"    {{ {len(states)}u, cells_{i}_states,"
            f" {c_string(wire.pack_page_states(states))} }},\n"
        )
    parts.append("};\n\n")

    # A board a few hours into its first pass: readable at a glance, and the
    # heads and wrap counts are small enough to check by hand.
    small_map = wire.storage_map_message(512, 20, [
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
    ])

    # The real geometry, mid-eighth-pass: the erase-ahead sector sits empty
    # just past the head, anchors are sprinkled the way reconnects sprinkle
    # them, and one page is corrupt. The pattern is arithmetic (not random)
    # so the C side can rebuild the same board from the same three rules.
    def full_ring_state(i: int) -> int:
        if 1200 <= i < 1208:  # the erased sector ahead of the head
            return wire.PAGE_STATE_EMPTY
        if i == 4000:
            return wire.PAGE_STATE_BAD
        if i % 500 == 137:
            return wire.PAGE_STATE_ANCHOR
        return wire.PAGE_STATE_DATA

    full_map = wire.storage_map_message(512, 4992, [
        {"id": wire.MAP_SECTION_RING, "start": 0, "pages": 4480, "head": 1200,
         "wraps": 7, "states": [full_ring_state(i) for i in range(4480)]},
        {"id": wire.MAP_SECTION_ALERT_HEADERS, "start": 4480, "pages": 128,
         "head": 9, "wraps": 0,
         "states": [wire.PAGE_STATE_DATA] * 9 + [wire.PAGE_STATE_EMPTY] * 119},
        {"id": wire.MAP_SECTION_ALERT_BODIES, "start": 4608, "pages": 384,
         "head": 37, "wraps": 0,
         "states": [wire.PAGE_STATE_DATA] * 37 + [wire.PAGE_STATE_EMPTY] * 347},
    ])
    full_line = wire.encode(full_map)
    # The claim the fixed-size encoding exists to keep: the whole partition,
    # sections and all, inside the controller's 8 KiB big buffer.
    assert len(full_line) < 8192, f"full-size storage_map is {len(full_line)} B"

    parts.append(
        "/* wire.storage_map_message: a young board, and the real geometry\n"
        "   mid-eighth-pass. The full-size line is the buffer-fit proof. */\n"
        f"#define PY_MSG_STORAGE_MAP {c_string(wire.MSG_STORAGE_MAP)}\n"
        f"#define PY_CMD_STORAGE_MAP {c_string(wire.CMD_STORAGE_MAP)}\n"
        f"#define PY_MAP_SECTION_RING {c_string(wire.MAP_SECTION_RING)}\n"
        f"#define PY_MAP_SECTION_ALERT_HEADERS {c_string(wire.MAP_SECTION_ALERT_HEADERS)}\n"
        f"#define PY_MAP_SECTION_ALERT_BODIES {c_string(wire.MAP_SECTION_ALERT_BODIES)}\n"
        f"#define PY_PAGE_STATE_EMPTY {wire.PAGE_STATE_EMPTY}\n"
        f"#define PY_PAGE_STATE_DATA {wire.PAGE_STATE_DATA}\n"
        f"#define PY_PAGE_STATE_ANCHOR {wire.PAGE_STATE_ANCHOR}\n"
        f"#define PY_PAGE_STATE_BAD {wire.PAGE_STATE_BAD}\n"
        "#define PY_STORAGE_MAP_LINE "
        + c_string(wire.encode(small_map).decode("utf-8"))
        + "\n#define PY_STORAGE_MAP_FULL_LINE "
        + c_string(full_line.decode("utf-8"))
        + "\n\n"
    )

    # --- fan-clean accounting -----------------------------------------------
    # The controller reports how long the fan has left before a clean, and the
    # figure has to be the daemon's to the last digit: the app shows one number
    # and the user must not get a different answer depending on which controller
    # is on the other end. So the C is held against what wire.py actually
    # returns, for the whole payload rather than just the arithmetic.
    clean_cases = [
        0.0,          # nothing measured yet: a whole week to go
        1.0,          # one reading; rounds back to the full week
        359.9,        # just under the reporting granularity
        360.0,        # exactly a tenth of an hour
        3600.0,       # an hour
        302400.0,     # half a week
        604799.0,     # a second short
        604800.0,     # due now
        1000000.0,    # overdue; floors at zero rather than going negative
        1234.5,       # nothing round about it
        # A corrupt stored count. The only input that is not arithmetic but a
        # decision about which way to fail, and the one the two mirrors used to
        # answer in opposite directions - Python "due now", the firmware "a full
        # week left". It is here so the cross-check can see it.
        float("nan"),
        float("-inf"),  # the one that used to escape as `inf`, which is not JSON
    ]
    parts.append(f"#define PY_CLEAN_AFTER_SECONDS {wire.CLEAN_AFTER_SECONDS!r}\n\n")
    parts.append(
        "/* wire.clean_due_in_hours, and the `power` line carrying it. */\n"
        "typedef struct {\n"
        "    double run_seconds;\n"
        "    double expected_hours;\n"
        "    const char *expected_line;\n"
        "} wire_clean_vector_t;\n\n"
        "static const wire_clean_vector_t WIRE_CLEAN_VECTORS[] = {\n"
    )
    for run_seconds in clean_cases:
        hours = wire.clean_due_in_hours(run_seconds)
        payload = {
            "source": "usb",
            "method": "none",
            wire.POWER_CLEAN_DUE_HOURS: hours,
        }
        line = wire.encode({"type": wire.MSG_POWER, "power": payload}).decode("utf-8")
        parts.append(f"    {{ {c_double(run_seconds)}, {c_double(hours)}, {c_string(line)} }},\n")
    parts.append("};\n\n")

    # --- the default profile, as hello and profile carry it -----------------
    parts.append(
        "/* wire.DEFAULT_PROFILE, encoded as the daemon encodes a profile message. */\n"
        f"#define PY_PROFILE_LINE {c_string(wire.encode({'type': wire.MSG_PROFILE, 'profile': dict(wire.DEFAULT_PROFILE)}).decode('utf-8'))}\n\n"
    )

    # --- relative time ------------------------------------------------------
    # `wire.csv_columns` decides a session's header from its name, and the C
    # decides the same thing from `wire_file_data_open`'s `relative` flag. The
    # two column lists are pinned here so the frame the board sends and the file
    # a phone writes cannot drift apart - which is exactly how a clockless
    # session came to be backed up with nothing in it that could place it.
    columns_json = json.dumps(wire.CSV_COLUMNS, separators=(",", ":"))
    columns_rel_json = json.dumps(wire.CSV_COLUMNS_RELATIVE, separators=(",", ":"))
    assert wire.csv_columns("SPS30-20260803_014500.csv") == wire.CSV_COLUMNS
    assert wire.csv_columns("SPS30-R00000356-00001925.csv") == wire.CSV_COLUMNS_RELATIVE
    parts.append(
        "/* wire.CSV_COLUMNS and wire.CSV_COLUMNS_RELATIVE, as `file_data`\n"
        " * declares them. The relative form is the classic one with the board's\n"
        " * own clock appended, so a reader that has never heard of the two extra\n"
        " * columns finds the same thirteen in the same thirteen places. */\n"
        f"#define PY_CSV_COLUMNS_JSON {c_string(columns_json)}\n"
        f"#define PY_CSV_COLUMNS_RELATIVE_JSON {c_string(columns_rel_json)}\n"
        "/* The same two as a CSV header line, which is what a file starts with. */\n"
        f"#define PY_CSV_HEADER {c_string(','.join(wire.CSV_COLUMNS))}\n"
        f"#define PY_CSV_HEADER_RELATIVE {c_string(','.join(wire.CSV_COLUMNS_RELATIVE))}\n\n"
    )

    # `set_time` is not exercised as bytes here because the controller only ever
    # reads it, but `time_map` and the relative session name are things the C
    # emits, and both are pinned to the Python that defines them.
    rel_cases = [(0, 0), (1, 6437), (854, 3_612_400), (0x7FFFFFFF, 0xFFFFFFFF)]
    parts.append(
        "/* wire.rel_session_name: the session key when there was no clock. */\n"
        "typedef struct {\n    uint32_t run;\n    uint32_t start_ms;\n"
        "    const char *expected;\n} wire_rel_name_vector_t;\n\n"
        "static const wire_rel_name_vector_t WIRE_REL_NAME_VECTORS[] = {\n"
    )
    for run, start in rel_cases:
        parts.append(f"    {{ {run}u, {start}u, "
                     f"{c_string(wire.rel_session_name(run, start))} }},\n")
    parts.append("};\n\n")

    anchors = [
        wire.anchor(854, 12_000, 1_754_170_975_000, -240),
        wire.anchor(854, 3_612_400, 1_754_174_575_400, -240),
        wire.anchor(855, 8_000, 1_754_180_000_000, -300),
    ]
    time_map = wire.encode({"type": wire.MSG_TIME_MAP, "boot": 855, "anchors": anchors})
    parts.append(
        "/* One `time_map`, exactly as wire.py encodes it. The controller sends the\n"
        " * raw equivalences and does no conversion: that is what keeps the client's\n"
        " * conversion reversible. */\n"
        f"#define PY_TIME_MAP_RUN 855u\n"
        f"#define PY_TIME_MAP_COUNT {len(anchors)}\n"
        f"#define PY_TIME_MAP_LINE {c_string(time_map.decode('utf-8'))}\n\n"
        "typedef struct {\n    uint32_t run;\n    uint32_t rel_ms;\n"
        "    int64_t epoch_ms;\n    int16_t utc_offset_min;\n"
        "} wire_anchor_vector_t;\n\n"
        "static const wire_anchor_vector_t WIRE_ANCHOR_VECTORS[] = {\n"
        + "".join(f"    {{ {a['boot']}u, {a['rel_ms']}u, {a['epoch_ms']}LL, "
                  f"{a['utc_offset_min']} }},\n" for a in anchors)
        + "};\n\n"
    )

    # A reading from a clockless session: no timestamp, and the board's own clock
    # in its place. Encoded by the real wire.py, so the C cannot drift from it.
    rel_measurement = sps30.Measurement(*[f32(v) for v in MEASUREMENTS["plausible indoor"]])
    rel_row = wire.measurement_row(17, INSTANTS[1][1].replace(tzinfo=wire.EASTERN),
                                   rel_measurement, "")
    rel_row["timestamp"] = ""
    rel_line = wire.encode({
        "type": wire.MSG_READING,
        "csv": wire.rel_session_name(854, 6437),
        **rel_row,
        wire.COL_REL_BOOT: 854,
        wire.COL_REL_MS: 21_437,
    })
    packed = ", ".join(f"0x{bits(v):08x}u" for v in MEASUREMENTS["plausible indoor"])
    parts.append(
        "/* A reading with no clock behind it. `timestamp` is empty on purpose:\n"
        " * a fabricated one would be read as an instant everywhere downstream. */\n"
        f"static const uint32_t WIRE_REL_READING_VALUES[10] = {{ {packed} }};\n"
        f"#define WIRE_REL_READING_CSV {c_string(wire.rel_session_name(854, 6437))}\n"
        "#define WIRE_REL_READING_INDEX 17u\n"
        "#define WIRE_REL_READING_RUN 854u\n"
        "#define WIRE_REL_READING_MS 21437u\n"
        f"#define WIRE_REL_READING_LINE {c_string(rel_line.decode('utf-8'))}\n"
    )

    # --- normalizing a profile off the wire ---------------------------------
    # `acq_profile_normalize` is the firmware's copy of `wire.normalize_profile`
    # and the two have to agree exactly, because they are the same defence: a
    # profile that only one of them bounds is a controller that can be told to
    # stop recording while it goes on looking healthy. The cases below are the
    # ends rather than the middle - each ceiling, each floor, the interaction
    # between `cycle_s` and the work it has to hold, and the values that are not
    # numbers at all.
    profile_cases = [
        {},                                              # nothing given: the default
        {"mode": "duty_cycle"},                          # a mode and nothing else
        {"mode": "sabotage"},                            # an unknown mode falls back
        {"interval": 10.0},                              # an ordinary request
        {"interval": wire.INTERVAL_MAX},                 # exactly at the ceiling
        {"interval": wire.INTERVAL_MAX + 1},             # one past it
        {"interval": 1e9},                               # the silent-stop interval
        {"interval": 0.0},                               # not usable: the default
        {"interval": -5.0},                              # nor is a negative one
        {"cycle_s": 86400.0},                            # the app's longest offer
        {"cycle_s": wire.CYCLE_MAX + 1.0},               # one past the ceiling
        {"cycle_s": 86400000.0},                         # "a thousand days"
        {"warmup_s": 1.0},                               # under Sensirion's floor
        {"warmup_s": wire.WARMUP_MAX + 100.0},           # over the ceiling
        {"average_s": wire.AVERAGE_MAX * 10},            # over the ceiling
        # Every field maximal at once: the case where the cycle floor and the
        # cycle ceiling could contradict each other if the bounds were chosen
        # carelessly. warmup + average must still fit inside one cycle.
        {
            "mode": "duty_cycle",
            "interval": 1e12,
            "cycle_s": 1e12,
            "warmup_s": 1e12,
            "average_s": 1e12,
        },
        # A short cycle with real work in it: the floor raises the cycle, and
        # that is not clamping, it is the invariant.
        {"cycle_s": 10.0, "warmup_s": 30.0, "average_s": 30.0},
        # Not numbers. NaN takes the default because there is no end to clamp it
        # to; an infinity clamps because what it asks for is unambiguous.
        {"interval": float("nan"), "cycle_s": float("nan")},
        {"interval": float("inf"), "cycle_s": float("inf")},
        {"interval": float("-inf")},
        {"interval": "not a number", "cycle_s": None},   # junk straight off the wire
    ]
    parts.append(
        f"#define PY_INTERVAL_MAX_S {c_double(wire.INTERVAL_MAX)}\n"
        f"#define PY_CYCLE_MAX_S {c_double(wire.CYCLE_MAX)}\n"
        f"#define PY_WARMUP_MAX_S {c_double(wire.WARMUP_MAX)}\n"
        f"#define PY_AVERAGE_MAX_S {c_double(wire.AVERAGE_MAX)}\n"
        f"#define PY_WARMUP_MIN_S {c_double(wire.WARMUP_MIN)}\n\n"
        "/* wire.normalize_profile: what a client asked for, and what it gets. */\n"
        "typedef struct {\n"
        "    const char *description;\n"
        "    int mode_in;   /* -1 = not supplied, -2 = supplied but unknown */\n"
        "    double interval_in, cycle_in, warmup_in, average_in;  /* 0 = not supplied */\n"
        "    int mode_out;\n"
        "    double interval_out, cycle_out, warmup_out, average_out;\n"
        "} wire_profile_vector_t;\n\n"
        "static const wire_profile_vector_t WIRE_PROFILE_VECTORS[] = {\n"
    )
    mode_codes = {wire.MODE_CONTINUOUS: 0, wire.MODE_DUTY_CYCLE: 1}

    # "not supplied" and "supplied as junk" reach the C as the same thing: a
    # field the C caller left at zero. That is exactly what `net_server.c` does
    # with a key `wire_json_double` could not read, so the vectors exercise the
    # real path rather than a convenient one.
    def given(case: dict, key: str) -> float:
        value = case.get(key, 0.0)
        return float(value) if isinstance(value, (int, float)) else 0.0

    for case in profile_cases:
        got = wire.normalize_profile(case)
        mode_in = -1 if "mode" not in case else mode_codes.get(case["mode"], -2)
        parts.append(
            "    { "
            + c_string(json.dumps(case, default=str))
            + f", {mode_in}, {c_double(given(case, 'interval'))}, {c_double(given(case, 'cycle_s'))}, "
            + f"{c_double(given(case, 'warmup_s'))}, {c_double(given(case, 'average_s'))}, "
            + f"{mode_codes[got['mode']]}, {c_double(got['interval'])}, {c_double(got['cycle_s'])}, "
            + f"{c_double(got['warmup_s'])}, {c_double(got['average_s'])} }},\n"
        )
    parts.append("};\n")

    out_path.write_text("".join(parts))
    print(
        f"wrote {out_path} from {REPO_ROOT / 'wire.py'}: "
        f"{len(reading_rows)} reading lines, {len(INSTANTS)} instants, "
        f"{len(index_cases)} indices, {len(strings) + len(constants) + 1} constants"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
