/**
 * @file acq.c
 * @brief The acquisition state machine - see acq.h.
 *
 * Ported from `sps30_daemon.py`'s acquisition thread. Its structure is kept
 * deliberately recognisable - a `step()` that drains commands and then switches
 * on the state - so the two can be read side by side.
 */

#include "acq.h"

#include <inttypes.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nvs.h"
#include "nvs_flash.h"

static const char *TAG = "acq";

/* The fan runs at maximum for about ten seconds; allow slack, as the daemon's
 * CLEAN_DURATION_SECONDS = 12.0 does. */
#define CLEAN_DURATION_MS 12000

/* Where the fan-clean account lives across restarts. The daemon writes a JSON
 * file next to its CSVs; NVS is this board's equivalent, and is deliberately not
 * the `store` partition - that is a ring of readings which reuses its oldest
 * pages, so a counter kept there would be erased by the data it is counting.
 *
 * The write budget, since it is the obvious objection: continuous measurement
 * writes once per ::ACQ_CLEAN_PERSIST_SECONDS, so 1680 times a week. The `nvs`
 * partition is 24 KB, six pages of 126 entries, so that is roughly 700 page
 * erases a year spread over six pages - about 120 cycles each against a part
 * rated for 100,000. */
#define CLEAN_NVS_NAMESPACE "sps30"
#define CLEAN_NVS_KEY "fan_run_s"

/* Commands from other tasks. The daemon has the same indirection for the same
 * reason: the sensor has exactly one owner, and letting another task issue a
 * command directly would interleave two exchanges on one UART. */
typedef enum {
    CMD_PAUSE,
    CMD_RESUME,
    CMD_CLEAN,
    CMD_SHUTDOWN,
    CMD_SET_PROFILE,
    CMD_RAW,
} acq_cmd_kind_t;

/*
 * The profile travels *in* the queue rather than in a shared variable the task
 * reads when it gets round to it. Two changes in quick succession - a mode chip
 * and then an interval chip, which is one tap each in the app's Options sheet -
 * would otherwise both land in that variable before the task drained either, and
 * the first command would apply the second command's profile.
 */
typedef struct {
    acq_cmd_kind_t kind;
    acq_profile_t profile; /**< CMD_SET_PROFILE only */
    uint32_t raw_seq;      /**< CMD_RAW only; see ::s_raw */
} acq_cmd_t;

#define COMMAND_QUEUE_DEPTH 4

/**
 * The one in-flight raw exchange.
 *
 * A raw command is the only one that answers, so it is the only one whose
 * caller has to wait. The buffers are file-static rather than the caller's,
 * because a transport task that gave up waiting would otherwise leave the
 * acquisition task holding a pointer into a stack frame that no longer exists.
 *
 * ::seq is what makes the abandonment safe. A caller that times out releases
 * the mutex, and the next caller may fill this struct in before the queued
 * command from the first one is ever drained. Both the struct and the queued
 * command carry the sequence number, so a stale command finds a mismatch,
 * discards itself and does not hand one client's answer to another.
 */
typedef struct {
    uint32_t seq;
    size_t request_len;
    size_t response_len;
    esp_err_t result;
    uint8_t request[ACQ_RAW_MAX_BYTES];
    uint8_t response[ACQ_RAW_MAX_BYTES];
} acq_raw_job_t;

/**
 * How long a caller waits for its raw exchange.
 *
 * Commands are drained at the top of every `step()`, and a step is one sensor
 * exchange, so the wait is one queue's worth of steps plus a UART timeout. Five
 * seconds is several times that, which makes a timeout here mean the sensor is
 * wedged rather than that the board was briefly busy.
 */
#define RAW_WAIT_MS 5000

static acq_config_t s_config;
static acq_state_t s_state = ACQ_STATE_STOPPED;
static TaskHandle_t s_task = NULL;
static QueueHandle_t s_commands = NULL;
static volatile bool s_shutdown = false;
static SemaphoreHandle_t s_done = NULL;

static acq_raw_job_t s_raw;
static SemaphoreHandle_t s_raw_mutex = NULL; /**< one raw caller at a time */
static SemaphoreHandle_t s_raw_done = NULL;  /**< the task signalling completion */

static acq_observer_t s_observers[ACQ_MAX_OBSERVERS];
static size_t s_observer_count = 0;

static acq_stats_t s_stats;
static acq_sensor_info_t s_info;

/* Session bookkeeping. `s_recovering` is the daemon's flag of the same name and
 * carries the decision that a dropout does not start a new session. */
static uint32_t s_session = 0;
static uint32_t s_sequence = 0;
static uint32_t s_read_failures = 0;
static uint32_t s_settle_remaining = 0;
static bool s_recovering = false;
static bool s_sensor_present = false;

/* The profile in force, and the daemon's `_profile_gen`. A duty cycle spans
 * minutes, so a profile change almost always lands in the middle of one; the
 * counter is how the cycle in flight discovers it is stale and stands down
 * instead of finishing into a regime that no longer exists. */
static acq_profile_t s_profile = ACQ_DEFAULT_PROFILE();
static uint32_t s_profile_gen = 0;
static bool s_asleep = false;

/* When the phase now running ends, in esp_timer microseconds; 0 when no timed
 * phase is running. Only warm-up, averaging and sleeping have a known end. */
static int64_t s_phase_end_us = 0;

/* Measured seconds since the last fan clean, and the daemon's `_run_seconds`.
 * See acq_clean.h for why the sensor's own counter cannot do this job.
 *
 * `s_clean` belongs to the acquisition task. `s_clean_published` is the copy
 * other tasks read, and it is a word rather than the double because a double is
 * two stores on this part: a client task reading between them would get a
 * number that was never true, and the one such number that matters is the one
 * that reports a clean is due when it is not. */
static acq_clean_t s_clean;
static volatile uint32_t s_clean_published = 0;

/*
 * The rest of what other tasks read, and the only thing that makes those reads
 * whole.
 *
 * `s_clean_published` above solves its problem by being a single word, which is
 * a single store on this part. The other three cross-task reads cannot use that
 * trick and were left with nothing:
 *
 *   - `s_profile` is 40 bytes. A reader could be handed a new `mode` with the
 *     old `interval_s` - a profile that was never in force.
 *   - `s_info` is 72 bytes including two strings, written whole at detection and
 *     cleared field-wise when the sensor goes. A torn read shows a garbled
 *     serial number.
 *   - `s_phase_end_us` is an `int64_t` on a 32-bit part, so it is two loads. The
 *     transition through zero at ::begin_phase and ::end_phase is the case that
 *     bites: a new low word with the old high word reports the countdown as
 *     finished for one sample.
 *
 * A spinlock rather than a mutex because it is statically initialised - these
 * accessors are reachable from a transport task before ::acq_start has run, and
 * a mutex would have to be created somewhere and NULL-checked everywhere. The
 * critical sections are a struct copy each, tens of instructions, and none of
 * them is on the acquisition task's hot path.
 *
 * The acquisition task owns all three and reads its own copies unlocked
 * throughout, exactly as it does `s_clean`. Only the writes are guarded, which
 * is all a single-writer/many-reader value needs.
 */
