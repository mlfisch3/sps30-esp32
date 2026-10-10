"""The power model: known state in, known power out, and a runtime that is short.

No hardware and no battery - there is no battery on this device to have. These
drive ``power.py``, which is the specification the firmware's
``components/power`` is a port of.

Four things are being held here, and they are not equally interesting:

1. **The arithmetic.** A state goes in, a number comes out, and the number is
   the one hand-computed from the constants rather than the one the code
   happened to produce. Where a case asserts a literal, that literal is derived
   in the test from ``power.DRAWS`` so that changing a constant changes the
   expectation - and where it asserts a *relationship* (duty cycling costs less
   than continuous; a radio costs something) it is a relationship that must hold
   whatever the constants become.
2. **The derating is applied, once, and shortens the answer.** The captain's
   requirement is that the reported time is an underestimate, and a model that
   quietly stopped derating would still look completely healthy.
3. **Not knowing degrades gracefully.** The state of every board in this project
   today is "no battery capacity known", and the answer to that must be an
   admission and not a confident number.
4. **The conservative choices are the ones the code makes.** Several assumptions
   could have gone either way, and every one of them was resolved toward a
   shorter runtime. That is a decision, so it is asserted rather than trusted.
"""
import math

import pytest

import power
import store
import wire


# --- the constants table ----------------------------------------------------
def test_every_draw_records_a_source_and_a_provenance():
    for key, draw in power.DRAWS.items():
        assert isinstance(draw.source, power.Source), key
        assert draw.provenance.strip(), f"{key} has no provenance"
        assert draw.current_ma > 0, key
        assert draw.rail_v in (power.SPS30_RAIL_V, power.ESP32_RAIL_V), key


def test_nothing_claims_to_have_been_measured():
    """The honest state of this model, asserted so it cannot drift quietly.

    Not a rule against measuring: it is a rule against a row *saying* it was
    measured when nobody has put a meter on this board. When somebody does, this
    test is the one to change, in the same commit that records what they read.
    """
    counts = power.sources_summary()
    assert counts[power.Source.MEASURED] == 0, (
        "a row now claims to be measured. If that is true, update this test and "
        "record in the row's provenance who measured it, with what, and when."
    )
    assert counts[power.Source.ESTIMATE] > 0


def test_every_draw_belongs_to_exactly_one_subsystem():
    assert set(power.SUBSYSTEM_OF) == set(power.DRAWS)
    assert set(power.SUBSYSTEM_OF.values()) <= set(power.SUBSYSTEMS)


def test_the_write_rate_comes_from_the_measured_byte_cost():
    """The one genuinely measured input, and where it came from."""
    assert power.BYTES_PER_READING == 10.0  # STORAGE_FORMAT.md, over 120,000 readings
    assert power.PAGES_PER_SECTOR == 4096 // store.PAGE_BYTES == 8


# --- known state in, known power out ----------------------------------------
def test_continuous_sensor_is_the_measuring_current_plus_the_amortised_clean():
    """Continuous mode: the fan never stops, so there is nothing to average."""
    state = power.DeviceState(profile={"mode": wire.MODE_CONTINUOUS, "interval": 1.0})
    expected_ma = power.DRAWS["sensor_measuring"].current_ma + (
        power.DRAWS["sensor_cleaning"].current_ma
        * power.CLEAN_SECONDS
        / power.CLEAN_INTERVAL_SECONDS
    )
    assert power.sensor_mean_ma(state) == pytest.approx(expected_ma)

    got = power.estimate_power(state)
    assert got.by_subsystem["sensor"] == pytest.approx(expected_ma * power.SPS30_RAIL_V)


def test_a_longer_continuous_interval_saves_storage_and_not_the_sensor():
    """wire.py says so in as many words, and this is the assertion of it."""
    fast = power.DeviceState(profile={"mode": wire.MODE_CONTINUOUS, "interval": 1.0})
    slow = power.DeviceState(profile={"mode": wire.MODE_CONTINUOUS, "interval": 60.0})

    assert power.sensor_mean_ma(fast) == power.sensor_mean_ma(slow)
    assert power.storage_mean_ma(slow) < power.storage_mean_ma(fast)
    # And the saving is real but negligible, which is itself the finding: on
    # this hardware, at these rates, writing to flash is not a power cost.
    assert power.estimate_power(fast).by_subsystem["storage"] < 1.0  # mW


