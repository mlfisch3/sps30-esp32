"""The on-device store format: readings packed into fixed-size flash pages.

This is the **specification** for what the ESP32 writes to flash, in the same
sense that `sps30.py` specifies the sensor protocol and `wire.py` specifies the
network protocol: it is executable, the C implementation mirrors it, and
`firmware/test/gen_store_vectors.py` runs the real functions here and writes
what they emit into a header the C tests assert against byte for byte.

It implements `STORAGE_FORMAT.md`, which measured the encoding on 120,000 real
readings and settled it. Nothing here re-litigates that document; the two
places this adds to it are called out below, and both are consequences of
writing to raw flash rather than to a file.

## The encoding, from STORAGE_FORMAT.md

Per reading, for each of the ten fields in `wire.FIELDS` order:

    value_q = round(value / quantum)          quantum 0.01, or 0.001 for size
    delta   = value_q - previous_value_q
    encoded = varint(zigzag(delta))

Ten fields, one byte per field in the measured common case, so ten bytes per
reading. Timestamps cost nothing: they are the session start plus the reading
index times the cadence, and a break in that cadence is written as a marker
rather than paid for on every reading.

## What raw flash adds

**1. A page, not a file.** There is no filesystem, so the unit is a fixed-size
page written in one shot and protected by a CRC. A page carries its own header:
the session it belongs to, that session's start time and cadence, the index of
its first reading, and that reading's ten values in full. Every page is
therefore independently decodable, which is what makes a ring buffer possible -
the oldest pages are overwritten and whatever survives still reads back. It is
also what makes power loss safe by construction: a page that was half written
fails its CRC and is simply not there.

The cost of repeating the header is real and is not hidden: it is reported by
`measure()` below, and it is why the figure this format achieves is about
eleven bytes per reading rather than exactly ten.

**2. Markers live at the end of the page, not in the delta stream.**
`STORAGE_FORMAT.md` says markers are "interspersed, tagged". Interspersing them
in the byte stream would need a tag byte on every record to tell a marker from
a reading, which is a tenth of the budget spent on saying "this is a reading"
231,000 times. Instead the readings pack forward from the start of the payload
and the markers pack backward from the end, with a count of each in the header.
The decoder knows how many of each to expect, so nothing is ambiguous and the
common case pays nothing at all.
"""
from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass, field
from typing import Iterable, Iterator, Sequence

import wire

# --- the quanta, from STORAGE_FORMAT.md -------------------------------------

#: Mass and number concentrations. 5x finer than the sensor's own
#: sample-to-sample movement, 500x coarser than the doubles the CSV stores.
QUANTUM_DEFAULT = 0.01
#: Typical particle size, whose p95 movement is 0.0034 um.
QUANTUM_SIZE = 0.001

#: Per-field quanta in `wire.FIELDS` order. Only `typical_size` differs.
QUANTA = tuple(QUANTUM_SIZE if f == "typical_size" else QUANTUM_DEFAULT for f in wire.FIELDS)

FIELD_COUNT = len(wire.FIELDS)
assert FIELD_COUNT == 10

# --- the page ---------------------------------------------------------------

#: Bytes per page. The unit of writing, of CRC cover and of loss on power fail.
#:
#: 512 is the balance point. Larger pages amortise the header over more readings
#: and store more per byte; smaller pages lose less when the plug is pulled
#: mid-page. At 512 a page holds about 45 readings, so a hard power cut at 1 Hz
#: costs at most three quarters of a minute, and the format still fits two and a
#: half days of continuous 1 Hz into the partition - see `capacity()`.
PAGE_BYTES = 512

#: `b"S3"`. Two bytes rather than four: a page header is paid for thousands of
#: times over and the CRC is what actually establishes validity.
MAGIC = 0x3353
VERSION = 1

#: Sealed because it filled, rather than because a timer or a session end cut it
#: short. Informational - a reader treats both the same.
FLAG_FULL = 0x01

