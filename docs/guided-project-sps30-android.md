# Guided Project: Build a Networked IoT Sensor Dashboard - from a Serial Driver to a Native Android App

_A stage-by-stage reconstruction of the SENSIRION SPS30 project, mirroring the exact order it was actually built. Written for developers who are comfortable programmers but **new to Android** - each Android concept is introduced in place, and condensed background reviews live in the Appendix. Every code block is the real code from the project._

## What you'll build

A complete system: a **Python UART driver** for the Sensirion SPS30 sensor; an **always-on Raspberry Pi daemon** that owns the sensor and streams readings over TCP; a **terminal dashboard**; and finally a **native Kotlin/Jetpack-Compose Android app** that discovers the Pi on the LAN, streams live, charts it with touch gestures, keeps monitoring in the background, and exports data. You'll hit - and solve - the same real bugs we did.

## How the modules map to the real progression

- **Module 1: The Sensor and Its Language (SHDLC over UART)**
- **Module 2: Making It Testable (Refactor + a Real Test Suite)**
- **Module 3: The Always-On Daemon and a Shared Wire Contract**
- **Module 4: A Live Terminal Dashboard (Textual Client)**
- **Module 5: Separating 'What to Draw' from 'How to Draw' (Pure Chart Logic)**
- **Module 6: Shipping It: systemd on the Pi**
- **Module 7: Interim Android: Termux Direct-Connect**
- **Module 8: Going Native: The Android Toolchain and a First APK**
- **Module 9: Porting the Protocol and Networking to Kotlin**
- **Module 10: The Live Chart and Readouts**
- **Module 11: Touch Interaction: Gestures, Windows, Pin/Zoom/Pan**
- **Module 12: Surviving the Background: A Foreground Service That Actually Keeps Streaming**
- **Module 13: Polish: Responsive Layout and an Honest Chart**
- **Module 14: Feature Upgrades: Cycling Pins, an Options Menu, Config, and Export**

Appendix: condensed background reviews (serial/SHDLC, client/server, coroutines & Flow, Compose, ViewModel/StateFlow, foreground services, MediaStore, the Gradle toolchain, the adb dev loop).

---


# Module 1: The Sensor and Its Language (SHDLC over UART)

**Learning objective.** Talk to the SPS30 particulate-matter sensor from a Raspberry Pi over a serial line, and stream real measurements.

## Concepts introduced

The **Sensirion SPS30** is an optical particulate-matter sensor. It speaks **SHDLC** -
Sensirion's simple framing over a 115200-baud 8N1 serial line. A frame is delimited by
`0x7E` start/stop bytes, protects a small set of control bytes with **byte-stuffing**
(escape `0x7D`), and ends each frame with a one-byte **checksum**. On top of framing
there are a handful of commands: *start measurement*, *stop*, *read measurement*,
*device info*, *fan clean*.

You will build one flat Python module, `sps30.py`, in three layers:
1. **pure SHDLC math** - `checksum`, `stuff`/`unstuff`, `build_frame`, `parse_response`;
2. a thin **`SPS30` class** that owns the `serial.Serial` port;
3. a small **CLI** (`info`, `clean`, `stream`).

Keeping the framing math as free functions (no I/O) is a deliberate choice that pays off
two modules later when we can unit-test the protocol with zero hardware.

## Build it - step by step

1. Wire the sensor to the Pi (see the Pitfall below - this is where hours were lost).
2. `pip install pyserial`.
3. Implement the SHDLC constants and the pure framing functions.
4. Implement `decode_measurement` (unpack 10 IEEE-754 floats) and the `Measurement`
   dataclass whose field order becomes a **project-wide contract**.
5. Implement the `SPS30` class and a CLI, then run `python3 sps30.py info` and
   `python3 sps30.py stream` on the Pi.


**SHDLC constants, error type, and the `Measurement` field-order contract**  
<sub>`SENSIRION/sps30.py`</sub>

```python
# --- SHDLC constants --------------------------------------------------------
_START = 0x7E
_STOP = 0x7E
_ESC = 0x7D
_ADDR = 0x00  # the SPS30's SHDLC address is fixed at 0

# Byte-stuffing: inside a frame these bytes are escaped as ESC, (byte ^ 0x20).
_STUFF = {0x7E: 0x5E, 0x7D: 0x5D, 0x11: 0x31, 0x13: 0x33}
_UNSTUFF = {v: k for k, v in _STUFF.items()}

# Commands
_CMD_START = 0x00
_CMD_STOP = 0x01
_CMD_READ = 0x03
_CMD_SLEEP = 0x10
_CMD_WAKE = 0x11
_CMD_CLEAN = 0x56
_CMD_INFO = 0xD0
_CMD_VERSION = 0xD1
_CMD_RESET = 0xD3

_STATE_ERRORS = {
    0x01: "wrong data length for this command",
    0x02: "unknown command",
    0x03: "no access right for command",
    0x04: "illegal command parameter or out of range",
    0x28: "internal function argument out of range",
    0x43: "command not allowed in current state",
}


class SPS30Error(Exception):
    """Any protocol- or device-level failure talking to the SPS30."""


@dataclass
class Measurement:
    """One set of SPS30 outputs. Mass in ug/m3, number in #/cm3, size in um."""

    pm1_0: float
    pm2_5: float
    pm4_0: float
    pm10: float
    nc0_5: float
    nc1_0: float
    nc2_5: float
    nc4_0: float
    nc10: float
    typical_size: float

    def __str__(self) -> str:
        return (
            f"PM1.0={self.pm1_0:6.2f}  PM2.5={self.pm2_5:6.2f}  "
            f"PM4.0={self.pm4_0:6.2f}  PM10={self.pm10:6.2f} ug/m3   "
            f"typ.size={self.typical_size:4.2f} um"
        )


class SPS30:
```


**Framing: checksum, send a command, read a response frame (methods on `SPS30`)**  
<sub>`SENSIRION/sps30.py`</sub>

```python
    # --- framing ------------------------------------------------------------
    @staticmethod
    def _checksum(data: bytes) -> int:
        # LSB of the sum of all frame bytes (excluding start/stop), inverted.
        return 0xFF - (sum(data) & 0xFF)

    def _send(self, cmd: int, data: bytes = b"") -> None:
        payload = bytes([_ADDR, cmd, len(data)]) + data
        framed = payload + bytes([self._checksum(payload)])
        out = bytearray([_START])
        for b in framed:
            if b in _STUFF:
                out += bytes([_ESC, _STUFF[b]])
            else:
                out.append(b)
        out.append(_STOP)
        self._ser.reset_input_buffer()
        self._ser.write(out)

    def _read_frame(self) -> bytes:
        # Wait for the start byte, then collect until the stop byte, un-stuffing.
        while True:
            b = self._ser.read(1)
            if not b:
                raise SPS30Error("timeout waiting for response (start byte)")
            if b[0] == _START:
                break
        raw = bytearray()
        while True:
            b = self._ser.read(1)
            if not b:
                raise SPS30Error("timeout waiting for response (stop byte)")
            if b[0] == _STOP:
                break
            raw.append(b[0])

        out = bytearray()
        i = 0
        while i < len(raw):
            if raw[i] == _ESC:
                i += 1
                if i >= len(raw) or raw[i] not in _UNSTUFF:
                    raise SPS30Error("malformed byte-stuffing in response")
                out.append(_UNSTUFF[raw[i]])
            else:
                out.append(raw[i])
            i += 1
        return bytes(out)

    def _transceive(self, cmd: int, data: bytes = b"") -> bytes:
        self._send(cmd, data)
        frame = self._read_frame()
        if len(frame) < 5:
            raise SPS30Error(f"response too short: {frame.hex()}")
        _adr, rcmd, state, length = frame[0], frame[1], frame[2], frame[3]
        payload = frame[4 : 4 + length]
        if len(payload) != length or len(frame) < 5 + length:
            raise SPS30Error(f"declared length {length} exceeds frame {frame.hex()}")
        chk = frame[4 + length]
        expected = self._checksum(frame[: 4 + length])
        if chk != expected:
            raise SPS30Error(
                f"checksum mismatch got 0x{chk:02x} want 0x{expected:02x} "
                f"(frame {frame.hex()})"
            )
        if rcmd != cmd:
            raise SPS30Error(f"command echo mismatch sent 0x{cmd:02x} got 0x{rcmd:02x}")
        if state != 0:
            reason = _STATE_ERRORS.get(state, "unknown error")
            raise SPS30Error(f"device error state 0x{state:02x}: {reason}")
        return payload

    # --- commands -----------------------------------------------------------
```


