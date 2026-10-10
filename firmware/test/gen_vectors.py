#!/usr/bin/env python3
"""Generate C test vectors by running the proven Python codec in ``sps30.py``.

This is the point of the whole test suite. The C in ``components/shdlc`` is a
port of ``sps30.py``, and the strongest available check is not a test somebody
invented but a byte-for-byte comparison against the implementation that has
actually been reading this sensor on a Raspberry Pi.

So: this script imports the real ``sps30.py`` from the repository root, calls
its real ``checksum``/``stuff``/``unstuff``/``build_frame``/``parse_response``,
and writes the results into ``vectors_generated.h``. ``test_shdlc.c`` then
asserts the C produces exactly those bytes.

``sps30.py`` imports ``pyserial`` at module scope for its transport half, which
is not installed here and is not needed for the codec half. Rather than take a
dependency for a pure-logic export, a stub module is injected first - see
``_load_sps30``. Nothing in the codec touches it; if that ever stops being true
this script will fail loudly rather than silently test a stub.

Usage:  python3 gen_vectors.py [output.h]      (default: vectors_generated.h)
"""
from __future__ import annotations

import dataclasses
import importlib.util
import pathlib
import struct
import sys
import types

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
SPS30_PY = REPO_ROOT / "sps30.py"


def _load_sps30() -> types.ModuleType:
    """Import the real sps30.py, standing in a stub for the unused pyserial."""
    if "serial" not in sys.modules:
        stub = types.ModuleType("serial")
        # Only the names sps30.py touches at import time or in class bodies.
        stub.EIGHTBITS = 8
        stub.PARITY_NONE = "N"
        stub.STOPBITS_ONE = 1
        stub.SerialException = type("SerialException", (Exception,), {})

        def _no_serial(*_args, **_kwargs):
            raise RuntimeError(
                "gen_vectors.py touched the serial transport; it must only use "
                "the pure codec functions"
            )

        stub.Serial = _no_serial
        sys.modules["serial"] = stub

    if not SPS30_PY.is_file():
        sys.exit(f"cannot find the reference implementation at {SPS30_PY}")

    spec = importlib.util.spec_from_file_location("sps30_reference", SPS30_PY)
    module = importlib.util.module_from_spec(spec)
    # @dataclass resolves its own module through sys.modules while the class
    # body executes, so register before running it.
    sys.modules["sps30_reference"] = module
    spec.loader.exec_module(module)
    return module


sps30 = _load_sps30()


# --- building the cases -----------------------------------------------------
def miso_inner(cmd: int, state: int, data: bytes) -> bytes:
    """Assemble the un-stuffed body of a MISO frame, the way the device does.

    sps30.py only ever *parses* these, so there is no reference builder to
    borrow. The checksum still comes from ``sps30.checksum``, and every frame
    built here is fed back through ``sps30.parse_response`` before it is
    emitted, so a mistake in this function cannot reach the C tests.
    """
    payload = bytes([sps30.ADDR, cmd, state, len(data)]) + data
    return payload + bytes([sps30.checksum(payload)])


def miso_frame(cmd: int, state: int, data: bytes) -> bytes:
    """A complete MISO wire frame: delimiters and byte-stuffing applied."""
    return bytes([sps30.START]) + sps30.stuff(miso_inner(cmd, state, data)) + bytes([sps30.STOP])


def find_data_with_escaped_checksum(cmd: int, escaped_byte: int) -> bytes:
    """Find MOSI data whose *checksum* is one of the four escaped bytes.

    A checksum landing on 0x7E, 0x7D, 0x11 or 0x13 has to be stuffed like any
    other byte. It is the easiest case to get wrong - a port that stuffs only
    the data and appends the raw checksum passes every ordinary test and fails
    here - so each of the four is generated explicitly.
    """
    for probe in range(256):
        data = bytes([probe])
        payload = bytes([sps30.ADDR, cmd, len(data)]) + data
        if sps30.checksum(payload) == escaped_byte:
            return data
    raise AssertionError(f"no single-byte data yields checksum 0x{escaped_byte:02x}")


ALL_ESCAPED = bytes([0x7E, 0x7D, 0x11, 0x13])