static portMUX_TYPE s_published_mux = portMUX_INITIALIZER_UNLOCKED;

const char *acq_state_str(acq_state_t state)
{
    switch (state) {
    case ACQ_STATE_STOPPED: return "stopped";
    case ACQ_STATE_CHECKING: return "checking";
    case ACQ_STATE_MEASURING: return "measuring";
    case ACQ_STATE_PAUSED: return "paused";
    case ACQ_STATE_CLEANING: return "cleaning";
    case ACQ_STATE_WARMUP: return "warmup";
    case ACQ_STATE_SLEEPING: return "sleeping";
    default: return "unknown";
    }
}

void acq_get_profile(acq_profile_t *out)
{
    if (out == NULL) {
        return;
    }
    taskENTER_CRITICAL(&s_published_mux);
    *out = s_profile;
    taskEXIT_CRITICAL(&s_published_mux);
}

bool acq_supports_sleep(void)
{
    taskENTER_CRITICAL(&s_published_mux);
    const bool supported = s_info.valid && s_info.firmware_major >= 2;
    taskEXIT_CRITICAL(&s_published_mux);
    return supported;
}

double acq_phase_remaining(void)
{
    taskENTER_CRITICAL(&s_published_mux);
    const int64_t end_us = s_phase_end_us;
    taskEXIT_CRITICAL(&s_published_mux);
    if (end_us == 0) {
        return -1.0;
    }
    const int64_t left = end_us - esp_timer_get_time();
    return left > 0 ? (double)left / 1000000.0 : 0.0;
}

/** Arm the countdown ::acq_phase_remaining reports. */
static void begin_phase(double seconds)
{
    const int64_t end_us = esp_timer_get_time() + (int64_t)(seconds * 1000000.0);
    taskENTER_CRITICAL(&s_published_mux);
    s_phase_end_us = end_us;
    taskEXIT_CRITICAL(&s_published_mux);
}

/** Disarm it, so the protocol's `seconds` goes back to "no phase running". */
static void end_phase(void)
{
    taskENTER_CRITICAL(&s_published_mux);
    s_phase_end_us = 0;
    taskEXIT_CRITICAL(&s_published_mux);
}

static bool duty_cycling(void)
{
    return s_profile.mode == ACQ_MODE_DUTY_CYCLE;
}

/** The continuous read period in milliseconds, from the profile. */
static uint32_t interval_ms(void)
{
    const double s = s_profile.interval_s;
    /* Clamped rather than trusted. This value came off a socket, and a zero
     * would turn the measure loop into a spin that starves every other task. */
    if (!(s > 0.001) || s > 3600.0) {
        return 1000;
    }
    return (uint32_t)(s * 1000.0);
}

acq_state_t acq_state(void)
{
    return s_state;
}

void acq_get_stats(acq_stats_t *out)
{
    if (out == NULL) {
        return;
    }
    *out = s_stats;
    out->session = s_session;
    out->sequence = s_sequence;
}

void acq_get_sensor_info(acq_sensor_info_t *out)
{
    if (out == NULL) {
        return;
    }
    taskENTER_CRITICAL(&s_published_mux);
    *out = s_info;
    taskEXIT_CRITICAL(&s_published_mux);
}

esp_err_t acq_add_observer(const acq_observer_t *observer)
{
    if (observer == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_observer_count >= ACQ_MAX_OBSERVERS) {
        return ESP_ERR_NO_MEM;
    }
    s_observers[s_observer_count++] = *observer;
    return ESP_OK;
}

static void publish_reading(const acq_reading_t *reading)
{
    for (size_t i = 0; i < s_observer_count; i++) {
        if (s_observers[i].on_reading != NULL) {
            s_observers[i].on_reading(reading, s_observers[i].ctx);
        }
    }
}

static void set_state(acq_state_t state, const char *detail)
{
    s_state = state;
    ESP_LOGI(TAG, "state -> %s (%s)", acq_state_str(state), detail);
    for (size_t i = 0; i < s_observer_count; i++) {
        if (s_observers[i].on_state != NULL) {
            s_observers[i].on_state(state, detail, s_observers[i].ctx);
        }
    }
}

/**
 * Sleep, but wake early if a command arrives.
 *
 * The daemon's `_interruptible_sleep` exists so that a pause does not wait out
 * a whole interval before taking effect. Peeking the queue is how that is done
 * here without a second synchronisation primitive.
 */
static void interruptible_delay(uint32_t ms)
{
    const TickType_t deadline = xTaskGetTickCount() + pdMS_TO_TICKS(ms);
    while (!s_shutdown && xTaskGetTickCount() < deadline) {
        if (uxQueueMessagesWaiting(s_commands) > 0) {
            return;
        }
        TickType_t left = deadline - xTaskGetTickCount();
        vTaskDelay(left > pdMS_TO_TICKS(50) ? pdMS_TO_TICKS(50) : left);
    }
}

/** Record which kind of link fault the last exchange hit, for the report. */
static void account_link_fault(void)
{
    switch (sps30_last_link_fault()) {
    case SPS30_LINK_SILENT:
        s_stats.silent_faults++;
        break;
    case SPS30_LINK_GARBLED:
        s_stats.garbled_faults++;
        break;
    default:
        break;
    }
}

/** The troubleshooting order, printed with the evidence that selected it. */
static void log_link_diagnosis(const char *what)
{
    const sps30_link_fault_t fault = sps30_last_link_fault();
    ESP_LOGW(TAG, "%s: %zu bytes received - %s", what, sps30_last_rx_count(),
             sps30_link_fault_str(fault));
    if (fault == SPS30_LINK_SILENT) {
        ESP_LOGW(TAG, "  1. SEL (pin 4) FLOATING selects UART; grounded selects I2C,");
        ESP_LOGW(TAG, "     and that is latched at power-up - power-cycle, do not reset.");
        ESP_LOGW(TAG, "  2. TX and RX cross exactly once: sensor RX to GPIO%d, sensor TX",
                 SPS30_DEFAULT_TX_GPIO);
        ESP_LOGW(TAG, "     to GPIO%d.", SPS30_DEFAULT_RX_GPIO);
        ESP_LOGW(TAG, "  3. VDD needs 5 V from a supply good for at least 1 A.");
    } else if (fault == SPS30_LINK_GARBLED) {
        ESP_LOGW(TAG, "  the line is alive, so this is not wiring: check that both ends");
        ESP_LOGW(TAG, "  agree on %d 8N1 and that the levels are 3.3 V.", SPS30_BAUD_RATE);
    }
}