> **⚠️ COMMON PITFALL - Dead silence vs garbled bytes**  
> Three wiring/power gotchas cost hours: (1) the **SEL pin FLOATING selects UART** (tied to GND = I2C), and the interface is latched at power-up so changing SEL needs a power cycle; (2) TX/RX cross exactly once (sensor RX -> Pi TXD); (3) **power was the real killer** - a Pi 3 on a weak supply sat at 4.75 V and the sensor never booted its comms: *dead silence*. Rule of thumb: **dead silence => wrong interface/wiring/power; garbled bytes => baud/levels.**


> **🧭 DESIGN DECISION - UART, not I2C**  
> The Pi's Broadcom I2C controller has a clock-stretching bug and the SPS30 stretches the clock. UART with per-frame checksums sidesteps it entirely and is rock-solid at 115200. The mini-UART (`/dev/serial0`) is fine - no need to disable Bluetooth.


## ✅ Checkpoint
On the Pi, `python3 sps30.py info` prints the product/serial/firmware, and `python3 sps30.py stream --count 5 --json` prints five measurements of ten float fields each. If you get dead silence, revisit the wiring/power pitfall.

---


# Module 2: Making It Testable (Refactor + a Real Test Suite)

**Learning objective.** Restructure the driver so the protocol can be tested with no hardware, and add a test suite that stays green anywhere.

## Concepts introduced

Right now the framing math and the live serial I/O are tangled. We split them so the
**purity boundary becomes the testability boundary**: everything that *can* be pure
(checksum, stuffing, frame build/parse, measurement decode) becomes a free function; the
`SPS30` class becomes a thin transport shell that only composes those functions.

Then three test files:
- `test_protocol.py` - the pure codec against **captured real byte vectors**;
- `test_transport.py` - the `SPS30` class driven by a **`FakeSerial`** (feeds canned
  response bytes, records what was written) - no real port;
- `test_hardware.py` - the only tests needing a sensor, **gated behind `--run-hardware`**
  (a custom pytest flag) so a normal `pytest` run is hardware-free.

## Build it - step by step

1. Move the framing functions out of the class; have the class call them.
2. Capture a few real request/response frames into `vectors.py`.
3. Write `test_protocol.py` (round-trip stuff/unstuff, checksum, build/parse, decode).
4. Write a `FakeSerial` and `test_transport.py`.
5. Add the `--run-hardware` option in `conftest.py`; gate `test_hardware.py` on it.
6. `pytest` (green, no hardware); `pytest --run-hardware` on the Pi.


**The `FakeSerial` pattern - test command/response logic with no port**  
<sub>`SENSIRION-tests/tests/test_transport.py`</sub>

```python
"""Software integration tests: the full driver stack against a FakeSerial.

No hardware - the FakeSerial replays real captured responses, so this exercises
write -> frame -> read -> unstuff -> parse -> decode end to end.
"""
import pytest

import sps30
from tests import vectors


def test_info_commands(fake_serial_factory):
    sensor, _ = fake_serial_factory(
        dict([vectors.PRODUCT_TYPE, vectors.SERIAL_NUMBER, vectors.VERSION])
    )
    assert sensor.product_type() == vectors.EXPECTED_PRODUCT
    assert sensor.serial_number() == vectors.EXPECTED_SERIAL
    assert sensor.firmware_version() == vectors.EXPECTED_FIRMWARE


def test_measurement_cycle(fake_serial_factory):
    sensor, fake = fake_serial_factory(
        dict([vectors.START, vectors.READ, vectors.STOP])
    )
    sensor.start_measurement()
    m = sensor.read_measurement()
    sensor.stop_measurement()

    assert m is not None
    assert m.pm1_0 == pytest.approx(vectors.EXPECTED_MEASUREMENT["pm1_0"], abs=1e-3)
    assert m.typical_size == pytest.approx(
        vectors.EXPECTED_MEASUREMENT["typical_size"], abs=1e-3
    )
    # The exact request bytes went out on the wire, in order.
    assert fake.written[0].hex() == vectors.START[0]
    assert fake.written[-1].hex() == vectors.STOP[0]


def test_read_returns_none_when_not_ready(fake_serial_factory):
    sensor, _ = fake_serial_factory(dict([vectors.READ_NOT_READY]))
    assert sensor.read_measurement() is None


def test_command_echo_mismatch_raises(fake_serial_factory):
    # Answer a READ request with a STOP-shaped response (wrong CMD echo).
    sensor, _ = fake_serial_factory({vectors.READ[0]: vectors.STOP[1]})
    with pytest.raises(sps30.SPS30Error, match="echo mismatch"):
        sensor.read_measurement()


def test_device_error_state_raises(fake_serial_factory):
    # Start request answered with STATE 0x43 (command not allowed in state).
    sensor, _ = fake_serial_factory({vectors.START[0]: "7e00004300bc7e"})
    with pytest.raises(sps30.SPS30Error, match="0x43"):
        sensor.start_measurement()


def test_timeout_when_no_response(fake_serial_factory):
    # Empty response map -> nothing to read -> transport timeout.
    sensor, _ = fake_serial_factory({})
    with pytest.raises(sps30.SPS30Error, match="timeout"):
        sensor.product_type()
```


> **🧭 DESIGN DECISION - A hand-written fake beats mocking a byte protocol**  
> A small `FakeSerial` that feeds bytes and captures writes is clearer and more robust than `unittest.mock` for a binary protocol - you assert on exact bytes, not call spies.


> **🧭 DESIGN DECISION - Opt-in hardware tests, not skip-magic**  
> An explicit `--run-hardware` flag makes intent obvious and keeps `pytest` green in CI and on any laptop.


## ✅ Checkpoint
`pytest` passes with zero hardware attached. On the Pi with the sensor, `pytest --run-hardware` also passes. The framing functions are importable and pure - which the daemon will exploit next.

---


# Module 3: The Always-On Daemon and a Shared Wire Contract

**Learning objective.** Run a Pi-resident service that owns the sensor, records CSV, and serves live readings to any number of network clients - without ever letting a client stall acquisition.

## Concepts introduced

A phone or laptop shouldn't own the sensor - the **Pi** should, always, and simply serve
data. So we build `sps30_daemon.py`: one process with an **acquisition thread** that is
the *sole owner* of the serial port, a **finite-state machine** (`checking <-> measuring`)
that auto-detects and auto-recovers the sensor, a **CSV writer** (the source of truth),
and a **TCP server** that broadcasts readings as **JSONL** (one JSON object per line).

Everything on the socket or in the CSV is defined **once** in `wire.py` - the single
source of truth for field names, the CSV schema, the filename format, and the
message/command vocabulary. Every later component (the TUI, and eventually the Kotlin
app) imports from this contract so they can never disagree.

The hardest requirement: **acquisition is never blocked by clients.** Reading the sensor
and flushing the CSV happen on a dedicated thread; a slow or dead client must not pause
it. That drives the fan-out design in the Pitfall below.

## Build it - step by step

1. Write `wire.py`: `FIELDS` (the 10-field order from Module 1), `CSV_COLUMNS`, the
   `MSG_*`/`CMD_*` constants, `encode`/`decode`, and `iter_messages` (newline framing).
2. Write the acquisition thread + FSM: probe the sensor; on detect, open a new
   `SPS30-YYYYmmdd_HHMMSS.csv` (Eastern) and stream; `flush()` every row.
3. Add a TCP accept loop and a per-client outbound queue.
4. Implement control commands (pause/resume/clean/restart/raw) - **enqueued** and run by
   the acquisition thread between reads, never touching the port concurrently.


**`wire.py` - the whole shared contract (imported everywhere)**  
<sub>`SENSIRION-dashboard/wire.py`</sub>

