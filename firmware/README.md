# SPS30 ESP32 firmware

Reads a real SPS30 continuously over UART2 and serves it on the network:
detect the sensor, start it measuring, record the full measured-values frame on
a cadence, notice when it stops answering, get it back - and stream every
reading as JSONL to the Android app that already exists, over Wi-Fi **or over
Bluetooth Low Energy**, without the app changing at all.

## Status

**Runs on hardware.** Every milestone is verified against the physical sensor,
serial `E3164D2544338487`, firmware 2.1 - the same unit the Raspberry Pi reads -
and against the real access point.

| | |
|---|---|
| Host tests | **18/18 suites, 28,870 checks, 0 failures**, under AddressSanitizer and UndefinedBehaviorSanitizer |
| `idf.py build` for esp32 | **clean**, no warnings; `sps30_esp32.bin` is 0x115a80 bytes, 28% of the app partition free |
| Identify exchange | **verified on hardware.** Answers on the first attempt |
| Continuous acquisition | **verified on hardware.** See "What a run looks like" |
| Link fault diagnosis | **verified on hardware**, both branches, by injecting each fault |
| Wi-Fi station and reconnect | **verified on hardware.** Three injected link losses, each recovered in ~2.1 s |
| JSONL over TCP | **verified end to end** from a plain socket: 8 clients x 6 minutes at 1 Hz, no gaps |
| JSONL over BLE | **verified end to end** from the real app on the real tablet: found by service UUID, MTU 247, live readings and commands both ways. See "Bluetooth" |

## Layout

```
firmware/
  components/shdlc/    pure SHDLC codec - framing, stuffing, checksum. No UART.
  components/sps30/    UART2 transport over the codec, plus the command set.
    sps30_codec.c        pure measurement decoding. No UART, no ESP-IDF.
  components/acq/      the acquisition state machine.
    acq_queue.c          pure bounded drop-oldest queue - the transport seam.
  components/wire/     pure JSONL encoding of the protocol. No sockets, no clock.
  components/net/      Wi-Fi station, SNTP, and the protocol server over that queue.
    net_link.h           the four operations a transport owes the protocol.
  components/ble/      the Nordic UART Service, carrying the identical JSONL.
    ble_bytes.c          pure inbound buffer - what it discards when it fills.
  components/store/    the flash ring, and what survives it wrapping.
    store_codec.c        pure page format - a C port of store.py.
    store_alert.c        pure: what counts as alert-level, and the segment summary.
    store_preserve.c     pure over three callbacks: the area alert data is kept in.
    store_walk.c         pure over one callback: how `get_file` finds a session's
                         pages without reading the whole ring. See store_walk.h.
  components/power/    pure: what a state draws, and how long a battery would last.
    power_model.c        a C port of power.py. Nothing calls it - there is no battery.
  main/                identify the sensor, run acquisition, put it on the network.
  cmake/               reads the Wi-Fi credentials at build time. Never commits them.
  test/                host tests, cross-checked against sps30.py and wire.py.
  tools/monitor.py     read the console without a TTY; idf.py monitor cannot.
  tools/tcp_probe.py   talk to the running board the way the app does, and check it.
```

The split between codec and transport is deliberate and is inherited from
`sps30.py`, whose module docstring calls it out: the codec is unit-testable
without any hardware, which is the only reason there is anything to test today.
`components/shdlc` therefore declares **no dependencies at all** - not even on
ESP-IDF - so `test/` compiles that exact source with plain `gcc`.

`components/wire` is the same split applied to the protocol: it turns readings
and state into bytes and knows nothing about sockets, the clock or FreeRTOS, so
`test/` compiles it and diffs what it emits against the repository's `wire.py`.
Everything that cannot be pure - the driver, lwIP, the tasks - is in
`components/net`, which is therefore the part with no unit tests and the part
whose evidence is the hardware runs recorded below.

## Building

ESP-IDF v5.3.2. It must be activated **outside** any virtualenv - direnv
activates one in this worktree automatically, and ESP-IDF refuses to run from
inside it:

```sh
cd firmware
env -i HOME=$HOME PATH=/usr/bin:/bin bash -lc \
  'cd '"$PWD"' && . $HOME/esp/esp-idf/export.sh && idf.py set-target esp32 && idf.py build'
```

`PHASE4.md` explains why the `env -i` wrapper is needed and how the toolchain
was installed without `sudo`.

`set-target` rewrites `sdkconfig` from `sdkconfig.defaults`, so **delete
`sdkconfig` after changing the defaults** or the change is silently ignored -
that file is generated and is not in the repository.

The board needs **4 MB of flash**, which the one on the bench has (`esptool.py
flash_id` says so; it is an ESP32-D0WD-V3). Wi-Fi and NimBLE together do not fit
the default 1 MB app partition, so the project uses `partitions.csv`; that file
explains the sizes and what is deliberately left free.

## Flashing and watching it run

```sh
cd firmware
env -i HOME=$HOME PATH=/usr/bin:/bin bash -lc \
  'cd '"$PWD"' && . $HOME/esp/esp-idf/export.sh && idf.py -p /dev/ttyUSB0 flash'
```

Then read the console. **`idf.py monitor` does not work here**: it drives a
terminal directly and fails outright when stdout is a pipe, which is every
scripted or agent-driven session. `tools/monitor.py` does the one thing the
monitor is needed for - pulse DTR/RTS to reset the board, then capture what it
prints - with pyserial alone:

```sh
~/.espressif/python_env/idf5.3_py3.10_env/bin/python firmware/tools/monitor.py \
    --seconds 60 --until 'MILESTONE MET'
```

pyserial is not installed for the system interpreter; ESP-IDF's environment has
it, which is why that path is spelled out. `--no-reset` attaches to a board
already running instead of restarting it.

The console is on UART0 (GPIO1/GPIO3) and the sensor is on UART2
(GPIO16/GPIO17), so flashing and logging stay independent of the sensor - that
separation is the reason for the pin choice and is not negotiable.

