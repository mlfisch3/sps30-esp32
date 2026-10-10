# Bluetooth (BLE) direct connection - design

> **Status: built on every host, and verified end to end on hardware.**
> The Pi and the Android app on 2026-07-24; the **ESP32 on 2026-08-02**.
> Unlike the other design documents in this worktree, this one is **still
> accurate**: the BLE path has not changed since it was built. Treat the design
> sections as current.
> The two exceptions are historical: "What needs on-device testing" was done and
> passed, and "Build order" is finished.
> Operational detail that came out of building it - the Pi's onboard Bluetooth
> needing a one-time `cmdline.txt` fix, the systemd unit, the socket-teardown
> gotcha - is in `CLAUDE.md`.
>
> **What changed on 2026-08-02.** The last line of "Host side" below - "ESP32-C6
> (future): the firmware implements the identical NUS service + JSONL directly"
> - is no longer future. `firmware/components/ble` does exactly that, on the
> ESP32-WROOM-32E that is on the bench. The app needed **no change**, which was
> the prediction this whole design was built on and is now a measured fact: the
> same APK that talks to the Pi over BLE talks to the board over BLE.
> `firmware/README.md` has the hardware evidence, and the two deliberate
> departures from the text below are recorded in "Where the ESP32 differs".

Goal: let the Android app connect to the sensor host **directly over Bluetooth Low
Energy**, so the dashboard works with **no Wi-Fi network available** at all. Bluetooth
is offered as an alternative transport alongside the existing Wi-Fi/TCP path; the user
picks (or the app auto-falls-back).

## Why BLE (not Classic Bluetooth / RFCOMM-SPP)
The SPS30 is controlled today by a **Raspberry Pi** but will later be controlled by an
**ESP32-C6-MINI-1-N4** (or equivalent pre-certified module). The ESP32-C6 has **Wi-Fi 6 + BLE 5.0 (LE)
+ 802.15.4 (Thread/Zigbee) - but NO Classic Bluetooth (BR/EDR)**. The Pi has both BLE and Classic. For a transport
that works on *both* the current Pi and the future ESP32-C6, it **must be BLE**. Classic
SPP/RFCOMM would be a dead end for the ESP32-C6, so it is rejected despite being a simpler
byte-stream on the Pi.

## The transport is "JSONL over BLE-UART" - the wire protocol does not change
BLE is not a byte stream; it is GATT (services + characteristics). We use the de-facto
standard **Nordic UART Service (NUS)** to carry a byte stream, and push the **exact same
newline-delimited JSONL** (`wire.py`) over it that the TCP server already uses:

- **Service** `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **RX characteristic** `6E400002-...` - **Write / Write-No-Response**, phone -> host
  (client commands: `{"cmd":"pause"}\n`, etc.)
- **TX characteristic** `6E400003-...` - **Notify**, host -> phone (`hello`/`reading`/
  `state`/... lines, chunked across notifications).

Because the payload is identical JSONL, **`wire.py`, the `Messages`/`Commands` Kotlin
port, and the whole message-handling layer are reused unchanged**. Only the *transport*
(how bytes move) differs. On the ESP32-C6 the firmware implements the same NUS + the same
JSONL, so the app needs no change to support it.

### Framing over BLE
BLE notifications are capped at `ATT_MTU - 3` bytes (~20 by default, up to ~244 after MTU
negotiation). A JSONL line may span several notifications, and one notification may hold
part of two lines. So both ends do the **same newline reassembly** already used for TCP:
accumulate bytes, split on `\n`, keep a partial trailing line. No new framing - identical
to `wire.iter_messages` / `DaemonClient`'s byte-level splitter.

### Throughput is fine for the live use case
Readings are ~1/s and ~200 bytes each; after MTU negotiation a reading is 1-2
notifications. BLE handles that comfortably. **File transfer / backfill (`get_file`) is
large and slow over BLE and is disabled on the BLE transport** (live readings + control
commands only) - the "no Wi-Fi field operation" scenario only needs the live stream and
pause/resume/clean/restart.

## Android side (`apk/`)
Refactor the client into a **transport abstraction** so the UI/session code is transport-
agnostic:

```
interface Transport {
    val state: StateFlow<ConnectionState>
    val messages: SharedFlow<ServerMessage>   // same lossy drop-oldest semantics
    fun send(line: String)                     // an already-framed JSONL command
    suspend fun run()                          // connect + stream until cancelled
}
```
- `TcpTransport` = the existing `DaemonClient` behind this interface (Wi-Fi path).
- `BleTransport` = new. Scans for the NUS service UUID (BLE advertising - no IP/subnet
  needed, which is the whole point when there is no Wi-Fi), connects GATT, negotiates a
  larger MTU, enables TX notifications, reassembles JSONL -> `ServerMessage` on the same
  lossy `SharedFlow`, and writes command lines to RX (splitting a long command across
  writes if needed).
- `MonitorSession` holds a `ConnectionMode { WIFI, BLUETOOTH }` (persisted in `AppConfig`)
  and runs the chosen transport; the message-folding/history/chart code is unchanged
  because both transports emit the same `ServerMessage`s.
- **Permissions (Android 12+):** `BLUETOOTH_SCAN` (+ `neverForLocation` flag so no
  location permission is needed) and `BLUETOOTH_CONNECT`, requested at runtime when the
  user enables Bluetooth mode. Older APIs use the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` +
  location. Also handle "Bluetooth is off" (prompt to enable).
