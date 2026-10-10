"""The power model: what the controller draws, and how long a battery lasts.

This module is the single source of truth for one calculation: given what the
device is doing right now - which acquisition profile, which radios up, how fast
readings are reaching flash - how much power is it using, and if a battery of
known remaining capacity is feeding it, how long will it keep going?

It lives here, in Python, for the same reason ``wire.py`` and ``store.py`` do.
The firmware is where every input actually lives (the profile, the link states,
the write rate, and one day a fuel gauge), so the *answer* has to be computable
on the ESP32 - but a number the app may one day show and the controller computes
is exactly the kind of thing that drifts when it is written twice from memory.
``firmware/components/power`` is a port of this file, and
``firmware/test/gen_power_vectors.py`` runs the real functions below and writes
what they return into a header the C asserts against. Change this file, run
``make -C firmware/test power-vectors``, and the mirror is held to it.

There is deliberately no Kotlin mirror, because only the controller knows its
own link state, its own write rate and whether its sensor can sleep. The app is
told the numbers over the wire instead: ``wire.MSG_POWER_ESTIMATE`` is the
delivery, :func:`estimate_message` below composes it, and the firmware's encoder
is held to that composition byte for byte by the generated vectors. If a Kotlin
mirror is ever really needed, mirror it the way ``Wire.kt`` mirrors ``wire.py``
and add the vectors.

Nothing here touches hardware and nothing here is a driver. There is no battery
on this device yet. This is the arithmetic, built and tested so that the day a
capacity figure exists it has somewhere to go.

## Two honesty rules, and they are the point of the module

**Every physical constant records where it came from.** ``Source.DATASHEET``,
``Source.MEASURED`` and ``Source.ESTIMATE`` are not decoration: whoever tunes
this later needs to know which numbers are worth arguing with. As of writing
**no row is ``MEASURED``**: two are datasheet figures this repository already
records, and the other twelve are estimates, several of them recollections of
datasheets no copy of which is in this repository. Each one says so in its own
``provenance``, and ``sources_summary()`` will keep saying it until somebody
puts a meter on the board. An estimate presented as a measurement would send
that person to re-measure the wrong thing.

The one genuinely measured number in the file is not a current at all:
``BYTES_PER_READING``, which ``STORAGE_FORMAT.md`` settled over 120,000 real
readings, and which is what turns an acquisition profile into a write rate.

**The answer is short on purpose.** ``RUNTIME_DERATING`` shortens every reported
runtime, because promising eight hours and delivering six is a worse failure than
promising six and delivering eight. See its own docstring for what it covers and
why it is the value it is.

The two rules compose: a constant that has to be guessed is guessed *high*, so
that ignorance about a draw also shortens the answer rather than lengthening it.
That is not double counting with the derating - the derating covers the battery
and the conversion between it and these rails, and the guesses cover the rails
themselves - but it does mean the model is pessimistic twice over, which is the
intended direction and worth knowing before anyone calls it inaccurate.

## Why it is in milliwatts and not milliamps

The SPS30 wants 5 V and the ESP32 wants 3.3 V, so their currents cannot be
added: 100 mA of fan is not 100 mA of radio. Every constant below is a current
at *its own rail*, the model multiplies by that rail's voltage, and the sums are
in milliwatts. The battery is then energy - a capacity in mAh at a nominal cell
voltage - and the runtime is energy over power. Doing it in milliamps happens to
work only when everything shares one rail, which here it does not.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Mapping

import store
import wire

# --- where a number came from -----------------------------------------------


class Source(str, Enum):
    """How much a constant below is worth trusting.

    ``DATASHEET`` - traceable to a manufacturer document, or to a figure this
    repository already records with its document named.
    ``MEASURED`` - somebody measured it on real hardware and wrote down where.
    ``ESTIMATE`` - a guess, however educated. Recollections of a datasheet that
    is not in this repository count as estimates, deliberately: an unverified
    recollection is exactly as wrong as a guess when it is wrong.
    """

    DATASHEET = "datasheet"
    MEASURED = "measured"
    ESTIMATE = "estimate"


@dataclass(frozen=True)
class Draw:
    """One physical constant, with the receipts.

    ``current_ma`` is at ``rail_v``, not at the battery. See the module
    docstring for why that distinction is load-bearing.
    """

    name: str
    current_ma: float
    rail_v: float
    source: Source
    provenance: str

    @property
    def power_mw(self) -> float:
        return self.current_ma * self.rail_v


# --- the rails --------------------------------------------------------------

#: The SPS30's supply. Datasheet range is 4.5-5.5 V; the wiring in
#: `HARDWARE_SETUP.md` takes it from the board's 5 V pin.
SPS30_RAIL_V = 5.0

#: The ESP32-WROOM-32E's supply, and everything else on the controller board.
ESP32_RAIL_V = 3.3


# --- the physical constants -------------------------------------------------
# One table, one place, each row saying where it came from. Everything else in
# this module is arithmetic over these.
#
# A note that applies to the whole ESP32 half: this project has never put a
# meter on the board. Those rows are recollections of the Espressif datasheet,
# taken at the top of whatever range is recalled rather than the middle, and
# they are labelled ESTIMATE for that reason. The SPS30 half is better off -
# `HARDWARE_SETUP.md` and `wire.py` both record Sensirion's own figures with the
# document named - and the storage half is better still, because
# `STORAGE_FORMAT.md` measured the byte cost of a reading on 120,000 real ones.

DRAWS: dict[str, Draw] = {
    # --- the sensor ---------------------------------------------------------
    "sensor_measuring": Draw(
        name="SPS30 measuring, fan at speed",
        current_ma=55.0,
        rail_v=SPS30_RAIL_V,
        source=Source.DATASHEET,
        provenance=(
            "Sensirion SPS30 datasheet typical, restated in HARDWARE_SETUP.md "
            "('55 mA typical, 80 mA peak at 5 V') and in wire.py's profile notes."
        ),
    ),
    "sensor_fan_starting": Draw(
        name="SPS30 during fan spin-up (the warm-up window)",
        current_ma=80.0,
        rail_v=SPS30_RAIL_V,
        source=Source.DATASHEET,
        provenance=(
            "The 80 mA peak from the same datasheet figure. Charged for the "
            "whole warm-up rather than for the instant of spin-up, because the "
            "warm-up is exactly when the fan is accelerating and the model is "
            "meant to err short."
        ),
    ),
    "sensor_idle": Draw(
        name="SPS30 powered, not measuring",
        current_ma=3.6,
        rail_v=SPS30_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "Recalled as the datasheet's idle-mode typical; no copy of the "
            "datasheet is in this repository, so it is an estimate. This is "
            "what a duty-cycled sensor costs between cycles when it cannot be "
            "put to sleep, which is the default this model assumes."
        ),
    ),
    "sensor_sleep": Draw(
        name="SPS30 asleep between duty cycles",
        current_ma=0.5,
        rail_v=SPS30_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "The firmware records the datasheet figure as 'under 50 uA' - see "
            "`park_sensor` in components/acq/acq.c - and this row is ten times "
            "that, deliberately. Not because the figure is doubted: because "
            "`park_sensor` also documents that the sleep command can fail and "
            "the cycle then 'burns idle power instead of sleep power', which is "
            "3.6 mA. A number between the two hedges a failure the firmware "
            "itself says can happen. Needs SPS30 firmware >= 2.0 "
            "(wire.MIN_FIRMWARE_FOR_SLEEP) to be reachable at all."
        ),
    ),
    "sensor_cleaning": Draw(
        name="SPS30 fan self-clean",
        current_ma=80.0,
        rail_v=SPS30_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "The fan is driven to full speed, so the datasheet's 80 mA peak is "
            "used for the whole clean. Runs for CLEAN_SECONDS once every "
            "wire-side week (acq_clean.h's ACQ_CLEAN_AFTER_SECONDS), so it is "
            "worth about a thousandth of the total and is here for completeness."
        ),
    ),
    # --- the processor ------------------------------------------------------
    "cpu_active": Draw(
        name="ESP32 CPU running, radios accounted separately",
        current_ma=68.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "Recalled as the top of the Espressif datasheet's modem-sleep "
            "CPU-active range (about 30-68 mA depending on clock). The top, not "
            "the middle, per the module's rule about guessing. THIS IS THE "
            "SINGLE MOST IMPORTANT ROW TO MEASURE: it is the largest continuous "
            "term in every state the firmware can currently be in, and a 2x "
            "error in it is a 2x error in the answer."
        ),
    ),
    "cpu_light_sleep": Draw(
        name="ESP32 in light sleep",
        current_ma=1.5,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "Recalled as around 0.8 mA; taken at roughly twice that. "
            "**Unreachable today**: there is no `esp_pm` configuration and no "
            "call to `esp_light_sleep_start` anywhere in the firmware, so the "
            "ESP32 never sleeps even while the sensor is duty cycled. Modelled "
            "so the saving can be argued about before anyone builds it - and "
            "the argument it supports is a real one, since with the sensor "
            "asleep and the radios down the processor is most of what is left."
        ),
    ),
    "board_overhead": Draw(
        name="Regulator quiescent, power LED, USB-serial bridge",
        current_ma=15.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "A development board's own overhead: a permanently lit power LED "
            "and a USB-serial bridge that draws whether or not anything is "
            "plugged into it. Guessed. Included by default because the board "
            "this project has is a development board; a custom one would set "
            "board_overhead=False and stop paying it."
        ),
    ),
    # --- connectivity -------------------------------------------------------
    # Adders on top of the CPU row, at the ESP32's rail. Wi-Fi and BLE can both
    # be up at once and the model sums them, because that is what this firmware
    # does: `net_link.h` serves both transports simultaneously and a transport
    # change is make-before-break, so there are moments when both are live.
    "wifi_idle": Draw(
        name="Wi-Fi associated, listening, nothing to send",
        current_ma=90.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "Recalled as the datasheet's RX/listening figure (95-100 mA) less a "
            "little, since the modem is not receiving continuously. No Wi-Fi "
            "power save is assumed because the firmware does not enable any; if "
            "it ever does, this row splits in two rather than shrinking."
        ),
    ),
    "wifi_streaming": Draw(
        name="Wi-Fi associated and serving clients",
        current_ma=120.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "The idle row plus an average allowance for transmission. Peak TX "
            "is recalled at 200-300 mA (HARDWARE_SETUP.md says the same) but "
            "that is a duty of a few percent at the JSONL rates this protocol "
            "uses, so an average is the honest term here. A board serving eight "
            "clients at 1 Hz will exceed this; measuring it is the second "
            "priority after cpu_active."
        ),
    ),
    "ble_advertising": Draw(
        name="BLE advertising, nobody connected",
        current_ma=15.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "An average over the advertising interval, guessed high. The radio "
            "is off between advertisements, so the true average depends on an "
            "interval this model is not told."
        ),
    ),
    "ble_connected": Draw(
        name="BLE connected and carrying the protocol",
        current_ma=30.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "Guessed as roughly a quarter of the Wi-Fi streaming adder, which "
            "is the ratio BLE is usually worth. BLE_NUS_MAX_LINKS is 1, so this "
            "does not scale with clients the way the Wi-Fi row would."
        ),
    ),
    # --- storage ------------------------------------------------------------
    # Charged by the second of flash activity rather than as a standing current,
    # because at 1 Hz the flash is busy for a few milliseconds a minute.
    "flash_page_write": Draw(
        name="SPI flash while programming a page",
        current_ma=20.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "A typical serial-flash program current, guessed. Paired with "
            "FLASH_PAGE_WRITE_SECONDS below."
        ),
    ),
    "flash_sector_erase": Draw(
        name="SPI flash while erasing a sector",
        current_ma=25.0,
        rail_v=ESP32_RAIL_V,
        source=Source.ESTIMATE,
        provenance=(
            "As above, and erases are the slow half by two orders of magnitude, "
            "which is why they are counted separately. Paired with "
            "FLASH_SECTOR_ERASE_SECONDS below."
        ),
    ),
}


#: Why the model computes the duty-cycle average from per-state currents rather
#: than adopting Sensirion's headline figure.
#:
#: Their low-power app note quotes ~3.5 mA average for 60 s of measurement every
#: 15 minutes. Their own measuring current of 55 mA makes that impossible:
#: 60 s x 55 mA is 3300 mA-s, and 900 s x 3.5 mA is 3150 mA-s, so the
#: measurement window alone exceeds the whole quoted average. The two figures
#: come from different documents and probably different assumptions about what
#: counts as the measurement window. What survives the disagreement is that the
#: sleep current must be near zero for the headline to be anywhere near right.
#: So this model builds the average itself, and does not quote theirs.
SENSIRION_AVERAGE_NOTE = (
    "wire.py quotes ~3.5 mA for 60 s per 15 min; that is inconsistent with "
    "55 mA measuring, so the model computes the average instead of adopting it."
)

#: How long an SPS30 fan self-clean runs. ESTIMATE: recalled as about ten
#: seconds, taken at twenty so a wrong memory costs nothing but conservatism.
CLEAN_SECONDS = 20.0

#: How often the firmware cleans, from `acq_clean.h`'s ACQ_CLEAN_AFTER_SECONDS.
CLEAN_INTERVAL_SECONDS = 604800.0

#: Seconds of flash activity per 512-byte page programmed. ESTIMATE, high.
FLASH_PAGE_WRITE_SECONDS = 0.005

#: Seconds of flash activity per 4096-byte sector erased. ESTIMATE, high: serial
#: flash sector erases are tens of milliseconds and this takes 100 ms.
FLASH_SECTOR_ERASE_SECONDS = 0.1

#: Bytes a stored reading costs. MEASURED - `STORAGE_FORMAT.md` settled on delta
#: + zigzag varint at exactly ten bytes per reading, measured over 120,000 real
#: readings from this sensor. The one genuinely measured number in this file.
BYTES_PER_READING = 10.0

#: Pages per erase sector: `store.PAGE_BYTES` into a 4096-byte flash sector.
PAGES_PER_SECTOR = 4096 // store.PAGE_BYTES


# --- the derating -----------------------------------------------------------

#: The single conservatism knob. Every reported runtime is multiplied by this.
#:
#: The captain's requirement, in his words: it is better to estimate too little
#: time and have it last longer than to promise an amount of time and fail to
#: deliver it. So the number this module reports is an underestimate **by
#: design**, and this is the one place that is decided. Nothing else in this file
#: quietly shades a figure for safety; the constants are the best guess of the
#: physics and this is the margin, so the two can be tuned independently.
#:
#: What it covers, and roughly what each is worth:
#:
#: * **Measurement uncertainty.** Most rows in ``DRAWS`` are ``ESTIMATE`` and
#:   several are unverified recollections. They are already guessed high, but
#:   guessed high is not the same as right.
#: * **Battery ageing.** A cell at 80% of its rated capacity is a healthy cell,
#:   not a failing one, and a capacity figure will usually be the *rated* one.
#: * **Cold.** A lithium cell delivers noticeably less below about 5 C, and this
#:   is an air-quality instrument that will be asked to sit outdoors.
#: * **Voltage sag and conversion loss.** The cell's terminal voltage falls
#:   under load and across discharge, and something has to make 5 V for the
#:   sensor and 3.3 V for the ESP32 out of it. A boost converter at these
#:   currents is 85-90% efficient at best, less at the low end.
#: * **Draws nothing here models.** Flash reads during a backfill, the SPS30's
#:   inrush at power-on, whatever a future fuel gauge itself costs.
#:
#: **Initial value: 0.70, i.e. report 70% of the modelled runtime.** Chosen as
#: roughly 0.85 for the battery half (ageing plus cold plus the unusable end of
#: the discharge curve) times 0.85 for the conversion half, which is 0.72, taken
#: down to 0.70 for the estimate-heavy constants. It is one constant rather than
#: a product on purpose: a product invites tuning one factor and forgetting the
#: others, and there is no measurement yet that could justify separating them.
#:
#: **How to tune it.** Once a battery exists, run a state to flat, compare the
#: real runtime with ``estimate_runtime(..., derating=1.0)``, and move this to
#: the worst ratio observed rather than the average one - the point of it is the
#: bad case. Moving it *up* past about 0.9 means the constants have been
#: measured and this has become a margin rather than an apology; moving it down
#: past about 0.5 means a constant is wrong and should be fixed instead.
RUNTIME_DERATING = 0.70


# --- what the device is doing -----------------------------------------------

WIFI_OFF = "off"
WIFI_IDLE = "idle"            # associated, no client traffic
WIFI_STREAMING = "streaming"  # associated and serving clients
WIFI_STATES = (WIFI_OFF, WIFI_IDLE, WIFI_STREAMING)

BLE_OFF = "off"
BLE_ADVERTISING = "advertising"
BLE_CONNECTED = "connected"
BLE_STATES = (BLE_OFF, BLE_ADVERTISING, BLE_CONNECTED)

CPU_ACTIVE = "active"
CPU_LIGHT_SLEEP = "light_sleep"
CPU_STATES = (CPU_ACTIVE, CPU_LIGHT_SLEEP)


@dataclass(frozen=True)
class Connectivity:
    """Which radios are up, and doing what.

    Both fields at once is legitimate and is not an error: the board serves the
    identical protocol over Wi-Fi and Bluetooth simultaneously, and a transport
    change is make-before-break, so there are moments when both are live.

    The defaults are what the firmware does today with an app attached - Wi-Fi
    serving, Bluetooth advertising for whoever turns up - which is also the more
    expensive reading of an unknown state, and so the right default here.
    """

    wifi: str = WIFI_STREAMING
    ble: str = BLE_ADVERTISING

    def normalized(self) -> "Connectivity":
        """Coerce unknown states to the costliest one in their family.

        Unknown means unknown, and the module's rule is that ignorance shortens
        the answer. This mirrors ``wire.normalize_profile``'s posture of never
        raising over something that arrived from outside.
        """
        wifi = self.wifi if self.wifi in WIFI_STATES else WIFI_STREAMING
        ble = self.ble if self.ble in BLE_STATES else BLE_CONNECTED
        return Connectivity(wifi=wifi, ble=ble)


@dataclass(frozen=True)
class DeviceState:
    """Everything the model needs to know about what the device is doing.

    ``profile`` is a ``wire.py`` acquisition profile and is normalized on the way
    in, so a profile straight off a socket is safe to pass.

    ``sensor_sleeps_between_cycles`` defaults to False, which is the expensive
    answer, and it is not merely caution. Sleep needs SPS30 firmware >= 2.0, and
    ``park_sensor`` in ``components/acq/acq.c`` says in as many words that the
    sleep command can fail and the cycle then "burns idle power instead of sleep
    power". So a duty-cycled board that is not sleeping is a state the firmware
    already knows how to be in, and it is the one this model assumes until told
    otherwise. Assuming the cheaper case would lengthen the estimate, which is
    the one direction this model may not err in.

    ``storage_readings_per_s`` overrides the write rate the profile implies. It
    exists because the write rate is a stated input to this model rather than a
    derived one - a board replaying an import, or one whose acquisition has
    stalled, writes at a rate its profile does not describe. Left None it is
    derived from the profile, which is the ordinary case.
    """

    profile: Mapping = field(default_factory=lambda: dict(wire.DEFAULT_PROFILE))
    connectivity: Connectivity = field(default_factory=Connectivity)
    cpu: str = CPU_ACTIVE
    sensor_sleeps_between_cycles: bool = False
    sensor_present: bool = True
    storage_readings_per_s: float | None = None
    board_overhead: bool = True

    def normalized(self) -> "DeviceState":
        cpu = self.cpu if self.cpu in CPU_STATES else CPU_ACTIVE
        return replace(
            self,
            profile=wire.normalize_profile(dict(self.profile)),
            connectivity=self.connectivity.normalized(),
            cpu=cpu,
        )


@dataclass(frozen=True)
class Battery:
    """What is left in the battery, as far as anything knows.

    ``capacity_mah`` is the remaining capacity, not the rated one - a fuel gauge
    reports the former and a datasheet the latter, and confusing them is the
    difference between a runtime and a fantasy. ``None`` means nobody knows,
    which is the state of every board in this project today and is answered with
    "unknown" rather than with a number.

    ``nominal_v`` turns that charge into energy. 3.7 V is one lithium-ion cell
    at its nominal point, which is the assumption a capacity in mAh usually
    carries; it is deliberately the nominal and not the fully charged 4.2 V,
    because the cell spends most of the discharge below the latter.
    """

    capacity_mah: float | None = None
    nominal_v: float = 3.7

    @property
    def energy_mwh(self) -> float | None:
        if self.capacity_mah is None:
            return None
        if not math.isfinite(self.capacity_mah) or not math.isfinite(self.nominal_v):
            return None
        if self.nominal_v <= 0:
            return None
        if self.capacity_mah < 0:
            return None
        return self.capacity_mah * self.nominal_v


#: No battery capacity was supplied at all. The honest answer to "how long will
#: it run" when nothing has been measured is not a number.
REASON_NO_CAPACITY = "no remaining battery capacity is known"

#: A capacity was supplied but was not a usable number - NaN, an infinity, a
#: negative charge, a nonsensical cell voltage.
REASON_UNUSABLE_CAPACITY = "the remaining capacity given is not a usable number"

#: The modelled power came out at or below zero, which no real state can do
#: since the board's own overhead is never free. Guarded anyway, because the
#: alternative is dividing by it and reporting an infinite runtime.
REASON_NO_DRAW = "the modelled power is not positive, so no runtime follows"


# --- the model --------------------------------------------------------------


def recorded_readings_per_s(profile: Mapping) -> float:
    """How many readings a second this profile causes to be *stored*.

    Continuous mode records one every ``interval``; duty-cycle mode folds a
    whole ``average_s`` window into one point per ``cycle_s``. This is the
    storage input, not the measurement one - in continuous mode raising the
    interval saves flash and saves nothing else, because the fan never stops.
    """
    p = wire.normalize_profile(dict(profile))
    if p["mode"] == wire.MODE_DUTY_CYCLE:
        return 1.0 / p["cycle_s"]
    return 1.0 / p["interval"]


#: Which subsystem each ``DRAWS`` row belongs to. One place, so that the
#: breakdown the captain asked for - sensor, processor, connectivity, storage -
#: cannot drift from the rows that make it up.
#:
#: The **order of ``DRAWS`` itself is a contract** with the firmware mirror:
#: ``power_draw_id_t`` is the same list in the same order, the generated vectors
#: index by it, and both sides accumulate in it so they land on the same bits
#: rather than merely on the same value. Add a row at the end of its section and
#: at the end of the enum's matching run; do not reorder.
SUBSYSTEM_OF: dict[str, str] = {
    "sensor_measuring": "sensor",
    "sensor_fan_starting": "sensor",
    "sensor_idle": "sensor",
    "sensor_sleep": "sensor",
    "sensor_cleaning": "sensor",
    "cpu_active": "controller",
    "cpu_light_sleep": "controller",
    "board_overhead": "controller",
    "wifi_idle": "radio",
    "wifi_streaming": "radio",
    "ble_advertising": "radio",
    "ble_connected": "radio",
    "flash_page_write": "storage",
    "flash_sector_erase": "storage",
}
assert set(SUBSYSTEM_OF) == set(DRAWS), "every draw belongs to exactly one subsystem"

#: The subsystems, in the order a breakdown should be read. The tuple itself
#: lives in ``wire.py``, because it is also the vocabulary of the
#: ``power_estimate`` message's ``by_subsystem`` object.
SUBSYSTEMS = wire.POWER_SUBSYSTEMS
assert set(SUBSYSTEM_OF.values()) == set(SUBSYSTEMS)


def sensor_draw_ma(state: DeviceState) -> dict[str, float]:
    """The sensor's average current over one whole cycle, per draw, at its rail.

    Returned per draw rather than as one number so that the model can say which
    constant a given state actually depends on - which is what makes
    ``constants_needing_measurement`` an ordered list of work rather than a
    guess. Sum it for the subsystem total; ``sensor_mean_ma`` does that.

    Continuous mode is the easy half: the fan and laser never stop, so it is the
    measuring current flat, plus the amortised self-clean.

    Duty-cycle mode is the interesting half, and it is where the conservative
    assumptions live. The warm-up window is charged at the fan's *peak* current
    rather than its running current, because the warm-up is exactly the window
    in which the fan is accelerating. The remainder of the cycle is charged at
    idle unless the caller has said the sensor can sleep.

    Normalizes on the way in, and so does every other public function here.
    Idempotent, so ``estimate_power`` normalizing first costs nothing - and
    without it a profile that omitted a field would reach the arithmetic with
    the field simply missing, which is a crash rather than a clamp.
    """
    state = state.normalized()
    if not state.sensor_present:
        return {}

    p = state.profile
    # Amortised over the whole week between cleans. Present in every state
    # because the fan-clean account (`acq_clean.h`) survives restarts and is not
    # something a profile can switch off.
    out = {
        "sensor_cleaning": DRAWS["sensor_cleaning"].current_ma
        * CLEAN_SECONDS
        / CLEAN_INTERVAL_SECONDS
    }

    if p["mode"] != wire.MODE_DUTY_CYCLE:
        out["sensor_measuring"] = DRAWS["sensor_measuring"].current_ma
        return out

    cycle_s = p["cycle_s"]
    warmup_s = p["warmup_s"]
    average_s = p["average_s"]
    # `wire.normalize_profile` guarantees cycle_s >= warmup_s + average_s, so
    # this cannot go negative. Clamped anyway: this arrives from a caller, and
    # a negative idle window would *shorten* the mean and lengthen the answer.
    off_s = max(0.0, cycle_s - warmup_s - average_s)
    between = "sensor_sleep" if state.sensor_sleeps_between_cycles else "sensor_idle"

    out["sensor_fan_starting"] = DRAWS["sensor_fan_starting"].current_ma * warmup_s / cycle_s
    out["sensor_measuring"] = DRAWS["sensor_measuring"].current_ma * average_s / cycle_s
    out[between] = DRAWS[between].current_ma * off_s / cycle_s
    return out


def radio_draw_ma(connectivity: Connectivity) -> dict[str, float]:
    """The radios' adders, per draw, at the ESP32's rail.

    Summed rather than maxed: this firmware really does run both at once.
    """
    connectivity = connectivity.normalized()
    out: dict[str, float] = {}
    if connectivity.wifi == WIFI_IDLE:
        out["wifi_idle"] = DRAWS["wifi_idle"].current_ma
    elif connectivity.wifi == WIFI_STREAMING:
        out["wifi_streaming"] = DRAWS["wifi_streaming"].current_ma

    if connectivity.ble == BLE_ADVERTISING:
        out["ble_advertising"] = DRAWS["ble_advertising"].current_ma
    elif connectivity.ble == BLE_CONNECTED:
        out["ble_connected"] = DRAWS["ble_connected"].current_ma
    return out


def storage_draw_ma(state: DeviceState) -> dict[str, float]:
    """The flash's average adders for the write rate this state implies.

    Two terms. Pages are programmed as readings fill them - ten bytes a reading
    into ``store.BODY_BYTES`` of usable page - and sectors are erased ahead of
    those pages once the ring has wrapped.

    **The wrap is assumed to have happened.** On a fresh board no erase is
    needed and this term is smaller, but a board that has been recording for two
    days is a wrapped one, and assuming the cheaper case would lengthen the
    estimate. This is a small term either way: at 1 Hz it is well under a
    milliamp, which is itself worth knowing - "writing to storage" is named as a
    thing that costs power, and on this hardware, at these rates, it does not.

    The derived rate follows the **profile**, not the sensor: a board also
    writes what a client sends it, so ``sensor_present=False`` does not by
    itself mean nothing is being written. A caller that knows nothing is says
    so, with ``storage_readings_per_s=0``.
    """
    state = state.normalized()
    rate = state.storage_readings_per_s
    if rate is None:
        rate = recorded_readings_per_s(state.profile)
    if not math.isfinite(rate) or rate <= 0:
        return {}

    pages_per_s = rate * BYTES_PER_READING / store.BODY_BYTES
    sectors_per_s = pages_per_s / PAGES_PER_SECTOR
    return {
        "flash_page_write": pages_per_s
        * FLASH_PAGE_WRITE_SECONDS
        * DRAWS["flash_page_write"].current_ma,
        "flash_sector_erase": sectors_per_s
        * FLASH_SECTOR_ERASE_SECONDS
        * DRAWS["flash_sector_erase"].current_ma,
    }


def controller_draw_ma(state: DeviceState) -> dict[str, float]:
    """The processor's own draw plus the board's overhead, radios excluded."""
    state = state.normalized()
    cpu = "cpu_light_sleep" if state.cpu == CPU_LIGHT_SLEEP else "cpu_active"
    out = {cpu: DRAWS[cpu].current_ma}
    if state.board_overhead:
        out["board_overhead"] = DRAWS["board_overhead"].current_ma
    return out


def sensor_mean_ma(state: DeviceState) -> float:
    """The sensor's average current over a whole cycle, at its own rail."""
    return sum(sensor_draw_ma(state).values())


