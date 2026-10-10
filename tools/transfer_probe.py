#!/usr/bin/env python3
"""Drive a controller's export/import over the wire, and count what changed.

`firmware/test/replay_store.c` proves the merge on the host, over an image of a
board's partition. This proves it on the board, over the socket, against flash
that is being written to by a sensor at the same time - which is the one thing a
host test cannot reproduce.

It is a client and nothing more: it speaks the protocol the Android app speaks,
so what it exercises is the shipping firmware end to end.

    tools/transfer_probe.py --host 192.168.1.226 list
    tools/transfer_probe.py --host 192.168.1.226 export SPS30-20260802_201630.csv out.csv
    tools/transfer_probe.py --host 192.168.1.226 roundtrip SPS30-20260802_201630.csv

`roundtrip` is the interesting one: it exports a session, imports it straight
back, imports it a second time, and reports the controller's own count before
and after each. Nothing it does can remove a reading - `put_file` is the only
command it sends that writes anything, and that one merges.

It deliberately never sends `erase`.
"""

from __future__ import annotations

import argparse
import datetime
import json
import socket
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 2)[0])
import wire  # noqa: E402  the repository's protocol definition

# The controller reads a 2 KiB line, so a chunk of rows has to fit inside one
# with its JSON around it. Eight rows is what the app sends - about 900 bytes,
# so a whole frame fits beside a partial one - and matching it means this
# measures the transfer the app actually performs.
ROWS_PER_FRAME = 8


class Client:
    def __init__(self, host: str, port: int = wire.DEFAULT_PORT, timeout: float = 20.0):
        self.sock = socket.create_connection((host, port), timeout)
        self.sock.settimeout(timeout)
        self.buf = b""
        self.hello = self.wait("hello")

    def send(self, **command) -> None:
        self.sock.sendall((json.dumps(command) + "\n").encode())

    def messages(self):
        """Yield decoded messages, forever, blocking as needed."""
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
        """The next message of one of `types`. Live readings flow past."""
        for msg in self.messages():
            if msg.get("type") in types:
                return msg
            if not ignore_readings and msg.get("type") == "error":
                raise RuntimeError(msg.get("message", "refused"))

    def close(self) -> None:
        self.sock.close()


def list_files(client: Client) -> list[dict]:
    client.send(cmd=wire.CMD_LIST_FILES)
    return client.wait("file_list")["files"]


def storage(client: Client) -> dict:
    client.send(cmd="storage")
    return client.wait("storage")


def rows_of(client: Client, name: str) -> tuple[list[str], list[list[str]]]:
    """Every row of `name`, from the beginning - which is what an export asks for."""
    client.send(cmd=wire.CMD_GET_FILE, name=name, since_index=0)
    columns: list[str] = []
    rows: list[list[str]] = []
    while True:
        msg = client.wait("file_data", "error")
        if msg["type"] == "error":
            raise RuntimeError(msg["message"])
        columns = msg.get("columns") or columns
        rows.extend(msg.get("rows") or [])
        if msg.get("done"):
            return columns, rows


def normalise(name: str, columns: list[str], rows: list[list[str]]) -> list[str]:
    """Rows as CSV text in the session's own column order, per `wire.csv_columns`.

    `wire.CSV_COLUMNS` for a dated session, and the two-wider relative form for
    one the board recorded with no clock. Reducing a clockless session onto the
    narrow list is what wrote the backups in `SENSIRION-data-backup` without the
    board's clock in them: rows with an empty `timestamp` and no `(boot, ms)`,
    which nothing can ever place again.
    """
    order = [columns.index(c) if c in columns else -1 for c in wire.csv_columns(name)]
    out = []
    for row in rows:
        out.append(",".join(row[i] if 0 <= i < len(row) else "" for i in order))
    return out


def put_file(client: Client, name: str, rows: list[str], progress: bool = False) -> dict:
    """Send `rows` back and return the controller's account of what it did."""
    seq = 0
    started = time.monotonic()
    for start in range(0, len(rows), ROWS_PER_FRAME):
        batch = rows[start:start + ROWS_PER_FRAME]
        last = start + ROWS_PER_FRAME >= len(rows)
        client.send(cmd="put_file", name=name, rows="\n".join(batch), seq=seq, done=last)
        seq += 1
        if progress and seq % 25 == 0:
            sent = min(start + ROWS_PER_FRAME, len(rows))
            rate = sent / max(time.monotonic() - started, 1e-6)
            print(f"    sent {sent}/{len(rows)} rows ({rate:.0f}/s)", flush=True)
    if not rows:
        client.send(cmd="put_file", name=name, rows="", seq=0, done=True)
    return client.wait("import_result")


