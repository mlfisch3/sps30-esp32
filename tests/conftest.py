"""Pytest configuration: the two opt-in categories, and shared fixtures.

The standard pack needs no sensor, no board and no network, and runs in about
four seconds. Two markers take a test out of it, and each is opted back in with
its own flag so neither can be dragged in by accident:

``@pytest.mark.hardware``
    Needs a physically connected SPS30 on a serial port. ``--run-hardware``,
    which you pass on the Raspberry Pi.

``@pytest.mark.slow``
    Too long-running to belong in a pack whose value is that it gets run - it
    measures a transfer, or watches the sensor for a while, or drives a board
    over the network. ``--run-slow``, plus ``--board <ip>`` for the ones that
    need an ESP32 controller to talk to.

``run_tests.sh`` names the commands; see its header.

Two kinds of skip, and why the difference is shouted about
----------------------------------------------------------
A skip is not one thing. Either you did not ask for the test (no ``--run-slow``,
no ``--board``), or you did and this machine could not run it (Textual absent,
the board silent). The first is a clean run. The second is a run that quietly
did less than it looks like it did, and that is worse than a failure because it
manufactures confidence: six GUI tests once skipped themselves into a green
"260 passed" for want of ``textual-plotext``, and the truth was 266.

So every skip reason carries one of the two prefixes below, the summary ends
with a verdict line naming which kind happened, and a degraded run exits
non-zero unless ``--allow-degraded`` says that is knowingly accepted. A skip
reason with neither prefix is reported as unclassified rather than assumed
harmless - the fail-safe direction is "shown".
"""
import pytest

#: You did not ask for this test. Not running it is the correct outcome.
NOT_ASKED = "not asked for: "
#: You did ask, and this machine could not. The run is degraded, not clean.
COULD_NOT_RUN = "could not run here: "


def skip_not_asked(reason):
    """Skip because the run did not opt in to this test."""
    pytest.skip(NOT_ASKED + reason)


def skip_could_not_run(reason):
    """Skip because the environment could not provide what the test needs."""
    pytest.skip(COULD_NOT_RUN + reason)


def pytest_addoption(parser):
    parser.addoption(
        "--run-hardware",
        action="store_true",
        default=False,
        help="run tests that require a physically connected SPS30",
    )
    parser.addoption(
        "--run-slow",
        action="store_true",
        default=False,
        help="run tests marked slow, which are excluded from the standard pack",
    )
    parser.addoption(
        "--port",
        action="store",
        default="/dev/serial0",
        help="serial port of the SPS30 (hardware tests)",
    )
    parser.addoption(
        "--board",
        action="store",
        default=None,
        help="address of an ESP32 controller on the LAN (slow board tests)",
    )
    parser.addoption(
        "--board-writable",
        action="store_true",
        default=False,
        help=(
            "permit slow tests that send data back to --board. They only ever "
            "re-import readings the board already holds, but a controller whose "
            "import claim is broken is exactly what they detect, so this is "
            "never assumed"
        ),
    )
    parser.addoption(
        "--allow-degraded",
        action="store_true",
        default=False,
        help=(
            "exit 0 even when tests were skipped because this machine could "
            "not run them. The skips are still reported; this only says you "
            "have seen them and accept the run is incomplete"
        ),
    )


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "hardware: requires a physically connected SPS30 sensor"
    )
    config.addinivalue_line(
        "markers", "slow: too long-running for the standard pack; needs --run-slow"
    )


def pytest_collection_modifyitems(config, items):
    hardware_ok = config.getoption("--run-hardware")
    slow_ok = config.getoption("--run-slow")
    skip_hardware = pytest.mark.skip(
        reason=NOT_ASKED + "needs --run-hardware and a connected SPS30"
    )
    skip_slow = pytest.mark.skip(
        reason=NOT_ASKED + "needs --run-slow; see ./run_tests.sh slow"
    )
    for item in items:
        if not hardware_ok and "hardware" in item.keywords:
            item.add_marker(skip_hardware)
        if not slow_ok and "slow" in item.keywords:
            item.add_marker(skip_slow)


