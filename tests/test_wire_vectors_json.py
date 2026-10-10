"""The committed JSON wire vectors are fresh, strict, and self-consistent.

`vectors/wire_vectors.json` is the wire contract in a form a second platform
can be held to by machine - an XCTest or a Kotlin/Native test loads it and
asserts its implementation produces or parses exactly these bytes. That only
works while the committed file actually is what `wire.py` produces, so the
standard pack regenerates it in memory here and compares byte for byte: edit
`wire.py` without rerunning `tools/gen_wire_vectors_json.py` and this pack
goes red rather than letting the two contracts drift.
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import struct
import sys

import pytest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
GENERATOR = REPO_ROOT / "tools" / "gen_wire_vectors_json.py"
VECTORS = REPO_ROOT / "vectors" / "wire_vectors.json"

sys.path.insert(0, str(REPO_ROOT))
import wire  # noqa: E402


@pytest.fixture(scope="module")
def generator():
    spec = importlib.util.spec_from_file_location("gen_wire_vectors_json", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    sys.modules["gen_wire_vectors_json"] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def doc():
    return json.loads(VECTORS.read_text())


def test_committed_vectors_match_wire_py(generator):
    """Byte for byte, because the file itself is the cross-platform artifact.

    A consumer on another platform vendors these bytes; a regenerated file that
    differed only in formatting would still be a different artifact. The fix
    for a red here is never to edit the JSON - it is
    `python3 tools/gen_wire_vectors_json.py`.
    """
    assert VECTORS.is_file(), (
        f"{VECTORS} is missing; run python3 {GENERATOR.relative_to(REPO_ROOT)}"
    )
    assert VECTORS.read_text() == generator.build(), (
        "vectors/wire_vectors.json is stale against wire.py; regenerate with "
        "python3 tools/gen_wire_vectors_json.py"
    )


def test_the_file_is_strict_json():
    """No NaN/Infinity literals: Swift's JSONDecoder and kotlinx would refuse them.

    Python's own encoder emits them by default, so this is asserted rather than
    assumed - the generator carries specials as strings, and this is what fails
    if that discipline slips.
    """

    def no_constants(name):
        raise AssertionError(f"non-standard JSON constant in vectors: {name}")

    json.loads(VECTORS.read_text(), parse_constant=no_constants)


def test_every_line_decodes_and_reencodes_to_the_same_bytes(doc):
    """Each `line` is a frame `wire.py` itself round-trips exactly.

    `wire.encode(wire.decode(line))` reproducing the line proves both that the
    vector is a genuine frame (not hand-typed) and that key order in the file
    is the key order the daemon emits - which is what the C encoder is held to
    and what a byte-comparing consumer needs.
    """
    lines = [r["line"] for r in doc["readings"]]
    lines.append(doc["relative_reading"]["line"])
    lines += [c["line"] for c in doc["clean"]]
    lines += [doc["profile_line"], doc["raw"]["response_line"],
              doc["time_map"]["line"],
              doc["storage_map"]["small"]["line"],
              doc["storage_map"]["full"]["line"],
              doc["alerts"]["alerts_line"], doc["alerts"]["segment_line"],
              doc["alerts"]["segment_reduced_line"], doc["alerts"]["data_line"],
              doc["drain"]["state_line"], doc["drain"]["data_line"],
              doc["drain"]["segments_line"],
              doc["file_list"]["empty"]["line"],
              doc["file_list"]["whole"]["line"],
              doc["file_list"]["page"]["line"]]
    assert len(lines) > 30
    for line in lines:
        assert line.endswith("\n"), line
        assert wire.encode(wire.decode(line)) == line.encode("utf-8")


def test_values_hex_is_the_bit_pattern_of_values(doc):
    """The decimal and the bit pattern in each reading are the same float32.

    A consumer may parse either; the two disagreeing would mean one of them
    lies, so they are held together here.
    """
    vectors = doc["readings"] + [doc["relative_reading"]]
    for vector in vectors:
        for value, hexbits in zip(vector["values"], vector["values_hex"]):
            assert struct.pack(">f", value).hex() == hexbits, vector.get("name")


def test_cells_vectors_are_what_wire_py_packs(doc):
    """The packing vectors round-trip through the reference implementation."""
    for case in doc["cells"]:
        states = [int(ch) for ch in case["states"]]
        assert wire.pack_page_states(states) == case["cells"]
        assert wire.unpack_page_states(case["cells"], len(states)) == states


def test_storage_map_sections_agree_with_their_line(doc):
    """The unpacked states in the file are the cells the line carries."""
    for which in ("small", "full"):
        vector = doc["storage_map"][which]
        message = wire.decode(vector["line"])
        assert message["pages"] == vector["pages"]
        for stated, sent in zip(vector["sections"], message["sections"]):
            assert stated["id"] == sent["id"]
            assert stated["head"] == sent["head"]
            assert stated["wraps"] == sent["wraps"]
            unpacked = wire.unpack_page_states(sent["cells"], sent["pages"])
            assert "".join(str(s) for s in unpacked) == stated["states"]