def radio_mean_ma(connectivity: Connectivity) -> float:
    """The radios' combined adder, at the ESP32's rail."""
    return sum(radio_draw_ma(connectivity).values())


def storage_mean_ma(state: DeviceState) -> float:
    """The flash's average adder, at the ESP32's rail."""
    return sum(storage_draw_ma(state).values())


def controller_mean_ma(state: DeviceState) -> float:
    """The processor and the board's overhead, at the ESP32's rail."""
    return sum(controller_draw_ma(state).values())


@dataclass(frozen=True)
class Power:
    """What a state costs, broken down the way the question was asked.

    Milliwatts throughout, because the SPS30 and the ESP32 are on different
    rails and their currents do not add. ``by_subsystem`` sums to ``total_mw``,
    and so does ``by_draw`` - the same total cut two ways, one for a person
    reading a report and one for deciding which constant is worth measuring.
    """

    by_subsystem: Mapping[str, float]
    by_draw: Mapping[str, float]
    total_mw: float
    state: DeviceState

    def battery_ma(self, nominal_v: float = 3.7) -> float:
        """The current this would pull from a cell at ``nominal_v``.

        Convenience for comparing against a meter. Note it is the current *out
        of the cell before conversion losses*, which are inside
        ``RUNTIME_DERATING`` rather than here - so a meter will read higher than
        this, and that is the model being conservative, not being wrong.
        """
        if not math.isfinite(nominal_v) or nominal_v <= 0:
            return math.inf
        return self.total_mw / nominal_v