def held(client: Client, name: str) -> int:
    for entry in list_files(client):
        if entry["name"] == name:
            return entry["rows"]
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=wire.DEFAULT_PORT)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list")
    sub.add_parser("storage")
    p = sub.add_parser("export")
    p.add_argument("session")
    p.add_argument("out")
    p = sub.add_parser("roundtrip")
    p.add_argument("session")
    p = sub.add_parser("graft")
    p.add_argument("session")
    p.add_argument("--days", type=int, default=30,
                   help="shift the session this many days into the past")
    args = parser.parse_args()

    client = Client(args.host, args.port)
    print(f"connected to {args.host}: {client.hello['product']} "
          f"serial {client.hello['serial']} firmware {client.hello['firmware']}")

    if args.command == "list":
        for entry in list_files(client):
            print(f"  {entry['name']}  {entry['rows']:>7} rows  start {entry.get('start')}")
    elif args.command == "storage":
        print(json.dumps(storage(client), indent=2))
    elif args.command == "export":
        columns, rows = rows_of(client, args.session)
        with open(args.out, "w") as f:
            f.write(",".join(wire.csv_columns(args.session)) + "\n")
            for row in normalise(args.session, columns, rows):
                f.write(row + "\n")
        print(f"wrote {len(rows)} rows to {args.out}")
    elif args.command == "roundtrip":
        name = args.session
        columns, rows = rows_of(client, name)
        text = normalise(name, columns, rows)
        print(f"\nexported {len(text)} rows of {name}")

        for attempt in (1, 2):
            before = held(client, name)
            result = put_file(client, name, text, progress=True)
            after = held(client, name)
            print(f"\nimport #{attempt}")
            print(f"  controller held {before} rows before, {after} after")
            print(f"  added {result['added']}, already held {result['duplicate']}, "
                  f"rejected {result['rejected']}, state {result['state']}")
            print(f"  {result['message']}")
            # The session may be growing while this runs, so the honest check is
            # that the import added nothing - not that the total stood still.
            print("  ADDED NOTHING" if result["added"] == 0 else "  !! ADDED ROWS")
            time.sleep(1)

        columns2, rows2 = rows_of(client, name)
        text2 = normalise(name, columns2, rows2)
        kept = sum(1 for row in text if row in set(text2))
        print(f"\nof the {len(text)} exported rows, {kept} are still there "
              f"({'all of them' if kept == len(text) else 'SOME ARE MISSING'})")

    elif args.command == "graft":
        # A session the controller has never seen: a real one, moved wholesale
        # into the past so its name and every timestamp are new. This is the
        # "importing a session it has never seen creates it complete" case, and
        # it is the only one that actually writes to flash.
        name = args.session
        columns, rows = rows_of(client, name)
        text = normalise(name, columns, rows)
        shift = datetime.timedelta(days=args.days)

        moved = []
        for row in text:
            cells = row.split(",")
            when = datetime.datetime.fromisoformat(cells[1]) - shift
            cells[1] = when.isoformat()
            moved.append(",".join(cells))
        first = datetime.datetime.fromisoformat(moved[0].split(",")[1])
        grafted = f"SPS30-{first:%Y%m%d_%H%M%S}.csv"

        print(f"\ngrafting {len(moved)} rows of {name} as {grafted} "
              f"({args.days} days earlier)")
        print(f"  the controller holds it already: {held(client, grafted) > 0}")

        for attempt in (1, 2):
            before = held(client, grafted)
            result = put_file(client, grafted, moved, progress=True)
            after = held(client, grafted)
            print(f"\nimport #{attempt}")
            print(f"  controller held {before} rows before, {after} after")
            print(f"  added {result['added']}, already held {result['duplicate']}, "
                  f"rejected {result['rejected']}, state {result['state']}")
            print(f"  {result['message']}")
            time.sleep(1)

        columns2, rows2 = rows_of(client, grafted)
        back = normalise(grafted, columns2, rows2)
        same = set(moved) == set(back)
        print(f"\nread back {len(back)} rows of {grafted}; "
              f"{'identical to what was sent' if same else 'DIFFERENT FROM WHAT WAS SENT'}")
        missing = [r for r in moved if r not in set(back)]
        if missing:
            print(f"  {len(missing)} rows are missing, first: {missing[0]}")

    client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
