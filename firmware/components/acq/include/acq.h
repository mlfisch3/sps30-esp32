/**
 * @file acq.h
 * @brief The acquisition state machine: keep a live SPS30 producing readings.
 *
 * A port of the acquisition half of `sps30_daemon.py`, which has been running
 * this sensor on a Raspberry Pi for months. The states, the transitions and the
 * tolerances are its, not new ones: every constant here has a counterpart there
 * and the ones that look arbitrary were paid for in lost data. Where this file
 * differs, it says so and why.
 *
 * What it does, in one line: detect the sensor, start it measuring, record what
 * it says on a cadence, notice when it stops answering, and get it back.
 *
 * ## The seam
 *
 * Acquisition does not know how readings leave the board. It hands each one to
 * every registered ::acq_observer_t and forgets it. A network transport is
 * therefore an addition rather than a change: it registers an observer that
 * pushes into an ::acq_queue_t and drains that from its own task.
 *
 * That seam has since been used three times over - `components/net` serves the
 * JSONL protocol over Wi-Fi and over BLE, and `components/store` writes the ring
 * buffer - and this file still knows about none of them. Keep it that way: an
 * `#include` of a transport here is the sign the seam has been lost.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "acq_clean.h"
#include "acq_profile.h"
#include "acq_queue.h"
#include "esp_err.h"
#include "sps30.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Where the state machine is.
 *
 * The names are the daemon's `wire.py` vocabulary, so a future transport can
 * report them to the existing Android client without a translation table.
 */
typedef enum {
    ACQ_STATE_STOPPED = 0, /**< the task is not running */
    ACQ_STATE_CHECKING,    /**< no sensor answering; probing for one */
    ACQ_STATE_MEASURING,   /**< recording readings */
    ACQ_STATE_PAUSED,      /**< sensor present, measurement stopped on request */
    ACQ_STATE_CLEANING,    /**< fan self-clean running */
    ACQ_STATE_WARMUP,      /**< duty cycle: fan spinning up, readings discarded */
    ACQ_STATE_SLEEPING,    /**< duty cycle: sensor parked in Sleep-Mode */
} acq_state_t;

/** @brief The state's name, matching `wire.py`'s strings. Never NULL. */
const char *acq_state_str(acq_state_t state);

/* --- acquisition profile -------------------------------------------------- */

/*
 * ::acq_profile_t, ::acq_mode_t, ACQ_DEFAULT_PROFILE() and the bounds every
 * field is held to live in `acq_profile.h`, which is pure C and therefore
 * testable on the host. Included here so a caller that wants the state machine
 * needs only one header.
 */

/**
 * @brief Switch acquisition profile. Takes effect at the next step.
 *
 * Clamped by ::acq_profile_normalize, not rejected, the way
 * `wire.normalize_profile` clamps: a nonsense interval becomes the nearest
 * sensible one rather than dropping the command, because this arrives straight
 * off a socket and taking acquisition down over a malformed field would be the
 * worse failure.
 *
 * Duty cycling needs sensor firmware >= 2.0 for Sleep/Wake. This does **not**
 * check that - the caller holds the sensor identity and can say so in words the
 * user understands, which a returned error code cannot; see ::acq_supports_sleep.
 *
 * Switching profile starts a new session, because a recording must never mix
 * 1 Hz rows with 15-minute averages.
 */
esp_err_t acq_set_profile(const acq_profile_t *profile);

/** @brief Copy out the profile in force. @p out must not be NULL. */
void acq_get_profile(acq_profile_t *out);

/** @brief Whether the attached sensor's firmware offers Sleep/Wake (>= 2.0). */
bool acq_supports_sleep(void);

/**
 * @brief Seconds left in the current timed phase, or -1 when none is running.
 *
 * Warm-up, averaging and sleeping are the only phases with a known end, so this
 * is -1 in every continuous state. The protocol's `seconds` field carries it so
 * the app can show a countdown rather than an apparently frozen sensor - which
 * is what a fifteen-minute sleep looks like without one.
 */