def test_duty_cycle_averages_the_three_windows_by_hand():
    """The whole duty-cycle sum, spelled out rather than taken from the code."""
    profile = wire.normalize_profile(
        {"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0, "warmup_s": 30.0, "average_s": 30.0}
    )
    state = power.DeviceState(profile=profile, sensor_sleeps_between_cycles=True)

    warm = power.DRAWS["sensor_fan_starting"].current_ma * 30.0
    measure = power.DRAWS["sensor_measuring"].current_ma * 30.0
    asleep = power.DRAWS["sensor_sleep"].current_ma * (900.0 - 60.0)
    clean = (
        power.DRAWS["sensor_cleaning"].current_ma
        * power.CLEAN_SECONDS
        / power.CLEAN_INTERVAL_SECONDS
    )
    expected_ma = (warm + measure + asleep) / 900.0 + clean

    assert power.sensor_mean_ma(state) == pytest.approx(expected_ma)


def test_duty_cycle_is_worth_a_great_deal_at_the_sensor():
    """The reason the mode exists, held as a relationship rather than a number."""
    continuous = power.DeviceState(profile={"mode": wire.MODE_CONTINUOUS})
    duty = power.DeviceState(
        profile={"mode": wire.MODE_DUTY_CYCLE}, sensor_sleeps_between_cycles=True
    )
    assert power.sensor_mean_ma(duty) < power.sensor_mean_ma(continuous) / 5


def test_the_subsystem_breakdown_and_the_per_draw_one_are_the_same_total():
    got = power.estimate_power(power.DeviceState())
    assert sum(got.by_subsystem.values()) == pytest.approx(got.total_mw)
    assert sum(got.by_draw.values()) == pytest.approx(got.total_mw)
    assert set(got.by_subsystem) == set(power.SUBSYSTEMS)


def test_the_two_rails_are_not_added_as_currents():
    """The SPS30 is on 5 V and the ESP32 on 3.3 V, and that has to show.

    If somebody ever collapses this to one rail, the sensor's contribution
    changes by a third and this is what notices.
    """
    sensor_only = power.DeviceState(
        connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF),
        board_overhead=False,
        cpu=power.CPU_LIGHT_SLEEP,
        profile={"mode": wire.MODE_CONTINUOUS},
    )
    got = power.estimate_power(sensor_only)
    assert got.by_subsystem["sensor"] == pytest.approx(
        power.sensor_mean_ma(sensor_only.normalized()) * power.SPS30_RAIL_V
    )
    assert got.by_subsystem["controller"] == pytest.approx(
        power.DRAWS["cpu_light_sleep"].current_ma * power.ESP32_RAIL_V
    )


# --- connectivity -----------------------------------------------------------
def test_both_radios_at_once_costs_both():
    """The board really does serve Wi-Fi and Bluetooth simultaneously."""
    both = power.Connectivity(power.WIFI_STREAMING, power.BLE_CONNECTED)
    assert power.radio_mean_ma(both) == pytest.approx(
        power.DRAWS["wifi_streaming"].current_ma + power.DRAWS["ble_connected"].current_ma
    )


def test_the_radio_states_are_ordered_the_way_the_physics_is():
    off = power.radio_mean_ma(power.Connectivity(power.WIFI_OFF, power.BLE_OFF))
    advertising = power.radio_mean_ma(power.Connectivity(power.WIFI_OFF, power.BLE_ADVERTISING))
    connected = power.radio_mean_ma(power.Connectivity(power.WIFI_OFF, power.BLE_CONNECTED))
    wifi_idle = power.radio_mean_ma(power.Connectivity(power.WIFI_IDLE, power.BLE_OFF))
    wifi_busy = power.radio_mean_ma(power.Connectivity(power.WIFI_STREAMING, power.BLE_OFF))

    assert off == 0.0
    assert 0 < advertising < connected < wifi_idle < wifi_busy