- **UI:** a connection-mode selector (Wi-Fi / Bluetooth) in the options menu; the status
  banner shows which transport is live. In the foreground service, BLE monitoring does not
  need the Wi-Fi lock (it needs the connection kept alive instead).

## Host side - Pi now, ESP32-C6 later
- **Pi:** a new **BLE GATT peripheral** (`sps30_ble.py`) that advertises NUS and bridges
  the same JSONL to/from the running daemon. On Linux this is **BlueZ over D-Bus** (register
  a GATT application: the NUS service + RX write handler + TX notify; plus an LE
  advertisement). It connects to the daemon's TCP server on `127.0.0.1:8730` as a local
  client and simply **pipes** that JSONL onto the BLE TX characteristic, and pipes RX
  writes back to the daemon as commands - so the daemon itself is unchanged and both
  transports serve identical data. (Alternative: fold the GATT server into the daemon
  process; the bridge keeps concerns separate and the daemon untouched.)
  - Requires BlueZ with experimental GATT/advertising APIs enabled; runs as a small
    service next to `sps30-daemon`.
- **ESP32 (built 2026-08-02):** the firmware implements the identical NUS service +
  JSONL directly from its own SPS30 driver, in `firmware/components/ble` over NimBLE.
  No app change. This is why the payload was kept identical.
  It went further than "the same bytes": the protocol layer in
  `firmware/components/net/net_server.c` was refactored behind a four-operation
  `net_link_t`, so BLE reuses `hello`, the control lock and the whole command
  vocabulary rather than reimplementing them. A command added there is answered on
  both transports at once.

## Where the ESP32 differs from the text above, and why

Two deliberate departures, both recorded here rather than left to be discovered.

**1. `get_file` is not disabled on the BLE transport.** The paragraph above
disables it because the Pi's archive is 190 MB of CSV and BLE is the wrong pipe
for it. The ESP32 is not that machine: `STORAGE_FORMAT.md` makes it a **ring
buffer** at 10 bytes a reading, so a whole day of 1 Hz data is 0.9 MB - about a
minute at the 244-byte notifications this link negotiates, against roughly a day
for the Pi's archive. More to the point, the reason the ESP32 has Bluetooth at
all is the deployable case in `STORAGE_FORMAT.md`: a board left somewhere with no
network, collected later. Refusing the file commands on the only transport that
case can use would defeat the feature.

So the ESP32 filters **nothing**: every command reaches the same `dispatch`
whichever transport carried it. That prediction has since been collected on:
on-device storage landed on `esp32-controller` while this branch was in review,
and `list_files` and `get_file` were answered over Bluetooth the moment the two
were on one branch, with **no change to the transport at all**. That is what
"the transport drops in" was meant to buy, and it is why no command allowlist
was introduced.

**2. Live readings still need a clock, and BLE cannot supply one.** Readings
carry an ISO 8601 timestamp that the app parses and will not do without, and the
board's only clock is SNTP. A board that has never had Wi-Fi therefore streams
`hello`, `state`, `control`, `clients` and the file commands over BLE, but holds
readings in the queue rather than sending them unstamped. Collection works; the
live stream does not. Closing that needs a time source the protocol does not
currently have - a `set_time` command from the phone, or an RTC - and adding one
is a change to `wire.py` and therefore a decision above this firmware.

## Security note
BLE here is unauthenticated/unencrypted at the app layer (like the LAN daemon). BLE
pairing/bonding can add link-layer encryption; a PSK handshake in the JSONL could add app-
layer auth later. For the intended trusted-personal-device use it is acceptable; documented
like the Wi-Fi daemon's trusted-LAN posture.

## What needs on-device testing (cannot be verified from the dev box)
BLE scan/connect/notify is only verifiable on real hardware (a BLE-capable phone + the Pi's
BLE radio). The **pure parts are unit-tested**: the JSONL reassembly, the transport
abstraction, and command framing. End-to-end BLE (scan -> connect -> live stream -> control)
is an explicit on-device verification step, like the earlier phases.

## Build order
1. Design (this doc) + the transport abstraction (refactor `DaemonClient` behind `Transport`).
2. Android `BleTransport` (scan/connect/notify/reassemble/write) + permissions + a pure
   JSONL-reassembler with tests.
3. `ConnectionMode` in config + the UI selector + status wiring.
4. Pi `sps30_ble.py` BLE-UART bridge (BlueZ).
5. On-device end-to-end verification (deferred to hardware).
