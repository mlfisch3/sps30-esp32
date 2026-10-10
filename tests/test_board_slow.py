"""Slow tests: the firmware corrections that only a real board can show.

    ./run_tests.sh slow --board 192.168.1.226
    ./run_tests.sh slow --board 192.168.1.226 --board-writable

Every other correction from the same day is guarded by a host test that runs in
the standard pack. These are not, and it is not for want of trying: they
live in ``firmware/components/net/net_server.c`` and ``.../store/store.c``,
neither of which the host suite can compile - ``net_server.c`` is FreeRTOS,
lwIP and esp_timer, and ``store.c`` is ``esp_partition`` and erase timing. See
``firmware/test/Makefile``, which says why ``store.c`` is deliberately absent.

What is left is the thing each defect actually did, measured over the socket:

* a transfer paced by a poll instead of by the link (:func:`test_a_backfill_runs
  _at_the_speed_of_the_link`),
* every ``get_file`` reading the whole 4,480-page ring off flash before its first
  frame (:func:`test_a_small_backfill_does_not_pay_for_the_whole_ring`),
* a client task that never blocks, starving the idle task the watchdog watches
  (:func:`test_two_concurrent_transfers_do_not_reset_the_board`),
* an import claim checked outside the lock, so two importers cross sessions
  (:func:`test_two_simultaneous_imports_never_cross_sessions`).

The ring walk is the one of these with a host test *as well*
(``firmware/test/test_store_walk.c``): the walk itself was lifted into
``store_walk.c``, which is pure, so the rows it returns and the reads it costs
are both checked without a board. What only a board can say is what those reads
cost in **time**, which is what the test here measures.

The third of those found something else when it was first run on hardware, and
that one *is* guarded on the host now: the refusal a second importer should have
got was issued once per chunk, which wedged both clients instead of refusing
either. The rule it broke was lifted into ``net_import.c``, which is pure C and
compiles anywhere, and ``firmware/test/test_net_import.c`` holds it. This module
still owns the half no host test can reach - that the two ends agree.

These are slow because they are: the throughput test moves a whole session, and
the watchdog test has to run past the five seconds the watchdog gives.

**Nothing here fabricates a session.** The import test re-sends readings the
board already holds, which merge as duplicates; it never invents a reading, a
timestamp or a session name. It still asks for ``--board-writable`` before it
sends anything at all, because a controller whose import claim is broken is
precisely what it detects, and on such a controller the crossing it looks for is
real corruption of the record.
"""
from __future__ import annotations

import json
import socket
import threading
import time

import pytest

import wire
from tests.conftest import skip_could_not_run, skip_not_asked

pytestmark = pytest.mark.slow


# The pre-fix ceiling, from the code that set it. `LINK_WAIT_MS` is 100 ms and a
# 2 KiB `file_data` frame carries thirteen readings (pinned on the host by
# test_wire.c's `a 2 KiB backfill frame carries thirteen readings`), so a client
# that only ever woke on the poll could not exceed ten frames a second whatever
# the network could do. The floor below is well clear of that in the direction
# the fix moves, and well under what a working link gives, so it does not become
# a flaky assertion about somebody's Wi-Fi.
POLL_PACED_READINGS_PER_S = 13 * (1000 / 100)   # 130
MIN_READINGS_PER_S = 300

# Long enough to run past CONFIG_ESP_TASK_WDT_TIMEOUT_S, which the generated
# sdkconfig sets to five seconds for both cores' idle tasks.
WATCHDOG_SOAK_S = 20.0

# What a `get_file` may spend before its first frame. Nothing is in that window
# but the controller locating the session's pages, so it is the fixed cost of the
# request with everything else - the link, the encoding, the app - excluded. The
# whole-ring walk it replaced is ~0.7 s by the firmware's own figure, and what
# replaced it reads about 0.8% of the flash; see
# `firmware/test/test_store_walk.c`, which counts the reads.
FIXED_GET_FILE_BUDGET_S = 1.0