/* --- measuring ------------------------------------------------------------ */

/**
 * Flag the next few readings as an unreliable settling spike.
 *
 * After a start or a fan clean the SPS30's reading spikes and takes seconds to
 * settle. The daemon flags rather than discards, and that choice is load-bearing
 * in both directions: a consumer that wants clean data can filter on the flag,
 * and nothing is silently thrown away by a layer that cannot know what the data
 * is for.
 *
 * **Duty cycling never flags**, and that is not an optimisation. Every point it
 * records is already the average of readings taken after a full warm-up, which
 * is exactly what makes it trustworthy. Flagging by row count would also be
 * badly wrong there: one row per cycle means `settle_ms` worth of rows is
 * `settle_ms / interval` *cycles*, and at the default fifteen-minute cycle that
 * is five hours of real data hidden behind the client's exclude-flagged filter.
 * That is `PRD.md` C-12, and it is listed there because it already happened.
 */
static void flag_settle(void)
{
    if (duty_cycling()) {
        s_settle_remaining = 0;
        return;
    }
    const uint32_t interval = interval_ms();
    s_settle_remaining = (s_config.settle_ms + interval - 1) / interval;
    if (s_settle_remaining == 0) {
        s_settle_remaining = 1;
    }
}

static void handle_sensor_loss(const char *detail)
{
    ESP_LOGW(TAG, "sensor lost: %s", detail);
    log_link_diagnosis("sensor stopped answering");
    s_stats.sensor_losses++;

    /* Best effort only - if it is not answering, this will not answer either.
     * It matters when the sensor is alive but the session is being torn down
     * for another reason, where leaving the fan running would be wrong. */
    (void)sps30_stop_measurement();

    s_sensor_present = false;
    taskENTER_CRITICAL(&s_published_mux);
    s_info.valid = false;
    taskEXIT_CRITICAL(&s_published_mux);
    /* Whatever the sensor was, it is not asleep on our say-so any more, and the
     * detection path wakes anything that is. Leaving this true would skip that
     * wake on the next cycle and re-lose a sensor that had come back. */
    s_asleep = false;
    end_phase();
    /* The session deliberately survives. A dropout is a gap in one recording,
     * not the end of it; the daemon learned that starting a new session on
     * every recovery is what made days of history appear to begin at the last
     * glitch. */
    s_recovering = true;
    s_read_failures = 0;
    set_state(ACQ_STATE_CHECKING, detail);
}

static void begin_measuring(const char *detail, bool new_session)
{
    /* The sensor may already be measuring - after a reset while running, or on
     * the first cycle after a resume - in which case Start returns 0x43
     * "command not allowed in current state". Stop first to reach a known idle
     * state and ignore the error when it was already idle. */
    (void)sps30_stop_measurement();

    esp_err_t err = sps30_start_measurement();
    if (err != ESP_OK) {
        handle_sensor_loss("start_measurement failed");
        return;
    }

    if (new_session) {
        s_session++;
        s_sequence = 0;
    }
    flag_settle();
    s_read_failures = 0;
    end_phase();
    set_state(ACQ_STATE_MEASURING, detail);
}

/**
 * Hand one finished value to the observers as this session's next reading.
 *
 * Shared by the two producers, which differ only in where the numbers came
 * from: continuous publishes what the sensor just said, duty cycling publishes
 * the average of a warmed-up run. Sequence, session and the settle flag are the
 * session's business either way, which is why they live here and not in either
 * caller.
 */
static void publish_measurement(const sps30_measurement_t *m)
{
    acq_reading_t reading = {
        .uptime_us = esp_timer_get_time(),
        .values = *m,
        .sequence = ++s_sequence,
        .session = (uint16_t)s_session,
        .flags = 0,
    };
    if (s_settle_remaining > 0) {
        reading.flags |= ACQ_FLAG_SETTLING;
        s_settle_remaining--;
    }

    s_stats.readings++;
    publish_reading(&reading);
}

static void measure_once(void)
{
    sps30_measurement_t m;
    bool ready = false;

    esp_err_t err = sps30_read_measurement(&m, &ready);
    if (err != ESP_OK) {
        /* One failed read is not a missing sensor. A marginal supply or a
         * momentary line glitch drops the odd response, and tearing the session
         * down for each one restarts the index and makes a consumer's history
         * appear to begin at the last hiccup. Only give up after several. */
        s_stats.read_failures++;
        s_read_failures++;
        account_link_fault();
        if (s_read_failures < s_config.read_failures_before_loss) {
            ESP_LOGD(TAG, "read failed (%" PRIu32 "/%" PRIu32 "), retrying",
                     s_read_failures, s_config.read_failures_before_loss);
            return;
        }
        handle_sensor_loss("read failed repeatedly");
        return;
    }

    s_read_failures = 0;
    if (!ready) {
        s_stats.not_ready++;
        return; /* no new value this tick - normal, keep going */
    }

    if (!sps30_measurement_plausible(&m)) {
        /* Framing and checksum passed, so the sensor really did send this. That
         * makes it a data-quality problem rather than a link problem, and
         * deliberately not grounds for tearing the session down: dropping the
         * value keeps it out of the record while the session survives. */
        s_stats.implausible++;
        ESP_LOGW(TAG, "implausible reading discarded: pm2_5=%.2f nc0_5=%.2f size=%.2f",
                 m.pm2_5, m.nc0_5, m.typical_size);
        return;
    }

    publish_measurement(&m);
}

/* --- duty cycling (Sensirion's low-power recommendation) ------------------- */

/*
 * The engine below is a port of the daemon's `_duty_cycle_once` and its
 * helpers. It reads top to bottom as the app note's pseudo-code does: wake,
 * start, discard the warm-up, average, park, sleep out the rest of the cycle.
 *
 * The one thing that is not in the pseudo-code, and is the whole reason this is
 * harder than it looks, is that a cycle takes minutes. A pause, a profile
 * change or a shutdown will land in the middle of one, so every wait is
 * interruptible and every resumption re-checks whether this cycle is still the
 * one the user wants. `s_profile_gen` is what makes that check possible.
 */

static void drain_commands(void);

/**
 * Wait @p seconds, then say whether this cycle should still carry on.
 *
 * A command can land mid-cycle, so the caller has to re-ask rather than assume
 * the world it left is the world it came back to.
 */
