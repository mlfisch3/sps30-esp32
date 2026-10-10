"""Hardware-free tests for the SPS30 acquisition daemon.

The FakeSerial from ``conftest`` replays real captured SPS30 responses (see
``tests/vectors.py``), so the full driver stack runs with zero hardware while we
drive the daemon's FSM one ``step()`` at a time. Intervals are made no-ops so
there are no real sleeps and every test is fast and deterministic.
"""
import csv
import socket
import time
from datetime import datetime

import pytest

import sps30
import wire
from sps30_daemon import (
    CLIENT_QUEUE_MAX,
    ClientHandler,
    Daemon,
    read_file_rows,
    scan_files,
)
from tests import vectors
from tests.conftest import FakeSerial

# A complete response map: probe (info/serial/version), start, read, stop.
FULL_RESPONSES = dict(
    [
        vectors.PRODUCT_TYPE,
        vectors.SERIAL_NUMBER,
        vectors.VERSION,
        vectors.START,
        vectors.READ,
        vectors.STOP,
    ]
)

# Everything needed to detect + start, but no READ answer -> reads time out.
NO_READ_RESPONSES = dict(
    [
        vectors.PRODUCT_TYPE,
        vectors.SERIAL_NUMBER,
        vectors.VERSION,
        vectors.START,
        vectors.STOP,
    ]
)

NOOP_SLEEP = lambda _s: None


def make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES, **kw):
    """Build a Daemon whose sensor factory yields a FakeSerial-backed SPS30."""
    monkeypatch.setattr(
        sps30.serial, "Serial", lambda *a, **k: FakeSerial(responses)
    )
    daemon = Daemon(
        port="fake",
        tcp_port=0,
        host="127.0.0.1",
        csv_dir=tmp_path,
        interval=0.0,
        poll_interval=0.0,
        sensor_factory=sps30.SPS30,
        sleep=NOOP_SLEEP,
        **kw,
    )
    return daemon


# ---------------------------------------------------------------------------
# CSV / wire formatting
# ---------------------------------------------------------------------------
def test_format_index_zero_padded_width_7():
    assert wire.format_index(1) == "0000001"
    assert wire.format_index(1234567) == "1234567"
    assert len(wire.format_index(5)) == wire.INDEX_WIDTH == 7


def test_alert_thresholds_sends_the_lowest_enabled_line_per_field():
    """The only thing about a user's alert rules that ever leaves their phone.

    A controller whose ring has wrapped had to know which readings were worth
    copying out of its way, and the case that matters is the one where no phone
    was present to ask - so it holds one number per field. The **minimum**, and
    nothing else: keeping a superset of what any rule would fire on is right, and
    keeping less than some rule would fire on means an alert whose evidence is
    gone by the time anyone looks. Mirrored in `AppConfig.preservationThresholds`.
    """
    rules = [
        {"field": "pm2_5", "threshold": 55.0, "enabled": True},
        {"field": "pm2_5", "threshold": 20.0, "enabled": True},
        {"field": "pm10", "threshold": 90.0, "enabled": True},
        {"field": "pm2_5", "threshold": 5.0, "enabled": False},
    ]
    assert wire.alert_thresholds(rules) == {"pm2_5": 20.0, "pm10": 90.0}


def test_alert_thresholds_refuses_anything_that_is_not_a_line():
    assert wire.alert_thresholds([]) == {}
    assert wire.alert_thresholds(None) == {}
    # Watching nothing is a thing a user is allowed to choose, and it has to be
    # expressible: an empty result means "stop keeping anything", not "unchanged".
    assert wire.alert_thresholds([{"field": "pm2_5", "threshold": 20.0, "enabled": False}]) == {}
    assert wire.alert_thresholds([{"field": "pm2_5", "threshold": float("nan")}]) == {}
    assert wire.alert_thresholds([{"field": "pm2_5", "threshold": float("inf")}]) == {}
    assert wire.alert_thresholds([{"field": "pm2_5", "threshold": 0.0}]) == {}
    assert wire.alert_thresholds([{"field": "pm2_5", "threshold": -1.0}]) == {}
    assert wire.alert_thresholds([{"field": "not_a_field", "threshold": 20.0}]) == {}
    assert wire.alert_thresholds([{"field": "pm2_5", "threshold": "twenty"}]) == {}


def test_the_shipped_preservation_line_is_the_apps_own_default():
    """One number in three places: wire.py, the firmware and the app's AlertRule.

    The firmware side is cross-checked in `firmware/test/test_wire.c` against the
    vector this constant generates, so all three fail together rather than
    drifting apart quietly.
    """
    assert wire.ALERT_DEFAULT_THRESHOLDS == {"pm2_5": 20.0}
    assert set(wire.ALERT_DEFAULT_THRESHOLDS) <= set(wire.FIELDS)
    assert wire.ALERT_AUTO_DEFAULT is True


def test_clean_due_in_hours_floors_and_rounds():
    assert wire.clean_due_in_hours(0.0) == 168.0
    assert wire.clean_due_in_hours(wire.CLEAN_AFTER_SECONDS) == 0.0
    assert wire.clean_due_in_hours(wire.CLEAN_AFTER_SECONDS * 10) == 0.0  # never negative
    assert wire.clean_due_in_hours(3600.0) == 167.0


def test_clean_due_in_hours_reports_a_corrupt_count_as_due_now():
    """Which way an unreadable count fails, pinned at the definition.

    Only a corrupt stored value reaches this, so the question is not what the
    arithmetic gives but which direction is safe. "Due now" costs one twelve
    second fan cycle; "a full week left" throws away the one thing this figure
    exists to say. The firmware mirror answered the second for a while, which is
    why the direction is written down here and cross-checked from here into
    `firmware/test/wire_vectors_generated.h`.
    """
    assert wire.clean_due_in_hours(float("nan")) == 0.0
    assert wire.clean_due_in_hours(float("inf")) == 0.0
    # And this one used to escape as `inf`, which json.dumps emits as `Infinity`
    # - not JSON, and not a number any client can render.
    assert wire.clean_due_in_hours(float("-inf")) == 0.0


def test_csv_filename_pattern():
    dt = datetime(2026, 7, 17, 9, 30, 5, tzinfo=wire.EASTERN)
    assert wire.csv_filename(dt) == "SPS30-20260717_093005.csv"


def test_measurement_row_columns_and_eastern_timestamp():
    m = sps30.Measurement(*range(10))
    when = datetime(2026, 1, 15, 12, 0, 0, tzinfo=wire.EASTERN)  # winter -> EST
    row = wire.measurement_row(7, when, m)
    assert list(row.keys()) == wire.CSV_COLUMNS
    assert row["index"] == "0000007"
    # ISO-8601 Eastern, seconds precision, with the -05:00 EST offset.
    assert row["timestamp"] == "2026-01-15T12:00:00-05:00"
    assert row["pm1_0"] == 0 and row["typical_size"] == 9


def test_a_clockless_session_has_two_more_columns_and_the_same_thirteen():
    """The relative header extends the classic one; it does not replace it.

    This is the whole compatibility argument, asserted rather than asserted-to:
    the thirteen columns a reader has always known keep their names *and* their
    positions, and the board's own clock goes on the end. A build that has never
    heard of `rel_boot` reads such a file exactly as it reads any other.
    """
    assert wire.CSV_COLUMNS_RELATIVE[: len(wire.CSV_COLUMNS)] == wire.CSV_COLUMNS
    assert wire.CSV_COLUMNS_RELATIVE[-2:] == [wire.COL_REL_BOOT, wire.COL_REL_MS]
    # And the classic list itself did not move. A clockless session is the only
    # thing that gets the wider header, so nothing the Pi writes changes at all.
    assert wire.CSV_COLUMNS[-1] == "event"
    assert wire.COL_REL_BOOT not in wire.CSV_COLUMNS