# The commands the driver actually sends, with the arguments it sends them with.
mosi_cases: list[tuple[str, int, bytes]] = [
    ("start_measurement", sps30.CMD_START, bytes([0x01, 0x03])),
    ("stop_measurement", sps30.CMD_STOP, b""),
    ("read_measurement", sps30.CMD_READ, b""),
    ("sleep", sps30.CMD_SLEEP, b""),
    ("wake", sps30.CMD_WAKE, b""),
    ("fan_clean", sps30.CMD_CLEAN, b""),
    ("product_type", sps30.CMD_INFO, bytes([0x00])),
    ("serial_number", sps30.CMD_INFO, bytes([0x03])),
    ("firmware_version", sps30.CMD_VERSION, b""),
    ("read_status", sps30.CMD_STATUS, b""),
    ("reset", sps30.CMD_RESET, b""),
    ("auto_clean_read", sps30.CMD_AUTO_CLEAN, bytes([0x00])),
    ("auto_clean_set_604800", sps30.CMD_AUTO_CLEAN, bytes([0x00]) + (604800).to_bytes(4, "big")),
    # Stuffing stress: every escaped byte in one payload, in both orders.
    ("data_all_four_escaped", sps30.CMD_AUTO_CLEAN, ALL_ESCAPED),
    ("data_all_four_escaped_reversed", sps30.CMD_AUTO_CLEAN, ALL_ESCAPED[::-1]),
    ("data_escaped_adjacent_pairs", sps30.CMD_AUTO_CLEAN, ALL_ESCAPED * 2),
    # A maximum-length payload containing every possible byte value.
    ("data_max_length_255", sps30.CMD_AUTO_CLEAN, bytes(range(1, 256))),
    ("data_empty", sps30.CMD_AUTO_CLEAN, b""),
]

# The four checksum-is-an-escaped-byte cases.
for _b in ALL_ESCAPED:
    _d = find_data_with_escaped_checksum(sps30.CMD_AUTO_CLEAN, _b)
    mosi_cases.append((f"checksum_is_0x{_b:02x}", sps30.CMD_AUTO_CLEAN, _d))

# Responses the device sends back. The first two are the first milestone: the
# real serial number and firmware of the sensor on the bench.
_measurement = struct.pack(
    ">10f", 1.25, 2.5, 3.75, 4.0, 12.5, 20.0, 21.5, 22.0, 22.5, 0.65
)

miso_cases: list[tuple[str, int, int, bytes]] = [
    ("serial_number_E3164D2544338487", sps30.CMD_INFO, 0x00, b"E3164D2544338487\x00"),
    ("firmware_version_2_1", sps30.CMD_VERSION, 0x00, bytes([0x02, 0x01, 0x00, 0x07, 0x00, 0x00, 0x00])),
    ("product_type_00080000", sps30.CMD_INFO, 0x00, b"00080000\x00"),
    ("measurement_40_bytes", sps30.CMD_READ, 0x00, _measurement),
    ("measurement_not_ready_empty", sps30.CMD_READ, 0x00, b""),
    ("stop_measurement_ack", sps30.CMD_STOP, 0x00, b""),
    # Non-zero device states: the codec must decode these cleanly and leave the
    # verdict to the caller, exactly as parse_response does.
    ("error_state_0x43_wrong_state", sps30.CMD_START, 0x43, b""),
    ("error_state_0x02_unknown_cmd", 0xFF, 0x02, b""),
    # A response whose data needs un-stuffing.
    ("data_needs_unstuffing", sps30.CMD_INFO, 0x00, ALL_ESCAPED),
    ("max_length_response", sps30.CMD_INFO, 0x00, bytes(range(1, 256))),
]

# Raw stuff/unstuff round trips, independent of any framing.
stuff_cases: list[tuple[str, bytes]] = [
    ("empty", b""),
    ("no_escapes", bytes([0x00, 0x01, 0x42, 0xFF])),
    ("all_four_escaped", ALL_ESCAPED),
    ("all_four_escaped_repeated", ALL_ESCAPED * 4),
    ("every_byte_value", bytes(range(256))),
    ("only_0x7e", bytes([0x7E] * 8)),
    ("only_0x7d", bytes([0x7D] * 8)),
    ("only_0x11", bytes([0x11] * 8)),
    ("only_0x13", bytes([0x13] * 8)),
]