class BoardClient:
    """The protocol the app speaks, and nothing else.

    Deliberately a local copy rather than an import of ``tools/transfer_probe.py``:
    that is a hand tool with a ``main`` and its own argument parsing, and a test
    that reaches into it would be pinned to its command line.
    """

    def __init__(self, host: str, port: int = wire.DEFAULT_PORT, timeout: float = 30.0):
        self.sock = socket.create_connection((host, port), timeout)
        self.sock.settimeout(timeout)
        self.buf = b""
        self.hello = self.wait("hello")

    def send(self, **command) -> None:
        self.sock.sendall((json.dumps(command) + "\n").encode())

    def messages(self):
        while True:
            while b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                if line.strip():
                    yield json.loads(line)
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("the controller closed the connection")
            self.buf += chunk

    def wait(self, *types: str, ignore_readings: bool = True):
        for msg in self.messages():
            if msg.get("type") in types:
                return msg
            if not ignore_readings and msg.get("type") == wire.MSG_READING:
                return msg
        raise AssertionError("unreachable")

    def list_files(self) -> list[dict]:
        self.send(cmd=wire.CMD_LIST_FILES)
        return self.wait(wire.MSG_FILES).get("files") or []

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def _endpoint(request) -> tuple[str, int]:
    """``--board`` as (host, port). ``host`` or ``host:port``, or a skip."""
    given = request.config.getoption("--board")
    if not given:
        skip_not_asked("needs --board <ip> (see ./run_tests.sh slow)")
    host, _, port = given.partition(":")
    return host, int(port) if port else wire.DEFAULT_PORT


@pytest.fixture
def board(request):
    """A controller on the LAN, or a skip that says how to supply one."""
    host, port = _endpoint(request)
    try:
        client = BoardClient(host, port)
    except OSError as e:
        skip_could_not_run(f"no controller answering at {host}:{port} ({e})")
    yield client
    client.close()


def _writable(request) -> tuple[str, int]:
    if not request.config.getoption("--board-writable"):
        skip_not_asked("sends data to the board; needs --board-writable")
    return _endpoint(request)


def _biggest_session(client: BoardClient, at_least: int) -> str:
    """The stored session with the most rows, or a skip naming what was found."""
    files = [f for f in client.list_files() if (f.get("rows") or 0) >= at_least]
    if not files:
        skip_could_not_run(
            f"no stored session with at least {at_least} rows; the board has "
            f"{len(client.list_files())} session(s). Let it record for a while first"
        )
    return max(files, key=lambda f: f["rows"])["name"]


def _drain_session(client: BoardClient, name: str, since: int = 0) -> tuple[int, float]:
    """Ask for a whole session and return (rows received, seconds it took)."""
    client.send(cmd=wire.CMD_GET_FILE, name=name, since_index=since)
    rows = 0
    started = time.monotonic()
    while True:
        msg = client.wait(wire.MSG_FILE_DATA, wire.MSG_ERROR)
        if msg["type"] == wire.MSG_ERROR:
            raise AssertionError(f"the controller refused get_file: {msg['message']}")
        rows += len(msg.get("rows") or [])
        if msg.get("done"):
            return rows, time.monotonic() - started


def test_a_backfill_runs_at_the_speed_of_the_link(board):
    """Finding: a backfill was throttled to about 17 kB/s by a wait loop.

    The client loop asked the link for writability only when a line was already
    buffered. Between frames of a ``get_file`` there is none, so it waited on
    readability alone - and a phone that has sent its request and is now only
    listening never makes the socket readable. Every frame therefore cost a full
    ``LINK_WAIT_MS``.

    That is not a slow link, it is a loop that will not go faster than its own
    poll, so measuring the rate is what tells the two apart. The fix asks for
    writability whenever a transfer still has rows, which hands the pace to TCP.
    """
    name = _biggest_session(board, at_least=600)
    rows, seconds = _drain_session(board, name)
    rate = rows / max(seconds, 1e-6)
    assert rows > 0, f"{name} delivered no rows"
    assert rate > MIN_READINGS_PER_S, (
        f"{name}: {rows} readings in {seconds:.1f}s = {rate:.0f}/s. "
        f"A loop paced by LINK_WAIT_MS cannot exceed {POLL_PACED_READINGS_PER_S:.0f}/s, "
        f"so this is the poll setting the speed and not the link"
    )