```python
"""Shared contract between the SPS30 daemon and GUI client.

This module is the single source of truth for:
  * the CSV schema and file naming,
  * the TCP wire protocol (newline-delimited JSON, "JSONL"),
  * the message/command vocabulary.

Both ``sps30_daemon.py`` (server, on the Pi) and ``sps30_gui.py`` (client)
import from here so they can never disagree about the format. Keep it dependency
free (standard library only) so it runs identically on the Pi and the endpoint.
"""
from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime
from zoneinfo import ZoneInfo

# --- time / files -----------------------------------------------------------
EASTERN = ZoneInfo("America/New_York")  # "EST" per the spec (handles EST/EDT)

# The ten SPS30 outputs, in fixed order, matching sps30.Measurement fields.
FIELDS = [
    "pm1_0", "pm2_5", "pm4_0", "pm10",
    "nc0_5", "nc1_0", "nc2_5", "nc4_0", "nc10",
    "typical_size",
]
CSV_COLUMNS = ["index", "timestamp"] + FIELDS

# Row index is zero-padded to a uniform width good for up to 9,999,999 rows.
INDEX_WIDTH = 7


def format_index(n: int) -> str:
    """Zero-padded measurement counter, e.g. 1 -> '0000001'."""
    return f"{n:0{INDEX_WIDTH}d}"


def now_eastern() -> datetime:
    return datetime.now(EASTERN)


def csv_filename(start: datetime) -> str:
    """Acquisition CSV name from its start time: SPS30-YYYYmmdd_HHMMSS.csv."""
    return start.astimezone(EASTERN).strftime("SPS30-%Y%m%d_%H%M%S.csv")


def iso_timestamp(when: datetime) -> str:
    return when.astimezone(EASTERN).isoformat(timespec="seconds")


def measurement_row(index: int, when: datetime, measurement) -> dict:
    """Build a dict keyed by CSV_COLUMNS from an sps30.Measurement."""
    row = {"index": format_index(index), "timestamp": iso_timestamp(when)}
    row.update(asdict(measurement))
    return row


def row_to_reading(columns, row) -> dict:
    """Map one CSV-style ``row`` onto a reading dict keyed by ``columns``.

    Pairs each column name with its cell, skipping any column past the end of a
    short row. Shared by every consumer of ``(columns, rows)`` file payloads so
    the mapping lives in exactly one place.
    """
    return {c: row[i] for i, c in enumerate(columns or []) if i < len(row)}


def rows_to_readings(columns, rows):
    """Yield a reading dict per row for a CSV-style ``(columns, rows)`` payload."""
    for row in rows or []:
        yield row_to_reading(columns, row)


# --- TCP protocol -----------------------------------------------------------
DEFAULT_PORT = 8730
DEFAULT_HOST = "0.0.0.0"       # daemon binds here
DEFAULT_CLIENT_HOST = "127.0.0.1"  # gui connects here by default (SSH-on-Pi case)

# Server -> client message "type" values.
MSG_HELLO = "hello"        # {product, serial, firmware, state, csv, index}
MSG_READING = "reading"    # {csv, index, timestamp, <FIELDS...>}
MSG_STATE = "state"        # {state, detail}
MSG_RAW = "raw_response"   # {request, response}  (hex strings)
MSG_FILES = "file_list"    # {files: [{name, rows, bytes, start}]}
MSG_FILE_DATA = "file_data"  # {name, columns, rows: [[...], ...], done}
MSG_ERROR = "error"        # {message}

# Client -> server command "cmd" values.
CMD_PAUSE = "pause"        # stop measuring (fan off), stay connected to sensor
CMD_RESUME = "resume"      # resume measuring
CMD_CLEAN = "clean"        # run the fan self-clean cycle, then resume
CMD_RESTART = "restart"    # force back to a known-good measuring+streaming state
CMD_RAW = "raw"            # {hex: "7e...7e"} send a raw frame, return response
CMD_LIST_FILES = "list_files"
CMD_GET_FILE = "get_file"  # {name, since_index?}

# Daemon acquisition states.
STATE_CHECKING = "checking"    # no sensor detected; polling for one
STATE_MEASURING = "measuring"  # streaming readings + writing CSV
STATE_PAUSED = "paused"        # sensor present, measurement stopped by user
STATE_CLEANING = "cleaning"    # fan self-clean in progress


def encode(message: dict) -> bytes:
    """Serialize one message as a single JSONL frame (UTF-8, newline-terminated)."""
    return (json.dumps(message, separators=(",", ":")) + "\n").encode("utf-8")


def decode(line: bytes | str) -> dict:
    """Parse one JSONL frame."""
    if isinstance(line, bytes):
        line = line.decode("utf-8")
    return json.loads(line)


def iter_messages(buffer: bytearray):
    """Yield complete decoded messages from a growing byte buffer.

    Removes consumed bytes from ``buffer`` in place; leaves any partial trailing
    line for the next call. Use this on both ends to frame the TCP stream.
    """
    while True:
        nl = buffer.find(b"\n")
        if nl < 0:
            return
        line = bytes(buffer[:nl])
        del buffer[: nl + 1]
        if line.strip():
            yield decode(line)
```


> **⚠️ COMMON PITFALL - A slow client corrupted a file transfer**  
> The per-client outbound queue is **lossy** (drop-oldest) so a slow client never blocks acquisition - but that dropped a *file-transfer chunk* and corrupted a `get_file`. Fix: each queued message carries a `lossless: bool`. Readings/state are lossy (drop-oldest); file chunks, file lists, raw responses, and errors are **lossless**. The queue is a `deque` + `threading.Condition`.


> **🧭 DESIGN DECISION - CSV is the durable source of truth**  
> Each row is `flush()`ed immediately (a process crash loses at most the row being written); `fsync` runs every N rows for power-loss durability. A client that missed rows recovers them later via `CMD_GET_FILE` - the network is a convenience, the CSV is truth.


## ✅ Checkpoint
Start the daemon on the Pi; `ss -tlnp | grep 8730` shows it listening. A raw socket read of `192.168.1.159:8730` returns a `hello` line then a stream of `reading` lines, and `~/sps30-data/SPS30-*.csv` grows one flushed row per second.

---


# Module 4: A Live Terminal Dashboard (Textual Client)

**Learning objective.** Build a terminal UI that connects to the daemon, renders a live chart and the current values, and offers controls - all decoupled from the acquisition it observes.

## Concepts introduced

`sps30_gui.py` is a **Textual** TUI (Textual = a Python framework for rich terminal apps;
`textual-plotext` renders charts as text). It is a pure network consumer: it connects to
the daemon over TCP, frames the JSONL stream with `wire.iter_messages`, and updates
widgets. A background thread does the socket I/O and hands decoded messages to the UI
thread via Textual's `call_from_thread`.

Key patterns: a `DashboardState` object folds incoming messages into UI state; a status
banner reflects the connection; the client keeps its own **mirror CSV** and can
**auto-reconnect and backfill** missed rows via `CMD_GET_FILE`. This client half is
exactly what we later re-implement natively on Android - so the message handling here is
the blueprint.

## Build it - step by step

1. Build the Textual app skeleton (a chart widget, a banner, a controls row, a raw console).
2. Start a network thread that connects, reads framed messages, and posts them to the UI.
3. Fold `hello`/`reading`/`state`/`file_data` into state; redraw the chart on a timer.
4. Wire controls (pause/resume/clean/restart) to send `CMD_*` frames.


**Message dispatch on the client (the blueprint for the later Kotlin client)**  
<sub>`SENSIRION-dashboard/sps30_gui.py`</sub>

```python
        def _handle_message(self, message: dict) -> None:
            kind, payload = self.state.handle_message(message)
            if kind == "hello":
                backfill = payload.get("backfill")
                if backfill and self.client is not None:
                    self._request_file(
                        "backfill", backfill,
                        f"-> backfill {backfill.get('name')}")
            elif kind == "raw":
                log = self.query_one("#rawlog", RichLog)
                log.write(f"RAW req : {payload.get('request', '')}")
                log.write(f"RAW resp: {payload.get('response', '')}")
            elif kind == "files":
                self.files_meta = list(self.state.files)
                self._render_files()
                # A pending grow was waiting on the file list; resume it now.
                if self._grow_target is not None:
                    target = self._grow_target
                    self._grow_target = None
                    self._backfill_history_to(target)
            elif kind == "file_data":
                self._handle_file_data(payload)
            elif kind == "error":
                log = self.query_one("#rawlog", RichLog)
                log.write(f"ERROR: {payload.get('message', '')}")

        def _request_file(self, purpose: str, command: dict,
```


**Connection-status banner handling**  
<sub>`SENSIRION-dashboard/sps30_gui.py`</sub>

```python
        def _handle_status(self, status: str, detail: str) -> None:
            banner = self.query_one("#banner", Static)
            banner.remove_class("ok", "warn", "err")
            if status == "connected":
                banner.add_class("ok")
                banner.update(f"connected - {detail}")
            elif status == "reconnecting":
                banner.add_class("warn")
                banner.update(f"reconnecting... {detail}")
            else:
                banner.add_class("err")
                banner.update(f"disconnected - {detail} (auto-reconnecting)")

        def _handle_message(self, message: dict) -> None:
```


> **🧭 DESIGN DECISION - The client never blocks on the socket either**  
> Socket reads run on a background thread; decoded messages cross to the UI thread via `call_from_thread`. The UI never does blocking I/O - the same principle we enforce later with Kotlin coroutines + a lossy Flow.