# --- measurement decoding ---------------------------------------------------
# Ten big-endian float32 in the fixed field order. The C decode is checked
# against sps30.decode_measurement() bit pattern for bit pattern, which is a
# stronger claim than comparing printed decimals: it catches a byte-order slip
# that happens to round-trip through six significant figures.
def _pack(*values: float) -> bytes:
    assert len(values) == 10
    return struct.pack(">10f", *values)


# A payload whose bytes contain all four that must be escaped, so stuffing and
# float decoding are exercised together rather than only separately. Built by
# hand rather than searched for: 0x3F/0x40 leading bytes keep every value an
# ordinary number near 1-4, and the escaped bytes go in the mantissa where they
# cannot change that.
def _payload_with_escaped_bytes() -> bytes:
    data = (
        bytes([0x3F, 0x7E, 0x7D, 0x11])
        + bytes([0x3F, 0x13, 0x11, 0x7D])
        + bytes([0x40, 0x7E, 0x11, 0x13])
        + bytes([0x40, 0x7D, 0x13, 0x7E])
        + struct.pack(">6f", 12.5, 20.0, 21.5, 22.0, 22.5, 0.65)
    )
    assert len(data) == 40
    assert {0x7E, 0x7D, 0x11, 0x13} <= set(data), "escaped bytes missing"
    assert all(v == v and abs(v) < 1e9 for v in struct.unpack(">10f", data))
    return data


measurement_cases: list[tuple[str, bytes]] = [
    # The vector the MISO table already carries, decoded here as well.
    ("typical", _pack(1.25, 2.5, 3.75, 4.0, 12.5, 20.0, 21.5, 22.0, 22.5, 0.65)),
    # Clean air: every field zero. Also checks that 0.0 is not confused with
    # "no measurement ready", which is an empty payload, not a zero one.
    ("all_zero", _pack(*([0.0] * 10))),
    # Plausible indoor air, the range this bench actually reads.
    ("indoor", _pack(1.02, 1.18, 1.24, 1.27, 6.94, 8.03, 8.19, 8.22, 8.23, 0.48)),
    # Full scale: the datasheet's 1000 ug/m3 mass and 3000 #/cm3 number limits.
    ("full_scale", _pack(1000.0, 1000.0, 1000.0, 1000.0, 3000.0, 3000.0, 3000.0,
                         3000.0, 3000.0, 10.0)),
    # Awkward binary representations: values with long mantissas, a very small
    # denormal-adjacent value, and one that is exactly a power of two.
    ("awkward", _pack(0.1, 0.2, 0.3, 1.0 / 3.0, 1e-30, 65536.0, 3.4028235e38,
                      1.1754944e-38, 0.5, 1e-7)),
    # Every byte of the payload distinct, so a transposition cannot hide. The
    # values are denormals, which is exactly why it is a good decode test.
    ("ramp", bytes(range(40))),
    ("contains_escaped_bytes", _payload_with_escaped_bytes()),
]


# --- emitting ---------------------------------------------------------------
def c_bytes(name: str, data: bytes) -> str:
    """A C array definition. Zero-length arrays are illegal in C, so pad one
    element and rely on the accompanying length being 0."""
    body = ", ".join(f"0x{b:02x}" for b in data) if data else "0x00"
    lines = []
    chunk = []
    for token in body.split(", "):
        chunk.append(token)
        if len(chunk) == 12:
            lines.append("    " + ", ".join(chunk) + ",")
            chunk = []
    if chunk:
        lines.append("    " + ", ".join(chunk) + ",")
    return f"static const uint8_t {name}[] = {{\n" + "\n".join(lines) + "\n};\n"


