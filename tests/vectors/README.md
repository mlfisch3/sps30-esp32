# The wire contract, as vectors any platform can be held to

`wire_vectors.json` is the SPS30 JSONL wire protocol as data: every message
vocabulary word, every column list, and a set of exact frames produced by the
real `wire.py` over deliberately awkward inputs.
It exists so that a second platform's implementation - Swift, Kotlin/Native, or
anything else - can be verified against the protocol by machine rather than by
someone remembering to copy strings.

## Where it comes from, and what keeps it honest

- `wire.py` (repository root) is the single source of truth for the protocol.
  Nothing in the JSON is typed by hand; every expected string was produced by
  calling `wire.py`'s own functions.
- `tools/gen_wire_vectors_json.py` generates this file.
  It imports its case tables from `firmware/test/gen_wire_vectors.py` - the
  same cases the firmware's C mirror is held to - so the two artifacts cannot
  drift apart on coverage.
  Regenerate with: `python3 tools/gen_wire_vectors_json.py`.
- `tests/test_wire_vectors_json.py` (standard Python pack) regenerates the file
  in memory and compares byte for byte, so a `wire.py` change that is not
  followed by regeneration turns the pack red.
  Never edit the JSON by hand; the freshness test will refuse it.
- `apk/core/.../wire/WireVectorFileTest.kt` is the Kotlin consumer: it loads
  this file (path in the `WIRE_VECTORS_JSON` environment variable, set by the
  Gradle build) and asserts the Kotlin client parses these exact bytes back to
  the values `wire.py` was given, bit patterns included.
  An iOS XCTest is expected to consume the same file the same way.

## Encoding conventions

Also stated in the file's own `meta.encoding`:

- `line` values are exact JSONL frames as `wire.py` emits them, trailing
  newline included. Byte-compare against them; do not re-serialize and hope.
- `values` are float32-promoted doubles (shortest round-trip decimal), and
  `values_hex` beside them is the big-endian IEEE-754 binary32 bit pattern as
  8 hex digits. A correct double parser recovers the same bits from either.
- The file is strict JSON. Inputs that are IEEE specials are the strings
  `"NaN"`, `"Infinity"`, `"-Infinity"`; every expected *output* is finite by
  construction, so a consumer only meets these on the input side.
- Per-page storage states are digit strings, one page per character, values
  0-3 (`page_states` names them).

## Scope

The file pins what `wire.py` defines: server-emitted frames, the message and
command vocabulary, column orders, pure name/index functions, and the
normalization bounds.
It does not pin the SPS30 UART codec (that is `firmware/test/gen_vectors.py`'s
job, from `sps30.py`) or the power model (`gen_power_vectors.py`, from
`power.py`) - those are controller-side and a phone client only renders their
results.
