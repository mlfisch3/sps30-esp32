/**
 * @file net_mdns.c
 * @brief The ESP-IDF half of the advertisement: register what
 *        `net_mdns_record.c` says, and say so in the log.
 *
 * mDNS is not part of ESP-IDF v5.3 - it was moved out of the tree into the
 * component registry - so `espressif/mdns` arrives through
 * `components/net/idf_component.yml` and is pinned there. `mdns.h` below comes
 * from that component, not from the IDF.
 *
 * The whole file is deliberately thin. Nothing here decides what to advertise;
 * it takes the record from the pure translation unit next door and hands it to
 * the responder, which is what lets the interesting half be tested on a host.
 */

#include "net_mdns.h"

#include <string.h>

#include "esp_log.h"
#include "mdns.h"

static const char *TAG = "mdns";

static bool s_advertising;

esp_err_t net_mdns_start(uint16_t port)
{
    if (s_advertising) {
        return ESP_OK;
    }

    esp_err_t err = mdns_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "the responder did not start: %s", esp_err_to_name(err));
        return err;
    }

    /* The A record, so the SRV record below has a name to point at. The same
     * string the DHCP request uses - see NET_MDNS_HOSTNAME - so a board is
     * `sps30.local` and `sps30` in the router's table for one reason rather
     * than two. */
    err = mdns_hostname_set(NET_MDNS_HOSTNAME);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "hostname: %s", esp_err_to_name(err));
        return err;
    }

    net_mdns_txt_t txt[NET_MDNS_TXT_COUNT];
    const size_t count = net_mdns_txt_records(txt, NET_MDNS_TXT_COUNT);
    if (count != NET_MDNS_TXT_COUNT) {
        /* Unreachable unless the two constants disagree, which the host pack
         * would have caught; treated as an error rather than advertising a
         * partial record set, because half an advertisement is a different
         * advertisement. */
        ESP_LOGE(TAG, "the TXT record did not build");
        return ESP_ERR_INVALID_SIZE;
    }

    /* mdns_txt_item_t is the same two const pointers, but it is the component's
     * type and this file is the only place the two meet. */
    mdns_txt_item_t items[NET_MDNS_TXT_COUNT];
    for (size_t i = 0; i < count; i++) {
        items[i].key = txt[i].key;
        items[i].value = txt[i].value;
    }

    err = mdns_service_add(NET_MDNS_INSTANCE, NET_MDNS_SERVICE, NET_MDNS_PROTO, port, items,
                           count);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "the service was not registered: %s", esp_err_to_name(err));
        return err;
    }

    s_advertising = true;

    /* The log line and the test assert against the same function, so what the
     * console claims is advertised is what the host pack checked. */
    char described[NET_MDNS_DESCRIBE_LEN];
    if (net_mdns_describe(described, sizeof(described), port) > 0) {
        ESP_LOGI(TAG, "----------------------------------------");
        ESP_LOGI(TAG, "advertising %s", described);
        ESP_LOGI(TAG, "the app browses for %s.%s; there is nothing to configure",
                 NET_MDNS_SERVICE, NET_MDNS_PROTO);
        ESP_LOGI(TAG, "----------------------------------------");
    }
    return ESP_OK;
}

bool net_mdns_advertising(void)
{
    return s_advertising;
}
