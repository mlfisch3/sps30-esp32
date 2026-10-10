/**
 * @file net_wifi.c
 * @brief Station mode with a backoff that survives the access point leaving.
 *
 * The behaviour worth stating, because it is what the sensor's usefulness rests
 * on: **losing Wi-Fi is not an error condition here.** Acquisition keeps
 * running, the readings keep being recorded, and the station keeps trying. A
 * router rebooting must cost the captain a gap in the phone's chart, not a
 * firmware that has given up and needs a power cycle.
 *
 * The retry is exponential with a cap, driven by a one-shot `esp_timer` rather
 * than a loop in a task: the retry costs no stack while it waits, and the delay
 * is the only state it needs. Backing off matters more than it looks - an
 * unconditional reconnect every 100 ms against an AP that is switched off is a
 * busy loop holding the radio and the CPU for however long the captain is out.
 */

#include "net_wifi.h"

#include <stdio.h>
#include <string.h>

#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "nvs_flash.h"

/*
 * Deliberately not "wifi". That tag belongs to the ESP-IDF driver, whose own
 * logging is silenced below, and sharing it would silence these lines too - one
 * of which is the IP address the whole feature exists to hand over.
 */
static const char *TAG = "wifi_sta";

/**
 * The backoff schedule. First retry is immediate-ish, then doubling to half a
 * minute: fast enough that a momentary glitch is invisible, slow enough that an
 * AP that is off for an hour costs a few dozen attempts rather than 36,000.
 */
#define RETRY_FIRST_MS 1000
#define RETRY_MAX_MS 30000

#define CONNECTED_BIT BIT0

static EventGroupHandle_t s_events;
static esp_timer_handle_t s_retry_timer;
static uint32_t s_retry_delay_ms = RETRY_FIRST_MS;
static uint32_t s_disconnects;
static uint32_t s_connects;
static char s_ip[NET_WIFI_IP_LEN] = "";
static bool s_started;

static void schedule_retry(void)
{
    if (s_retry_timer == NULL) {
        return;
    }
    (void)esp_timer_stop(s_retry_timer);
    ESP_LOGI(TAG, "retrying the configured network in %u ms", (unsigned)s_retry_delay_ms);
    (void)esp_timer_start_once(s_retry_timer, (uint64_t)s_retry_delay_ms * 1000);

    s_retry_delay_ms *= 2;
    if (s_retry_delay_ms > RETRY_MAX_MS) {
        s_retry_delay_ms = RETRY_MAX_MS;
    }
}

static void retry_now(void *arg)
{
    (void)arg;
    /* esp_wifi_connect only posts to the Wi-Fi task, so calling it from the
     * timer task is cheap and cannot block here. */
    const esp_err_t err = esp_wifi_connect();
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "connect attempt failed to start: %s", esp_err_to_name(err));
        schedule_retry();
    }
}

/**
 * Wi-Fi and IP events.
 *
 * The reason codes are logged because they separate the two failures that look
 * identical from the outside: a wrong password (`AUTH_FAIL`, `HANDSHAKE_TIMEOUT`)
 * from an AP that is not there (`NO_AP_FOUND`). **The SSID is never logged**,
 * only the fact that it is the configured one.
 */
static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;

    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        ESP_LOGI(TAG, "station started; connecting to the configured network");
        retry_now(NULL);
        return;
    }

    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        const wifi_event_sta_disconnected_t *ev = (const wifi_event_sta_disconnected_t *)data;
        if (xEventGroupGetBits(s_events) & CONNECTED_BIT) {
            s_disconnects++;
            ESP_LOGW(TAG, "link lost (reason %d); acquisition keeps running",
                     ev != NULL ? ev->reason : 0);
        } else {
            ESP_LOGI(TAG, "not connected yet (reason %d)", ev != NULL ? ev->reason : 0);
        }
        xEventGroupClearBits(s_events, CONNECTED_BIT);
        s_ip[0] = '\0';
        schedule_retry();
        return;
    }

    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        const ip_event_got_ip_t *ev = (const ip_event_got_ip_t *)data;
        s_connects++;
        s_retry_delay_ms = RETRY_FIRST_MS; /* a good connection resets the backoff */
        snprintf(s_ip, sizeof(s_ip), IPSTR, IP2STR(&ev->ip_info.ip));
        xEventGroupSetBits(s_events, CONNECTED_BIT);
        ESP_LOGI(TAG, "----------------------------------------");
        ESP_LOGI(TAG, "connected. IP address: %s", s_ip);
        ESP_LOGI(TAG, "point the app at %s", s_ip);
        ESP_LOGI(TAG, "----------------------------------------");
        return;
    }

    if (base == IP_EVENT && id == IP_EVENT_STA_LOST_IP) {
        ESP_LOGW(TAG, "lease lost");
        xEventGroupClearBits(s_events, CONNECTED_BIT);
        s_ip[0] = '\0';
    }
}

