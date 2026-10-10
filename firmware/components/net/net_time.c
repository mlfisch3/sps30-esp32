/**
 * @file net_time.c
 * @brief SNTP, the Eastern zone, and uptime -> wall clock. See `net_time.h`.
 *
 * The zone's offset itself is next door in `net_time_offset.c`, which is pure
 * `<time.h>` and therefore host-testable, unlike everything here.
 */

#include "net_time.h"

#include <stdlib.h>
#include <sys/time.h>
#include <time.h>

#include "esp_log.h"
#include "esp_sntp.h"
#include "esp_timer.h"
#include "nvs.h"
#include "nvs_flash.h"

static const char *TAG = "time";

/** False pins the clock after the first answer. See ::net_time_start. */
static bool s_keep_syncing = true;

static net_time_observer_t s_observer;

/** Where the last time we were willing to believe survives a power cycle. */
#define TIME_NVS_NAMESPACE "sps30_time"
#define TIME_NVS_KEY "last_good"

/**
 * Microseconds to add to `esp_timer_get_time()` to get Unix microseconds.
 *
 * Written by the sync callback and read by client tasks. A 64-bit store is not
 * atomic on this part, so a reader could in principle see half of an update -
 * hence the valid flag being set only after the offset, and the offset only ever
 * moving by the tens of milliseconds a resync corrects. The worst case is one
 * reading stamped a few milliseconds out, which is not worth a mutex on the
 * serialising path.
 */
static volatile int64_t s_offset_us;
static volatile bool s_valid;
static volatile uint32_t s_syncs;
static volatile uint32_t s_rejected;

/**
 * The newest instant this board has been willing to stamp a reading with.
 *
 * Loaded from NVS at start and written forward as the clock is accepted, which
 * is what makes the backward check survive the reboot an attacker would
 * otherwise use to clear it. Seconds, because a second of resolution is all the
 * check needs and it keeps the stored value readable.
 */
static int64_t s_last_good_s;
/** What was actually written, so the rate limit below can compare against it. */
static int64_t s_persisted_s;

static void persist_last_good(int64_t epoch_s)
{
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(TIME_NVS_NAMESPACE, NVS_READWRITE, &nvs);
    if (err == ESP_OK) {
        err = nvs_set_i64(nvs, TIME_NVS_KEY, epoch_s);
        if (err == ESP_OK) {
            err = nvs_commit(nvs);
        }
        nvs_close(nvs);
    }
    if (err != ESP_OK) {
        /* Not fatal. The window still holds for this power-on; what is lost is
         * its memory across the next reboot, and saying so is better than
         * refusing to keep time over a bookkeeping write. */
        ESP_LOGW(TAG, "could not persist the last known good time: %s", esp_err_to_name(err));
        return;
    }
    s_persisted_s = epoch_s;
}

/** Read the mark back. Anything unreadable simply leaves the board with none. */
static void load_last_good(void)
{
    /* Idempotent, and normally already done by the Wi-Fi bring-up; the same
     * argument `acq.c` makes for calling it again. */
    const esp_err_t init = nvs_flash_init();
    if (init != ESP_OK) {
        ESP_LOGW(TAG, "NVS unavailable (%s); the clock window will not survive a reboot",
                 esp_err_to_name(init));
        return;
    }

    nvs_handle_t nvs;
    const esp_err_t err = nvs_open(TIME_NVS_NAMESPACE, NVS_READONLY, &nvs);
    if (err == ESP_OK) {
        int64_t stored = 0;
        if (nvs_get_i64(nvs, TIME_NVS_KEY, &stored) == ESP_OK && stored > 0) {
            s_last_good_s = stored;
            s_persisted_s = stored;
        }
        nvs_close(nvs);
    } else if (err != ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "could not read the last known good time: %s", esp_err_to_name(err));
    }

    if (s_last_good_s > 0) {
        char stamp[40];
        const time_t secs = (time_t)s_last_good_s;
        struct tm local;
        localtime_r(&secs, &local);
        strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S %Z", &local);
        ESP_LOGI(TAG, "no clock will be believed before %s", stamp);
    }
}

/** Put the system clock back to @p epoch_us after refusing what SNTP applied. */
static void restore_clock(int64_t epoch_us)
{
    const struct timeval back = {
        .tv_sec = (time_t)(epoch_us / 1000000),
        .tv_usec = (suseconds_t)(epoch_us % 1000000),
    };
    settimeofday(&back, NULL);
}