# ---------------------------------------------------------------------------
# The verdict: did this pack run, or did it only look like it did?
# ---------------------------------------------------------------------------
# pytest's own last line answers "was what ran green?". That is a different
# question from "did it run?", and only the second one is worth trusting on a
# machine you have not set up yourself. This adds the second answer underneath,
# in as many words, so a glance at the bottom of the output cannot mistake one
# for the other.

_SKIPS = []  # (kind, reason) for every skip in this session


def _skip_reason(report):
    """The reason text out of a skip report, whatever shape pytest put it in."""
    longrepr = report.longrepr
    if isinstance(longrepr, tuple) and len(longrepr) == 3:
        text = str(longrepr[2])
    else:
        text = str(longrepr)
    prefix = "Skipped: "
    return text[len(prefix):] if text.startswith(prefix) else text


def _classify(reason):
    if reason.startswith(NOT_ASKED):
        return "not-asked", reason[len(NOT_ASKED):]
    if reason.startswith(COULD_NOT_RUN):
        return "degraded", reason[len(COULD_NOT_RUN):]
    return "unclassified", reason


def pytest_sessionstart(session):
    _SKIPS.clear()


def pytest_runtest_logreport(report):
    if report.skipped:
        _SKIPS.append(_classify(_skip_reason(report)))


def _incomplete():
    """Skips that mean the pack did less than it appears to have done."""
    return [(kind, why) for kind, why in _SKIPS if kind != "not-asked"]


def pytest_sessionfinish(session, exitstatus):
    """A degraded run is not a passing run, so do not exit 0 claiming it was.

    ``--allow-degraded`` turns this back off, because "I know, and I accept it"
    is a legitimate position - it just has to be taken deliberately rather than
    inherited from a machine that was never set up.
    """
    if exitstatus != 0 or session.config.getoption("--allow-degraded"):
        return
    if _incomplete():
        session.exitstatus = pytest.ExitCode.TESTS_FAILED


def pytest_unconfigure(config):
    """Write the verdict last, because the last line is the one people read.

    ``pytest_terminal_summary`` would put it *above* pytest's own
    "266 passed in 4s", and then the line a hurried eye lands on is still the
    one that cannot tell "it ran and was fine" from "it did not run".
    """
    tr = config.pluginmanager.get_plugin("terminalreporter")
    if tr is None or not hasattr(tr, "stats"):  # -p no:terminal, or a usage error
        return
    passed = len(tr.stats.get("passed", []))
    failed = len(tr.stats.get("failed", []))
    errors = len(tr.stats.get("error", []))
    incomplete = _incomplete()
    not_asked = len(_SKIPS) - len(incomplete)

    counts = f"{passed} passed, {failed} failed, {errors} errors, {len(_SKIPS)} skipped"

    if failed or errors:
        tr.write_sep("=", "VERDICT: it ran, and it was not fine", red=True, bold=True)
        tr.write_line(counts)
        return

    if not incomplete and passed == 0:
        # Green, but nothing happened - `./run_tests.sh slow` with no --board,
        # say. True and unremarkable, and it must not read as "it was fine".
        tr.write_sep("=", "VERDICT: nothing ran", yellow=True, bold=True)
        tr.write_line(
            f"0 tests ran; {not_asked} were not asked for by this command. "
            f"Nothing failed because nothing was tried.",
            yellow=True,
        )
        return

    if not incomplete:
        tr.write_sep("=", "VERDICT: it ran, and it was fine", green=True, bold=True)
        tr.write_line(
            f"{passed} passed; every test this run asked for actually ran"
            + (f" ({not_asked} not asked for)." if not_asked else ".")
        )
        # The tests this command excluded, said out loud. A `-m` expression
        # deselects them before they can even show up as skips, so without this
        # line a clean verdict reads as full coverage while another pack can sit
        # red indefinitely - which is exactly how two red layout tests hid
        # behind weeks of green standard runs in the apk pack.
        deselected = len(tr.stats.get("deselected", []))
        if deselected:
            tr.write_line(
                f"{deselected} test(s) belong to packs this command excluded and "
                f"did not run: their state is unknown, not green. The other "
                f"packs: ./run_tests.sh slow, ./run_tests.sh hardware."
            )
        return

    tr.write_sep("=", "VERDICT: INCOMPLETE - it did not all run", yellow=True, bold=True)
    tr.write_line(counts, yellow=True)
    tr.write_line(
        f"{len(incomplete)} test(s) this run asked for did not run on this "
        f"machine. That is not a pass:",
        yellow=True,
    )
    tally = {}
    for kind, why in incomplete:
        tally[(kind, why)] = tally.get((kind, why), 0) + 1
    for (kind, why), n in sorted(tally.items(), key=lambda kv: -kv[1]):
        label = "unclassified skip" if kind == "unclassified" else "could not run"
        tr.write_line(f"  {n:>3}x  [{label}] {why}", yellow=True)
    if any("odule" in why or "mport" in why for _kind, why in incomplete):
        tr.write_line(
            "That looks like a missing package: "
            "python3 -m pip install -r requirements-dev.txt "
            "(the list, with reasons, is tools/check_test_deps.py)",
            yellow=True,
        )
    if not config.getoption("--allow-degraded"):
        tr.write_line("Exiting non-zero. Pass --allow-degraded to accept this "
                      "knowingly.", yellow=True)


