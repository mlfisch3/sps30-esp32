#!/usr/bin/env python3
"""Measure how fast the controller's free-running clock loses or gains time.

## Why this exists

Relative-time recording works by appending equivalences - "my relative clock read
X when the world clock read Y" - and letting the client convert. How far apart
those equivalences may safely be is decided by one number: how fast the board's
clock drifts away from the world's. This measures that number instead of quoting
a datasheet tolerance.

## What it does, and the two traps in doing it

1. **The board must be free-running.** Flash a build with
   `NET_TIME_KEEP_SYNCING 0` (see `firmware/main/main.c`). SNTP then answers
   once, sets the clock and stops. A board that keeps resyncing corrects the
   very drift being measured, and the run would report roughly zero however bad
   the crystal was.

2. **This host is not a known-good clock either.** Under WSL2 the system clock
   is set from the Windows host and is not NTP-disciplined; measured, it sat
   ~720 ms from true time and moves on its own. So the probe queries real NTP
   servers throughout the run and records the offset, and the analysis corrects
   the host's readings before differencing them against the board's. Without
   that, this measures the sum of two drifts and attributes it to the board.

## Resolution, given that the protocol carries whole seconds

`reading.timestamp` is ISO 8601 to the second, so a single reading places the
board's clock only to +/- 1 s. The acquisition cadence is about 1009 ms, so the
board's sub-second phase walks about 9 ms per reading and completes a cycle
every ~112 s. Over a window of several cycles the fractional part is sampled
evenly across [0, 1), so the mean of `board_second - true_time` is the true
offset less one half second, and the half cancels in the slope. That recovers
tens of milliseconds from a one-second field, which is what makes an hour-scale
run able to see a twenty-parts-per-million crystal at all.

Everything is written to a CSV as it arrives, so a run that is interrupted is
still analysable, and the analysis (`--analyse`) is a separate pass over that
file rather than something only this process could do.

Usage:
    drift_probe.py --out drift.csv [--host 192.168.1.x] [--hours 6]
    drift_probe.py --analyse drift.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import socket
import struct
import sys
import time
from datetime import datetime

DEFAULT_PORT = 8730

# How often to ask a real NTP server where the host clock stands. Two minutes is
# far more often than the host clock can move meaningfully and cheap enough that
# a whole night of it is a few thousand small packets.
NTP_EVERY_S = 120.0
NTP_SERVERS = ("time.google.com", "time.cloudflare.com", "pool.ntp.org")

# Window over which the sub-second phase is averaged out. Five cycles of the
# ~112 s beat between the 1009 ms cadence and the 1000 ms second.
WINDOW_S = 600.0


def ntp_query(host: str, timeout: float = 5.0) -> tuple[float, float]:
    """`(offset, rtt)` in seconds: how far this host's clock is behind [host]."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        t1 = time.time()
        sock.sendto(b"\x1b" + 47 * b"\0", (host, 123))
        data, _ = sock.recvfrom(96)
        t4 = time.time()
    finally:
        sock.close()
    if len(data) < 48:
        raise ValueError("short NTP reply")

    def stamp(at: int) -> float:
        secs, frac = struct.unpack("!II", data[at:at + 8])
        return secs + frac / 2.0 ** 32 - 2208988800.0

    t2, t3 = stamp(32), stamp(40)
    return ((t2 - t1) + (t3 - t4)) / 2.0, t4 - t1


