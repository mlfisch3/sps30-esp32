/**
 * @file main.c
 * @brief Bring up the SPS30, keep it measuring, and put it on the network.
 *
 * The first milestone - identify the sensor over UART2 - is met and stays here
 * as a boot-time assertion rather than being deleted: it is the cheapest check
 * that wiring, levels, baud, framing and checksums are all still right, and it
 * costs one exchange. What follows it is the acquisition state machine and the
 * transport that carries its output off the board.
 *
 * The console observer below is now one of two consumers of readings. That was
 * always the design: `acq` hands each reading to its observers and knows nothing
 * else about them, so the network layer arrived as a second observer rather than
 * as a change to any of this. Nothing in `acq` moved to accommodate it.
 *
 * ## Credentials
 *
 * `wifi_credentials.h` is generated at build time, into the build directory,
 * from files that live outside the repository - see
 * `../cmake/wifi_credentials.cmake`. **This is the only file that includes it**,
 * and the values only travel from here into the Wi-Fi driver. Nothing logs them.
 */

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "acq.h"
#include "ble_nus.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "net_budget.h"
#include "net_mdns.h"
#include "net_server.h"
#include "net_time.h"
#include "net_wifi.h"
#include "sps30.h"
#include "store.h"
#include "wifi_credentials.h"
#include "wire.h"

static const char *TAG = "main";

/* Known-good values for this sensor, from the Pi. */
#define EXPECTED_SERIAL "E3164D2544338487"
#define EXPECTED_FW_MAJOR 2
#define EXPECTED_FW_MINOR 1

/* How often to print the running summary, in readings. At 1 Hz that is a line
 * every half minute: enough to judge stability from a log, few enough that the
 * per-reading lines stay readable. */
#define SUMMARY_EVERY 30

/*
 * The two switches that make the clock something you can reason about.
 *
 * Both are 1 in every build that ships. Each is a one-character change here,
 * rather than a Kconfig entry, because the whole value of them is that the
 * measurements they enable can be taken again by the next person.
 *
 * NET_TIME_USE_SNTP 0 - do not ask the network for the time at all. The board
 *   then behaves exactly as one that has never joined Wi-Fi: it records against
 *   its own clock, names its sessions for the run, and waits for a client to
 *   hand it a time. The difference from simply building without credentials is
 *   that the board is still on the network, so the whole relative-time path can
 *   be watched over TCP with instruments attached instead of only over
 *   Bluetooth. It withholds a clock; it does not fake anything.
 *
 * NET_TIME_KEEP_SYNCING 0 - stop SNTP after the first answer, leaving the clock
 *   free-running on the crystal. That is how its drift is measured, and a clock
 *   that keeps resyncing silently corrects away the very thing being measured.
 *   See `firmware/tools/drift_probe.py`.
 */
#define NET_TIME_USE_SNTP 1
#define NET_TIME_KEEP_SYNCING 1

/** Print the wiring the firmware assumes, so a mismatch is obvious in the log. */
static void log_expected_wiring(void)
{
    ESP_LOGI(TAG, "SPS30 acquisition on UART2");
    ESP_LOGI(TAG, "expected wiring (TX and RX cross exactly once):");
    ESP_LOGI(TAG, "  SPS30 VDD (1) -> 5V     (5 V +/-10%%, NOT 3V3)");
    ESP_LOGI(TAG, "  SPS30 RX  (2) -> GPIO%d  (ESP32 TX)", SPS30_DEFAULT_TX_GPIO);
    ESP_LOGI(TAG, "  SPS30 TX  (3) -> GPIO%d  (ESP32 RX)", SPS30_DEFAULT_RX_GPIO);
    ESP_LOGI(TAG, "  SPS30 SEL (4) -> unconnected (floating selects UART)");
    ESP_LOGI(TAG, "  SPS30 GND (5) -> GND");
}

/**
 * Re-check the first milestone before acquisition takes the sensor over.
 *
 * Not fatal if it fails: the state machine's own detection loop handles an
 * absent sensor properly, with the troubleshooting order and a retry, and
 * duplicating that here would only add a way to give up early.
 */