static bool sleep_checked(double seconds, uint32_t generation)
{
    if (seconds > 0) {
        interruptible_delay((uint32_t)(seconds * 1000.0));
    }
    drain_commands();
    return !s_shutdown && s_sensor_present && s_profile_gen == generation &&
           (s_state == ACQ_STATE_MEASURING || s_state == ACQ_STATE_WARMUP ||
            s_state == ACQ_STATE_SLEEPING);
}

/** Stop measuring and drop into Sleep-Mode (under 50 uA) until the next cycle. */
static void park_sensor(void)
{
    if (!s_sensor_present) {
        return;
    }
    (void)sps30_stop_measurement();
    if (sps30_sleep() != ESP_OK) {
        /* Not fatal: the next cycle starts by waking, which a sensor that is
         * already awake ignores. Worst case is that this cycle burns idle
         * power instead of sleep power, which is a bill, not a bug. */
        ESP_LOGW(TAG, "could not park sensor for sleep");
        s_asleep = false;
        return;
    }
    s_asleep = true;
}

/**
 * Give up on the cycle in flight.
 *
 * Only park the sensor if we are *still* duty cycling. If the profile has just
 * switched to continuous then `begin_measuring` has already started a fresh
 * session, and putting the sensor to sleep here would strand that session
 * measuring a sensor whose UART receiver is switched off.
 */
static void abandon_cycle(uint32_t generation)
{
    if (s_profile_gen == generation && duty_cycling()) {
        park_sensor();
    }
}

/* --- fan-clean accounting -------------------------------------------------- */

/*
 * The daemon's group of the same name. Why the controller counts at all is in
 * acq_clean.h; what is here is the two halves that need a board - the flash the
 * count survives in, and the clean itself.
 */

/** Make the current count visible to the tasks that report it. */
static void publish_clean_count(void)
{
    s_clean_published = (uint32_t)acq_clean_run_seconds(&s_clean);
}

/** Read the account back from NVS. Anything unreadable starts a fresh week. */
static void load_clean_account(void)
{
    /* Idempotent, and normally already done by the Wi-Fi bring-up. Calling it
     * again costs nothing and means acquisition still has its counter on a
     * board brought up without a network. The recovery-by-erase decision is
     * deliberately left to `net_wifi`: two places erasing NVS on a bad page
     * count is one place too many. */
    const esp_err_t init = nvs_flash_init();
    if (init != ESP_OK) {
        ESP_LOGW(TAG, "NVS unavailable (%s); the fan-clean count will not survive a reboot",
                 esp_err_to_name(init));
    }

    uint32_t stored = 0;
    nvs_handle_t nvs;
    const esp_err_t err = nvs_open(CLEAN_NVS_NAMESPACE, NVS_READONLY, &nvs);
    if (err == ESP_OK) {
        if (nvs_get_u32(nvs, CLEAN_NVS_KEY, &stored) != ESP_OK) {
            stored = 0; /* never written: this board has not measured yet */
        }
        nvs_close(nvs);
    } else if (err != ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "could not read the fan-clean count: %s", esp_err_to_name(err));
    }

    acq_clean_init(&s_clean, (double)stored);
    publish_clean_count();
    ESP_LOGI(TAG, "fan clean: %.1f h measured since the last one, %.1f h of the week left",
             acq_clean_run_seconds(&s_clean) / 3600.0,
             (ACQ_CLEAN_AFTER_SECONDS - acq_clean_run_seconds(&s_clean)) / 3600.0);
}

/** Write the account to NVS, and only then call it persisted. */
static void save_clean_account(void)
{
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(CLEAN_NVS_NAMESPACE, NVS_READWRITE, &nvs);
    if (err == ESP_OK) {
        err = nvs_set_u32(nvs, CLEAN_NVS_KEY, (uint32_t)acq_clean_run_seconds(&s_clean));
        if (err == ESP_OK) {
            err = nvs_commit(nvs);
        }
        nvs_close(nvs);
    }
    if (err != ESP_OK) {
        /* Not fatal and not retried here: the next accrual tries again, and the
         * worst case is a count that restarts from the last value that did land
         * rather than acquisition stopping over a bookkeeping write. */
        ESP_LOGW(TAG, "could not persist the fan-clean count: %s", esp_err_to_name(err));
        return;
    }
    acq_clean_persisted(&s_clean);
}

/** The daemon's `_accrue_run_time`, plus the write its `_save_state` did. */
static void accrue_run_time(double seconds)
{
    acq_clean_accrue(&s_clean, seconds);
    publish_clean_count();
    if (acq_clean_should_persist(&s_clean)) {
        save_clean_account();
    }
}

/**
 * Note that the fan has just been cleaned, however that came about.
 *
 * **Divergence from the daemon, on purpose.** `sps30_daemon.py` resets the
 * count in `_maybe_auto_clean` but not in `_cmd_clean`, so a clean the user
 * asked for does not clear it. That contradicts what the count is documented to
 * be - "run time since the last clean" - and it breaks the half of this feature
 * the user can see: continuous mode never auto-cleans (see ::maybe_auto_clean),
 * so the only way the reported "clean due" can ever clear there is the button,
 * and a button that reports nothing happened is a button nobody presses twice.
 * A fan that was just cleaned is clean whoever asked, so both paths land here.
 */
static void clean_ran(void)
{
    acq_clean_reset(&s_clean);
    publish_clean_count();
    save_clean_account();
}

/**
 * Run a fan clean if a week of measurement has accrued. True if one ran.
 *
 * Called from the duty cycle only, which is the daemon's placement and its
 * reasoning: continuous operation leaves the sensor powered, so the sensor's
 * own weekly clean is running and forcing a second one would be redundant. Duty
 * cycling is where its counter is reset by every Sleep-Mode entry and so never
 * arrives, and this is the substitute.
 *
 * The count still accrues in continuous mode, and is still reported, so a board
 * that is power-cycled often enough to defeat the sensor's own counter tells
 * the user a clean is due even though it will not run one unprompted.
 */
