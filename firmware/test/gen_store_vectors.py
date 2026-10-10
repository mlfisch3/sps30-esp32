#!/usr/bin/env python3
"""Generate C test vectors by running the real store format, ``store.py``.

The same argument as ``gen_vectors.py`` and ``gen_wire_vectors.py``:
``components/store/store_codec.c`` is a port of ``store.py``, and the strongest
available check is not a test somebody invented but a byte-for-byte comparison
against the module that is the specification.

So this imports the real ``store.py``, packs real readings through its real
``PageWriter``, and writes the sealed pages into ``store_vectors_generated.h``.
``test_store.c`` then asserts the C emits exactly those bytes, and separately
that it decodes Python's bytes back to the same values.

Two details make that comparison meaningful rather than decorative:

* **The values are float32**, round-tripped through ``struct`` before Python
  sees them, so both sides quantise the identical number. Without that the two
  would be comparing different values and the test would be measuring
  ``struct.pack``.
* **The cases include the awkward ones**: a settling run that opens and closes,
  a time correction past the tolerance, a negative delta, a value large enough
  to need a two-byte varint, and a page filled right to its limit. Those are the
  paths where a port drifts.

Usage:  python3 gen_store_vectors.py [output.h]   (default: store_vectors_generated.h)
"""
from __future__ import annotations

import pathlib
import struct
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

sys.path.insert(0, str(HERE))
sys.path.insert(0, str(REPO_ROOT))

import store  # noqa: E402


def f32(value: float) -> float:
    """`value` as the float32 the C will hold, so both sides see one number."""
    return struct.unpack("<f", struct.pack("<f", value))[0]


#: The session every case is packed against. Fixed, so the vectors are stable.
SESSION = 7
SESSION_START_MS = 1_754_170_963_000  # 2026-08-02T17:22:43-04:00
CADENCE_MS = 1000
UTC_OFFSET_MIN = -240


def case_steady() -> list[store.Reading]:
    """Ordinary indoor air: small positive and negative deltas, no markers."""
    out = []
    pm = 3.10
    for i in range(1, 33):
        pm += 0.02 if i % 3 else -0.05
        values = [pm, pm + 0.18, pm + 0.18, pm + 0.18,
                  pm * 6.6, pm * 7.9, pm * 7.9, pm * 7.9, pm * 7.9, 0.46]
        out.append(store.Reading(i, [f32(v) for v in values],
                                 SESSION_START_MS + (i - 1) * CADENCE_MS))
    return out


def case_settling() -> list[store.Reading]:
    """A settling run that starts at the first reading and ends part way in."""
    out = []
    for i in range(1, 13):
        pm = 12.0 - i * 0.4
        values = [pm, pm, pm, pm, pm * 6, pm * 7, pm * 7, pm * 7, pm * 7, 0.51]
        out.append(store.Reading(i, [f32(v) for v in values],
                                 SESSION_START_MS + (i - 1) * CADENCE_MS,
                                 store.SETTLING_EVENT if i <= 5 else ""))
    return out


def case_time_break() -> list[store.Reading]:
    """A dropout: reading 6 arrives four seconds late, past the tolerance."""
    out = []
    for i in range(1, 11):
        pm = 2.0 + i * 0.01
        values = [pm, pm, pm, pm, pm * 6, pm * 7, pm * 7, pm * 7, pm * 7, 0.44]
        drift = 4000 if i >= 6 else 0
        out.append(store.Reading(i, [f32(v) for v in values],
                                 SESSION_START_MS + (i - 1) * CADENCE_MS + drift))
    return out


def case_big_deltas() -> list[store.Reading]:
    """A smoke event: deltas well past one byte of varint, and negatives."""
    out = []
    pm = 4.0
    for i in range(1, 17):
        pm = pm * 1.7 if i % 2 else pm * 0.55
        values = [pm, pm * 1.1, pm * 1.2, pm * 1.2,
                  pm * 6.0, pm * 7.1, pm * 7.2, pm * 7.2, pm * 7.2, 0.30 + i * 0.01]
        out.append(store.Reading(i, [f32(v) for v in values],
                                 SESSION_START_MS + (i - 1) * CADENCE_MS))
    return out


def case_fills_page() -> list[store.Reading]:
    """Enough readings that the page fills and a second one opens."""
    out = []
    pm = 1.0
    for i in range(1, 121):
        pm += 0.01
        values = [pm, pm, pm, pm, pm * 6, pm * 7, pm * 7, pm * 7, pm * 7, 0.40]
        out.append(store.Reading(i, [f32(v) for v in values],
                                 SESSION_START_MS + (i - 1) * CADENCE_MS))
    return out


CASES = [
    ("steady", case_steady()),
    ("settling", case_settling()),
    ("time_break", case_time_break()),
    ("big_deltas", case_big_deltas()),
    ("fills_page", case_fills_page()),
]

