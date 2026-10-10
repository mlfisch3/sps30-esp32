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

import base64
import json
import math
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
# "event" is blank for normal readings; non-blank (e.g. "startup"/"resume"/
# "restart"/"clean") flags a reading taken while the measurement is disturbed and
# unreliable (sensor spinning up / just cleaned), so clients can exclude it.
#
# These thirteen are the classic header and never change. A session recorded
# without a clock needs two more, and gets them by appending rather than by
# rewriting this list - see CSV_COLUMNS_RELATIVE in the relative-time section.
CSV_COLUMNS = ["index", "timestamp"] + FIELDS + ["event"]

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


def measurement_row(index: int, when: datetime, measurement, event: str = "") -> dict:
    """Build a dict keyed by CSV_COLUMNS from an sps30.Measurement.

    ``event`` flags a disturbed/unreliable reading (blank for normal ones).
    """
    row = {"index": format_index(index), "timestamp": iso_timestamp(when)}
    row.update(asdict(measurement))
    row["event"] = event
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

# --- being found: the mDNS / DNS-SD advertisement ---------------------------
# The controller announces the TCP protocol above as a DNS-SD service, and the
# app browses for it instead of connecting to all 254 addresses of every local
# /24 in turn. The sweep still exists as a fallback for a controller too old to
# advertise; see `Discovery.kt` and `LanDiscovery.kt`.
#
# These names are a compatibility surface in the same way the message types
# above are - a browsing client and an advertising controller have to agree on
# them with nothing else in common - so they are defined here, where the rest of
# the vocabulary is defined, and mirrored into the firmware
# (`components/net/include/net_mdns_record.h`, cross-checked against this module
# by the host test pack) and into the app (`net/Mdns.kt`).
#
# What is deliberately *not* here is any identifier of the individual unit. See
# MDNS_INSTANCE.
MDNS_SERVICE = "_sensorq"  # DNS-SD service name; <= 15 chars, leading underscore
MDNS_PROTO = "_tcp"        # the protocol the SRV record points at
MDNS_SERVICE_TYPE = f"{MDNS_SERVICE}.{MDNS_PROTO}"  # "_sensorq._tcp"

# The mDNS host name, so the A record is `sps30.local`. The same string the
# controller asks DHCP for, deliberately: one name in one place, so that when
# the security work anonymises it (see docs/SECURITY_PLAN.md, "No stable
# identifier on the wire") it moves in both at once.
MDNS_HOSTNAME = "sps30"

# The service instance name - the human-readable half of the advertisement.
#
# A constant, with no serial number, MAC address, board identifier or random
# per-unit token in it, and that is the whole point: the record is multicast to
# every device on the LAN, unencrypted, unprompted and repeatedly, so anything
# unit-specific in it is a permanent identifier handed to any stranger who
# listens - the exact leak the security plan closes by shrinking `hello`.
#
# Two controllers on one LAN do not collide, because DNS-SD already solves that:
# RFC 6762 conflict resolution renames the loser to "SENSORQ (2)" during
# probing, without either being configured. Telling *which* is which is the
# app's job after connecting, not the advertisement's.
MDNS_INSTANCE = "SENSORQ"

# TXT keys. Everything a client needs to decide whether to connect, and nothing
# else - a TXT record is as public as the instance name is.
MDNS_TXT_VERSION = "txtvers"   # DNS-SD convention: the layout of this TXT record
MDNS_TXT_PROTOCOL = "proto"    # the JSONL protocol version this module defines

# Values, as strings, because TXT values are bytes and every reader of them
# compares text.
MDNS_TXT_VERSION_VALUE = "1"
MDNS_TXT_PROTOCOL_VALUE = "1"

# The advertisement in full: exactly these keys, in this order, and no others.
# A client must ignore keys it does not know (that is what MDNS_TXT_VERSION is
# for); this tuple is what the advertising side is held to.
MDNS_TXT = (
    (MDNS_TXT_VERSION, MDNS_TXT_VERSION_VALUE),
    (MDNS_TXT_PROTOCOL, MDNS_TXT_PROTOCOL_VALUE),
)

# Server -> client message "type" values.
MSG_HELLO = "hello"        # {product, serial, firmware, state, csv, index, has_clock}
MSG_READING = "reading"    # {csv, index, timestamp, <FIELDS...>, event}
MSG_STATE = "state"        # {state, detail}
MSG_RAW = "raw_response"   # {request, response}  (hex strings)
MSG_FILES = "file_list"    # {files: [{name, rows, bytes, start}], offset, total,
                           #  next?} - one page of the listing; see file_list_page
MSG_FILE_DATA = "file_data"  # {name, columns, rows: [[...], ...], done}
MSG_ERROR = "error"        # {message, ref?} - see ERROR_REF_* and error_message()
MSG_PROFILE = "profile"    # the active acquisition profile (see PROFILE_* below)
MSG_POWER = "power"        # {source, charging, percent, voltage_v, runtime_hours, method}
                           # plus POWER_CLEAN_DUE_HOURS (below), on every host
MSG_CONTROL = "control"    # {holder, holder_name, expires_in} - who may issue commands
MSG_BACKUP_META = "backup_meta"    # {name, bytes, sessions, rows}
MSG_BACKUP_CHUNK = "backup_chunk"  # {seq, data (base64), done}
MSG_CLIENTS = "clients"    # {clients: [{id, name}], max} - who is watching
MSG_TIME_MAP = "time_map"  # {relative, boot, anchors: [...]} - see below

# --- on-device storage ------------------------------------------------------
# A controller that keeps the record rather than relaying it. The Pi daemon has
# a filesystem and answers none of these; the ESP32 controller has a flash ring
# and answers all of them. They are named here because this module is where the
# vocabulary lives, so that three implementations cannot disagree about it.
MSG_STORAGE = "storage"    # {used_pages, total_pages, readings, sessions,
                           #  fraction, warn_at, warn, ready}
MSG_IMPORT_RESULT = "import_result"  # {name, added, duplicate, rejected,
                                     #  sessions, state, message}
                                     # state: ok | full | busy | error. `busy`
                                     # means another device held the import
                                     # claim and none of it was taken; it is
                                     # distinct from `ok` with added 0, which
                                     # means the controller already had it all.
MSG_ERASE_RESULT = "erase_result"    # {state, message, sessions_confirmed,
                                     #  sessions_refused, pages_erased,
                                     #  readings_erased, overridden}