def test_csv_columns_follows_the_session_name():
    dated = wire.csv_filename(datetime(2026, 8, 3, 1, 45, 0, tzinfo=wire.EASTERN))
    relative = wire.rel_session_name(1202, 0x821)
    assert wire.csv_columns(dated) == wire.CSV_COLUMNS
    assert wire.csv_columns(relative) == wire.CSV_COLUMNS_RELATIVE
    # A name that is neither gets the classic header: the wider one exists only
    # for a session that is known to carry the board's clock.
    assert wire.csv_columns("notes.txt") == wire.CSV_COLUMNS


# ---------------------------------------------------------------------------
# FSM transitions
# ---------------------------------------------------------------------------
def test_checking_to_measuring_transition(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    assert daemon._state == wire.STATE_CHECKING

    daemon.step()  # probe + start -> measuring

    assert daemon._state == wire.STATE_MEASURING
    assert daemon._info["serial"] == vectors.EXPECTED_SERIAL
    assert daemon._info["product"] == vectors.EXPECTED_PRODUCT
    assert daemon._info["firmware"] == vectors.EXPECTED_FIRMWARE
    # A fresh CSV exists with just the header.
    files = list(tmp_path.glob("SPS30-*.csv"))
    assert len(files) == 1
    assert files[0].read_text().splitlines() == [",".join(wire.CSV_COLUMNS)]


def test_begin_measuring_stops_before_start(monkeypatch, tmp_path):
    # A prior unclean shutdown (or systemd restart while running) can leave the
    # sensor measuring, so start_measurement would return 0x43. The daemon must
    # send a defensive Stop before Start so startup is deterministic.
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # detect + begin measuring

    written = daemon._sensor._ser.written
    stop = bytes.fromhex(vectors.STOP[0])
    start = bytes.fromhex(vectors.START[0])
    assert stop in written and start in written
    assert written.index(stop) < written.index(start)


def test_reading_is_written_and_flushed(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring
    daemon.step()  # one read -> one row written

    assert daemon._index == 1
    csv_file = next(tmp_path.glob("SPS30-*.csv"))
    lines = csv_file.read_text().splitlines()
    assert lines[0] == ",".join(wire.CSV_COLUMNS)
    assert len(lines) == 2  # header + one data row (already flushed to disk)
    data = lines[1].split(",")
    assert data[0] == "0000001"
    # Columns: index, timestamp, pm1_0, pm2_5, ...  -> data[2] is pm1_0.
    assert float(data[2]) == pytest.approx(
        vectors.EXPECTED_MEASUREMENT["pm1_0"], abs=1e-3
    )
    assert float(data[3]) == pytest.approx(
        vectors.EXPECTED_MEASUREMENT["pm2_5"], abs=1e-3
    )


def test_measurement_row_event_column():
    m = sps30.Measurement(*range(10))
    when = datetime(2026, 1, 15, 12, 0, 0, tzinfo=wire.EASTERN)
    assert wire.measurement_row(1, when, m)["event"] == ""  # blank by default
    flagged = wire.measurement_row(1, when, m, "startup")
    assert flagged["event"] == "startup"
    assert list(flagged.keys()) == wire.CSV_COLUMNS  # event is a real column


def test_startup_spike_is_flagged_then_clears(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.interval = 5.0  # settle window = round(SETTLE_SECONDS/5) = 4 rows
    daemon.step()  # -> measuring: flags the first 4 readings "startup"
    for _ in range(6):
        daemon.step()  # 6 readings

    rows = [
        line.split(",")
        for line in next(tmp_path.glob("SPS30-*.csv")).read_text().splitlines()[1:]
    ]
    events = [r[-1] for r in rows]  # "event" is the last column
    assert events[:4] == ["startup"] * 4
    assert all(e == "" for e in events[4:])  # settled -> blank


def test_auto_recover_returns_to_checking_after_repeated_read_failures(monkeypatch, tmp_path):
    from sps30_daemon import READ_FAILURES_BEFORE_LOSS

    daemon = make_daemon(monkeypatch, tmp_path, responses=NO_READ_RESPONSES)
    daemon.step()  # detect + start -> measuring
    assert daemon._state == wire.STATE_MEASURING

    # A sensor that has genuinely gone away stays silent, so the loss is still
    # detected - just not on the strength of a single missed response.
    for _ in range(READ_FAILURES_BEFORE_LOSS):
        daemon.step()

    assert daemon._state == wire.STATE_CHECKING
    assert daemon._sensor is None


def test_one_missed_read_does_not_end_the_session(monkeypatch, tmp_path):
    """Regression: days of history vanished from the plot.

    A single timed-out read used to tear the session down, which closed the CSV
    and opened a new one on recovery. The client backfills the *current* file,
    so every glitch silently truncated its history back to that moment - three
    times in one day on a marginal supply.
    """
    from sps30_daemon import READ_FAILURES_BEFORE_LOSS

    daemon = make_daemon(monkeypatch, tmp_path, responses=NO_READ_RESPONSES)
    daemon.step()
    csv_before = daemon._csv_name

    for _ in range(READ_FAILURES_BEFORE_LOSS - 1):
        daemon.step()

    assert daemon._state == wire.STATE_MEASURING, "gave up too early"
    assert daemon._csv_name == csv_before, "the recording must not be replaced"


def test_recovery_continues_the_same_recording(monkeypatch, tmp_path):
    """After a dropout the readings belong in the file they were already going
    to: same sensor, same settings, same intent - one recording with a gap."""
    from sps30_daemon import READ_FAILURES_BEFORE_LOSS

    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    csv_before = daemon._csv_name
    assert csv_before

    # Force a loss, then let it find the sensor again.
    daemon._handle_sensor_loss("simulated dropout")
    assert daemon._state == wire.STATE_CHECKING
    daemon.step()  # re-detect

    assert daemon._state == wire.STATE_MEASURING
    assert daemon._csv_name == csv_before, (
        f"recovery started a new file ({daemon._csv_name}) instead of continuing {csv_before}"
    )
    assert len(list(tmp_path.glob("SPS30-*.csv"))) == 1


def test_a_user_restart_does_start_a_new_recording(monkeypatch, tmp_path):
    """The continuation is only for dropouts - an explicit restart still means
    a fresh session, which is what "all" and the session views key off."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    daemon.step()  # record a reading, advancing the index
    first = daemon._csv_name
    assert daemon._index > 0

    daemon.enqueue_command(wire.CMD_RESTART, {}, None)
    daemon._drain_commands()

    assert daemon._csv_name != first, "restart must open a new recording"
    assert daemon._index == 0, "a new session restarts the index"
    assert len(list(tmp_path.glob("SPS30-*.csv"))) == 2


def test_sessions_started_in_the_same_second_do_not_overwrite(monkeypatch, tmp_path):
    """CSV names are second-resolution; a rapid restart must not truncate the
    previous recording."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    first = daemon._csv_name
    daemon._open_new_csv()
    assert daemon._csv_name != first
    assert len(list(tmp_path.glob("SPS30-*.csv"))) == 2


# ---------------------------------------------------------------------------
# Command queue
# ---------------------------------------------------------------------------
def test_pause_and_resume_via_command_queue(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring
    daemon.step()  # write one row -> index advances to 1
    assert daemon._index == 1

    daemon.enqueue_command(wire.CMD_PAUSE, {})
    daemon.step()
    assert daemon._state == wire.STATE_PAUSED
    assert daemon._csv_file is None  # CSV closed while paused

    daemon.enqueue_command(wire.CMD_RESUME, {})
    daemon.step()  # resume rolls a fresh CSV, then reads one row
    assert daemon._state == wire.STATE_MEASURING
    assert daemon._csv_file is not None
    # The CSV was rolled (index reset), so this step wrote row #1 again - had it
    # merely appended to the old file the index would now be 2.
    assert daemon._index == 1
    csv_file = next(tmp_path.glob("SPS30-*.csv"))
    assert len(csv_file.read_text().splitlines()) == 2  # header + one row


def test_fsync_every_is_configurable_and_clamped(monkeypatch, tmp_path):
    from sps30_daemon import FSYNC_EVERY, build_parser

    # Explicit value is honored; values < 1 are clamped up to 1.
    d = make_daemon(monkeypatch, tmp_path, fsync_every=5)
    assert d.fsync_every == 5
    d0 = make_daemon(monkeypatch, tmp_path, fsync_every=0)
    assert d0.fsync_every == 1

    # CLI exposes --fsync-every and defaults to the module constant.
    args = build_parser().parse_args([])
    assert args.fsync_every == FSYNC_EVERY
    args = build_parser().parse_args(["--fsync-every", "7"])
    assert args.fsync_every == 7


def test_fsync_runs_on_configured_interval(monkeypatch, tmp_path):
    import sps30_daemon

    calls = []
    monkeypatch.setattr(sps30_daemon.os, "fsync", lambda fd: calls.append(fd))
    daemon = make_daemon(monkeypatch, tmp_path, fsync_every=2)
    daemon.step()  # -> measuring (opens CSV; no data-row fsync yet)
    before = len(calls)
    daemon.step()  # row 1 -> flush only, no fsync
    assert len(calls) == before
    daemon.step()  # row 2 -> reaches the interval -> fsync
    assert len(calls) == before + 1


def test_restart_forces_measuring_state(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring
    daemon.enqueue_command(wire.CMD_RESTART, {})
    daemon.step()
    assert daemon._state == wire.STATE_MEASURING
    assert daemon._sensor is not None


def test_raw_command_returns_response_to_client(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()  # -> measuring

    class FakeClient:
        def __init__(self):
            self.sent = []

        def send(self, msg, lossless=False):
            self.sent.append(msg)

    client = FakeClient()
    # Ask for the product-type frame; the FakeSerial answers with its response.
    daemon.enqueue_command(wire.CMD_RAW, {"hex": vectors.PRODUCT_TYPE[0]}, client)
    daemon.step()

    assert len(client.sent) == 1
    msg = client.sent[0]
    assert msg["type"] == wire.MSG_RAW
    assert msg["request"] == vectors.PRODUCT_TYPE[0]
    assert msg["response"] == vectors.PRODUCT_TYPE[1]


# ---------------------------------------------------------------------------
# File serving helpers (CMD_GET_FILE / CMD_LIST_FILES)
# ---------------------------------------------------------------------------
def _write_csv(path, n_rows):
    lines = [",".join(wire.CSV_COLUMNS)]
    for i in range(1, n_rows + 1):
        vals = [wire.format_index(i), "2026-01-15T12:00:0%d-05:00" % (i % 10)]
        vals += ["%.2f" % (i + j) for j in range(len(wire.FIELDS))]
        lines.append(",".join(vals))
    path.write_text("\n".join(lines) + "\n")


def test_get_file_since_index_selects_only_newer_rows(tmp_path):
    path = tmp_path / "SPS30-20260115_120000.csv"
    _write_csv(path, 5)

    columns, rows = read_file_rows(path, since_index=2)

    assert columns == wire.CSV_COLUMNS
    # Only indices strictly greater than 2 -> 3, 4, 5.
    assert [r[0] for r in rows] == ["0000003", "0000004", "0000005"]


def test_get_file_without_since_returns_all_rows(tmp_path):
    path = tmp_path / "SPS30-20260115_120000.csv"
    _write_csv(path, 4)
    columns, rows = read_file_rows(path, since_index=None)
    assert len(rows) == 4


def test_scan_files_reports_rows_and_bytes(tmp_path):
    _write_csv(tmp_path / "SPS30-20260115_120000.csv", 3)
    _write_csv(tmp_path / "SPS30-20260116_120000.csv", 7)
    files = scan_files(tmp_path)
    assert [f["name"] for f in files] == [
        "SPS30-20260116_120000.csv",
        "SPS30-20260115_120000.csv",
    ]
    by_name = {f["name"]: f for f in files}
    assert by_name["SPS30-20260115_120000.csv"]["rows"] == 3
    assert by_name["SPS30-20260116_120000.csv"]["rows"] == 7
    assert all(f["bytes"] > 0 for f in files)
    # start timestamp comes from the first data row (cheap first-line parse)
    assert by_name["SPS30-20260115_120000.csv"]["start"].endswith("-05:00")


def test_scan_files_counts_row_without_trailing_newline(tmp_path):
    # The cheap buffered newline count must still include a final row that has
    # no trailing newline, and a header-only / empty file must report 0 rows.
    path = tmp_path / "SPS30-20260115_120000.csv"
    _write_csv(path, 4)
    text = path.read_text().rstrip("\n")  # drop the trailing newline
    path.write_text(text)
    files = scan_files(tmp_path)
    assert files[0]["rows"] == 4

    header_only = tmp_path / "SPS30-20260116_120000.csv"
    header_only.write_text(",".join(wire.CSV_COLUMNS) + "\n")
    empty = tmp_path / "SPS30-20260117_120000.csv"
    empty.write_text("")
    by_name = {f["name"]: f for f in scan_files(tmp_path)}
    assert by_name["SPS30-20260116_120000.csv"]["rows"] == 0
    assert by_name["SPS30-20260117_120000.csv"]["rows"] == 0


# ---------------------------------------------------------------------------
# Walking a listing that does not fit one frame
# ---------------------------------------------------------------------------
# `file_list` used to be whatever the sender could fit and nothing about what it
# could not. Measured on the ESP32 board on 2026-08-04: it held 84 sessions and
# named 64, so "copy everything the sensor has recorded" wrote 64 of them, the
# other 20 could not be fetched or plotted or offered to the erase gate, and the
# shortfall grew by one on every restart.
#
# `wire.file_list_page` is the fix and it is the protocol rather than one host's
# behaviour, so it is tested here, against wire.py itself.
def _listing(n: int) -> list[dict]:
    return [{"name": f"SPS30-2026080{i // 10}_{i:06d}.csv", "rows": i} for i in range(n)]


def test_file_list_page_walks_the_whole_listing():
    files = _listing(84)
    seen: list[dict] = []
    offset, pages = 0, 0
    while True:
        page = wire.file_list_page(files, offset, limit=64)
        assert page[wire.FILES_OFFSET] == offset
        assert page[wire.FILES_TOTAL] == 84
        seen.extend(page["files"])
        pages += 1
        assert pages <= 4, "the walk did not terminate"
        nxt = wire.file_list_next(page)
        if nxt is None:
            break
        offset = nxt
    assert pages == 2
    assert [f["name"] for f in seen] == [f["name"] for f in files]


def test_file_list_page_says_when_there_is_nothing_following():
    whole = wire.file_list_page(_listing(3), 0)
    assert wire.FILES_NEXT not in whole
    assert wire.file_list_next(whole) is None
    assert whole[wire.FILES_TOTAL] == 3

    empty = wire.file_list_page([], 0)
    assert empty["files"] == []
    assert empty[wire.FILES_TOTAL] == 0
    assert wire.file_list_next(empty) is None


def test_file_list_page_past_the_end_is_empty_not_an_error():
    # A listing can shrink under a client part way through walking it - the ring
    # reclaims its oldest pages - and the honest answer to "what comes after the
    # end" is "nothing", not a refusal the client has to interpret.
    page = wire.file_list_page(_listing(3), 99, limit=64)
    assert page["files"] == []
    assert page[wire.FILES_OFFSET] == 99
    assert wire.file_list_next(page) is None
    assert wire.file_list_page(_listing(3), -5)[wire.FILES_OFFSET] == 0


def test_file_list_next_refuses_to_stand_still_or_go_backwards():
    # The end condition is `next`, so a controller that answers with its own
    # offset - or one before it - would put a client in a loop that never ends.
    # Everything on this wire is unauthenticated; the client is the one that has
    # to be unloopable.
    at = {wire.FILES_OFFSET: 64, wire.FILES_NEXT: 64}
    assert wire.file_list_next(at) is None
    assert wire.file_list_next({wire.FILES_OFFSET: 64, wire.FILES_NEXT: 10}) is None
    assert wire.file_list_next({wire.FILES_OFFSET: 64, wire.FILES_NEXT: 65}) == 65
    assert wire.file_list_next({wire.FILES_NEXT: True}) is None
    assert wire.file_list_next({wire.FILES_NEXT: "8"}) is None
    assert wire.file_list_next({}) is None


def test_the_daemon_answers_a_listing_from_an_offset(tmp_path):
    import sps30_daemon

    # This host has no frame limit and so never pages of its own accord: a page
    # size here would silently shorten the frozen Pi app's listing, which has
    # never heard of `next`. It still honours an offset when one is asked for,
    # and still says where the page sits and how much there is.
    for day in range(1, 6):
        _write_csv(tmp_path / f"SPS30-2026011{day}_120000.csv", 2)

    class _Handler:
        send = staticmethod(lambda message, lossless=False: sent.append(message))
        daemon = type("D", (), {"csv_dir": tmp_path})()

    sent: list[dict] = []
    sps30_daemon.ClientHandler._send_file_list(_Handler, {})
    whole = sent.pop()
    assert len(whole["files"]) == 5
    assert whole[wire.FILES_TOTAL] == 5
    assert wire.file_list_next(whole) is None

    sps30_daemon.ClientHandler._send_file_list(_Handler, {wire.FILES_OFFSET: 3})
    tail = sent.pop()
    assert [f["name"] for f in tail["files"]] == [f["name"] for f in whole["files"][3:]]
    assert tail[wire.FILES_OFFSET] == 3
    assert tail[wire.FILES_TOTAL] == 5

    # A nonsense offset is read as "from the beginning" rather than crashing the
    # connection: nothing on this wire is authenticated.
    sps30_daemon.ClientHandler._send_file_list(_Handler, {wire.FILES_OFFSET: "lots"})
    assert len(sent.pop()["files"]) == 5


# ---------------------------------------------------------------------------
# The storage occupancy map
# ---------------------------------------------------------------------------
# `storage_map` is the per-page seat map of the controller's flash. The Pi
# daemon never answers it (it has a filesystem, not a ring), so what is tested
# here is the protocol itself: the 2-bit packing and the frame builder that the
# C and Kotlin mirrors are held to byte for byte through the wire vectors.

def test_page_states_pack_to_known_bytes():
    # Page i lives in bits (i % 4) * 2 of byte i // 4, little end first:
    # [DATA, EMPTY, ANCHOR, BAD] is 0b11_10_00_01 = 0xE1.
    packed = wire.pack_page_states(
        [wire.PAGE_STATE_DATA, wire.PAGE_STATE_EMPTY,
         wire.PAGE_STATE_ANCHOR, wire.PAGE_STATE_BAD])
    assert packed == "4Q=="


def test_page_states_round_trip_at_awkward_lengths():
    # Lengths straddling every byte boundary, so the final partial byte and the
    # base64 padding are both exercised rather than only the tidy multiple.
    for pages in (1, 2, 3, 4, 5, 7, 8, 4480, 4992):
        states = [wire.PAGE_STATES[i % 4] for i in range(pages)]
        assert wire.unpack_page_states(wire.pack_page_states(states), pages) == states


def test_page_states_refuse_what_they_cannot_mean():
    with pytest.raises(ValueError):
        wire.pack_page_states([4])
    with pytest.raises(ValueError):
        wire.unpack_page_states("not base64!", 4)
    # A map of the wrong size is a disagreement about the geometry, and cells
    # rendered anyway would land on the wrong pages.
    with pytest.raises(ValueError):
        wire.unpack_page_states(wire.pack_page_states([0, 0, 0, 0]), 8)


def test_storage_map_message_mirrors_the_chip():
    ring = [wire.PAGE_STATE_DATA] * 6 + [wire.PAGE_STATE_EMPTY] * 2
    message = wire.storage_map_message(512, 12, [
        {"id": wire.MAP_SECTION_RING, "start": 0, "pages": 8, "head": 6,
         "wraps": 3, "states": ring},
        {"id": wire.MAP_SECTION_ALERT_BODIES, "start": 8, "pages": 4, "head": 0,
         "wraps": 0, "states": [wire.PAGE_STATE_EMPTY] * 4},
    ])
    line = wire.encode(message)
    back = wire.decode(line)
    assert back["type"] == wire.MSG_STORAGE_MAP
    assert back["page_bytes"] == 512
    assert back["pages"] == 12
    ring_back, bodies_back = back["sections"]
    assert ring_back["id"] == wire.MAP_SECTION_RING
    assert (ring_back["start"], ring_back["pages"]) == (0, 8)
    assert (ring_back["head"], ring_back["wraps"]) == (6, 3)
    assert wire.unpack_page_states(ring_back["cells"], 8) == ring
    assert wire.unpack_page_states(bodies_back["cells"], 4) == [0, 0, 0, 0]


def test_storage_map_message_refuses_a_map_that_disagrees_with_itself():
    # States shorter than the section: the cells would silently shift onto the
    # wrong pages, which is exactly the faked precision the map exists to avoid.
    with pytest.raises(ValueError):
        wire.storage_map_message(512, 8, [
            {"id": "ring", "start": 0, "pages": 8, "head": 0, "wraps": 0,
             "states": [0] * 7}])
    # A head outside the section points at a page the section does not have.
    with pytest.raises(ValueError):
        wire.storage_map_message(512, 8, [
            {"id": "ring", "start": 0, "pages": 8, "head": 8, "wraps": 0,
             "states": [0] * 8}])


def test_storage_map_is_a_controller_only_command():
    # The Pi daemon answers `storage_map` with its ordinary unknown-command
    # refusal, exactly like `storage` and the drain family: it has a filesystem,
    # and none of the flash-ring vocabulary is its job. Registering the command
    # here is what keeps test_every_wire_command_is_dispatchable honest about
    # that, rather than demanding the Pi grow a handler.
    assert wire.CMD_STORAGE_MAP in wire.CONTROLLER_ONLY_COMMANDS


# ---------------------------------------------------------------------------
# End-to-end smoke: real TCP server, a client receives HELLO + READING
# ---------------------------------------------------------------------------
def test_tcp_client_receives_hello_and_reading(monkeypatch, tmp_path):
    # Use tiny real sleeps here so the acquisition thread actually cycles.
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon._sleep = lambda _s: time.sleep(0.005)
    daemon.start()
    try:
        sock = socket.create_connection(("127.0.0.1", daemon.tcp_port), timeout=5)
        sock.settimeout(5)
        buffer = bytearray()
        seen = {}
        deadline = time.time() + 5
        while time.time() < deadline and not (
            wire.MSG_HELLO in seen and wire.MSG_READING in seen
        ):
            data = sock.recv(4096)
            if not data:
                break
            buffer.extend(data)
            for msg in wire.iter_messages(buffer):
                seen[msg["type"]] = msg
        sock.close()
    finally:
        daemon.stop()

    assert wire.MSG_HELLO in seen
    assert wire.MSG_READING in seen
    reading = seen[wire.MSG_READING]
    assert reading["index"] == "0000001" or int(reading["index"]) >= 1
    for field in wire.FIELDS:
        assert field in reading


# --- per-client fan-out queue: lossy vs lossless -----------------------------
def _bare_handler():
    """A ClientHandler with a dummy socket; threads are created but never
    started, so we can exercise the queue policy directly."""
    return ClientHandler(sock=object(), addr=("test", 0), daemon=object())


def test_live_broadcasts_are_lossy_past_the_cap():
    ch = _bare_handler()
    for i in range(CLIENT_QUEUE_MAX + 50):
        ch.send({"type": wire.MSG_READING, "i": i})  # lossy (default)
    # Never grows past the cap; the oldest lossy messages are dropped.
    assert len(ch._outq) == CLIENT_QUEUE_MAX
    # The survivors are the newest ones (oldest dropped).
    kept = [m["i"] for m, _lossless in ch._outq]
    assert kept[-1] == CLIENT_QUEUE_MAX + 49
    assert min(kept) == 50


def test_file_transfer_chunks_are_never_dropped():
    ch = _bare_handler()
    # Saturate with lossy live readings, then enqueue lossless file chunks. The
    # chunks preserve themselves by evicting lossy messages, keeping the total
    # bounded rather than dropping a chunk.
    for i in range(CLIENT_QUEUE_MAX):
        ch.send({"type": wire.MSG_READING, "i": i})
    for j in range(20):
        ch.send({"type": wire.MSG_FILE_DATA, "j": j}, lossless=True)
    kept = [m for m, lossless in ch._outq if lossless]
    assert len(kept) == 20                     # no chunk dropped
    assert len(ch._outq) == CLIENT_QUEUE_MAX    # total stayed bounded (lossy evicted)

    # Even a flood of further lossy sends cannot evict the chunks.
    for i in range(CLIENT_QUEUE_MAX):
        ch.send({"type": wire.MSG_READING, "i": 10_000 + i})
    kept = [m for m, lossless in ch._outq if lossless]
    assert len(kept) == 20
    assert all(m["type"] == wire.MSG_FILE_DATA for m in kept)


def test_all_lossless_backlog_grows_past_cap_rather_than_dropping():
    ch = _bare_handler()
    # When nothing droppable remains, the queue exceeds the soft cap instead of
    # corrupting a transfer.
    for j in range(CLIENT_QUEUE_MAX + 20):
        ch.send({"type": wire.MSG_FILE_DATA, "j": j}, lossless=True)
    assert len(ch._outq) == CLIENT_QUEUE_MAX + 20


# ---------------------------------------------------------------------------
# Acquisition profiles / low-power duty cycling
# ---------------------------------------------------------------------------
DUTY_RESPONSES = dict(
    [
        vectors.PRODUCT_TYPE,
        vectors.SERIAL_NUMBER,
        vectors.VERSION,
        vectors.START,
        vectors.READ,
        vectors.STOP,
        vectors.SLEEP,
        vectors.WAKE,
    ]
)


def test_normalize_profile_defaults_and_clamps():
    assert wire.normalize_profile(None) == wire.DEFAULT_PROFILE
    assert wire.normalize_profile({"mode": "nonsense"})["mode"] == wire.MODE_CONTINUOUS
    # Sensirion say never warm up for less than 8s, whatever the caller asks.
    assert wire.normalize_profile({"warmup_s": 1.0})["warmup_s"] == wire.WARMUP_MIN
    # Negative / non-numeric fields fall back rather than taking acquisition down.
    assert wire.normalize_profile({"interval": -5})["interval"] == 1.0
    assert wire.normalize_profile({"interval": "abc"})["interval"] == 1.0
    # A cycle must be long enough to contain the work it describes.
    tight = wire.normalize_profile(
        {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 5, "warmup_s": 30, "average_s": 30}
    )
    assert tight["cycle_s"] == 60.0


def test_normalize_profile_bounds_every_field_from_above():
    """An unbounded profile is a host that stops recording and still looks well.

    A cycle of a thousand days used to be accepted and adopted: the daemon stays
    connected, keeps answering, keeps calling itself healthy, and never writes a
    reading again. Nothing in the app can tell that from a working sensor, which
    makes it the quietest way to lose the record - so every field is bounded at
    the top as well as at the bottom.
    """
    huge = wire.normalize_profile(
        {
            "mode": wire.MODE_DUTY_CYCLE,
            "interval": 1e12,
            "cycle_s": 86_400_000.0,  # a thousand days
            "warmup_s": 1e9,
            "average_s": 1e9,
        }
    )
    assert huge["interval"] == wire.INTERVAL_MAX
    assert huge["cycle_s"] == wire.CYCLE_MAX
    assert huge["warmup_s"] == wire.WARMUP_MAX
    assert huge["average_s"] == wire.AVERAGE_MAX

    # Infinity clamps, because what it asks for is unambiguous; NaN falls back
    # to the default, because there is no end to clamp it to.
    assert wire.normalize_profile({"cycle_s": float("inf")})["cycle_s"] == wire.CYCLE_MAX
    assert wire.normalize_profile({"cycle_s": float("nan")})["cycle_s"] == wire.CYCLE_DEFAULT

    # The ceilings sit above everything the app offers, so bounding the profile
    # cannot refuse a setting a user can actually reach.
    for interval in AppConfigLike.CONTINUOUS_INTERVALS:
        assert wire.normalize_profile({"interval": interval})["interval"] == interval
    for period in AppConfigLike.DUTY_CYCLE_PERIODS:
        assert wire.normalize_profile({"cycle_s": period})["cycle_s"] == period

    # The two clamps cannot contradict each other: raising `cycle_s` to hold the
    # work it describes must never push it past its own ceiling.
    maximal = wire.normalize_profile(
        {"cycle_s": 1e12, "warmup_s": 1e12, "average_s": 1e12}
    )
    assert maximal["cycle_s"] <= wire.CYCLE_MAX
    assert maximal["cycle_s"] >= maximal["warmup_s"] + maximal["average_s"]


class AppConfigLike:
    """The Android client's offered settings, from `AppConfig.kt`.

    Copied rather than imported because it is Kotlin. It is here so that a
    ceiling lowered past something the app can ask for fails a test instead of
    turning a control the user can reach into one that silently does nothing.
    """

    CONTINUOUS_INTERVALS = (1.0, 10.0, 30.0, 60.0)
    DUTY_CYCLE_PERIODS = (300.0, 900.0, 1800.0, 3600.0, 43_200.0, 86_400.0)


def test_firmware_gate_for_sleep():
    assert wire.firmware_supports_sleep("2.1")
    assert wire.firmware_supports_sleep("2.0")
    assert not wire.firmware_supports_sleep("1.9")
    assert not wire.firmware_supports_sleep("")
    assert not wire.firmware_supports_sleep(None)


def test_wake_sends_ff_pulse_before_the_wake_frame(fake_serial_factory):
    """In Sleep-Mode the UART is off; a bare 0xFF must precede the Wake frame."""
    sensor, fake = fake_serial_factory(DUTY_RESPONSES)
    sensor.wake()
    assert b"\xff" in fake.written, "no 0xFF wake pulse was sent"
    pulse = fake.written.index(b"\xff")
    frame = fake.written.index(bytes.fromhex(vectors.WAKE[0]))
    assert pulse < frame, "the 0xFF pulse must come before the Wake command"


def test_wake_retries_then_gives_up(fake_serial_factory):
    # No WAKE response mapped -> every attempt times out.
    sensor, fake = fake_serial_factory({})
    with pytest.raises(sps30.SPS30Error, match="wake failed after 2 attempts"):
        sensor.wake(attempts=2)
    assert fake.written.count(b"\xff") == 2


def test_duty_cycle_averages_and_sleeps_between_measurements(monkeypatch, tmp_path):
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={
            "mode": wire.MODE_DUTY_CYCLE,
            "cycle_s": 900.0, "warmup_s": 30.0, "average_s": 30.0,
        },
    )
    daemon.step()  # checking -> measuring
    assert daemon._state == wire.STATE_MEASURING
    daemon.step()  # one full duty cycle

    sent = [w.hex() for w in daemon._sensor._ser.written]
    assert vectors.SLEEP[0] in sent, "sensor was never put to sleep"
    assert vectors.STOP[0] in sent, "measurement was never stopped"
    # Exactly one averaged point recorded for the cycle, not one per read.
    rows = list(tmp_path.glob("SPS30-*.csv"))
    assert len(rows) == 1
    body = rows[0].read_text().strip().splitlines()
    assert len(body) == 2, f"expected header + one averaged row, got {len(body)}"
    assert daemon._state == wire.STATE_SLEEPING


def test_duty_cycle_wakes_before_the_next_cycle(monkeypatch, tmp_path):
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()
    daemon.step()
    assert daemon._asleep
    daemon._state = wire.STATE_MEASURING  # next cycle comes round
    daemon._sensor._ser.written.clear()
    daemon.step()
    sent = [w.hex() for w in daemon._sensor._ser.written]
    assert vectors.WAKE[0] in sent, "a sleeping sensor must be woken first"


def test_set_profile_is_refused_on_old_firmware(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    daemon._info["firmware"] = "1.9"
    client = _RecordingClient()
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE, {"profile": {"mode": wire.MODE_DUTY_CYCLE}}, client
    )
    daemon._drain_commands()
    assert daemon.profile["mode"] == wire.MODE_CONTINUOUS
    assert any("firmware 2.0" in m.get("message", "") for m in client.sent)


def test_set_profile_switches_mode_and_announces(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    client = _RecordingClient()
    daemon.add_client(client)
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE,
        {"profile": {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 300.0}}, client,
    )
    daemon._drain_commands()
    assert daemon.profile["mode"] == wire.MODE_DUTY_CYCLE
    assert daemon.profile["cycle_s"] == 300.0
    assert any(m.get("type") == wire.MSG_PROFILE for m in client.sent)


def test_estimated_current_matches_the_app_note(monkeypatch, tmp_path):
    """Sensirion's worked example: 60s measuring per 15min -> ~3.5 mA average."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    assert daemon.estimated_average_ma() == pytest.approx(55.0)
    daemon.profile = wire.normalize_profile(
        {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0,
         "warmup_s": 30.0, "average_s": 30.0}
    )
    assert daemon.estimated_average_ma() == pytest.approx(3.7, abs=0.2)


def test_scheduled_clean_fires_after_a_week_of_run_time(monkeypatch, tmp_path):
    """The sensor's own auto-clean counter resets on power-down, so duty cycling
    would never trigger it - the daemon has to drive it."""
    from sps30_daemon import CLEAN_AFTER_SECONDS

    daemon = make_daemon(
        monkeypatch, tmp_path, responses=dict(DUTY_RESPONSES, **dict([vectors.CLEAN])),
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()
    daemon._run_seconds = CLEAN_AFTER_SECONDS + 1
    daemon.step()
    sent = [w.hex() for w in daemon._sensor._ser.written]
    assert vectors.CLEAN[0] in sent, "no fan clean was triggered"
    assert daemon._run_seconds == 0.0, "run-time accrual should reset after a clean"


def test_run_time_accrual_persists_across_restarts(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon._run_seconds = 12345.0
    daemon._save_state()
    revived = make_daemon(monkeypatch, tmp_path)
    assert revived._run_seconds == 12345.0


def test_shutdown_parks_the_sensor_and_closes_the_csv(monkeypatch, tmp_path):
    called = []
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        shutdown_hook=lambda: called.append("poweroff"),
    )
    daemon.step()
    daemon.enqueue_command(wire.CMD_SHUTDOWN, {}, None)
    daemon._drain_commands()
    assert called == ["poweroff"]
    assert daemon._state == wire.STATE_SHUTDOWN
    assert daemon._stop.is_set()
    assert daemon._csv_file is None, "CSV must be closed before power is cut"


def test_hello_carries_profile_and_power(monkeypatch, tmp_path):
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        power_reader=lambda: {"source": "mains", "method": "none"},
    )
    daemon.step()
    hello = daemon.hello_message()
    assert hello["profile"]["mode"] == wire.MODE_CONTINUOUS
    assert hello["power"]["source"] == "mains"
    assert hello["supports_sleep"] is True
    # The Pi has an operating system to halt, so it offers `shutdown`. A bare
    # controller says False here and the app leaves the control out entirely.
    assert hello[wire.HELLO_CAN_POWER_OFF] is True


class _RecordingClient:
    """Stands in for a ClientHandler, capturing what the daemon sends it."""

    _next_id = 100

    def __init__(self, name="test-client"):
        self.sent = []
        _RecordingClient._next_id += 1
        self.client_id = _RecordingClient._next_id
        self.name = name

    def send(self, message, lossless=False):
        self.sent.append(message)


def test_every_wire_command_is_dispatchable(monkeypatch, tmp_path):
    """Guards the gap between `wire.CMD_*` and ClientHandler._dispatch.

    Adding a command to wire.py and handling it on the acquisition thread is not
    enough - the client handler validates against its own allowlist first, and a
    command missing from it is rejected as "unknown cmd" before it ever reaches
    the daemon. That is exactly how set_profile shipped broken to hardware once.

    Scoped to the commands this daemon is meant to answer.
    ``wire.CONTROLLER_ONLY_COMMANDS`` are the ones only a controller that keeps
    the record answers - sending a session back, erasing what is backed up,
    preserved alert data - and the Pi answers "unknown cmd" to them on purpose,
    because it has a filesystem and none of them are its job. Asserting it
    dispatched them would be asserting the two controllers are the same machine.
    """
    daemon = make_daemon(monkeypatch, tmp_path)
    commands = [
        getattr(wire, name) for name in dir(wire)
        if name.startswith("CMD_") and isinstance(getattr(wire, name), str)
        and getattr(wire, name) not in wire.CONTROLLER_ONLY_COMMANDS
    ]
    assert commands, "no CMD_* constants found in wire"

    for cmd in commands:
        handler = ClientHandler.__new__(ClientHandler)
        handler.daemon = daemon
        handler.client_id = 1
        handler.name = "dispatch-test"
        sent = []
        handler.send = lambda m, lossless=False: sent.append(m)
        handler._dispatch({"cmd": cmd, "name": "nope.csv"})
        unknown = [m for m in sent if "unknown cmd" in str(m.get("message", ""))]
        assert not unknown, f"{cmd!r} is not routed by ClientHandler._dispatch"


def test_duty_cycle_survives_a_sensor_that_is_already_measuring(monkeypatch, tmp_path):
    """Regression: the first cycle after switching profiles hit 0x43.

    _cmd_set_profile calls _begin_measuring, which starts measurement; the cycle
    that follows then called start_measurement again and the real sensor answered
    "command not allowed in current state". FakeSerial replays canned responses
    regardless of mode, so only `enforce_state=True` reproduces it.
    """
    monkeypatch.setattr(
        sps30.serial, "Serial",
        lambda *a, **k: FakeSerial(DUTY_RESPONSES, enforce_state=True),
    )
    daemon = Daemon(
        port="fake", tcp_port=0, host="127.0.0.1", csv_dir=tmp_path,
        poll_interval=0.0, sensor_factory=sps30.SPS30, sleep=NOOP_SLEEP,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()  # checking -> measuring (sensor is now measuring)
    assert daemon._state == wire.STATE_MEASURING
    daemon.step()  # first duty cycle, with the sensor already started
    assert daemon._state == wire.STATE_SLEEPING, (
        f"first cycle should reach sleep, got {daemon._state}: {daemon._detail}"
    )
    assert daemon._asleep


def test_profile_change_pushes_the_new_current_estimate(monkeypatch, tmp_path):
    """The modelled draw is the reason to switch modes, so it must not lag.

    Power telemetry is otherwise only polled once a minute; without an explicit
    push the options screen kept showing 55 mA after selecting low power.
    """
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        power_reader=lambda: {"source": "mains", "method": "none"},
    )
    daemon.step()
    client = _RecordingClient()
    daemon.add_client(client)
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE,
        {"profile": {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0,
                     "warmup_s": 30.0, "average_s": 30.0}},
        client,
    )
    daemon._drain_commands()
    powers = [m for m in client.sent if m.get("type") == wire.MSG_POWER]
    assert powers, "no power update was pushed with the profile change"
    assert powers[-1]["power"]["average_ma"] == pytest.approx(3.7, abs=0.2)


def test_switching_to_continuous_mid_cycle_does_not_strand_the_sensor(monkeypatch, tmp_path):
    """Regression: found on hardware. A duty cycle spans minutes, so a profile
    change lands *inside* one. The in-flight cycle used to carry on regardless,
    park the sensor in Sleep-Mode and leave the FSM in `sleeping` - a state only
    a duty cycle ever leaves, so continuous mode then streamed nothing at all.
    """
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0,
                 "warmup_s": 30.0, "average_s": 30.0},
    )
    daemon.step()  # -> measuring

    # Switch to continuous during the cycle's warm-up sleep.
    switched = {"done": False}
    original = daemon._sleep

    def sleep_then_switch(seconds):
        if not switched["done"] and daemon._state == wire.STATE_WARMUP:
            switched["done"] = True
            daemon.enqueue_command(
                wire.CMD_SET_PROFILE, {"profile": {"mode": wire.MODE_CONTINUOUS}}, None
            )
        original(seconds)

    daemon._sleep = sleep_then_switch
    daemon.step()

    assert switched["done"], "the test never injected the profile change"
    assert not daemon.duty_cycling
    assert not daemon._asleep, "sensor must not be left asleep in continuous mode"
    assert daemon._state == wire.STATE_MEASURING, (
        f"stranded in {daemon._state}: {daemon._detail}"
    )

    # And it must actually stream again.
    daemon._sleep = original
    before = daemon._index
    daemon.step()
    assert daemon._index > before, "no readings after switching back to continuous"


def test_sleeping_state_recovers_if_the_profile_is_continuous(monkeypatch, tmp_path):
    """Belt and braces for the same dead end, whatever route reached it."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    daemon._set_state(wire.STATE_SLEEPING, "stale duty-cycle state")
    daemon.step()
    assert daemon._state == wire.STATE_MEASURING


def test_detection_wakes_a_sensor_left_asleep(monkeypatch, tmp_path):
    """Regression: found on hardware, and it was a device-bricking one.

    Sleep-Mode disables the SPS30's UART, so a sensor left asleep by a previous
    duty-cycle run answers nothing on probe. Detection used to read that as "no
    sensor" and retry forever - meaning any daemon restart while in low-power
    mode lost the sensor permanently until someone power-cycled it.
    """
    class AsleepUntilWoken(FakeSerial):
        def __init__(self, responses):
            super().__init__(responses)
            self.awake = False

        def write(self, data):
            self.written.append(bytes(data))
            if bytes(data) == b"\xff":
                self.awake = True
                return 1
            if not self.awake:
                return len(data)  # silent: UART is off in Sleep-Mode
            if bytes(data) in self.responses:
                self._rx.extend(self.responses[bytes(data)])
            return len(data)

    monkeypatch.setattr(
        sps30.serial, "Serial", lambda *a, **k: AsleepUntilWoken(DUTY_RESPONSES)
    )
    daemon = Daemon(
        port="fake", tcp_port=0, host="127.0.0.1", csv_dir=tmp_path,
        poll_interval=0.0, sensor_factory=sps30.SPS30, sleep=NOOP_SLEEP,
    )
    daemon.step()
    assert daemon._state == wire.STATE_MEASURING, (
        f"a sleeping sensor was not detected: {daemon._detail}"
    )
    assert daemon._info["serial"] == vectors.EXPECTED_SERIAL
    assert not daemon._asleep


def test_duty_cycle_points_are_not_flagged_as_settling(monkeypatch, tmp_path):
    """Regression: averaged points never reached the plot.

    _begin_measuring flags SETTLE_SECONDS worth of *rows*. In continuous mode
    that is 20 rows = 20 seconds; in duty cycle it is 20 rows = 20 *cycles* = 5
    hours at the recommended 15-minute period, and the client hides flagged
    points by default - so low-power mode looked like it recorded nothing.
    """
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    daemon.step()  # -> measuring
    daemon.step()  # one cycle -> one averaged row

    csvs = list(tmp_path.glob("SPS30-*.csv"))
    assert len(csvs) == 1
    rows = list(csv.DictReader(csvs[0].read_text().splitlines()))
    assert rows, "no averaged row was written"
    assert rows[0]["event"] == "", (
        "averaged point was flagged %r and would be hidden" % (rows[0]["event"],)
    )


def test_continuous_still_flags_its_startup_spike(monkeypatch, tmp_path):
    """The duty-cycle exemption must not disarm continuous mode's spike flag."""
    daemon = make_daemon(monkeypatch, tmp_path)
    daemon.step()
    daemon.step()
    csvs = list(tmp_path.glob("SPS30-*.csv"))
    rows = list(csv.DictReader(csvs[0].read_text().splitlines()))
    assert rows[0]["event"] == "startup"


def test_duty_cycle_states_carry_a_phase_duration(monkeypatch, tmp_path):
    """The banner counts down locally, so each phase must publish its length."""
    daemon = make_daemon(
        monkeypatch, tmp_path, responses=DUTY_RESPONSES,
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 60.0,
                 "warmup_s": 8.0, "average_s": 8.0},
    )
    client = _RecordingClient()
    daemon.add_client(client)
    daemon.step()
    daemon.step()

    phases = {
        m["state"]: m.get("seconds")
        for m in client.sent if m.get("type") == wire.MSG_STATE
    }
    assert phases.get(wire.STATE_WARMUP) == 8.0
    assert phases.get(wire.STATE_SLEEPING) is not None
    assert phases[wire.STATE_SLEEPING] > 0


# ---------------------------------------------------------------------------
# Multi-user control arbitration
# ---------------------------------------------------------------------------
def _handler(daemon, name):
    """A ClientHandler with just the fields arbitration touches."""
    h = ClientHandler.__new__(ClientHandler)
    h.daemon = daemon
    h.name = name
    h.addr = ("test", 0)
    h.sent = []
    h.send = lambda m, lossless=False: h.sent.append(m)
    with ClientHandler._id_lock:
        ClientHandler._next_id += 1
        h.client_id = ClientHandler._next_id
    return h


def test_a_lone_user_never_meets_the_control_lock(monkeypatch, tmp_path):
    """Uncontested control is granted silently - one user must not have to know
    the mechanism exists."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice = _handler(daemon, "alice")
    assert daemon.may_control(alice)
    assert daemon.may_control(alice)  # and stays granted


def test_second_user_is_refused_while_the_first_holds_control(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    assert daemon.may_control(alice)
    assert not daemon.may_control(bob)
    assert daemon.control_holder_name() == "alice"


def test_viewing_commands_never_require_control(monkeypatch, tmp_path):
    """Watching must never take control away from whoever is driving."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)

    bob._dispatch({"cmd": wire.CMD_LIST_FILES})
    assert not any(m.get("needs_control") for m in bob.sent)

    bob._dispatch({"cmd": wire.CMD_PAUSE})
    denied = [m for m in bob.sent if m.get("needs_control")]
    assert denied, "a mutating command from a non-holder must be refused"
    assert "alice" in denied[0]["message"]


def test_control_expires_so_nobody_is_stranded(monkeypatch, tmp_path):
    """A holder who walks away must not lock the sensor forever."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    assert daemon.may_control(alice)
    assert not daemon.may_control(bob)

    clock = [time.monotonic() + wire.CONTROL_TTL_SECONDS + 1]
    monkeypatch.setattr(time, "monotonic", lambda: clock[0])
    assert daemon.may_control(bob), "the lock should have expired"
    assert daemon.control_holder_name() == "bob"


def test_using_control_refreshes_the_lease(monkeypatch, tmp_path):
    """Someone actively working must not be timed out mid-task."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    now = [1000.0]
    monkeypatch.setattr(time, "monotonic", lambda: now[0])
    assert daemon.may_control(alice)
    for _ in range(5):
        now[0] += wire.CONTROL_TTL_SECONDS * 0.5
        assert daemon.may_control(alice)
    assert not daemon.may_control(bob)


def test_claim_takes_over_from_an_absent_holder(monkeypatch, tmp_path):
    """Taking over is allowed on purpose: a lock only its owner can free is a
    lock that strands people when they walk off."""
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)
    bob._dispatch({"cmd": wire.CMD_CLAIM})
    assert daemon.control_holder_name() == "bob"
    assert daemon.may_control(bob)
    assert not daemon.may_control(alice)


def test_release_frees_control_for_others(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)
    alice._dispatch({"cmd": wire.CMD_RELEASE})
    assert daemon.control_holder_name() == "nobody"
    assert daemon.may_control(bob)


def test_release_by_a_non_holder_does_nothing(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.may_control(alice)
    bob._dispatch({"cmd": wire.CMD_RELEASE})
    assert daemon.control_holder_name() == "alice"


def test_disconnecting_holder_frees_control(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice, bob = _handler(daemon, "alice"), _handler(daemon, "bob")
    daemon.add_client(alice)
    daemon.may_control(alice)
    daemon.remove_client(alice)
    assert daemon.control_holder_name() == "nobody"
    assert daemon.may_control(bob)


def test_identify_names_the_client_in_the_roster(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice = _handler(daemon, "192.168.1.5")
    daemon.add_client(alice)
    alice._dispatch({"cmd": wire.CMD_IDENTIFY, "name": "Alice's phone"})
    roster = daemon.clients_message()
    assert roster["clients"] == [{"id": alice.client_id, "name": "Alice's phone"}]
    assert roster["max"] == wire.MAX_CLIENTS


def test_control_message_reports_the_holder_and_remaining_lease(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    alice = _handler(daemon, "alice")
    assert daemon.control_message()["holder"] is None
    daemon.may_control(alice)
    msg = daemon.control_message()
    assert msg["holder"] == alice.client_id
    assert msg["holder_name"] == "alice"
    assert 0 < msg["expires_in"] <= wire.CONTROL_TTL_SECONDS


# ---------------------------------------------------------------------------
# Session backup
# ---------------------------------------------------------------------------
def test_backup_streams_every_session_as_one_archive(monkeypatch, tmp_path):
    """Taking a copy must never need the control lock, and must arrive whole."""
    import base64, io, zipfile

    daemon = make_daemon(monkeypatch, tmp_path)
    for n in (1, 2, 3):
        (tmp_path / f"SPS30-2026073{n}_120000.csv").write_text(
            "index,timestamp,pm2_5,event\n" + "".join(f"{i},t{i},1.5,\n" for i in range(50))
        )

    client = _handler(daemon, "someone")
    client._dispatch({"cmd": wire.CMD_BACKUP})

    meta = [m for m in client.sent if m.get("type") == wire.MSG_BACKUP_META]
    assert meta, "no archive metadata was sent"
    assert meta[0]["sessions"] == 3
    assert meta[0]["name"].endswith(".zip")
    assert meta[0]["bytes"] > 0

    chunks = [m for m in client.sent if m.get("type") == wire.MSG_BACKUP_CHUNK]
    assert chunks, "no archive data was sent"
    assert chunks[-1]["done"], "the stream must end with an explicit done marker"
    # Sequence numbers have to be gapless or the client cannot detect truncation.
    assert [c["seq"] for c in chunks] == list(range(len(chunks)))

    blob = b"".join(base64.b64decode(c["data"]) for c in chunks if c["data"])
    assert len(blob) == meta[0]["bytes"], "declared size and delivered size disagree"

    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        assert sorted(zf.namelist()) == sorted(p.name for p in tmp_path.glob("SPS30-*.csv"))
        assert zf.read(zf.namelist()[0]).startswith(b"index,timestamp")


def test_backup_needs_no_control_claim(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    (tmp_path / "SPS30-20260731_120000.csv").write_text("index,timestamp\n1,t\n")
    holder = _handler(daemon, "alice")
    assert daemon.may_control(holder)  # someone else is driving

    reader = _handler(daemon, "bob")
    reader._dispatch({"cmd": wire.CMD_BACKUP})
    assert not any(m.get("needs_control") for m in reader.sent)
    assert any(m.get("type") == wire.MSG_BACKUP_META for m in reader.sent)


def test_backup_with_nothing_recorded_says_so(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path)
    client = _handler(daemon, "someone")
    client._dispatch({"cmd": wire.CMD_BACKUP})
    assert any("no recorded sessions" in m.get("message", "") for m in client.sent)


# --- protocol decisions: profile reset, error correlation, per-transport cap --

def test_set_profile_resets_the_fields_it_does_not_mention():
    """A partial set_profile is a whole profile, not a patch.

    Two hosts that merge differently would disagree about the resulting profile
    while both believing they honoured the same command, so the command is
    defined as stateless: what is left out goes back to its default.
    """
    running = wire.normalize_profile(
        {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 300.0, "warmup_s": 45.0}
    )
    assert running["warmup_s"] == 45.0

    applied = wire.apply_set_profile({"profile": {"interval": 5.0}}, current=running)
    assert applied["interval"] == 5.0
    assert applied["mode"] == wire.MODE_CONTINUOUS
    assert applied["warmup_s"] == wire.WARMUP_DEFAULT
    assert applied["cycle_s"] == wire.CYCLE_DEFAULT
    # An empty command is simply the default profile.
    assert wire.apply_set_profile({}, current=running) == wire.DEFAULT_PROFILE


def test_set_profile_through_the_daemon_drops_the_old_duty_settings(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path, responses=DUTY_RESPONSES)
    daemon.step()
    client = _RecordingClient()
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE,
        {"profile": {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 300.0, "warmup_s": 45.0}},
        client,
    )
    daemon._drain_commands()
    assert daemon.profile["warmup_s"] == 45.0

    # Now a command that mentions only the mode: the 45s warm-up must not survive.
    daemon.enqueue_command(
        wire.CMD_SET_PROFILE, {"profile": {"mode": wire.MODE_DUTY_CYCLE}}, client
    )
    daemon._drain_commands()
    assert daemon.profile["warmup_s"] == wire.WARMUP_DEFAULT
    assert daemon.profile["cycle_s"] == wire.CYCLE_DEFAULT


def test_error_frames_say_what_they_refer_to():
    tagged = wire.error_message("backup failed", ref=wire.CMD_BACKUP)
    assert tagged["ref"] == wire.CMD_BACKUP
    assert wire.error_concerns(tagged, wire.CMD_BACKUP)
    assert not wire.error_concerns(tagged, wire.CMD_GET_FILE)

    # Additive: an untagged error is byte-identical to what came before, and is
    # still treated as concerning everything.
    plain = wire.error_message("no sensor to clean")
    assert plain == {"type": wire.MSG_ERROR, "message": "no sensor to clean"}
    assert "ref" not in plain
    assert wire.error_concerns(plain, wire.CMD_BACKUP)
    assert wire.error_concerns({"type": wire.MSG_ERROR, "message": "x"}, wire.CMD_BACKUP)


def test_an_unrelated_daemon_error_is_not_attributed_to_a_backup(monkeypatch, tmp_path):
    """The failure this exists to stop: a clean failing must not kill a transfer."""
    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    daemon._sensor = None
    client = _RecordingClient()
    daemon.enqueue_command(wire.CMD_CLEAN, {}, client)
    daemon._drain_commands()

    errors = [m for m in client.sent if m.get("type") == wire.MSG_ERROR]
    assert errors, "expected the clean to be refused"
    assert all(e["ref"] == wire.CMD_CLEAN for e in errors)
    assert not any(wire.error_concerns(e, wire.CMD_BACKUP) for e in errors)


def test_client_capacity_is_reported_per_transport():
    assert wire.max_clients_for(wire.TRANSPORT_TCP) == wire.MAX_CLIENTS_TCP
    # Bluetooth serves one. Saying "1 of 8" there promises a second device may
    # connect when it never can.
    assert wire.max_clients_for(wire.TRANSPORT_BLE) == 1
    # An unnamed transport gets the figure clients were already being given.
    assert wire.max_clients_for(None) == wire.MAX_CLIENTS_TCP

    # The daemon counts sockets, so a BLE client is told 8; the honest number for
    # its own link is the smaller of the two.
    assert wire.effective_max_clients(8, wire.TRANSPORT_BLE) == 1
    assert wire.effective_max_clients(8, wire.TRANSPORT_TCP) == 8
    # An older daemon says nothing; the transport figure stands alone.
    assert wire.effective_max_clients(0, wire.TRANSPORT_BLE) == 1
    assert wire.effective_max_clients(None, wire.TRANSPORT_TCP) == 8


def test_hello_and_roster_name_the_transport_they_counted(monkeypatch, tmp_path):
    daemon = make_daemon(monkeypatch, tmp_path, responses=FULL_RESPONSES)
    daemon.step()
    hello = daemon.hello_message()
    assert hello["transport"] == wire.TRANSPORT_TCP
    assert hello["max_clients"] == wire.MAX_CLIENTS_TCP
    roster = daemon.clients_message()
    assert roster["transport"] == wire.TRANSPORT_TCP
    assert roster["max"] == wire.MAX_CLIENTS_TCP