static void check_milestone(void)
{
    char product[SPS30_STRING_LEN] = {0};
    char serial[SPS30_STRING_LEN] = {0};
    uint8_t major = 0, minor = 0;

    if (sps30_product_type(product, sizeof(product)) != ESP_OK ||
        sps30_serial_number(serial, sizeof(serial)) != ESP_OK ||
        sps30_firmware_version(&major, &minor) != ESP_OK) {
        ESP_LOGW(TAG, "identify failed at boot: %s",
                 sps30_link_fault_str(sps30_last_link_fault()));
        ESP_LOGW(TAG, "leaving it to the acquisition loop, which retries and diagnoses");
        return;
    }

    ESP_LOGI(TAG, "----------------------------------------");
    ESP_LOGI(TAG, "product type     : %s", product);
    ESP_LOGI(TAG, "serial number    : %s", serial);
    ESP_LOGI(TAG, "firmware version : %u.%u", major, minor);
    ESP_LOGI(TAG, "----------------------------------------");

    if (strcmp(serial, EXPECTED_SERIAL) == 0 && major == EXPECTED_FW_MAJOR &&
        minor == EXPECTED_FW_MINOR) {
        ESP_LOGI(TAG, "MILESTONE MET: matches the Pi (%s, firmware %u.%u).",
                 EXPECTED_SERIAL, EXPECTED_FW_MAJOR, EXPECTED_FW_MINOR);
    } else {
        ESP_LOGW(TAG, "the sensor answered but is not the unit the Pi reads:");
        ESP_LOGW(TAG, "  got %s firmware %u.%u, want %s firmware %u.%u", serial, major,
                 minor, EXPECTED_SERIAL, EXPECTED_FW_MAJOR, EXPECTED_FW_MINOR);
    }
}

/* --- the console observer ------------------------------------------------- */

/**
 * Log every reading, and a summary periodically.
 *
 * Runs on the acquisition task, so it must not block - printing is the whole of
 * it. The network transport is a second observer of exactly the shape this file
 * used to describe: it copies into an `acq_queue_t` and returns.
 */
static void on_reading(const acq_reading_t *r, void *ctx)
{
    (void)ctx;

    printf("[%6" PRIu32 "] %s PM1.0=%6.2f PM2.5=%6.2f PM4.0=%6.2f PM10=%6.2f ug/m3  "
           "NC0.5=%7.2f NC1.0=%7.2f NC2.5=%7.2f NC4.0=%7.2f NC10=%7.2f #/cm3  "
           "size=%4.2f um%s\n",
           r->sequence, (r->flags & ACQ_FLAG_SETTLING) ? "~" : " ", r->values.pm1_0,
           r->values.pm2_5, r->values.pm4_0, r->values.pm10, r->values.nc0_5,
           r->values.nc1_0, r->values.nc2_5, r->values.nc4_0, r->values.nc10,
           r->values.typical_size,
           (r->flags & ACQ_FLAG_SETTLING) ? "  (settling)" : "");

    if (r->sequence % SUMMARY_EVERY == 0) {
        acq_stats_t s;
        acq_get_stats(&s);
        net_server_stats_t net;
        net_server_get_stats(&net);
        char ip[NET_WIFI_IP_LEN];
        net_wifi_ip(ip, sizeof(ip));

        ESP_LOGI(TAG,
                 "after %" PRIu32 " readings: %" PRIu32 " not-ready, %" PRIu32
                 " read failures (%" PRIu32 " silent, %" PRIu32 " garbled), %" PRIu32
                 " implausible, %" PRIu32 " sensor losses; heap %u B free",
                 s.readings, s.not_ready, s.read_failures, s.silent_faults,
                 s.garbled_faults, s.implausible, s.sensor_losses,
                 (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT));
        store_stats_t store;
        store_get_stats(&store);
        ESP_LOGI(TAG,
                 "store: %s %" PRIu32 " readings in %" PRIu32 " pages, %" PRIu32 "/%" PRIu32
                 " slots used, %" PRIu32 " dropped, %" PRIu32 " unreadable",
                 store.ready ? "ready" : "NOT READY", store.readings_stored,
                 store.pages_written, store.slots_used, store.slots, store.readings_dropped,
                 store.decode_failures);
        ESP_LOGI(TAG,
                 "net: ip=%s clients=%" PRIu32 "/%u sent=%" PRIu32 " dropped=%" PRIu32
                 " stalled=%" PRIu32 " refused=%" PRIu32 " drops=%" PRIu32
                 " clock=%s bad_clocks=%" PRIu32 " stack_min=%" PRIu32 " B mdns=%s",
                 ip[0] != '\0' ? ip : "none", net.clients_now, (unsigned)WIRE_MAX_CLIENTS,
                 net.readings_sent, net.readings_dropped, net.stalled_out, net.refused,
                 net_wifi_disconnects(), net_time_valid() ? "set" : "unset",
                 net_time_refusals(), net.min_stack_free,
                 /* Whether the app can find this board by listening or only by
                  * sweeping - the difference between the two paths, in the one
                  * line a console capture is judged from. */
                 net_mdns_advertising() ? "on" : "off");

        ble_nus_stats_t ble;
        ble_nus_get_stats(&ble);
        ESP_LOGI(TAG,
                 "ble: name=%s connects=%" PRIu32 " served=%" PRIu32 " gone=%" PRIu32
                 " refused=%" PRIu32 " deferred=%" PRIu32 " notify_fail=%" PRIu32
                 " rx_lost=%" PRIu32 " B mtu=%u",
                 ble_nus_device_name()[0] != '\0' ? ble_nus_device_name() : "none",
                 ble.connects, ble.subscribes, ble.disconnects, ble.refused,
                 ble.notify_deferred, ble.notify_failed, ble.rx_dropped, (unsigned)ble.mtu);
    }
}