# --- relative time ----------------------------------------------------------
#
# A board with no clock counts on its own. `REL_RUN` is the run marker that
# distinguishes this stretch of running from the one before the last reboot, and
# `REL_START_MS` is where the board's millisecond counter stood when the session
# opened. The two are packed into the same header field that carries an epoch on
# an absolute page, which is what lets the per-page rebase, the delta arithmetic
# and the time markers all carry over untouched.

REL_RUN = 854
REL_START_MS = 6_437
REL_SESSION = 1
REL_SESSION_START = store.wire.rel_pack(REL_RUN, REL_START_MS)


def case_relative() -> list[store.Reading]:
    """A clockless session, including a dropout that still needs a time marker.

    The point of the case is that nothing about the *inside* of a page changes
    when its origin becomes relative: the cadence break at reading 7 must still
    produce a `MARK_TIME`, measured against the page's own first reading.
    """
    out = []
    pm = 2.4
    for i in range(1, 21):
        pm += 0.03 if i % 4 else -0.07
        values = [pm, pm + 0.2, pm + 0.2, pm + 0.2,
                  pm * 6.5, pm * 7.8, pm * 7.8, pm * 7.8, pm * 7.8, 0.48]
        drift = 3000 if i >= 7 else 0
        out.append(store.Reading(i, [f32(v) for v in values],
                                 REL_SESSION_START + (i - 1) * CADENCE_MS + drift,
                                 store.SETTLING_EVENT if i <= 3 else ""))
    return out


#: The equivalences an anchor page is built from. Three, because one connection
#: is the easy case and the interesting behaviour - a reader picking the nearest
#: - needs more than one. The epochs are real instants; the run marker is shared
#: with the readings above, because an equivalence from another run says nothing
#: about this one.
ANCHORS = [
    store.Anchor(store.wire.rel_pack(REL_RUN, 12_000), 1_754_170_975_000, UTC_OFFSET_MIN),
    store.Anchor(store.wire.rel_pack(REL_RUN, 3_612_400), 1_754_174_575_400, UTC_OFFSET_MIN),
    store.Anchor(store.wire.rel_pack(REL_RUN + 1, 8_000), 1_754_180_000_000, UTC_OFFSET_MIN),
]