/** Log a refused clock the way it deserves: with both instants spelled out. */
static void log_refusal(const char *source, const char *why, int64_t candidate_s,
                        int64_t reference_s)
{
    char got[40];
    char expected[40];
    struct tm tm_got, tm_expected;
    const time_t a = (time_t)candidate_s;
    const time_t b = (time_t)reference_s;
    localtime_r(&a, &tm_got);
    localtime_r(&b, &tm_expected);
    strftime(got, sizeof(got), "%Y-%m-%d %H:%M:%S %Z", &tm_got);
    strftime(expected, sizeof(expected), "%Y-%m-%d %H:%M:%S %Z", &tm_expected);
    ESP_LOGW(TAG, "refused a time from %s: %s (%s), not %s", source, got, why, expected);
}

/**
 * Decide whether a candidate time may be kept, and keep it if so.
 *
 * The two rules from `net_time.h`, applied to **every** source. SNTP is the
 * obvious one to distrust, but a `set_time` from a connected client is not
 * inherently better - it is unauthenticated in exactly the same way - and a
 * clock accepted through a second door that skipped these checks would be a
 * hole straight through them. Whoever offers a time gets the same gate.
 *
 * @param source what to call it in the log, so a refusal names its origin.
 * @return true if the clock now reads @p candidate_us.
 */
static bool accept_candidate(int64_t candidate_us, int64_t uptime_us, const char *source)
{
    const int64_t candidate_s = candidate_us / 1000000;

    /* Time does not run backwards, and a reading that has already been stamped
     * cannot be unstamped. This is the check that matters: re-dating the record
     * is what a clock attack is for, and it needs the clock to go *back*. It
     * holds across a reboot, which is the whole reason the mark is in NVS. */
    if (s_last_good_s > 0 && candidate_s < s_last_good_s - NET_TIME_BACKWARD_SLACK_S) {
        s_rejected++;
        log_refusal(source, "earlier than this board has already recorded", candidate_s,
                    s_last_good_s);
        /* The clock is put back to the mark rather than left in the attacker's
         * past, but the board is deliberately NOT called valid on the strength
         * of it: the mark is where time had got to, not where it is now.
         * Readings stay held back until a source this board believes answers,
         * which is loud, and a wrongly dated record would not be. */
        restore_clock(s_valid ? uptime_us + s_offset_us : s_last_good_s * 1000000);
        return false;
    }

    /* Once the clock has been set, a resync corrects milliseconds. The bound is
     * hours rather than seconds only because `esp_timer` is a crystal and a
     * board that stays up for a year accumulates real drift against it; nothing
     * legitimate comes close to the limit, and each step that survives this is
     * logged, so walking the clock forward is slow and noisy rather than free. */
    if (s_valid) {
        const int64_t expected_us = uptime_us + s_offset_us;
        int64_t step_us = candidate_us - expected_us;
        if (step_us < 0) {
            step_us = -step_us;
        }
        if (step_us > (int64_t)NET_TIME_MAX_STEP_S * 1000000) {
            s_rejected++;
            log_refusal(source, "further than a resync can honestly move the clock",
                        candidate_s, expected_us / 1000000);
            restore_clock(expected_us);
            return false;
        }
    }

    s_offset_us = candidate_us - uptime_us;
    s_syncs++;

    if (candidate_s > s_last_good_s) {
        s_last_good_s = candidate_s;
        /* Rate limited rather than written per sync: the mark only has to be
         * good enough that a reboot cannot rewind the board past what it has
         * already recorded, and an hour of granularity is well inside that. */
        if (s_last_good_s - s_persisted_s >= NET_TIME_PERSIST_INTERVAL_S) {
            persist_last_good(s_last_good_s);
        }
    }

    if (!s_valid) {
        s_valid = true;
        char stamp[40];
        const time_t secs = (time_t)candidate_s;
        struct tm local;
        localtime_r(&secs, &local);
        strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S %Z", &local);
        ESP_LOGI(TAG, "clock set from %s: %s", source, stamp);
        ESP_LOGI(TAG, "readings can now carry a timestamp the app will accept");
        /* The first accepted time is written straight away rather than waiting
         * for the interval: a board that is power-cycled often would otherwise
         * never lay down a mark at all. */
        persist_last_good(s_last_good_s);
    } else {
        ESP_LOGD(TAG, "clock re-set from %s (%u)", source, (unsigned)s_syncs);
    }
    return true;
}

/** Tell the observer, with the monotonic reading only this layer can pair. */
static void announce(int64_t uptime_us, int64_t epoch_ms, int utc_offset_min)
{
    if (s_observer != NULL) {
        s_observer(uptime_us, epoch_ms, utc_offset_min);
    }
}

void net_time_observe(net_time_observer_t observer)
{
    s_observer = observer;
}