static void on_state(acq_state_t state, const char *detail, void *ctx)
{
    (void)ctx;
    ESP_LOGI(TAG, "acquisition is %s: %s", acq_state_str(state), detail);
}

/* --- the one rate reduction in this firmware ------------------------------- */

/**
 * The interval the user asked for, before any floor was put under it.
 *
 * Kept here rather than in the store because this is the only place that knows
 * the difference between "the user chose one second" and "one second is what is
 * left after a reduction". Without it, releasing a floor would have nothing to
 * release *to* and the board would stay slow for ever.
 *
 * Set from the profile every time a floor is applied and from the user's own
 * `set_profile` the rest of the time - see ::on_profile.
 */
static double s_user_interval_s = 1.0;
static uint32_t s_rate_floor_ms;

/**
 * Raise or release the recording interval because the preservation area said so.
 *
 * This is the **only** thing in this firmware that reduces the recording rate,
 * and it is reached only for pressure on the area holding preserved alert data.
 * A ring that has filled does not come here: it wraps, which is what a ring is
 * for, and reducing the rate because the ring filled is the behaviour the
 * captain considered and rejected.
 *
 * Three properties it has to have, and all three are why it lives in `main.c`
 * rather than in `store.c`:
 *
 * - **It never makes the interval shorter than the user asked for.** The floor
 *   is a floor; `max` of the two is the whole rule.
 * - **It does not touch the fan.** Only `interval_s` moves, inside continuous
 *   mode, so a reduction cannot put a spike or a settling window into the
 *   captain's data the way a mode change would. In duty cycle the wake-to-wake
 *   period is what carries the rate, so that is what is floored instead.
 * - **It is visible.** `acq_set_profile` publishes a `profile` message, and
 *   `alerts` carries `rate_floor_s`, so a board that has quietly moved to one
 *   sample a minute does not look like a board recording every second.
 *
 * Runs on the store task, which is why it only queues a profile change.
 */
static void on_rate_floor(uint32_t floor_ms, void *ctx)
{
    (void)ctx;
    s_rate_floor_ms = floor_ms;

    acq_profile_t profile;
    acq_get_profile(&profile);
    const double floor_s = (double)floor_ms / 1000.0;

    if (profile.mode == ACQ_MODE_DUTY_CYCLE) {
        const double wanted = profile.cycle_s > floor_s ? profile.cycle_s : floor_s;
        if (wanted == profile.cycle_s) {
            return;
        }
        profile.cycle_s = wanted;
    } else {
        const double wanted =
            s_user_interval_s > floor_s ? s_user_interval_s : floor_s;
        if (wanted == profile.interval_s) {
            return;
        }
        profile.interval_s = wanted;
    }
    ESP_LOGW(TAG,
             "preserved alert data is under pressure: recording every %.0f s "
             "(the user asked for %.0f s)",
             profile.mode == ACQ_MODE_DUTY_CYCLE ? profile.cycle_s : profile.interval_s,
             s_user_interval_s);
    (void)acq_set_profile(&profile);
}

