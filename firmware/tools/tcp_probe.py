#!/usr/bin/env python3
"""Talk to the controller over TCP the way the Android app does, and say so.

A plain socket and the repository's own `wire.py` framing - no app, no phone, no
firmware-side cooperation - so what it prints is evidence about the wire rather
than about this script. It is the check that the port is right, the protocol is
right and the numbers are real, and it is the one worth running first when
something looks wrong on the phone: if this is clean, the problem is not the
controller.

It validates as well as prints:

* every line must be a complete JSON object with a known `type`,
* a `reading` must carry all ten fields, a parseable ISO 8601 timestamp with an
  offset (the app drops readings whose timestamp will not parse), and an index
  that advances,
* the timestamps must track real elapsed time, which catches a clock that is set
  once and then stops,
* PM2.5 is sanity-checked against what indoor air actually is. A reading that is
  well-formed and implausible is reported as implausible, not as success.

    python3 tcp_probe.py 192.168.1.226 --seconds 60
    python3 tcp_probe.py 192.168.1.226 --raw          # every byte, unparsed
    python3 tcp_probe.py 192.168.1.226 --clients 3    # concurrency
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import socket
import sys
import threading
import time

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT))

import wire  # noqa: E402  the same module the daemon and the app agree through

# What indoor air is. Outside this, a reading is well-formed and not believable,
# and saying so is more useful than reporting a successful stream of nonsense.
PLAUSIBLE_PM25 = (0.0, 60.0)


class Probe:
    """One connection, its own thread, its own verdict."""

    def __init__(self, host: str, port: int, label: str, name: str | None):
        self.host = host
        self.port = port
        self.label = label
        self.name = name
        self.lines: list[dict] = []
        self.raw: list[bytes] = []
        self.errors: list[str] = []
        self.hello: dict | None = None
        self.readings = 0
        self.first_reading: dict | None = None
        self.last_reading: dict | None = None
        self.implausible = 0
        self.index_gaps = 0
        # Being turned away past MAX_CLIENTS is correct behaviour, not a fault,
        # so it is recorded rather than counted as a failure to connect.
        self.refused = False
        # A stalled probe connects and then never reads. It exists to prove the
        # controller's central promise - that a client cannot slow acquisition -
        # so its own outcome is not a pass or fail, the other clients' is.
        self.stall = False
        self.trickle_bytes = 0
        self.trickled = 0
        self.stall_closed_after: float | None = None

    def check_reading(self, message: dict) -> None:
        for field in wire.FIELDS:
            if field not in message:
                self.errors.append(f"reading is missing {field}")
                return
        stamp = message.get("timestamp", "")
        try:
            when = dt.datetime.fromisoformat(stamp)
        except ValueError:
            self.errors.append(f"unparseable timestamp {stamp!r}; the app would drop this")
            return
        if when.utcoffset() is None:
            self.errors.append(f"timestamp {stamp!r} has no UTC offset")
            return

        if self.last_reading is not None:
            previous = int(self.last_reading["index"])
            current = int(message["index"])
            same_session = self.last_reading.get("csv") == message.get("csv")
            if same_session and current != previous + 1:
                self.index_gaps += 1

        pm25 = float(message["pm2_5"])
        if not PLAUSIBLE_PM25[0] <= pm25 <= PLAUSIBLE_PM25[1]:
            self.implausible += 1

        self.readings += 1
        if self.first_reading is None:
            self.first_reading = message
        self.last_reading = message

    def run_stalled(self, seconds: float) -> None:
        """Connect, shrink the receive window to nothing, and barely read.

        Two shapes of bad client, and the controller handles them differently on
        purpose:

        * **stopped** (`trickle_bytes` 0) - never reads at all. The controller's
          send stops making progress and it is disconnected after its stall
          timeout, before its queue has had to drop anything.
        * **slow** (`trickle_bytes` > 0) - reads a trickle, far below the ~330
          bytes a second a reading costs. Every byte resets the stall clock, so
          it is never disconnected; instead it falls behind until its bounded
          queue starts dropping the oldest reading. That is the path drop-oldest
          exists for, and the one that must not touch anybody else.

        The tiny SO_RCVBUF is what makes either finish in seconds rather than
        minutes: with the default buffer the controller could write a quarter of
        an hour of readings before TCP pushed back at all.
        """
        started = time.monotonic()
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            # Sized against what TCP will actually do. A receiver avoiding silly
            # window syndrome will not advertise a window below about one MSS, so
            # with a 2 KB buffer a client reading a few bytes a second never
            # reopens the window at all and is *stopped*, not slow, however hard
            # it is polling. Measured: 16 B/s against a 2 KB buffer made no
            # sender-side progress for 30 s and was correctly disconnected as
            # stalled. Being slow rather than stopped needs enough buffer that
            # each read frees a window update.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF,
                            8192 if self.trickle_bytes else 2048)
            sock.settimeout(1.0)
            sock.connect((self.host, self.port))
            deadline = started + seconds
            while time.monotonic() < deadline:
                time.sleep(1.0)
                if self.trickle_bytes:
                    try:
                        self.trickled += len(sock.recv(self.trickle_bytes))
                    except socket.timeout:
                        pass
                    except OSError as exc:
                        self.errors.append(f"the slow connection failed: {exc}")
                        break
        except OSError as exc:
            self.errors.append(f"the stalled connection failed to open: {exc}")
        finally:
            self.stall_closed_after = time.monotonic() - started
            sock.close()

    def run(self, seconds: float, keep_raw: bool) -> None:
        if self.stall:
            self.run_stalled(seconds)
            return
        buffer = bytearray()
        deadline = time.monotonic() + seconds
        try:
            with socket.create_connection((self.host, self.port), timeout=10) as sock:
                if self.name:
                    sock.sendall(wire.encode({"cmd": wire.CMD_IDENTIFY, "name": self.name}))
                sock.settimeout(1.0)
                while time.monotonic() < deadline:
                    try:
                        chunk = sock.recv(4096)
                    except socket.timeout:
                        continue
                    if not chunk:
                        self.errors.append("the controller closed the connection")
                        break
                    if keep_raw:
                        self.raw.append(chunk)
                    buffer.extend(chunk)
                    for message in wire.iter_messages(buffer):
                        self.lines.append(message)
                        kind = message.get("type")
                        if kind == wire.MSG_HELLO:
                            self.hello = message
                        elif kind == wire.MSG_READING:
                            self.check_reading(message)
                        elif kind == wire.MSG_ERROR and message.get("at_capacity"):
                            self.refused = True
                        elif kind is None:
                            self.errors.append(f"a message with no type: {message}")
                    if self.refused:
                        break
                if buffer.strip():
                    self.errors.append(f"a partial line was left unframed: {bytes(buffer)!r}")
        except OSError as exc:
            self.errors.append(f"connection failed: {exc}")

    def elapsed_span(self) -> float | None:
        """Wall-clock seconds between the first and last reading's timestamps."""
        if self.first_reading is None or self.last_reading is None:
            return None
        first = dt.datetime.fromisoformat(self.first_reading["timestamp"])
        last = dt.datetime.fromisoformat(self.last_reading["timestamp"])
        return (last - first).total_seconds()

    def report(self, wall_seconds: float) -> bool:
        print(f"\n--- {self.label} " + "-" * (60 - len(self.label)))
        if self.stall:
            how = (f"read {self.trickled} B in {self.stall_closed_after:.0f} s "
                   f"({self.trickled / max(self.stall_closed_after, 1):.0f} B/s, against "
                   f"the ~330 B/s a reading costs)"
                   if self.trickle_bytes else
                   f"held open and never read for {self.stall_closed_after:.0f} s")
            print(f"{how}. Check the controller's log for its queue dropping and for "
                  f"the stall disconnect; check the other clients above for their rate.")
            for error in self.errors:
                print(f"PROBLEM: {error}")
            return not self.errors
        if self.refused:
            # The daemon refuses politely past the cap rather than accepting and
            # starving everyone; this end has to see that as the cap working.
            print(f"refused at capacity, as the protocol says it should "
                  f"(at_capacity, {len(self.lines)} message(s))")
            return True
        if self.hello:
            h = self.hello
            print(f"hello: serial={h.get('serial')} firmware={h.get('firmware')} "
                  f"state={h.get('state')} client_id={h.get('client_id')} "
                  f"max_clients={h.get('max_clients')} csv={h.get('csv')}")
        else:
            self.errors.append("no hello was sent; the app's LAN sweep needs one")

        kinds: dict[str, int] = {}
        for message in self.lines:
            kinds[message.get("type", "?")] = kinds.get(message.get("type", "?"), 0) + 1
        print(f"messages: {kinds}")

        span = self.elapsed_span()
        if self.readings:
            rate = self.readings / wall_seconds
            print(f"readings: {self.readings} in {wall_seconds:.0f} s "
                  f"({rate:.2f}/s), index gaps {self.index_gaps}, "
                  f"implausible PM2.5 {self.implausible}")
            if span is not None:
                print(f"timestamps span {span:.0f} s of wall clock "
                      f"(a stopped clock would say 0)")
                if self.readings > 5 and span < 1.0:
                    self.errors.append("the timestamps do not advance; the clock is stuck")
            last = self.last_reading
            assert last is not None
            print("last reading: PM1.0=%.2f PM2.5=%.2f PM4.0=%.2f PM10=%.2f ug/m3, "
                  "size=%.2f um, event=%r" % (
                      float(last["pm1_0"]), float(last["pm2_5"]), float(last["pm4_0"]),
                      float(last["pm10"]), float(last["typical_size"]), last.get("event", "")))
            if self.implausible:
                self.errors.append(
                    f"{self.implausible} readings had PM2.5 outside "
                    f"{PLAUSIBLE_PM25[0]}-{PLAUSIBLE_PM25[1]} ug/m3; do not call this working")
        else:
            self.errors.append("no readings arrived")

        for error in self.errors:
            print(f"PROBLEM: {error}")
        return not self.errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=wire.DEFAULT_PORT)
    parser.add_argument("--seconds", type=float, default=30.0)
    parser.add_argument("--clients", type=int, default=1, help="connect this many at once")
    parser.add_argument("--raw", action="store_true", help="print the bytes as they arrive")
    parser.add_argument("--name", default="tcp_probe", help="identify as this name")
    parser.add_argument(
        "--trickle", type=int, default=0, metavar="N",
        help="add N clients that read only --trickle-bytes a second, far below "
             "the rate a reading costs. They stay connected and fall behind, "
             "which is what makes the bounded queue drop.")
    parser.add_argument("--trickle-bytes", type=int, default=16)
    parser.add_argument(
        "--stall", type=int, default=0, metavar="N",
        help="add N clients that connect and then never read, with a tiny receive "
             "buffer so the controller's send blocks within seconds. The other "
             "clients must keep their 1 Hz throughout.")
    args = parser.parse_args(argv)

    print(f"connecting to {args.host}:{args.port} for {args.seconds:.0f} s "
          f"with {args.clients} client(s)"
          + (f", {args.stall} deliberately stalled" if args.stall else "")
          + (f", {args.trickle} deliberately slow" if args.trickle else ""))

    probes = [
        Probe(args.host, args.port, f"client {i + 1}",
              f"{args.name}-{i + 1}" if args.clients > 1 else args.name)
        for i in range(args.clients)
    ]
    stalled = [
        Probe(args.host, args.port, f"stalled client {i + 1}", f"stalled-{i + 1}")
        for i in range(args.stall)
    ]
    for probe in stalled:
        probe.stall = True
    trickling = [
        Probe(args.host, args.port, f"slow client {i + 1}", f"slow-{i + 1}")
        for i in range(args.trickle)
    ]
    for probe in trickling:
        probe.stall = True
        probe.trickle_bytes = args.trickle_bytes
    probes.extend(stalled)
    probes.extend(trickling)
    threads = [
        threading.Thread(target=p.run, args=(args.seconds, args.raw), daemon=True)
        for p in probes
    ]
    started = time.monotonic()
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    wall = time.monotonic() - started

    if args.raw:
        print("\n--- raw bytes, first client " + "-" * 34)
        sys.stdout.write(b"".join(probes[0].raw).decode("utf-8", "replace"))

    # Every client reports, then the verdict. `all(...)` over a generator would
    # short-circuit and hide the results of every client after the first failure,
    # which is precisely the run where they matter most.
    verdicts = [probe.report(wall) for probe in probes]
    ok = all(verdicts)
    print()
    if ok:
        print("PASS: every line framed, every reading complete, timestamps advancing")
        return 0
    print("FAIL: see the PROBLEM lines above")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