### What a run looks like

Boot identifies the sensor, joins the network, then acquisition takes over:

```
I (368) sps30: UART2 up at 115200 8N1: TX=GPIO17 -> sensor RX, RX=GPIO16 <- sensor TX
I (388) main: serial number    : E3164D2544338487
I (398) main: firmware version : 2.1
I (408) main: MILESTONE MET: matches the Pi (E3164D2544338487, firmware 2.1).
I (583) main: free heap at boot: 250972 B (245 KiB) 8-bit-capable
I (823) wifi_sta: station started; connecting to the configured network
I (823) time: SNTP started; readings are held back until the clock is set
I (823) net: listening on port 8730 for up to 8 clients
I (833) main: free heap once everything is up: 191952 B (187 KiB)
I (1923) wifi_sta: ----------------------------------------
I (1923) wifi_sta: connected. IP address: 192.168.1.226
I (1923) wifi_sta: point the app at 192.168.1.226
I (1923) wifi_sta: ----------------------------------------
I (4853) time: clock set from the network: 2026-08-01 16:39:48 EDT
I (4853) time: readings can now carry a timestamp the app will accept
[     1] ~ PM1.0=  1.62 PM2.5=  1.91 ... size=0.63 um  (settling)
...
[    21]   PM1.0=  7.79 PM2.5=  9.00 PM4.0=  9.63 PM10=  9.75 ug/m3  ...
I (61503) main: after 60 readings: 1 not-ready, 0 read failures (0 silent,
                0 garbled), 0 implausible, 0 sensor losses; heap 150192 B free
I (61513) main: net: ip=192.168.1.226 clients=8/8 sent=123 dropped=0 stalled=0
                refused=0 drops=0 clock=set stack_min=1824 B
```

**The address is the one thing the captain needs off this log**, which is why it
is printed inside a rule and repeated as an instruction. The board also asks the
DHCP server for the hostname `sps30`, so it is findable in a router's client
list without reading a serial console at all.

The second summary line is the network's health in one place: clients connected,
reading lines written, readings dropped from a full client queue, clients closed
for not draining, connections refused at the cap, Wi-Fi drops, whether the clock
is set, and the smallest stack headroom any client task has had.

The `~` and `(settling)` mark the first 20 seconds after a start, when the
reading is still climbing as the fan spins up. They are **flagged, not
discarded** - the daemon's choice, kept: a consumer that wants clean data can
filter on the flag, and no layer silently throws away data it cannot know the
use of.

The firmware compares the identity against the Pi's known values itself, so the
verdict is in the log rather than left to whoever is reading it. It is the same
physical sensor, so a mismatch is a real finding.

### When it fails, it says which fault

`HARDWARE_SETUP.md`'s rule is built into the driver rather than left to the
reader: **silence means interface, wiring or power; garbled bytes mean baud rate
or logic levels.** The evidence that separates them is the byte count - nothing
arriving cannot be a framing problem, because a framing problem still produces
bytes - and it is logged alongside the verdict.

Both branches were verified on the bench by moving the receive pin, which needs
no rewiring and reaches each fault cleanly:

| `rx_gpio` pointed at | What arrives | Verdict |
|---|---|---|
| GPIO4, unconnected | nothing at all | `0 bytes received - silence: check the interface (SEL floating), the wiring, then power`, then the SEL / TX-RX / power order |
| GPIO3 (U0RXD), with the host writing a continuous stream containing no `0x7E` | bytes that never start a frame | `523 bytes with no start delimiter, giving up: garbled bytes: check the baud rate and the logic levels` |

The second is not a contrived stand-in: bytes that never form a frame is exactly
what a wrong baud rate produces *at the receiver*. Setting the baud rate wrong
would not have tested it, because one UART's rate drives the transmitter too -
the sensor would have stopped understanding the command and gone quiet, which is
the silent branch again. That is why there is no baud-rate knob in
`sps30_config_t`: it could only ever break a working rig.

In both cases the firmware kept retrying and recovered on its own the moment the
pin was restored.

**That test found a real bug, which is why it was worth running.** The Python's
`_read_frame`, and this port before the fix, bound the skip-to-START scan only
by the per-byte timeout. A line that never goes quiet and never carries a `0x7E`
therefore holds it forever: the first run consumed **85,787 bytes over 11.7
seconds** and only returned when the noise stopped, blocking the acquisition
task for the whole of it. Continuous line noise is precisely the condition the
garbled diagnosis exists to report, so wedging there is the worst possible
place to wedge. Both loops in `read_frame` are now bounded by a byte count and a
deadline, and the same injection gives up after 523 bytes in about 80 ms.

`sps30.py` has the same unbounded scan. It has not bitten on the Pi - a USB
serial line that noisy would have been noticed - but it is worth knowing about
before that code is trusted in a noisier place.

## Wiring

Full detail in `../HARDWARE_SETUP.md`. TX and RX cross exactly once.

| SPS30 pin | Signal | ESP32 |
|---|---|---|
| 1 | VDD | **5V** (`VIN`/`5V`), not 3V3 |
| 2 | RX | GPIO17 (ESP32 TX) |
| 3 | TX | GPIO16 (ESP32 RX) |
| 4 | SEL | **unconnected** - floating selects UART |
| 5 | GND | GND |

SEL is latched at power-up, so if it was ever grounded it needs a full power
cycle, not a reset. No level shifter is needed: both UART pins are 3.3 V
compatible and only VDD needs 5 V.

## Networking

### Wi-Fi credentials, and where they are not

The SSID and password are **never in this repository**, in any form, at any
point. They live in two files outside it, mode 600:

```
~/.wifi_ssid
~/.wifi_pass
```

`cmake/wifi_credentials.cmake` reads them at configure time and writes
`build/generated/wifi_credentials.h`. That location is the safety property
rather than a convenience: `build/` has been gitignored since the first commit,
so the generated file cannot be added to the index even by `git add -A`.
`.gitignore` names `wifi_credentials.h` as well, so it stays ignored wherever it
is generated. Only `main.c` includes it, and it passes the values straight into
`net_wifi_start` - nothing below `main.c` has a compile-time relationship with a
secret.

