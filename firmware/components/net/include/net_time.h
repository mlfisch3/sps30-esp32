/**
 * @file net_time.h
 * @brief Wall-clock time over SNTP, and the mapping from uptime onto it.
 *
 * ## Why this is not optional
 *
 * `acq_reading_t` timestamps readings with monotonic uptime, correctly: there is
 * no RTC on this board and nothing else on it knows the date. The protocol,
 * however, carries an ISO 8601 instant, and the Android client parses it with
 * `OffsetDateTime.parse` and **discards any reading whose timestamp will not
 * parse**. So a controller with no clock can stream perfectly good readings that
 * the app silently drops. SNTP is what closes that gap, and it is the reason
 * this file exists rather than a `strftime` call in the server.
 *
 * ## The mapping
 *
 * A reading is queued when it is taken and serialised when a client is ready for
 * it, so its wall-clock instant has to be reconstructed from its uptime rather
 * than read off the clock at send time. ::net_time_epoch_us_for_uptime does
 * that, from the offset between the two clocks recorded at each sync. It works
 * **backwards** as well: a reading taken before the first sync gets its correct
 * instant once the sync lands, because the arithmetic only needs the offset to
 * be known now, not to have been known then.
 *
 * ## Why the clock is not simply believed
 *
 * Control of the clock is control of the record. Every reading is stamped from
 * here; session names are derived from it, and so are the page start times the
 * ring is ordered by, so a chosen time changes the *shape* of the record and
 * not merely its labels. And the clock is the softest thing on the board:
 * SNTP is unauthenticated UDP, trivially spoofable off-path, and
 * `CONFIG_LWIP_DHCP_GET_NTP_SRV` means a rogue DHCP server chooses the server
 * in the first place. Transport encryption would not touch any of that.
 *
 * So a time from the network is checked before it is kept, on two rules:
 *
 * 1. **Never backwards past what has already been recorded.** The newest
 *    accepted instant is written to NVS, so this survives the power cycle an
 *    attacker would otherwise use to clear it. This is the rule that matters:
 *    re-dating the record needs the clock to go back.
 * 2. **Once set, no step larger than ::NET_TIME_MAX_STEP_S.** A resync corrects
 *    milliseconds; anything near the bound is not a correction.
 *
 * A refused time is logged and the system clock is put back. Crucially, a board
 * that has never had a clock stays *invalid* after a refusal rather than
 * adopting the stored mark: readings are held back, which the app shows, and a
 * held-back reading is a loud failure where a wrongly dated one is a silent
 * one. ::net_time_refusals counts them.
 *
 * **Both rules apply to ::net_time_set as well.** A time handed over by a
 * connected client is not inherently better than one from a server - it is
 * unauthenticated in the same way - and a clock accepted through a second door
 * that skipped these checks would be a hole straight through them.
 *
 * ## What a board with no clock does instead
 *
 * Nothing above helps a controller that has never joined a network: there is no
 * server to distrust and no time to check. Such a board records against its own
 * clock and says so, and a time arriving later is written as an *equivalence*
 * rather than used to relabel anything - see `store.h`. So the failure this file
 * guards against and the one relative time guards against are different: this
 * one is a wrong time believed, that one is no time at all.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * `wire.py` records in `America/New_York` ("EST" per the spec, handling both
 * EST and EDT). This is that zone as a POSIX TZ string, which is what newlib
 * understands: EST is UTC-5, EDT is UTC-4, and the changeovers are the second
 * Sunday in March and the first Sunday in November, at 02:00 local.
 */
#define NET_TIME_TZ_EASTERN "EST5EDT,M3.2.0/2,M11.1.0/2"

/**
 * @brief The largest step accepted once the clock has been set at least once.
 *
 * Four hours, which is enormous next to anything real: a resync corrects tens
 * of milliseconds. It is not tighter because `esp_timer` runs off a crystal
 * with a few tens of ppm of error, so a board that stays up for a year drifts
 * by minutes against it and a tight bound would eventually start refusing
 * honest servers. Daylight saving is not a factor - the zone is applied on the
 * way out, and the epoch this bounds does not move at a changeover.
 */
#define NET_TIME_MAX_STEP_S (4 * 3600)

/**
 * @brief How far behind the stored mark a time may still be and be believed.
 *
 * The mark is written at most once an hour and is a whole number of seconds, so
 * a legitimate server can be a little behind it without anything being wrong.
 * Small enough that it buys an attacker nothing.
 */
#define NET_TIME_BACKWARD_SLACK_S 120

/**
 * @brief How much accepted time must pass before the mark is rewritten.
 *
 * A flash write per sync would be wear for nothing: the mark only has to be
 * good enough that a reboot cannot rewind the board past what it has already
 * recorded, and an hour is well inside that.
 */