static void on_sync(struct timeval *tv)
{
    (void)tv;
    /* SNTP has already stepped the system clock by the time this runs -
     * SNTP_SYNC_MODE_IMMED does that and then notifies - so refusing a step
     * means putting the clock back, not declining to take it. */
    struct timeval now;
    gettimeofday(&now, NULL);
    const int64_t candidate_us = (int64_t)now.tv_sec * 1000000 + now.tv_usec;
    const int64_t uptime_us = esp_timer_get_time();
    const bool first = !s_valid;

    if (!accept_candidate(candidate_us, uptime_us, "the network")) {
        return;
    }

    if (first && !s_keep_syncing) {
        /* Measurement mode. Stopping here is the whole point: a clock that
         * keeps resyncing corrects the drift we are trying to measure. */
        esp_sntp_stop();
        ESP_LOGW(TAG, "SNTP stopped after one sync; the clock is now free-running");
        ESP_LOGW(TAG, "this is a drift-measurement build, not a normal one");
    }

    /* Every accepted sync, not only the first: each is an independent
     * measurement of the offset between this board's crystal and the world,
     * which is exactly what an equivalence records.
     *
     * Minutes, not seconds. ::announce hands its third argument straight to the
     * observer, which writes it into the anchor's `utc_offset_min`, and this
     * call passed ::net_time_utc_offset_s for long enough to leave -14400 in
     * the record of a board that synced with no phone present. */
    announce(uptime_us, candidate_us / 1000,
             net_time_utc_offset_min(candidate_us / 1000000));
}

bool net_time_set(int64_t epoch_ms, int utc_offset_min)
{
    /* 2020-01-01. A phone whose own clock has not been set yet reports 1970,
     * and the backward check below cannot catch that on a board that has never
     * had a mark to compare against - which is exactly the board this command
     * exists for. Refusing is the honest answer: it goes on recording relative,
     * losing nothing. */
    if (epoch_ms < 1577836800000LL) {
        s_rejected++;
        ESP_LOGW(TAG, "refusing a client time of %lld ms: before 2020",
                 (long long)epoch_ms);
        return false;
    }

    const int64_t uptime_us = esp_timer_get_time();
    const int64_t candidate_us = epoch_ms * 1000;
    /* Set first, then check: `accept_candidate` puts the clock back itself if it
     * refuses, so both paths through it leave the system clock consistent with
     * what was decided. */
    const struct timeval tv = {
        .tv_sec = (time_t)(epoch_ms / 1000),
        .tv_usec = (suseconds_t)((epoch_ms % 1000) * 1000),
    };
    settimeofday(&tv, NULL);
    if (!accept_candidate(candidate_us, uptime_us, "a connected client")) {
        return false;
    }
    announce(uptime_us, epoch_ms, utc_offset_min);
    return true;
}

void net_time_set_zone(const char *posix_tz)
{
    if (posix_tz != NULL) {
        setenv("TZ", posix_tz, 1);
        tzset();
    }
}

void net_time_start(const char *posix_tz, bool keep_syncing)
{
    s_keep_syncing = keep_syncing;
    net_time_set_zone(posix_tz);

    /* Before SNTP, so the first answer is already checked against the mark. */
    load_last_good();

    esp_sntp_setoperatingmode(ESP_SNTP_OPMODE_POLL);
    /* Three servers, because a single unreachable host means no clock at all and
     * therefore no readings the app will plot. The DHCP-supplied server is tried
     * first where the router offers one, which is the common case on a home LAN
     * and the fastest to answer. */
    esp_sntp_setservername(0, "pool.ntp.org");
    esp_sntp_setservername(1, "time.google.com");
    esp_sntp_setservername(2, "time.cloudflare.com");
    sntp_set_time_sync_notification_cb(on_sync);
    /* Step rather than slew. Slewing from 1970 would take longer than the
     * hardware's service life, and nothing here measures intervals off the wall
     * clock - `acq` uses the monotonic timer - so a step is harmless.
     *
     * Harmless is not the same as unchecked: SNTP is unauthenticated UDP and
     * `CONFIG_LWIP_DHCP_GET_NTP_SRV` means a rogue DHCP server picks the
     * server, so ::on_sync decides what to keep. See `net_time.h`. */
    sntp_set_sync_mode(SNTP_SYNC_MODE_IMMED);
    esp_sntp_init();
    ESP_LOGI(TAG, "SNTP started; readings are held back until the clock is set");
}

bool net_time_valid(void)
{
    return s_valid;
}

int64_t net_time_epoch_us_for_uptime(int64_t uptime_us)
{
    if (!s_valid) {
        return 0;
    }
    return uptime_us + s_offset_us;
}

uint32_t net_time_syncs(void)
{
    return s_syncs;
}

uint32_t net_time_refusals(void)
{
    return s_rejected;
}