Override the locations with `SPS30_WIFI_SSID_FILE` and `SPS30_WIFI_PASS_FILE`.
With no SSID file the firmware still builds and runs, logs that networking is
off, and reads the sensor to the console as before.

**Nothing prints either value.** Not CMake, not the generated header's comments,
not a log line. That took one deliberate change beyond writing careful code: the
ESP-IDF Wi-Fi driver logs

```
I (983) wifi:connected with <SSID>, aid = 19, channel 11, ...
```

at INFO on every association, which puts the captain's network name into every
serial capture and every pasted bug report. `net_wifi_start` therefore calls
`esp_log_level_set("wifi", ESP_LOG_WARN)`, and this firmware's own Wi-Fi logging
uses the tag `wifi_sta` so that silencing the driver does not silence the line
carrying the IP address. Care taken above a component does not survive that
component being chatty; this was found by reading a real console capture, not by
reasoning.

Editing either file and rebuilding does the obvious thing: both are registered
as configure dependencies, so cmake re-runs and the header is regenerated.

### Losing the network is not an error

The station retries with exponential backoff from 1 s to a 30 s ceiling, and a
successful association resets it. An access point that is switched off costs a
few dozen attempts an hour rather than 36,000, and one that comes back is
rejoined within a second or two.

Acquisition does not care. It runs on its own task with no reference to the
network, and the console log stays a complete record of the run. **A router
rebooting must cost the captain a gap in the phone's chart, not a firmware that
has given up and needs a power cycle.**

Verified on hardware by injecting the same `WIFI_EVENT_STA_DISCONNECTED` a
vanishing access point produces, three times, under load:

```
W (50863) main: FAULT INJECTION: forcing the link down
W (50873) wifi_sta: link lost (reason 8); acquisition keeps running
I (50873) wifi_sta: retrying the configured network in 1000 ms
I (52953) wifi_sta: connected. IP address: 192.168.1.226
```

2.1 seconds from loss to a working address, every time, with readings recorded
throughout and no restart of the acquisition session.

### The clock, and why readings wait for it

`acq_reading_t` timestamps readings with monotonic uptime, correctly: there is
no RTC on this board. The protocol carries an ISO 8601 instant, and the Android
client parses it with `OffsetDateTime.parse` and **discards any reading whose
timestamp will not parse**. A controller with no clock therefore streams
perfectly good readings that the app silently throws away.

So SNTP is not a nicety here, it is load-bearing, and `net_time` exists for it.
Readings are held in their queues until the clock is set rather than being sent
unstamped - an unstamped reading is not a degraded one, it is a discarded one -
and because the mapping is from uptime rather than from the clock at send time,
the readings taken in the seconds before the sync are delivered afterwards with
the right instant. First sync on this LAN takes about 4 s from association;
one boot took 26 s, so a client connecting immediately may see `hello` and a
state before any readings.

The zone is `America/New_York`, which is what `wire.py` records in, as the POSIX
string `EST5EDT,M3.2.0/2,M11.1.0/2`.

### Announcing itself, so the app can listen instead of knock

The board advertises the protocol server over mDNS. What it advertises, in full:

```
SENSORQ._sensorq._tcp on sps30.local:8730 txtvers=1 proto=1
```

| | |
|---|---|
| Service type | `_sensorq._tcp` |
| Instance name | `SENSORQ` - a constant, see below |
| Host name | `sps30.local`, the same name the DHCP request asks for |
| Port | from the SRV record, i.e. whatever `net_server_start` is listening on |
| `txtvers` | `1` - the layout version of this TXT record, per DNS-SD convention |
| `proto` | `1` - the version of the JSONL protocol `wire.py` defines |

These names are defined in `wire.py`, mirrored into
`components/net/include/net_mdns_record.h` and into the app's `Wire.kt`, and the
firmware's copy is held against the Python by `test_net_mdns.c`. They are a
compatibility surface with no negotiation behind it: an app browses for a string
it was compiled with, and one wrong character is not an error message, it is "no
sensor on this network".

**What replaces what.** The app used to find the board by connecting to all 254
addresses of every local /24 it could see, 64 sockets at a time, keeping
whichever answered `hello`. That still works and is still there as the fallback -
a board flashed with firmware older than this is not findable any other way - but
it is on a clock: Android's Local Network Protections gate exactly that pattern
(opt-in at target 36, blocked by default at 37) and Apple's local-network
permission is built around declared Bonjour service types. Listening is also
simply better: one multicast query instead of several hundred connects, tens of
milliseconds instead of seconds, and what answers has *said* it is a SENSORQ
rather than merely having accepted a connection on a port.

**What is deliberately not in it, and why.** A DNS-SD record is multicast to
every device on the link, unencrypted, unprompted, and re-sent on every browse.
So the question for each field is not "might a client find this useful" but "does
a client need it to decide whether to connect at all". Three things that were
available and were left out:

- **The sensor's serial number.** `hello` carries it today and
  `docs/SECURITY_PLAN.md` takes it *out* of `hello` precisely because it is a
  permanent hardware identifier handed to any stranger who connects. Putting it
  in a record handed to any stranger who merely listens would be strictly worse
  than the leak being closed.
- **Anything derived from the MAC**, including the `SPS30-c24aea` form the
  Bluetooth name uses. It is a serial number by another route and it survives the
  board changing address, network and owner.
- **Live state** - the session name, the profile, the connected-client count. It
  does not help anyone choose a device, and a client count in particular would be
  a "someone is home" beacon rewritten as people come and go.

