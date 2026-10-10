#!/usr/bin/env python3
"""Read the ESP32's console log without a TTY.

`idf.py monitor` drives a terminal directly and fails outright when stdout is a
pipe, which is every automated and agent-driven session here. This does the one
thing the monitor is needed for - reset the board and capture what it prints -
using pyserial alone, so a run can be scripted and its output diffed.

The DTR/RTS pulse is the same reset the flasher uses: on the usual USB-serial
bridges DTR drives EN and RTS drives GPIO0, so asserting EN low and releasing it
with GPIO0 high boots the application rather than the ROM loader.

pyserial is not installed for the system interpreter, but ESP-IDF's environment
has it, so run this with that one:

    ~/.espressif/python_env/idf5.3_py3.10_env/bin/python firmware/tools/monitor.py

Options:
    --port PORT       serial device (default /dev/ttyUSB0)
    --seconds N       how long to capture (default 30)
    --no-reset        attach to a running board instead of resetting it
    --until REGEX     stop early once a line matches
"""
from __future__ import annotations

import argparse
import re
import sys
import time

import serial  # pyserial


def reset_board(port: serial.Serial) -> None:
    """Pulse EN low with GPIO0 high, so the board boots the application."""
    port.setDTR(False)  # GPIO0 high: run the app, not the ROM bootloader
    port.setRTS(True)  # EN low: hold in reset
    time.sleep(0.1)
    port.setRTS(False)  # EN high: release
    port.reset_input_buffer()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--port", default="/dev/ttyUSB0")
    p.add_argument("--baud", type=int, default=115200)
    p.add_argument("--seconds", type=float, default=30.0)
    p.add_argument("--no-reset", action="store_true")
    p.add_argument("--until", default=None, help="stop once a line matches this regex")
    args = p.parse_args(argv)

    stop = re.compile(args.until) if args.until else None

    with serial.Serial(args.port, args.baud, timeout=0.2) as ser:
        if not args.no_reset:
            reset_board(ser)
        deadline = time.monotonic() + args.seconds
        pending = bytearray()
        while time.monotonic() < deadline:
            chunk = ser.read(4096)
            if not chunk:
                continue
            pending += chunk
            # Emit whole lines only, so a line is never split across a decode.
            while b"\n" in pending:
                raw, _, pending = pending.partition(b"\n")
                line = raw.decode("utf-8", "replace").rstrip("\r")
                print(line, flush=True)
                if stop is not None and stop.search(line):
                    return 0
        if pending:
            print(pending.decode("utf-8", "replace"), flush=True)
    # A timeout is only a failure when the caller asked to wait for something.
    return 1 if stop is not None else 0


if __name__ == "__main__":
    raise SystemExit(main())