def find_controller(port: int = DEFAULT_PORT, timeout: float = 0.35) -> str | None:
    """Sweep this host's /24 for something answering on [port], as the app does."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.connect(("8.8.8.8", 80))
    local = probe.getsockname()[0]
    probe.close()
    prefix = local.rsplit(".", 1)[0]
    for last in range(1, 255):
        candidate = f"{prefix}.{last}"
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        try:
            sock.connect((candidate, port))
        except OSError:
            continue
        finally:
            sock.close()
        return candidate
    return None


ISO = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})([+-])(\d{2}):(\d{2})$")


def parse_iso(text: str) -> float | None:
    """Epoch seconds from `wire.iso_timestamp` output, or None."""
    m = ISO.match(text.strip())
    if not m:
        return None
    y, mo, d, h, mi, s, sign, oh, om = m.groups()
    from calendar import timegm

    local = timegm((int(y), int(mo), int(d), int(h), int(mi), int(s), 0, 0, 0))
    offset = int(oh) * 3600 + int(om) * 60
    return float(local + offset if sign == "-" else local - offset)


def collect(host: str, port: int, out_path: str, hours: float) -> int:
    deadline = time.time() + hours * 3600.0
    next_ntp = 0.0
    readings = 0
    ntp_samples = 0

    with open(out_path, "a", newline="") as handle:
        writer = csv.writer(handle)
        if handle.tell() == 0:
            writer.writerow(["kind", "host_epoch", "value", "extra"])
            handle.flush()

        sock = socket.create_connection((host, port), timeout=30)
        sock.settimeout(30)
        print(f"connected to {host}:{port}", flush=True)
        buffer = bytearray()

        while time.time() < deadline:
            now = time.time()
            if now >= next_ntp:
                server = NTP_SERVERS[ntp_samples % len(NTP_SERVERS)]
                try:
                    offset, rtt = ntp_query(server)
                    writer.writerow(["ntp", f"{time.time():.6f}", f"{offset:.6f}",
                                     f"{server}:{rtt:.4f}"])
                    ntp_samples += 1
                    handle.flush()
                except OSError as err:
                    writer.writerow(["ntp_fail", f"{time.time():.6f}", "", f"{server}:{err}"])
                next_ntp = now + NTP_EVERY_S

            try:
                chunk = sock.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                print("controller closed the connection", flush=True)
                break
            buffer += chunk
            while True:
                nl = buffer.find(b"\n")
                if nl < 0:
                    break
                line = bytes(buffer[:nl])
                del buffer[:nl + 1]
                arrived = time.time()
                if not line.strip():
                    continue
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                if msg.get("type") != "reading":
                    continue
                board = parse_iso(str(msg.get("timestamp", "")))
                if board is None:
                    continue
                writer.writerow(["reading", f"{arrived:.6f}", f"{board:.0f}",
                                 msg.get("index", "")])
                readings += 1
                if readings % 300 == 0:
                    handle.flush()
                    print(f"{readings} readings, {ntp_samples} NTP samples, "
                          f"{(deadline - arrived) / 3600:.2f} h to go", flush=True)

        handle.flush()
        sock.close()
    print(f"done: {readings} readings, {ntp_samples} NTP samples -> {out_path}", flush=True)
    return 0


def analyse(path: str) -> int:
    readings: list[tuple[float, float]] = []
    ntp: list[tuple[float, float]] = []
    with open(path, newline="") as handle:
        for row in csv.reader(handle):
            if not row or row[0] == "kind":
                continue
            if row[0] == "reading":
                readings.append((float(row[1]), float(row[2])))
            elif row[0] == "ntp":
                ntp.append((float(row[1]), float(row[2])))

    if len(readings) < 100 or len(ntp) < 2:
        print(f"not enough data yet: {len(readings)} readings, {len(ntp)} NTP samples")
        return 1

    ntp.sort()

    def host_offset(at: float) -> float:
        """Linearly interpolated host-clock error at [at], from the NTP samples."""
        if at <= ntp[0][0]:
            return ntp[0][1]
        if at >= ntp[-1][0]:
            return ntp[-1][1]
        lo = 0
        hi = len(ntp) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if ntp[mid][0] <= at:
                lo = mid
            else:
                hi = mid
        (t0, o0), (t1, o1) = ntp[lo], ntp[hi]
        return o0 if t1 == t0 else o0 + (o1 - o0) * (at - t0) / (t1 - t0)

    start = readings[0][0]
    # Board error against true time, per reading. The half second is the mean of
    # the truncation the one-second timestamp field applies; it is a constant, so
    # it shifts every point equally and does not touch the slope.
    points = [(host - start, board - (host + host_offset(host)) + 0.5)
              for host, board in readings]

    windows: list[tuple[float, float, int]] = []
    bucket: list[tuple[float, float]] = []
    edge = WINDOW_S
    for elapsed, error in points:
        if elapsed > edge:
            if len(bucket) >= 60:
                mid = sum(e for e, _ in bucket) / len(bucket)
                mean = sum(v for _, v in bucket) / len(bucket)
                windows.append((mid, mean, len(bucket)))
            bucket = []
            while elapsed > edge:
                edge += WINDOW_S
        bucket.append((elapsed, error))
    if len(bucket) >= 60:
        mid = sum(e for e, _ in bucket) / len(bucket)
        mean = sum(v for _, v in bucket) / len(bucket)
        windows.append((mid, mean, len(bucket)))

    if len(windows) < 2:
        span = points[-1][0] / 3600.0
        print(f"only {len(windows)} window(s) over {span:.2f} h - run longer")
        return 1

    n = len(windows)
    sx = sum(w[0] for w in windows)
    sy = sum(w[1] for w in windows)
    sxx = sum(w[0] * w[0] for w in windows)
    sxy = sum(w[0] * w[1] for w in windows)
    denom = n * sxx - sx * sx
    slope = (n * sxy - sx * sy) / denom
    intercept = (sy - slope * sx) / n
    residuals = [w[1] - (intercept + slope * w[0]) for w in windows]
    rms = (sum(r * r for r in residuals) / len(residuals)) ** 0.5

    # Standard error of the slope. Without it a two-point "fit" reports a drift
    # with a straight face, which is exactly what the first half hour of this
    # run did - the estimate swung from -23 ppm to +19 ppm as windows arrived,
    # not because anything was wrong but because five points over forty minutes
    # cannot see a twenty-parts-per-million crystal.
    slope_err = float("inf")
    if n > 2:
        residual_var = sum(r * r for r in residuals) / (n - 2)
        slope_err = (residual_var * n / denom) ** 0.5

    span_h = points[-1][0] / 3600.0
    print(f"file             : {path}")
    print(f"readings         : {len(readings)}")
    print(f"NTP samples      : {len(ntp)}")
    print(f"span             : {span_h:.2f} h")
    print(f"windows          : {n} of {WINDOW_S / 60:.0f} min")
    print(f"host clock error : {ntp[0][1] * 1000:+.0f} ms -> {ntp[-1][1] * 1000:+.0f} ms"
          "  (corrected for)")
    print()
    print(f"DRIFT            : {slope * 3600.0:+.3f} +/- {slope_err * 3600.0:.3f} s per hour")
    print(f"                 : {slope * 1e6:+.2f} +/- {slope_err * 1e6:.2f} ppm")
    print(f"                 : {slope * 86400.0:+.2f} s per day")
    print(f"window scatter   : {rms * 1000:.0f} ms rms about the fit")
    print(f"total over run   : {slope * points[-1][0]:+.3f} s")
    if abs(slope) < 3 * slope_err or abs(slope * points[-1][0]) < 3 * rms:
        print()
        print("NOT YET MEASURABLE: the slope is not separated from its own error.")
        print("Keep the run going. A twenty-ppm crystal needs hours, not minutes.")
    else:
        print(f"significance     : {abs(slope) / slope_err:.1f} sigma")
    for mid, mean, count in windows:
        print(f"  t+{mid / 3600:6.3f} h  error {mean * 1000:+9.1f} ms  ({count} readings)")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--analyse", metavar="CSV", help="analyse an existing log and exit")
    parser.add_argument("--out", default="drift.csv", help="where to append samples")
    parser.add_argument("--host", help="controller address (default: sweep the /24)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--hours", type=float, default=6.0)
    args = parser.parse_args(argv[1:])

    if args.analyse:
        return analyse(args.analyse)

    host = args.host or find_controller(args.port)
    if host is None:
        print("no controller found on this /24", file=sys.stderr)
        return 2
    print(f"started {datetime.now().isoformat(timespec='seconds')}, "
          f"logging to {args.out} for {args.hours} h", flush=True)
    return collect(host, args.port, args.out, args.hours)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
