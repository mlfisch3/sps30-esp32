#!/usr/bin/env python3
"""Generate C test vectors by running the real power model, ``power.py``.

The same argument as ``gen_vectors.py`` and ``gen_wire_vectors.py``:
``components/power`` is a port of ``power.py``, and the strongest available
check on a port is not a test somebody invented but the real module's own
answers written down and compared against.

It matters more here than usual, for a reason particular to this model. Every
number in it is an estimate or a datasheet figure - nothing has been metered -
so there is no external truth to check either implementation against. What
*can* be checked is that the two agree, and that the deliberate conservatism
survives the port: a mirror that quietly dropped the derating, or read an
unknown radio state as "off" instead of as the costliest one, would produce
longer runtimes and look entirely healthy doing it.

Compared for **equality**, not for closeness. ``power.py`` and
``power_model.c`` accumulate in the same order for exactly that reason - see
the note in ``estimate_power`` - so any difference at all is a real difference.

Usage:  python3 gen_power_vectors.py [output.h]  (default: power_vectors_generated.h)
"""
from __future__ import annotations

import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

sys.path.insert(0, str(HERE))
sys.path.insert(0, str(REPO_ROOT))

# Reuses the pyserial stub and the real sps30.py loader, so this imports the
# reference implementation the same way the other two generators do.
import gen_vectors  # noqa: E402
from gen_wire_vectors import c_double, c_string  # noqa: E402

import power  # noqa: E402  (the repository's power.py, the model's source of truth)
import store  # noqa: E402
import wire  # noqa: E402

assert gen_vectors.sps30 is not None

#: `power.DRAWS` keys in table order, which is `power_draw_id_t` order in the C.
#: Emitted so the header can assert the two tables are the same table.
DRAW_KEYS = list(power.DRAWS)

WIFI_CODES = {power.WIFI_OFF: 0, power.WIFI_IDLE: 1, power.WIFI_STREAMING: 2}
BLE_CODES = {power.BLE_OFF: 0, power.BLE_ADVERTISING: 1, power.BLE_CONNECTED: 2}
CPU_CODES = {power.CPU_ACTIVE: 0, power.CPU_LIGHT_SLEEP: 1}
SOURCE_CODES = {
    power.Source.DATASHEET: 0,
    power.Source.MEASURED: 1,
    power.Source.ESTIMATE: 2,
}
SUBSYSTEM_CODES = {name: i for i, name in enumerate(power.SUBSYSTEMS)}
MODE_CODES = {wire.MODE_CONTINUOUS: 0, wire.MODE_DUTY_CYCLE: 1}

# --- the states the two implementations are compared on ---------------------
# The ends and the decisions, not the middle. Each case is here because getting
# it wrong in the port would be invisible from the outside:
#
#   * the default state, which is the board as the firmware runs it today;
#   * each radio combination, because both radios really can be up at once;
#   * duty cycle with and without sleep, which is the whole low-power argument;
#   * a profile a client could send that the bounds have to catch;
#   * a stated write rate, and a stated write rate that is nonsense;
#   * the states nothing reaches yet - light sleep, no sensor - so that the port
#     is checked before anything depends on it.
STATE_CASES: list[tuple[str, power.DeviceState]] = [
    ("the board as it runs today", power.DeviceState()),
    (
        "everything off that can be",
        power.DeviceState(
            connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF),
            cpu=power.CPU_LIGHT_SLEEP,
            sensor_present=False,
            board_overhead=False,
        ),
    ),
    (
        "wifi idle, no bluetooth",
        power.DeviceState(connectivity=power.Connectivity(power.WIFI_IDLE, power.BLE_OFF)),
    ),
    (
        "bluetooth only, a board with no network",
        power.DeviceState(connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_CONNECTED)),
    ),
    (
        "both transports up at once, which a handover really does",
        power.DeviceState(
            connectivity=power.Connectivity(power.WIFI_STREAMING, power.BLE_CONNECTED)
        ),
    ),
    (
        "duty cycle, sensor cannot sleep (the assumed case)",
        power.DeviceState(profile={"mode": wire.MODE_DUTY_CYCLE}),
    ),
    (
        "duty cycle, sensor sleeps (firmware >= 2.0)",
        power.DeviceState(
            profile={"mode": wire.MODE_DUTY_CYCLE}, sensor_sleeps_between_cycles=True
        ),
    ),
    (
        "duty cycle at the longest period the bounds allow",
        power.DeviceState(
            profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": wire.CYCLE_MAX},
            sensor_sleeps_between_cycles=True,
        ),
    ),
    (
        "the thousand-day cycle a client could send, after clamping",
        power.DeviceState(profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 8.64e10}),
    ),
    (
        "duty cycle whose warm-up and average fill the whole period",
        power.DeviceState(
            profile={
                "mode": wire.MODE_DUTY_CYCLE,
                "cycle_s": 60.0,
                "warmup_s": 30.0,
                "average_s": 30.0,
            }
        ),
    ),
    (
        "continuous at a minute, which saves flash and nothing else",
        power.DeviceState(profile={"mode": wire.MODE_CONTINUOUS, "interval": 60.0}),
    ),
    (
        "a stated write rate, as an import produces",
        power.DeviceState(storage_readings_per_s=100.0),
    ),
    (
        "a stated write rate of zero: nothing is being written",
        power.DeviceState(storage_readings_per_s=0.0),
    ),
    (
        "a stated write rate that is not a number",
        power.DeviceState(storage_readings_per_s=float("nan")),
    ),
    (
        "a radio state nobody recognises, read as the costliest",
        power.DeviceState(connectivity=power.Connectivity("who knows", "nor this")),
    ),
]