esp_err_t net_stack_init(void)
{
    static bool done;
    if (done) {
        return ESP_OK;
    }

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        /* The Wi-Fi driver keeps calibration data in NVS. A partition left over
         * from a different build is not worth failing to connect over. */
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "NVS init failed: %s", esp_err_to_name(err));
        return err;
    }

    /*
     * This is what starts lwIP's TCP/IP task, and until it has run there is no
     * mailbox for a socket call to post to - `socket()` asserts rather than
     * failing, so it cannot be retried around.
     *
     * It is separate from ::net_wifi_start because the server binds the
     * wildcard address and outlives any association: a board with no
     * credentials, or one whose access point is down, still listens, and still
     * serves the same protocol over Bluetooth. Making the listener wait for a
     * network it may never get would take the whole protocol down with it.
     */
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    done = true;
    return ESP_OK;
}

esp_err_t net_wifi_start(const net_wifi_config_t *config)
{
    if (config == NULL || config->ssid == NULL || config->ssid[0] == '\0') {
        ESP_LOGE(TAG, "no credentials were built in; see firmware/README.md");
        return ESP_ERR_INVALID_ARG;
    }
    if (s_started) {
        return ESP_OK;
    }

    const esp_err_t err = net_stack_init();
    if (err != ESP_OK) {
        return err;
    }

    s_events = xEventGroupCreate();
    if (s_events == NULL) {
        return ESP_ERR_NO_MEM;
    }

    /*
     * Silence the ESP-IDF Wi-Fi driver's own INFO logging, because it prints
     * the SSID.
     *
     * This is not a hypothetical. On the first hardware run the console carried
     *
     *     I (983) wifi:connected with <SSID>, aid = 19, channel 11, ...
     *
     * which puts the captain's network name in every serial capture, every
     * pasted log and every bug report - from the driver, not from any line
     * written here. Care taken above the driver does not survive the driver
     * being chatty, so the only fix is to turn its INFO level off. Warnings and
     * errors still come through, and none of them name the network.
     */
    esp_log_level_set("wifi", ESP_LOG_WARN);

    esp_netif_t *netif = esp_netif_create_default_wifi_sta();
    if (netif != NULL && config->hostname != NULL) {
        (void)esp_netif_set_hostname(netif, config->hostname);
    }

    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));

    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                                        on_wifi_event, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, ESP_EVENT_ANY_ID,
                                                        on_wifi_event, NULL, NULL));

    const esp_timer_create_args_t timer = {
        .callback = retry_now,
        .name = "wifi_retry",
    };
    ESP_ERROR_CHECK(esp_timer_create(&timer, &s_retry_timer));

    wifi_config_t sta = {0};
    /* strncpy, not strlcpy: these fields are fixed-size byte arrays that the
     * driver reads by length, and a 32-byte SSID is legal and unterminated. */
    strncpy((char *)sta.sta.ssid, config->ssid, sizeof(sta.sta.ssid));
    if (config->password != NULL) {
        strncpy((char *)sta.sta.password, config->password, sizeof(sta.sta.password));
    }
    /* Accept anything from open upwards. Pinning a minimum here would turn a
     * router reconfigured to WPA2-only, or to WPA3, into a silent failure. */
    sta.sta.threshold.authmode = WIFI_AUTH_OPEN;
    sta.sta.pmf_cfg.capable = true;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta));
    /* Wipe the local copy the moment the driver has taken its own. It does not
     * make the secret unrecoverable - the driver has it, and so does the binary
     * - but it keeps it off this task's stack for the rest of the run. */
    memset(&sta, 0, sizeof(sta));

    ESP_ERROR_CHECK(esp_wifi_start());
    /* No modem sleep: this board is USB powered and its job is to answer a
     * socket promptly. Power saving would add up to a beacon interval of
     * latency to every packet in exchange for milliamps nothing is counting. */
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));

    s_started = true;
    return ESP_OK;
}

bool net_wifi_connected(void)
{
    return s_events != NULL && (xEventGroupGetBits(s_events) & CONNECTED_BIT) != 0;
}

bool net_wifi_wait_connected(uint32_t timeout_ms)
{
    if (s_events == NULL) {
        return false;
    }
    const EventBits_t bits = xEventGroupWaitBits(s_events, CONNECTED_BIT, pdFALSE, pdTRUE,
                                                 pdMS_TO_TICKS(timeout_ms));
    return (bits & CONNECTED_BIT) != 0;
}

void net_wifi_ip(char *out, size_t cap)
{
    if (out == NULL || cap == 0) {
        return;
    }
    snprintf(out, cap, "%s", s_ip);
}

uint32_t net_wifi_disconnects(void)
{
    return s_disconnects;
}

uint32_t net_wifi_connects(void)
{
    return s_connects;
}