#: This page's times are readings of the board's own clock, not of a wall clock.
#:
#: A controller that has never had a network has no honest value to put in
#: `session_start_ms`. Writing a zero epoch would be a lie that reads as 1970
#: downstream, so the page says so instead: with this bit set, `session_start_ms`
#: and `page_start_ms` are packed relative-clock readings (see `rel_pack`) and a
#: reader must not present them as instants until it has an equivalence to
#: convert them through.
#:
#: **Everything else about the page is unchanged.** In particular the per-page
#: rebase still does all the work: `page_start_ms` is this page's own first
#: reading, the deltas within the page are `i * cadence`, and a real cadence that
#: is 1009 ms rather than 1000 is absorbed by `MARK_TIME` exactly as before.
#: Relative time is a change of what the origin *means*, not of how a page keeps
#: time inside itself.
FLAG_RELATIVE = 0x02

#: This page carries equivalences, not readings. See `AnchorPage` below.
FLAG_ANCHOR = 0x04

#: `struct` layout of the header, little-endian. Written out longhand so the C
#: struct can be read off it:
#:
#:   off  size  field
#:   0    2     magic            uint16  MAGIC
#:   2    1     version          uint8   VERSION
#:   3    1     flags            uint8   FLAG_*
#:   4    4     page_seq         uint32  monotonic for the life of the device
#:   8    4     session          uint32  acquisition session number
#:   12   8     session_start_ms int64   epoch ms of reading 1 of the session
#:   20   8     page_start_ms    int64   epoch ms of reading 1 of THIS page
#:   28   4     cadence_ms       uint32  nominal spacing between readings
#:   32   4     first_index      uint32  1-based index of this page's first reading
#:   36   2     utc_offset_min   int16   minutes east of UTC at the session start
#:   38   1     count            uint8   readings in this page, >= 1
#:   39   1     marker_count     uint8   markers in this page
#:   40   2     payload_len      uint16  bytes of base+deltas, from HEADER_BYTES
#:   42   2     marker_len       uint16  bytes of markers, ending at PAGE_BYTES
#:   44   4     crc32            uint32  of the whole page with this field zeroed
#:
#: `page_start_ms` is what keeps the timestamps free. Deriving every timestamp
#: from the *session* start instead was measured and does not work: the sensor's
#: real cadence is 1009 ms, not 1000, so the derived time drifts about nine
#: milliseconds per reading and every reading in a long session needs a
#: correction marker. Rebasing each page costs eight bytes per page - two
#: hundredths of a byte per reading - and reduces the accumulated error to under
#: half a second, which is below the tolerance below and so costs nothing at all.
HEADER_STRUCT = struct.Struct("<HBBIIqqIIhBBHHI")
HEADER_BYTES = HEADER_STRUCT.size
assert HEADER_BYTES == 48, HEADER_BYTES

#: Offset of the `crc32` field, which is zeroed while the CRC is computed.
CRC_OFFSET = 44

#: How far a reading's real time may sit from the derived one before it is worth
#: a marker. STORAGE_FORMAT.md reserves markers for when "the cadence actually
#: breaks - a dropout, a profile change, a clock correction"; scheduler jitter of
#: a few tens of milliseconds is not one of those, and the chart cannot see it.
TIME_TOLERANCE_MS = 500

#: Bytes available to readings and markers together.
BODY_BYTES = PAGE_BYTES - HEADER_BYTES
assert BODY_BYTES == 464

# --- marker tags ------------------------------------------------------------

#: Readings [at, at+run) were taken while the measurement was disturbed - the
#: settling window after a start or a clean. `wire`'s `event` column, packed.
MARK_SETTLING = 0x01
#: The reading at `at` was not taken at `start + index * cadence`. Payload is a
#: zigzag varint of the signed millisecond error. This is the "cadence actually
#: breaks" record STORAGE_FORMAT.md reserves - a dropout or a clock correction.
MARK_TIME = 0x02
#: A named event at `at`: "clean", "resume", "restart". Payload is a length-
#: prefixed ASCII string.
MARK_EVENT = 0x03

#: What the `event` column says for the ordinary disturbed-measurement window,
#: and therefore the one event name a marker never has to spell out.
#: `wire.h`'s `WIRE_EVENT_SETTLING`.
SETTLING_EVENT = "settling"