# --- the runtimes -----------------------------------------------------------
# Every way of not knowing, plus the boundary that *is* known: flat.
RUNTIME_CASES: list[tuple[str, power.DeviceState, power.Battery | None, float]] = [
    ("no battery fitted, which is every board today", power.DeviceState(), None,
     power.RUNTIME_DERATING),
    ("a battery whose charge nobody has measured", power.DeviceState(), power.Battery(),
     power.RUNTIME_DERATING),
    ("2000 mAh on the board as it runs today", power.DeviceState(), power.Battery(2000.0),
     power.RUNTIME_DERATING),
    (
        "2000 mAh duty-cycled with the radios off",
        power.DeviceState(
            profile={"mode": wire.MODE_DUTY_CYCLE},
            connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF),
            sensor_sleeps_between_cycles=True,
        ),
        power.Battery(2000.0),
        power.RUNTIME_DERATING,
    ),
    ("a flat battery is zero hours, not unknown", power.DeviceState(), power.Battery(0.0),
     power.RUNTIME_DERATING),
    ("a gauge returning NaN", power.DeviceState(), power.Battery(float("nan")),
     power.RUNTIME_DERATING),
    ("a gauge returning an infinity", power.DeviceState(), power.Battery(float("inf")),
     power.RUNTIME_DERATING),
    ("a negative charge", power.DeviceState(), power.Battery(-100.0), power.RUNTIME_DERATING),
    ("a cell voltage of zero", power.DeviceState(), power.Battery(2000.0, 0.0),
     power.RUNTIME_DERATING),
    (
        "the derating lifted, which is how it gets tuned",
        power.DeviceState(),
        power.Battery(2000.0),
        1.0,
    ),
    (
        "a derating that is not usable falls back to the constant",
        power.DeviceState(),
        power.Battery(2000.0),
        float("nan"),
    ),
    (
        "a large cell on a quiet board, where the hours get long",
        power.DeviceState(
            profile={"mode": wire.MODE_DUTY_CYCLE},
            connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF),
            cpu=power.CPU_LIGHT_SLEEP,
            sensor_sleeps_between_cycles=True,
            board_overhead=False,
        ),
        power.Battery(10000.0),
        power.RUNTIME_DERATING,
    ),
]

RUNTIME_STATUS_CODES = {
    "": 0,
    power.REASON_NO_CAPACITY: 1,
    power.REASON_UNUSABLE_CAPACITY: 2,
    power.REASON_NO_DRAW: 3,
}