double acq_phase_remaining(void);

/** @brief Called for each recorded reading, from the acquisition task. */
typedef void (*acq_reading_fn)(const acq_reading_t *reading, void *ctx);

/** @brief Called on every state change, from the acquisition task. */
typedef void (*acq_state_fn)(acq_state_t state, const char *detail, void *ctx);

/**
 * @brief Called when the profile in force changes, from the acquisition task.
 *
 * Not the same event as ::acq_set_profile returning. That only queues the
 * change; this fires when the state machine has adopted it, and carries what it
 * adopted after clamping - which is what a client should be told, since what it
 * asked for may not be what the sensor will do. A change that turns out to be a
 * no-op still fires, because the client that asked deserves an answer either way.
 */
typedef void (*acq_profile_fn)(const acq_profile_t *profile, void *ctx);

/**
 * @brief A consumer of acquisition output. Either callback may be NULL.
 *
 * Callbacks run on the acquisition task and must not block: a slow observer
 * delays the next read and shows up as a gap in the data. The intended shape
 * for anything that might block is to copy into an ::acq_queue_t and return.
 */
typedef struct {
    acq_reading_fn on_reading;
    acq_state_fn on_state;
    acq_profile_fn on_profile;
    void *ctx;
} acq_observer_t;

/** How many observers may register. One console logger, room for transports. */
#define ACQ_MAX_OBSERVERS 4

/** @brief Runtime tuning. ::ACQ_DEFAULT_CONFIG is the daemon's settings. */
typedef struct {
    uint32_t interval_ms;      /**< between recorded readings while measuring */
    uint32_t poll_interval_ms; /**< between probes while checking for a sensor */
    uint32_t settle_ms;        /**< flag readings for this long after a start */
    uint32_t read_failures_before_loss; /**< silent reads tolerated before giving up */
    uint32_t identify_attempts; /**< probes per detection round before backing off */
    uint32_t task_stack_bytes;
    int task_priority;
} acq_config_t;

/*
 * The daemon's tunables, unchanged except where the units differ:
 *
 *   SAMPLE_PERIOD            1.0 s  -> interval_ms 1000. The SPS30 publishes
 *                                     exactly one new value per second; asking
 *                                     faster only returns empty payloads.
 *   DEFAULT_POLL_INTERVAL    2.0 s  -> poll_interval_ms 2000
 *   SETTLE_SECONDS          20.0 s  -> settle_ms 20000
 *   READ_FAILURES_BEFORE_LOSS   5   -> unchanged. At 1 Hz that is a few seconds
 *                                     of silence: long enough to ride out a
 *                                     glitch or a brief brown-out, short enough
 *                                     that an unplugged sensor is noticed.
 */
#define ACQ_DEFAULT_CONFIG()                                                   \
    ((acq_config_t){                                                           \
        .interval_ms = 1000,                                                   \
        .poll_interval_ms = 2000,                                              \
        .settle_ms = 20000,                                                    \
        .read_failures_before_loss = 5,                                        \
        .identify_attempts = 3,                                                \
        .task_stack_bytes = 4096,                                              \
        .task_priority = 5,                                                    \
    })

/** @brief Counters for judging whether a run was healthy. */
typedef struct {
    uint32_t readings;       /**< readings recorded and published */
    uint32_t not_ready;      /**< empty payloads: normal, one per poll that beat the sensor */
    uint32_t read_failures;  /**< failed reads, cumulative across recoveries */
    uint32_t implausible;    /**< frames that decoded but held impossible values */
    uint32_t sensor_losses;  /**< times the sensor stopped answering entirely */
    uint32_t silent_faults;  /**< failures where not one byte came back */
    uint32_t garbled_faults; /**< failures where bytes came back and would not decode */
    uint32_t session;        /**< current session number; a gap does not start a new one */
    uint32_t sequence;       /**< readings recorded in this session */
} acq_stats_t;

/**
 * @brief Identity of the attached sensor, empty until one answers.
 */