# --- equivalences ------------------------------------------------------------
#
# When a time source finally reaches a board that has been recording relative,
# the board does **not** go back and relabel what it has written. Rewriting
# readings would put data that is already safe back under the write head, and a
# power cut part way through would corrupt an archive that was fine. So it
# appends, and what it appends is an equivalence: "my relative clock read X when
# the world clock read Y".
#
# One is recorded per time source that turns up, not only the first. Each is an
# independent measurement of the same offset, so a reader can use the one nearest
# a given page and the error between two of them is bounded by the crystal's
# drift rather than by however long the board has been running.
#
# An equivalence is eighteen bytes and lives in a page of its own, flagged
# ::FLAG_ANCHOR and carrying no readings. That page costs one ring slot - about
# forty seconds of 1 Hz recording - and buys the whole run before it a place on
# the calendar. Each anchor page repeats every equivalence known for its run, so
# losing the oldest pages to the ring does not lose the oldest equivalences with
# them; the newest anchor page is a complete record on its own.

#: `struct` layout of one equivalence, little-endian:
#:
#:   off  size  field
#:   0    8     rel_ms         int64  packed (run, ms) - see `wire.rel_pack`
#:   8    8     epoch_ms       int64  what the world clock read at that moment
#:   16   2     utc_offset_min int16  the zone the supplied time was in
ANCHOR_STRUCT = struct.Struct("<qqh")
ANCHOR_BYTES = ANCHOR_STRUCT.size
assert ANCHOR_BYTES == 18, ANCHOR_BYTES

#: Equivalences one anchor page carries. Sixteen is 288 bytes of a 464-byte body,
#: so the whole history of a run fits in the page that records its newest member,
#: and a board that is connected to sixteen times keeps all sixteen.
MAX_ANCHORS = 16


@dataclass
class Anchor:
    """One equivalence: the board's clock and the world's, at the same instant."""

    rel_ms: int          # packed (run, ms), as `wire.rel_pack` builds it
    epoch_ms: int
    utc_offset_min: int


# --- varints ----------------------------------------------------------------

def zigzag(value: int) -> int:
    """Map a signed int onto an unsigned one that keeps small magnitudes small."""
    return (value << 1) ^ (value >> 63) if value < 0 else value << 1


def unzigzag(value: int) -> int:
    return (value >> 1) ^ -(value & 1)


def put_varint(out: bytearray, value: int) -> None:
    """Append an LEB128 unsigned varint."""
    if value < 0:
        raise ValueError("varints are unsigned; zigzag first")
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return