def main(argv: list[str]) -> int:
    out_path = pathlib.Path(argv[1]) if len(argv) > 1 else HERE / "vectors_generated.h"

    parts: list[str] = []
    parts.append(
        "/*\n"
        " * GENERATED FILE - do not edit by hand.\n"
        " *\n"
        " * Produced by firmware/test/gen_vectors.py, which imports the real\n"
        " * sps30.py from the repository root and calls its real codec\n"
        " * functions. Every byte array below is what that field-proven Python\n"
        " * implementation actually emits, so test_shdlc.c comparing against\n"
        " * them is a direct cross-check of the C port against the reference.\n"
        " *\n"
        " * Regenerate with:  make -C firmware/test vectors\n"
        " */\n"
        "#pragma once\n\n"
        "#include <stddef.h>\n"
        "#include <stdint.h>\n\n"
    )

    # MOSI
    for i, (name, cmd, data) in enumerate(mosi_cases):
        frame = sps30.build_frame(cmd, data)
        # Prove the reference agrees with itself before trusting the bytes.
        inner = sps30.unstuff(frame[1:-1])
        assert frame[0] == sps30.START and frame[-1] == sps30.STOP, name
        assert inner[0] == sps30.ADDR and inner[1] == cmd and inner[3 : 3 + len(data)] == data, name
        assert sps30.checksum(inner[:-1]) == inner[-1], name
        parts.append(f"/* MOSI: {name} */\n")
        parts.append(c_bytes(f"mosi_{i}_data", data))
        parts.append(c_bytes(f"mosi_{i}_frame", frame))

    parts.append(
        "typedef struct {\n"
        "    const char *name;\n"
        "    uint8_t cmd;\n"
        "    const uint8_t *data;\n"
        "    size_t data_len;\n"
        "    const uint8_t *frame;\n"
        "    size_t frame_len;\n"
        "} mosi_vector_t;\n\n"
        "static const mosi_vector_t MOSI_VECTORS[] = {\n"
    )
    for i, (name, cmd, data) in enumerate(mosi_cases):
        frame = sps30.build_frame(cmd, data)
        parts.append(
            f'    {{ "{name}", 0x{cmd:02x}, mosi_{i}_data, {len(data)}, '
            f"mosi_{i}_frame, {len(frame)} }},\n"
        )
    parts.append("};\n\n")

    # MISO
    for i, (name, cmd, state, data) in enumerate(miso_cases):
        frame = miso_frame(cmd, state, data)
        # Round-trip through the reference parser: if sps30.py cannot read the
        # frame back, it never reaches the C tests.
        r_cmd, r_state, r_data = sps30.parse_response(sps30.unstuff(frame[1:-1]))
        assert (r_cmd, r_state, r_data) == (cmd, state, data), name
        parts.append(f"/* MISO: {name} */\n")
        parts.append(c_bytes(f"miso_{i}_frame", frame))
        parts.append(c_bytes(f"miso_{i}_data", data))

    parts.append(
        "typedef struct {\n"
        "    const char *name;\n"
        "    const uint8_t *frame;\n"
        "    size_t frame_len;\n"
        "    uint8_t cmd;\n"
        "    uint8_t state;\n"
        "    const uint8_t *data;\n"
        "    size_t data_len;\n"
        "} miso_vector_t;\n\n"
        "static const miso_vector_t MISO_VECTORS[] = {\n"
    )
    for i, (name, cmd, state, data) in enumerate(miso_cases):
        frame = miso_frame(cmd, state, data)
        parts.append(
            f'    {{ "{name}", miso_{i}_frame, {len(frame)}, 0x{cmd:02x}, '
            f"0x{state:02x}, miso_{i}_data, {len(data)} }},\n"
        )
    parts.append("};\n\n")

    # stuff / unstuff
    for i, (name, raw) in enumerate(stuff_cases):
        stuffed = sps30.stuff(raw)
        assert sps30.unstuff(stuffed) == raw, name
        parts.append(f"/* stuffing: {name} */\n")
        parts.append(c_bytes(f"stuff_{i}_raw", raw))
        parts.append(c_bytes(f"stuff_{i}_stuffed", stuffed))

    parts.append(
        "typedef struct {\n"
        "    const char *name;\n"
        "    const uint8_t *raw;\n"
        "    size_t raw_len;\n"
        "    const uint8_t *stuffed;\n"
        "    size_t stuffed_len;\n"
        "} stuff_vector_t;\n\n"
        "static const stuff_vector_t STUFF_VECTORS[] = {\n"
    )
    for i, (name, raw) in enumerate(stuff_cases):
        stuffed = sps30.stuff(raw)
        parts.append(
            f'    {{ "{name}", stuff_{i}_raw, {len(raw)}, '
            f"stuff_{i}_stuffed, {len(stuffed)} }},\n"
        )
    parts.append("};\n\n")

    # Checksums over every payload above, as a table in its own right.
    checksum_inputs = [raw for _n, raw in stuff_cases] + [d for _n, _c, d in mosi_cases]
    for i, raw in enumerate(checksum_inputs):
        parts.append(c_bytes(f"csum_{i}_input", raw))
    parts.append(
        "typedef struct {\n"
        "    const uint8_t *input;\n"
        "    size_t input_len;\n"
        "    uint8_t expected;\n"
        "} checksum_vector_t;\n\n"
        "static const checksum_vector_t CHECKSUM_VECTORS[] = {\n"
    )
    for i, raw in enumerate(checksum_inputs):
        parts.append(f"    {{ csum_{i}_input, {len(raw)}, 0x{sps30.checksum(raw):02x} }},\n")
    parts.append("};\n\n")

    # Measurement decoding, cross-checked against sps30.decode_measurement().
    # The expectations are emitted as the exact IEEE-754 bit patterns the
    # reference produced, so the C comparison is exact rather than approximate.
    parts.append(
        "/*\n"
        " * Measurement decoding. Each payload is 40 big-endian float32 as the\n"
        " * SPS30 sends them; `expected` holds the ten values as native-endian\n"
        " * IEEE-754 bit patterns, taken from sps30.decode_measurement() itself.\n"
        " * Comparing bit patterns rather than printed decimals is deliberate:\n"
        " * it catches a byte-order slip that still round-trips through six\n"
        " * significant figures.\n"
        " */\n"
    )
    for i, (name, payload) in enumerate(measurement_cases):
        decoded = sps30.decode_measurement(payload)
        assert decoded is not None, name
        fields = dataclasses.astuple(decoded)
        assert len(fields) == 10, name
        # Round-trip: re-packing the decoded values must reproduce the payload.
        assert struct.pack(">10f", *fields) == payload, name
        bits = [struct.unpack("<I", struct.pack("<f", v))[0] for v in fields]
        parts.append(f"/* measurement: {name} */\n")
        parts.append(c_bytes(f"meas_{i}_payload", payload))
        parts.append(
            f"static const uint32_t meas_{i}_expected[10] = {{\n    "
            + ", ".join(f"0x{b:08x}u" for b in bits)
            + ",\n};\n"
        )

    parts.append(
        "typedef struct {\n"
        "    const char *name;\n"
        "    const uint8_t *payload;\n"
        "    size_t payload_len;\n"
        "    const uint32_t *expected; /* ten native-endian float bit patterns */\n"
        "} measurement_vector_t;\n\n"
        "static const measurement_vector_t MEASUREMENT_VECTORS[] = {\n"
    )
    for i, (name, payload) in enumerate(measurement_cases):
        parts.append(
            f'    {{ "{name}", meas_{i}_payload, {len(payload)}, meas_{i}_expected }},\n'
        )
    parts.append("};\n\n")

    # The field order is a hard contract shared with the daemon's CSV, the wire
    # protocol and the Kotlin port. Export it from the dataclass so a reorder in
    # either language fails the build rather than silently transposing columns.
    field_names = [f.name for f in dataclasses.fields(sps30.Measurement)]
    assert len(field_names) == 10
    parts.append(
        "/* The Measurement field order, straight from sps30.py's dataclass. */\n"
        "static const char *const PYTHON_FIELD_ORDER[10] = {\n    "
        + ", ".join(f'"{n}"' for n in field_names)
        + ",\n};\n"
    )

    out_path.write_text("".join(parts))
    print(
        f"wrote {out_path} from {SPS30_PY}: "
        f"{len(mosi_cases)} MOSI, {len(miso_cases)} MISO, "
        f"{len(stuff_cases)} stuffing, {len(checksum_inputs)} checksum, "
        f"{len(measurement_cases)} measurement vectors"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