def test_a_small_backfill_does_not_pay_for_the_whole_ring(board):
    """Finding: every ``get_file`` read the entire 4,480-page ring off flash.

    ``store_cursor_open`` started at the oldest slot with ``remaining = slots``
    and ``store_cursor_next`` then read all 512 bytes of every slot to run
    ``store_peek_page``, which reads 48 of them. So a request that returns twenty
    rows read 2.19 MB of flash to find them, exactly as a request returning
    twenty thousand did - a **fixed** cost, and the firmware's own note at
    ``store.c`` puts a whole-ring pass at 512 bytes a slot at "most of a second".

    That is invisible on a big transfer and is the whole of a small one. Pass 4
    recorded a real reconnect: 153 s away, 161 stored readings back, of which
    about 63% of the elapsed time was the walk. It is also un-yieldable - the
    first ``store_cursor_next`` runs thousands of flash reads before it returns a
    single reading - which is what freeze pass 3 saw as ``IDLE0`` starved for
    five seconds while a reconnect was served.

    The fix reads the 48-byte header to decide and the whole page only to use it,
    and bounds the walk to the slots the session's own pages can be in. What that
    leaves is measurable from the client alone and needs no instrumentation on
    the board: **the delay before the first frame of a tiny request**. Nothing
    else happens in it.

    The threshold is deliberately far from both numbers. Pre-fix this is ~0.7 s
    on the board's own figure; post-fix the host suite counts 326 slot reads for
    the same shape of request against 4,480, and 17,968 bytes of flash against
    2,293,760. A second is a wide gate that the old code cannot pass and a Wi-Fi
    hiccup cannot fail.
    """
    name = _biggest_session(board, at_least=600)
    files = {f["name"]: f for f in board.list_files()}
    rows_held = files[name].get("rows") or 0

    # Ask for the last twenty readings, the way a phone that was away for twenty
    # seconds does. `since_index` is a watermark, so this is an ordinary request.
    board.send(cmd=wire.CMD_GET_FILE, name=name, since_index=max(rows_held - 20, 0))
    asked = time.monotonic()
    first = board.wait(wire.MSG_FILE_DATA, wire.MSG_ERROR)
    before_first_frame = time.monotonic() - asked
    assert first["type"] != wire.MSG_ERROR, (
        f"the controller refused get_file: {first.get('message')}"
    )
    rows = len(first.get("rows") or [])
    while not first.get("done"):
        first = board.wait(wire.MSG_FILE_DATA)
        rows += len(first.get("rows") or [])

    assert before_first_frame < FIXED_GET_FILE_BUDGET_S, (
        f"{name}: {before_first_frame * 1e3:.0f} ms passed before the first frame of a "
        f"{rows}-row request. Nothing is in that time but the controller finding the "
        f"session's pages, so this is the whole-ring walk still being paid for"
    )


def test_two_concurrent_transfers_do_not_reset_the_board(board, request):
    """Finding: the client task never yielded during a transfer.

    ``tcp_wait`` is ``select()`` with the socket in ``writefds``, and a socket
    with room returns from it at once - so for the whole of a ``get_file`` the
    client task was runnable and never blocked. Acquisition at priority 5
    preempts it, but the FreeRTOS idle task at priority 0 does not, and the task
    watchdog is armed at boot over both cores' idle tasks with five seconds.
    The part is dual-core and these tasks are unpinned: one saturating client
    merely slows things down, two reboot the board mid-transfer.

    So this needs two of them, and it needs to run past the five seconds. A
    reboot shows up as the live session being replaced: the name is derived from
    the run, so it cannot survive one.
    """
    host, port = _endpoint(request)
    name = _biggest_session(board, at_least=600)
    live_before = board.hello.get("csv")
    assert live_before, "hello named no live session; is the board recording?"

    errors: list[BaseException] = []
    stop = threading.Event()

    def hammer():
        try:
            with BoardClient(host, port) as c:
                while not stop.is_set():
                    _drain_session(c, name)
        except BaseException as e:  # noqa: BLE001 - reported, not swallowed
            errors.append(e)

    threads = [threading.Thread(target=hammer, daemon=True) for _ in range(2)]
    for t in threads:
        t.start()
    time.sleep(WATCHDOG_SOAK_S)
    stop.set()
    for t in threads:
        t.join(timeout=60)

    assert not errors, f"a transfer died while both were running: {errors[0]!r}"

    with BoardClient(host, port) as after:
        assert after.hello.get("csv") == live_before, (
            f"the live session changed from {live_before} to {after.hello.get('csv')} "
            f"across {WATCHDOG_SOAK_S:.0f}s of two concurrent transfers - the board "
            f"restarted, which is the task watchdog firing on a starved idle task"
        )