def estimate_power(state: DeviceState) -> Power:
    """The power a given device state uses, in milliwatts.

    The state is normalized first, so a profile straight off a socket and a
    connectivity value nobody recognises are both safe: the profile is clamped
    the way ``wire.normalize_profile`` clamps it, and an unrecognised radio
    state is read as the costliest one in its family.
    """
    s = state.normalized()
    per_rail = (
        (sensor_draw_ma(s), SPS30_RAIL_V),
        (controller_draw_ma(s), ESP32_RAIL_V),
        (radio_draw_ma(s.connectivity), ESP32_RAIL_V),
        (storage_draw_ma(s), ESP32_RAIL_V),
    )
    # Every row, including the ones this state does not use, so the breakdown is
    # a complete account rather than only what happened to be on. It is also
    # what lets the C mirror sum in exactly this order and land on exactly these
    # bits - floating-point addition is not associative, and the vectors compare
    # for equality rather than for closeness.
    by_draw: dict[str, float] = {key: 0.0 for key in DRAWS}
    for currents, rail_v in per_rail:
        for key, current_ma in currents.items():
            by_draw[key] += current_ma * rail_v

    by_subsystem = {name: 0.0 for name in SUBSYSTEMS}
    for key in DRAWS:
        by_subsystem[SUBSYSTEM_OF[key]] += by_draw[key]

    return Power(
        by_subsystem=by_subsystem,
        by_draw=by_draw,
        total_mw=sum(by_subsystem.values()),
        state=s,
    )