The instance name is a constant for the same reason. Two boards on one LAN do not
collide, because DNS-SD solves that itself: RFC 6762 conflict resolution renames
the loser during probing (ESP-IDF's responder implements it), so uniqueness costs
no identifier at all. Telling *which* board is yours is a question for the
connection - the security plan answers it there, with a per-connection keyed hint
- not for a broadcast.

**What it costs.** mDNS is not in ESP-IDF v5.3; it comes from the component
registry, pinned in `components/net/idf_component.yml`. Measured with
`idf.py size-components`: **33,656 B of flash code, 1,012 B of rodata and 2,346 B
of static DRAM**, plus a 4 KB responder task stack taken from the heap at
`mdns_init`. The heap figure has **not** been measured on the board; `main.c`
prints free heap once everything is up, and that line is where to check it.

One trap worth writing down, because the compiler is the only thing that mentions
it: `CONFIG_MDNS_MAX_INTERFACES=1` is accepted by Kconfig and is **not safe**.
`mdns_priv_pcb_check_for_duplicates` declares an array of that size, initialises
it with two values and indexes `[1]`, so a one-interface build reads past the end
of it on every call in the packet path. Two is the smallest value that code is
written for; see `sdkconfig.defaults`.

### The protocol is the daemon's, not a new one

JSONL over TCP on port 8730, framed by newlines. `wire.py` defines it, the app
speaks it, and `components/wire` implements it - same message types, same field
names, same `(csv, index)` de-duplication key, same eight-client cap, same
control-claim arbitration with its 120 s expiry.

`csv` deserves a note, because this board writes no CSV. In the protocol that
field is the **session key**: the app de-duplicates on `(csv, index)` and resets
its watermark when the name changes, so that per-session indices restarting at 1
do not look already-seen. Naming sessions `SPS30-YYYYmmdd_HHMMSS.csv` the way the
daemon names its files is what lets the app treat both controllers identically.
The name is computed once per session from the wall-clock instant of that
session's first reading, retroactively if the clock arrived later.

`event` is `settling` rather than one of the daemon's `startup`/`resume`/
`restart`/`clean`. Clients treat any non-blank value as "exclude this from the
plot", which is the whole of the contract, and `acq_reading_t` carries a flag
rather than a cause - so naming one of the four would be a guess dressed as a
fact.

What this controller cannot do, it says so:

| Command | Answer |
|---|---|
| `pause`, `resume`, `clean`, `restart` | honoured, through the acquisition task |
| `identify`, `claim`, `release` | honoured |
| `list_files` | the sessions the ring actually holds, newest first |
| `get_file` | the session's rows, streamed as `file_data` frames past whatever index the client already has |
| `backup` | error naming the route that does work: ask for the sessions one at a time |
| `set_profile` | honoured, continuous and duty cycle both, clamped rather than refused |
| `raw` | honoured, through the acquisition task; the frame goes out verbatim and the reply comes back verbatim, up to 128 bytes each way |
| `shutdown` | error: there is no host to power off - and `hello` says `can_power_off: false` so a client need never send it |

**`list_files` and `get_file` are answered over Bluetooth too**, because
`dispatch` is written once and neither transport filters it - which is what
makes collecting a board that never had a network possible. See "Bluetooth".

An empty `file_list` rather than an error is deliberate for a board that has
genuinely stored nothing yet: nothing is wrong, there is simply nothing there,
and an error would surface in the app as a fault. The
rest are errors because a silent no-op reads to a user as the sensor ignoring
them.

### Checking it from a machine that is not the phone

```sh
python3 firmware/tools/tcp_probe.py 192.168.1.226 --seconds 60
python3 firmware/tools/tcp_probe.py 192.168.1.226 --raw        # every byte
python3 firmware/tools/tcp_probe.py 192.168.1.226 --clients 8  # concurrency
python3 firmware/tools/tcp_probe.py 192.168.1.226 --stall 2    # clients that never read
python3 firmware/tools/tcp_probe.py 192.168.1.226 --trickle 1 --trickle-bytes 200
```

It is a plain socket and the repository's own `wire.py` framing, so what it
prints is evidence about the wire rather than about the tool. It validates as
well as prints: every line a complete object, every reading carrying all ten
fields and a parseable offset timestamp, indices advancing, timestamps tracking
real elapsed time, and PM2.5 within what indoor air actually is. **A well-formed
stream of implausible numbers is reported as implausible, not as success.**

Run it first when something looks wrong on the phone. If it is clean, the problem
is not the controller.

### What a client cannot do

The rule the whole design serves: **a client can never stall acquisition.** The
acquisition task's entire involvement in the network is a 56-byte copy into each
client's bounded queue under an uncontended mutex. No socket call, no
serialisation, no allocation, no wait a client can lengthen.

Three bad clients, and what happens to each, all observed on hardware:

| | |
|---|---|
| **Too many.** A ninth and tenth connection | refused with `at_capacity` and a readable message, connection closed. The other eight never notice |
| **Stopped.** Connects, never reads | disconnected after 30 s. Its queue is 32 readings, so at 1 Hz it is closed two seconds *before* it would have had to drop anything |
| **Slow.** Reads 165 B/s against the ~330 B/s a reading costs | stays connected, falls behind, and its queue drops the oldest - measured, 2 readings - until its window closes for 30 s and it is reaped like a stopped one |

In every case the healthy clients kept a full 1 Hz with zero index gaps.

Turning a client away needed one thing beyond sending the message: closing a
socket that still has unread inbound data makes TCP send an RST, and an RST
discards whatever is still in the send buffer. A client that identifies itself
the instant it connects - which the app does - hits that every time, and gets
`ECONNRESET` instead of the explanation. `refuse()` therefore shuts down the
write side, drains the inbound data with a bounded timeout, and only then closes.
Before that change, the ninth of nine simultaneous connections got a reset and no
message at all.

### Sustained run

Eight clients, six minutes, on the real access point:

| | |
|---|---|
| Readings delivered per client | 355-357 in 360 s (0.99/s) |
| Index gaps | **0**, on every client |
| Readings dropped, clients stalled | **0** |
| Free heap, 0 clients / 8 clients | 191,952 B / 150,232 B |
| Heap drift over the six minutes | **none.** 150,232 B at minute 1 and at minute 6 |
| Client task stack headroom | 1,824-1,836 B of 4,096 |

The flat heap is the result worth having: nothing in the serialise-and-send path
allocates, so a long run looks exactly like a short one.

Two things showed up that are worth writing down. Something on the LAN probes
port 8730 every ~5.5 s with a connect-read-close - the shape of the app's own
subnet sweep - and while all eight slots were full those probes were refused with
`at_capacity` and cost nothing. And PM2.5 sat at 7-10 ug/m3 throughout, at the
top of the 1-10 band for indoor air; that is what this sensor reads in this room,
and it matches the 7.79/9.00 in the acquisition run recorded above, so it is the
room rather than the port.

## Bluetooth

The same protocol, over the same JSONL, with no network at all. `BLUETOOTH_DESIGN.md`
specified this in July and the Raspberry Pi and the Android app have both spoken it
since then; the ESP32 was the missing third. It is no longer missing.

Nothing about the payload is new. What a phone receives over Bluetooth is byte for
byte what it receives over TCP, which is why **the app needed no change** - the same
APK that talks to the Pi over BLE talks to this board over BLE.

### Found, not addressed

The board advertises the **Nordic UART Service**,
`6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, with commands arriving on the `...0002`
characteristic and readings leaving on `...0003` as notifications. The app scans with
a filter on that service UUID, so there is **nothing to configure**: no address to
type, no pairing, no bonding, no identifier to read off the board. That is now
exactly the shape of the Wi-Fi path too: the app browses for `_sensorq._tcp`
rather than being told an IP, and falls back on sweeping the subnet for a `hello`
only for a board too old to announce itself. Neither radio has anything to
configure, and neither hands over an identifier to find the board by.

The advertised name (`SPS30-c24aea` on this board, from the radio's own address) only
labels the connection in the app's banner. It distinguishes two boards on a bench; it
is not how either is found.

### One protocol, two transports

The protocol was **not** reimplemented for Bluetooth. `net_server.c` used to own both
the protocol and the socket; the socket now lives behind `net_link_t` - four
operations, `recv`/`send`/`wait`/`close` - and `components/ble` is a second
implementation of those four. `hello`, the control lock, the client roster, the
command vocabulary and the eight-client cap are the same code either way.

The practical consequence, and the reason it was worth doing: **a command added to
`dispatch` is answered on both transports at once.** When on-device storage lands,
`list_files` and `get_file` work over Bluetooth with nothing added here. There is
deliberately no per-transport command filter - `BLUETOOTH_DESIGN.md` disables
`get_file` over BLE on the Pi, where the archive is 190 MB, and that reasoning does
not carry to a ring buffer holding 10 bytes a reading.

### Verified on hardware

Against the real tablet (a Galaxy Tab S7+ on Android 13) running the real app, not a
test harness:

| | |
|---|---|
| Found by service-UUID scan | **yes**, with no address configured and no pairing |
| ATT MTU negotiated | **247**, so a 250-byte reading is one notification rather than thirteen |
| Readings delivered | **120 in 120 s, exactly 1.00/s**, with `dropped=0 stalled=0 notify_fail=0 rx_lost=0` |
| Commands, phone to board | **verified**: the app's `identify` arrived, and the roster read `{"id":3,"name":"Galaxy Tab S7+"}` |
| Free heap, BLE up, one client | **102,128 B**, and not merely stable - byte-identical at five samples 30 s apart |
| Both transports at once | **yes**: a BLE client and a TCP client served side by side, ids 3 and 4 |
| Disconnect and reconnect | **verified**: switching the app to Wi-Fi and back freed the slot, resumed advertising and reconnected without a reset |

The app's own status banner is the end-to-end statement:
`Bluetooth (BLE) · connected SPS30-c24aea · measuring`, with the readout tiles
moving.

NimBLE costs about 45 KB of heap - free heap with one client fell from ~150 KB to
~102 KB - and about 200 KB of flash, which is what forced a custom partition table.
See `partitions.csv`.

### One client, not eight

> Superseded 2026-08-03: this section said "Two clients, not eight" and that
> `BLE_NUS_MAX_LINKS` is 2. It has been **1** since `41549f9`, and the header
> that defines it is the current explanation. The text below is corrected to
> match; the header wins if they ever disagree again.

`BLE_NUS_MAX_LINKS` is 1 against Wi-Fi's 8, and it is the radio that decides
that: every connection shares one antenna and interleaves connection events, so a
second would cost the first its throughput to serve somebody who could use Wi-Fi
instead. It **must equal `CONFIG_BT_NIMBLE_MAX_CONNECTIONS`** in
`sdkconfig.defaults` - they drifted apart once and the symptom was a
`BLE_HS_ENOMEM` in the log that nobody could account for. The consequence is
that the board stops advertising while a phone is connected, so a second phone
does not see it at all rather than seeing it and failing. A phone that arrives
when the *controller* is full is refused with the same `at_capacity` message the
TCP path sends. See `components/ble/include/ble_nus.h`.

### Bluetooth without a clock: closed 2026-08-03

This section used to say that live readings needed a clock and there was only
SNTP, so a board that had never joined Wi-Fi served `hello`, the control lock and
the file commands over Bluetooth but not the live stream - collection worked off
the network and viewing did not.

It is closed, and not by adding an RTC. **The board counts on its own clock and
says so.** A session decides its time base once, when it opens: if a clock is
there it records instants exactly as it always has, and if the pre-clock buffer
fills without one it commits to relative time, writes everything it was holding,
and goes on recording. Nothing is dropped either way, which is the change - the
buffer filling used to drop the oldest reading in it, every second, forever.

A relative reading carries an empty `timestamp` and a `rel_boot`/`rel_ms` pair,
and a relative session is named `SPS30-Rxxxxxxxx-xxxxxxxx.csv` for its run rather
than for a date it does not know. When a time source appears - `set_time` from a
phone on Bluetooth, or SNTP - the board **appends an equivalence** and rewrites
nothing; the app converts. See `STORAGE_FORMAT.md` for the format and `store.h`
for why appending is the hard rule.

### The two clock switches, and the drift they measure

Both are in `main/main.c` and both are 1 in every build that ships.

| Switch | 0 means |
|---|---|
| `NET_TIME_USE_SNTP` | never ask the network for the time. The board behaves as one that has never joined Wi-Fi, but is still reachable over TCP - so the whole relative-time path can be watched with instruments attached. It withholds a clock; it fakes nothing. |
| `NET_TIME_KEEP_SYNCING` | stop SNTP after the first answer, leaving the clock free-running on the crystal. This is how its drift is measured; a clock that keeps resyncing corrects away the thing being measured. |

**Measured free-run drift: -0.029 +/- 0.005 s per hour** (-8.0 +/- 1.5 ppm,
-0.69 s per day), over 3.6 hours and 12,852 readings, 5.5 sigma. Two traps that
make that number worth having rather than decorative:

- **This host is not a known-good clock either.** Under WSL2 the system clock is
  set from Windows and is not NTP-disciplined; measured, it sat about 720 ms from
  true time and moved on its own. The probe queries real NTP servers throughout
  the run and corrects for it, or the figure would be the sum of two drifts
  attributed to one of them.
- **The protocol carries whole seconds.** A single reading places the board's
  clock only to +/- 1 s. The ~1009 ms cadence walks the sub-second phase through
  a full cycle every ~112 s, so averaging over ten-minute windows recovers tens
  of milliseconds from a one-second field - which is what lets an hour-scale run
  see a twenty-parts-per-million crystal at all.

What the number is for: it says how far apart equivalences may safely be. At
-0.029 s/h, two an hour apart bound the conversion error at about thirty
milliseconds - far below anything the chart can resolve, and below the store's
own 500 ms marker tolerance - so there is no reason to record them more often
than a connection happens to arrive.

The host correction was load-bearing rather than precautionary: over the same
3.6 hours this host's own clock moved 84 ms against NTP, comparable to the
board's 104 ms. Differencing the two without correcting would have reported
roughly half the real figure and called it measured.

## Tests

```sh
make -C firmware/test                # build and run the standard pack
make -C firmware/test slow           # the long-running pack (see below)
make -C firmware/test vectors        # regenerate expectations from sps30.py
make -C firmware/test wire-vectors   # regenerate expectations from wire.py
make -C firmware/test store-vectors  # regenerate expectations from store.py
make -C firmware/test power-vectors  # regenerate expectations from power.py
```

No ESP-IDF, no toolchain activation and no hardware: the codec and the protocol
encoder are pure C. The Makefile's header lists every suite and says what each
one is for; it is the authority, not this list.

The standard pack runs in about a tenth of a second, which is the point of it.
The slow pack holds the one thing that cannot: `slow_wire_format` formats **every
float32 there is** - all 4,294,967,296 of them, at two decimals and at three -
with `wire_format_fixed` and with the `snprintf` it replaced, and compares the
bytes. That is the difference between "the rows look the same" and "the rows
*are* the same", which is the difference between a formatting change and a
change to the wire format.

`test_store_preserve` is worth knowing about for a second reason. The
preservation area only misbehaves once it has turned over, and turning it over on
the board means two days of continuous recording - so the flash under it is three
function pointers, and the suite fills it, wraps both of its rings and checks what
survived, in milliseconds. The claim it exists to prove is the one the whole
design rests on: **as the ring destroys ordinary history, alert segments survive
it, and their headers survive even the loss of their own samples.**

### The cross-check, which is the part that matters

`test/gen_vectors.py` imports the real `sps30.py` from the repository root and
calls its real `checksum`, `stuff`, `unstuff`, `build_frame` and
`parse_response`, writing what they produce into `vectors_generated.h`. The C
tests then assert byte-for-byte equality.

That is worth more than any test invented here, because `sps30.py` is not a
specification somebody wrote down - it is the code that has actually been
running this sensor. The generated header is committed so the tests run without
Python, and regenerating is an explicit `make vectors`.

`sps30.py` imports `pyserial` at module scope for its transport half, which is
not installed and is not needed for the codec. The generator injects a stub
whose `Serial` raises, so if the generator ever touched the transport it would
fail loudly rather than quietly test a stub.

Coverage:

- **22 MOSI frames** - every command the driver sends with the arguments it
  sends them with, plus payloads containing all four escaped bytes, a
  maximum-length 255-byte payload spanning every byte value, and the four cases
  where the *checksum itself* lands on an escaped byte. That last set is the
  one a careless port fails: stuffing the data but appending a raw checksum
  passes every ordinary test.
- **10 MISO frames** - including the milestone's own responses, decoded from
  bytes to `E3164D2544338487` and `2.1`. Each is round-tripped through
  `sps30.parse_response` before it is emitted, so a mistake in the generator
  cannot reach the C.
- **31 checksums** and **9 stuffing round trips** straight from the Python.
- **Malformed input** - truncated frames, missing START, missing STOP, bad
  checksum, a trailing ESC, an ESC followed by each of the 252 illegal bytes, a
  declared length overrunning the frame, a length leaving no room for the
  checksum, inners below the five-byte minimum, and every undersized
  destination buffer.
- **Every single-bit corruption** of a known-good frame - all 176 of them are
  rejected, none silently accepted.
- **11,110 stuffing combinations** over the bytes that matter: the four escaped
  bytes, the four escape codes they become (which must *not* be escaped when
  they appear alone), and two ordinary bytes.
- **7 measurement payloads**, decoded and compared against
  `sps30.decode_measurement()` **bit pattern for bit pattern** rather than to a
  tolerance. That is the point: the mistake being guarded against is a
  byte-order slip, and half of those still agree to six significant figures. The
  payloads cover clean air (all zero), plausible indoor air, the datasheet's
  full scale, awkward binaries (1/3, denormals, `FLT_MAX`), a ramp of all 40
  byte values, and one whose bytes contain all four that must be escaped, so
  stuffing and float decoding are exercised together.
- **65,536 big-endian bit patterns** through the single-float decoder,
  round-tripped exactly.
- **The field order**, asserted against `sps30.py`'s `Measurement` dataclass
  field-by-field. The order is a contract shared with the daemon's CSV, the wire
  protocol and the Kotlin port, so a reorder in either language now fails the
  build instead of silently transposing columns. The test also checks that the
  named fields and `values[]` really are the same storage.
- **The two payload lengths that mean different things** - empty is "no new
  value ready" and is *not* an error, 40 decodes, and every other length from 1
  to 64 is rejected. Collapsing those two cases would either tear down a healthy
  session several times a second or swallow a real protocol fault.
- **Plausibility** - real indoor air, all-zero and datasheet full scale must
  pass; negative values, `1e30`, NaN and infinity must not.

`test_wire` cross-checks the protocol the same way and against the same
standard, in **717 checks**. `gen_wire_vectors.py` imports the repository's
`wire.py`, calls its real `encode`, `measurement_row`, `iso_timestamp`,
`csv_filename` and `format_index`, and the C asserts byte-for-byte equality with
what came back. Two details make that comparison mean something:

- **The floats are float32 values**, round-tripped through `struct` before Python
  sees them, so the double the JSON encoder formats is exactly the one the C gets
  when it promotes `float` to `double`. Without that the two would be comparing
  different numbers and the test would be measuring `struct.pack`.
- **The timestamps carry the UTC offset `zoneinfo` resolved**, so the pure C can
  be compared against a real tzdata without shipping one, and the cases straddle
  both daylight-saving changeovers.

**That test earned its keep immediately.** The first implementation formatted
numbers with `%g`, which is the obvious choice and is wrong: Python's `repr`
emits the shortest digit string that round-trips and then chooses fixed notation
unless the decimal point falls outside a window, so it writes `10.0` and
`1000000.0` where `%g` writes `1e+01` and `1e+06`. Every one of those parses to
the same number and no client would have complained, which is exactly why it
needed a byte-for-byte test rather than a tolerance. It also covers escaping (a
client-supplied name cannot end a string early or inject the newline that frames
the stream), every buffer size from 1 up to just past a full line (a truncated
line must produce *nothing*, never a prefix, because a partial object still ends
in the delimiter), non-finite values becoming `null` rather than Python's invalid
`NaN`, and the command reader - escapes, `\uXXXX`, surrogate pairs, nested
objects whose keys must not be mistaken for top-level ones, and clipping a long
name at a UTF-8 character boundary.

`test_acq_queue` covers the queue separately, in **1093 checks**: FIFO order,
drop-oldest on overflow with the drop counted, 327 pushes through a 32-deep ring
staying ordered, interleaved push/pop accounting for every record, NULL safety
at every entry point, and the record staying 56 bytes - which is the unit the
queue-sizing arithmetic is written in, so a change to it fails the build.

## Deliberate fidelity to `sps30.py`

Where C forced a difference, it is mechanical: exceptions become `shdlc_err_t`
returns and every writer takes an explicit capacity. Two behaviours were kept
even though they are arguably loose, because changing a proven exchange on an
unverified board is the wrong trade:

- **Trailing bytes after the checksum are tolerated**, not rejected -
  `parse_response` in the Python does not check for them.
- **`read_frame` still mirrors the Python's framing**: skip to START, collect
  through the next STOP. Because START and STOP are the same byte, a doubled
  delimiter yields an empty frame and then a "too short" error rather than being
  re-synchronised. The Python has behaved this way in the field without trouble,
  so it was left alone.

Both are noted here rather than silently changed, so that whoever brings the
board up knows they are choices.

The one place this port **deliberately departs** from `sps30.py` is the bound on
that scan, described above under link diagnosis. Fidelity to a proven reference
is worth a great deal, but not to the point of copying a loop that can block the
acquisition task indefinitely once it has been demonstrated doing so.

## The acquisition state machine

`components/acq` is a port of `sps30_daemon.py`'s acquisition thread. The
states, transitions and tolerances are its, not new ones - the constants that
look arbitrary were paid for in lost data on the Pi, and the reasons are carried
across in the comments.

```
        +-----------+   sensor answers    +-------------+
        | checking  |-------------------->|  measuring  |
        |           |<--------------------|             |
        +-----------+  5 failed reads     +-------------+
                                            |         ^
                                     pause  v         | resume / clean done
                                     +----------+ +----------+
                                     |  paused  | | cleaning |
                                     +----------+ +----------+
```

Four behaviours are worth knowing because they are decisions, not accidents:

1. **One failed read is not a missing sensor.** Five consecutive failures are
   tolerated first (`READ_FAILURES_BEFORE_LOSS`). At 1 Hz that is a few seconds
   of silence - long enough to ride out a glitch or a brief brown-out, short
   enough that an unplugged sensor is noticed. Tearing the session down for each
   dropped reply is what made days of history on the Pi appear to begin at the
   last hiccup.
2. **A dropout does not start a new session.** Recovery continues the session it
   left, so a gap is a gap in one recording rather than the end of it.
3. **Start is always preceded by a stop.** A sensor already measuring rejects
   Start with `0x43` "command not allowed in current state", which happens after
   a reset mid-run and on the first cycle after a resume. Stopping first reaches
   a known idle state; the error is ignored when it was already idle.
4. **A silent sensor is probed again after a wake.** In Sleep-Mode the SPS30's
   UART receiver is off entirely, so a sensor left asleep answers nothing at
   all. Detection sends the `0xFF` pulse and the Wake-up command before writing
   it off - without that, low-power operation would lose the sensor permanently
   across a restart.

A frame that passes framing and checksum but decodes to an impossible value
(negative, `1e30`, NaN) is **discarded and counted, not treated as a link
fault**. The sensor demonstrably sent it, so it is a data-quality problem, and
tearing down a working session over one bad float would be the wrong cure.

## Sizing the client queues

`ACQ_CLIENT_QUEUE_MAX` is **32**, against the Pi daemon's 1000. The full
arithmetic is in `components/acq/include/acq_queue.h`; in short:

| | |
|---|---|
| Free DRAM at boot, measured from `heap_init` | 179 KiB |
| Committed once a network layer lands (Wi-Fi ~50K, lwIP ~30K, core stacks ~15K, 8 client tasks ~24K, headroom ~20K) | ~139 KiB |
| Left for client queues | ~40 KiB, so ~5 KiB each across 8 clients |
| At 56 bytes a reading, the memory ceiling | ~90 readings |
| **Chosen** | **32** |

The gap between 90 and 32 is deliberate and is not an arithmetic margin. At the
1 Hz the SPS30 publishes, 32 readings is 32 seconds of backlog; a client that
has not drained for half a minute is not briefly busy, it is gone, and the cure
is to notice and let it re-sync on reconnect rather than to hold its data
longer. A deeper queue buys no correctness, only a later admission.

The cost is **14 KiB** for all eight clients - under 8% of free DRAM - against
1.6 MB for eight Pi-sized queues, which does not fit in the part's entire 512 KB
of SRAM. Most of that saving is not the smaller count but queueing the 56-byte
binary record instead of ~200 bytes of serialised JSON; serialising at send time
is what makes a 32-deep queue cost 1.8 KiB.

### What it actually costs, now that it can be measured

The table above was estimated before there was a network layer to weigh. There
is one now, and the board reports:

| | |
|---|---|
| Free 8-bit heap at boot, before any networking | 250,972 B (245 KiB) |
| Free with Wi-Fi, SNTP and the server running | 191,952 B (187 KiB) |
| So: Wi-Fi, lwIP, SNTP and the server | **59,020 B (58 KiB)** |
| Free with eight clients streaming | 150,232 B (147 KiB) |
| So: per connected client, from the heap | **5,730 B** (a 4 KiB task stack, its TCB, and lwIP's socket) |
| Static DRAM for the eight client slots and their queues | **24,058 B**, from `idf.py size-components` |

**The number is unchanged and the argument holds.** The one figure that was wrong
is the 179 KiB premise, which understated the free memory - so the real headroom
is larger than the budget assumed, not smaller, and 32 was never in question on
memory grounds. It was chosen on the behavioural grounds above, which is why the
measurement does not move it.

The client task stack is 4,096 bytes because serialising a reading means ten
doubles through newlib's float formatting, which is the hungriest thing on the
path. Measured headroom at disconnect is 1,824-1,836 bytes, so the peak is about
2,270; the firmware logs it on every disconnect and in the periodic summary, so
the number stays checkable rather than trusted.

Readings are the *only* thing that belongs in this queue. The Pi tagged every
queued message lossy or lossless because it multiplexed archive chunks onto the
same queue, and dropping a chunk silently corrupts an archive. Keeping the
archive path on a separate, backpressured channel enforces the same invariant
structurally, so there is no flag left to forget to set.

## The power model, which nothing calls yet

`components/power` answers one question: given what the board is doing right
now - which acquisition profile, which radios up, how fast readings are reaching
flash - what is it drawing, and if a battery of known remaining capacity were
feeding it, how long would it keep going?

**There is no battery on this board.** Nothing in the firmware calls this, there
is no fuel gauge driver and there is no UI. It is here so that the calculation
exists, is checked, and is one port rather than three when a battery arrives.
`power.py` at the repository root is the specification and carries the whole
argument; this is the mirror, held to it by `power_vectors_generated.h` exactly
as `components/wire` is held to `wire.py`.

Three things about it are worth knowing before touching it or quoting a number
it produces:

- **The answer is short on purpose.** `POWER_RUNTIME_DERATING` is 0.70, and it
  is one named constant rather than a factor sprinkled through the arithmetic so
  it can be tuned once in one place. Reporting less time than the device will
  actually manage is the requirement, not a defect: promising eight hours and
  delivering six is a worse failure than promising six and delivering eight.
  `power.py`'s docstring for it lists what it covers - measurement uncertainty,
  battery ageing, capacity lost in the cold, voltage sag and conversion loss,
  and the draws nothing models - and says how to tune it against a real cell.
- **Nothing in it has been measured.** Two of the fourteen constants are
  datasheet figures this repository already records; the other twelve are
  estimates, several of them recollections. Every row says which it is, and both
  test packs assert that no row claims to have been measured, so the day one
  honestly can, that is a deliberate act in a commit that records who measured
  it and with what. `cpu_active` is the figure most worth a meter: it is the
  largest continuous term in every state the board can currently be in.
- **Milliwatts, not milliamps.** The SPS30 is on 5 V and the ESP32 on 3.3 V, so
  their currents do not add. Each constant is a current at its own rail and the
  model multiplies before it sums.

The whole model is pure C over a caller-owned struct, so
`firmware/test/test_power_model.c` drives every state and every way of not
knowing on the host - including the one that matters most, that the reported
time is always shorter than the physics, which is checked independently of the
vectors so that removing the derating from `power.py` and regenerating would not
hide it.

## What is deliberately not here

The seam `acq` was built around held: the network layer registers an
`acq_observer_t` that copies into an `acq_queue_t` and drains it from its own
task, and **nothing in `acq` moved to accommodate it**. The console logger in
`main.c` is the same shape and is still there, unchanged, as the second consumer.

Still absent, and for the reasons they were always absent - they need decisions
or hardware that is not here yet:

- **OTA.** One of the two sockets reserved out of `LWIP_MAX_SOCKETS` is for it.

> Superseded 2026-08-03: this list also carried **duty cycling**, on the grounds
> that it "needs the profile plumbing that arrives with a transport". The
> transport arrived and so did the plumbing. `set_profile` honours
> `duty_cycle`, `acq.c`'s `duty_cycle_once` runs the wake/warm-up/average/sleep
> cycle of `PRD.md` C-3 onward, and `AGENTS.md` has the pass for exercising it
> from a socket and from the app.