#define NET_TIME_PERSIST_INTERVAL_S 3600

/**
 * @brief Set the local zone, without starting anything.
 *
 * Separate from ::net_time_start because a board may never start SNTP - it may
 * have no credentials, or be a build that deliberately does not ask the network
 * for the time - and still learn the time later from a connected client. The
 * zone has to be in place before that happens, or the first absolute session
 * such a board records is stamped in UTC while claiming to be local.
 */
void net_time_set_zone(const char *posix_tz);

/**
 * @brief Set the zone and start SNTP. Safe to call before Wi-Fi is up.
 *
 * SNTP polls until it gets an answer, so calling this at boot rather than on the
 * first connection means a reconnect after an outage needs no prompting.
 *
 * @param keep_syncing whether to go on resyncing after the first answer. False
 *        stops SNTP the moment the clock is set, leaving it free-running on the
 *        board's own crystal. That is not a normal operating mode: it exists so
 *        the crystal's drift can be **measured**, which a resyncing clock
 *        silently corrects away. See `firmware/tools/drift_probe.py`.
 */
void net_time_start(const char *posix_tz, bool keep_syncing);

/** @brief True once the clock has been set from the network at least once. */
bool net_time_valid(void);

/** @brief Unix microseconds for a reading stamped @p uptime_us, or 0 if unknown. */
int64_t net_time_epoch_us_for_uptime(int64_t uptime_us);

/**
 * @brief The configured zone's UTC offset in **seconds** at @p epoch_s.
 *
 * Computed by comparing local and UTC calendar fields rather than reading
 * `tm_gmtoff`, so it does not depend on which newlib extensions this toolchain
 * happens to expose.
 *
 * Seconds is what the formatters in `wire.h` take - ::wire_iso_timestamp and
 * ::wire_csv_name both want an offset in seconds. Anything that is going to be
 * *recorded* wants ::net_time_utc_offset_min instead; see there for why the
 * unit is in both names.
 */
int net_time_utc_offset_s(int64_t epoch_s);

/**
 * @brief The configured zone's UTC offset in **minutes** at @p epoch_s.
 *
 * Minutes is the unit every field that stores an offset carries: the page
 * header's `utc_offset_min` (`store.py`), the `time_map` anchor (`wire.py`),
 * and the app's `utcOffsetMinutes`. Nothing on the wire or on flash holds an
 * offset in seconds.
 *
 * Both functions spell their unit out because leaving it implicit cost real
 * data: the SNTP path passed the seconds one into the minutes field and the
 * board recorded -14400 where the zone was -240, sixty times too large and
 * still small enough to fit the int16 it was written into. `test_net_time.c`
 * holds both of these to the same instants `wire.py` resolves with `zoneinfo`,
 * which is where a factor of sixty now shows up immediately.
 */
int net_time_utc_offset_min(int64_t epoch_s);

/** @brief How many times the clock has been set from the network. */
uint32_t net_time_syncs(void);

/**
 * @brief How many times a time from the network was refused as implausible.
 *
 * Nonzero on a healthy network means something is wrong with a server, not with
 * this board. Sustained and nonzero means somebody is trying to move the
 * record, and it is worth the heartbeat line it costs to be able to see that.
 */
uint32_t net_time_refusals(void);

/**
 * @brief Set the clock from a client that has one. `wire.CMD_SET_TIME`.
 *
 * The other way a board learns the time, and the only way a board that has never
 * joined Wi-Fi can: a phone connected over Bluetooth knows what time it is and
 * can simply say so. Nothing about the recording changes when this arrives - a
 * session already recording relative stays relative, and what is written is an
 * *equivalence*, appended - so accepting it cannot alter a reading already
 * taken.
 *
 * Checked by the same two rules as a time from the network, plus a floor of
 * 2020: a phone whose own clock is unset reports 1970, and the backward rule
 * cannot catch that on a board that has no mark yet, which is precisely the
 * board this exists for.
 *
 * @param utc_offset_min the client's zone, used for naming and display only.
 * @return false if the time was refused.
 */
bool net_time_set(int64_t epoch_ms, int utc_offset_min);

/**
 * @brief Called whenever the clock is accepted, from the network or a client.
 *
 * @param uptime_us the monotonic clock at the moment the time was learned, which
 *        is the half of the equivalence only this layer can see.
 */
typedef void (*net_time_observer_t)(int64_t uptime_us, int64_t epoch_ms, int utc_offset_min);

/** @brief Register the one observer. Call before ::net_time_start. */
void net_time_observe(net_time_observer_t observer);

#ifdef __cplusplus
}
#endif