@dataclass(frozen=True)
class Runtime:
    """How long the device will keep going, or why that cannot be said.

    ``hours`` is ``None`` when the question has no honest answer, and ``reason``
    says which way it failed. A caller must not turn a ``None`` into a zero or
    into a dash and a number: "unknown" and "about to die" are opposite states
    and the whole point of returning None is that they stay distinguishable.

    ``hours`` is already derated. ``modelled_hours`` is what the physics alone
    said, kept so that the derating can be seen rather than trusted, and so the
    day somebody runs a real battery flat they can compare against it directly.
    """

    hours: float | None
    modelled_hours: float | None
    derating: float
    total_mw: float
    reason: str = ""

    @property
    def known(self) -> bool:
        return self.hours is not None

    def describe(self) -> str:
        """One line a log or a report can carry. Never a bare number."""
        if self.hours is None:
            return f"runtime unknown: {self.reason}"
        return (
            f"about {self.hours:.1f} h left at {self.total_mw:.0f} mW "
            f"(derated by {self.derating:.2f} from {self.modelled_hours:.1f} h)"
        )


def estimate_runtime(
    power: Power,
    battery: Battery | None,
    derating: float = RUNTIME_DERATING,
) -> Runtime:
    """How long ``power`` runs on ``battery``, deliberately underestimated.

    ``derating`` is exposed only so that it can be measured against: pass 1.0 to
    get the undereated physics when comparing the model with a battery somebody
    actually ran flat. Production callers leave it alone.

    Degrading gracefully is a requirement rather than a nicety here, so every
    way of not knowing is answered with ``hours=None`` and a reason:

    * no battery at all, or a capacity of ``None`` - the state of every board in
      this project today;
    * a capacity that is NaN, infinite or negative - what a flaky fuel gauge
      returns before anyone has learned to distrust it;
    * a nonsensical cell voltage;
    * a modelled power of zero or less, which no real state produces but which
      would otherwise divide into an infinite runtime.

    A remaining capacity of exactly zero is **not** one of these: it is a
    perfectly good answer of 0.0 hours, and reporting it as unknown would hide a
    flat battery behind a shrug.
    """
    if not math.isfinite(derating) or derating <= 0:
        derating = RUNTIME_DERATING

    if battery is None or battery.capacity_mah is None:
        return Runtime(None, None, derating, power.total_mw, REASON_NO_CAPACITY)

    energy_mwh = battery.energy_mwh
    if energy_mwh is None:
        return Runtime(None, None, derating, power.total_mw, REASON_UNUSABLE_CAPACITY)

    if not math.isfinite(power.total_mw) or power.total_mw <= 0:
        return Runtime(None, None, derating, power.total_mw, REASON_NO_DRAW)

    modelled = energy_mwh / power.total_mw
    return Runtime(
        hours=modelled * derating,
        modelled_hours=modelled,
        derating=derating,
        total_mw=power.total_mw,
    )