def state_literal(state: power.DeviceState) -> str:
    """A C `power_state_t` initialiser for [state], before normalization.

    Deliberately the *un*-normalized state: the C has to do its own clamping, so
    handing it the already-clamped profile would test nothing. A field Python
    holds as ``None`` becomes the `storage_rate_stated` flag being false, which
    is how the C spells "nobody said".
    """
    profile = dict(state.profile)
    mode = MODE_CODES.get(profile.get("mode"), 0)
    stated = state.storage_readings_per_s is not None
    return (
        "{ .profile = { "
        f".mode = {mode}, "
        f".interval_s = {c_double(float(profile.get('interval', wire.DEFAULT_PROFILE['interval'])))}, "
        f".cycle_s = {c_double(float(profile.get('cycle_s', wire.DEFAULT_PROFILE['cycle_s'])))}, "
        f".warmup_s = {c_double(float(profile.get('warmup_s', wire.DEFAULT_PROFILE['warmup_s'])))}, "
        f".average_s = {c_double(float(profile.get('average_s', wire.DEFAULT_PROFILE['average_s'])))}"
        " }, "
        f".wifi = {WIFI_CODES.get(state.connectivity.wifi, 99)}, "
        f".ble = {BLE_CODES.get(state.connectivity.ble, 99)}, "
        f".cpu = {CPU_CODES.get(state.cpu, 99)}, "
        f".sensor_sleeps_between_cycles = {'true' if state.sensor_sleeps_between_cycles else 'false'}, "
        f".sensor_present = {'true' if state.sensor_present else 'false'}, "
        f".storage_rate_stated = {'true' if stated else 'false'}, "
        f".storage_readings_per_s = {c_double(float(state.storage_readings_per_s or 0.0))}, "
        f".board_overhead = {'true' if state.board_overhead else 'false'} }}"
    )