/**
 * Remember what the user actually chose, so a floor can be released back to it.
 *
 * Every profile change comes through here, including the ones ::on_rate_floor
 * makes - so a change is only taken as the user's when it is not the floor
 * currently in force. Getting that test wrong in the other direction is the
 * failure that matters: it would record the reduced interval as the user's
 * choice and the board would never come back to full rate.
 */
static void on_profile(const acq_profile_t *profile, void *ctx)
{
    (void)ctx;
    if (profile == NULL || profile->mode == ACQ_MODE_DUTY_CYCLE) {
        return;
    }
    const double floor_s = (double)s_rate_floor_ms / 1000.0;
    if (s_rate_floor_ms != 0 && profile->interval_s == floor_s) {
        return; /* this is the floor talking, not the user */
    }
    s_user_interval_s = profile->interval_s;
}

/* --- networking ----------------------------------------------------------- */

/**
 * Start the protocol server and the two transports that carry it.
 *
 * Failure here is deliberately **not** fatal. A board that cannot reach the
 * access point is still a board reading the sensor, and the console log is still
 * a record; taking acquisition down because a router is rebooting would be the
 * wrong trade. The one thing that has to happen in order is that the server
 * registers its observer before `acq_start`.
 *
 * The order below matters in one respect and not in another. The **server**
 * comes first because both transports hand connections to it, and because its
 * observer must be registered before acquisition runs. **Wi-Fi and Bluetooth are
 * independent**: each starts whether or not the other did, which is what makes a
 * board with no network still worth connecting to. In particular Bluetooth is
 * outside the credentials guard on purpose - "no Wi-Fi credentials" is one of
 * the situations BLE exists for, not a reason to leave it off.
 */
static void start_transports(void)
{
    /* The zone, before anything that could learn the time. Not inside the
     * credentials guard and not inside `net_time_start`: a board with no Wi-Fi
     * can still be handed a clock by a phone over Bluetooth, and if the zone is
     * not set by then the first absolute session it records is stamped in UTC
     * while claiming to be local. */
    net_time_set_zone(NET_TIME_TZ_EASTERN);

    /* lwIP first, and not as part of Wi-Fi: the listener's `socket()` call
     * asserts rather than failing if the TCP/IP task is not up, so this cannot
     * be left to whether an access point happens to be configured. */
    ESP_ERROR_CHECK(net_stack_init());

    /* Binds the wildcard address, so it survives Wi-Fi arriving late or going
     * away, and it costs one socket when there is no network at all. */
    net_server_config_t server = NET_SERVER_DEFAULT_CONFIG();
    ESP_ERROR_CHECK(net_server_start(&server));

#if WIFI_CREDENTIALS_PRESENT
    const net_wifi_config_t wifi = {
        .ssid = WIFI_SSID,
        .password = WIFI_PASSWORD,
        /* Shows up in the router's DHCP table, so the board is findable by name
         * as well as by the address it logs. The same string mDNS answers
         * `sps30.local` with, from one definition rather than two. */
        .hostname = NET_MDNS_HOSTNAME,
    };
    const esp_err_t err = net_wifi_start(&wifi);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Wi-Fi did not start: %s; carrying on without it", esp_err_to_name(err));
    } else {
        /* Announce the server, so the app can listen for this board instead of
         * probing all 254 addresses of every local /24. Not fatal if it fails
         * and deliberately not gated on the link being up: the responder
         * attaches itself through netif events, so a board waiting for an
         * access point starts announcing when it arrives, and a board that
         * cannot announce at all is still found by the app's sweep.
         *
         * The port comes from the server's own config rather than from the
         * constant behind it, so an SRV record cannot end up pointing at a port
         * nothing is bound to. */
        const esp_err_t advert = net_mdns_start(server.port);
        if (advert != ESP_OK) {
            ESP_LOGW(TAG, "not announcing on mDNS: %s; the app's sweep still finds it",
                     esp_err_to_name(advert));
        }
#if NET_TIME_USE_SNTP
        /* The clock is started before the link is up on purpose: SNTP polls, so
         * it connects itself when the interface arrives and re-syncs after an
         * outage without anything here noticing. */
        net_time_start(NET_TIME_TZ_EASTERN, NET_TIME_KEEP_SYNCING);
#else
        ESP_LOGW(TAG, "SNTP is off in this build; the board has no clock of its own");
        ESP_LOGW(TAG, "sessions will record relative time until a client sets it");
#endif
    }