def estimate(
    state: DeviceState,
    battery: Battery | None = None,
    derating: float = RUNTIME_DERATING,
) -> tuple[Power, Runtime]:
    """The whole question in one call: what it draws, and how long that lasts.

    The two halves are separately callable because the first is useful without a
    battery - comparing profiles, arguing about whether a radio is worth its
    keep - and today that is the only half anything can actually use.
    """
    power = estimate_power(state)
    return power, estimate_runtime(power, battery, derating)


# --- the wire reply ----------------------------------------------------------

#: `Runtime.reason` prose to the wire's enum. The prose is for a log a person
#: reads; the wire carries a value a client can switch on without parsing it.
_RUNTIME_STATUS_OF_REASON = {
    REASON_NO_CAPACITY: wire.POWER_RUNTIME_NO_CAPACITY,
    REASON_UNUSABLE_CAPACITY: wire.POWER_RUNTIME_UNUSABLE_CAPACITY,
    REASON_NO_DRAW: wire.POWER_RUNTIME_NO_DRAW,
}


def runtime_payload(runtime: Runtime) -> dict:
    """A :class:`Runtime` as the `runtime` slot of a ``power_estimate`` reply."""
    if runtime.known:
        return wire.power_runtime_payload(
            wire.POWER_RUNTIME_OK, runtime.hours, runtime.modelled_hours,
            runtime.derating,
        )
    status = _RUNTIME_STATUS_OF_REASON.get(runtime.reason, wire.POWER_RUNTIME_NO_CAPACITY)
    return wire.power_runtime_payload(status)