# --- the storage occupancy map ----------------------------------------------
# A seat-map of the store partition: one cell per 512-byte flash page, so a
# client can draw where data actually sits on the chip rather than quote a
# fraction. Read-only by construction - it is answered from a header walk and
# changes nothing.
#
# The frame mirrors the chip. `sections` lists the regions of the partition in
# address order, each with its own write position, so a client renders whatever
# arrives rather than hard-coding today's layout: today that is the ring plus
# the two preserved-alert regions, and when the tiered scheme lands it becomes
# the main ring plus its coarse generations with no protocol change.
#
# `cells` is the honest part and the reason this is not derived client-side
# from `storage.used_pages`: each page's state comes from its own header, read
# fresh for every answer, the same 48-byte peek the boot scan trusts. The peek
# does not check the CRC (the boot scan's reasoning, `store_codec.h`), so DATA
# means "holds a plausible page", not "decoded end to end" - the same standard
# every other reader applies before paying for the body.
#
# Encoding: 2 bits per page, four pages per byte, page i in bits (i % 4) * 2
# of byte i // 4, then standard base64 with padding. A fixed 1,248 bytes for
# the whole 4,992-page partition, deliberately not run-length encoded: RLE is
# smaller on a tidy ring but unbounded on a messy one, and a map that cannot
# be sent exactly when the flash is in an interesting state would be the wrong
# tool. The fixed size also keeps the frame inside the controller's 8 KiB big
# buffer with room to spare.
MSG_STORAGE_MAP = "storage_map"  # {page_bytes, pages, sections: [
                                 #   {id, start, pages, head, wraps, cells}]}

PAGE_STATE_EMPTY = 0   # erased flash: no header, nothing recorded
PAGE_STATE_DATA = 1    # a plausible reading page (header peek, CRC unchecked)
PAGE_STATE_ANCHOR = 2  # a time-equivalence page: infrastructure, not readings
PAGE_STATE_BAD = 3     # not erased and not a page: torn write or corruption

PAGE_STATES = (PAGE_STATE_EMPTY, PAGE_STATE_DATA, PAGE_STATE_ANCHOR,
               PAGE_STATE_BAD)

# Section ids the controller sends today. A client treats the list as open:
# an id it does not know is still drawn, labelled by its id, because the whole
# point of the section list is that the layout can grow without the protocol
# moving.
MAP_SECTION_RING = "ring"                    # the wrapping ring of readings
MAP_SECTION_ALERT_HEADERS = "alert_headers"  # preserved segment summaries
MAP_SECTION_ALERT_BODIES = "alert_bodies"    # preserved segment samples