#else
    ESP_LOGW(TAG, "no Wi-Fi credentials were found at build time; Wi-Fi is off");
    ESP_LOGW(TAG, "see firmware/README.md for where they are read from");
#endif

    const esp_err_t ble = ble_nus_start();
    if (ble != ESP_OK) {
        ESP_LOGE(TAG, "Bluetooth did not start: %s; carrying on without it",
                 esp_err_to_name(ble));
    }
}

void app_main(void)
{
    log_expected_wiring();

    /* Report the figure the queue-sizing arithmetic in acq_queue.h is built on,
     * so the assumption is checkable against the board rather than trusted. */
    ESP_LOGI(TAG, "free heap at boot: %u B (%u KiB) 8-bit-capable",
             (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT),
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_8BIT) / 1024));
    ESP_LOGI(TAG, "client queue: %u readings x %u B x %u clients = %u B",
             (unsigned)ACQ_CLIENT_QUEUE_MAX, (unsigned)ACQ_READING_BYTES,
             (unsigned)ACQ_MAX_CLIENTS,
             (unsigned)(ACQ_CLIENT_QUEUE_MAX * ACQ_READING_BYTES * ACQ_MAX_CLIENTS));

    sps30_config_t sensor = SPS30_DEFAULT_CONFIG();
    ESP_ERROR_CHECK(sps30_init(&sensor));
    check_milestone();

    /* Storage before the observers, so the ring is being scanned while the
     * sensor is still being brought up rather than after it. Not fatal if it
     * fails: a board that cannot store is still a board measuring and serving,
     * and `store_start` says loudly which of the two happened - "no stored
     * sessions" must never again be indistinguishable from "storage is
     * broken". */
    if (store_start() != ESP_OK) {
        ESP_LOGE(TAG, "storage did not start; readings will not survive a reboot");
    }

    /* After store_start, so the preservation area has already worked out what
     * pressure it is under and the callback is told the truth at once rather
     * than being told zero and corrected a segment later. */
    store_set_rate_floor_cb(on_rate_floor, NULL);

    const acq_observer_t console = {
        .on_reading = on_reading,
        .on_state = on_state,
        .on_profile = on_profile,
        .ctx = NULL,
    };
    ESP_ERROR_CHECK(acq_add_observer(&console));

    /* Before acq_start: acq_add_observer is deliberately not safe to call once
     * the acquisition task is running. */
    start_transports();

    acq_config_t config = ACQ_DEFAULT_CONFIG();
    ESP_ERROR_CHECK(acq_start(&config));

    /* The figure every memory question on this board is answered against, so it
     * is measured here - after the store, the radios and acquisition have taken
     * theirs - and said with what it buys. `WIRE_MAX_CLIENTS` is sized against
     * lwIP's socket limit and is not the bound that bites; this is. */
    {
        const unsigned free_now = (unsigned)heap_caps_get_free_size(MALLOC_CAP_8BIT);
        const unsigned each = (unsigned)net_server_client_bytes();
        ESP_LOGI(TAG, "free heap once everything is up: %u B (%u KiB)", free_now,
                 free_now / 1024);
        /* The number the accept path will actually honour, reserve and all, not
         * the cheerier one that ignores it. `net_budget.h` says why there is a
         * reserve: the board once fitted a sixth client and then starved the
         * Wi-Fi driver, which dropped all six. */
        ESP_LOGI(TAG,
                 "a client costs %u B of it and %u B is kept back, so %u of the %u slots "
                 "can be filled",
                 each, (unsigned)NET_HEAP_RESERVE,
                 (unsigned)net_budget_capacity(free_now, each, NET_HEAP_RESERVE),
                 (unsigned)WIRE_MAX_CLIENTS);
    }

    /* app_main returns and its task is reclaimed; acquisition runs on its own. */
}