def estimate_message(state: DeviceState, battery: Battery | None = None) -> dict:
    """The whole ``power_estimate`` reply for ``state``, as ``wire.py`` frames it.

    This is the specification of the firmware's answer to
    ``wire.CMD_GET_POWER_ESTIMATE``, and the generated vectors hold the C to it
    byte for byte, so the composition choices here are the contract:

    * An alternative is **this board doing something else**: every input except
      the acquisition profile is carried over unchanged, so the comparison
      isolates the one thing the user can change from the panel. A stated write
      rate is the exception - it describes what the board is doing right now
      (an import, a stall), not what recording under another profile would
      write, so each alternative derives its rate from its own profile.
    * ``sleeps`` is carried over as this board's reality. A board whose sensor
      cannot sleep is priced without the sleeping, under every alternative,
      because showing it the capable board's saving would overstate a switch it
      cannot actually make.
    * Per-alternative ``hours`` appear exactly when the current runtime is a
      number - one battery, priced under every mode - which is the future the
      panel is shaped for and no board can reach today.
    """
    s = state.normalized()
    current_power = estimate_power(s)
    runtime = estimate_runtime(current_power, battery)

    alternatives = []
    for profile in wire.power_compare_profiles():
        alt_state = replace(s, profile=profile, storage_readings_per_s=None)
        alt_power = estimate_power(alt_state)
        alt_runtime = estimate_runtime(alt_power, battery)
        alternatives.append(wire.power_alternative_payload(
            profile, s.sensor_sleeps_between_cycles, alt_power.total_mw,
            hours=alt_runtime.hours,
        ))

    return wire.power_estimate_message(
        wire.power_current_payload(
            s.profile, s.sensor_sleeps_between_cycles, current_power.total_mw,
            current_power.by_subsystem,
        ),
        alternatives,
        runtime_payload(runtime),
        sources={source.value: count for source, count in sources_summary().items()},
    )