class FakeSerial:
    """A minimal serial stand-in that plays back canned responses.

    Built from a {request_bytes: response_bytes} map. When the driver writes a
    request frame, the matching response is queued for it to read back - so the
    full driver stack is exercised with zero hardware.
    """

    def __init__(self, responses, enforce_state=False):
        self.responses = {bytes.fromhex(k): bytes.fromhex(v) for k, v in responses.items()}
        self._rx = bytearray()
        self.written = []
        # Opt-in device-FSM modelling; see _state_error below.
        self.enforce_state = enforce_state
        self.measuring = False

    def reset_input_buffer(self):
        self._rx.clear()

    def write(self, data):
        data = bytes(data)
        self.written.append(data)
        if self.enforce_state:
            err = self._state_error(data)
            if err is not None:
                self._rx.extend(err)
                return len(data)
            self._track_state(data)
        if data in self.responses:
            self._rx.extend(self.responses[data])
        return len(data)

    # The real SPS30 refuses commands that do not suit its current mode - Start
    # while already measuring answers 0x43 "command not allowed in current
    # state". Replaying canned responses hides that, so `enforce_state` models
    # just enough of the device FSM to catch it (a real bug found on hardware).
    _START = bytes.fromhex("7e0000020103f97e")
    _STOP = bytes.fromhex("7e000100fe7e")
    _SLEEP = bytes.fromhex("7e001000ef7e")
    _WAKE = bytes.fromhex("7e007d3100ee7e")
    # addr 00, cmd echo, state 0x43, len 0, checksum.
    _ERR_START = bytes.fromhex("7e00004300bc7e")
    _ERR_SLEEP = bytes.fromhex("7e00104300ac7e")

    def _state_error(self, data):
        if data == self._START and self.measuring:
            return self._ERR_START
        if data == self._SLEEP and self.measuring:
            return self._ERR_SLEEP  # Sleep is only legal from idle
        return None

    def _track_state(self, data):
        if data == self._START:
            self.measuring = True
        elif data in (self._STOP, self._SLEEP):
            self.measuring = False

    def flush(self):
        # The wake sequence flushes after its raw 0xFF pulse; nothing to do here.
        pass

    def read(self, n=1):
        out = bytes(self._rx[:n])
        del self._rx[: len(out)]
        return out

    def close(self):
        pass


@pytest.fixture
def fake_serial_factory(monkeypatch):
    """Return a factory that patches serial.Serial to a FakeSerial and opens SPS30."""
    import sps30

    def make(responses):
        fake = FakeSerial(responses)
        monkeypatch.setattr(sps30.serial, "Serial", lambda *a, **k: fake)
        sensor = sps30.SPS30(port="fake")
        return sensor, fake

    return make


@pytest.fixture
def sensor(request):
    """A live SPS30 on real hardware (hardware tests only)."""
    import sps30

    port = request.config.getoption("--port")
    dev = sps30.SPS30(port=port)
    yield dev
    dev.close()