def c_bytes(data: bytes, indent: str = "        ") -> str:
    lines = []
    for i in range(0, len(data), 12):
        chunk = ", ".join(f"0x{b:02x}" for b in data[i:i + 12])
        lines.append(indent + chunk + ",")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    out_path = pathlib.Path(argv[1]) if len(argv) > 1 else HERE / "store_vectors_generated.h"

    parts: list[str] = []
    parts.append(f"""/* GENERATED by gen_store_vectors.py from store.py - do not edit.
 *
 * Every page below was produced by the real specification, not by hand. To
 * change what is expected here, change store.py and run:
 *
 *     make -C firmware/test store-vectors
 */
#pragma once

#include <stdint.h>

#define STORE_VEC_PAGE_BYTES {store.PAGE_BYTES}
#define STORE_VEC_HEADER_BYTES {store.HEADER_BYTES}
#define STORE_VEC_FIELDS {store.FIELD_COUNT}
#define STORE_VEC_SESSION {SESSION}
#define STORE_VEC_SESSION_START_MS {SESSION_START_MS}LL
#define STORE_VEC_CADENCE_MS {CADENCE_MS}
#define STORE_VEC_UTC_OFFSET_MIN {UTC_OFFSET_MIN}

typedef struct {{
    uint32_t index;
    float values[STORE_VEC_FIELDS];
    int64_t epoch_ms;
    int settling;
}} store_vec_reading_t;

typedef struct {{
    const char *name;
    const store_vec_reading_t *readings;
    unsigned reading_count;
    const uint8_t *pages;       /* reading_count pages, PAGE_BYTES each */
    unsigned page_count;
}} store_vec_case_t;
""")

    case_entries = []
    for name, readings in CASES:
        pages = store.pack(readings, session=SESSION, cadence_ms=CADENCE_MS,
                           utc_offset_min=UTC_OFFSET_MIN)
        # The specification has to round-trip its own output before it is
        # allowed to become an expectation.
        back = list(store.unpack(pages))
        assert len(back) == len(readings), f"{name}: {len(back)} != {len(readings)}"

        rows = []
        for r in readings:
            # "%.9g" can emit "2", which is an int in C; force a decimal point
            values = ", ".join(f"{float(v):.9e}f" for v in r.values)
            rows.append(f"    {{ {r.index}, {{ {values} }}, {r.epoch_ms}LL, "
                        f"{1 if r.event else 0} }},")
        parts.append(f"\nstatic const store_vec_reading_t store_vec_{name}_readings[] = {{\n"
                     + "\n".join(rows) + "\n};\n")

        blob = b"".join(pages)
        parts.append(f"static const uint8_t store_vec_{name}_pages[] = {{\n"
                     + c_bytes(blob) + "\n};\n")
        case_entries.append(
            f'    {{ "{name}", store_vec_{name}_readings, {len(readings)}, '
            f"store_vec_{name}_pages, {len(pages)} }},")

    parts.append("\nstatic const store_vec_case_t store_vec_cases[] = {\n"
                 + "\n".join(case_entries) + "\n};\n")
    parts.append(f"\n#define STORE_VEC_CASE_COUNT {len(CASES)}\n")

    # --- the relative-time case ---------------------------------------------
    rel_readings = case_relative()
    rel_pages = store.pack(rel_readings, session=REL_SESSION, cadence_ms=CADENCE_MS,
                           utc_offset_min=UTC_OFFSET_MIN, relative=True)
    rel_back = list(store.unpack(rel_pages))
    assert len(rel_back) == len(rel_readings)
    assert all(store.decode_page(p).relative for p in rel_pages), "relative flag lost"
    # Every reading must come back with the relative clock it went in with, which
    # is the property that makes the conversion the client does reversible.
    for original, decoded in zip(rel_readings, rel_back):
        assert original.epoch_ms == decoded.epoch_ms, (original.index, decoded.epoch_ms)

    rows = []
    for r in rel_readings:
        values = ", ".join(f"{float(v):.9e}f" for v in r.values)
        rows.append(f"    {{ {r.index}, {{ {values} }}, {r.epoch_ms}LL, "
                    f"{1 if r.event else 0} }},")
    parts.append("\n/* A session recorded with no clock: the same page format with a\n"
                 " * relative origin. `store.py` produced these bytes. */\n"
                 f"#define STORE_VEC_REL_RUN {REL_RUN}u\n"
                 f"#define STORE_VEC_REL_START_MS {REL_START_MS}u\n"
                 f"#define STORE_VEC_REL_SESSION {REL_SESSION}\n"
                 f"#define STORE_VEC_REL_SESSION_START {REL_SESSION_START}LL\n"
                 f"#define STORE_VEC_REL_READING_COUNT {len(rel_readings)}\n"
                 f"#define STORE_VEC_REL_PAGE_COUNT {len(rel_pages)}\n"
                 "\nstatic const store_vec_reading_t store_vec_relative_readings[] = {\n"
                 + "\n".join(rows) + "\n};\n"
                 "static const uint8_t store_vec_relative_pages[] = {\n"
                 + c_bytes(b"".join(rel_pages)) + "\n};\n")

    # --- the anchor page ----------------------------------------------------
    # Session 0: an equivalence belongs to a *run* of the board, not to one
    # session in it. `acq` numbers sessions from 1, so zero cannot collide.
    anchor_page = store.seal_anchor_page(99, 0, store.wire.rel_pack(REL_RUN, 0),
                                         store.wire.rel_pack(REL_RUN, 3_612_400),
                                         UTC_OFFSET_MIN, ANCHORS)
    decoded = store.decode_page(anchor_page)
    assert decoded is not None and decoded.count == 0, "an anchor page must decode"
    assert [(a.rel_ms, a.epoch_ms, a.utc_offset_min) for a in decoded.anchors] == \
           [(a.rel_ms, a.epoch_ms, a.utc_offset_min) for a in ANCHORS]
    entries = ",\n".join(
        f"    {{ {a.rel_ms}LL, {a.epoch_ms}LL, {a.utc_offset_min} }}" for a in ANCHORS)
    parts.append("\n/* An equivalence page: 'my relative clock read X when the world\n"
                 " * clock read Y', appended and never rewritten. */\n"
                 f"#define STORE_VEC_ANCHOR_COUNT {len(ANCHORS)}\n"
                 f"#define STORE_VEC_ANCHOR_PAGE_SEQ 99u\n"
                 "\ntypedef struct {\n    int64_t rel_ms;\n    int64_t epoch_ms;\n"
                 "    int16_t utc_offset_min;\n} store_vec_anchor_t;\n\n"
                 "static const store_vec_anchor_t store_vec_anchors[] = {\n"
                 + entries + ",\n};\n"
                 "static const uint8_t store_vec_anchor_page[] = {\n"
                 + c_bytes(anchor_page) + "\n};\n")

    out_path.write_text("".join(parts))
    total = sum(len(r) for _, r in CASES)
    print(f"wrote {out_path} - {len(CASES)} cases, {total} readings, "
          f"plus {len(rel_readings)} relative readings in {len(rel_pages)} pages "
          f"and {len(ANCHORS)} equivalences")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