def pack_page_states(states) -> str:
    """Encode per-page states as the ``cells`` string of a ``storage_map``.

    Two bits per page, four pages per byte, little end first, standard base64
    with padding. The inverse is :func:`unpack_page_states`, and the two are
    what the C and Kotlin mirrors are held to byte for byte.
    """
    packed = bytearray((len(states) + 3) // 4)
    for i, state in enumerate(states):
        if state not in PAGE_STATES:
            raise ValueError(f"not a page state: {state!r}")
        packed[i >> 2] |= state << ((i & 3) * 2)
    return base64.b64encode(bytes(packed)).decode("ascii")


def unpack_page_states(cells: str, pages: int) -> list[int]:
    """Decode a ``cells`` string back into ``pages`` per-page states.

    Raises ``ValueError`` when the string is not base64 or is not exactly the
    length ``pages`` requires - a map of the wrong size is a disagreement about
    the geometry, and rendering it anyway would put cells on the wrong pages.
    """
    packed = base64.b64decode(cells.encode("ascii"), validate=True)
    if len(packed) != (pages + 3) // 4:
        raise ValueError(
            f"cells is {len(packed)} bytes; {pages} pages need {(pages + 3) // 4}")
    return [(packed[i >> 2] >> ((i & 3) * 2)) & 0x3 for i in range(pages)]


def storage_map_message(page_bytes: int, pages: int, sections: list[dict]) -> dict:
    """One ``storage_map`` frame, with the keys in the order the C emits them.

    Each section is ``{id, start, pages, head, wraps, states}`` where ``states``
    is the per-page state list; it leaves here packed as ``cells``. ``head`` is
    the page the next write goes into, as an offset within the section, and
    ``wraps`` is completed passes of that section's write position - together
    they are how far along the overwrite is once a section has filled.
    """
    out_sections = []
    for section in sections:
        states = section["states"]
        if len(states) != section["pages"]:
            raise ValueError(
                f"section {section['id']!r} has {len(states)} states "
                f"for {section['pages']} pages")
        if not 0 <= section["head"] < max(1, section["pages"]):
            raise ValueError(
                f"section {section['id']!r} head {section['head']} is outside it")
        out_sections.append({
            "id": section["id"],
            "start": section["start"],
            "pages": section["pages"],
            "head": section["head"],
            "wraps": section["wraps"],
            "cells": pack_page_states(states),
        })
    return {
        "type": MSG_STORAGE_MAP,
        "page_bytes": page_bytes,
        "pages": pages,
        "sections": out_sections,
    }


# --- walking a listing that does not fit one frame ---------------------------
# `file_list` is the enumeration a backup walks, so a session the controller
# holds and cannot name is a session nobody can fetch, plot or offer to the erase
# gate. One frame is a hard limit on a controller with 8 KiB to build it in, and
# raising that limit is the mistake the session table already made twice: any
# fixed number is a number a board eventually holds more than.
#
# So the listing is paged. `list_files` may carry an `offset` - the newest-first
# index of the first entry wanted - and `file_list` says where the page it is
# answering with sits, and where the rest of the listing begins.
FILES_OFFSET = "offset"
"""Newest-first index of ``files[0]`` within the whole listing."""

FILES_TOTAL = "total"
"""Sessions the controller holds, however many frames naming them all takes.

The same number ``storage.sessions`` reports, and it must stay so: it is what a
client compares its archive against to know whether the copy it just took is
complete.
"""

FILES_NEXT = "next"
"""The ``offset`` to ask for to continue, present only while more remain.

Deliberately not derived by the client from ``offset + len(files)``. A controller
skips entries it will not serve - an empty session, one whose name it cannot
encode - so a page can be shorter than the listing it consumed, and a client
adding lengths would ask for the same page forever. Absent means the walk is
over, which is the only end condition; a controller that sends it must always
send something strictly greater than the ``offset`` it was asked for.
"""


def file_list_page(files: list[dict], offset: int = 0,
                   limit: int | None = None) -> dict:
    """One page of a ``file_list`` reply, newest first.

    ``limit`` is what the sender can fit in one frame, or None for "all of it",
    which is what a host with no frame limit answers. A host that pages must not
    silently truncate instead: a listing that stops short without saying so is
    exactly the failure this exists to prevent.

    An ``offset`` past the end is answered with an empty page rather than an
    error, because a listing can shrink under a walk - the ring reclaims its
    oldest pages while a client is part way through - and the honest answer to
    "what comes after the end" is "nothing".
    """
    total = len(files)
    start = max(0, int(offset or 0))
    page = files[start:] if limit is None else files[start:start + max(0, limit)]
    message = {
        "type": MSG_FILES,
        "files": page,
        FILES_OFFSET: start,
        FILES_TOTAL: total,
    }
    following = start + len(page)
    if following < total:
        message[FILES_NEXT] = following
    return message


def file_list_next(message: dict) -> int | None:
    """The ``offset`` to ask for next, or None when the listing is complete.

    The client half of :func:`file_list_page`, written once here so three
    implementations cannot each invent their own end condition. A controller too
    old to page never sends ``next``, so this answers None and the walk stops
    after one frame - which is exactly what those controllers have always meant.
    """
    following = message.get(FILES_NEXT)
    if not isinstance(following, int) or isinstance(following, bool):
        return None
    at = message.get(FILES_OFFSET)
    at = at if isinstance(at, int) and not isinstance(at, bool) else 0
    # Never go backwards or stand still: a controller that says so is broken or
    # hostile, and either way a client that obeyed it would loop for ever.
    return following if following > at else None


STORE_WARN_FRACTION = 0.85
"""How full the ring gets before the controller asks for everything to be copied.

`HANDOFF.md` item 13. The controller says so unasked, because the point of the
threshold is that the data leaves *before* there is pressure to delete it, and a
phone that has to poll for it is a phone that will not be polling on the day it
matters.

Measured on the **ring only**, never on the whole partition. The preserved-alert
area below is outside the ring, so a board holding a lot of alert segments does
not look like a board whose ring is filling up - the two features have to be able
to run at once without either provoking the other.
"""

# --- preserved alert data ---------------------------------------------------
# What the controller keeps when the ring wraps. The governing rule is that
# recording incoming data beats protecting older data, so wrapping is expected
# and ordinary history is allowed to go - but readings that crossed the alert
# line are copied out of the ring's way and survive it.
#
# See `firmware/components/store/store_alert.h` for what "alert-level" means and
# why the controller is told a threshold at all when `AlertRule`'s thresholds
# deliberately live only on the phone.
MSG_ALERTS = "alerts"      # {ready, segments, pressure, rate_floor_s, wraps,
                           #  thresholds: {field: ug/m3}}
MSG_ALERT_SEGMENT = "alert_segment"  # one preserved segment's header; see below
MSG_ALERT_DATA = "alert_data"        # {seq, name, columns, rows, done}

CMD_GET_ALERTS = "get_alerts"  # {since_seq?} deliver preserved segments past it
CMD_SET_ALERTS = "set_alerts"  # {auto?: bool, thresholds?: {field: ug/m3}}

# --- commands only a controller that keeps the record answers ---------------
CMD_PUT_FILE = "put_file"  # {name, columns?, rows, done} send a session back
CMD_ERASE = "erase"        # {claims: [...], override?} erase what is backed up
CMD_STORAGE = "storage"    # ask for MSG_STORAGE now rather than waiting
CMD_STORAGE_MAP = "storage_map"  # ask for MSG_STORAGE_MAP: the per-page map

# --- backing everything up and clearing the board, block by block -----------
# A separate path from CMD_ERASE, deliberately, and it does not weaken it: the
# rule that nothing is deleted the asking device cannot prove it holds is the
# same rule, applied per block instead of per session.
#
# `erase` takes a manifest of whole sessions and frees nothing until the copy is
# finished, which cannot empty a board holding more than a phone can take in one
# go. This interleaves - copy a block, prove it landed, free that block, move on
# - so space comes back *ahead* of the write head and acquisition never stops
# and never drops a reading. The device drives it; the controller only ever
# answers, which is what makes an interruption at any point safe.
#
# See BACKUP_AND_CLEAR.md for the whole design and
# `firmware/components/store/store_drain.h` for the block rule.
MSG_DRAIN_STATE = "drain_state"  # {state, block, kind, final, rows, digest,
                                 #  blocks_left, blocks_freed, blocks_kept,
                                 #  readings_copied, segments_copied,
                                 #  readings_freed, message}
MSG_DRAIN_DATA = "drain_data"    # {block, kind, columns, rows, done}

CMD_DRAIN_BEGIN = "drain_begin"      # take the drain claim; nothing is freed
CMD_DRAIN_NEXT = "drain_next"        # send the oldest block that is still held
CMD_DRAIN_CONFIRM = "drain_confirm"  # {block, rows, digest} - free it if true
CMD_DRAIN_CANCEL = "drain_cancel"    # give the claim back; nothing is freed

DRAIN_KIND_BODY = "body"          # preserved alert samples
DRAIN_KIND_SEGMENTS = "segments"  # preserved segment summaries
DRAIN_KIND_RING = "ring"          # ordinary recorded readings

DRAIN_STATES = ("idle", "offered", "freed", "kept", "refused", "finished", "error")
"""What a `drain_state` can be saying.

``offered`` means a block has been sent and the controller is waiting to be told
whether it landed. ``freed`` and ``kept`` are the two ways a confirmation can
succeed - ``kept`` is the last block, the one holding the readings arriving right
now, which is copied and proved like every other and then deliberately left in
place. ``refused`` frees nothing and ends the drain, because a confirmation that
does not match is exactly the situation in which carrying on would be
indefensible.
"""

DRAIN_SEGMENT_COLUMNS = [
    "seq", "session", "field", "threshold", "start_s", "stop_s",
    "first", "last", "midpoint", "mean", "variance", "minimum", "maximum",
    "points", "first_index", "last_index", "wrap", "has_body",
]
"""The cells of a drained segment-summary row.

`ALERT_SEGMENT_KEYS` with the two ISO timestamps replaced by whole seconds.
Deliberately: a segment of a session recorded with no clock has a *packed* board
clock in those fields, and formatting one as a date produces a string that does
not read back - which would make a perfectly good copy fail its digest and stop
the drain. Seconds are unambiguous in both time bases.

The float cells carry the shortest text that reads back as the same number, not
two decimals, because the digest is taken over the values themselves rather than
over a quantised copy of them. `has_body` is worked out at read time and is not
digested.
"""

# {} -> MSG_POWER_ESTIMATE. Ask what the board is drawing under the power model,
# and what the other acquisition modes would cost instead. Controller-only for a
# physical reason rather than a storage one: the model's constants describe the
# ESP32 board's rails and radios, and a Pi answering with them would be lying
# about different hardware. See the power-estimate section below for the reply.
CMD_GET_POWER_ESTIMATE = "get_power_estimate"

CONTROLLER_ONLY_COMMANDS = frozenset({
    CMD_PUT_FILE, CMD_ERASE, CMD_STORAGE, CMD_STORAGE_MAP,
    CMD_GET_ALERTS, CMD_SET_ALERTS,
    CMD_DRAIN_BEGIN, CMD_DRAIN_NEXT, CMD_DRAIN_CONFIRM, CMD_DRAIN_CANCEL,
    CMD_GET_POWER_ESTIMATE,
})
"""Commands the Raspberry Pi daemon does not answer, and should not.

The project runs two controllers and they are not the same machine. A Pi has a
filesystem the user can already reach, so "send this session back" and "erase
what is backed up" are a file copy and a `rm`; a bare controller whose entire
archive is a flash partition has to make both of them protocol, and has to
preserve alert data against its own ring wrapping because nothing else will.
``get_power_estimate`` is here for a different reason: ``power.py``'s constants
model the ESP32 board, so the Pi has no honest answer to give.

They are named here rather than left out of this module, which is where they
used to be: a vocabulary that exists in `wire.h` and in the Kotlin but not in the
source of truth is a vocabulary three implementations can quietly disagree about.
Naming them and saying who answers them is the honest version.

A daemon that does not know one answers its usual "unknown cmd", which is what
every caller of these already expects and checks for.
"""

ALERT_DEFAULT_THRESHOLDS = {"pm2_5": 20.0}
"""The line a controller preserves against before any client has said otherwise.

`AlertEngine.defaults()`'s only enabled rule, and the figure named in the
project's own task notes. It has to have a default because the case preserved
alert data exists for is precisely the one where **no phone is present** to hold
an opinion.

A client that wants a different line sends the **minimum** of its enabled rules'
thresholds for each field. The minimum, because preserving a superset of what any
rule would fire on is right and preserving less than some rule would fire on is
not. Nothing else about a rule is sent: no id, no label, no reaction, nothing
per user. This is a retention policy, not a notification setting.
"""

ALERT_SEGMENT_KEYS = [
    "seq",          # header sequence; never repeats, orders the segments
    "session",      # the CSV name of the session it came from
    "field",        # which of FIELDS crossed the line
    "threshold",    # the line in force at the time
    "start",        # ISO timestamp of the first point
    "stop",         # ISO timestamp of the last point
    "first",        # first value
    "last",         # last value
    "midpoint",     # the middle, averaged over a local neighbourhood
    "mean",         # segment average
    "variance",     # segment variance
    "minimum",
    "maximum",
    "points",       # number of points
    "first_index",  # index range within the session, so rows can be placed
    "last_index",
    "wrap",         # the ring wrap count when it was preserved
    "has_body",     # false once the samples themselves have been reduced away
]
"""Every key an `alert_segment` carries.

This is the captain's list, and the reason it is fixed and separate from the
samples is his requirement that it **survive even if everything else about the
segment is eventually reduced away**. A segment with ``has_body`` false is not a
broken segment - it is the summary doing the job it was kept for.
"""

ALERT_AUTO_DEFAULT = True
"""Preserved alert data goes to a device on connection unless it declines.

The default is that it happens. A client that does not want it sends
``set_alerts`` with ``auto: false``; one that says nothing gets the data, which
is why an older client - which models neither message - is safe: it ignores the
frames rather than having to know to ask for them.
"""

# --- capability flags carried in `hello` ------------------------------------
# A host announces what it can do so a client can decide what to *offer*, rather
# than let the user press a control and be refused. A control that always errors
# is worse than one that is not there.
#
# Both default to True when the key is absent, because every host that predates
# them is a Pi and a Pi can do both things. A host that cannot must say so.
HELLO_SUPPORTS_SLEEP = "supports_sleep"
"""Sensor firmware >= 2.0, so Sleep/Wake and therefore duty cycling work."""

HELLO_CAN_POWER_OFF = "can_power_off"
"""There is a host operating system for ``CMD_SHUTDOWN`` to power off.

False on a bare-metal controller. The ESP32 port has no OS to halt and no
filesystem to unmount - its store is power-fail safe by construction, so pulling
the plug is already the supported way to stop it (see ``STORAGE_FORMAT.md``).
There is no ritual left for the command to perform, so the client hides the
control instead of offering a button whose only outcome is an error dialog.
"""

HELLO_HAS_CLOCK = "has_clock"
"""Whether the host knows what time it is.

False asks to be told, and a client that has a clock should send ``CMD_SET_TIME``.
Absent means True by the same rule as the flags above: every host that predates
this had a clock, and a client must not badger one that never needed telling.
On the Pi the command is declined, and an unsolicited refusal arriving in the
middle of a backfill is an error the client then has to explain away.

A controller that says False is not broken - see the relative-time section
below. It is recording perfectly well against its own clock and is asking for
the one thing it cannot work out for itself.
"""

# --- fan-clean accounting ---------------------------------------------------
# The SPS30 cleans its own fan after a week, but datasheet 4.2 is explicit that
# the counter it does that on resets on every power-down. A controller that is
# duty cycling, or one that gets its plug pulled, therefore never reaches the
# week and the fan is never cleaned - silently, because a dirty fan degrades
# readings rather than failing them. So the controller keeps its own account of
# measured run time since the last clean, persists it across restarts, and says
# how much of the week is left.
CLEAN_AFTER_SECONDS = 604800.0  # 1 week, matching the sensor's own default

POWER_CLEAN_DUE_HOURS = "clean_due_in_hours"
"""Key in the ``power`` payload: hours of measurement before a clean is due.

Zero means due now. Present on every host, and in ``hello``'s embedded copy of
the payload as well as in a standalone ``power`` message, because it changes
slowly enough that a client which only ever saw it at connect time would show a
figure a week stale.
"""


def clean_due_in_hours(run_seconds: float) -> float:
    """Hours of measurement left before a fan clean is due, floored at zero.

    Rounded to a tenth of an hour, which is also the granularity a host needs to
    persist at: below six minutes of accrual the reported value cannot change.

    A ``run_seconds`` that is not a real number reports **zero: due now**. Only a
    corrupt stored count can produce one, so this is about which way to fail, and
    the safe direction is the warning: reporting "due now" when the count is
    unreadable costs one twelve-second fan cycle, while reporting "a full week
    left" throws away the only thing this figure exists to say.

    Written out rather than left to ``max(0.0, nan)``, which already returned
    zero by accident of how Python's ``max`` breaks ties. It is the definition
    here, and the C mirror had quietly chosen the opposite - ``wire.c`` folded a
    non-finite count to ``0.0`` seconds *measured* and so reported the full week.
    A negative infinity also used to escape as ``inf``, which is not JSON.
    """
    if not math.isfinite(run_seconds):
        return 0.0
    return round(max(0.0, CLEAN_AFTER_SECONDS - run_seconds) / 3600.0, 1)


# Client -> server command "cmd" values.
CMD_PAUSE = "pause"        # stop measuring (fan off), stay connected to sensor
CMD_RESUME = "resume"      # resume measuring
CMD_CLEAN = "clean"        # run the fan self-clean cycle, then resume
CMD_RESTART = "restart"    # force back to a known-good measuring+streaming state
CMD_RAW = "raw"            # {hex: "7e...7e"} send a raw frame, return response
CMD_LIST_FILES = "list_files"  # {offset?} - see file_list_page
CMD_GET_FILE = "get_file"  # {name, since_index?}
# {profile: {...}} switch acquisition profile. The profile object is the WHOLE
# new profile, not a patch: any field it omits goes back to its default rather
# than keeping the value the host happens to be running (see apply_set_profile).
CMD_SET_PROFILE = "set_profile"
CMD_SHUTDOWN = "shutdown"  # park the sensor, close the CSV, power the host off
CMD_IDENTIFY = "identify"  # {name} announce a human-readable name for the roster
CMD_CLAIM = "claim"        # take the control lock (see CONTROL_TTL_SECONDS)
CMD_RELEASE = "release"    # give up the control lock
CMD_BACKUP = "backup"      # stream every recorded session as one zip archive
CMD_SET_TIME = "set_time"  # {epoch_ms, utc_offset_min?} give the controller a clock

# Raw bytes per archive chunk before base64. Base64 inflates by a third, so this
# is roughly a 340 KB line - large enough that framing overhead is negligible,
# small enough to stay responsive and bounded in memory on both ends.
BACKUP_CHUNK_BYTES = 256 * 1024

# Commands that change what the sensor is doing, and so require the control lock.
# Everything not listed here (file reads, identify, claim/release) is always
# allowed, because watching must never require taking control away from someone.
#
# CMD_SET_TIME is deliberately NOT here. It does not change what the sensor is
# doing; it tells a controller that has no clock what the time is, and the whole
# situation it exists for is a board found over Bluetooth by whoever walked up to
# it. Requiring the control lock would mean the one phone that could hand over a
# clock is the one that first took control away from everyone else.
MUTATING_COMMANDS = frozenset({
    CMD_PAUSE, CMD_RESUME, CMD_CLEAN, CMD_RESTART, CMD_RAW,
    CMD_SET_PROFILE, CMD_SHUTDOWN,
})
# `set_alerts` is deliberately absent. It moves a retention line and switches off
# a delivery this client did not ask for, neither of which changes what the
# sensor is doing - and a client that had to take the control lock to decline its
# own copy of the alert data would be taking it away from whoever holds it.


def alert_thresholds(rules) -> dict:
    """The preservation line per field, from a client's alert rules.

    The **minimum** enabled threshold for each field, and nothing else about the
    rules. See `ALERT_DEFAULT_THRESHOLDS` for why the minimum and why so little.

    ``rules`` is any iterable of objects or mappings with ``field``,
    ``threshold`` and ``enabled``. A rule that is disabled contributes nothing;
    if none is enabled the result is empty, which means "watch nothing" and is a
    thing a user is allowed to choose.
    """
    out: dict = {}
    for rule in rules or []:
        get = rule.get if isinstance(rule, dict) else lambda k, d=None: getattr(rule, k, d)
        if not get("enabled", True):
            continue
        field = get("field", "pm2_5")
        try:
            threshold = float(get("threshold"))
        except (TypeError, ValueError):
            continue
        if field not in FIELDS or not math.isfinite(threshold) or threshold <= 0:
            continue
        if field not in out or threshold < out[field]:
            out[field] = threshold
    return out

# --- relative time ----------------------------------------------------------
# A controller need not know the world time to record. It counts from zero and
# every reading's time is its position times the interval of its block, which is
# how STORAGE_FORMAT.md already works. What it cannot do is name the instant.
#
# So a session declares itself **relative**: its times are readings of the
# board's own monotonic clock, not of a wall clock. When a time source finally
# turns up the board does not go back and relabel anything - rewriting readings
# would put data that is already safe back under the write head - it APPENDS an
# equivalence: "my relative clock read X when the world clock read Y". The client
# does the conversion, which also keeps it reversible: raw relative readings plus
# equivalences means a later, better time source can be applied again, which is
# impossible once a timestamp is baked into flash.
#
# The relative clock is a pair, (boot, ms), because a reboot restarts the count
# and two runs must never be presented as one. `boot` identifies the run; `ms` is
# milliseconds within it. An equivalence only ever applies inside its own `boot`.

# Reading and row fields that carry the relative clock. Present only on a
# relative session, in which case `timestamp` is empty rather than invented.
COL_REL_BOOT = "rel_boot"
COL_REL_MS = "rel_ms"

CSV_COLUMNS_RELATIVE = CSV_COLUMNS + [COL_REL_BOOT, COL_REL_MS]
"""The CSV header of a session recorded without a clock.

`CSV_COLUMNS` with the board's own clock appended, which is exactly what the
controller already declares in a `file_data` frame for such a session
(`wire_file_data_open`). Naming it here makes the *file* on disk the same shape
as the frame, so a session that goes out to a file and comes back is the session
that went out.

Without it a clockless row carried neither an instant nor a position, and every
reader - correctly - kept nothing of it. That is data loss in the one direction
this project does not accept: `timestamp` is empty because the board refused to
invent one, and dropping the reading for the board's honesty is the worst
possible reward for it.

**Additive by construction.** The two columns go on the end, after `event`, so a
reader that has never heard of them sees the same thirteen columns in the same
thirteen positions and reads them exactly as before - whether it looks cells up
by name or by index. Nothing that writes an absolute session writes this header
at all, so the Pi's files, and the frozen Pi app that mirrors this module, are
untouched by it.
"""


DRAIN_READING_COLUMNS = ["session"] + CSV_COLUMNS_RELATIVE
"""The cells of a drained reading row. See `MSG_DRAIN_DATA`.

`CSV_COLUMNS_RELATIVE` with the session's name in front. The name is there
because a block is a slice of *flash*, not of one session, and may hold pages of
several; the two clock cells are always present rather than added only for a
relative session as `file_data` does, because one block can hold pages of both
kinds and a single column list has to describe them all. An absent cell is
empty: a dated row has no `rel_boot`, a clockless one has no `timestamp`.

Defined here rather than beside the other drain names above because it is built
from `CSV_COLUMNS_RELATIVE`, which is defined here.
"""


def csv_columns(name: str) -> list:
    """The header a session's file needs, from its name.

    A pure function of the name, like `rel_session_name` and `csv_filename`
    themselves: the `SPS30-Rxxxxxxxx-xxxxxxxx.csv` form says the session was
    recorded against the board's own clock, and that is the whole of what
    decides which header its file gets. No caller has to track it separately,
    and none of them can disagree about it.
    """
    return CSV_COLUMNS_RELATIVE if parse_rel_session_name(name) else CSV_COLUMNS

# `hello`'s answer to "do you know what time it is?". Defined beside the other
# capability flags above rather than here, because it is one: see
# HELLO_HAS_CLOCK.

# What an exported row says the time was actually derived from. The captain's
# rule: an export is honest about what the device knew.
TIME_MEASURED = "measured"      # the controller had a clock when it recorded
TIME_DERIVED = "derived"        # relative reading converted through an equivalence
TIME_UNANCHORED = "unanchored"  # relative reading with no equivalence; time unknown
TIME_MANUAL = "manual"          # relative reading placed by hand by the user
TIME_SOURCE_COLUMN = "time_source"


def rel_pack(boot: int, ms: int) -> int:
    """Pack a relative clock reading into the 64-bit field a timestamp uses.

    The store's page header has an int64 for the time and no spare field, so the
    run marker rides in the top half. Milliseconds stay in the bottom 32 bits,
    which is 49 days of uninterrupted running - far past the point at which the
    ring has overwritten itself - so `page_start + i * cadence` arithmetic works
    on a packed value unchanged.
    """
    return ((boot & 0x7FFFFFFF) << 32) | (ms & 0xFFFFFFFF)


def rel_boot(packed: int) -> int:
    """Which run of the board a packed relative clock reading belongs to."""
    return (packed >> 32) & 0x7FFFFFFF


def rel_ms(packed: int) -> int:
    """Milliseconds on the board's clock, within its run."""
    return packed & 0xFFFFFFFF


def rel_session_name(boot: int, start_ms: int) -> str:
    """The session key for a session recorded with no clock.

    The absolute form is `SPS30-YYYYmmdd_HHMMSS.csv`, a local wall-clock time. A
    relative session has no honest value to put there, so it is named after what
    it does know - the run and the position in it - and the `R` makes the
    difference visible to a person reading a list of sessions rather than hidden
    in a field. Like the absolute name it is a pure function of the page header,
    which is what lets a session be renamed identically after a reboot.
    """
    return f"SPS30-R{boot & 0x7FFFFFFF:08X}-{start_ms & 0xFFFFFFFF:08X}.csv"


def parse_rel_session_name(name: str) -> tuple[int, int] | None:
    """`(boot, start_ms)` from a relative session key, or None if it is not one."""
    if not name.startswith("SPS30-R") or not name.endswith(".csv"):
        return None
    body = name[len("SPS30-R"):-len(".csv")]
    if len(body) != 17 or body[8] != "-":
        return None
    try:
        return int(body[:8], 16), int(body[9:], 16)
    except ValueError:
        return None


def anchor(boot: int, ms: int, epoch_ms: int, utc_offset_min: int) -> dict:
    """One equivalence, as `time_map` carries it.

    Read it as a sentence: on run `boot`, when my clock read `ms`, the world
    clock read `epoch_ms`. Recorded once per connection that supplies a time,
    not only the first - each is an independent measurement of the offset, so a
    client can use the one nearest a block and bound the drift between them.
    """
    return {
        "boot": boot,
        "rel_ms": ms,
        "epoch_ms": epoch_ms,
        "utc_offset_min": utc_offset_min,
    }


def resolve_relative(anchors, boot: int, ms: int) -> tuple[int, int] | None:
    """`(epoch_ms, utc_offset_min)` for a relative reading, or None if unknowable.

    The nearest equivalence *in the same run* wins; equivalences from another run
    say nothing at all about this one, because the board's clock restarted in
    between by an amount nobody measured. Returning None is the honest answer and
    the caller must say so rather than substitute a plausible time.
    """
    best = None
    for a in anchors or ():
        if a.get("boot") != boot:
            continue
        distance = abs(int(a.get("rel_ms", 0)) - ms)
        if best is None or distance < best[0]:
            best = (distance, a)
    if best is None:
        return None
    a = best[1]
    return int(a["epoch_ms"]) + (ms - int(a["rel_ms"])), int(a.get("utc_offset_min", 0))

# How long a control claim survives without being used. Long enough that a user
# working through the options menu is not interrupted, short enough that someone
# who walks away with the lock does not strand everybody else.
CONTROL_TTL_SECONDS = 120.0

# --- transports and their client capacity -----------------------------------
# The two links carry the same JSONL protocol but do not have the same capacity,
# and the difference is not cosmetic: Wi-Fi serves a room, Bluetooth serves one
# phone. Reporting a single fixed number made the app say "1 of 8" on Bluetooth,
# which tells a user a second device may connect when it never can.
TRANSPORT_TCP = "tcp"  # Wi-Fi / LAN sockets
TRANSPORT_BLE = "ble"  # Bluetooth Low Energy

# lwIP defaults to 10 sockets and a couple go to the listener and OTA, so about
# this many remain. Capping the Pi at the same number keeps behaviour identical
# across both hosts rather than diverging when the hardware changes.
MAX_CLIENTS_TCP = 8
# NimBLE's GATT server is bridged to exactly one central at a time, and the Pi's
# BLE bridge is a single proxy connection. One is the truth on both hosts.
MAX_CLIENTS_BLE = 1

# The socket-server cap. Kept under its historical name because it is what the
# TCP accept path enforces; per-transport reporting goes through max_clients_for.
MAX_CLIENTS = MAX_CLIENTS_TCP

TRANSPORT_CAPACITY = {
    TRANSPORT_TCP: MAX_CLIENTS_TCP,
    TRANSPORT_BLE: MAX_CLIENTS_BLE,
}


def max_clients_for(transport: str | None) -> int:
    """How many clients ``transport`` can actually serve at once.

    An unknown or missing transport falls back to the TCP figure, which is what
    a client that cannot name its link was already being told.
    """
    return TRANSPORT_CAPACITY.get(transport, MAX_CLIENTS_TCP)


def effective_max_clients(reported: int | None, transport: str | None) -> int:
    """Reconcile a server-reported cap with what ``transport`` can carry.

    The daemon counts sockets, so it reports the TCP figure even to a client
    arriving over the BLE bridge - the bridge looks like one more socket. The
    honest number is the smaller of the two, and 0/None from an older daemon
    means "unstated", so the transport figure stands alone.
    """
    limit = max_clients_for(transport)
    if not reported or reported <= 0:
        return limit
    return min(int(reported), limit)

# Daemon acquisition states.
STATE_CHECKING = "checking"    # no sensor detected; polling for one
STATE_MEASURING = "measuring"  # streaming readings + writing CSV
STATE_PAUSED = "paused"        # sensor present, measurement stopped by user
STATE_CLEANING = "cleaning"    # fan self-clean in progress
STATE_SLEEPING = "sleeping"    # duty-cycle: sensor asleep between measurements
STATE_WARMUP = "warmup"        # duty-cycle: fan spun up, readings not yet trusted
STATE_SHUTDOWN = "shutting_down"  # host is powering off; safe to unplug shortly

# --- acquisition profiles ---------------------------------------------------
# MODE_CONTINUOUS: sensor measures non-stop and we record every `interval`
#   seconds. Raising the interval saves DATA, not power - the fan and laser stay
#   on and the sensor still draws ~55 mA.
# MODE_DUTY_CYCLE: Sensirion's low-power recommendation. Per cycle: wake, start
#   measurement, discard `warmup_s`, average `average_s` of readings into one
#   point, stop, sleep until the next `cycle_s`. Their reference figures are
#   60 s of measurement every 15 min -> ~3.5 mA average vs ~55 mA continuous.
MODE_CONTINUOUS = "continuous"
MODE_DUTY_CYCLE = "duty_cycle"

# Sensirion "Low-Power Operation of the SPS30" (app note v1, Aug 2020):
# 30 s minimum warm-up before the output is trustworthy, then average a further
# 30 s. Going below 8 s of warm-up is explicitly not recommended.
WARMUP_DEFAULT = 30.0
WARMUP_MIN = 8.0
AVERAGE_DEFAULT = 30.0
CYCLE_DEFAULT = 900.0  # 15 min, the interval their field data recommends

# Upper bounds on the same four fields. Every one of them is a way to stop the
# host recording while it goes on answering and looking healthy - a cycle of a
# thousand days is accepted, adopted, and then nothing is ever written again.
# That is the quietest possible failure for a product whose whole value is the
# record, so the fields are bounded at both ends rather than only at the bottom.
#
# The ceilings are set above anything a client can legitimately ask for and no
# higher: the app offers 60 s as its longest continuous interval and 24 h as its
# longest duty cycle, and Sensirion's own figures put warm-up and averaging at
# 30 s each. Clamping - not rejecting - is kept, for the reason
# `normalize_profile` gives: this arrives straight off a socket.
INTERVAL_MAX = 3600.0    # 1 h; the app's longest offer is 60 s
CYCLE_MAX = 86400.0      # 24 h, which is the app's longest duty-cycle period
WARMUP_MAX = 300.0       # 5 min; Sensirion recommend 30 s
AVERAGE_MAX = 300.0      # 5 min; Sensirion recommend 30 s
# WARMUP_MAX + AVERAGE_MAX must stay <= CYCLE_MAX, or the "a cycle holds the
# work it describes" floor below would push cycle_s back over its own ceiling.
assert WARMUP_MAX + AVERAGE_MAX <= CYCLE_MAX

# Sleep/wake needs SPS30 firmware >= 2.0.
MIN_FIRMWARE_FOR_SLEEP = (2, 0)

DEFAULT_PROFILE = {
    "mode": MODE_CONTINUOUS,
    "interval": 1.0,     # continuous: seconds between recorded readings
    "cycle_s": CYCLE_DEFAULT,   # duty cycle: wake-to-wake period
    "warmup_s": WARMUP_DEFAULT,  # duty cycle: discarded fan spin-up/settling
    "average_s": AVERAGE_DEFAULT,  # duty cycle: averaged into one point
}


def normalize_profile(profile: dict | None) -> dict:
    """Coerce a client-supplied profile into a valid one, clamping bad values.

    Never raises: an unreadable field falls back to its default rather than
    taking acquisition down, since this arrives straight off the wire.

    Every duration is bounded at both ends. The floors keep a profile physically
    sensible; the ceilings are there because an unbounded one is a way to stop
    the host recording without anything looking wrong - see ``CYCLE_MAX``.
    """
    src = profile or {}
    out = dict(DEFAULT_PROFILE)
    mode = src.get("mode")
    if mode in (MODE_CONTINUOUS, MODE_DUTY_CYCLE):
        out["mode"] = mode

    def bounded(key: str, ceiling: float) -> None:
        try:
            value = float(src[key])
        except (KeyError, TypeError, ValueError):
            return
        if value > 0:
            # `not value > 0` also catches NaN, which falls back to the default
            # rather than clamping, since there is no sensible end to clamp it
            # to. Infinity does clamp, because its intent is unambiguous.
            out[key] = min(value, ceiling)

    for key, ceiling in (
        ("interval", INTERVAL_MAX),
        ("cycle_s", CYCLE_MAX),
        ("warmup_s", WARMUP_MAX),
        ("average_s", AVERAGE_MAX),
    ):
        bounded(key, ceiling)
    out["warmup_s"] = max(WARMUP_MIN, out["warmup_s"])
    # A cycle has to be long enough to contain the work it describes, else the
    # sensor would never actually sleep. This can only raise `cycle_s`, and the
    # assertion by WARMUP_MAX says it can never raise it past CYCLE_MAX.
    out["cycle_s"] = max(out["cycle_s"], out["warmup_s"] + out["average_s"])
    return out


def apply_set_profile(params: dict | None, current: dict | None = None) -> dict:
    """The profile a ``set_profile`` command leaves the host running.

    This is the definition of the command, and it is deliberately stateless: the
    incoming object is the whole profile, so a field it omits returns to its
    default instead of inheriting whatever is running. ``current`` is accepted
    only so callers can pass it and be shown, unambiguously, that it is unused -
    two hosts that merge differently would disagree about the resulting profile
    while both believing they honoured the same command.
    """
    return normalize_profile((params or {}).get("profile"))


# --- the modelled power estimate ---------------------------------------------
# What the board is drawing under `power.py`'s model, and what the other
# acquisition modes would cost instead - the reply to CMD_GET_POWER_ESTIMATE
# (defined beside CONTROLLER_ONLY_COMMANDS: only the ESP32 answers it, because
# the model's constants describe that board and no other; the Pi daemon gives
# its usual "unknown cmd", which a client treats as "no estimate here").
#
# The app deliberately does not mirror `power.py` in Kotlin. Only the controller
# knows its own link state, its own write rate and whether its sensor can sleep,
# so it computes every figure - for its current state and for each alternative -
# and the app renders what it is told. One model, one implementation, held to
# `power.py` by the firmware vectors; this message is the delivery power.py's
# own docstring promises. `power.estimate_message` composes the reply and is the
# specification the firmware's encoder is held to, byte for byte.

MSG_POWER_ESTIMATE = "power_estimate"

POWER_ESTIMATE_BASIS_MODELLED = "modelled"
"""The only value `basis` has today: every figure is modelled, none measured.

Carried in the message rather than assumed by the client, so the day a `power`
payload is backed by a meter the panel can change its one honesty sentence
without an app release deciding when. `sources` carries the counts behind it -
`power.sources_summary()`'s answer - so "12 of 14 constants are estimates" is a
statement the wire proves rather than prose that goes stale.
"""

#: Why the `runtime` slot is or is not a number: `power.Runtime`'s ways of not
#: knowing, as values a client can switch on. `hours` is present only under
#: "ok". A client must keep the other three distinguishable from zero hours:
#: "unknown" and "about to die" are opposite states, and there is no battery on
#: any board today, so today every reply says `no_capacity`.
POWER_RUNTIME_OK = "ok"
POWER_RUNTIME_NO_CAPACITY = "no_capacity"              # nothing knows what is left
POWER_RUNTIME_UNUSABLE_CAPACITY = "unusable_capacity"  # NaN, negative, bad volts
POWER_RUNTIME_NO_DRAW = "no_draw"                      # modelled power not positive
POWER_RUNTIME_STATUSES = (
    POWER_RUNTIME_OK, POWER_RUNTIME_NO_CAPACITY,
    POWER_RUNTIME_UNUSABLE_CAPACITY, POWER_RUNTIME_NO_DRAW,
)

#: The subsystems a breakdown is cut into, in the order a report reads them.
#: The vocabulary half of `power.SUBSYSTEMS` (which aliases this): the keys of
#: `current.by_subsystem`, and the order both mirrors accumulate in.
POWER_SUBSYSTEMS = ("sensor", "controller", "radio", "storage")

#: The duty-cycle periods a comparison prices, smallest first - the same six the
#: app's mode picker offers (`AppConfig.DUTY_CYCLE_PERIODS`), so the panel
#: prices exactly the switches the user can make. The app holds its copy to this
#: one by test, the way `Wire.kt` holds every other mirrored constant.
POWER_COMPARE_CYCLES = (300.0, 900.0, 1800.0, 3600.0, 43200.0, 86400.0)


def power_compare_profiles() -> list:
    """The profiles a power comparison answers for, in reply order.

    Continuous first, then each supported duty-cycle period, everything else at
    its default - deliberately, because the recording interval changes only the
    flash term (fractions of a milliwatt) and pricing every interval would bury
    the one comparison that matters, continuous against duty cycling, under
    rows that all say the same number.
    """
    profiles = [normalize_profile({"mode": MODE_CONTINUOUS})]
    for cycle_s in POWER_COMPARE_CYCLES:
        profiles.append(normalize_profile({"mode": MODE_DUTY_CYCLE, "cycle_s": cycle_s}))
    return profiles


def power_current_payload(profile: dict | None, sleeps: bool, total_mw: float,
                          by_subsystem: dict) -> dict:
    """The `current` half of a `power_estimate` reply.

    The full breakdown, because the captain's question about the current state
    is "what is all of it doing" - the sensor is only part of the bill and the
    panel must say so. Subsystem keys and order are `power.SUBSYSTEMS`.
    """
    return {
        "profile": normalize_profile(dict(profile or {})),
        "sleeps": bool(sleeps),
        "total_mw": total_mw,
        "by_subsystem": {name: by_subsystem.get(name, 0.0) for name in POWER_SUBSYSTEMS},
    }


def power_alternative_payload(profile: dict | None, sleeps: bool, total_mw: float,
                              hours: float | None = None) -> dict:
    """One priced alternative: the mode, what identifies it, and its total.

    Flat rather than a nested profile echo, and totals only: the breakdown
    belongs to the state the board is actually in. `sleeps` says whether the
    figure assumes the sensor sleeps between cycles - this board's sensor may
    not support that, and a figure computed for the capable case would overstate
    the saving on a board that cannot have it.

    `hours` is the estimated remaining life under this mode, present only when
    the reply's `runtime.status` is "ok" - which no board can say today. The
    slot is defined now so capacity information arrives as a value in an
    existing place rather than as a new message.
    """
    p = normalize_profile(dict(profile or {}))
    out: dict = {"mode": p["mode"]}
    if p["mode"] == MODE_DUTY_CYCLE:
        out["cycle_s"] = p["cycle_s"]
        out["sleeps"] = bool(sleeps)
    out["total_mw"] = total_mw
    if hours is not None:
        out["hours"] = hours
    return out


def power_runtime_payload(status: str, hours: float | None = None,
                          modelled_hours: float | None = None,
                          derating: float | None = None) -> dict:
    """The `runtime` slot: a number under "ok", a named reason otherwise.

    An unrecognised status is read as `no_capacity`, and so is "ok" arriving
    without a usable number - a runtime slot must never claim to know while
    carrying nothing, because the client's whole contract is that "ok" means
    `hours` can be rendered. `modelled_hours` and `derating` ride along under
    "ok" for the same reason `power.Runtime` keeps them: so the conservatism
    can be seen rather than trusted.
    """
    if status not in POWER_RUNTIME_STATUSES:
        status = POWER_RUNTIME_NO_CAPACITY
    if status != POWER_RUNTIME_OK:
        return {"status": status}
    if hours is None or not math.isfinite(hours) or hours < 0:
        return {"status": POWER_RUNTIME_NO_CAPACITY}
    out = {"status": POWER_RUNTIME_OK, "hours": hours}
    if modelled_hours is not None:
        out["modelled_hours"] = modelled_hours
    if derating is not None:
        out["derating"] = derating
    return out


def power_estimate_message(current: dict, alternatives: list, runtime: dict,
                           sources: dict | None = None) -> dict:
    """The whole `power_estimate` reply, keys in the order the firmware emits.

    `sources` is `power.sources_summary()` as plain counts; None omits it,
    which only a test should do - the counts are the evidence behind `basis`.
    """
    message: dict = {"type": MSG_POWER_ESTIMATE, "basis": POWER_ESTIMATE_BASIS_MODELLED}
    if sources:
        message["sources"] = {
            key: int(sources[key])
            for key in ("datasheet", "measured", "estimate") if key in sources
        }
    message["current"] = current
    message["alternatives"] = list(alternatives)
    message["runtime"] = runtime
    return message


# --- errors -----------------------------------------------------------------
# What an error refers to. A client runs long transfers (a file read, a session
# archive) alongside ordinary commands, and without this it cannot tell "your
# backup failed" from "there is no sensor to clean" - so an unrelated failure
# would tear down an in-flight transfer. The value is the CMD_* the error
# answers, or blank when the error belongs to no particular request.
ERROR_REF_NONE = ""


def error_message(message: str, ref: str = ERROR_REF_NONE, **extra) -> dict:
    """Build an error frame, tagged with the command it answers.

    ``ref`` is omitted from the frame when blank, so a reader that does not know
    the field sees exactly the bytes it saw before this existed.
    """
    frame = {"type": MSG_ERROR, "message": message}
    if ref:
        frame["ref"] = ref
    frame.update(extra)
    return frame


def error_concerns(message: dict, ref: str) -> bool:
    """True if an error frame is about ``ref`` (or does not say what it is about).

    An untagged error - an older daemon, or a genuinely unattributed failure -
    is treated as concerning everything, which is the behaviour that existed
    before the field did.
    """
    tag = message.get("ref") or ERROR_REF_NONE
    return not tag or tag == ref


def firmware_supports_sleep(firmware: str) -> bool:
    """True if [firmware] ("2.1") is >= the version that added Sleep/Wake."""
    try:
        parts = tuple(int(p) for p in str(firmware).split(".")[:2])
    except (TypeError, ValueError):
        return False
    return len(parts) == 2 and parts >= MIN_FIRMWARE_FOR_SLEEP


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