static bool maybe_auto_clean(void)
{
    if (!acq_clean_due(&s_clean) || !s_sensor_present) {
        return false;
    }
    ESP_LOGI(TAG, "auto-clean: %.0f h of measurement since the last clean",
             acq_clean_run_seconds(&s_clean) / 3600.0);

    /* Asleep the UART receiver is off, so every command below would time out
     * and a due clean would be reported as a lost sensor. */
    if (s_asleep) {
        if (sps30_wake(3) != ESP_OK) {
            handle_sensor_loss("sensor did not wake for its scheduled fan clean");
            return true;
        }
        s_asleep = false;
    }
    /* The clean is only accepted while measuring, and the cycle has just parked
     * the sensor, so start it again first. */
    (void)sps30_stop_measurement();
    if (sps30_start_measurement() != ESP_OK) {
        handle_sensor_loss("could not start measuring for a scheduled clean");
        return true;
    }

    end_phase();
    set_state(ACQ_STATE_CLEANING, "scheduled fan self-clean");
    if (sps30_fan_clean() != ESP_OK) {
        handle_sensor_loss("scheduled fan_clean failed");
        return true;
    }
    /* Cleared here rather than after the wait, because the sensor has accepted
     * the command and the fan is already spinning up: nothing that happens over
     * the next twelve seconds can make it un-cleaned, and a shutdown or a lost
     * sensor in that window must not leave the count saying a clean is still
     * owed. It would run a second one on the next boot. */
    clean_ran();

    interruptible_delay(CLEAN_DURATION_MS);
    if (s_shutdown) {
        return true;
    }

    park_sensor();
    set_state(ACQ_STATE_SLEEPING, "clean complete");
    return true;
}

/** Bring the sensor from Sleep-Mode to measuring. False if it has gone. */
static bool wake_and_start(void)
{
    if (s_asleep) {
        /* Sleep-Mode switches the UART receiver off, so the wake sequence is a
         * dummy byte to rouse the interface followed by the real command; the
         * driver's retry count covers a sensor that misses the first. */
        if (sps30_wake(3) != ESP_OK) {
            handle_sensor_loss("sensor did not wake for its duty cycle");
            return false;
        }
        s_asleep = false;
    }
    /* Start is rejected with 0x43 "not allowed in current state" if the sensor
     * is already measuring, which it is on the first cycle after a switch from
     * continuous. Stop first to reach a known idle state; the error when it was
     * already idle is the expected one and is ignored. */
    (void)sps30_stop_measurement();
    if (sps30_start_measurement() != ESP_OK) {
        handle_sensor_loss("duty-cycle start failed");
        return false;
    }
    return true;
}

/**
 * Average one reading per second over @p seconds into a single measurement.
 *
 * Averaging rather than sampling once is what the app note asks for: after
 * warm-up it recommends folding a further 30 s of readings into one value for
 * repeatability. Its pseudo-code counts samples rather than watching the clock
 * and so does this - the SPS30 produces exactly one new value per second, so a
 * count is the honest unit and does not drift with the delay's accuracy.
 *
 * @return true if @p out holds an average; false if the run produced nothing.
 */
static bool collect_average(double seconds, sps30_measurement_t *out)
{
    uint32_t samples = (uint32_t)(seconds + 0.5);
    if (samples == 0) {
        samples = 1;
    }

    double totals[SPS30_MEASUREMENT_FIELDS] = {0};
    uint32_t count = 0;

    for (uint32_t i = 0; i < samples && !s_shutdown; i++) {
        sps30_measurement_t m;
        bool ready = false;
        if (sps30_read_measurement(&m, &ready) != ESP_OK) {
            s_stats.read_failures++;
            account_link_fault();
            handle_sensor_loss("duty-cycle read failed");
            return false;
        }
        if (!ready) {
            s_stats.not_ready++;
        } else if (!sps30_measurement_plausible(&m)) {
            s_stats.implausible++;
        } else {
            for (size_t v = 0; v < SPS30_MEASUREMENT_FIELDS; v++) {
                totals[v] += m.values[v];
            }
            count++;
        }
        interruptible_delay(1000); /* one new value per second, per the datasheet */
        if (uxQueueMessagesWaiting(s_commands) > 0) {
            /* A command is waiting, and every remaining delay would return
             * instantly - turning the rest of the run into a burst of
             * back-to-back UART reads. Stop here instead and let the caller
             * decide; a shorter average taken after a full warm-up is still a
             * good reading, and the caller may be about to discard it anyway. */
            break;
        }
    }

    if (count == 0) {
        ESP_LOGW(TAG, "duty cycle produced no readings");
        return false;
    }
    for (size_t v = 0; v < SPS30_MEASUREMENT_FIELDS; v++) {
        out->values[v] = (float)(totals[v] / count);
    }
    return true;
}

/** One wake -> warm up -> average -> sleep cycle. */
static void duty_cycle_once(void)
{
    const double cycle = s_profile.cycle_s;
    const double warmup = s_profile.warmup_s;
    const double average = s_profile.average_s;
    const int64_t started_us = esp_timer_get_time();
    const uint32_t generation = s_profile_gen;

    if (!wake_and_start()) {
        return;
    }

    begin_phase(warmup);
    set_state(ACQ_STATE_WARMUP, "warming up");
    if (!sleep_checked(warmup, generation)) {
        abandon_cycle(generation);
        return;
    }
    /* The fan has been running throughout the warm-up, so it counts towards the
     * clean even though nothing was recorded from it. */
    accrue_run_time(warmup);

    begin_phase(average);
    set_state(ACQ_STATE_MEASURING, "averaging");
    sps30_measurement_t averaged;
    const bool got = collect_average(average, &averaged);
    accrue_run_time(average);
    drain_commands(); /* the run may have stopped short for a waiting command */
    if (s_profile_gen != generation || !s_sensor_present || s_shutdown ||
        s_state != ACQ_STATE_MEASURING) {
        abandon_cycle(generation);
        return;
    }
    if (got) {
        publish_measurement(&averaged);
    }

    park_sensor();
    if (maybe_auto_clean()) {
        /* The clean has left the sensor parked and the state at `sleeping`; the
         * loop starts the next cycle from there rather than sleeping out the
         * remainder of a cycle the clean has already overrun. */
        return;
    }

    const double elapsed = (double)(esp_timer_get_time() - started_us) / 1000000.0;
    double remaining = cycle - elapsed;
    if (remaining < 0) {
        remaining = 0;
    }
    begin_phase(remaining);
    set_state(ACQ_STATE_SLEEPING, "sleeping until the next cycle");
    (void)sleep_checked(remaining, generation);
    end_phase();
}

/* --- checking -> measuring ------------------------------------------------ */

/** Identify the sensor. ESP_OK only if all three reads succeeded. */
static esp_err_t probe(acq_sensor_info_t *out)
{
    esp_err_t err = sps30_product_type(out->product, sizeof(out->product));
    if (err != ESP_OK) {
        return err;
    }
    err = sps30_serial_number(out->serial, sizeof(out->serial));
    if (err != ESP_OK) {
        return err;
    }
    return sps30_firmware_version(&out->firmware_major, &out->firmware_minor);
}