def get_varint(buf: bytes, pos: int) -> tuple[int, int]:
    """Read one varint at `pos`. Returns `(value, next_pos)`."""
    value = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("varint runs past the end of the buffer")
        byte = buf[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint is too long")


def quantise(values: Sequence[float]) -> list[int]:
    """The ten floats as integer multiples of their quanta, in FIELDS order."""
    if len(values) != FIELD_COUNT:
        raise ValueError(f"expected {FIELD_COUNT} values, got {len(values)}")
    # round-half-away-from-zero, so the C implementation (lroundf) and this
    # agree on .5 - Python's round() is half-to-even and would not.
    out = []
    for v, q in zip(values, QUANTA):
        scaled = v / q
        out.append(int(scaled + 0.5) if scaled >= 0 else -int(-scaled + 0.5))
    return out


def dequantise(quanta: Sequence[int]) -> list[float]:
    return [q * step for q, step in zip(quanta, QUANTA)]


# --- building a page --------------------------------------------------------

@dataclass
class Reading:
    """One recorded reading, as the store cares about it."""

    index: int
    values: Sequence[float]
    epoch_ms: int
    event: str = ""


@dataclass
class PageWriter:
    """Accumulate readings into one page, then `seal()` it into 512 bytes.

    `add()` returns False when the reading does not fit; the caller seals this
    page, starts another and offers the reading again. Nothing is ever written
    partially: a page becomes bytes exactly once.
    """

    session: int
    session_start_ms: int
    cadence_ms: int
    utc_offset_min: int
    page_seq: int = 0
    #: Times in this page are the board's own clock. See `FLAG_RELATIVE`.
    relative: bool = False

    first_index: int = 0
    page_start_ms: int = 0
    count: int = 0
    _base: list[int] = field(default_factory=list)
    _prev: list[int] = field(default_factory=list)
    _payload: bytearray = field(default_factory=bytearray)
    _markers: list[tuple[int, int, bytes]] = field(default_factory=list)
    _settle_run: int = 0
    _settle_at: int = -1

    @property
    def empty(self) -> bool:
        return self.count == 0

    def _marker_bytes(self) -> bytearray:
        """Serialise the markers. Written backwards from the end of the page."""
        out = bytearray()
        for at, tag, payload in self._pending_markers():
            put_varint(out, at)
            out.append(tag)
            out += payload
        return out

    def _pending_markers(self) -> list[tuple[int, int, bytes]]:
        markers = list(self._markers)
        if self._settle_run:
            run = bytearray()
            put_varint(run, self._settle_run)
            markers.append((self._settle_at, MARK_SETTLING, bytes(run)))
        return markers

    def _free(self) -> int:
        return BODY_BYTES - len(self._payload) - len(self._marker_bytes())

    def add(self, reading: Reading) -> bool:
        """Try to pack `reading` in. False means "this page is full"."""
        if self.count == 0xFF:
            return False
        # Indices are contiguous within a page, which is what lets the header
        # carry one `first_index` instead of every reading carrying its own. A
        # dropout breaks that, so it also breaks the page: the next page opens
        # at the new index and the hole is visible as a hole rather than being
        # silently closed up. Dropouts are rare, and a short page is cheap.
        if self.count and reading.index != self.first_index + self.count:
            return False
        quanta = quantise(reading.values)

        record = bytearray()
        if self.count == 0:
            # The first reading is stored whole, as the base every delta in the
            # page is relative to. Varints, not fixed int32s: indoor values are
            # a few hundred to a few thousand quanta, which is two bytes each.
            for q in quanta:
                put_varint(record, zigzag(q))
        else:
            for q, prev in zip(quanta, self._prev):
                put_varint(record, zigzag(q - prev))

        # Markers this reading would add, priced before committing to it.
        extra_markers = self._price_markers(reading)
        if len(record) + extra_markers > self._free():
            return False

        if self.count == 0:
            self.first_index = reading.index
            self.page_start_ms = reading.epoch_ms
            self._base = list(quanta)
        self._payload += record
        self._prev = quanta
        self._apply_markers(reading)
        self.count += 1
        return True

    def _expected_ms(self, at: int, actual_ms: int) -> int:
        """The time a reader will derive for the reading at position `at`.

        The first reading of a page defines the page's own base, so its derived
        time is its real one and it never needs a correction.
        """
        if at == 0:
            return actual_ms
        return self.page_start_ms + at * self.cadence_ms

    def _price_markers(self, reading: Reading) -> int:
        """Upper bound on the marker bytes accepting `reading` would add."""
        before = len(self._marker_bytes())
        snapshot = (list(self._markers), self._settle_run, self._settle_at)
        self._apply_markers(reading, trial=True)
        after = len(self._marker_bytes())
        self._markers, self._settle_run, self._settle_at = snapshot
        return after - before

    def _apply_markers(self, reading: Reading, trial: bool = False) -> None:
        at = self.count  # position within this page
        # Any non-empty `event` means the measurement was disturbed - that is
        # what the column means to every consumer. The common case by far is the
        # plain settling window, so that costs a run marker and nothing more;
        # a differently named event additionally keeps its name.
        settling = bool(reading.event)
        if settling:
            if self._settle_run and self._settle_at + self._settle_run == at:
                self._settle_run += 1
            else:
                if self._settle_run:
                    run = bytearray()
                    put_varint(run, self._settle_run)
                    self._markers.append((self._settle_at, MARK_SETTLING, bytes(run)))
                self._settle_at = at
                self._settle_run = 1
        elif self._settle_run:
            run = bytearray()
            put_varint(run, self._settle_run)
            self._markers.append((self._settle_at, MARK_SETTLING, bytes(run)))
            self._settle_run = 0
            self._settle_at = -1

        # A named event that is not just the settling flag is worth keeping
        # verbatim, because the app shows it.
        if reading.event and reading.event != SETTLING_EVENT:
            name = reading.event.encode("ascii", "replace")[:31]
            payload = bytearray()
            put_varint(payload, len(name))
            payload += name
            self._markers.append((at, MARK_EVENT, bytes(payload)))

        drift = reading.epoch_ms - self._expected_ms(at, reading.epoch_ms)
        if abs(drift) > TIME_TOLERANCE_MS:
            payload = bytearray()
            put_varint(payload, zigzag(drift))
            self._markers.append((at, MARK_TIME, bytes(payload)))

    def seal(self, full: bool = False) -> bytes:
        """Freeze this page into exactly PAGE_BYTES bytes."""
        if self.count == 0:
            raise ValueError("an empty page is not a page")
        markers = self._marker_bytes()
        marker_count = len(self._pending_markers())
        flags = (FLAG_FULL if full else 0) | (FLAG_RELATIVE if self.relative else 0)
        page = bytearray(PAGE_BYTES)
        header = HEADER_STRUCT.pack(
            MAGIC, VERSION, flags,
            self.page_seq, self.session, self.session_start_ms, self.page_start_ms,
            self.cadence_ms, self.first_index, self.utc_offset_min, self.count,
            marker_count, len(self._payload), len(markers), 0,
        )
        page[:HEADER_BYTES] = header
        page[HEADER_BYTES:HEADER_BYTES + len(self._payload)] = self._payload
        if markers:
            page[PAGE_BYTES - len(markers):] = markers
        crc = zlib.crc32(bytes(page)) & 0xFFFFFFFF
        struct.pack_into("<I", page, CRC_OFFSET, crc)
        return bytes(page)


def seal_anchor_page(page_seq: int, session: int, session_start_ms: int,
                     page_start_ms: int, utc_offset_min: int,
                     anchors: Sequence[Anchor]) -> bytes:
    """Freeze a run's equivalences into exactly PAGE_BYTES bytes.

    Same header, same CRC, same 512 bytes as a reading page - so the ring, the
    boot scan and the power-fail story all work on it unchanged - but flagged
    ::FLAG_ANCHOR and carrying `count == 0` readings. That is the only place in
    this format where a zero count is valid, and the flag is what makes it valid
    rather than corrupt.
    """
    if not anchors:
        raise ValueError("an anchor page with no equivalences says nothing")
    kept = list(anchors)[-MAX_ANCHORS:]
    payload = bytearray()
    for a in kept:
        payload += ANCHOR_STRUCT.pack(a.rel_ms, a.epoch_ms, a.utc_offset_min)

    page = bytearray(PAGE_BYTES)
    header = HEADER_STRUCT.pack(
        MAGIC, VERSION, FLAG_ANCHOR | FLAG_RELATIVE,
        page_seq, session, session_start_ms, page_start_ms,
        0, 0, utc_offset_min, 0, 0, len(payload), 0, 0,
    )
    page[:HEADER_BYTES] = header
    page[HEADER_BYTES:HEADER_BYTES + len(payload)] = payload
    crc = zlib.crc32(bytes(page)) & 0xFFFFFFFF
    struct.pack_into("<I", page, CRC_OFFSET, crc)
    return bytes(page)


@dataclass
class Page:
    """A decoded page."""

    page_seq: int
    session: int
    session_start_ms: int
    page_start_ms: int
    cadence_ms: int
    first_index: int
    utc_offset_min: int
    count: int
    full: bool
    readings: list[Reading]
    #: Times here are the board's own clock, not instants. See `FLAG_RELATIVE`.
    relative: bool = False
    #: Non-empty only on an anchor page, which carries no readings.
    anchors: list[Anchor] = field(default_factory=list)


def decode_page(raw: bytes) -> Page | None:
    """Decode one page, or None if it is blank, corrupt or not ours.

    Never raises on bad input. A ring buffer is read after a power cut, and a
    reader that throws on the one page that was being written when the plug came
    out would lose every page after it as well.
    """
    if len(raw) != PAGE_BYTES:
        return None
    try:
        (magic, version, flags, page_seq, session, start_ms, page_start_ms,
         cadence_ms, first_index, utc_off, count, marker_count, payload_len,
         marker_len, crc) = HEADER_STRUCT.unpack_from(raw)
    except struct.error:
        return None
    if magic != MAGIC or version != VERSION:
        return None
    anchor_page = bool(flags & FLAG_ANCHOR)
    # A zero count is corrupt on a reading page and correct on an anchor page,
    # which is the whole difference the flag makes to a reader.
    if count == 0 and not anchor_page:
        return None
    if payload_len > BODY_BYTES or marker_len > BODY_BYTES:
        return None
    if payload_len + marker_len > BODY_BYTES:
        return None
    check = bytearray(raw)
    struct.pack_into("<I", check, CRC_OFFSET, 0)
    if zlib.crc32(bytes(check)) & 0xFFFFFFFF != crc:
        return None

    relative = bool(flags & FLAG_RELATIVE)

    if anchor_page:
        anchors = []
        for at in range(0, payload_len - ANCHOR_BYTES + 1, ANCHOR_BYTES):
            rel_ms, epoch_ms, offset = ANCHOR_STRUCT.unpack_from(raw, HEADER_BYTES + at)
            anchors.append(Anchor(rel_ms, epoch_ms, offset))
        return Page(page_seq, session, start_ms, page_start_ms, cadence_ms,
                    first_index, utc_off, 0, bool(flags & FLAG_FULL), [],
                    relative, anchors)

    body = raw[HEADER_BYTES:HEADER_BYTES + payload_len]
    markers = raw[PAGE_BYTES - marker_len:] if marker_len else b""

    # Markers first: a reading needs to know whether it was flagged.
    settling: set[int] = set()
    events: dict[int, str] = {}
    drifts: dict[int, int] = {}
    pos = 0
    try:
        for _ in range(marker_count):
            at, pos = get_varint(markers, pos)
            tag = markers[pos]
            pos += 1
            if tag == MARK_SETTLING:
                run, pos = get_varint(markers, pos)
                settling.update(range(at, at + run))
            elif tag == MARK_TIME:
                raw_drift, pos = get_varint(markers, pos)
                drifts[at] = unzigzag(raw_drift)
            elif tag == MARK_EVENT:
                length, pos = get_varint(markers, pos)
                events[at] = markers[pos:pos + length].decode("ascii", "replace")
                pos += length
            else:
                return None
    except (ValueError, IndexError):
        return None

    readings: list[Reading] = []
    prev: list[int] = []
    pos = 0
    try:
        for i in range(count):
            quanta = []
            for f in range(FIELD_COUNT):
                raw_v, pos = get_varint(body, pos)
                delta = unzigzag(raw_v)
                quanta.append(delta if i == 0 else prev[f] + delta)
            prev = quanta
            index = first_index + i
            epoch_ms = page_start_ms + i * cadence_ms + drifts.get(i, 0)
            event = events.get(i) or (SETTLING_EVENT if i in settling else "")
            readings.append(Reading(index, dequantise(quanta), epoch_ms, event))
    except (ValueError, IndexError):
        return None

    return Page(page_seq, session, start_ms, page_start_ms, cadence_ms,
                first_index, utc_off, count, bool(flags & FLAG_FULL), readings,
                relative)


# --- packing a whole run, for measurement and for tests ---------------------

def pack(readings: Iterable[Reading], session: int = 1, cadence_ms: int = 1000,
         utc_offset_min: int = -240, relative: bool = False) -> list[bytes]:
    """Pack `readings` into sealed pages, the way the firmware does."""
    pages: list[bytes] = []
    readings = list(readings)
    if not readings:
        return pages
    start_ms = readings[0].epoch_ms - (readings[0].index - 1) * cadence_ms
    writer = PageWriter(session, start_ms, cadence_ms, utc_offset_min, len(pages), relative)
    for r in readings:
        if not writer.add(r):
            pages.append(writer.seal(full=True))
            writer = PageWriter(session, start_ms, cadence_ms, utc_offset_min,
                                len(pages), relative)
            if not writer.add(r):
                raise ValueError("a single reading did not fit in an empty page")
    if not writer.empty:
        pages.append(writer.seal(full=False))
    return pages


def unpack(pages: Iterable[bytes]) -> Iterator[Reading]:
    for raw in pages:
        page = decode_page(raw)
        if page is None:
            continue
        yield from page.readings


# --- capacity ---------------------------------------------------------------

@dataclass
class Capacity:
    partition_bytes: int
    pages: int
    bytes_per_reading: float
    readings: int

    def days_at(self, cadence_s: float) -> float:
        return self.readings * cadence_s / 86400.0


def capacity(partition_bytes: int, bytes_per_reading: float) -> Capacity:
    pages = partition_bytes // PAGE_BYTES
    readings = int(pages * PAGE_BYTES / bytes_per_reading)
    return Capacity(partition_bytes, pages, bytes_per_reading, readings)