def main(argv: list[str]) -> int:
    out_path = HERE / (argv[1] if len(argv) > 1 else "power_vectors_generated.h")

    parts: list[str] = [
        "/* GENERATED by gen_power_vectors.py from the repository's power.py.\n"
        " * Do not edit. Regenerate with `make -C firmware/test power-vectors`.\n"
        " *\n"
        " * Every double here was produced by running the real power model. The C\n"
        " * is asserted equal to them, not close to them - see the generator's\n"
        " * docstring for why that is achievable and why it is worth having. */\n"
        "#pragma once\n\n"
        "#include <stdbool.h>\n#include <math.h>\n\n"
    ]

    # --- the constants, so a drifted one is caught before any arithmetic is ---
    parts.append(
        f"#define PY_RUNTIME_DERATING {c_double(power.RUNTIME_DERATING)}\n"
        f"#define PY_SPS30_RAIL_V {c_double(power.SPS30_RAIL_V)}\n"
        f"#define PY_ESP32_RAIL_V {c_double(power.ESP32_RAIL_V)}\n"
        f"#define PY_CLEAN_SECONDS {c_double(power.CLEAN_SECONDS)}\n"
        f"#define PY_CLEAN_INTERVAL_SECONDS {c_double(power.CLEAN_INTERVAL_SECONDS)}\n"
        f"#define PY_FLASH_PAGE_WRITE_SECONDS {c_double(power.FLASH_PAGE_WRITE_SECONDS)}\n"
        f"#define PY_FLASH_SECTOR_ERASE_SECONDS {c_double(power.FLASH_SECTOR_ERASE_SECONDS)}\n"
        f"#define PY_BYTES_PER_READING {c_double(power.BYTES_PER_READING)}\n"
        f"#define PY_PAGE_BODY_BYTES {c_double(float(store.BODY_BYTES))}\n"
        f"#define PY_PAGES_PER_SECTOR {c_double(float(power.PAGES_PER_SECTOR))}\n"
        f"#define PY_DRAW_COUNT {len(DRAW_KEYS)}\n"
        f"#define PY_SUBSYSTEM_COUNT {len(power.SUBSYSTEMS)}\n\n"
    )

    # --- the table itself ---------------------------------------------------
    # Row for row: key, current, rail, source and subsystem. The source label is
    # in here because it is a claim about evidence, and a mirror that promoted a
    # guess to a datasheet figure would be making that claim on nobody's behalf.
    parts.append(
        "/* power.DRAWS, row for row and in table order. */\n"
        "typedef struct {\n"
        "    const char *key;\n"
        "    double current_ma;\n"
        "    double rail_v;\n"
        "    int source;     /* 0 datasheet, 1 measured, 2 estimate */\n"
        "    int subsystem;  /* 0 sensor, 1 controller, 2 radio, 3 storage */\n"
        "} py_draw_vector_t;\n\n"
        "static const py_draw_vector_t PY_DRAWS[] = {\n"
    )
    for key in DRAW_KEYS:
        draw = power.DRAWS[key]
        parts.append(
            f"    {{ {c_string(key)}, {c_double(draw.current_ma)}, {c_double(draw.rail_v)}, "
            f"{SOURCE_CODES[draw.source]}, {SUBSYSTEM_CODES[power.SUBSYSTEM_OF[key]]} }},\n"
        )
    parts.append("};\n\n")

    counts = power.sources_summary()
    parts.append(
        "/* What the model is currently built on. Emitted so the C pack says the\n"
        " * same uncomfortable thing the Python pack does: nothing here has been\n"
        " * measured on this board. */\n"
        f"#define PY_SOURCE_DATASHEET_COUNT {counts[power.Source.DATASHEET]}\n"
        f"#define PY_SOURCE_MEASURED_COUNT {counts[power.Source.MEASURED]}\n"
        f"#define PY_SOURCE_ESTIMATE_COUNT {counts[power.Source.ESTIMATE]}\n\n"
    )

    # --- state -> power -----------------------------------------------------
    parts.append(
        "/* power.estimate_power: a state in, a breakdown out. */\n"
        "typedef struct {\n"
        "    const char *description;\n"
        "    power_state_t state;              /* as given, NOT normalized */\n"
        f"    double by_draw_mw[{len(DRAW_KEYS)}];\n"
        f"    double by_subsystem_mw[{len(power.SUBSYSTEMS)}];\n"
        "    double total_mw;\n"
        "    double recorded_readings_per_s;\n"
        "} py_power_vector_t;\n\n"
        "static const py_power_vector_t PY_POWER_VECTORS[] = {\n"
    )
    for description, state in STATE_CASES:
        got = power.estimate_power(state)
        by_draw = ", ".join(c_double(got.by_draw[key]) for key in DRAW_KEYS)
        by_sub = ", ".join(c_double(got.by_subsystem[name]) for name in power.SUBSYSTEMS)
        rate = power.recorded_readings_per_s(state.profile)
        parts.append(
            f"    {{ {c_string(description)}, {state_literal(state)},\n"
            f"      {{ {by_draw} }},\n"
            f"      {{ {by_sub} }}, {c_double(got.total_mw)}, {c_double(rate)} }},\n"
        )
    parts.append("};\n\n")

    # --- power + battery -> runtime -----------------------------------------
    parts.append(
        "/* power.estimate_runtime: the derating, and every way of not knowing. */\n"
        "typedef struct {\n"
        "    const char *description;\n"
        "    power_state_t state;\n"
        "    bool capacity_known;\n"
        "    double capacity_mah;\n"
        "    double nominal_v;\n"
        "    double derating_in;\n"
        "    int status;             /* 0 ok, 1 no capacity, 2 unusable, 3 no draw */\n"
        "    double hours;           /* meaningful only when status == 0 */\n"
        "    double modelled_hours;\n"
        "    double derating_out;\n"
        "} py_runtime_vector_t;\n\n"
        "static const py_runtime_vector_t PY_RUNTIME_VECTORS[] = {\n"
    )
    for description, state, battery, derating in RUNTIME_CASES:
        got = power.estimate_power(state)
        runtime = power.estimate_runtime(got, battery, derating)
        status = RUNTIME_STATUS_CODES[runtime.reason]
        hours = runtime.hours if runtime.hours is not None else 0.0
        modelled = runtime.modelled_hours if runtime.modelled_hours is not None else 0.0
        known = battery is not None and battery.capacity_mah is not None
        capacity = battery.capacity_mah if known else 0.0
        volts = battery.nominal_v if battery is not None else 3.7
        parts.append(
            f"    {{ {c_string(description)}, {state_literal(state)},\n"
            f"      {'true' if known else 'false'}, {c_double(float(capacity))}, "
            f"{c_double(float(volts))}, {c_double(float(derating))},\n"
            f"      {status}, {c_double(hours)}, {c_double(modelled)}, "
            f"{c_double(runtime.derating)} }},\n"
        )
    parts.append("};\n")

    # --- the whole reply, as bytes ------------------------------------------
    # `power.estimate_message` through `wire.encode`: the line the app parses,
    # produced by the real Python, asserted byte for byte against
    # `power_report_line`. This is the check that keeps the app free of a power
    # model of its own - the one implementation is the firmware's, and this is
    # what holds it to the specification.
    parts.append(
        "/* power.estimate_message via wire.encode: the `power_estimate` line.\n"
        " * battery_null distinguishes no battery argument at all from a battery\n"
        " * whose capacity nobody knows; both must answer `no_capacity`. */\n"
        "typedef struct {\n"
        "    const char *description;\n"
        "    power_state_t state;              /* as given, NOT normalized */\n"
        "    bool battery_null;\n"
        "    bool capacity_known;\n"
        "    double capacity_mah;\n"
        "    double nominal_v;\n"
        "    const char *expected_line;\n"
        "} py_report_vector_t;\n\n"
        "static const py_report_vector_t PY_REPORT_VECTORS[] = {\n"
    )
    report_cases: list[tuple[str, power.DeviceState, power.Battery | None]] = [
        ("the board as it runs today, no battery", power.DeviceState(), None),
        (
            "a battery fitted but never measured",
            power.DeviceState(),
            power.Battery(),
        ),
        (
            "duty cycle, sensor sleeps, bluetooth only",
            power.DeviceState(
                profile={"mode": wire.MODE_DUTY_CYCLE},
                connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_CONNECTED),
                sensor_sleeps_between_cycles=True,
            ),
            None,
        ),
        (
            "duty cycle, sensor cannot sleep",
            power.DeviceState(profile={"mode": wire.MODE_DUTY_CYCLE}),
            None,
        ),
        (
            "no sensor answering, wifi idle",
            power.DeviceState(
                connectivity=power.Connectivity(power.WIFI_IDLE, power.BLE_ADVERTISING),
                sensor_present=False,
            ),
            None,
        ),
        (
            "an import in flight: the stated rate must not price the alternatives",
            power.DeviceState(storage_readings_per_s=100.0),
            None,
        ),
        (
            "2000 mAh known: the runtime slot and every alternative gain hours",
            power.DeviceState(),
            power.Battery(2000.0),
        ),
        (
            "a flat battery is zero hours everywhere, not unknown",
            power.DeviceState(),
            power.Battery(0.0),
        ),
        (
            "a gauge returning NaN is unusable, not zero",
            power.DeviceState(),
            power.Battery(float("nan")),
        ),
    ]
    for description, state, battery in report_cases:
        line = wire.encode(power.estimate_message(state, battery)).decode("utf-8")
        battery_null = battery is None
        known = battery is not None and battery.capacity_mah is not None
        capacity = battery.capacity_mah if known else 0.0
        volts = battery.nominal_v if battery is not None else 3.7
        parts.append(
            f"    {{ {c_string(description)}, {state_literal(state)},\n"
            f"      {'true' if battery_null else 'false'}, {'true' if known else 'false'}, "
            f"{c_double(float(capacity))}, {c_double(float(volts))},\n"
            f"      {c_string(line)} }},\n"
        )
    parts.append("};\n\n")

    # The reply's vocabulary and comparison list, so the C constants cannot
    # drift from the wire.py that defines them.
    parts.append(
        f"#define PY_CMD_GET_POWER_ESTIMATE {c_string(wire.CMD_GET_POWER_ESTIMATE)}\n"
        f"#define PY_MSG_POWER_ESTIMATE {c_string(wire.MSG_POWER_ESTIMATE)}\n"
        f"#define PY_POWER_BASIS_MODELLED {c_string(wire.POWER_ESTIMATE_BASIS_MODELLED)}\n"
        f"#define PY_POWER_RUNTIME_OK {c_string(wire.POWER_RUNTIME_OK)}\n"
        f"#define PY_POWER_RUNTIME_NO_CAPACITY {c_string(wire.POWER_RUNTIME_NO_CAPACITY)}\n"
        f"#define PY_POWER_RUNTIME_UNUSABLE_CAPACITY {c_string(wire.POWER_RUNTIME_UNUSABLE_CAPACITY)}\n"
        f"#define PY_POWER_RUNTIME_NO_DRAW {c_string(wire.POWER_RUNTIME_NO_DRAW)}\n"
        f"#define PY_POWER_COMPARE_CYCLE_COUNT {len(wire.POWER_COMPARE_CYCLES)}\n"
        "static const double PY_POWER_COMPARE_CYCLES[] = { "
        + ", ".join(c_double(v) for v in wire.POWER_COMPARE_CYCLES)
        + " };\n"
        "static const char *const PY_POWER_SUBSYSTEMS[] = { "
        + ", ".join(c_string(name) for name in wire.POWER_SUBSYSTEMS)
        + " };\n\n"
    )

    out_path.write_text("".join(parts))
    print(
        f"wrote {out_path} from {REPO_ROOT / 'power.py'}: "
        f"{len(DRAW_KEYS)} draws, {len(STATE_CASES)} states, "
        f"{len(RUNTIME_CASES)} runtimes, {len(report_cases)} reply lines"
    )
    # Said out loud on every regeneration, because it is the thing that most
    # easily stops being true without anyone noticing it ever was.
    if counts[power.Source.MEASURED] == 0:
        print(
            "  note: no constant in the model has been measured on this board. "
            "Every figure is a datasheet value or an estimate."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