static void try_start(void)
{
    acq_sensor_info_t found = {0};
    esp_err_t err = ESP_FAIL;

    for (uint32_t attempt = 0; attempt < s_config.identify_attempts; attempt++) {
        err = probe(&found);
        if (err == ESP_OK) {
            break;
        }
    }

    if (err != ESP_OK) {
        /* A silent sensor is not necessarily an absent one: in Sleep-Mode the
         * UART receiver is switched off entirely, so a sensor left asleep - by
         * a reset mid-cycle, say - answers nothing at all. Send the wake
         * sequence and probe once more before writing it off, or low-power
         * operation would lose the sensor permanently across a restart. */
        if (sps30_wake(3) == ESP_OK && probe(&found) == ESP_OK) {
            ESP_LOGI(TAG, "sensor was asleep; woken during detection");
            err = ESP_OK;
        }
    }

    if (err != ESP_OK) {
        account_link_fault();
        log_link_diagnosis("no sensor detected");
        interruptible_delay(s_config.poll_interval_ms);
        return;
    }

    found.valid = true;
    taskENTER_CRITICAL(&s_published_mux);
    s_info = found;
    taskEXIT_CRITICAL(&s_published_mux);
    s_sensor_present = true;
    ESP_LOGI(TAG, "sensor detected: product=%s serial=%s firmware=%u.%u",
             found.product, found.serial, found.firmware_major, found.firmware_minor);

    const bool resumed = s_recovering;
    s_recovering = false;
    if (resumed) {
        ESP_LOGI(TAG, "sensor recovered; continuing session %" PRIu32, s_session);
    }
    begin_measuring(resumed ? "sensor recovered" : "sensor detected", !resumed);
}

/* --- commands ------------------------------------------------------------- */

static void run_clean(void)
{
    if (!s_sensor_present) {
        ESP_LOGW(TAG, "clean requested with no sensor; ignoring");
        return;
    }

    /* A duty-cycling sensor is asleep for most of the cycle, and asleep its
     * UART receiver is off, so every command below would time out and the clean
     * would be reported as a lost sensor. */
    if (s_asleep) {
        if (sps30_wake(3) != ESP_OK) {
            handle_sensor_loss("sensor did not wake for a fan clean");
            return;
        }
        s_asleep = false;
    }
    end_phase();

    /* The fan clean is only accepted while measuring, so make sure it is - the
     * CLI does the same, starting measurement before asking for the clean. */
    if (s_state != ACQ_STATE_MEASURING) {
        if (sps30_start_measurement() != ESP_OK) {
            handle_sensor_loss("could not start measuring before a clean");
            return;
        }
    }

    if (sps30_fan_clean() != ESP_OK) {
        handle_sensor_loss("fan_clean failed");
        return;
    }
    /* Accepted, so the fan is clean whatever happens next - see the same note
     * in ::maybe_auto_clean. */
    clean_ran();

    set_state(ACQ_STATE_CLEANING, "fan self-clean, about 10 s");
    interruptible_delay(CLEAN_DURATION_MS);
    if (s_shutdown) {
        return;
    }
    /* A clean disturbs the reading exactly as a fresh start does, so the
     * readings after it settle too. Same session: this is the user cleaning the
     * fan mid-recording, not starting a new one. */
    begin_measuring("resumed after fan clean", false);
}

static void pause_sensor(void)
{
    if (s_sensor_present && !s_asleep) {
        (void)sps30_stop_measurement();
    }
    end_phase();
    set_state(ACQ_STATE_PAUSED, "paused on request");
}

/**
 * Adopt a new profile.
 *
 * Bumping the generation is what stands down any cycle in flight; see
 * ::abandon_cycle. The rest is the daemon's `_cmd_set_profile`: a new regime
 * means a new session, because one file must never mix 1 Hz rows with
 * fifteen-minute averages, and the only thing a reader has to tell them apart
 * by is which session they are in.
 */
static void apply_profile(const acq_profile_t *profile)
{
    const bool changed = memcmp(&s_profile, profile, sizeof(*profile)) != 0;
    taskENTER_CRITICAL(&s_published_mux);
    s_profile = *profile;
    taskEXIT_CRITICAL(&s_published_mux);
    s_profile_gen++;
    ESP_LOGI(TAG, "profile -> %s interval=%.3fs cycle=%.0fs warmup=%.0fs average=%.0fs",
             duty_cycling() ? "duty_cycle" : "continuous", s_profile.interval_s,
             s_profile.cycle_s, s_profile.warmup_s, s_profile.average_s);

    /* Announce before acting, and announce even when nothing changed. The client
     * that asked is waiting to be told what it got, and a request that clamped
     * to the profile already in force is still an answered request - saying
     * nothing is what a dead control looks like. */
    for (size_t i = 0; i < s_observer_count; i++) {
        if (s_observers[i].on_profile != NULL) {
            s_observers[i].on_profile(&s_profile, s_observers[i].ctx);
        }
    }

    if (!changed || !s_sensor_present) {
        return;
    }
    /* Paused stays paused. A profile change says what to do when measuring
     * resumes, not that it should resume - and starting the sensor here would
     * override a deliberate stop with a settings tweak. */
    if (s_state == ACQ_STATE_PAUSED || s_state == ACQ_STATE_CHECKING) {
        return;
    }
    if (s_asleep) {
        /* Best effort: a sensor that will not wake is caught by the first read
         * of the new session, which routes it through sensor loss properly. */
        (void)sps30_wake(3);
        s_asleep = false;
    }
    begin_measuring(duty_cycling() ? "profile: low power" : "profile: continuous", true);
}

/**
 * Carry out one raw exchange, or say why it cannot happen.
 *
 * The daemon's `_cmd_raw`: no state change, no recovery, no interpretation of
 * the answer. A raw frame that upsets the sensor is meant to be recoverable
 * with `restart`, which is exactly what the daemon's comment on `_cmd_restart`
 * says it is there for, so nothing here tries to be clever about it.
 *
 * Signals ::s_raw_done for the sequence it was queued with, and for **no
 * other**. A stale command returns in silence.
 *
 * That is deliberate and is the opposite of what this comment used to claim.
 * Signalling on a stale sequence would release the caller of the *next*
 * exchange before that exchange had run, and hand it whatever ::s_raw.result
 * held - which at that moment is the `ESP_ERR_TIMEOUT` ::acq_raw seeds it with,
 * or worse, the previous caller's answer. Nobody is left blocked by the
 * silence: the caller whose sequence this was has already timed out and gone,
 * and the one that owns the buffers now gets its own signal when its own
 * command is drained.
 *
 * Restoring the documented behaviour would reintroduce the bug the sequence
 * number exists to prevent. See ::acq_raw_job_t.
 */