def test_turning_the_radios_off_is_the_biggest_saving_available_today():
    """Which is the finding the model exists to produce, so it is asserted."""
    on = power.estimate_power(power.DeviceState())
    off = power.estimate_power(
        power.DeviceState(connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF))
    )
    assert on.total_mw - off.total_mw == on.by_subsystem["radio"]
    assert on.by_subsystem["radio"] > on.by_subsystem["sensor"]


# --- storage ----------------------------------------------------------------
def test_the_storage_term_follows_the_stated_write_rate_not_the_profile():
    """The write rate is an input, because a board can write without recording.

    An import, or a replay, writes at a rate its acquisition profile says
    nothing about, so the model takes the rate when it is given one.
    """
    profile = {"mode": wire.MODE_CONTINUOUS, "interval": 60.0}
    derived = power.estimate_power(power.DeviceState(profile=profile))
    stated = power.estimate_power(
        power.DeviceState(profile=profile, storage_readings_per_s=100.0)
    )
    assert stated.by_subsystem["storage"] > derived.by_subsystem["storage"] * 100


def test_a_nonsense_write_rate_costs_nothing_rather_than_raising():
    for rate in (0.0, -1.0, float("nan"), float("inf")):
        state = power.DeviceState(storage_readings_per_s=rate)
        got = power.storage_mean_ma(state)
        assert math.isfinite(got), rate
        if rate == float("inf"):
            # An infinite write rate is not a state; it is arithmetic that got
            # away. It must not silently become an infinite power.
            assert got == 0.0 or math.isfinite(got)
        else:
            assert got == 0.0