## ✅ Checkpoint
Run the TUI (locally against the Pi's IP, or over SSH on the Pi against 127.0.0.1). You see a live updating chart, the current PM values, a green 'connected' banner, and pause/resume visibly change the daemon's state.

---


# Module 5: Separating 'What to Draw' from 'How to Draw' (Pure Chart Logic)

**Learning objective.** Extract all chart math into a pure, exhaustively testable module with no UI and no sockets - so the exact same logic can drive a terminal chart today and a native Android chart later.

## Concepts introduced

`chartview.py` owns *what* to draw without knowing *how*: parsing a human window
duration, storing full-resolution multi-series readings, and computing the visible slice
plus time-axis labels. It imports only the standard library and `wire`. This is the most
important reuse decision in the whole project: because this logic is pure, it is later
**ported almost verbatim to Kotlin**.

Three pieces:
- `parse_duration("1h30m" | "1800" | "12 hours") -> seconds`;
- `TimeSeriesHistory` - a **bisect-based** sorted store, de-duplicated by
  `(csv_name, index)`, retention-trimmed, and **decimated** for render speed;
- `ChartView` - moving vs pinned modes, x-freeze, zoom, pan, y-autoscale, and a
  `compute(history, now)` that returns plain draw data + Eastern tick labels.

## Build it - step by step

1. Write `parse_duration` (bare seconds, unit suffixes, combinations, spaced words).
2. Write `TimeSeriesHistory.add/merge/window/clear` over parallel sorted lists using
   `bisect`.
3. Write `ChartView.compute` returning `x`, `series`, tick epochs + Eastern labels.
4. Unit-test all of it with synthetic readings - no hardware, no terminal.


**`TimeSeriesHistory` - bisect store, de-duped by (csv, index)**  
<sub>`SENSIRION-dashboard/chartview.py`</sub>

```python
class TimeSeriesHistory:
    """Full-resolution multi-series store keyed on (csv_name, index), ordered by
    time.

    Epochs are float seconds (UTC epoch derived from each reading's ISO
    timestamp). De-duplication is by ``(csv_name or '', index)`` so live
    readings - whose index restarts at the top of every new CSV - never collide
    across files: the same index under two different CSV names is kept twice.
    Points older than ``latest - retention_seconds`` are trimmed so memory stays
    bounded while still holding many hours of history.
    """

    def __init__(self, retention_seconds: float = 172800.0):  # 48h default
        self.retention_seconds = float(retention_seconds)
        # Parallel sorted lists: ``_epochs[i]`` is the time of ``_points[i]``.
        self._epochs: list[float] = []
        self._points: list[dict] = []
        # Keys already stored, for O(1) de-dup.
        self._seen: set = set()

    def _key(self, reading: dict):
        csv = reading.get("csv") or reading.get("name") or ""
        return (str(csv), _index_of(reading.get("index", 0)))

    def add(self, reading: dict) -> bool:
        """Insert one live reading. Returns True if it was new and stored,
        False if it duplicated an existing (csv, index) or lacked a usable
        timestamp."""
        key = self._key(reading)
        if key in self._seen:
            return False
        epoch = _epoch_of(reading)
        if epoch is None:
            # No placeable time - skip rather than crash or guess.
            return False
        point = {"key": key, "epoch": epoch, "values": _values_of(reading)}
        # bisect_right keeps ties in insertion order, so equal-epoch points and
        # later-merged older rows both land in a stable time order.
        pos = bisect.bisect_right(self._epochs, epoch)
        self._epochs.insert(pos, epoch)
        self._points.insert(pos, point)
        self._seen.add(key)
        self._trim()
        return True

    def merge(self, readings) -> int:
```


> **⚠️ COMMON PITFALL - Two CSVs both start at index 0000001**  
> Live indices restart at the top of every new CSV, so **index alone collides across files**. The de-dup key is `(csv_name, index)` - the same index under two different CSV names is kept twice, on purpose.


> **🧭 DESIGN DECISION - Decimate the visible slice, not the store**  
> At ~1 reading/s a 48 h store holds ~170k points; a multi-hour view would ship far more points than any renderer wants. `compute` stride-decimates the *visible* slice to a cap, always keeping the first and last point so the live right edge stays exact.


## ✅ Checkpoint
`pytest tests/test_chartview.py` passes. `parse_duration('1h30m')` == 5400; a `TimeSeriesHistory` fed 100 readings returns the correct windowed slice and Eastern-formatted tick labels from `compute`.

---


# Module 6: Shipping It: systemd on the Pi

**Learning objective.** Make the daemon a real always-on service that starts on boot and auto-restarts on crash.

## Concepts introduced

A dashboard is only useful if the Pi just *serves data* whenever it's powered. We install
`sps30_daemon.py` as a **systemd** service: `enable` makes it start on boot,
`Restart=always` makes it come back after a crash, and `journalctl` gives logs. The unit
file on the Pi uses **Pi paths** (not the repo's dev paths) - a subtlety that bites if
you copy the repo's reference unit verbatim.

## Build it - step by step

1. `scp` the daemon files into `~/sps30-dashboard/` on the Pi.
2. Write `/etc/systemd/system/sps30-daemon.service` with `WorkingDirectory` and
   `ExecStart` pointing at the Pi paths, `Restart=always`, `WantedBy=multi-user.target`.
3. `sudo systemctl enable --now sps30-daemon`; verify with `systemctl is-active` and
   `journalctl -u sps30-daemon -f`.
4. Redeploy later = `scp` new files, then `sudo systemctl restart sps30-daemon`.


**The systemd unit (reference copy in the repo; the Pi copy uses Pi paths)**  
<sub>`SENSIRION-dashboard/sps30-daemon.service`</sub>

```
# SPS30 acquisition daemon - systemd service unit
#
# Install (on the Raspberry Pi, as root):
#   sudo cp sps30-daemon.service /etc/systemd/system/sps30-daemon.service
#   sudo systemctl daemon-reload
#   sudo systemctl enable --now sps30-daemon.service
#
# Follow the logs (journald captures the daemon's stdout):
#   journalctl -u sps30-daemon.service -f
#
# The user 'drd' must be able to open the serial port. On Raspberry Pi OS that
# means being in the 'dialout' group and having the serial console disabled so
# /dev/serial0 is free for the sensor:
#   sudo usermod -aG dialout drd
#   sudo raspi-config   # Interface Options -> Serial: login shell NO, hardware YES
#
# Adjust ExecStart flags (or the SPS30_* environment variables) as needed.

[Unit]
Description=SPS30 particulate matter acquisition daemon
Documentation=file:///opt/sps30/ARCHITECTURE.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=drd
Group=dialout
WorkingDirectory=/opt/sps30
ExecStart=/usr/bin/python3 /opt/sps30/sps30_daemon.py --port /dev/serial0 --tcp-port 8730 --csv-dir /var/lib/sps30-data --interval 1.0
Restart=always
RestartSec=3
# Flush stdout immediately so journald sees log lines in real time.
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
```


> **⚠️ COMMON PITFALL - The repo's unit has the wrong (dev) paths**  
> The `sps30-daemon.service` in the repo was written with a WSL2 dev path; the deployed Pi unit must use `WorkingDirectory=/var/lib/sps30-dashboard`. Don't `scp` the repo unit over the working one without fixing the paths.


> **🧭 DESIGN DECISION - Verify Restart=always actually recovers**  
> We hard-killed the daemon and watched journald show a `Scheduled restart` within ~3 s and the FSM go straight back to `measuring`. A boot-time cold start shows the same auto-detect sequence - so nothing needs a human after a power blip.


## ✅ Checkpoint
`systemctl is-enabled sps30-daemon` = enabled, `is-active` = active. Kill the process; it restarts within seconds and resumes streaming. Reboot the Pi; it comes back measuring on its own.

---


# Module 7: Interim Android: Termux Direct-Connect

**Learning objective.** Get the existing dashboard onto an Android phone with a one-command launch, and discover the Pi on the LAN without mDNS - which does not work on Android.

## Concepts introduced

Before writing a native app, we package the *existing* Python client to run in **Termux**
(a terminal emulator + package manager on Android). The phone connects directly to the
daemon over LAN TCP; `setup.sh` installs `textual textual-plotext plotext` (no pyserial -
the client is a pure network consumer) and drops an `sps30` launcher on PATH.

The central hard-won fact: **Android's Bionic `getaddrinfo` does not resolve `.local`/
mDNS**, and the Pi's IP is DHCP. So discovery is a **stdlib LAN subnet sweep**, not a name
lookup: find this device's `/24`, sweep `.1..254` concurrently, and *verify* each
candidate is really the daemon by reading its first JSONL line and checking it decodes to
a `hello`.

## Build it - step by step

1. Write `sps30_launch.py`: resolve host (CLI > env > config > last-good > pi.local),
   preflight a TCP connect, then launch the TUI.
2. Write the discovery: `local_subnet_bases()` (probe per RFC1918 block + default route),
   a concurrent `discover()` sweep, and `verify_daemon()` (first line must be `hello`).
3. Write `setup.sh` (idempotent; Termux + generic Linux) and a thin `sps30` launcher.
4. Install on the phone from F-Droid Termux; daily use is just `sps30`.


**VPN-aware subnet collection (Python) - probe every RFC1918 block + the default route**  
<sub>`SENSIRION-android/android/sps30_launch.py`</sub>

```python
def local_subnet_bases():
    """Ordered, de-duplicated list of this device's local IPv4 /24 bases.

    Probes each RFC1918 block plus the default route (see ``_PROBE_TARGETS``)
    and collects the distinct /24 base of every local interface the OS would
    use. Private-LAN bases are listed before any public/tunnel base, since the
    daemon lives on a private LAN. Returns [] if nothing can be determined.
    """
    private, public, seen = [], [], set()
    for dst in _PROBE_TARGETS:
        base = _base_of(_source_ip_for(dst))
        if not base or base in seen:
            continue
        seen.add(base)
        (private if _is_private_base(base) else public).append(base)
    return private + public


def local_subnet_base():
```


> **⚠️ COMMON PITFALL - A VPN hides the real Wi-Fi subnet**  
> Probing `8.8.8.8` to learn the local `/24` returns the **VPN tunnel** subnet when a VPN is active (the S24 has one), so discovery swept the wrong network. Fix: probe a representative address in **every** RFC1918 block *plus* the default route, collect all detected `/24`s, and sweep them all.


> **⚠️ COMMON PITFALL - Termux has no system time-zone database**  
> `wire.py` does `ZoneInfo('America/New_York')` at import; Android/Termux ships no IANA tz DB, so the client crashed on launch. Fix: add the pure-Python `tzdata` wheel to the installed dependencies.


## ✅ Checkpoint
On the phone in Termux, `sps30 --discover` prints the Pi's current IP (found by sweep, through the VPN); `sps30` opens the dashboard and streams live. This proved the whole client-over-LAN concept before writing a line of Kotlin.

---


# Module 8: Going Native: The Android Toolchain and a First APK

**Learning objective.** Stand up a Kotlin + Jetpack Compose project that builds a real installable APK - bootstrapping the whole Android toolchain in userspace.

## Concepts introduced

Now we build a *real* native app (see the Design Decision for why, over the WebView/
embedded-Python alternatives). First, the toolchain, entirely in userspace (no root):
a **JDK 17** (AGP 8.x needs 17+), the **Android SDK** command-line tools + a platform +
build-tools, and Gradle via the wrapper.

The app is standard modern Android: **Gradle 8.9 / AGP 8.6.1 / Kotlin 2.0.21 (K2) /
Jetpack Compose (BOM) / Material 3**, `compileSdk 35`, `minSdk 26`. Module 8's deliverable
is just a scaffold: one `MainActivity` hosting a Compose "Hello" screen, an adaptive
launcher icon, and a debug APK you can install.

## Build it - step by step

1. Install JDK 17 (Temurin) and the Android SDK (`platform-tools`, `platforms;android-35`,
   `build-tools;35.0.0`); accept licenses. Point Gradle at the SDK via `local.properties`.
2. Create `settings.gradle.kts`, root `build.gradle.kts`, and `app/build.gradle.kts`.
3. Write `AndroidManifest.xml`, a `MainActivity` with a Compose placeholder, and a theme.
4. `./gradlew :app:assembleDebug` -> `app-debug.apk`; install it.


**`app/build.gradle.kts` - the module build (Compose, serialization, coroutines)**  
<sub>`SENSIRION-android-apk-features/apk/app/build.gradle.kts`</sub>

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sensirion.sps30"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sensirion.sps30"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
```


> **🧭 DESIGN DECISION - Native Kotlin over WebView or embedded Python**  
> Three paths existed: (a) run `textual-serve` on the Pi behind a thin WebView APK; (b) bundle CPython + the Textual TUI on-device; (c) a native rewrite. Native was chosen for real touch UX, a responsive layout, notifications, offline capability, and a clean path to a Watch app - the project's priority was quality/maintainability over least effort.


> **⚠️ COMMON PITFALL - No sudo, old system JDK**  
> The build box had no sudo password and a system JDK 11. Everything was installed in **userspace** (`~/tools/jdk17`, `~/android-sdk`); builds run with `JAVA_HOME` pointed at 17. The Android command-line-tools zip filename is `commandlinetools` (no hyphen) - a wrong guess 404s.


## ✅ Checkpoint
`./gradlew :app:assembleDebug` produces `app-debug.apk` (~9 MB). Installed on the phone it launches, shows the placeholder screen with Material-3 dark theming, and appears in the app drawer.

---


# Module 9: Porting the Protocol and Networking to Kotlin

**Learning objective.** Re-implement the wire protocol, VPN-aware discovery, and the streaming client in Kotlin - guarded by unit tests so the port can't silently drift from the Python.

## Concepts introduced

The pure layers now get faithful Kotlin ports:
- `wire/` - `Wire` (the constants/FIELDS from `wire.py`), `Messages` (a **sealed**
  `ServerMessage` hierarchy + a *tolerant* `parse()` that never throws - a bad line yields
  `null`/`Unknown`, so a stray frame can't crash the read loop), and `Commands`
  (frame encoders). JSON via **kotlinx.serialization**.
- `net/Discovery` - the same VPN-aware sweep, but with a crucial Android twist (Pitfall).
- `net/DaemonClient` - a **coroutine** TCP client: byte-level newline framing emits
  `ServerMessage`s on a **lossy `SharedFlow`** (drop-oldest, mirroring the daemon's own
  per-client queue - a slow UI must never stall the socket), plus `connectLoop` with
  backoff and re-discovery.

`WireTest` asserts against byte-for-byte the frames the *live daemon* emits, so the port
stays honest.

## Build it - step by step

1. Port `Wire`, `Messages` (sealed `ServerMessage` + `parse`), and `Commands`.
2. Write `WireTest` using real captured frames.
3. Port discovery, using `NetworkInterface` enumeration as the primary source (Pitfall).
4. Write `DaemonClient` with framed reads under `runInterruptible`, a lossy `SharedFlow`,
   and a reconnecting `connectLoop`.


**`Messages.kt` - sealed hierarchy + a parse() that never throws**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/wire/Messages.kt`</sub>

```kotlin
object Messages {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Parse one JSONL frame into a [ServerMessage], or `null` if it is not usable. */
    fun parse(line: String): ServerMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val obj = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return null
        }
        return fromObject(obj)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strOr(key: String, default: String = ""): String = str(key) ?: default

    private fun fromObject(obj: JsonObject): ServerMessage? {
        return when (val type = obj.str("type")) {
            Wire.MSG_HELLO -> ServerMessage.Hello(
                product = obj.strOr("product"),
                serial = obj.strOr("serial"),
                firmware = obj.strOr("firmware"),
                state = obj.strOr("state"),
                csv = obj.str("csv"),
                index = obj.strOr("index"),
            )

            Wire.MSG_READING -> {
                val values = LinkedHashMap<String, Double>()
                for (field in Wire.FIELDS) {
                    obj[field]?.jsonPrimitive?.doubleOrNull?.let { values[field] = it }
                }
                ServerMessage.Reading(
                    csv = obj.str("csv"),
                    index = obj.strOr("index"),
                    timestamp = obj.strOr("timestamp"),
                    values = values,
                )
            }

            Wire.MSG_STATE -> ServerMessage.StateMsg(
                state = obj.strOr("state"),
                detail = obj.strOr("detail"),
            )

            Wire.MSG_RAW -> ServerMessage.RawResponse(
                request = obj.strOr("request"),
                response = obj.strOr("response"),
            )

            Wire.MSG_FILES -> {
                val files = obj["files"]?.jsonArray?.mapNotNull { element ->
                    val fileObj = element as? JsonObject ?: return@mapNotNull null
                    ServerMessage.FileEntry(
                        name = fileObj.strOr("name"),
                        rows = fileObj["rows"]?.jsonPrimitive?.intOrNull ?: 0,
                        bytes = fileObj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                        start = fileObj.str("start"),
                    )
                } ?: emptyList()
                ServerMessage.FileList(files)
            }

            Wire.MSG_FILE_DATA -> {
                val columns = obj["columns"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val rows = obj["rows"]?.jsonArray?.map { rowElement ->
                    rowElement.jsonArray.map { cell -> cell.jsonPrimitive.contentOrNull ?: "" }
                } ?: emptyList()
                ServerMessage.FileData(
                    name = obj.strOr("name"),
                    columns = columns,
                    rows = rows,
                    done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }

            Wire.MSG_ERROR -> ServerMessage.ErrorMsg(obj.strOr("message"))

            null -> null
            else -> ServerMessage.Unknown(type)
        }
    }
}
```


**`Discovery.kt` - interface enumeration is the primary source on Android**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/net/Discovery.kt`</sub>

```kotlin
    fun interfaceBases(): List<String> {
        val bases = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                        baseOf(addr.hostAddress)?.let { bases.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to whatever the caller's probe can find
        }
        return bases.toList()
    }

    /**
     * Ordered, de-duplicated list of this device's local /24 bases. Private-LAN
     * bases come before any public/tunnel base, since the daemon is on a private
     * LAN. Draws from interface enumeration first (robust on Android), then
     * augments with the routing probe. Both sources are injectable for tests.
     */
    fun localSubnetBases(
        interfaces: () -> List<String> = { interfaceBases() },
        probe: (String) -> String? = { sourceIpFor(it) },
    ): List<String> {
        val private = LinkedHashSet<String>()
        val public = LinkedHashSet<String>()
        fun consider(base: String?) {
            if (base == null) return
            if (isPrivateBase(base)) private.add(base) else public.add(base)
        }
        for (base in interfaces()) consider(base)
        for (dst in PROBE_TARGETS) consider(baseOf(probe(dst)))
        return (private + public).toList()
    }

    /**
     * True only if `host:port` is the SPS30 daemon (it sends a `hello` frame on
     * connect). Blocking; never throws. Reuse of [Messages.parse] guarantees the
     * verification matches how the app reads real frames.
     */
    fun verifyDaemon(host: String, port: Int, timeoutMs: Long = 1000): Boolean {
```


**`DaemonClient.kt` - framed reads on a lossy Flow, reconnecting loop**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/net/DaemonClient.kt`</sub>

```kotlin
    suspend fun connectLoop(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 4000,
        rediscover: (suspend () -> String?)? = null,
    ) {
        var target = host
        var backoffMs = 500L
        while (currentCoroutineContext().isActive) {
            _state.value = ConnectionState.Connecting(target, port)
            val socket = Socket()
            try {
                withContext(io) {
                    socket.connect(InetSocketAddress(target, port), connectTimeoutMs)
                }
                socket.soTimeout = 0
                _state.value = ConnectionState.Connected(target, port)
                backoffMs = 500L
                streamUntilClosed(socket)
                // Clean EOF (server closed).
                _state.value = ConnectionState.Reconnecting("connection closed")
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Reconnecting(e.message ?: "connection lost")
            } finally {
                runCatching { socket.close() }
            }

            if (!currentCoroutineContext().isActive) break
            if (rediscover != null) {
                runCatching { rediscover() }.getOrNull()?.let { target = it }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(8000L)
        }
    }

    /** Runs one connected session: a reader and a writer, until either ends. */
    private suspend fun streamUntilClosed(socket: Socket) = coroutineScope {
        val writer = launch(io) { pumpOutbound(socket) }
        try {
            runInterruptible(io) { readFramesBlocking(socket) }
        } finally {
            writer.cancel()
        }
    }

    /** Blocking read loop: byte-level newline framing, UTF-8 decode per line. */
    private fun readFramesBlocking(socket: Socket) {
        val input = socket.getInputStream()
        val chunk = ByteArray(4096)
        val residual = ByteArrayOutputStream()
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break // EOF
            residual.write(chunk, 0, n)
            val bytes = residual.toByteArray()
            var start = 0
            var i = 0
            while (i < bytes.size) {
                if (bytes[i] == NEWLINE) {
                    val line = String(bytes, start, i - start, Charsets.UTF_8)
                    Messages.parse(line)?.let { _messages.tryEmit(it) }
                    start = i + 1
                }
                i++
            }
            residual.reset()
            if (start < bytes.size) residual.write(bytes, start, bytes.size - start)
        }
    }

    /** Drains queued command lines to the socket until cancelled or the socket dies. */
    private suspend fun pumpOutbound(socket: Socket) {
```


> **⚠️ COMMON PITFALL - DatagramSocket.getLocalAddress() returns the wildcard on Android**  
> The Python discovery used the UDP-connect trick (`getsockname()`), but on Android `DatagramSocket.getLocalAddress()` returns `0.0.0.0` after `connect()`, so the probe found no subnets and the app reported 'no daemon found'. Fix: enumerate `NetworkInterface` directly (works in an app, lists Wi-Fi + VPN) as the **primary** source; keep the socket probe only as augmentation. This is what made it connect.


> **⚠️ COMMON PITFALL - Two porting bugs the unit tests caught**  
> Kotlin `"1d".toDoubleOrNull()` returns `1.0` (the `d`/`f` numeric suffix), which broke duration parsing until guarded by a strict-number regex; and the ceiling trick `-(-a/b)` is wrong for Kotlin's toward-zero integer division (use `(a+b-1)/b`). Neither is visible without tests.


## ✅ Checkpoint
`./gradlew :app:testDebugUnitTest` passes (wire round-trip + discovery + a loopback-socket `DaemonClient` test). A debug screen that calls `Discovery.discover()` then `DaemonClient.connectLoop()` finds the real Pi through the VPN and logs live `reading` messages.

---


# Module 10: The Live Chart and Readouts

**Learning objective.** Port the chart logic to Kotlin, draw the four PM series natively on a Compose Canvas, and wire the whole thing to a ViewModel showing live values.

## Concepts introduced

`data/TimeSeriesHistory` and `data/ChartView` are Kotlin ports of `chartview.py` (same
bisect store, same moving-window `compute`, java.time for Eastern labels). `ChartData` is
the immutable draw model `compute` returns.

`ui/PmChart` is a native line chart on a Compose **`Canvas`** - no chart library. It draws
axes, grid, time labels, and the four PM polylines. `DashboardViewModel` exposes a
`StateFlow<UiState>`; a `MonitorSession` (next module) folds readings into the history and
recomputes `ChartData` about once a second so the moving window advances even between
readings.

## Build it - step by step

1. Port `Durations`, `TimeSeriesHistory`, `ChartView` (+ JVM unit tests mirroring the
   Python tests).
2. Write `PmChart` drawing `ChartData` on a Canvas; colour the series to match the TUI.
3. Wire a ViewModel: collect `DaemonClient.messages`, `history.add`, recompute on a 1 Hz
   ticker, expose `StateFlow`.
4. Compose the screen: status banner, four readout cells, the chart.


**`ChartView.compute` (Kotlin) - the moving-window draw model**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/data/ChartView.kt`</sub>

```kotlin
    fun compute(history: TimeSeriesHistory, nowEpoch: Double, maxPoints: Int? = null): ChartData {
        val (start, end) = visibleRange(nowEpoch)
        val span = end - start
        val w = history.window(start, end)

        var yLo: Double? = null
        var yHi: Double? = null
        for (field in Wire.PM_FIELDS) {
            val vals = w.series[field] ?: continue
            for (v in vals) {
                yLo = if (yLo == null) v else minOf(yLo, v)
                yHi = if (yHi == null) v else maxOf(yHi, v)
            }
        }

        val (x, series) = decimate(w.epochs, w.series, maxPoints)
        val ticks = ticks(start, end)
        val fmt = labelFormatter(span)
        val labels = ticks.map { label(it, fmt) }

        return ChartData(
            x = x,
            series = series,
            start = start,
            end = end,
            xTicks = ticks,
            xLabels = labels,
            dataYMin = yLo,
            dataYMax = yHi,
            spanSeconds = span,
            sampleCount = w.epochs.size,
            mode = mode,
            viewMode = viewMode(),
            windowSeconds = windowSeconds,
            yAuto = yAuto,
            yMin = yMin,
            yMax = yMax,
            xFrozen = xFrozen(),
            hasReferencePin = referencePinTime != null,
        )
    }

    private fun ticks(start: Double, end: Double): DoubleArray {
```


**`PmChart` core - drawing the series on a Canvas (widest-first; see Module 13)**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/ui/PmChart.kt`</sub>

```kotlin
    for (field in DRAW_ORDER) {
        if (field !in visible) continue
        val vals = data.series[field] ?: continue
        if (vals.isEmpty() || data.x.isEmpty()) continue
        val color = colors[field] ?: Color.Gray
        val width = STROKE_WIDTH[field] ?: 3f
        val path = Path()
        var started = false
        val n = minOf(vals.size, data.x.size)
        for (j in 0 until n) {
            val px = xOf(data.x[j])
            val py = yOf(vals[j])
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
        if (n > 0) drawCircle(color, width + 1f, Offset(xOf(data.x[n - 1]), yOf(vals[n - 1])))
    }

    if (data.x.isEmpty()) {
```


> **🧭 DESIGN DECISION - Anchor 'now' to the newest datum, not just the wall clock**  
> If the phone clock lags the Pi, using wall-clock `now` clips the freshest reading off the right edge. The recompute uses `max(wallNow, latestEpoch)`.


## ✅ Checkpoint
On the phone the app discovers the Pi, connects, and draws live PM lines with an Eastern-time x-axis while the four readout cells update each second.

---


# Module 11: Touch Interaction: Gestures, Windows, Pin/Zoom/Pan

**Learning objective.** Bring the TUI's full chart-control set to touch - pinch-zoom and drag-pan on the chart, window presets, and pin/moving modes with manual axis ranges.

## Concepts introduced

`ChartView` gains the full interaction surface (a complete port of the Python `ChartView`):
moving vs pinned modes, an absolute frozen x-range, `pan`, `zoom` (mode-aware), and
y-autoscale / manual y-range. `PmChart` adds gestures via `detectTransformGestures`:
pinch reports a span-scale factor to `zoom`, horizontal drag reports seconds to `pan`.
A control-chip row exposes Live / Pin / window presets.

## Build it - step by step

1. Complete `ChartView`: `setMode/toggleMode/visibleRange/pan/zoom/setXRange/freezeX/
   xAutoscale/setYRange/yAutoscale`, all unit-tested across moving/pinned/frozen.
2. Add gestures to `PmChart` (`detectTransformGestures`), converting pixels->seconds and
   pinch->factor.
3. Add the Live/Pin/window chips wired to the ViewModel.


**Mode-aware zoom/pan in `ChartView.kt`**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/data/ChartView.kt`</sub>

```kotlin
    fun pan(deltaSeconds: Double, nowEpoch: Double) {
        val fixed = xFixed
        when {
            fixed != null -> xFixed = (fixed.first + deltaSeconds) to (fixed.second + deltaSeconds)
            mode == ChartMode.PINNED -> {
                val start = pinStart ?: visibleRange(nowEpoch).first
                pinStart = start + deltaSeconds
            }
            else -> {
                val (start, end) = visibleRange(nowEpoch)
                xFixed = (start + deltaSeconds) to (end + deltaSeconds)
            }
        }
    }

    fun shiftPin(deltaSeconds: Double, nowEpoch: Double) {
        if (mode != ChartMode.PINNED) setMode(ChartMode.PINNED, nowEpoch)
        pinStart = (pinStart ?: (nowEpoch - windowSeconds)) + deltaSeconds
    }

    fun zoom(factor: Double, nowEpoch: Double) {
        require(factor > 0) { "zoom factor must be positive, got $factor" }
        val fixed = xFixed
        when {
            fixed != null -> {
                val center = (fixed.first + fixed.second) / 2.0
                val half = (fixed.second - fixed.first) / 2.0 * factor
                xFixed = (center - half) to (center + half)
            }
            mode == ChartMode.PINNED -> {
                val pin = pinStart ?: visibleRange(nowEpoch).first
                pinStart = nowEpoch - (nowEpoch - pin) * factor
            }
            else -> setWindow(windowSeconds * factor)
        }
    }

    // --- y-axis -------------------------------------------------------------
```


> **⚠️ COMMON PITFALL - A pure drag was silently drifting the window size**  
> `detectTransformGestures` reports a tiny incidental zoom (~1.0x) even during a pure drag, so panning slowly changed the window seconds and no preset button stayed highlighted. Fix: a **zoom dead-zone** (ignore `|zoom-1| < ~0.015`) plus a tolerance on the window-button highlight.


## ✅ Checkpoint
On the phone you can pinch to change the time span, drag to pan (which freezes the view and shows 'frozen'), tap window presets to switch spans, and toggle Pin to fix the left edge while the right grows.

---


# Module 12: Surviving the Background: A Foreground Service That Actually Keeps Streaming

**Learning objective.** Move the connection into a process-lifetime, thread-safe owner so it survives configuration changes, and run it in a foreground service that keeps the Wi-Fi alive - so monitoring continues with the screen off.

## Concepts introduced

The connection, history, and chart view move out of the ViewModel into `MonitorSession` -
a **process-lifetime singleton** so streaming survives Activity recreation and can be
shared with a service. All non-thread-safe model mutation is **confined to a
single-threaded dispatcher** (`Dispatchers.Default.limitedParallelism(1)`) so the message
collector, the 1 Hz ticker, and UI control calls never race. `DashboardViewModel` becomes
a thin delegate.

`Sps30Service` is a **`dataSync` foreground service** with an ongoing notification (live
status + PM values, a Stop action). Crucially it holds a **`WifiLock`**, because a
foreground service keeps the *process* alive but not the *Wi-Fi radio* in doze.

## Build it - step by step

1. Extract `MonitorSession` (singleton; confinement dispatcher; `StateFlow` of UI state);
   make the ViewModel delegate to it.
2. Write `Sps30Service` (foreground, type `dataSync`, notification, `WifiLock`, Android-15
   `onTimeout`), plus a `Notifications` builder and the manifest permissions.
3. Add a UI toggle that requests `POST_NOTIFICATIONS` (API 33+) then starts the service.


**`MonitorSession` - process-lifetime owner, single-thread confinement**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/ui/MonitorSession.kt`</sub>

```kotlin
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val confinement = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confinement)

    private val client = DaemonClient()
    private val history = TimeSeriesHistory(retentionSeconds = 172_800.0)
    private val chart = ChartView(windowSeconds = 300.0)

    private val _ui = MutableStateFlow(DashboardUiState(windowSeconds = 300.0))
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    @Volatile private var started = false

    /** Begin discovery + streaming once per process. Idempotent. */
    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { client.state.collect { st -> _ui.update { it.copy(connection = st) } } }
        scope.launch { client.messages.collect { onMessage(it) } }
        scope.launch { while (isActive) { recompute(); delay(1000) } }
        scope.launch {
            _ui.update { it.copy(discovering = true) }
            val host = Discovery.discover(PORT, dispatcher = Dispatchers.IO)
            if (host == null) {
                _ui.update { it.copy(discovering = false, discoveryFailed = true) }
                return@launch
            }
            _ui.update { it.copy(discovering = false) }
            client.connectLoop(
                host = host,
                port = PORT,
                rediscover = { Discovery.discover(PORT, dispatcher = Dispatchers.IO) },
            )
        }
    }

    fun setBackgroundEnabled(enabled: Boolean) {
```


**`Sps30Service` - foreground service + WifiLock**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/service/Sps30Service.kt`</sub>

```kotlin
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sps30:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun stopMonitoring() {
```


> **⚠️ COMMON PITFALL - Wi-Fi drops in doze - a foreground service isn't enough**  
> On the S24 the Wi-Fi radio powers down when the screen sleeps, so background streaming silently stalled even with a foreground service. Fix: hold a `WIFI_MODE_FULL_HIGH_PERF` `WifiLock` while monitoring (and document that 24/7 use also needs a battery-optimization exemption).


> **⚠️ COMMON PITFALL - The service is exported=false - adb can't start it**  
> Correct for security, but it means you must start it from the app UI, not `adb start-service`. Verified instead by tapping the toggle and reading the notification.


## ✅ Checkpoint
Toggle Background, press Home; the ongoing notification shows live PM values and **updates while the app is backgrounded** (e.g. 5.6 -> 5.7). A dropped connection auto-reconnects (the banner shows 'Reconnecting...' then recovers).

---


# Module 13: Polish: Responsive Layout and an Honest Chart

**Learning objective.** Adapt the layout to phones vs tablets/landscape, and fix the chart so overlapping series all remain visible.

## Concepts introduced

`DashboardScreen` chooses a **stacked** layout on phones and a **side-by-side** layout
(controls panel + full-height chart) on wide screens (>= 600 dp: the Tab S7+, and phone
landscape). And a physics-driven chart fix: in clean air the SPS30's PM2.5/PM4.0/PM10 are
essentially equal (PM4/PM10 are *calculated* from a size distribution that's almost all
fine particles - this is expected sensor behavior, not a bug), so a naive draw hides three
lines under one. Drawing **widest-first with graduated stroke widths** makes the coincident
cuts render as a layered colour band, all four visible.

## Build it - step by step

1. Split the screen into `StackedDashboard` / `WideDashboard`, chosen by
   `screenWidthDp >= 600`.
2. In `PmChart`, draw `pm10` (widest) down to `pm1_0` (narrowest) so coincident lines
   layer instead of overdrawing.
3. Write a user README covering the battery-optimization steps for reliable 24/7 use.


**Widest-first graduated draw order (`PmChart.kt`)**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/ui/PmChart.kt`</sub>

```kotlin
private val DRAW_ORDER = listOf("pm10", "pm4_0", "pm2_5", "pm1_0")
private val STROKE_WIDTH = mapOf("pm10" to 6.5f, "pm4_0" to 5f, "pm2_5" to 3.5f, "pm1_0" to 2.5f)

// Ignore incidental pinch below this magnitude so a pure drag does not zoom.
private const val ZOOM_DEADZONE = 0.015f
```


> **🧭 DESIGN DECISION - The equal readings are the sensor telling the truth**  
> This was investigated against the datasheet and field studies: the SPS30 optically sizes only small particles (<~1.3 um) and *derives* PM4/PM10; clean air has almost no coarse particles, so they converge. They diverge with dust. So the fix is a clearer *rendering*, plus a later option to hide the redundant derived lines - not a 'fix' to the data.


## ✅ Checkpoint
Rotate to landscape (or install on a tablet): the controls move to a left panel and the chart fills the right. In clean air you can see the green/red/cyan band layered (all four series), not a single line.

---


# Module 14: Feature Upgrades: Cycling Pins, an Options Menu, Config, and Export

**Learning objective.** Add a sophisticated interaction model (cycling pins, a reference pin, mode-aware window buttons), a collapsible options menu, persisted user config, and CSV/PNG export to user-visible storage.

## Concepts introduced

The final module layers real product features on a separate `android-apk-features` branch:
- **`ViewMode` {LIVE, LIVE_FROZEN, PIN}** derived from raw state; the **Pin** button
  enters Pin at the current left edge and, tapped again, **cycles the N most-recent pins**;
  **long-press** sets/snaps a **reference pin**; window buttons **re-pin** in Pin mode.
- **`AppConfig`** (`@Serializable`) + **`ConfigStore`** (SharedPreferences JSON, named
  save/load/reset); `DashboardViewModel` becomes an `AndroidViewModel`.
- **`OptionsSheet`** - a `ModalBottomSheet` (hidden by default, opened by a gear) housing
  the moved controls plus per-series visibility, editable window presets, and export.
- **Export via MediaStore**: PNG -> `Pictures/SPS30/` (appears in the gallery), CSV ->
  `Downloads/SPS30/`.

## Build it - step by step

1. Add `ViewMode`, a pin-history + reference pin, and mode-aware `pinTap`/`pinLongPress`/
   `applyWindow` to `ChartView` (+ 10 unit tests).
2. Add `AppConfig` + `ConfigStore`; make the ViewModel an `AndroidViewModel`.
3. Build `OptionsSheet`; move Pause/Clean/Restart/Y/Background into it; add per-series
   visibility and editable presets.
4. Write `Exports` using MediaStore; render the PNG off-screen from the shared
   `drawPmChart`.


**Exports to user-visible storage via MediaStore (`Exports.kt`)**  
<sub>`SENSIRION-android-apk-features/apk/app/src/main/kotlin/com/sensirion/sps30/config/Exports.kt`</sub>

```kotlin
package com.sensirion.sps30.config

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves exports to shared, user-visible storage via MediaStore (Android 10+):
 * PNGs into **Pictures/SPS30** (so they appear in the gallery) and CSVs into
 * **Downloads/SPS30** (visible in Files / Downloads). No storage permission is
 * needed - an app may always insert its own items. On Android 9 and older it
 * falls back to the app's external files dir. Returns a short, user-facing
 * location string for the confirmation toast.
 */
object Exports {
    private const val SUBDIR = "SPS30"

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun writeCsv(context: Context, csv: String): String {
        val name = "sps30-${stamp()}.csv"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                return "Downloads/$SUBDIR/$name"
            }
        }
        return legacy(context, name) { it.writeText(csv) }
    }

    fun writePng(context: Context, bitmap: Bitmap): String {
        val name = "sps30-${stamp()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBDIR")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)
                    ?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return "Pictures/$SUBDIR/$name"
            }
        }
        return legacy(context, name) { it.outputStream().use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) } }
    }

    /** Android 9 and older, or if the MediaStore insert fails: app files dir. */
    private fun legacy(context: Context, name: String, write: (File) -> Unit): String {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, name)
        write(file)
        return file.absolutePath
    }
}
```


> **⚠️ COMMON PITFALL - Files saved to the app's private dir never show in the gallery**  
> The first export wrote to `Android/data/.../files/` - not indexed by the gallery and hidden by file managers. Fix: **MediaStore** inserts into `Pictures/SPS30` (gallery-visible) and `Downloads/SPS30`; no storage permission needed for own-app inserts.


> **⚠️ COMMON PITFALL - The Compose GraphicsLayer capture API wasn't available**  
> `GraphicsLayer.drawLayer` (to snapshot a composable to a bitmap) is unresolved in this Compose BOM. Instead, the chart drawing was refactored into a `DrawScope.drawPmChart` function reused by both the live Canvas and an off-screen `renderChartToBitmap` - a single source of drawing truth, and version-independent.


## ✅ Checkpoint
Open the gear menu; hide PM4.0 (its readout still shows); tap Pin twice to cycle; Export CSV lands in Downloads/SPS30 and Save PNG appears in your gallery. Settings persist across relaunch.

---


# Appendix: Condensed Background Reviews

_Skim these if an Android/systems concept above was unfamiliar._


### A. Serial + SHDLC in 60 seconds
UART is asynchronous serial: two data wires (TX/RX) crossed between peers, a common ground, agreed baud (115200) and framing (8N1 = 8 data bits, no parity, 1 stop). SHDLC wraps command bytes in `0x7E ... 0x7E`, escapes a few control bytes with `0x7D`, and appends a checksum. `pyserial` gives you `read/write` on `/dev/serial0`.


### B. Why a daemon + a thin client (client/server)
One long-lived process (the daemon) owns the scarce resource (the serial port) and publishes to many clients over TCP. Clients are stateless observers that can come and go. This is the same shape as a database server + apps: it keeps acquisition isolated from UI churn and lets a phone, a laptop, and a service all watch at once.


### C. JSONL framing
JSONL = one JSON object per line. Framing a TCP byte stream means buffering bytes and splitting on `\n`; a partial trailing line waits for the next read. Both ends use the same `iter_messages`/byte-newline logic so neither can desync.


### D. Kotlin coroutines & Flow (for the Android client)
A coroutine is a suspendable function; `Dispatchers.IO` runs blocking socket code off the main thread. A `Flow` is an async stream; a `SharedFlow` is a hot, broadcastable one. We make the readings flow **lossy** (drop-oldest) so a slow collector (the UI) can never back-pressure the socket. `runInterruptible` lets coroutine cancellation interrupt a blocking read so the socket tears down promptly.


### E. Jetpack Compose basics
Compose is declarative UI: `@Composable` functions describe the screen as a function of state, and recompose when the state they read changes. `Modifier` chains configure layout/drawing/input. `Canvas`/`DrawScope` give you imperative drawing (our chart). `collectAsStateWithLifecycle()` turns a `StateFlow` into Compose state.


### F. ViewModel & StateFlow
A `ViewModel` outlives configuration changes (rotation); `StateFlow` is an observable current-value holder. The UI observes one `StateFlow<UiState>` and re-renders. Here the *real* owner is a process-lifetime singleton (`MonitorSession`) so the connection survives even the ViewModel; the ViewModel just forwards.


### G. Android background execution & foreground services
Android aggressively limits background work. A **foreground service** with an ongoing notification is the sanctioned way to keep doing user-visible work (here, streaming). Since Android 14 you must declare a `foregroundServiceType` (`dataSync`) and matching permission; Android 15 adds a daily time budget (`onTimeout`). A foreground service keeps the *process* alive but not the *Wi-Fi radio* - hence the `WifiLock`.


### H. Scoped storage & MediaStore
Since Android 10, apps can't freely write public folders. **MediaStore** lets an app insert its own items into shared collections (`Images`->Pictures, `Downloads`) with no permission; the file is indexed so it shows up in the gallery/Files. Writing to the app's private `getExternalFilesDir` needs no permission either, but nothing else can find those files - which is the bug Module 14 fixes.


### I. The Gradle/AGP/Kotlin toolchain
Gradle builds the app; the **Android Gradle Plugin (AGP)** adds Android tasks; the Kotlin plugin compiles Kotlin; the Compose compiler plugin handles `@Composable`. Versions must be compatible (AGP 8.6 <-> Gradle 8.7+ <-> Kotlin 2.0.x). AGP 8.x needs JDK 17+. `compileSdk` = the API you compile against; `minSdk` = the oldest device you support.


### J. The on-device dev loop (wireless adb)
`adb` is the Android debug bridge. Over Wi-Fi you **pair** once (persists in `~/.android`), then **connect** to a port that *rotates every session* (scan the phone's high ports to find it). Drive the UI with `adb shell input tap/swipe`, capture with `screencap`, inspect with `dumpsys`. Note the screen sleeping drops Wi-Fi and yields a black screenshot - wake it first.