def test_two_simultaneous_imports_never_cross_sessions(board, request):
    """Finding: the import claim was checked outside the store lock.

    ``store_import_open`` decided whether anyone else was importing before it
    took the lock, and client tasks are preemptive at equal priority, so two
    that arrived together both proceeded. The second ``store_merge_begin`` reset
    the state the first was filling and ``s.import_name`` became the second
    client's - after which the first client's pages were filed into the second
    client's session.

    That is worse than a dropped row: the reading reappears in the wrong
    recording and nothing looks wrong afterwards.

    What it asserts, and why it is not "one of them must be refused". Whether
    the two transfers actually overlap is up to the scheduler, and a claim that
    holds perfectly still lets both through when they happen not to collide - so
    a refusal is evidence the window was reached, not something the fix promises.
    The invariant that holds either way is about where the rows went, and both
    clients are sending back readings the board already holds:

    * every row must come back a **duplicate**, because every row is already
      there under its own name. A row filed under the *other* session is new
      there, and the controller counts it in ``added``.
    * neither session's row count may move, for the same reason.

    Nothing here is fabricated; see this module's docstring. A refusal, if one
    happens, is reported so a passing run says whether it raced at all.

    **And both importers must report at all.** That is the assertion this test
    kept failing on, on 2026-08-03, three runs including one with the board
    otherwise idle: the claim was sound and nothing crossed, but the *refusal*
    was issued once per chunk into a one-line-deep reply buffer that the sender -
    busy in ``sendall`` - was not reading, so the controller stopped reading too
    and both ends sat there until they timed out. ``an importer never reported:
    {}`` is what that looks like from here. See ``firmware/components/net/
    net_import.h``, and ``firmware/test/test_net_import.c`` for the same rule
    checked without a board.
    """
    host, port = _writable(request)
    files = [f for f in board.list_files() if (f.get("rows") or 0) > 0]
    if len(files) < 2:
        skip_could_not_run(f"needs two non-empty stored sessions; the board has {len(files)}")
    pair = sorted(files, key=lambda f: f["rows"], reverse=True)[:2]
    before = {f["name"]: f["rows"] for f in pair}

    exported: dict[str, tuple[list[str], list[list[str]]]] = {}
    for f in pair:
        board.send(cmd=wire.CMD_GET_FILE, name=f["name"], since_index=0)
        columns: list[str] = []
        rows: list[list[str]] = []
        while True:
            msg = board.wait(wire.MSG_FILE_DATA, wire.MSG_ERROR)
            assert msg["type"] != wire.MSG_ERROR, msg.get("message")
            columns = msg.get("columns") or columns
            rows.extend(msg.get("rows") or [])
            if msg.get("done"):
                break
        exported[f["name"]] = (columns, rows)

    results: dict[str, dict] = {}
    ready = threading.Barrier(len(pair))

    def importer(name: str):
        columns, rows = exported[name]
        order = [columns.index(c) if c in columns else -1 for c in wire.csv_columns(name)]
        text = [
            ",".join(r[i] if 0 <= i < len(r) else "" for i in order) for r in rows
        ]
        with BoardClient(host, port) as c:
            ready.wait(timeout=30)  # both open their import in the same breath
            seq = 0
            for start in range(0, len(text), 8):
                batch = text[start:start + 8]
                c.send(
                    cmd=wire.CMD_PUT_FILE, name=name, rows="\n".join(batch),
                    seq=seq, done=start + 8 >= len(text),
                )
                seq += 1
            results[name] = c.wait(wire.MSG_IMPORT_RESULT, wire.MSG_ERROR)

    threads = [threading.Thread(target=importer, args=(f["name"],)) for f in pair]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=300)

    assert len(results) == len(pair), f"an importer never reported: {results}"

    # `busy` is the controller's word for "somebody else had the claim", and it
    # is the only one that means refused - every `import_result` carries a
    # message, including the ones that worked, so the presence of one says
    # nothing.
    refused = [
        name for name, r in results.items()
        if r["type"] == wire.MSG_ERROR or r.get("state") == "busy"
    ]
    print(
        f"\n  one import was refused ({refused[0]}), so the claim was contested"
        if refused else
        "\n  neither import was refused: they did not overlap this run, and the"
        " assertions below are the ones that hold either way"
    )

    for name, r in results.items():
        if r["type"] == wire.MSG_ERROR:
            continue  # refused outright, and said so: nothing of its was written
        assert r.get("added", 0) == 0, (
            f"{name}: the controller added {r['added']} row(s) from a re-import "
            f"of readings it already held. Every row should have come back a "
            f"duplicate, so these were placed somewhere they were not - which is "
            f"the claim letting two importers into one merge. (A ring that "
            f"wrapped mid-test could also evict rows and re-add them; check "
            f"`storage` if the count is small.) Full result: {r}"
        )

    # A fresh connection, deliberately. This test's own fixture has spent the
    # whole transfer not reading its socket, and a client that has accepted
    # nothing for CLIENT_STALL_MS is disconnected by design - see
    # `net_server.c`. Asking it for the row counts would make a passing fix look
    # like a failure, and asks the same question of the board either way.
    with BoardClient(host, port) as after_client:
        after = {
            f["name"]: f["rows"] for f in after_client.list_files() if f["name"] in before
        }
    for name, rows in before.items():
        assert after.get(name) == rows, (
            f"{name} went from {rows} rows to {after.get(name)} after two "
            f"simultaneous imports of readings it already held. Rows that merge "
            f"as duplicates cannot change a count, so this is one client's pages "
            f"filed under the other's session"
        )