static void run_raw(uint32_t seq)
{
    if (seq != s_raw.seq) {
        /* Its caller timed out and a later one now owns the buffers. */
        return;
    }
    if (!s_sensor_present) {
        s_raw.result = ESP_ERR_INVALID_STATE;
    } else if (s_asleep) {
        /* Sleep-Mode switches the sensor's serial receiver off, so this would
         * be a guaranteed timeout dressed up as a link fault. Waking it here
         * would be the raw console silently changing what the sensor is doing,
         * which is the one thing a pass-through must not do. */
        s_raw.result = ESP_ERR_NOT_ALLOWED;
    } else {
        s_raw.result = sps30_transceive_raw(s_raw.request, s_raw.request_len,
                                            s_raw.response, sizeof(s_raw.response),
                                            &s_raw.response_len);
    }
    xSemaphoreGive(s_raw_done);
}

/**
 * Fail every raw command still queued, so nobody is left waiting on a task
 * that is about to stop. Called on the shutdown paths, which are the only ones
 * that leave the queue undrained.
 */
static void abandon_queued_raw(void)
{
    acq_cmd_t cmd;
    while (xQueueReceive(s_commands, &cmd, 0) == pdTRUE) {
        if (cmd.kind == CMD_RAW && cmd.raw_seq == s_raw.seq) {
            s_raw.result = ESP_ERR_INVALID_STATE;
            s_raw.response_len = 0;
            xSemaphoreGive(s_raw_done);
        }
    }
}

static void drain_commands(void)
{
    acq_cmd_t cmd;
    while (xQueueReceive(s_commands, &cmd, 0) == pdTRUE) {
        switch (cmd.kind) {
        case CMD_PAUSE:
            /* Warm-up and sleeping are pausable too, and have to be: they are
             * where a duty-cycling sensor spends almost all of its time, so a
             * pause that only worked from MEASURING would look ignored for
             * fourteen minutes out of every fifteen. */
            if (s_state == ACQ_STATE_MEASURING || s_state == ACQ_STATE_CHECKING ||
                s_state == ACQ_STATE_WARMUP || s_state == ACQ_STATE_SLEEPING) {
                pause_sensor();
            }
            break;
        case CMD_RESUME:
            if (s_state == ACQ_STATE_PAUSED) {
                if (s_sensor_present) {
                    if (s_asleep && sps30_wake(3) == ESP_OK) {
                        s_asleep = false;
                    }
                    begin_measuring("resumed on request", true);
                } else {
                    set_state(ACQ_STATE_CHECKING, "resumed; looking for a sensor");
                }
            }
            break;
        case CMD_CLEAN:
            run_clean();
            break;
        case CMD_SET_PROFILE:
            apply_profile(&cmd.profile);
            break;
        case CMD_RAW:
            run_raw(cmd.raw_seq);
            break;
        case CMD_SHUTDOWN:
            s_shutdown = true;
            abandon_queued_raw();
            return;
        }
    }
}

/* --- the loop ------------------------------------------------------------- */

/** Exactly one iteration, mirroring the daemon's `step()`. */
static void step(void)
{
    drain_commands();
    if (s_shutdown) {
        return;
    }

    switch (s_state) {
    case ACQ_STATE_CHECKING:
        try_start();
        break;
    case ACQ_STATE_MEASURING:
        if (duty_cycling()) {
            duty_cycle_once();
        } else {
            measure_once();
            /* The daemon accrues its interval here for the same reason: in
             * continuous mode the fan is running for the whole of it, so the
             * recording interval and the fan's run time are the same seconds.
             * Taken from the clamped millisecond value rather than the profile
             * field, so what is accrued is what was actually waited. */
            accrue_run_time((double)interval_ms() / 1000.0);
            interruptible_delay(interval_ms());
        }
        break;
    case ACQ_STATE_WARMUP:
    case ACQ_STATE_SLEEPING:
        /* Only a duty cycle enters these, and it leaves them itself. Arriving
         * here means the cycle stood down partway - which is exactly what a
         * switch back to continuous makes it do - so these would be a dead end
         * without an explicit way out. */
        if (duty_cycling()) {
            end_phase();
            set_state(ACQ_STATE_MEASURING, "next cycle");
        } else {
            begin_measuring("profile: continuous", true);
        }
        break;
    case ACQ_STATE_PAUSED:
    case ACQ_STATE_CLEANING:
    case ACQ_STATE_STOPPED:
    default:
        interruptible_delay(s_config.poll_interval_ms);
        break;
    }
}

static void acq_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "acquisition starting: interval=%" PRIu32 " ms, settle=%" PRIu32
                  " ms, %" PRIu32 " failed reads tolerated",
             s_config.interval_ms, s_config.settle_ms,
             s_config.read_failures_before_loss);
    set_state(ACQ_STATE_CHECKING, "looking for a sensor");

    while (!s_shutdown) {
        step();
    }

    /* Clean stop: leave the sensor idle rather than spinning its fan into a
     * firmware that has stopped listening. */
    if (s_sensor_present) {
        (void)sps30_stop_measurement();
    }
    /* The daemon saves its state on the way out for the same reason: an orderly
     * stop is the one case where the accrual since the last write is known to
     * be safe to keep, so there is no reason to lose it. */
    if (acq_clean_should_persist(&s_clean)) {
        save_clean_account();
    }
    set_state(ACQ_STATE_STOPPED, "shut down");
    ESP_LOGI(TAG, "acquisition stopped after %" PRIu32 " readings", s_stats.readings);

    /* The `s_shutdown` flag can break the loop without a CMD_SHUTDOWN having
     * been drained, so this is the second place a queued raw could be stranded. */
    abandon_queued_raw();
    s_task = NULL;
    xSemaphoreGive(s_done);
    vTaskDelete(NULL);
}

/* --- public API ----------------------------------------------------------- */