typedef struct {
    char product[SPS30_STRING_LEN];
    char serial[SPS30_STRING_LEN];
    uint8_t firmware_major;
    uint8_t firmware_minor;
    bool valid;
} acq_sensor_info_t;

/**
 * @brief Register an observer. Call before ::acq_start.
 *
 * Registration is deliberately not thread-safe and not removable: observers are
 * wired up once at boot, and a dynamic registry would need a lock on the hot
 * path to buy something nothing needs.
 */
esp_err_t acq_add_observer(const acq_observer_t *observer);

/**
 * @brief Initialise the sensor transport and start the acquisition task.
 *
 * @param config NULL for ::ACQ_DEFAULT_CONFIG.
 */
esp_err_t acq_start(const acq_config_t *config);

/**
 * @brief Stop measuring, park the sensor and end the task.
 *
 * Blocks until the task has run its shutdown: the sensor is told to stop, so it
 * is left idle rather than spinning its fan into a firmware that is no longer
 * listening. Safe to call when already stopped.
 */
esp_err_t acq_stop(void);

/** @brief Stop recording but keep the sensor detected. Idempotent. */
esp_err_t acq_pause(void);

/** @brief Resume recording after ::acq_pause. Starts a new session. */
esp_err_t acq_resume(void);

/**
 * @brief Run the fan self-clean, then resume measuring.
 *
 * Takes about ten seconds, during which no readings are recorded. Readings
 * afterwards are flagged as settling, because a clean disturbs them exactly as
 * a fresh start does.
 *
 * Also clears the fan-clean account, because the fan really has just been
 * cleaned; see ::acq_clean_seconds_since_clean.
 */
esp_err_t acq_clean(void);

/**
 * @brief The longest raw frame ::acq_raw_transaction will send or return.
 *
 * The same number as `WIRE_RAW_MAX_BYTES` and for the same reason, but stated
 * here too so `components/acq` keeps knowing nothing about the transport.
 */
#define ACQ_RAW_MAX_BYTES 128

/**
 * @brief Run one raw SHDLC exchange on the acquisition task and wait for it.
 *
 * The daemon's `_raw_transaction`, with the ownership made explicit. There it
 * is a method the acquisition thread calls on itself; here the caller is a
 * transport task, so the request is handed to the acquisition task and the
 * caller blocks - because the sensor has exactly one owner and a raw frame
 * written from a socket task would interleave with a measurement read.
 *
 * Calls are serialised: a second raw command waits for the first. Blocking is
 * bounded, so a wedged sensor costs the client an error rather than its
 * connection.
 *
 * @param[out] response_len bytes written to @p response.
 * @retval ESP_ERR_INVALID_STATE no sensor is answering, or acquisition is not
 *         running - the daemon's "no sensor for raw".
 * @retval ESP_ERR_NOT_ALLOWED   the sensor is parked in Sleep-Mode, where its
 *         serial receiver is off and every frame would time out.
 * @retval ESP_ERR_TIMEOUT       the exchange did not complete in time.
 */
esp_err_t acq_raw_transaction(const uint8_t *request, size_t request_len,
                              uint8_t *response, size_t response_cap,
                              size_t *response_len);

/**
 * @brief Seconds of measurement since the last fan clean, across reboots.
 *
 * The controller's own account, kept because the sensor's resets on every
 * power-down - the reasoning is in acq_clean.h. Held against
 * ::ACQ_CLEAN_AFTER_SECONDS, this is what the protocol reports as
 * `clean_due_in_hours`, and it is the only warning a user gets: a fan that
 * needs cleaning reads low rather than stopping.
 */
double acq_clean_seconds_since_clean(void);

/** @brief Where the state machine is right now. */
acq_state_t acq_state(void);

/** @brief Copy out the counters. @p out must not be NULL. */
void acq_get_stats(acq_stats_t *out);

/** @brief Copy out the attached sensor's identity. @p out must not be NULL. */
void acq_get_sensor_info(acq_sensor_info_t *out);

#ifdef __cplusplus
}
#endif
