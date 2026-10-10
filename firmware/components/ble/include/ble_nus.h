/**
 * @file ble_nus.h
 * @brief The same JSONL, over Bluetooth Low Energy. `BLUETOOTH_DESIGN.md`, built.
 *
 * The Raspberry Pi has served this protocol over BLE since 2026-07-24, through
 * `sps30_ble.py`, and the Android app has spoken it since the same week -
 * `apk/.../net/BleTransport.kt` scans, connects, negotiates an MTU, subscribes
 * and reassembles lines. Only the ESP32 was missing, so this is a gap being
 * filled rather than a design being invented: every UUID, the framing and the
 * discovery-by-service-UUID behaviour come from that document and from the app
 * that already implements the other end.
 *
 * ## What it is
 *
 * A GATT peripheral advertising the **Nordic UART Service**, the de-facto
 * BLE serial port:
 *
 * | | UUID | Direction |
 * |---|---|---|
 * | Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | advertised, so the app finds the board with no address to configure |
 * | RX | `6E400002-...` | write / write-no-response, phone to board: command lines |
 * | TX | `6E400003-...` | notify, board to phone: `hello`, readings, `state`, ... |
 *
 * The bytes are the **identical** newline-delimited JSONL the TCP server sends.
 * Nothing here encodes a message or answers a command: a subscribing phone is
 * handed to ::net_server_attach as a ::net_link_t and served by exactly the code
 * that serves a socket. That is the whole point, and it is why a command added
 * to `net_server.c` is answered over Bluetooth without a line being written
 * here. `list_files` and `get_file` are exactly that: on-device storage was
 * built on another branch and served over Bluetooth the moment the two met,
 * with nothing added on this side.
 *
 * ## Discovery, and why nothing needs configuring
 *
 * The app scans for the service UUID above, so a board is **found, never
 * addressed** - the same shape as the Wi-Fi path, where the app sweeps the
 * subnet for a `hello` on port 8730 rather than being told an IP. No pairing, no
 * bonding, no identifier the user has to read off the board and type in. The
 * advertised name (`SPS30-xxxxxx`, from the radio's own address) is for the
 * connection banner, not for finding anything.
 *
 * ## Independent of Wi-Fi, on purpose
 *
 * ::ble_nus_start does not consult the Wi-Fi state and is called whether or not
 * credentials were compiled in. A board deployed away from any network is the
 * case BLE exists for: `STORAGE_FORMAT.md` describes leaving one somewhere and
 * collecting it later, and Bluetooth is how it is collected.
 *
 * **One caveat, and it is not this file's to fix.** Readings carry a wall-clock
 * timestamp the app requires, and the only clock this board has is SNTP. With no
 * Wi-Fi since boot there is no clock, so `net_server.c` holds readings in the
 * queue rather than sending them unstamped - over Bluetooth exactly as over
 * TCP. `hello`, `state`, the control lock and the file commands are unaffected,
 * so **collecting stored sessions works with no network**; the live stream does
 * not. Closing that needs a time source, which is a protocol or hardware
 * decision. Note that a session stored *before* the board lost its network
 * carries its own timestamps in the page header, so it collects correctly
 * whatever the clock says now.
 *
 * ## How many phones
 *
 * One over Bluetooth, against ::WIRE_MAX_CLIENTS over Wi-Fi. RAM is the limit
 * rather than the protocol - see ::BLE_NUS_MAX_LINKS - and it is the one place
 * the two transports genuinely differ. The board simply stops advertising while
 * that one phone is connected, so a second never sees it to be disappointed.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Concurrent Bluetooth clients. One.
 *
 * Two was the intent and it did not survive contact with the part. Enabling
 * Bluetooth already takes about 56 KiB out of `dram0_0_seg`, and each extra
 * connection the controller is built for costs more on top of that, on a board
 * that also runs Wi-Fi, a flash ring and eight client slots. One is also what
 * `CLAUDE.md` said the trade would be from the start: "Wi-Fi gives many
 * viewers, Bluetooth gives one".
 *
 * Two consequences worth knowing, both observed:
 *
 * - **The board stops advertising while a phone is connected**, because there
 *   is no slot for whoever might answer. A second phone does not see it at all
 *   rather than seeing it and failing, which is the better of the two.
 * - Building NimBLE for one connection and asking it to advertise anyway
 *   answers `BLE_HS_ENOMEM`. That error was in the log for a while precisely
 *   because this constant and the build disagreed.
 *
 * **Must equal `CONFIG_BT_NIMBLE_MAX_CONNECTIONS` in `sdkconfig.defaults`.**
 * They drifted apart once already and the symptom was a log line nobody could
 * account for.
 */
#define BLE_NUS_MAX_LINKS 1

/** @brief Counters, for judging a run the way ::net_server_stats_t does. */
typedef struct {
    uint32_t connects;      /**< GAP connections established */
    uint32_t subscribes;    /**< connections that enabled notifications and were served */
    uint32_t disconnects;   /**< connections ended, for any reason */
    uint32_t refused;       /**< subscribers the controller had no client slot for */
    /**
     * Notifications the host stack could not take **yet** - no buffers - which
     * the caller then retried. Normal, and large during a bulk transfer: it is
     * what backpressure looks like from here, not loss.
     */
    uint32_t notify_deferred;
    /** Notifications that failed for a reason retrying will not fix. */
    uint32_t notify_failed;
    uint32_t rx_dropped;    /**< command bytes lost to a full inbound buffer */
    uint16_t mtu;           /**< the most recently negotiated ATT MTU */
} ble_nus_stats_t;

/**
 * @brief Bring up the controller, the host, the service and advertising.
 *
 * Safe to call before or after ::net_server_start; a phone that subscribes
 * before the server is up is refused rather than served, and it retries. Does
 * not need Wi-Fi, an IP address or a clock.
 *
 * @return ESP_OK once the NimBLE host task is running. Advertising starts a
 *         moment later, when the controller reports sync.
 */
esp_err_t ble_nus_start(void);

/** @brief Copy out the counters. @p out must not be NULL. */
void ble_nus_get_stats(ble_nus_stats_t *out);

/** @brief The advertised name, e.g. "SPS30-c24ae8". Empty until the host syncs. */
const char *ble_nus_device_name(void);

#ifdef __cplusplus
}
#endif