# --- reporting on the constants themselves ----------------------------------


def constants_needing_measurement(power: Power | None = None) -> list[tuple[str, Draw, float]]:
    """The ``ESTIMATE`` rows ordered by what an error in each is actually worth.

    The list whoever gets a meter should work down, and the ordering is the
    point of it. Ranking by the size of a row would put the fan self-clean at
    the top, because 80 mA is the biggest number in the table - and it would be
    wrong, because that 80 mA runs for twenty seconds a week and is worth a
    thousandth of the total. So the rank is each row's **contribution to a real
    state**, which is what ``Power.by_draw`` carries.

    ``power`` defaults to the default state, which is the board as the firmware
    runs it today: continuous acquisition, Wi-Fi serving, Bluetooth advertising.
    Pass another to ask the question about a state you care about instead - the
    answer genuinely differs, since the radios dominate one and the sensor the
    other.

    Returns ``(key, draw, mw_in_this_state)``, largest first. Rows a state does
    not use are included at zero rather than dropped, so the list stays a
    complete account of what has not been measured.
    """
    if power is None:
        power = estimate_power(DeviceState())
    rows = [
        (key, draw, power.by_draw.get(key, 0.0))
        for key, draw in DRAWS.items()
        if draw.source is Source.ESTIMATE
    ]
    return sorted(rows, key=lambda row: row[2], reverse=True)


def sources_summary() -> dict[Source, int]:
    """How many constants come from each kind of source.

    Exists to be looked at: as long as ``MEASURED`` is near zero, every number
    this module produces is an argument rather than a fact.
    """
    counts = {source: 0 for source in Source}
    for draw in DRAWS.values():
        counts[draw.source] += 1
    return counts