esp_err_t acq_start(const acq_config_t *config)
{
    if (s_task != NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    s_config = config ? *config : (acq_config_t)ACQ_DEFAULT_CONFIG();
    if (s_config.interval_ms == 0) {
        s_config.interval_ms = 1000;
    }
    if (s_config.identify_attempts == 0) {
        s_config.identify_attempts = 1;
    }

    memset(&s_stats, 0, sizeof(s_stats));
    taskENTER_CRITICAL(&s_published_mux);
    memset(&s_info, 0, sizeof(s_info));
    taskEXIT_CRITICAL(&s_published_mux);
    s_session = 0;
    s_sequence = 0;
    s_read_failures = 0;
    s_settle_remaining = 0;
    s_recovering = false;
    s_sensor_present = false;
    s_shutdown = false;
    s_asleep = false;
    s_profile_gen = 0;
    /* The profile owns the read interval from here on; the config's
     * `interval_ms` is its starting value, so there is one setting rather than
     * two that can disagree about how often to read. */
    acq_profile_t start = (acq_profile_t)ACQ_DEFAULT_PROFILE();
    start.interval_s = (double)s_config.interval_ms / 1000.0;
    taskENTER_CRITICAL(&s_published_mux);
    s_phase_end_us = 0;
    s_profile = start;
    taskEXIT_CRITICAL(&s_published_mux);

    /* Before the task starts, so the first accrual adds to what the last run
     * left rather than to zero. This is the whole point of the counter: a board
     * that reboots weekly must not restart the week each time. */
    load_clean_account();

    sps30_config_t sensor = SPS30_DEFAULT_CONFIG();
    esp_err_t err = sps30_init(&sensor);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }

    if (s_commands == NULL) {
        s_commands = xQueueCreate(COMMAND_QUEUE_DEPTH, sizeof(acq_cmd_t));
        if (s_commands == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }
    if (s_done == NULL) {
        s_done = xSemaphoreCreateBinary();
        if (s_done == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }
    if (s_raw_mutex == NULL) {
        s_raw_mutex = xSemaphoreCreateMutex();
        if (s_raw_mutex == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }
    if (s_raw_done == NULL) {
        s_raw_done = xSemaphoreCreateBinary();
        if (s_raw_done == NULL) {
            return ESP_ERR_NO_MEM;
        }
    }

    if (xTaskCreate(acq_task, "acq", s_config.task_stack_bytes, NULL,
                    s_config.task_priority, &s_task) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

static esp_err_t post(const acq_cmd_t *cmd)
{
    if (s_commands == NULL || s_task == NULL) {
        return ESP_ERR_INVALID_STATE;
    }
    return xQueueSend(s_commands, cmd, pdMS_TO_TICKS(100)) == pdTRUE
               ? ESP_OK
               : ESP_ERR_TIMEOUT;
}

/** Post a command that carries nothing but its kind. */
static esp_err_t post_kind(acq_cmd_kind_t kind)
{
    const acq_cmd_t cmd = {.kind = kind};
    return post(&cmd);
}

esp_err_t acq_stop(void)
{
    if (s_task == NULL) {
        return ESP_OK;
    }
    /* Set the flag as well as posting: the flag breaks any delay already in
     * progress, and the queued command is what the drain sees. */
    esp_err_t err = post_kind(CMD_SHUTDOWN);
    s_shutdown = true;
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }
    /* Generous: a shutdown arriving mid-exchange waits out one UART timeout. */
    if (xSemaphoreTake(s_done, pdMS_TO_TICKS(10000)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }
    return ESP_OK;
}

esp_err_t acq_pause(void)
{
    return post_kind(CMD_PAUSE);
}

esp_err_t acq_resume(void)
{
    return post_kind(CMD_RESUME);
}

esp_err_t acq_clean(void)
{
    return post_kind(CMD_CLEAN);
}

esp_err_t acq_raw_transaction(const uint8_t *request, size_t request_len,
                              uint8_t *response, size_t response_cap,
                              size_t *response_len)
{
    if (request == NULL || response == NULL || response_len == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (request_len == 0 || request_len > sizeof(s_raw.request)) {
        return ESP_ERR_INVALID_SIZE;
    }
    if (s_raw_mutex == NULL || s_raw_done == NULL || s_task == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    /* Serialise callers rather than queueing them: two people at two phones
     * both poking the sensor by hand is not a case worth buffering, and the
     * one who waits gets a plain "busy" instead of a delayed surprise. */
    if (xSemaphoreTake(s_raw_mutex, pdMS_TO_TICKS(RAW_WAIT_MS)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    /* An exchange that finished just after its caller gave up leaves the
     * completion signal set. Clear it here, under the mutex, so this caller
     * waits for its own answer rather than being handed the last one's. */
    (void)xSemaphoreTake(s_raw_done, 0);

    /* Whatever an abandoned earlier exchange left behind is dead the moment
     * this sequence number is written; see the note on ::acq_raw_job_t. */
    s_raw.seq++;
    const uint32_t seq = s_raw.seq;
    memcpy(s_raw.request, request, request_len);
    s_raw.request_len = request_len;
    s_raw.response_len = 0;
    s_raw.result = ESP_ERR_TIMEOUT;

    esp_err_t err;
    const acq_cmd_t cmd = {.kind = CMD_RAW, .raw_seq = seq};
    if (post(&cmd) != ESP_OK) {
        err = ESP_ERR_TIMEOUT;
    } else if (xSemaphoreTake(s_raw_done, pdMS_TO_TICKS(RAW_WAIT_MS)) != pdTRUE) {
        err = ESP_ERR_TIMEOUT;
    } else {
        err = s_raw.result;
        if (err == ESP_OK) {
            if (s_raw.response_len > response_cap) {
                err = ESP_ERR_INVALID_SIZE;
            } else {
                memcpy(response, s_raw.response, s_raw.response_len);
                *response_len = s_raw.response_len;
            }
        }
    }

    xSemaphoreGive(s_raw_mutex);
    return err;
}

double acq_clean_seconds_since_clean(void)
{
    /* Deliberately unlocked: one aligned word, one writer, and every reader
     * wants a number good to a tenth of an hour. A lock to stop a caller seeing
     * a value a second old would be guarding against nothing anyone can
     * perceive - which is not true of the torn double it replaces. */
    return (double)s_clean_published;
}

esp_err_t acq_set_profile(const acq_profile_t *profile)
{
    if (profile == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    /* `wire.normalize_profile`, field for field, in `acq_profile.c` so the host
     * tests can hold it against the Python it mirrors. Nothing unclamped ever
     * reaches the state machine: every path into a profile change comes through
     * here. */
    acq_cmd_t cmd = {.kind = CMD_SET_PROFILE, .profile = *profile};
    acq_profile_normalize(&cmd.profile);

    if (cmd.profile.interval_s != profile->interval_s || cmd.profile.cycle_s != profile->cycle_s ||
        cmd.profile.warmup_s != profile->warmup_s || cmd.profile.average_s != profile->average_s) {
        /* Said out loud. A profile that was quietly clamped and a profile that
         * was obeyed look identical afterwards, and the difference is the whole
         * point of the bounds. */
        ESP_LOGW(TAG,
                 "profile clamped: interval %.1f->%.1f, cycle %.1f->%.1f, warmup %.1f->%.1f, "
                 "average %.1f->%.1f",
                 profile->interval_s, cmd.profile.interval_s, profile->cycle_s, cmd.profile.cycle_s,
                 profile->warmup_s, cmd.profile.warmup_s, profile->average_s,
                 cmd.profile.average_s);
    }

    return post(&cmd);
}