def test_recorded_readings_per_s_matches_each_mode():
    assert power.recorded_readings_per_s({"mode": wire.MODE_CONTINUOUS, "interval": 2.0}) == 0.5
    duty = wire.normalize_profile({"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 900.0})
    assert power.recorded_readings_per_s(duty) == pytest.approx(1 / 900.0)


# --- the runtime, and the derating ------------------------------------------
def test_the_runtime_is_energy_over_power_derated_once():
    battery = power.Battery(capacity_mah=2000.0, nominal_v=3.7)
    got = power.estimate_power(power.DeviceState())
    runtime = power.estimate_runtime(got, battery)

    modelled = (2000.0 * 3.7) / got.total_mw
    assert runtime.modelled_hours == pytest.approx(modelled)
    assert runtime.hours == pytest.approx(modelled * power.RUNTIME_DERATING)
    assert runtime.derating == power.RUNTIME_DERATING


def test_the_reported_time_is_always_shorter_than_the_physics():
    """The captain's requirement, stated as the one thing that must never fail."""
    battery = power.Battery(capacity_mah=5000.0)
    for state in (
        power.DeviceState(),
        power.DeviceState(profile={"mode": wire.MODE_DUTY_CYCLE}),
        power.DeviceState(connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF)),
        power.DeviceState(cpu=power.CPU_LIGHT_SLEEP, sensor_present=False),
    ):
        runtime = power.estimate_runtime(power.estimate_power(state), battery)
        assert runtime.hours < runtime.modelled_hours


def test_the_derating_is_a_single_constant_and_it_is_conservative():
    assert 0 < power.RUNTIME_DERATING < 1.0
    # Not a value judgement about 0.70 specifically - a guard on the range in
    # which the constant is doing the job it was created for. Above 0.9 it has
    # stopped being a safety margin; below 0.5 it is hiding a wrong constant.
    assert 0.5 <= power.RUNTIME_DERATING <= 0.9


def test_the_derating_can_be_lifted_only_deliberately():
    """Passing 1.0 gives the physics with no derating, which is how it gets tuned."""
    got = power.estimate_power(power.DeviceState())
    battery = power.Battery(1000.0)
    plain = power.estimate_runtime(got, battery, derating=1.0)
    assert plain.hours == pytest.approx(plain.modelled_hours)
    assert plain.hours > power.estimate_runtime(got, battery).hours


def test_a_nonsense_derating_falls_back_to_the_constant():
    got = power.estimate_power(power.DeviceState())
    battery = power.Battery(1000.0)
    for bad in (0.0, -1.0, float("nan"), float("inf")):
        runtime = power.estimate_runtime(got, battery, derating=bad)
        assert runtime.derating == power.RUNTIME_DERATING
        assert runtime.hours == pytest.approx(
            runtime.modelled_hours * power.RUNTIME_DERATING
        )


# --- not knowing ------------------------------------------------------------
def test_no_battery_at_all_is_unknown_and_says_why():
    got = power.estimate_power(power.DeviceState())
    for battery in (None, power.Battery(), power.Battery(capacity_mah=None)):
        runtime = power.estimate_runtime(got, battery)
        assert runtime.hours is None
        assert runtime.known is False
        assert runtime.reason == power.REASON_NO_CAPACITY
        assert "unknown" in runtime.describe()


@pytest.mark.parametrize(
    "capacity, volts",
    [
        (float("nan"), 3.7),
        (float("inf"), 3.7),
        (-100.0, 3.7),
        (2000.0, 0.0),
        (2000.0, -3.7),
        (2000.0, float("nan")),
    ],
)
def test_an_unusable_capacity_is_unknown_rather_than_a_number(capacity, volts):
    """What a flaky fuel gauge returns, answered with a shrug and not a lie."""
    got = power.estimate_power(power.DeviceState())
    runtime = power.estimate_runtime(got, power.Battery(capacity, volts))
    assert runtime.hours is None
    assert runtime.reason == power.REASON_UNUSABLE_CAPACITY


def test_a_flat_battery_is_zero_hours_and_not_unknown():
    """The one boundary that is a number: empty is known, and it is zero.

    Reporting a flat battery as "unknown" would hide the one state a user most
    needs told, behind the same words used for "there is no gauge fitted".
    """
    got = power.estimate_power(power.DeviceState())
    runtime = power.estimate_runtime(got, power.Battery(0.0))
    assert runtime.known is True
    assert runtime.hours == 0.0
    assert runtime.reason == ""


def test_unknown_and_nearly_flat_never_render_the_same():
    got = power.estimate_power(power.DeviceState())
    unknown = power.estimate_runtime(got, None).describe()
    nearly = power.estimate_runtime(got, power.Battery(1.0)).describe()
    assert unknown != nearly
    assert unknown.startswith("runtime unknown")
    assert not nearly.startswith("runtime unknown")


def test_a_power_of_zero_does_not_become_an_infinite_runtime():
    """No real state does this, which is exactly why it is worth guarding."""
    empty = power.Power(by_subsystem={}, by_draw={}, total_mw=0.0, state=power.DeviceState())
    runtime = power.estimate_runtime(empty, power.Battery(2000.0))
    assert runtime.hours is None
    assert runtime.reason == power.REASON_NO_DRAW


# --- the conservative choices, asserted because they were decisions ----------
def test_a_sensor_that_might_not_sleep_is_assumed_not_to():
    """Sleep needs SPS30 firmware >= 2.0; a caller has to say it has it."""
    assert power.DeviceState().sensor_sleeps_between_cycles is False
    profile = {"mode": wire.MODE_DUTY_CYCLE}
    assumed = power.DeviceState(profile=profile)
    told = power.DeviceState(profile=profile, sensor_sleeps_between_cycles=True)
    assert power.sensor_mean_ma(assumed) > power.sensor_mean_ma(told)


def test_the_warmup_window_is_charged_at_the_fan_peak():
    assert (
        power.DRAWS["sensor_fan_starting"].current_ma
        > power.DRAWS["sensor_measuring"].current_ma
    )
    draws = power.sensor_draw_ma(
        power.DeviceState(profile=wire.normalize_profile({"mode": wire.MODE_DUTY_CYCLE}))
    )
    assert "sensor_fan_starting" in draws


def test_an_unrecognised_radio_state_is_read_as_the_costliest_one():
    """Ignorance shortens the answer. It arrives off a socket, so it happens."""
    junk = power.Connectivity(wifi="probably fine", ble="who knows")
    assert junk.normalized() == power.Connectivity(power.WIFI_STREAMING, power.BLE_CONNECTED)
    assert power.radio_mean_ma(junk.normalized()) == power.radio_mean_ma(
        power.Connectivity(power.WIFI_STREAMING, power.BLE_CONNECTED)
    )


def test_the_default_state_is_the_board_as_it_actually_runs():
    """Continuous, Wi-Fi serving, Bluetooth advertising, dev-board overhead paid."""
    default = power.DeviceState().normalized()
    assert default.profile == wire.normalize_profile(None)
    assert default.connectivity == power.Connectivity(power.WIFI_STREAMING, power.BLE_ADVERTISING)
    assert default.cpu == power.CPU_ACTIVE
    assert default.board_overhead is True


def test_a_profile_straight_off_a_socket_is_clamped_not_trusted():
    """The same defence wire.normalize_profile is, reached through this model.

    A cycle of a thousand days would otherwise average the sensor's draw down to
    nothing and report a runtime of months.
    """
    absurd = power.DeviceState(profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": 1e9})
    assert absurd.normalized().profile["cycle_s"] == wire.CYCLE_MAX
    sane = power.DeviceState(
        profile={"mode": wire.MODE_DUTY_CYCLE, "cycle_s": wire.CYCLE_MAX}
    )
    assert power.estimate_power(absurd).total_mw == pytest.approx(
        power.estimate_power(sane).total_mw
    )


def test_the_measurement_worklist_ranks_by_contribution_not_by_size():
    """The fan self-clean is the biggest number in the table and the least worth
    measuring, because it runs for twenty seconds a week."""
    ranked = power.constants_needing_measurement()
    keys = [key for key, _, _ in ranked]
    assert set(keys) == {
        key for key, draw in power.DRAWS.items() if draw.source is power.Source.ESTIMATE
    }
    contributions = [mw for _, _, mw in ranked]
    assert contributions == sorted(contributions, reverse=True)
    assert keys[0] in ("wifi_streaming", "cpu_active")
    assert keys.index("sensor_cleaning") > keys.index("cpu_active")


def test_the_worklist_answers_about_the_state_it_is_given():
    """A board with its radios off has a different first thing to measure."""
    quiet = power.estimate_power(
        power.DeviceState(connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF))
    )
    ranked = power.constants_needing_measurement(quiet)
    assert ranked[0][0] == "cpu_active"
    assert dict((key, mw) for key, _, mw in ranked)["wifi_streaming"] == 0.0


# --- the whole thing --------------------------------------------------------
def test_estimate_returns_both_halves_and_they_agree():
    state = power.DeviceState(profile={"mode": wire.MODE_DUTY_CYCLE})
    got, runtime = power.estimate(state, power.Battery(3000.0))
    assert runtime.total_mw == got.total_mw
    assert runtime.hours == pytest.approx(
        (3000.0 * 3.7) / got.total_mw * power.RUNTIME_DERATING
    )


def test_the_quietest_state_outlasts_the_busiest_one():
    """An end-to-end sanity check on the direction of every term at once."""
    battery = power.Battery(2000.0)
    busy = power.estimate(power.DeviceState(), battery)[1]
    quiet = power.estimate(
        power.DeviceState(
            profile={"mode": wire.MODE_DUTY_CYCLE},
            connectivity=power.Connectivity(power.WIFI_OFF, power.BLE_OFF),
            cpu=power.CPU_LIGHT_SLEEP,
            sensor_sleeps_between_cycles=True,
            board_overhead=False,
        ),
        battery,
    )[1]
    assert quiet.hours > busy.hours * 10


# --- the wire reply ----------------------------------------------------------
# ``estimate_message`` is the specification of the firmware's answer to
# ``get_power_estimate``, so its composition choices are contracts: what an
# "alternative" holds fixed, whose sleeps are priced, and when an hours figure
# is allowed to exist at all.


def test_the_reply_prices_the_current_state_and_every_offered_mode():
    message = power.estimate_message(power.DeviceState())
    assert message["type"] == wire.MSG_POWER_ESTIMATE
    assert message["basis"] == wire.POWER_ESTIMATE_BASIS_MODELLED

    current = message["current"]
    assert current["total_mw"] == power.estimate_power(power.DeviceState()).total_mw
    assert list(current["by_subsystem"]) == list(power.SUBSYSTEMS)
    assert sum(current["by_subsystem"].values()) == pytest.approx(current["total_mw"])

    # Continuous first, then each duty-cycle period the app's picker offers,
    # smallest first - the switches a user can actually make from the panel.
    alternatives = message["alternatives"]
    assert alternatives[0]["mode"] == wire.MODE_CONTINUOUS
    assert [a["cycle_s"] for a in alternatives[1:]] == list(wire.POWER_COMPARE_CYCLES)
    # Every duty cycle beats continuous, and longer beats shorter: the ordering
    # the panel exists to show.
    totals = [a["total_mw"] for a in alternatives]
    assert totals == sorted(totals, reverse=True)


def test_the_reply_admits_what_the_model_rests_on():
    message = power.estimate_message(power.DeviceState())
    counts = power.sources_summary()
    assert message["sources"] == {
        "datasheet": counts[power.Source.DATASHEET],
        "measured": counts[power.Source.MEASURED],
        "estimate": counts[power.Source.ESTIMATE],
    }
    # As long as nothing is measured, the reply must say so - the app's one
    # honesty sentence is driven by these counts.
    assert message["sources"]["measured"] == 0


def test_no_battery_means_no_hours_anywhere_in_the_reply():
    for battery in (None, power.Battery()):
        message = power.estimate_message(power.DeviceState(), battery)
        assert message["runtime"] == {"status": wire.POWER_RUNTIME_NO_CAPACITY}
        assert all("hours" not in a for a in message["alternatives"])


def test_a_known_capacity_fills_the_runtime_slot_and_every_alternative():
    message = power.estimate_message(power.DeviceState(), power.Battery(2000.0))
    runtime = message["runtime"]
    assert runtime["status"] == wire.POWER_RUNTIME_OK
    assert runtime["hours"] < runtime["modelled_hours"]
    assert runtime["derating"] == power.RUNTIME_DERATING
    for alternative in message["alternatives"]:
        assert alternative["hours"] > 0
    # A cheaper mode promises more hours, which is the decision the panel informs.
    hours = [a["hours"] for a in message["alternatives"]]
    assert hours == sorted(hours)


def test_an_alternative_is_this_board_doing_something_else():
    """Radios and sleeps carry over; a stated write rate does not."""
    sleeper = power.estimate_message(
        power.DeviceState(sensor_sleeps_between_cycles=True)
    )
    idler = power.estimate_message(power.DeviceState())
    for slept, idled in zip(sleeper["alternatives"], idler["alternatives"]):
        if slept["mode"] == wire.MODE_DUTY_CYCLE:
            assert slept["sleeps"] is True and idled["sleeps"] is False
            assert slept["total_mw"] < idled["total_mw"]

    # An import's stated write rate describes now, not the mode being priced:
    # the alternatives must match the ones computed with no stated rate.
    importing = power.estimate_message(power.DeviceState(storage_readings_per_s=100.0))
    assert importing["current"]["total_mw"] > idler["current"]["total_mw"]
    assert importing["alternatives"] == idler["alternatives"]


def test_the_runtime_slot_can_never_claim_ok_without_a_number():
    assert wire.power_runtime_payload(wire.POWER_RUNTIME_OK) == {
        "status": wire.POWER_RUNTIME_NO_CAPACITY
    }
    assert wire.power_runtime_payload(wire.POWER_RUNTIME_OK, float("nan")) == {
        "status": wire.POWER_RUNTIME_NO_CAPACITY
    }
    assert wire.power_runtime_payload("nonsense") == {
        "status": wire.POWER_RUNTIME_NO_CAPACITY
    }
    ok = wire.power_runtime_payload(wire.POWER_RUNTIME_OK, 5.0, 7.1, 0.7)
    assert ok == {"status": "ok", "hours": 5.0, "modelled_hours": 7.1, "derating": 0.7}


def test_the_reply_fits_the_controller_client_buffer():
    """The firmware sends from a 2 KiB per-client buffer (net_server.c's
    CLIENT_OUT_MAX); a reply that outgrew it would be dropped whole, so the
    specification stays inside it with room for the battery-bearing future."""
    for battery in (None, power.Battery(10000.0)):
        line = wire.encode(power.estimate_message(power.DeviceState(), battery))
        assert len(line) < 2048
